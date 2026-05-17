package dev.atvremote.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T1 sanity: design tokens equal the exact spec/bundle hex, and the font
 * families resolve without throwing (graceful-fallback contract). Visual
 * brush correctness is out of scope for a unit test (no pixel harness).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DesignSystemTest {

    private fun Color.hex(): Int = this.toArgb()

    @Test fun tokens_match_spec_hex() {
        // Opaque core tokens (spec "Design tokens" table + JSX sources).
        assertEquals(0xFF0E1014.toInt(), DesignTokens.ConnectScreenBg.hex())
        assertEquals(0xFF0E1014.toInt(), DesignTokens.InsetFieldBg.hex())
        assertEquals(0xFF16181D.toInt(), DesignTokens.SurfaceCard.hex())
        assertEquals(0xFF16181D.toInt(), DesignTokens.SheetBg.hex())
        assertEquals(0xFF181C25.toInt(), DesignTokens.RemoteBgStart.hex())
        assertEquals(0xFF0C0E13.toInt(), DesignTokens.RemoteBgMid.hex())
        assertEquals(0xFF08090C.toInt(), DesignTokens.RemoteBgEnd.hex())
        assertEquals(0xFFFFFFFF.toInt(), DesignTokens.TextPrimary.hex())
        assertEquals(0xFFE9EAEE.toInt(), DesignTokens.TextPrimarySoft.hex())
        assertEquals(0xFF5B89FF.toInt(), DesignTokens.AccentBlue.hex())
        assertEquals(0xFF8FB8FF.toInt(), DesignTokens.AccentLight.hex())
        assertEquals(0xFFCFDAFF.toInt(), DesignTokens.AccentActiveText.hex())
        assertEquals(0xFF3ECF8E.toInt(), DesignTokens.GreenDot.hex())
        assertEquals(0xFF4A72FF.toInt(), DesignTokens.TvLogoStart.hex())
        assertEquals(0xFF2C4ED4.toInt(), DesignTokens.TvLogoEnd.hex())
        assertEquals(0xFF2A2E38.toInt(), DesignTokens.TouchpadDiskStart.hex())
        assertEquals(0xFF181A20.toInt(), DesignTokens.TouchpadDiskMid.hex())
        assertEquals(0xFF0F1115.toInt(), DesignTokens.TouchpadDiskEnd.hex())
        assertEquals(0xFF1F232B.toInt(), DesignTokens.CenterOkIdleStart.hex())
        assertEquals(0xFF0D1015.toInt(), DesignTokens.CenterOkIdleEnd.hex())
        assertEquals(0xFF2C3956.toInt(), DesignTokens.CenterOkActiveStart.hex())
        assertEquals(0xFF1A2236.toInt(), DesignTokens.CenterOkActiveEnd.hex())
        assertEquals(0xFF232730.toInt(), DesignTokens.RoundButtonIdleStart.hex())
        assertEquals(0xFF14171D.toInt(), DesignTokens.RoundButtonIdleEnd.hex())
    }

    @Test fun alpha_overlay_tokens_have_expected_base_and_alpha() {
        // rgba(91,137,255,0.18) ring glow.
        assertEquals(0xFF5B89FF.toInt() and 0x00FFFFFF, DesignTokens.RingGlow.hex() and 0x00FFFFFF)
        assertEquals(0.18f, DesignTokens.RingGlow.alpha, 0.005f)
        // rgba(91,137,255,0.55) directional glow.
        assertEquals(0.55f, DesignTokens.DirGlow.alpha, 0.005f)
        // rgba(8,9,12,0.85) keyboard scrim, rgba(8,9,12,0.72) sheet scrim.
        assertEquals(0.85f, DesignTokens.OverlayScrim85.alpha, 0.005f)
        assertEquals(0.72f, DesignTokens.SheetScrim72.alpha, 0.005f)
    }

    @Test fun font_families_resolve_without_throwing() {
        // Graceful-fallback contract: building the families must never throw,
        // and they must produce a non-null FontFamily (bundled or fallback).
        assertNotNull(InterFontFamily)
        assertNotNull(JetBrainsMonoFontFamily)
        assertNotNull(AtvTypography.bodyLarge.fontFamily)
        assertEquals(InterFontFamily, AtvTypography.bodyLarge.fontFamily)
        assertEquals(JetBrainsMonoFontFamily, monoEyebrow.fontFamily)
    }

    @Test fun theme_color_scheme_is_fixed_dark() {
        // primary == accent blue, background == remote mid (#0C0E13 fallback).
        val scheme = AtvDarkColorScheme
        assertEquals(0xFF5B89FF.toInt(), scheme.primary.hex())
        assertEquals(0xFF0C0E13.toInt(), scheme.background.hex())
        assertEquals(0xFF16181D.toInt(), scheme.surface.hex())
        assertTrue(scheme.onBackground.hex() == 0xFFE9EAEE.toInt())
    }
}
