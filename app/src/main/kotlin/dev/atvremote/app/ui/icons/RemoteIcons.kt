package dev.atvremote.app.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Claude-Design stroke icon set, ported 1:1 from the in-repo bundle
 * `docs/superpowers/design/2026-05-17-tv-remote-claude-design/project/icons.jsx`.
 *
 * Every icon is a 24×24 viewBox `<svg>` rendered on a [Canvas]: the SVG path
 * `d` strings are reused verbatim via [PathParser] so the geometry is exact;
 * `strokeLinecap`/`strokeLinejoin` = round and the default `sw` (stroke width)
 * is **1.8** to match `icons.jsx`'s `<Icon sw=1.8>` default. Call sites pass
 * the stroke width the JSX used per usage (`sw={2}` etc.) via [strokeWidth].
 *
 * Canvas (not ImageVector) is used because several glyphs combine stroked and
 * filled sub-paths (`IconPlayPause`, `IconWifi` dot) with different paint at
 * `icons.jsx`-exact coordinates — a single Canvas keeps that faithful and
 * avoids per-call ImageVector cache churn.
 *
 * `tint` defaults to [Color.Unspecified] ⇒ the icon paints with the caller's
 * supplied [color]; pass an explicit color for the "active"/"pressed" states
 * the design defines (`#cfdaff` etc.) — that styling is owned by T2's
 * primitives and the T3/T4 screens, not here.
 */

/** Scale factor: the JSX viewBox is 24 units; Canvas works in px of [size]. */
private const val VIEW = 24f

