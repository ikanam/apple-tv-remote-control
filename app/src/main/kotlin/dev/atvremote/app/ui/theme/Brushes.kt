package dev.atvremote.app.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import kotlin.math.hypot

/**
 * Reusable Compose [Brush]es for the Claude-Design reskin, lifted from the
 * bundle (`project/remote.jsx`, `project/connect.jsx`) and the spec's "Design
 * tokens" table. Pure functions, no state.
 *
 * ## CSS → Compose translation notes
 * CSS `radial-gradient(circle at X% Y%, ...)` positions the gradient *center*
 * at a fraction of the painted box and (for `circle`) sizes the radius to the
 * farthest corner. `Brush.radialGradient(center=, radius=)` takes **pixels**,
 * so a brush created once cannot know the box it will paint. To keep these
 * helpers pure *and* honor the fractional centers at any size, the radial
 * brushes are [ShaderBrush]es that build their shader from the actual draw
 * `size` (`createShader(size)`), with the radius derived per CSS sizing
 * keyword. Linear gradients map directly. Where the CSS uses `ellipse`/
 * `closest-side` the nearest faithful circular/linear approximation is used
 * and called out inline (a perfect CSS-radial match needs a custom shader
 * matrix; the visual delta is negligible at these sizes — documented per the
 * spec's "approximate the CSS radial … document the approximation").
 */
object Brushes {

    // --- helpers ----------------------------------------------------------

    /** Fractional center → pixel [Offset] for a given draw [size]. */
    private fun center(size: Size, fx: Float, fy: Float): Offset =
        Offset(size.width * fx, size.height * fy)

    /** CSS `circle` (no size kw) ⇒ radius to the farthest corner. */
    private fun farthestCornerRadius(size: Size, c: Offset): Float {
        val dx = maxOf(c.x, size.width - c.x)
        val dy = maxOf(c.y, size.height - c.y)
        return hypot(dx, dy)
    }

    /** CSS `closest-side` ⇒ radius to the nearest edge. */
    private fun closestSideRadius(size: Size, c: Offset): Float =
        minOf(c.x, size.width - c.x, c.y, size.height - c.y)
            .coerceAtLeast(0.01f)

    /**
     * A [ShaderBrush] for a CSS `radial-gradient(circle at fx% fy%, stops)`.
     * [radiusOf] selects the CSS sizing keyword (default = `circle`/farthest
     * corner). Color stops are evenly spaced unless [stops] is given.
     */
    private fun cssRadial(
        fx: Float,
        fy: Float,
        colors: List<Color>,
        stops: List<Float>? = null,
        radiusOf: (Size, Offset) -> Float = ::farthestCornerRadius,
    ): Brush = object : ShaderBrush() {
        override fun createShader(size: Size): Shader {
            val c = center(size, fx, fy)
            return RadialGradientShader(
                center = c,
                radius = radiusOf(size, c).coerceAtLeast(0.01f),
                colors = colors,
                colorStops = stops,
                tileMode = TileMode.Clamp,
            )
        }
    }

    // --- Screen backgrounds ----------------------------------------------

    /**
     * Remote screen bg — remote.jsx:289
     * `radial-gradient(120% 80% at 50% 0%, #181c25 0%, #0c0e13 60%, #08090c 100%)`.
     *
     * Approximation: the CSS sizes the gradient ellipse to 120%×80% of the box
     * centered at top-middle; Compose `RadialGradientShader` is circular. We
     * center at (50%, 0%) and use radius = 1.2 × width (the CSS horizontal
     * extent, the dominant dimension on a phone-portrait remote) so the
     * #08090c outer stop reaches the bottom corners — visually matching the
     * top-lit vignette. Documented per spec.
     */
    // Recolor (owner): RemoteScreen background is pure black.
    fun remoteScreenBackground(): Brush = SolidColor(Color(0xFF000000))

    /** Connect screen bg — connect.jsx:128: solid `#0e1014`. */
    fun connectScreenBackground(): Brush =
        SolidColor(DesignTokens.ConnectScreenBg)

    // --- Touchpad ---------------------------------------------------------

    /**
     * Touchpad base disk — remote.jsx:63
     * `radial-gradient(circle at 35% 30%, #2a2e38 0%, #181a20 60%, #0f1115 100%)`.
     */
    // Recolor (owner): trackpad surface = the flat button color #1A1A1C.
    fun touchpadDisk(): Brush = SolidColor(Color(0xFF1A1A1C))

