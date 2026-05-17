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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import dev.atvremote.app.ui.icons.IconSettings
import dev.atvremote.app.ui.icons.IconTV
import dev.atvremote.app.ui.theme.Brushes
import dev.atvremote.app.ui.theme.DesignTokens
import dev.atvremote.app.vm.KeyboardViewModel
import dev.atvremote.app.vm.RemoteViewModel

internal object IphoneRemoteLayoutMetrics {
    val HorizontalPadding = 14.dp
    val TouchToControlsGap = 28.dp
    val BottomPadding = 32.dp
    val BottomControlsHeight = 172.dp
}

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
    onOpenSettings: () -> Unit = {},
    tuning: SwipeTuning = SwipeTuning.DEFAULT,
    layoutStyle: RemoteLayoutStyle = RemoteLayoutStyle.Physical,
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
        if (layoutStyle == RemoteLayoutStyle.Iphone) {
            IphoneRemoteLayout(
                remoteVm = remoteVm,
                deviceName = deviceName,
                onSwitchDevice = onSwitchDevice,
                tuning = tuning,
                kbReady = kbReady,
                onOpenKeyboard = { localKbOpen = true },
                onOpenSettings = onOpenSettings,
                haptics = haptics,
                connectionBanner = connectionBanner,
            )
        } else {
        // Immersive: the background Box is full-bleed (its gradient flows
        // behind the transparent status bar); only this content column is
        // statusBarsPadding()-inset so the bar blends in with no flat band.
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {

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
                        strokeWidth = 1.4f,
                    )
                }

                // settings gear (left) — mirrors the right power circle.
                // Relocated here from ConnectScreen: settings belong on the
                // app's main screen. 32dp circle, opens SwipeTuningScreen.
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1A1A1C)) // Recolor (owner): flat button bg
                        .clickable(role = Role.Button, onClick = onOpenSettings)
                        .semantics { contentDescription = "Settings" },
                    contentAlignment = Alignment.Center,
                ) {
                    IconSettings(
                        size = 16.dp,
                        color = Color(0xFFFEFEFE), // Recolor (owner)
                        strokeWidth = 1.2f,
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
                        .background(Color(0xFF1A1A1C)) // Recolor (owner): flat button bg
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
                        color = Color(0xFFFEFEFE), // Recolor (owner)
                        strokeWidth = 1.2f,
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
            // remote.jsx's center was `justifyContent:center`; owner asked for
            // the (now ×1.2 larger) touchpad to sit nearer the top, so this is
            // Arrangement.Top with a small lead gap rather than vertically
            // centered. Still wrapped in a min-height vertical scroll so a
            // short viewport scrolls instead of clipping (the bigger pad makes
            // that more likely) — no clipping on a normal phone.
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val viewportH = maxHeight
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = viewportH)
                        .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.Top,
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
                        ) { IconBack(size = 20.dp, strokeWidth = 1.2f) }

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
                        ) { IconKeyboard(size = 20.dp, strokeWidth = 1.2f) }
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
                        ) { IconTV(size = 20.dp, strokeWidth = 1.2f) }

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
        }

        // --- KeyboardOverlay — remote.jsx:367 (composited on top) ----------
        if (overlayVisible) {
            KeyboardOverlay(
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

@Composable
private fun IphoneRemoteLayout(
    remoteVm: RemoteViewModel,
    deviceName: String,
    onSwitchDevice: () -> Unit,
    tuning: SwipeTuning,
    kbReady: Boolean,
    onOpenKeyboard: () -> Unit,
    onOpenSettings: () -> Unit,
    haptics: Haptics?,
    connectionBanner: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = IphoneRemoteLayoutMetrics.HorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IphoneTopIconButton(
                contentDescription = "Keyboard",
                enabled = kbReady,
                onClick = { if (kbReady) onOpenKeyboard() },
            ) {
                IconKeyboard(size = 22.dp, color = Color(0xFFF5F5F7), strokeWidth = 1.7f)
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onSwitchDevice)
                    .padding(horizontal = 0.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onOpenSettings)
                        .semantics { contentDescription = "Settings" },
                    contentAlignment = Alignment.Center,
                ) {
                    IconSettings(size = 21.dp, color = Color(0xFFF5F5F7), strokeWidth = 1.5f)
                }

                Text(
                    text = deviceName,
                    color = Color(0xFFF5F5F7),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier.size(44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    IconChevronDown(
                        size = 12.dp,
                        color = DesignTokens.TextMuted50,
                        strokeWidth = 1.4f,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { remoteVm.wake(); haptics?.tap() },
                            onLongPress = { remoteVm.sleep(); haptics?.select() },
                        )
                    }
                    .semantics { contentDescription = "Power" },
                contentAlignment = Alignment.Center,
            ) {
                IconPower(size = 24.dp, color = Color(0xFFF5F5F7), strokeWidth = 1.8f)
            }
        }

        connectionBanner?.let {
            Text(
                text = it,
                color = DesignTokens.TextMuted55,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = IphoneRemoteLayoutMetrics.BottomPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IphoneTouchArea(
                    tuning = tuning,
                    onDirection = { remoteVm.pressButton(it) },
                    onTouchEvent = { remoteVm.onTouchEvent(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.773f),
                )

                Spacer(Modifier.height(IphoneRemoteLayoutMetrics.TouchToControlsGap))

                IphoneBottomControls(remoteVm = remoteVm)
            }
        }
    }
}

