package dev.atvremote.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.atvremote.app.conn.UiConnectionState
import dev.atvremote.app.ui.devices.DevicesScreen
import dev.atvremote.app.ui.hero.HeroScreen
import dev.atvremote.app.ui.keyboard.KeyboardScreen
import dev.atvremote.app.ui.pair.PairScreen
import dev.atvremote.app.ui.theme.AtvRemoteTheme
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
 * Top-level navigation (spec §5 nav structure): Hero is home; top-bar device
 * button -> Devices/Pair; "More" -> Tuning (debug/settings); focused text
 * field auto-routes to Keyboard.
 *
 * Reconciled per the Plan-3 remote-layout amendment (R2): the base Task-15
 * `enum class Dest{...Launcher...}` is replaced by the Amendment-A
 * [AppDestinations] enum (no Launcher); `LauncherViewModel`/`AppLauncherScreen`/
 * `HeroCallbacks` are dropped (the amended [HeroScreen] takes flat lambdas, no
 * `HeroCallbacks`); `haptics`/`keyboardProbe` are threaded through to wire the
 * amended Hero. The Devices->Pair deeper PairingViewModel flow is base Task-15's
 * own aspirational note (NOT in its actual code) — kept as base behavior
 * (`pairingState`/`onSubmitPin` are wired from MainActivity as null/no-op).
 */
@Composable
fun AppNav(
    discoveryVm: DiscoveryViewModel,
    remoteVm: RemoteViewModel,
    keyboardVm: KeyboardViewModel,
    connectionState: UiConnectionState,
    deviceName: String,
    onSelectDevice: (deviceId: String) -> Unit,
    pairingState: PairingUiState?,
    onSubmitPin: (String) -> Unit,
    haptics: dev.atvremote.app.haptics.Haptics?,
    keyboardProbe: suspend () -> String,
) {
    AtvRemoteTheme {
        var dest by remember { mutableStateOf(AppDestinations.HERO) }
        val kb by keyboardVm.state.collectAsState()
        val disc by discoveryVm.state.collectAsState()

        // Auto-route to the keyboard when the ATV focuses a text field (spec §5/§6).
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
            AppDestinations.DEVICES -> DevicesScreen(
                devices = disc.devices,
                onSelect = { onSelectDevice(it.device.id); dest = AppDestinations.HERO },
            )
            AppDestinations.PAIR -> PairScreen(
                state = pairingState ?: PairingUiState.Connecting,
                onSubmitPin = onSubmitPin,
                onCancel = { dest = AppDestinations.DEVICES },
            )
            AppDestinations.KEYBOARD -> KeyboardScreen(
                state = kb,
                onTextChange = keyboardVm::setText,
            )
            AppDestinations.TUNING -> dev.atvremote.app.ui.tuning.SwipeTuningScreen()
        }
    }
}
