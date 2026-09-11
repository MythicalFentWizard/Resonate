package com.exo.musicplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.playback.Spectrum
import kotlinx.coroutines.delay

/**
 * Band levels of the audio actually playing, as on the Windows build.
 *
 * The same shared analyser and the same band spacing, fed from the ExoPlayer
 * sink rather than a sound-card buffer, so a given track reads the same on both
 * platforms.
 *
 * The analyser is switched on only while this is composed. It costs one
 * 1024-point FFT per audio buffer, which is worth paying while somebody is
 * looking at it and not otherwise - so leaving this screen stops the work
 * rather than leaving it running behind a closed sheet.
 *
 * Sampled on a timer instead of every frame. The bars are 14 short rectangles
 * whose heights change slowly by design (the analyser already smooths the fall),
 * so redrawing them 60 times a second would spend battery to show the same
 * picture; about 16 times a second is indistinguishable and far cheaper.
 */
@Composable
fun SpectrumMeter(playing: Boolean, modifier: Modifier = Modifier) {
    val analyser = Spectrum.analyser
    var levels by remember { mutableStateOf(FloatArray(analyser.bands())) }

    DisposableEffect(analyser) {
        analyser.enabled = true
        onDispose { analyser.enabled = false }
    }

    LaunchedEffect(analyser) {
        val buffer = FloatArray(analyser.bands())
        while (true) {
            levels = analyser.snapshot(buffer).copyOf()
            delay(60)
        }
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "OUTPUT",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (playing) "live" else "idle",
                style = MaterialTheme.typography.labelSmall,
                color = if (playing) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            for (level in levels) {
                Box(
                    Modifier
                        .weight(1f)
                        // A 4dp floor so an idle meter reads as a row of bars
                        // at rest rather than as a broken empty box.
                        .height((4f + level * 66f).dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}
