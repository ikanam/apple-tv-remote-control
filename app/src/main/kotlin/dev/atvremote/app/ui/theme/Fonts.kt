@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package dev.atvremote.app.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.atvremote.app.R

/**
 * Compose [FontFamily]s for the Claude-Design reskin.
 *
 *  - [InterFontFamily]      — UI text (weights 400/500/600/700)
 *  - [JetBrainsMonoFontFamily] — eyebrows / codes / badges / IP (400/500/600/700)
 *
 * ## Bundled resources (T1, fetched from the SIL-OFL upstreams)
 *  - JetBrains Mono: four **static** TTFs
 *    (`jetbrains_mono_{regular,medium,semibold,bold}.ttf`).
 *  - Inter: a single **variable** TTF (`inter_variable.ttf`). The spec
 *    explicitly permits the Inter variable font as one resource; weights are
 *    selected with [FontVariation] `wght` axis settings, so 400/500/600/700
 *    all resolve from the one file. License text for both families is kept at
 *    `docs/superpowers/design/2026-05-17-tv-remote-claude-design/fonts-OFL.txt`.
 *
 * ## Graceful fallback (REQUIRED — never crash on a missing font)
 * The families are built through [bundledFamilyOrFallback]. If the design
 * resources are ever absent / renamed (e.g. a future variant build strips the
 * `res/font/` payload), each `R.font.*` reference becomes an unresolved id at
 * compile time — which is exactly why the resolution is centralised here: the
 * single place to swap to `FontFamily.Default` / `FontFamily.Monospace` is the
 * two `runCatching` builders below, and every call site keeps using
 * [InterFontFamily] / [JetBrainsMonoFontFamily] unchanged.
 *
 * `Font(...)` construction itself does not touch the filesystem (the glyph
 * data is lazily loaded by the text layout engine), so a *structurally*
 * missing resource cannot throw here; the `runCatching` guards the unlikely
 * construction failure and documents the fallback contract for later tasks
 * that may switch to a downloadable / fully-static font set.
 */

/** Weight → Inter variable-axis Font. */
private fun interVariation(weight: FontWeight): Font =
    Font(
        resId = R.font.inter_variable,
        weight = weight,
        style = FontStyle.Normal,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(weight.weight),
        ),
    )

/**
 * Inter (UI). Variable font, weights 400/500/600/700 via the `wght` axis.
 * Falls back to [FontFamily.Default] if the bundled resource is unavailable.
 */
val InterFontFamily: FontFamily =
    bundledFamilyOrFallback(FontFamily.Default) {
        FontFamily(
            interVariation(FontWeight.Normal),   // 400
            interVariation(FontWeight.Medium),   // 500
            interVariation(FontWeight.SemiBold), // 600
            interVariation(FontWeight.Bold),     // 700
        )
    }

/**
 * JetBrains Mono (eyebrows / codes / badges / ip). Four static weights.
 * Falls back to [FontFamily.Monospace] if a bundled resource is unavailable.
 */
val JetBrainsMonoFontFamily: FontFamily =
    bundledFamilyOrFallback(FontFamily.Monospace) {
        FontFamily(
            Font(R.font.jetbrains_mono_regular, FontWeight.Normal),   // 400
            Font(R.font.jetbrains_mono_medium, FontWeight.Medium),    // 500
            Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),// 600
            Font(R.font.jetbrains_mono_bold, FontWeight.Bold),        // 700
        )
    }

/**
 * Builds a bundled [FontFamily], degrading to [fallback] if construction
 * throws. Keeps the app from ever crashing on a missing/renamed font resource
 * while preserving the abstraction so real TTFs can be dropped in later.
 */
private inline fun bundledFamilyOrFallback(
    fallback: FontFamily,
    build: () -> FontFamily,
): FontFamily = runCatching(build).getOrDefault(fallback)
