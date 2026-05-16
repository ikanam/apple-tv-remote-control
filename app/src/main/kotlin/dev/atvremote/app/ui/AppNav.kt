package dev.atvremote.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.atvremote.app.conn.MulticastLockHolder
import dev.atvremote.app.conn.UiConnectionState
import dev.atvremote.app.ui.devices.DevicesScreen
import dev.atvremote.app.ui.hero.HeroScreen
import dev.atvremote.app.ui.keyboard.KeyboardScreen
import dev.atvremote.app.ui.pair.PairScreen
import dev.atvremote.app.ui.theme.AtvRemoteTheme
import dev.atvremote.app.vm.DiscoveredDevice
import dev.atvremote.app.vm.DiscoveryViewModel
import dev.atvremote.app.vm.KeyboardViewModel
import dev.atvremote.app.vm.PairingUiState
import dev.atvremote.app.vm.RemoteViewModel

/**
 * Navigation destinations for the Apple TV remote app.
 *
 * The launcher destination is intentionally removed per spec 2026-05-16
 * (Plan-3 Amendment A). The app-launcher subsystem is not included in Plan-3.
 *
 * The NavHost composable wiring over these five destinations is built in
 * base Task 15 (amended). This enum is the sole content of this file until
 * that task adds the NavHost + screen composables.
 */
enum class AppDestinations(val route: String) {
    HERO("hero"),
    DEVICES("devices"),
    PAIR("pair"),
    KEYBOARD("keyboard"),
    TUNING("tuning"),
}

/**
 * S5 first-run initial-routing rule (spec §2). Factored out as a pure function
 * so the decision is unit-testable without Robolectric.
 *
 * [hasReconnectableLast] is true iff there is a persisted last device AND it
 * still has stored credentials (computed in MainActivity from
 * `CredentialStore.lastDevice()` + `CredentialStore.load(id)`). When true the
 * app starts on HERO: the persisted device exists and an auto-reconnect has
 * been requested (it is a `serviceScope.launch{}`, so the first composition
 * may commit before connect actually starts — the AppNav banner reflects
 * Connecting/Connected/Reconnecting as it progresses, and a terminal
 * CredentialInvalid/Failed shows the banner so the user opens Devices to
 * re-pair). When false (fresh install / creds cleared) the app starts on
 * DEVICES so the user can discover & pair instead of landing on a dead HERO.
 */
internal fun initialDestination(hasReconnectableLast: Boolean): AppDestinations =
    if (hasReconnectableLast) AppDestinations.HERO else AppDestinations.DEVICES

/**
 * A navigation request hoisted from MainActivity (which owns the async
 * pair/connect decisions) to AppNav (which owns `dest`). [seq] makes two
 * consecutive requests for the SAME [dest] distinct so the applying
 * `LaunchedEffect` re-fires (e.g. pair completes → HERO, user opens Devices,
 * a second pair completes → HERO again). MainActivity increments [seq] per
 * request; equality therefore changes even when [dest] repeats.
 */
data class NavRequest(val dest: AppDestinations, val seq: Int)

/**
 * Top-level navigation (spec §5 nav structure): Hero is home; top-bar device
 * button -> Devices/Pair; "More" -> Tuning (debug/settings); focused text
 * field auto-routes to Keyboard.
 *
 * Reconciled per the Plan-3 remote-layout amendment (R2): the base Task-15
 * `enum class Dest{...Launcher...}` is replaced by the Amendment-A
 * [AppDestinations] enum (no Launcher); `LauncherViewModel`/`AppLauncherScreen`/
 * `HeroCallbacks` are dropped (the amended [HeroScreen] takes flat lambdas, no
 * `HeroCallbacks`); `haptics`/`keyboardProbe` are threaded through to wire the
 * amended Hero.
 *
 * S5 wires the real Devices->Pair->connect + first-run/auto-reconnect flow.
 * AppNav still solely owns `dest`; MainActivity drives navigation for its
 * async pair/connect decisions via the hoisted [requestedDestination] signal
 * (a `LaunchedEffect` applies it to `dest` whenever MainActivity sets it).
 * [initialDevices] picks the start destination (see [initialDestination]).
 * The Wi-Fi multicast lock ([multicastLock], spec §4) is held by a
 * `DisposableEffect` ONLY while the Devices screen is shown.
 */
