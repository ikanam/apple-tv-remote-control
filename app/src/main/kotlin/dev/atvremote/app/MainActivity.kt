package dev.atvremote.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.atvremote.app.conn.ConnectionManager
import dev.atvremote.app.conn.ConnectionService
import dev.atvremote.app.conn.UiConnectionState
import dev.atvremote.app.ui.AppDestinations
import dev.atvremote.app.ui.AppNav
import dev.atvremote.app.ui.NavRequest
import dev.atvremote.app.vm.DiscoveredDevice
import dev.atvremote.app.vm.DiscoveryViewModel
import dev.atvremote.app.vm.KeyboardViewModel
import dev.atvremote.app.vm.PairingUiState
import dev.atvremote.app.vm.PairingViewModel
import dev.atvremote.app.vm.RemoteViewModel
import dev.atvremote.protocol.AppleTvDevice
import dev.atvremote.protocol.AppleTvRemote
import dev.atvremote.protocol.HapCredentials
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * I3: a pairing-in-flight bound to the EXACT device its [PairingViewModel] was
 * created for. The post-pair connect must use [device] (and load creds by
 * `device.id`), NOT the mutable `pendingSelect` — a mid-pair re-select would
 * otherwise make the old pairing's Completed connect the wrong device with
 * mismatched creds (silent pair loss). `pendingSelect` reverts to being ONLY
 * the "a tap happened, run the suspend load/branch" trigger.
 */
private data class ActivePairing(
    val vm: PairingViewModel,
    val device: AppleTvDevice,
)

