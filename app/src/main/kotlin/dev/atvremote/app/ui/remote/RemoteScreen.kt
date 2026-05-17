package dev.atvremote.app.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.atvremote.app.haptics.Haptics
import dev.atvremote.app.swipe.SwipeTuning
import dev.atvremote.app.ui.hero.keyboardAvailable
import dev.atvremote.app.ui.icons.IconBack
import dev.atvremote.app.ui.icons.IconChevronDown
import dev.atvremote.app.ui.icons.IconKeyboard
import dev.atvremote.app.ui.icons.IconPlayPause
import dev.atvremote.app.ui.icons.IconPower
import dev.atvremote.app.ui.icons.IconTV
import dev.atvremote.app.ui.theme.Brushes
import dev.atvremote.app.ui.theme.DesignTokens
import dev.atvremote.app.vm.KeyboardViewModel
import dev.atvremote.app.vm.RemoteViewModel

/**
 * Screen 1 — the Apple-TV remote — ported 1:1 from `remote.jsx:279-370`,
 * replacing the old `HeroScreen` (+ its `Trackpad`/`DpadRow`/`ButtonRow`).
 *
 * Layout (top→bottom, matching the JSX exactly):
 *  - **Top bar** (remote.jsx:294): a centered device-switcher chip
 *    (`deviceName` + chevron-down, → [onSwitchDevice]) and a right 32dp power
 *    circle (tap = [RemoteViewModel.wake], long-press = [RemoteViewModel.sleep]).
 *  - optional unobtrusive connection banner line (same behavior the old Hero
 *    had — shown only when [connectionBanner] is non-null).
 *  - **Center** (remote.jsx:323): vertically centered, gap 28dp, the
 *    [Touchpad] then a 3×2 grid (remote.jsx:327). The grid's right column holds
 *    `IconTV` (row 1) and a [VolumePill] that spans rows 2-3 (its 172dp height
 *    == row2 80 + gap 12 + row3 80, so it aligns flush with the left column's
 *    Play/Pause + Keyboard rows — the faithful equivalent of the JSX
 *    `gridRow:'2 / span 2'`).
 *  - The **Keyboard** grid button is capability-gated (dimmed + disabled while
 *    the deferred Plan-2 keyboard surface is a `NotImplementedError` stub) via
 *    the existing [keyboardAvailable]/[keyboardProbe] — the cell stays so the
 *    grid layout never shifts.
 *  - The [KeyboardOverlay] is composited on top when the Keyboard button is
 *    tapped OR when the ATV focuses a text field (`KeyboardViewModel.state`
 *    `.visible` — the existing auto-route, now rendered as an in-screen
 *    overlay). `完成`/close clears only the *local* toggle; while
 *    `state.visible` is true the overlay stays (we do not fight the VM).
 *
 * View-only: every interaction calls an existing [RemoteViewModel] /
 * [KeyboardViewModel] public method — no logic added here.
 */
