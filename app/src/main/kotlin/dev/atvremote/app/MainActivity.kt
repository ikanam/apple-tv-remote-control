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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import dev.atvremote.app.conn.ConnectionManager
import dev.atvremote.app.conn.ConnectionService
import dev.atvremote.app.conn.UiConnectionState
import dev.atvremote.app.ui.AppNav
import dev.atvremote.app.vm.DiscoveryViewModel
import dev.atvremote.app.vm.KeyboardViewModel
import dev.atvremote.app.vm.RemoteViewModel
import dev.atvremote.protocol.AppleTvRemote
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private var manager: ConnectionManager? = null
    private val ready = MutableStateFlow(false)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            manager = (binder as ConnectionService.LocalBinder).manager()
            ready.value = true
        }
        override fun onServiceDisconnected(name: ComponentName?) { manager = null }
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
            val cm = manager ?: return@setContent
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

            AppNav(
                discoveryVm = discoveryVm,
                remoteVm = remoteVm,
                keyboardVm = keyboardVm,
                connectionState = ui,
                deviceName = (ui as? UiConnectionState.Connected)?.device?.name ?: "Apple TV",
                onSelectDevice = { id ->
                    lifecycleScope.launchWhenStarted {
                        val blob = graph.credentialStore.load(id) ?: return@launchWhenStarted
                        val creds = dev.atvremote.protocol.HapCredentials.parse(blob)
                        val device = (ui as? UiConnectionState.Connected)?.device
                            ?: discoveryVm.state.value.devices
                                .firstOrNull { it.device.id == id }?.device
                            ?: return@launchWhenStarted
                        cm.connect(device, creds)
                    }
                },
                pairingState = null,
                onSubmitPin = {},
                haptics = graph.haptics,
                keyboardProbe = { cm.currentSession()?.textGet() ?: "" },
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unbindService(conn) }
    }
}
