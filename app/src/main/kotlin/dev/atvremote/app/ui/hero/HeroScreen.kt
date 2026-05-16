package dev.atvremote.app.ui.hero

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.atvremote.app.haptics.Haptics
import dev.atvremote.app.swipe.SwipeTuning
import dev.atvremote.app.vm.RemoteViewModel

/**
 * Plan-3 amendment D: the Hero mirrors the physical Siri Remote, top→bottom:
 * Power (top bar, right) → Trackpad → Back · TV/Home → Play/Pause · Volume →
 * bottom-left gated Keyboard (physical Mute slot). No Mute/Siri/Apps.
 *
 * Reconciliation C: the base `HeroCallbacks` data class is superseded by this
 * amended signature. `keyboardProbe` (NOT a `vm.session()` accessor — that does
 * not exist on the committed RemoteViewModel) gates the Keyboard key; base
 * Task 15 wires it to the live session's `textGet()`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeroScreen(
    vm: RemoteViewModel,
    onOpenKeyboard: () -> Unit,
    haptics: Haptics? = null,                          // Power-button haptic; null in tests
    // Probed ONCE per composition via keyless produceState (LaunchedEffect(Unit) semantics).
    // T15 should wire this to CompanionSession.textGet() as a plain one-shot call — it need
    // NOT be a hot/observable source; a result change mid-composition won't re-enable the key.
    keyboardProbe: suspend () -> String = { "" },      // gated Keyboard; default ⇒ available
    deviceName: String = "",
    onOpenDevices: () -> Unit = {},
    onOpenMore: () -> Unit = {},
    connectionBanner: String? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextButton(onClick = onOpenDevices) { Text(deviceName) }
                },
                actions = {
                    IconButton(
                        onClick = onOpenMore,
                        modifier = Modifier.semantics { contentDescription = "More" },
                    ) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
                    // Power (mirrors the physical remote's top ⏻, rightmost):
                    // tap = Wake, long-press = Sleep. No state readback
                    // (FetchAttentionState is dead on tvOS 26.5).
                    IconButton(
                        onClick = { vm.wake(); haptics?.tap() },
                        modifier = Modifier
                            .semantics { contentDescription = "Power" }
                            .pointerInput(Unit) {
                                detectTapGestures(onLongPress = { vm.sleep(); haptics?.select() })
                            },
                    ) { Icon(Icons.Filled.PowerSettingsNew, contentDescription = null) }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            connectionBanner?.let {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { Text(it) }
            }
            Trackpad(
                tuning = SwipeTuning.DEFAULT,
                onEvent = vm::onTouchEvent,
                modifier = Modifier,
            )
            // "Back" maps to RemoteButton.Menu: Apple TV's Menu button IS the
            // back action; the physical Siri Remote labels it back.
            ButtonRow(
                onBack = vm::menu,
                onHome = vm::home,
                onPlayPause = vm::playPause,
                onVolumeUp = vm::volumeUp,
                onVolumeDown = vm::volumeDown,
            )
            // Bottom-left (physical remote's Mute slot) — Keyboard key,
            // capability-gated until the deferred keyboard chain lands.
            val kbReady by produceState(initialValue = false) {
                value = keyboardAvailable(keyboardProbe)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.Start,
            ) {
                IconButton(
                    onClick = { if (kbReady) onOpenKeyboard() },
                    enabled = kbReady,
                    modifier = Modifier.semantics { contentDescription = "Keyboard" },
                ) { Icon(Icons.Filled.Keyboard, contentDescription = null) }
            }
        }
    }
}
