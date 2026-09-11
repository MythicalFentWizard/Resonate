package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale

/**
 * The user's own picture behind everything, cropped to fill and darkened by
 * [dim] in the theme's base colour, so text stays readable on any picture.
 * The background effect draws over it, and the surfaces above turn
 * see-through while one is set.
 */
@Composable
fun Wallpaper(image: ImageBitmap?, dim: Float, modifier: Modifier = Modifier) {
    if (image == null) return
    Box(modifier) {
        Image(
            image,
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High
        )
        Box(Modifier.matchParentSize().background(Palette.Base.copy(alpha = dim)))
    }
}
