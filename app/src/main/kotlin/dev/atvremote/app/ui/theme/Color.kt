package dev.atvremote.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Claude-Design dark-only token palette.
 *
 * Every value is lifted verbatim from the in-repo design bundle
 * (`docs/superpowers/design/2026-05-17-tv-remote-claude-design/project/`),
 * cross-checked against the spec's "Design tokens" table
 * (`docs/superpowers/specs/2026-05-17-claude-design-ui-reskin.md`). Names are
 * self-describing; brushes that need these are assembled in [Brushes].
 *
 * The theme is dark-only — there is no light variant in the design.
 */
object DesignTokens {

    // --- Screen / surface backgrounds -------------------------------------

    /** Connect screen solid bg — connect.jsx:128 (`#0e1014`). */
    val ConnectScreenBg = Color(0xFF0E1014)

    /** Inset field bg (digit boxes, keyboard input, device TV-tile) —
     *  connect.jsx:83 (`#0e1014`). Same hex as [ConnectScreenBg] by design. */
    val InsetFieldBg = Color(0xFF0E1014)

    /** Surface card (device card) — connect.jsx:219 (`#16181d`). */
    val SurfaceCard = Color(0xFF16181D)

    /** Bottom-sheet bg (PairingSheet) — connect.jsx:54 (`#16181d`). */
    val SheetBg = Color(0xFF16181D)

    // Remote radial-gradient stops — remote.jsx:289
    val RemoteBgStart = Color(0xFF181C25) // 0%
    val RemoteBgMid = Color(0xFF0C0E13)   // 60%
    val RemoteBgEnd = Color(0xFF08090C)   // 100%

    // --- Text -------------------------------------------------------------

    /** Pure white text — bundle (`#fff`). */
    val TextPrimary = Color(0xFFFFFFFF)

    /** Off-white text — bundle (`#e9eaee`). Material `onBackground`. */
    val TextPrimarySoft = Color(0xFFE9EAEE)

    // Muted white overlays (connect.jsx) — kept as explicit alpha tokens so
    // later tasks don't re-derive rgba() values by hand.
    val TextMuted55 = Color(0xFFFFFFFF).copy(alpha = 0.55f)
    val TextMuted50 = Color(0xFFFFFFFF).copy(alpha = 0.50f)
    val TextMuted45 = Color(0xFFFFFFFF).copy(alpha = 0.45f)
    val TextMuted40 = Color(0xFFFFFFFF).copy(alpha = 0.40f)
    val TextMuted35 = Color(0xFFFFFFFF).copy(alpha = 0.35f)

    // --- Accents ----------------------------------------------------------

    /** Accent blue — bundle (`#5b89ff`). Material `primary`. */
    val AccentBlue = Color(0xFF5B89FF)

    /** Accent light — eyebrows / icons (`#8fb8ff`). */
    val AccentLight = Color(0xFF8FB8FF)

    /** Active text on pressed controls (`#cfdaff`). */
    val AccentActiveText = Color(0xFFCFDAFF)

    /** Online/green dot — connect.jsx:243 (`#3ecf8e`). */
    val GreenDot = Color(0xFF3ECF8E)

    // --- TV-logo gradient — connect.jsx:147 (linear-gradient 135deg) -------
    val TvLogoStart = Color(0xFF4A72FF)
    val TvLogoEnd = Color(0xFF2C4ED4)

    // --- Touchpad disk — remote.jsx:63 (radial, circle at 35% 30%) --------
    val TouchpadDiskStart = Color(0xFF2A2E38) // 0%
    val TouchpadDiskMid = Color(0xFF181A20)   // 60%
    val TouchpadDiskEnd = Color(0xFF0F1115)   // 100%

    // --- Touchpad ring glow — remote.jsx:51 (rgba(91,137,255,0.18)) -------
    val RingGlow = Color(0xFF5B89FF).copy(alpha = 0.18f)

    // --- Center OK idle — remote.jsx:117 (radial, circle at 40% 35%) ------
    val CenterOkIdleStart = Color(0xFF1F232B)
    val CenterOkIdleEnd = Color(0xFF0D1015)

    // --- Center OK active — remote.jsx:116 + blue inset glow (remote.jsx:119)
    val CenterOkActiveStart = Color(0xFF2C3956)
    val CenterOkActiveEnd = Color(0xFF1A2236)

    // --- Round button idle — remote.jsx:142 (radial, circle at 35% 30%) --
    val RoundButtonIdleStart = Color(0xFF232730)
    val RoundButtonIdleEnd = Color(0xFF14171D)

