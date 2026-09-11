package com.exo.musicplayer.ui.theme

import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * True when the starfield is painted behind the app.
 *
 * Screens read this to drop their own opaque background — a Scaffold's default
 * container colour would otherwise cover the stars completely.
 */
val LocalStarfieldActive = staticCompositionLocalOf { false }

/** Whether the stars themselves are showing, which [LocalStarfieldActive] no longer says alone. */
val LocalStarsVisible = staticCompositionLocalOf { false }

/** The user's wallpaper, decoded, and how far it is dimmed; null for none. */
val LocalWallpaper = staticCompositionLocalOf<ImageBitmap?> { null }
val LocalWallpaperDim = staticCompositionLocalOf { 0.45f }

/** Lyric colours chosen in Appearance; null follows the theme. */
data class LyricsColors(val active: Color? = null, val inactive: Color? = null)

val LocalLyricsColors = staticCompositionLocalOf { LyricsColors() }

/** Slightly rounder than the Material default; softer without looking like a toy. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MusicPlayerTheme(
    theme: ThemeState = ThemeState(),
    content: @Composable () -> Unit
) {
    val dark = when (theme.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current

    val colorScheme = when {
        theme.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> theme.palette.dark
        else -> theme.palette.light
    }

    // Stars only in dark mode: on a light background they read as smudges.
    val starsActive = theme.stars && dark

    // Decoded off the main thread, and again only when a new picture is chosen.
    val wallpaper by produceState<ImageBitmap?>(null, theme.wallpaper) {
        value = if (theme.wallpaper == 0L) {
            null
        } else {
            withContext(Dispatchers.IO) { decodeWallpaper(File(context.filesDir, "wallpaper.img")) }
        }
    }
    val backdrop = starsActive || wallpaper != null

    MaterialTheme(colorScheme = colorScheme, shapes = AppShapes) {
        CompositionLocalProvider(
            LocalStarfieldActive provides backdrop,
            LocalStarsVisible provides starsActive,
            LocalWallpaper provides wallpaper,
            LocalWallpaperDim provides theme.wallpaperDim,
            LocalLyricsColors provides LyricsColors(
                active = theme.lyricsActive?.let { Color(it) },
                inactive = theme.lyricsInactive?.let { Color(it) }
            )
        ) {
            if (backdrop) {
                Box(Modifier.fillMaxSize()) {
                    if (wallpaper == null) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            colorScheme.background,
                                            colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            colorScheme.background
                                        )
                                    )
                                )
                        )
                        Starfield(starColor = starTint(colorScheme.primary))
                    } else {
                        ScreenBackdrop()
                    }
                    content()
                }
            } else {
                content()
            }
        }
    }
}

/**
 * The wallpaper, dimmed in the theme's background colour, with the stars over
 * it when they are on. Full-screen pages that cover the app draw this so they
 * keep the same backdrop.
 */
@Composable
fun ScreenBackdrop() {
    val wallpaper = LocalWallpaper.current
    Box(Modifier.fillMaxSize()) {
        if (wallpaper != null) {
            Image(
                wallpaper,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = LocalWallpaperDim.current))
            )
        }
        if (LocalStarsVisible.current) {
            Starfield(starColor = starTint(MaterialTheme.colorScheme.primary))
        }
    }
}

/** A phone-screen-sized decode: the long edge at most 2400 px, whatever the photo's size. */
private fun decodeWallpaper(file: File): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2400) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

/** Stars pick up a hint of the palette so they belong to the theme. */
private fun starTint(primary: Color): Color = Color(
    red = (primary.red * 0.35f + 0.65f).coerceIn(0f, 1f),
    green = (primary.green * 0.35f + 0.65f).coerceIn(0f, 1f),
    blue = (primary.blue * 0.35f + 0.68f).coerceIn(0f, 1f),
    alpha = 1f
)
