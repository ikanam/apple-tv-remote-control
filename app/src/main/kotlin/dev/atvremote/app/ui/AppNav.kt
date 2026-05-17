package dev.atvremote.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.atvremote.app.conn.MulticastLockHolder
import dev.atvremote.app.conn.UiConnectionState
import dev.atvremote.app.ui.connect.ConnectScreen
import dev.atvremote.app.ui.remote.RemoteScreen
import dev.atvremote.app.ui.theme.AtvRemoteTheme
import dev.atvremote.app.vm.DiscoveredDevice
import dev.atvremote.app.vm.DiscoveryViewModel
import dev.atvremote.app.vm.KeyboardViewModel
import dev.atvremote.app.vm.PairingUiState
import dev.atvremote.app.vm.RemoteViewModel

/**
 * Navigation destinations for the Apple TV remote app.
 *
 * Restructured by the Claude-Design reskin (spec
 * `docs/superpowers/specs/2026-05-17-claude-design-ui-reskin.md`, Screen 3).
 * The old `[HERO, DEVICES, PAIR, KEYBOARD, TUNING]` model is superseded:
 *  - [REMOTE] — the `RemoteScreen`; the KeyboardOverlay and the device-switcher
 *    chip are *overlays/secondary* within it (T3), not separate destinations,
 *    so the old `KEYBOARD` auto-route is gone (RemoteScreen auto-shows the
 *    overlay on `KeyboardViewModel.state.visible` itself).
 *  - [CONNECT] — the `ConnectScreen`, in first-run OR switcher-overlay mode;
 *    the `PairingSheet` is an in-screen overlay within it (T4b).
 *  - [TUNING] — the debug `SwipeTuningScreen`, reached only from the Connect
 *    settings gear.
 */
enum class AppDestinations(val route: String) {
    REMOTE("remote"),
    CONNECT("connect"),
    TUNING("tuning"),
}

/**
 * S5 first-run initial-routing rule (spec §2). Factored out as a pure function
 * so the decision is unit-testable without Robolectric.
 *
 * [hasReconnectableLast] is true iff there is a persisted last device AND it
 * still has stored credentials (computed in MainActivity from
 * `CredentialStore.lastDevice()` + `CredentialStore.load(id)`). When true the
 * app starts on REMOTE: the persisted device exists and an auto-reconnect has
 * been requested (it is a `serviceScope.launch{}`, so the first composition
 * may commit before connect actually starts — the AppNav banner reflects
 * Connecting/Connected/Reconnecting as it progresses, and a terminal
 * CredentialInvalid/Failed shows the banner so the user opens Connect to
 * re-pair). When false (fresh install / creds cleared) the app starts on
 * CONNECT so the user can discover & pair instead of landing on a dead REMOTE.
 */
internal fun initialDestination(hasReconnectableLast: Boolean): AppDestinations =
    if (hasReconnectableLast) AppDestinations.REMOTE else AppDestinations.CONNECT

/**
 * A navigation request hoisted from MainActivity (which owns the async
 * pair/connect decisions) to AppNav (which owns `dest`). [seq] makes two
 * consecutive requests for the SAME [dest] distinct so the applying
 * `LaunchedEffect` re-fires (e.g. pair completes → REMOTE, user opens CONNECT,
 * a second pair completes → REMOTE again). MainActivity increments [seq] per
 * request; equality therefore changes even when [dest] repeats.
 */
data class NavRequest(val dest: AppDestinations, val seq: Int)

/**
 * Carries the CONNECT screen's *mode* across a nav request (the only piece of
 * state that must survive the hoisted-signal hop besides [NavRequest.dest]).
 *
 * - `null` ⇒ CONNECT renders in **first-run mode**: `currentId = null`,
 *   `onClose = null` (no back button, STEP-01 hero). This is the auto/
 *   first-run/post-cancel path.
 * - non-null ⇒ CONNECT renders in **switcher-overlay mode**: reached from the
 *   Remote device-switcher chip; `currentId` = the connected device's id (its
 *   card shows the `CURRENT` badge + accent border), and `onClose` is an
 *   in-composition `{ connectMode = null; dest = REMOTE }` so back / tapping
 *   the current device returns to the remote (it does NOT go through the
 *   hoisted MainActivity nav signal). Selecting a *different* device still
 *   goes through the normal `onSelectDevice` (S5 connect-or-pair) path.
 */
@JvmInline
value class ConnectMode(val currentId: String?)