@Composable
private fun IphoneBottomControls(remoteVm: RemoteViewModel) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(IphoneRemoteLayoutMetrics.BottomControlsHeight),
    ) {
        val narrow = maxWidth < 314.dp
        val compact = maxWidth < 380.dp
        val side = when {
            narrow -> 64.dp
            compact -> 72.dp
            else -> 80.dp
        }
        val center = when {
            narrow -> 132.dp
            compact -> 150.dp
            else -> 170.dp
        }
        val gap = when {
            narrow -> 18.dp
            maxWidth < 330.dp -> 10.dp
            compact -> 16.dp
            else -> 24.dp
        }
        val controlsHeight = when {
            narrow -> 140.dp
            compact -> 156.dp
            else -> 172.dp
        }

        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(controlsHeight)
                .testTag("iphone-bottom-controls"),
            horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.width(side),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IphoneRoundButton(
                    size = side,
                    contentDescription = "TV/Home",
                    onClick = { remoteVm.home() },
                ) { IconTV(size = 26.dp, color = Color(0xFFF5F5F7), strokeWidth = 1.7f) }
                IphoneRoundButton(
                    size = side,
                    contentDescription = "Play/Pause",
                    onClick = { remoteVm.playPause() },
                ) { IconPlayPause(size = 27.dp, color = Color(0xFFF5F5F7)) }
            }
            IphoneRoundButton(
                size = center,
                contentDescription = "Back",
                onClick = { remoteVm.menu() },
            ) { IconBack(size = 60.dp, color = Color(0xFFF5F5F7), strokeWidth = 1.15f) }
            IphoneChannelPill(
                width = side,
                height = center,
                onUp = { remoteVm.volumeUp() },
                onDown = { remoteVm.volumeDown() },
            )
        }
    }
}

@Composable
private fun IphoneTopIconButton(
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    size: Dp = 44.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.35f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun IphoneRoundButton(
    size: Dp,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF1C1C1E))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun IphoneChannelPill(
    width: Dp,
    height: Dp,
    onUp: () -> Unit,
    onDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(40.dp))
            .background(Color(0xFF1C1C1E)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable(role = Role.Button, onClick = onUp)
                .semantics { contentDescription = "Volume Up" },
            contentAlignment = Alignment.Center,
        ) {
            IconChevronDown(
                size = 22.dp,
                color = Color(0xFFF5F5F7),
                strokeWidth = 2.0f,
                modifier = Modifier.graphicsLayer(rotationZ = 180f),
            )
        }
        Text(
            text = "CH",
            color = Color(0xFFF5F5F7),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable(role = Role.Button, onClick = onDown)
                .semantics { contentDescription = "Volume Down" },
            contentAlignment = Alignment.Center,
        ) {
            IconChevronDown(
                size = 22.dp,
                color = Color(0xFFF5F5F7),
                strokeWidth = 2.0f,
            )
        }
    }
}
