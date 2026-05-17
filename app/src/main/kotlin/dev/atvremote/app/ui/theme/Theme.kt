package dev.atvremote.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Single FIXED dark color scheme for the Claude-Design reskin.
 *
 * The design has no light variant, so this is dark-only — no
 * `isSystemInDarkTheme()`, no dynamic color. The real screen backgrounds are
 * gradient [androidx.compose.ui.graphics.Brush]es drawn per-screen (see
 * [Brushes]); the Material `background`/`surface` slots here are only fallbacks
 * for any stock Material surface that isn't custom-painted.
 */
internal val AtvDarkColorScheme = darkColorScheme(
    primary = DesignTokens.AccentBlue,
    onPrimary = DesignTokens.TextPrimary,
    primaryContainer = DesignTokens.AccentFill08,
    onPrimaryContainer = DesignTokens.AccentActiveText,
    secondary = DesignTokens.AccentLight,
    onSecondary = DesignTokens.TextPrimary,
    tertiary = DesignTokens.GreenDot,
    background = DesignTokens.RemoteBgMid,        // ≈ #0C0E13
    onBackground = DesignTokens.TextPrimarySoft,  // #E9EAEE
    surface = DesignTokens.SurfaceCard,           // #16181D
    onSurface = DesignTokens.TextPrimary,
    surfaceVariant = DesignTokens.InsetFieldBg,   // #0E1014
    onSurfaceVariant = DesignTokens.TextMuted55,
    outline = DesignTokens.OutlineBorder12,
    outlineVariant = DesignTokens.HairlineBorder,
    error = DesignTokens.AccentBlue,              // design has no error palette
    onError = DesignTokens.TextPrimary,
)

/**
 * App theme. Dark is forced regardless of system/caller preference.
 *
 * The [darkTheme] parameter is retained ONLY so existing callers
 * (`AppNav`, previews, `MainActivity`) keep compiling — it is intentionally
 * ignored; the design is dark-only.
 */
@Composable
fun AtvRemoteTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = AtvDarkColorScheme,
        typography = AtvTypography,
        content = content,
    )
}
