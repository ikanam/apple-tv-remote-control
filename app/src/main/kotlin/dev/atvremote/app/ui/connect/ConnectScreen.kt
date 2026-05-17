package dev.atvremote.app.ui.connect

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.atvremote.app.ui.icons.IconBack
import dev.atvremote.app.ui.icons.IconCheck
import dev.atvremote.app.ui.icons.IconChevron
import dev.atvremote.app.ui.icons.IconDot
import dev.atvremote.app.ui.icons.IconSpinner
import dev.atvremote.app.ui.icons.IconTV
import dev.atvremote.app.ui.theme.Brushes
import dev.atvremote.app.ui.theme.DesignTokens
import dev.atvremote.app.ui.theme.JetBrainsMonoFontFamily
import dev.atvremote.app.vm.DiscoveredDevice
import dev.atvremote.app.vm.PairingUiState
import dev.atvremote.protocol.AppleTvDevice

/**
 * Device discovery / connect screen — ported from `connect.jsx:111-318`
 * (the `ConnectScreen` React component) with the spec's two locked
 * reconciliation decisions baked in
 * (`docs/superpowers/specs/2026-05-17-claude-design-ui-reskin.md`, Screen 2 +
 * reconciliation §1): driven by the real `DiscoveryViewModel`/
 * `PairingViewModel`, with graceful degradation of protocol-unavailable data.
 *
 * Replaces the old `DevicesScreen` + `PairScreen` (both deleted in T4b).
 *
 * ## Reconciliation deltas vs the prototype (spec §1 — intentional)
 *  - **NO RSSI / signal bars.** The Companion protocol exposes no RSSI; the
 *    `RssiBars` glyph (`connect.jsx:10-22`, used at `:261`) is dropped. The
 *    card's right edge is `IconCheck` (current) else `IconChevron`.
 *  - A small JetBrains-Mono `已配对` chip is added when
 *    [DiscoveredDevice.paired] (real data we *do* have).
 *  - The Wi-Fi status pill (SSID/IP + refresh affordance) was removed entirely:
 *    it carried no actionable value (discovery is continuous, no rescan API).
 *  - `+ 手动添加 IP 地址` opens a minimal name/IP dialog that constructs an
 *    [AppleTvDevice] and feeds [onManualAdd] (the same select path).
 *
 * ## Two modes (driven by [onClose])
 *  - **First-run** ([onClose] == null): TV-logo gradient tile + title
 *    `TV Remote`, hero `在 Wi-Fi 网络上 / 寻找你的 Apple TV`, no eyebrow,
 *    no back button.
 *  - **Switcher overlay** ([onClose] != null): back button + title `切换设备`,
 *    eyebrow `SWITCH — SELECT DEVICE`, hero `选择要控制的 / Apple TV 设备`;
 *    the [currentId] card shows the `CURRENT` badge + `IconCheck` + accent
 *    border and tapping it calls [onClose].
 *
 * This is a pure composable.
 *
 * @param devices the real [DiscoveryViewModel] device list.
 * @param scanning the real `DiscoveryUiState.scanning` flag.
 * @param onSelectDevice tap on a non-current card (MainActivity decides
 *        connect-if-paired vs PairingSheet — that S5 logic is preserved).
 * @param pairingState when non-null the [PairingSheet] overlay is composed on
 *        top (this is the in-Connect pair surface); null = list only.
 * @param onSubmitPin / [onPairCancel] passed straight through to [PairingSheet].
 * @param pairingDeviceName the 22sp sheet title; falls back to the mode title.
 * @param currentId id of the currently-connected device (switcher mode).
 * @param onClose null = first-run; non-null = switcher overlay + back/current.
 * @param onManualAdd a manually-constructed [AppleTvDevice] (same select path).
 */