@Composable
fun AppNav(
    discoveryVm: DiscoveryViewModel,
    remoteVm: RemoteViewModel,
    keyboardVm: KeyboardViewModel,
    connectionState: UiConnectionState,
    deviceName: String,
    initialDevices: Boolean,
    onSelectDevice: (DiscoveredDevice) -> Unit,
    pairingState: PairingUiState?,
    onSubmitPin: (String) -> Unit,
    onPairCancel: () -> Unit,
    requestedDestination: NavRequest?,
    multicastLock: MulticastLockHolder,
    haptics: dev.atvremote.app.haptics.Haptics?,
    keyboardProbe: suspend () -> String,
) {
    AtvRemoteTheme {
        var dest by remember {
            mutableStateOf(initialDestination(hasReconnectableLast = !initialDevices))
        }
        val kb by keyboardVm.state.collectAsState()
        val disc by discoveryVm.state.collectAsState()

        // MainActivity's async pair/connect/back decisions navigate via this
        // hoisted signal — AppNav still owns `dest`, this only applies the
        // requested destination when MainActivity sets one.
        LaunchedEffect(requestedDestination) {
            requestedDestination?.let { dest = it.dest }
        }

        // Auto-route to the keyboard when the ATV focuses a text field (spec §5/§6).
        // Intentionally overrides requestedDestination while kb.visible — an
        // actively-focused TV text field wins (do not 'fix' the apparent
        // NavRequest no-op): this block runs during composition while the
        // requestedDestination LaunchedEffect is post-commit, so on the next
        // frame a focused field re-asserts KEYBOARD over a just-applied nav.
        if (kb.visible && dest != AppDestinations.KEYBOARD) dest = AppDestinations.KEYBOARD
        if (!kb.visible && dest == AppDestinations.KEYBOARD) dest = AppDestinations.HERO

        val banner = when (connectionState) {
            is UiConnectionState.Reconnecting -> "Reconnecting…"
            is UiConnectionState.Connecting -> "Connecting…"
            is UiConnectionState.CredentialInvalid ->
                "Pairing expired — please re-pair this Apple TV"
            is UiConnectionState.Failed -> "Connection failed"
            else -> null
        }

        when (dest) {
            AppDestinations.HERO -> HeroScreen(
                vm = remoteVm,
                onOpenKeyboard = { dest = AppDestinations.KEYBOARD },
                haptics = haptics,
                keyboardProbe = keyboardProbe,
                deviceName = deviceName,
                onOpenDevices = { dest = AppDestinations.DEVICES },
                // base T15 routed Hero's menu -> Dest.Launcher; the launcher is
                // removed (Amendment A2) -> "More" opens Tuning, the only
                // remaining settings/debug-like destination among the 5.
                onOpenMore = { dest = AppDestinations.TUNING },
                connectionBanner = banner,
            )
            AppDestinations.DEVICES -> {
                // §4: hold the Wi-Fi multicast lock ONLY while Devices is shown
                // (battery-correct). Discovery (DiscoveryViewModel already
                // collecting) then actually receives mDNS. Auto-reconnect uses
                // the persisted device → needs no discovery → no lock.
                DisposableEffect(Unit) {
                    multicastLock.acquire()
                    onDispose { multicastLock.release() }
                }
                DevicesScreen(
                    devices = disc.devices,
                    // Post-select navigation is decided by MainActivity (async
                    // load/pair) via requestedDestination — NOT inline here.
                    onSelect = { onSelectDevice(it) },
                )
            }
            AppDestinations.PAIR -> PairScreen(
                state = pairingState ?: PairingUiState.Connecting,
                onSubmitPin = onSubmitPin,
                onCancel = { onPairCancel(); dest = AppDestinations.DEVICES },
            )
            AppDestinations.KEYBOARD -> KeyboardScreen(
                state = kb,
                onTextChange = keyboardVm::setText,
            )
            AppDestinations.TUNING -> dev.atvremote.app.ui.tuning.SwipeTuningScreen()
        }
    }
}