class MainActivity : ComponentActivity() {
    private var binder: ConnectionService.LocalBinder? = null
    private val ready = MutableStateFlow(false)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            binder = b as ConnectionService.LocalBinder
            ready.value = true
        }
        override fun onServiceDisconnected(name: ComponentName?) { binder = null }
    }

    /**
     * Issues a connect on the Service-lifetime scope (S3) — but ONLY when not
     * already Connecting/Connected/Reconnecting (S3-review double-connect
     * guard): re-launching while a connect is in flight or a session is live
     * would double-supervise. Idle/Failed/CredentialInvalid are the only
     * states from which a fresh connect is correct.
     */
    private fun launchConnectGuarded(
        cm: ConnectionManager,
        device: AppleTvDevice,
        creds: HapCredentials,
    ) {
        when (cm.uiState.value) {
            is UiConnectionState.Idle,
            is UiConnectionState.Failed,
            is UiConnectionState.CredentialInvalid -> binder?.launchConnect(device, creds)
            else -> Unit // Connecting/Connected/Reconnecting → do not re-launch
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val svc = Intent(this, ConnectionService::class.java)
        startForegroundService(svc)
        bindService(svc, conn, Context.BIND_AUTO_CREATE)

        val graph = (application as AtvRemoteApp).graph
        setContent {
            val isReady by ready.collectAsState()
            if (!isReady) return@setContent
            val cm = binder?.manager() ?: return@setContent
            val ui by cm.uiState.collectAsState()

            val discoveryVm = DiscoveryViewModel(
                discovery = AppleTvRemote.discovery(),
                pairedDeviceIds = { graph.credentialStore.allDeviceIds().toSet() },
            )
            val remoteVm = RemoteViewModel(
                sessionProvider = { cm.currentSession() },
                isConnected = { cm.uiState.value is UiConnectionState.Connected },
                onTap = { graph.haptics.tap() },
                onEdge = { graph.haptics.edgeStep() },
                onSelect = { graph.haptics.select() },
            )
            val keyboardVm = KeyboardViewModel { cm.currentSession() }

            // Hoisted nav signal: AppNav still owns `dest`; MainActivity's
            // async pair/connect decisions request a destination here and
            // AppNav's LaunchedEffect applies it. The seq counter makes
            // repeated same-destination requests distinct so the effect
            // re-fires (see NavRequest KDoc). M2: a single nav state — the
            // seq is a plain non-state holder bumped on each request (no
            // separate mutableStateOf needed; only NavRequest is observed).
            val navSeq = remember { intArrayOf(0) }
            var requestedDestination by remember { mutableStateOf<NavRequest?>(null) }
            fun navigateTo(d: AppDestinations) {
                navSeq[0] += 1
                requestedDestination = NavRequest(d, navSeq[0])
            }
            // I3: the active pairing is the VM + the device it was created for
            // (post-pair connect binds to THIS device, never pendingSelect).
            var pairing by remember { mutableStateOf<ActivePairing?>(null) }
            // The device tapped in Devices, awaiting an async paired-check.
            // I3: ONLY the load/branch trigger — NOT the post-pair connect
            // context.
            var pendingSelect by remember { mutableStateOf<DiscoveredDevice?>(null) }

            // null until lastDevice() (suspend) resolves — gate AppNav render
            // so we never flash DEVICES then jump to HERO (mirrors the
            // isReady/cm gate above).
            var initialDevices by remember { mutableStateOf<Boolean?>(null) }

            // §2 launch auto-reconnect (once per cm): use the persisted FULL
            // device (host/port) so NO discovery is needed.
            LaunchedEffect(cm) {
                val last = graph.credentialStore.lastDevice()
                val blob = last?.let { graph.credentialStore.load(it.id) }
                if (last != null && blob != null) {
                    initialDevices = false
                    launchConnectGuarded(cm, last, HapCredentials.parse(blob))
                } else {
                    initialDevices = true
                }
            }

            // §3 Devices→(connect | pair): a selected device's paired-check is
            // a suspend load; do it in an effect (no lifecycleScope).
            LaunchedEffect(pendingSelect) {
                val dd = pendingSelect ?: return@LaunchedEffect
                val device = dd.device
                val existing = graph.credentialStore.load(device.id)
                if (existing != null) {
                    launchConnectGuarded(cm, device, HapCredentials.parse(existing))
                    graph.credentialStore.saveLastDevice(device)
                    navigateTo(AppDestinations.REMOTE)
                    pendingSelect = null
                } else {
                    // I2: a re-select of a different device while a stale
                    // pairing exists must cancel the previous VM first (no
                    // leaked state collector).
                    pairing?.vm?.cancel()
                    pairing = ActivePairing(
                        vm = PairingViewModel(
                            deviceId = device.id,
                            handle = AppleTvRemote.pair(device),
                            // T9-review: a throwing persist cancels the VM's
                            // state collector — it MUST NOT throw.
                            persist = { id, b ->
                                runCatching {
                                    graph.credentialStore.save(id, b)
                                    graph.credentialStore.saveLastDevice(device)
                                }
                                Unit
                            },
                        ),
                        device = device,
                    )
                    // Pairing is the PairingSheet overlay *inside* CONNECT
                    // (T4b) — no separate PAIR destination. MainActivity drives
                    // pairingState in; AppNav renders the sheet over CONNECT.
                    navigateTo(AppDestinations.CONNECT)
                }
            }

            // I1: unconditional collectAsState on a stable flow — avoids the
            // conditional-composable slot-churn anti-pattern (I1). The flow is
            // the VM's state when a pairing is active, else a remembered empty
            // MutableStateFlow; collectAsState is ALWAYS called exactly once.
            val pairingVm = pairing?.vm
            val emptyPairingFlow = remember { MutableStateFlow<PairingUiState?>(null) }
            val pairingState: PairingUiState? by
                (pairingVm?.state ?: emptyPairingFlow)
                    .collectAsState(initial = null)

            // §3 pairing terminal-state table (I2 — explicit & symmetric, so
            // neither `pairing` nor `pendingSelect` can stay set without the
            // PairingSheet showing over CONNECT):
            //   Completed → connect (creds loaded by the bound device, I3) +
            //               saveLastDevice + clear pairing/pendingSelect +
            //               REMOTE.
            //   Failed    → STAY on the PairingSheet's Failed branch (it shows
            //               the reason + 取消). Do NOT auto-navigate/auto-clear
            //               — the user must see the reason; teardown is the
            //               single reliable onPairCancel (取消) path below,
            //               which cancels the VM + clears pairing/pendingSelect
            //               (the sheet then closes — CONNECT stays).
            //   Cancel (onPairCancel) → cancel VM + clear (sheet closes).
            // A mid-pair re-select of a different device cancels the previous
            // VM where the new ActivePairing is built (above).
            LaunchedEffect(pairing, pairingState) {
                val active = pairing ?: return@LaunchedEffect
                if (pairingState is PairingUiState.Completed) {
                    // I3: connect the device the VM was created for (bound at
                    // pair creation), loading creds by THAT device's id —
                    // independent of whatever pendingSelect now is.
                    val device = active.device
                    val blob = graph.credentialStore.load(device.id)
                    if (blob != null) {
                        launchConnectGuarded(cm, device, HapCredentials.parse(blob))
                        graph.credentialStore.saveLastDevice(device)
                    }
                    pairing = null
                    pendingSelect = null
                    navigateTo(AppDestinations.REMOTE)
                }
                // Failed: intentionally no-op here — stay on the PairingSheet
                // Failed branch. onPairCancel (取消) is the sole teardown.
            }

            val initial = initialDevices ?: return@setContent
            AppNav(
                discoveryVm = discoveryVm,
                remoteVm = remoteVm,
                keyboardVm = keyboardVm,
                connectionState = ui,
                deviceName = (ui as? UiConnectionState.Connected)?.device?.name ?: "Apple TV",
                // Connected device id seeds the switcher-overlay `currentId`
                // when the Remote chip opens CONNECT (null when not connected).
                connectedDeviceId = (ui as? UiConnectionState.Connected)?.device?.id,
                // The 22sp PairingSheet title — the device the active pairing
                // VM was bound to (I3), else null (sheet falls back to mode
                // title; only shown while pairingState != null anyway).
                pairingDeviceName = pairing?.device?.name,
                initialDevices = initial,
                onSelectDevice = { dd -> pendingSelect = dd },
                pairingState = pairingState,
                onSubmitPin = { pairing?.vm?.submitPin(it) },
                // I2: 取消 is the single reliable teardown for BOTH the Failed
                // branch and an in-progress cancel — cancel the VM, clear
                // pairing + pendingSelect. The PairingSheet closes because
                // pairingState then becomes null; CONNECT stays (no nav).
                onPairCancel = {
                    pairing?.vm?.cancel()
                    pairing = null
                    pendingSelect = null
                },
                requestedDestination = requestedDestination,
                multicastLock = graph.multicastLock,
                haptics = graph.haptics,
                keyboardProbe = { cm.currentSession()?.textGet() ?: "" },
                // Real (or degraded-null) Wi-Fi info for the status pill.
                // Passed as a lambda — NOT evaluated here. Each WifiStatus call
                // is a main-thread WifiManager binder round-trip; evaluating it
                // in this per-composition setContent body would re-hit the
                // binder on every recomposition (every trackpad-drag / banner
                // frame) though it is only consumed in AppNav's CONNECT branch.
                // AppNav invokes this exactly once per CONNECT entry.
                wifiInfo = { graph.wifiStatus.ssid() to graph.wifiStatus.localIpv4() },
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unbindService(conn) }
    }
}