/**
 * Top-level navigation (Claude-Design reskin, spec Screen 3): [REMOTE] is home;
 * the Remote device-switcher chip opens [CONNECT] in switcher-overlay mode; the
 * Connect settings gear opens [TUNING]; on a fresh/cleared install the app
 * starts on [CONNECT] in first-run mode (see [initialDestination]).
 *
 * The keyboard is the RemoteScreen in-screen overlay (T3) — there is no
 * KEYBOARD destination and no kb.visible auto-route here (RemoteScreen
 * auto-shows the overlay on `KeyboardViewModel.state.visible` itself). The
 * `PairingSheet` is a CONNECT-internal overlay (T4b) — there is no PAIR
 * destination; MainActivity drives the pairing state in via [pairingState].
 *
 * S5 connection logic is preserved verbatim: AppNav still solely owns `dest`;
 * MainActivity drives navigation for its async pair/connect decisions via the
 * hoisted [requestedDestination] signal (a `LaunchedEffect` applies it to
 * `dest` whenever MainActivity sets one). [initialDevices] picks the start
 * destination.
 *
 * **Switcher-overlay state threading.** CONNECT has two modes (first-run vs
 * switcher-overlay). The mode is local to AppNav and not part of the
 * MainActivity-owned nav signal, so it is tracked here in a hoisted
 * [ConnectMode]? (`connectMode`):
 *  - The Remote chip's `onSwitchDevice` sets `dest = CONNECT` AND
 *    `connectMode = ConnectMode(currentId = connectedDeviceId)` in the same
 *    in-composition handler (analogous to the old `dest = DEVICES`).
 *  - Any MainActivity-driven [requestedDestination] (auto-reconnect success,
 *    select→pair, pair Completed, Cancel) clears `connectMode` to null →
 *    first-run mode. This keeps the connection state machine in MainActivity
 *    untouched: MainActivity only ever asks for a *destination*, never a mode.
 *
 * The Wi-Fi multicast lock ([multicastLock], spec §4) is held by a
 * `DisposableEffect` ONLY while CONNECT is shown (discovery lives there now).
 * [wifiInfo] supplies the real (or degraded-null) `(ssid, localIp)` Wi-Fi info
 * from MainActivity's `WifiStatus`. It is a lambda (not eagerly-evaluated
 * `String?` params) deliberately: each call is a main-thread `WifiManager`
 * binder round-trip, so AppNav invokes it exactly ONCE per CONNECT entry
 * (`remember` keyed to the CONNECT branch) — never on every recomposition /
 * trackpad-drag / banner frame. Wi-Fi rarely changes within a single Connect
 * visit and the value is purely informational, so once-per-visit is correct.
 * [connectedDeviceId] is the currently-connected device id (used to seed
 * switcher-overlay `currentId`).
 */
