package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import kotlin.math.min

/**
 * Lays [content] out at the size it was designed for and scales all of it -
 * text, spacing, panels, controls - to fit the window.
 *
 * A layout squeezed below its design size wraps, clips and overlaps; the same
 * layout drawn smaller does none of that, so a small window shows exactly the
 * big one in miniature. The scale follows the window continuously as it is
 * resized, never goes above 1 (a large window keeps everything at its normal
 * size, with more room) and stops at [minScale]; each window sets a minimum
 * size so it cannot be dragged smaller than that still fits.
 */
@Composable
fun ScaledToWindow(
    designWidth: Dp,
    designHeight: Dp,
    minScale: Float = 0.62f,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = min(maxWidth / designWidth, maxHeight / designHeight).coerceIn(minScale, 1f)
        val outer = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(outer.density * scale, outer.fontScale)) {
            content()
        }
    }
}