private fun DrawScope.strokePath(
    pathData: String,
    color: Color,
    swPx: Float,
) {
    val p = PathParser().parsePathString(pathData).toPath()
    val s = size.minDimension / VIEW
    drawScaledPath(p, s, color, Stroke(width = swPx * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.fillPath(pathData: String, color: Color) {
    val p = PathParser().parsePathString(pathData).toPath()
    val s = size.minDimension / VIEW
    drawScaledPath(p, s, color, Fill)
}

private fun DrawScope.drawScaledPath(
    path: Path,
    s: Float,
    color: Color,
    style: DrawStyle,
) {
    // Center the 24-unit artwork in a possibly non-square box (icons are square
    // in practice, but keep it robust): translate then scale about origin.
    val drawW = VIEW * s
    val tx = (size.width - drawW) / 2f
    val ty = (size.height - drawW) / 2f
    translate(tx, ty) {
        scale(s, s, pivot = Offset.Zero) {
            drawPath(path, color, style = style)
        }
    }
}

/** Common composable scaffold: a square [size] Canvas drawing [block]. */
@Composable
private fun IconCanvas(
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    block: DrawScope.(Color) -> Unit,
) {
    Canvas(modifier = modifier.size(size)) { block(color) }
}

// --- stroke icons (icons.jsx:9-11,20-49) ----------------------------------

/** icons.jsx:9 — `M15 5l-7 7 7 7`. */
@Composable
fun IconBack(size: Dp = 24.dp, color: Color, strokeWidth: Float = 1.8f, modifier: Modifier = Modifier) =
    IconCanvas(size, color, modifier) { c -> strokePath("M15 5l-7 7 7 7", c, strokeWidth) }

/** icons.jsx:10 — `M9 5l7 7-7 7` (right-pointing chevron). */
@Composable
fun IconChevron(size: Dp = 24.dp, color: Color, strokeWidth: Float = 1.8f, modifier: Modifier = Modifier) =
    IconCanvas(size, color, modifier) { c -> strokePath("M9 5l7 7-7 7", c, strokeWidth) }

/**
 * Chevron-down — used by the Remote top-bar device switcher
 * (remote.jsx:304: `<path d="M6 9l6 6 6-6"/>`).
 */
@Composable
fun IconChevronDown(size: Dp = 24.dp, color: Color, strokeWidth: Float = 1.8f, modifier: Modifier = Modifier) =
    IconCanvas(size, color, modifier) { c -> strokePath("M6 9l6 6 6-6", c, strokeWidth) }

/** icons.jsx:11 — rect 3,5 18×12 r2 + `M8 21h8M12 17v4`. */
@Composable
fun IconTV(size: Dp = 24.dp, color: Color, strokeWidth: Float = 1.8f, modifier: Modifier = Modifier) =
    IconCanvas(size, color, modifier) { c ->
        // rounded rect (rx=2) drawn as a path so it scales/strokes uniformly.
        strokePath(roundedRect(3f, 5f, 18f, 12f, 2f), c, strokeWidth)
        strokePath("M8 21h8M12 17v4", c, strokeWidth)
    }

/** icons.jsx:12 — filled play triangle `M7 5.5v13l11-6.5z`. */
@Composable
fun IconPlay(size: Dp = 24.dp, color: Color, modifier: Modifier = Modifier) =
    IconCanvas(size, color, modifier) { c -> fillPath("M7 5.5v13l11-6.5z", c) }

/**
 * icons.jsx:13-19 — combined ▶ + ‖ glyph: filled triangle `M3 5.5v13l9-6.5z`
 * plus two filled rounded bars at x=14 / x=18.4 (w 2.6, h 13, ry 0.5). Drawn
 * fill-only with `stroke="none"` exactly as the JSX `<Icon fill stroke=none>`.
 */
@Composable
fun IconPlayPause(size: Dp = 24.dp, color: Color, modifier: Modifier = Modifier) =
    IconCanvas(size, color, modifier) { c ->
        fillPath("M3 5.5v13l9-6.5z", c)
        fillPath(roundedRect(14f, 5.5f, 2.6f, 13f, 0.5f), c)
        fillPath(roundedRect(18.4f, 5.5f, 2.6f, 13f, 0.5f), c)
    }

/** icons.jsx:20 — `M12 6v12M6 12h12`. */
@Composable
fun IconPlus(size: Dp = 24.dp, color: Color, strokeWidth: Float = 1.8f, modifier: Modifier = Modifier) =
    IconCanvas(size, color, modifier) { c -> strokePath("M12 6v12M6 12h12", c, strokeWidth) }

/** icons.jsx:21 — `M6 12h12`. */
@Composable
fun IconMinus(size: Dp = 24.dp, color: Color, strokeWidth: Float = 1.8f, modifier: Modifier = Modifier) =
    IconCanvas(size, color, modifier) { c -> strokePath("M6 12h12", c, strokeWidth) }

/** icons.jsx:22-27 — rect 2.5,6 19×12 r2 + key dots/spacebar path. */
@Composable
fun IconKeyboard(size: Dp = 24.dp, color: Color, strokeWidth: Float = 1.8f, modifier: Modifier = Modifier) =
    IconCanvas(size, color, modifier) { c ->
        strokePath(roundedRect(2.5f, 6f, 19f, 12f, 2f), c, strokeWidth)
        strokePath(
            "M6 10h.01M9 10h.01M12 10h.01M15 10h.01M18 10h.01" +
                "M6 13.5h.01M9 13.5h.01M15 13.5h.01M18 13.5h.01M8.5 16h7",
            c, strokeWidth,
        )
    }

/** icons.jsx:28 — `M12 4v8` + arc `M6.3 7.7a8 8 0 1 0 11.4 0`. */
@Composable
fun IconPower(size: Dp = 24.dp, color: Color, strokeWidth: Float = 1.8f, modifier: Modifier = Modifier) =
    IconCanvas(size, color, modifier) { c ->
        strokePath("M12 4v8", c, strokeWidth)
        strokePath("M6.3 7.7a8 8 0 1 0 11.4 0", c, strokeWidth)
    }

/** icons.jsx:29-36 — 3 Wi-Fi arcs + a filled center dot at (12,19) r0.8. */
@Composable
fun IconWifi(size: Dp = 24.dp, color: Color, strokeWidth: Float = 1.8f, modifier: Modifier = Modifier) =
    IconCanvas(size, color, modifier) { c ->
        strokePath("M2.5 9.2a14 14 0 0 1 19 0", c, strokeWidth)
        strokePath("M5.5 12.4a10 10 0 0 1 13 0", c, strokeWidth)
        strokePath("M8.5 15.6a6 6 0 0 1 7 0", c, strokeWidth)
        fillPath(circle(12f, 19f, 0.8f), c)
    }

/** icons.jsx:37-44 — refresh double-arrow (4 sub-paths). */
@Composable
fun IconRefresh(size: Dp = 24.dp, color: Color, strokeWidth: Float = 1.8f, modifier: Modifier = Modifier) =
    IconCanvas(size, color, modifier) { c ->
        strokePath("M3.5 12a8.5 8.5 0 0 1 14.5-6L21 9", c, strokeWidth)
        strokePath("M21 4v5h-5", c, strokeWidth)
        strokePath("M20.5 12a8.5 8.5 0 0 1-14.5 6L3 15", c, strokeWidth)
        strokePath("M3 20v-5h5", c, strokeWidth)
    }

/** icons.jsx:45 — `M5 12.5l4.5 4.5L19 7`. */
@Composable
fun IconCheck(size: Dp = 24.dp, color: Color, strokeWidth: Float = 1.8f, modifier: Modifier = Modifier) =
    IconCanvas(size, color, modifier) { c -> strokePath("M5 12.5l4.5 4.5L19 7", c, strokeWidth) }

/** icons.jsx:46-51 — gear: circle r2.8 + the cog rim path. */
@Composable
fun IconSettings(size: Dp = 24.dp, color: Color, strokeWidth: Float = 1.8f, modifier: Modifier = Modifier) =
    IconCanvas(size, color, modifier) { c ->
        strokePath(circle(12f, 12f, 2.8f), c, strokeWidth)
        strokePath(
            "M19.4 13.5a7.7 7.7 0 0 0 0-3l2-1.5-2-3.4-2.4.9a7.7 7.7 0 0 0-2.6-1.5L14 2h-4" +
                "l-.4 2.5a7.7 7.7 0 0 0-2.6 1.5l-2.4-.9-2 3.4 2 1.5a7.7 7.7 0 0 0 0 3l-2 1.5 2 3.4 " +
                "2.4-.9a7.7 7.7 0 0 0 2.6 1.5L10 22h4l.4-2.5a7.7 7.7 0 0 0 2.6-1.5l2.4.9 2-3.4z",
            c, strokeWidth,
        )
    }

/** icons.jsx:52-57 — a filled dot with a soft halo (`box-shadow 0 0 0 4px c22`). */
@Composable
fun IconDot(
    size: Dp = 8.dp,
    color: Color = androidx.compose.ui.graphics.Color(0xFF3ECF8E),
    modifier: Modifier = Modifier,
) = IconCanvas(size, color, modifier) { c ->
    // The CSS halo is a 4px ring at 0x22 (≈13%) alpha around the dot. Approx:
    // draw the halo as a larger faint disk, then the solid dot. Dot fills the
    // box; halo extends conceptually beyond — clamped to the box here.
    drawCircle(c.copy(alpha = 0.13f), radius = size.toPx() * 0.5f)
    drawCircle(c, radius = size.toPx() * 0.32f)
}

/** icons.jsx:58-65 — indeterminate spinner: faint track + a 90° bright arc. */
@Composable
fun IconSpinner(
    size: Dp = 18.dp,
    color: Color,
    angleDeg: Float = 0f,
    modifier: Modifier = Modifier,
) = IconCanvas(size, color, modifier) { c ->
    val s = this.size.minDimension / VIEW
    val sw = 2.5f * s
    val r = 9f * s
    val cx = this.size.width / 2f
    val cy = this.size.height / 2f
    // faint full track (opacity 0.2).
    drawCircle(
        color = c.copy(alpha = 0.2f),
        radius = r,
        center = Offset(cx, cy),
        style = Stroke(width = sw),
    )
    // bright 90° arc (icons.jsx path `M21 12a9 9 0 0 0-9-9`), rotated by
    // [angleDeg] so callers can drive it with an infinite rotation animation.
    rotate(angleDeg, pivot = Offset(cx, cy)) {
        drawArc(
            color = c,
            startAngle = -90f,
            sweepAngle = -90f,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
    }
}

// --- path helpers ----------------------------------------------------------

/** SVG rounded-rect as a path `d` string (matches `<rect rx=ry>`). */
private fun roundedRect(x: Float, y: Float, w: Float, h: Float, r: Float): String {
    val rr = minOf(r, w / 2f, h / 2f)
    // M x+rr y  ->  top edge  ->  rounded corners clockwise.
    return buildString {
        append("M${x + rr} $y")
        append("H${x + w - rr}")
        append("A$rr $rr 0 0 1 ${x + w} ${y + rr}")
        append("V${y + h - rr}")
        append("A$rr $rr 0 0 1 ${x + w - rr} ${y + h}")
        append("H${x + rr}")
        append("A$rr $rr 0 0 1 $x ${y + h - rr}")
        append("V${y + rr}")
        append("A$rr $rr 0 0 1 ${x + rr} $y")
        append("Z")
    }
}

/** SVG circle as two arcs (used where icons.jsx draws `<circle>`). */
private fun circle(cx: Float, cy: Float, r: Float): String =
    "M${cx - r} $cy" +
        "a$r $r 0 1 0 ${r * 2} 0" +
        "a$r $r 0 1 0 ${-r * 2} 0Z"