@Composable
fun AppNav(
    discoveryVm: DiscoveryViewModel,
    remoteVm: RemoteViewModel,
    keyboardVm: KeyboardViewModel,
    connectionState: UiConnectionState,
    deviceName: String,
    connectedDeviceId: String?,
    pairingDeviceName: String?,
    initialDevices: Boolean,
    onSelectDevice: (DiscoveredDevice) -> Unit,
    pairingState: PairingUiState?,
    onSubmitPin: (String) -> Unit,
    onPairCancel: () -> Unit,
    requestedDestination: NavRequest?,
    multicastLock: MulticastLockHolder,
    haptics: dev.atvremote.app.haptics.Haptics?,
    keyboardProbe: suspend () -> String,
    wifiInfo: () -> Pair<String?, String?> = { null to null },
) {
    AtvRemoteTheme {
        var dest by remember {
            mutableStateOf(initialDestination(hasReconnectableLast = !initialDevices))
        }
        // CONNECT mode: null = first-run (no close/currentId), non-null =
        // switcher overlay (reached from the Remote chip). Owned by AppNav,
        // NOT part of the MainActivity nav signal — any MainActivity-driven
        // requestedDestination resets it to first-run (see KDoc).
        //
        // Note the double-optional: the OUTER `ConnectMode?` being `null`
        // means "not switcher" (first-run); a NON-null `ConnectMode` means
        // "switcher overlay", and its inner `currentId` may *itself* be null
        // (chip opened while somehow not connected) — distinct from the outer
        // null. So: outer null ⇒ first-run; outer non-null ⇒ switcher (inner
        // currentId null just means no card gets the CURRENT badge).
        var connectMode by remember { mutableStateOf<ConnectMode?>(null) }
        val disc by discoveryVm.state.collectAsState()

        // MainActivity's async pair/connect/back decisions navigate via this
        // hoisted signal — AppNav still owns `dest`, this only applies the
        // requested destination when MainActivity sets one. A MainActivity nav
        // is always a non-overlay (first-run) CONNECT — only the in-composition
        // Remote chip sets switcher-overlay mode.
        LaunchedEffect(requestedDestination) {
            requestedDestination?.let {
                dest = it.dest
                connectMode = null
            }
        }

        // System back: TUNING and switcher-overlay CONNECT are sub-screens —
        // pop them here instead of letting the Activity finish (the nav is a
        // single `dest` state with no back stack, so an unhandled back exits
        // the app). First-run CONNECT and REMOTE are roots → handler disabled,
        // default (exit) behavior preserved. Both sub-screens are entered FROM
        // REMOTE (settings gear / device-switcher chip), so back → REMOTE.
        BackHandler(
            enabled = dest == AppDestinations.TUNING ||
                (dest == AppDestinations.CONNECT && connectMode != null),
        ) {
            connectMode = null
            dest = AppDestinations.REMOTE
        }

        val banner = when (connectionState) {
            is UiConnectionState.Reconnecting -> "Reconnecting…"
            is UiConnectionState.Connecting -> "Connecting…"
            is UiConnectionState.CredentialInvalid ->
                "Pairing expired — please re-pair this Apple TV"
            is UiConnectionState.Failed -> "Connection failed"
            else -> null
        }

        // Immersive edge-to-edge: MainActivity calls enableEdgeToEdge() and
        // themes.xml makes the system bars transparent. Each screen draws its
        // OWN background full-bleed (so the screen's color/gradient flows
        // behind the transparent status bar) and applies statusBarsPadding()
        // to its *content* only — so there is no flat themed band, the bar is
        // truly immersive, yet content never sits under it. This wrapper is a
        // plain pass-through (no bg, no inset) so it can't create a seam.
        Box(modifier = Modifier.fillMaxSize()) {
        when (dest) {
            // HeroScreen (+ Trackpad/DpadRow/ButtonRow) is replaced by the
            // Claude-Design RemoteScreen. KeyboardOverlay + the device-switcher
            // chip are RemoteScreen-internal overlays (T3), NOT separate
            // destinations. The chip → CONNECT in switcher-overlay mode with
            // currentId = the connected device id.
            AppDestinations.REMOTE -> RemoteScreen(
                remoteVm = remoteVm,
                keyboardVm = keyboardVm,
                deviceName = deviceName,
                onSwitchDevice = {
                    connectMode = ConnectMode(currentId = connectedDeviceId)
                    dest = AppDestinations.CONNECT
                },
                onOpenSettings = { dest = AppDestinations.TUNING },
                tuning = dev.atvremote.app.swipe.SwipeTuning.DEFAULT,
                haptics = haptics,
                keyboardProbe = keyboardProbe,
                connectionBanner = banner,
            )
            AppDestinations.CONNECT -> {
                // §4: hold the Wi-Fi multicast lock ONLY while CONNECT is shown
                // (battery-correct). Discovery (DiscoveryViewModel already
                // collecting) then actually receives mDNS. Auto-reconnect uses
                // the persisted device → needs no discovery → no lock. (Moved
                // here from the old DEVICES branch — discovery now lives in
                // CONNECT.)
                DisposableEffect(Unit) {
                    multicastLock.acquire()
                    onDispose { multicastLock.release() }
                }
                val overlay = connectMode
                ConnectScreen(
                    devices = disc.devices,
                    scanning = disc.scanning,
                    onSelectDevice = { onSelectDevice(it) },
                    pairingState = pairingState,
                    onSubmitPin = onSubmitPin,
                    onPairCancel = onPairCancel,
                    pairingDeviceName = pairingDeviceName,
                    // First-run mode: currentId/onClose null. Switcher-overlay
                    // mode (from the Remote chip): currentId = connected id,
                    // onClose → back to REMOTE (also fired when the user taps
                    // the current device card — ConnectScreen routes that to
                    // onClose internally).
                    currentId = overlay?.currentId,
                    onClose = if (overlay != null) {
                        { connectMode = null; dest = AppDestinations.REMOTE }
                    } else {
                        null
                    },
                    onManualAdd = { d -> onSelectDevice(DiscoveredDevice(d, paired = false)) },
                )
            }
            AppDestinations.TUNING -> dev.atvremote.app.ui.tuning.SwipeTuningScreen()
        }
        }
    }
}
