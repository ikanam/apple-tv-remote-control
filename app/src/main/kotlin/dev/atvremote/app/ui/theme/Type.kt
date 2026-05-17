package dev.atvremote.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Material3 [Typography] for the Claude-Design reskin.
 *
 * Default body/title/label styles use [InterFontFamily] (the design's UI
 * face). JetBrains Mono is applied per-component by later tasks (eyebrows /
 * codes / badges / IP lines) via [JetBrainsMonoFontFamily] / [monoEyebrow] —
 * Material's scheme has no dedicated "mono" slot, so it stays explicit.
 *
 * Sizes/weights here are sensible defaults that match the bundle's common
 * roles (device name 15sp/600, sheet title 22sp/600, hero 30sp/700, body
 * 13sp). Pixel-exact per-element styling is owned by the screen tasks; this
 * only guarantees the app renders Inter by default, never the platform face.
 */
val AtvTypography: Typography = run {
    val base = Typography()
    val ui = InterFontFamily
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = ui),
        displayMedium = base.displayMedium.copy(fontFamily = ui),
        displaySmall = base.displaySmall.copy(fontFamily = ui),
        headlineLarge = base.headlineLarge.copy(fontFamily = ui, fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontFamily = ui, fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontFamily = ui, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = ui, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = ui, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = ui, fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(fontFamily = ui),
        bodyMedium = base.bodyMedium.copy(fontFamily = ui),
        bodySmall = base.bodySmall.copy(fontFamily = ui),
        labelLarge = base.labelLarge.copy(fontFamily = ui, fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.copy(fontFamily = ui, fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(fontFamily = ui),
    )
}

/**
 * Shared eyebrow style (mono, uppercase tracking) used by the design's
 * `STEP 01 — DISCOVER` / `配对设备` / `TEXT INPUT →` labels. Tracking and color
 * are applied by the screen tasks (varies 0.16–0.2em per element); this just
 * pins the face + the common 11sp size so they don't re-declare it.
 */
val monoEyebrow: TextStyle = TextStyle(
    fontFamily = JetBrainsMonoFontFamily,
    fontSize = 11.sp,
    fontWeight = FontWeight.Medium,
)