@Composable
fun ConnectScreen(
    devices: List<DiscoveredDevice>,
    scanning: Boolean,
    onSelectDevice: (DiscoveredDevice) -> Unit,
    pairingState: PairingUiState?,
    onSubmitPin: (String) -> Unit,
    onPairCancel: () -> Unit,
    pairingDeviceName: String? = null,
    currentId: String? = null,
    onClose: (() -> Unit)? = null,
    onManualAdd: (AppleTvDevice) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val switcher = onClose != null

    // Brushes are pure but recreate a ShaderBrush object per call — hoist the
    // one used here (the TV-logo tile) so it isn't rebuilt every recomposition.
    val tvLogoBrush = remember { Brushes.tvLogoGradient() }

    var showManualAdd by remember { mutableStateOf(false) }

    val title = if (switcher) "切换设备" else "TV Remote"

    Box(modifier = modifier.fillMaxSize().background(DesignTokens.ConnectScreenBg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Immersive: the background Box above is full-bleed (flows
                // behind the transparent status bar); only this content column
                // is inset, so the bar blends with the screen, no flat band.
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                // connect.jsx:130 padding: '8px 0 24px' (horizontal pad lives
                // on each section, matching the prototype's per-block padding).
                .padding(top = 8.dp, bottom = 24.dp),
        ) {
            // ---- Top bar — connect.jsx:133 ---------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (switcher) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(
                                    role = Role.Button,
                                    onClick = { onClose?.invoke() },
                                )
                                .semantics { contentDescription = "Back" },
                            contentAlignment = Alignment.Center,
                        ) {
                            IconBack(size = 20.dp, color = Color.White.copy(alpha = 0.7f))
                        }
                    } else {
                        // TV-logo gradient tile — connect.jsx:145 (32dp r10).
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(tvLogoBrush),
                            contentAlignment = Alignment.Center,
                        ) {
                            IconTV(size = 18.dp, color = Color.White, strokeWidth = 2f)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = title,
                        color = DesignTokens.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // ---- Hero — connect.jsx:168 ------------------------------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
            ) {
                // First-run no longer shows an eyebrow (the old
                // `STEP 01 — DISCOVER` was removed); the switcher overlay keeps
                // its `SWITCH — SELECT DEVICE` label.
                if (switcher) {
                    Text(
                        text = "SWITCH — SELECT DEVICE",
                        color = DesignTokens.AccentLight,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 11.sp,
                        letterSpacing = (0.2f * 11).sp, // connect.jsx:169 ls 0.2em
                    )
                    Spacer(Modifier.height(8.dp)) // connect.jsx:172 marginTop:8
                }
                // 30sp 700, lineHeight ~1.15: first line white, second muted.
                Text(
                    text = if (switcher) "选择要控制的" else "在 Wi-Fi 网络上",
                    color = DesignTokens.TextPrimary,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 34.sp,
                )
                Text(
                    text = if (switcher) "Apple TV 设备" else "寻找你的 Apple TV",
                    color = DesignTokens.TextMuted55,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 34.sp,
                )
            }

            // ---- Device list — connect.jsx:206 -----------------------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = if (scanning) {
                        "搜索中... 已发现 ${devices.size}"
                    } else {
                        "已发现 ${devices.size} 台设备"
                    },
                    color = DesignTokens.TextMuted40,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 11.sp,
                    letterSpacing = (0.18f * 11).sp,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    devices.forEach { dd ->
                        DeviceCard(
                            dd = dd,
                            isCurrent = currentId != null && currentId == dd.device.id,
                            onClick = {
                                if (currentId != null && currentId == dd.device.id) {
                                    onClose?.invoke()
                                } else {
                                    onSelectDevice(dd)
                                }
                            },
                        )
                    }

                    if (scanning) {
                        ScanningPlaceholder()
                    }
                }
            }

            // ---- Manual add — connect.jsx:284 ------------------------------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
            ) {
                Text(
                    text = "+ 手动添加 IP 地址",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(
                            width = 1.dp,
                            color = DesignTokens.OutlineBorder12,
                            shape = RoundedCornerShape(14.dp),
                        )
                        .clickable(role = Role.Button) { showManualAdd = true }
                        .padding(vertical = 16.dp),
                )
            }

            // ---- Footer hint — connect.jsx:298 (static copy) ---------------
            Text(
                text = "请确保你的手机和 Apple TV 在同一 Wi-Fi 网络下，\n" +
                    "并在 Apple TV 上开启 \"AirPlay 与 HomeKit\"。",
                color = DesignTokens.TextMuted35,
                fontSize = 11.sp,
                lineHeight = 18.sp, // ~1.6 × 11
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, end = 28.dp, top = 20.dp),
            )
        }

        // ---- PairingSheet overlay (last in the Box → on top of all) --------
        if (pairingState != null) {
            PairingSheet(
                deviceName = pairingDeviceName ?: title,
                pairingState = pairingState,
                onSubmitPin = onSubmitPin,
                onCancel = onPairCancel,
                onPaired = {},
            )
        }

        // ---- Manual-add dialog ---------------------------------------------
        if (showManualAdd) {
            ManualAddDialog(
                onDismiss = { showManualAdd = false },
                onConfirm = { device ->
                    showManualAdd = false
                    onManualAdd(device)
                },
            )
        }
    }
}

/**
 * Dashed "scanning…" placeholder (connect.jsx:268). [IconSpinner] already
 * supports an `angleDeg`; an infinite transition rotates it continuously. The
 * transition only runs while this composable is in composition — i.e. only
 * while `scanning` is true (the caller gates it), so there is no idle cost.
 */
