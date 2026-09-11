package com.exo.musicplayer.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private data class Star(
    val x: Float,
    val y: Float,
    val radius: Float,
    val phase: Float,
    val speed: Float,
    val baseAlpha: Float,
    val drift: Float
)

/**
 * A slow, twinkling starfield.
 *
 * One infinite animation drives every star, with each star's brightness derived
 * from a per-star phase offset. Animating stars individually would mean hundreds
 * of running animations and a recomposition storm; this recomposes one float and
 * redraws a single Canvas.
 */
@Composable
fun Starfield(
    modifier: Modifier = Modifier,
    starColor: Color = Color.White,
    starCount: Int = 90,
    seed: Int = 7
) {
    val stars = remember(starCount, seed) {
        val random = Random(seed)
        List(starCount) {
            Star(
                x = random.nextFloat(),
                y = random.nextFloat(),
                // Mostly faint pinpricks with a few brighter ones, which reads
                // far more like a night sky than uniform dots.
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

    val transition = rememberInfiniteTransition(label = "starfield")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "twinkle"
    )

    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        for (star in stars) {
            val twinkle = sin(time * star.speed + star.phase)
            val alpha = (star.baseAlpha + twinkle * 0.3f).coerceIn(0.05f, 0.95f)
            // Gentle upward drift, wrapping at the top.
            val y = ((star.y - time / (2f * PI.toFloat()) * star.drift * 0.12f) % 1f + 1f) % 1f
            val radius = star.radius * density

            if (star.radius > 1.4f) {
                // A soft halo makes the brighter stars feel like light rather
                // than a flat dot.
                val centre = Offset(star.x * w, y * h)
                val halo = radius * 3.6f
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            starColor.copy(alpha = alpha * 0.24f),
                            starColor.copy(alpha = alpha * 0.07f),
                            Color.Transparent
                        ),
                        centre,
                        halo
                    ),
                    radius = halo,
                    center = centre
                )
            }
            drawCircle(
                color = starColor.copy(alpha = alpha),
                radius = radius,
                center = Offset(star.x * w, y * h)
            )
        }
    }
}