    // --- Round button active — remote.jsx:141 (== center OK active stops) -
    val RoundButtonActiveStart = Color(0xFF2C3956)
    val RoundButtonActiveEnd = Color(0xFF1A2236)

    // --- Directional press glow — remote.jsx:93-96 (rgba(91,137,255,0.55))
    val DirGlow = Color(0xFF5B89FF).copy(alpha = 0.55f)

    // --- Misc shared accent alphas (status pill / borders) ----------------
    /** Accent fill at 0.08 (status-pill / current-card bg). */
    val AccentFill08 = Color(0xFF5B89FF).copy(alpha = 0.08f)

    /** Accent border at 0.18 (status pill). */
    val AccentBorder18 = Color(0xFF5B89FF).copy(alpha = 0.18f)

    /** Accent border at 0.30 (keyboard input box). */
    val AccentBorder30 = Color(0xFF5B89FF).copy(alpha = 0.30f)

    /** Accent border at 0.35 (current device card). */
    val AccentBorder35 = Color(0xFF5B89FF).copy(alpha = 0.35f)

    /** Hairline white border at 0.06 (card / sheet outline). */
    val HairlineBorder = Color(0xFFFFFFFF).copy(alpha = 0.06f)

    /** Subtle control surface fill (power button bg, rgba(255,255,255,0.06)). */
    val ControlSurface06 = Color(0xFFFFFFFF).copy(alpha = 0.06f)

    /** Idle digit-box / inactive control border (rgba(255,255,255,0.10)). */
    val InactiveBorder10 = Color(0xFFFFFFFF).copy(alpha = 0.10f)

    /** Standard outline-button border (rgba(255,255,255,0.12)). */
    val OutlineBorder12 = Color(0xFFFFFFFF).copy(alpha = 0.12f)

    // --- Scrims (overlays) ------------------------------------------------
    /** Keyboard overlay scrim — remote.jsx:239 (rgba(8,9,12,0.85)). */
    val OverlayScrim85 = Color(0xFF08090C).copy(alpha = 0.85f)

    /** Pairing-sheet scrim — connect.jsx:43 (rgba(8,9,12,0.72)). */
    val SheetScrim72 = Color(0xFF08090C).copy(alpha = 0.72f)

    /** Opaque blur fallback (API < 31) — see [Brushes] / overlay tradeoff. */
    val OverlayOpaqueFallback = Color(0xFF0B0C10)
}

// ---------------------------------------------------------------------------
// Deprecated Plan-1/2 names. Nothing outside the theme package referenced the
// old `AppleSurfaceLight/...`/`Accent` vals (grep clean at T1), but they are
// kept as @Deprecated aliases mapping to the nearest new token so any future
// stray reference still compiles. Later tasks (T2–T5) should delete usages and
// then drop these. Light-mode aliases map to their nearest DARK token (the
// design is dark-only).
// ---------------------------------------------------------------------------

@Deprecated(
    "Dark-only design: use DesignTokens.ConnectScreenBg",
    ReplaceWith("DesignTokens.ConnectScreenBg", "dev.atvremote.app.ui.theme.DesignTokens"),
)
val AppleSurfaceLight = DesignTokens.ConnectScreenBg

@Deprecated(
    "Use DesignTokens.RemoteBgEnd",
    ReplaceWith("DesignTokens.RemoteBgEnd", "dev.atvremote.app.ui.theme.DesignTokens"),
)
val AppleSurfaceDark = DesignTokens.RemoteBgEnd

@Deprecated(
    "Use DesignTokens.SurfaceCard",
    ReplaceWith("DesignTokens.SurfaceCard", "dev.atvremote.app.ui.theme.DesignTokens"),
)
val TrackpadLight = DesignTokens.SurfaceCard

@Deprecated(
    "Use DesignTokens.SurfaceCard",
    ReplaceWith("DesignTokens.SurfaceCard", "dev.atvremote.app.ui.theme.DesignTokens"),
)
val TrackpadDark = DesignTokens.SurfaceCard

@Deprecated(
    "Use DesignTokens.TextPrimary",
    ReplaceWith("DesignTokens.TextPrimary", "dev.atvremote.app.ui.theme.DesignTokens"),
)
val ButtonTintLight = DesignTokens.TextPrimary

@Deprecated(
    "Use DesignTokens.TextPrimary",
    ReplaceWith("DesignTokens.TextPrimary", "dev.atvremote.app.ui.theme.DesignTokens"),
)
val ButtonTintDark = DesignTokens.TextPrimary

@Deprecated(
    "Use DesignTokens.AccentBlue",
    ReplaceWith("DesignTokens.AccentBlue", "dev.atvremote.app.ui.theme.DesignTokens"),
)
val Accent = DesignTokens.AccentBlue