@Composable
private fun ScanningPlaceholder() {
    val transition = rememberInfiniteTransition(label = "scanSpinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
        ),
        label = "scanSpinnerAngle",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconSpinner(size = 16.dp, color = DesignTokens.AccentLight, angleDeg = angle)
        Spacer(Modifier.width(12.dp))
        Text(
            text = "在网络中扫描 Apple TV 设备…",
            color = DesignTokens.TextMuted40,
            fontSize = 13.sp,
        )
    }
}

/**
 * One device card — connect.jsx:215-264 with the RSSI bars dropped (spec §1).
 * Right edge = [IconCheck] (current) else [IconChevron]; an `已配对` chip is
 * added when the device has stored credentials.
 */
@Composable
private fun DeviceCard(
    dd: DiscoveredDevice,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val d = dd.device
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isCurrent) DesignTokens.AccentFill08 else DesignTokens.SurfaceCard)
            .border(
                width = 1.dp,
                color = if (isCurrent) DesignTokens.AccentBorder35 else DesignTokens.HairlineBorder,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 44dp TV-tile.
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(DesignTokens.InsetFieldBg)
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            IconTV(
                size = 22.dp,
                color = if (isCurrent) DesignTokens.AccentLight else DesignTokens.AccentActiveText,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = d.name,
                    color = DesignTokens.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                IconDot(size = 6.dp, color = DesignTokens.GreenDot)
                if (isCurrent) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "CURRENT",
                        color = DesignTokens.AccentLight,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 9.sp,
                        letterSpacing = (0.16f * 9).sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF5B89FF).copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (dd.paired) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "已配对",
                        color = DesignTokens.TextMuted55,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 9.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            // model line: degrade to host when model blank/null (spec §1).
            Text(
                text = d.model?.takeIf { it.isNotBlank() } ?: d.host,
                color = DesignTokens.TextMuted50,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = d.host,
                color = DesignTokens.TextMuted35,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        // NO RSSI bars (spec §1) — just the trailing affordance.
        if (isCurrent) {
            Box(modifier = Modifier.semantics { contentDescription = "Current device" }) {
                IconCheck(size = 16.dp, color = DesignTokens.AccentLight)
            }
        } else {
            IconChevron(size = 16.dp, color = DesignTokens.TextMuted35)
        }
    }
}

/**
 * Minimal manual-add dialog (spec §1: "minimal numeric IP + name dialog that
 * constructs an [AppleTvDevice] and feeds the same select path"). Name is
 * optional; IP is required and minimally validated (non-empty dotted-quad,
 * each octet 0-255). Port defaults to the Companion default 49153. The
 * synthetic `id` is `host:port` and `pairable` is true so the same
 * select→pair-or-connect path applies.
 */
@Composable
private fun ManualAddDialog(
    onDismiss: () -> Unit,
    onConfirm: (AppleTvDevice) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(DesignTokens.SheetBg)
                .border(
                    width = 1.dp,
                    color = DesignTokens.HairlineBorder,
                    shape = RoundedCornerShape(20.dp),
                )
                .padding(24.dp),
        ) {
            Text(
                text = "手动添加设备",
                color = DesignTokens.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(16.dp))
            // Material text fields tint from LocalContentColor; keep readable.
            CompositionLocalProvider(LocalContentColor provides DesignTokens.TextPrimary) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("名称（可选）") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Manual device name" },
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = ip,
                    onValueChange = {
                        ip = it
                        error = null
                    },
                    singleLine = true,
                    label = { Text("IP 地址") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = error != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Manual IP address" },
                )
            }
            if (error != null) {
                Text(
                    text = error!!,
                    color = Color(0xFFFF6B6B),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = DesignTokens.TextMuted55)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    val host = ip.trim()
                    if (!isValidIpv4(host)) {
                        error = "请输入有效的 IP 地址"
                        return@TextButton
                    }
                    val port = 49153 // Companion default
                    val displayName = name.trim().ifBlank { host }
                    onConfirm(
                        AppleTvDevice(
                            id = "$host:$port",
                            name = displayName,
                            host = host,
                            port = port,
                            model = null,
                            pairable = true,
                        ),
                    )
                }) {
                    Text("添加", color = DesignTokens.AccentLight)
                }
            }
        }
    }
}

/** Minimal dotted-quad IPv4 check (non-empty, 4 octets, each 0-255). */
private fun isValidIpv4(s: String): Boolean {
    val parts = s.split(".")
    if (parts.size != 4) return false
    return parts.all { p ->
        p.isNotEmpty() && p.all { it.isDigit() } && (p.toIntOrNull()?.let { it in 0..255 } == true)
    }
}
