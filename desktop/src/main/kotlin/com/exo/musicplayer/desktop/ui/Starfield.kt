package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private class Star(
    val x: Float,
    val y: Float,
    val radius: Float,
    val phase: Float,
    val speed: Float,
    val baseAlpha: Float,
    val drift: Float
)

/** About fifteen updates a second: a twinkle this slow looks the same as at sixty. */
private const val FRAME_MS = 66L

/** One full turn of twinkle phase, matching the phone's nine-second cycle. */
private const val CYCLE_SECONDS = 9f

/**
 * The phone's starfield, behind a desktop surface.
 *
 * The same stars and the same maths as Android's Starfield, with the changes a
 * window that stays open all day needs. It advances about fifteen times a
 * second instead of on every frame, because a full redraw sixty times a second
 * is exactly the standing GPU cost this app has been trimmed to avoid. It stops
 * advancing while the window is not focused, so a Resonate left in the
 * background draws nothing at all. And it lives in a graphics layer of its own
 * behind the content, with the time read inside the draw call, so each tick
 * repaints the stars without recomposing or re-recording anything in front of
 * them.
 *
 * Place it first inside a Box with Modifier.matchParentSize(), after the
 * surface's own background.
 */
@Composable
fun Starfield(
    enabled: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    count: Int = 90,
    seed: Int = 7,
    pauseWhenUnfocused: Boolean = true
) {
    if (!enabled) return

    val stars = remember(count, seed) {
        val random = Random(seed)
        List(count) {
            Star(
                x = random.nextFloat(),
                y = random.nextFloat(),
                // Mostly faint pinpricks with a few brighter ones, which reads far
                // more like a night sky than uniform dots.
                radius = if (random.nextFloat() > 0.9f) {
                    1.4f + random.nextFloat() * 1.1f
                } else {
                    0.5f + random.nextFloat() * 0.8f
                },
                phase = random.nextFloat() * (2f * PI.toFloat()),
                speed = 0.6f + random.nextFloat() * 1.5f,
                baseAlpha = 0.28f + random.nextFloat() * 0.5f,
                drift = 0.15f + random.nextFloat() * 0.5f
            )
        }
    }

    val seconds = remember { mutableFloatStateOf(0f) }
    val focused = LocalWindowInfo.current.isWindowFocused || !pauseWhenUnfocused

    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        // Carries on from where it stopped, so refocusing doesn't make the sky jump.
        val origin = System.nanoTime() - (seconds.floatValue * 1e9f).toLong()
        while (isActive) {
            seconds.floatValue = (System.nanoTime() - origin) / 1e9f
            delay(FRAME_MS)
        }
    }

    Spacer(
        modifier
            .graphicsLayer()
            .drawBehind {
                val width = size.width
                val height = size.height
                val turn = 2f * PI.toFloat()
                val time = seconds.floatValue * turn / CYCLE_SECONDS
                for (star in stars) {
                    val twinkle = sin(time * star.speed + star.phase)
                    val alpha = (star.baseAlpha + twinkle * 0.3f).coerceIn(0.05f, 0.95f)
                    // Gentle upward drift, wrapping at the top.
                    val y = ((star.y - time / turn * star.drift * 0.12f) % 1f + 1f) % 1f
                    val radius = star.radius * density
                    val center = Offset(star.x * width, y * height)

                    if (star.radius > 1.4f) {
                        // A soft halo makes the brighter stars read as light
                        // rather than as a flat dot.
                        val halo = radius * 3.6f
                        drawCircle(
                            Brush.radialGradient(
                                listOf(color.copy(alpha = alpha * 0.24f), color.copy(alpha = alpha * 0.07f), Color.Transparent),
                                center,
                                halo
                            ),
                            halo,
                            center
                        )
                    }
                    drawCircle(color.copy(alpha = alpha), radius, center)
                }
            }
    )
}