    /**
     * Touchpad outer ring glow — remote.jsx:51
     * `radial-gradient(closest-side, rgba(91,137,255,0.18), transparent 70%)`.
     * Centered (default 50/50); fades to transparent by 70% of the closest
     * side (CSS `closest-side`).
     */
    fun touchpadRingGlow(): Brush = cssRadial(
        fx = 0.5f, fy = 0.5f,
        colors = listOf(DesignTokens.RingGlow, Color.Transparent),
        stops = listOf(0.0f, 0.70f),
        radiusOf = ::closestSideRadius,
    )

    // --- Center OK --------------------------------------------------------

    /** Center OK idle — remote.jsx:117
     *  `radial-gradient(circle at 40% 35%, #1f232b, #0d1015)`. */
    // Recolor (owner): confirm/OK button — #27282B (owner-picked), lighter
    // than the #1A1A1C buttons so it reads as the primary action.
    fun centerOkIdle(): Brush = SolidColor(Color(0xFF27282B))

    /** Center OK active — remote.jsx:116
     *  `radial-gradient(circle at 40% 35%, #2c3956, #1a2236)` (+ blue inset
     *  glow drawn separately as a border/shadow by the screen task). */
    // Recolor (owner): confirm/OK pressed — a touch lighter than #27282B.
    fun centerOkActive(): Brush = SolidColor(Color(0xFF34353A))

    // --- Round button -----------------------------------------------------

    /** Round button idle — remote.jsx:142
     *  `radial-gradient(circle at 35% 30%, #232730, #14171d)`. */
    // Recolor (owner): remote buttons (except confirm) are flat #1A1A1C;
    // press = scale + the blue glow ring drawn by RemoteButton.
    fun roundButtonIdle(): Brush = SolidColor(Color(0xFF1A1A1C))

    /** Round button active — remote.jsx:141
     *  `radial-gradient(circle at 35% 30%, #2c3956, #1a2236)`. */
    fun roundButtonActive(): Brush = SolidColor(Color(0xFF1A1A1C))

    /**
     * VolumePill base — remote.jsx:197
     * `radial-gradient(circle at 35% 20%, #232730, #14171d)` (same stops as
     * the idle round button, different center). Exposed so T2's VolumePill
     * doesn't re-derive it.
     */
    // Recolor (owner): volume pill matches the flat button color #1A1A1C.
    fun volumePill(): Brush = SolidColor(Color(0xFF1A1A1C))

    // --- TV logo ----------------------------------------------------------

    /**
     * TV-logo tile — connect.jsx:147 `linear-gradient(135deg, #4a72ff,
     * #2c4ed4)`. CSS 135° points to the bottom-right, so the gradient runs
     * top-left → bottom-right.
     */
    fun tvLogoGradient(): Brush = Brush.linearGradient(
        colors = listOf(DesignTokens.TvLogoStart, DesignTokens.TvLogoEnd),
        start = Offset(0f, 0f),
        end = Offset.Infinite, // top-left → bottom-right ≈ CSS 135deg
    )

    // --- Directional press glow ------------------------------------------

    /** Touchpad edge for [directionalGlow]. */
    enum class GlowEdge { Up, Right, Down, Left }

    /**
     * Directional press glow — remote.jsx:93-96, e.g. Up:
     * `radial-gradient(ellipse 80% 60% at 50% 0%, rgba(91,137,255,0.55),
     * transparent 60%)`. The CSS uses an ellipse anchored to the pressed edge;
     * approximated with a circular [ShaderBrush] centered on that edge,
     * fading to transparent by 60% (radius = farthest corner) — the visual
     * "light from the edge" reads the same on the round pad. Documented per
     * spec.
     */
    fun directionalGlow(edge: GlowEdge): Brush {
        val (fx, fy) = when (edge) {
            GlowEdge.Up -> 0.5f to 0.0f
            GlowEdge.Right -> 1.0f to 0.5f
            GlowEdge.Down -> 0.5f to 1.0f
            GlowEdge.Left -> 0.0f to 0.5f
        }
        return cssRadial(
            fx = fx, fy = fy,
            colors = listOf(DesignTokens.DirGlow, Color.Transparent),
            stops = listOf(0.0f, 0.60f),
        )
    }
}