@Composable
fun RemoteScreen(
    remoteVm: RemoteViewModel,
    keyboardVm: KeyboardViewModel,
    deviceName: String,
    onSwitchDevice: () -> Unit,
    tuning: SwipeTuning = SwipeTuning.DEFAULT,
    haptics: Haptics? = null,
    keyboardProbe: suspend () -> String = { "" },
    connectionBanner: String? = null,
    modifier: Modifier = Modifier,
) {
    val bg = remember { Brushes.remoteScreenBackground() }
    val kb by keyboardVm.state.collectAsState()

    // Probed ONCE per composition (keyless produceState ≡ LaunchedEffect(Unit),
    // identical to the old HeroScreen): a mid-composition capability change
    // won't re-enable the key — consistent with the documented gating.
    val kbReady by produceState(initialValue = false) {
        value = keyboardAvailable(keyboardProbe)
    }
    // Local Keyboard-button toggle. The overlay shows when this is true OR the
    // ATV focused a field (kb.visible). 完成 clears only the local toggle; we
    // never write kb.visible (VM-owned).
    var localKbOpen by remember { mutableStateOf(false) }
    val overlayVisible = localKbOpen || kb.visible

    Box(modifier = modifier.fillMaxSize().background(bg)) {
        Column(modifier = Modifier.fillMaxSize()) {

            // --- top bar — remote.jsx:294-320 -----------------------------
            // remote.jsx:294 padding '14px 16px 6px', `minHeight:44`,
            // `display:flex; alignItems:center`. minHeight (not a fixed height)
            // so the 32dp power circle is never clipped; device chip is
            // absolutely centered, power pinned right.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                // centered device-switcher chip (remote.jsx:295-306).
                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onSwitchDevice)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = deviceName,
                        color = DesignTokens.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, // 600
                    )
                    IconChevronDown(
                        size = 12.dp,
                        color = DesignTokens.TextMuted50, // rgba(255,255,255,0.5)
                        strokeWidth = 2.2f,
                    )
                }

                // power button (remote.jsx:308-319): 32dp circle, bg
                // rgba(255,255,255,0.06), IconPower 16dp. tap=wake,
                // long-press=sleep (preserve S4 power semantics).
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(DesignTokens.ControlSurface06)
                        // pointerInput(Unit) captures remoteVm/haptics for the
                        // gesture coroutine's life (no rememberUpdatedState):
                        // safe ONLY because AppNav/MainActivity pass stable
                        // hoisted (not per-recomposition) VM/haptics singletons.
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { remoteVm.wake(); haptics?.tap() },
                                onLongPress = { remoteVm.sleep(); haptics?.select() },
                            )
                        }
                        .semantics { contentDescription = "Power" },
                    contentAlignment = Alignment.Center,
                ) {
                    IconPower(
                        size = 16.dp,
                        color = Color.White.copy(alpha = 0.75f), // remote.jsx:313
                    )
                }
            }

            // optional connection banner — same unobtrusive line the old Hero
            // showed (small, centered, under the top bar).
            connectionBanner?.let {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = it,
                        color = DesignTokens.TextMuted55,
                        fontSize = 13.sp,
                    )
                }
            }

            // --- center: touchpad + grid — remote.jsx:323-365 -------------
            // remote.jsx's center is `flex:1; justifyContent:center`. On a
            // normal phone there is ample room so it simply centers. To stay
            // robust on a short viewport (and so the layout never collapses
            // later rows to zero height under a tight height constraint),
            // the centered block lives in a vertical scroll with a min-height
            // == the available height: tall enough ⇒ identical centered design;
            // too short ⇒ it scrolls instead of clipping. No visual change on
            // a real device.
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val viewportH = maxHeight
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = viewportH)
                        .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                Touchpad(
                    tuning = tuning,
                    onDirection = { remoteVm.pressButton(it) },
                    onTouchEvent = { remoteVm.onTouchEvent(it) },
                )

                Spacer(modifier = Modifier.height(28.dp)) // gap: 28

                // 3×2 grid (remote.jsx:327): two equal-width columns, rowGap
                // 12, padding '0 12px', items centered. Left column = Back /
                // Play-Pause / Keyboard (3 rows). Right column = TV/Home (row
                // 1) then VolumePill (172dp == rows 2-3) so it spans flush.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // left column
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        RemoteButton(
                            onClick = { remoteVm.menu() },
                            contentDescription = "Back",
                        ) { IconBack(size = 20.dp, strokeWidth = 2f) }

                        RemoteButton(
                            onClick = { remoteVm.playPause() },
                            contentDescription = "Play/Pause",
                        ) { IconPlayPause(size = 22.dp) }

                        // Keyboard — capability-gated. Disabled+dimmed (the cell
                        // stays so the grid never reflows) until the deferred
                        // keyboard surface lands. Enabled ⇒ opens the overlay.
                        RemoteButton(
                            onClick = { if (kbReady) localKbOpen = true },
                            contentDescription = "Keyboard",
                            modifier = Modifier.alpha(if (kbReady) 1f else 0.4f),
                        ) { IconKeyboard(size = 20.dp, strokeWidth = 1.8f) }
                    }

                    // right column
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        RemoteButton(
                            onClick = { remoteVm.home() },
                            contentDescription = "TV/Home",
                        ) { IconTV(size = 20.dp, strokeWidth = 2f) }

                        // spans rows 2-3 (172 == 80 + 12 + 80); vertically
                        // centered against the left column's bottom two rows.
                        VolumePill(
                            onUp = { remoteVm.volumeUp() },
                            onDown = { remoteVm.volumeDown() },
                        )
                    }
                }
                } // end centered scroll Column
            } // end BoxWithConstraints
        }

        // --- KeyboardOverlay — remote.jsx:367 (composited on top) ----------
        if (overlayVisible) {
            KeyboardOverlay(
                deviceName = deviceName,
                text = kb.text,
                onTextChange = { keyboardVm.setText(it) },
                // 完成: clear the local toggle only. If the ATV still has a
                // field focused (kb.visible) the overlay stays — the VM owns
                // that; do not fight it.
                onClose = { localKbOpen = false },
            )
        }
    }
}
