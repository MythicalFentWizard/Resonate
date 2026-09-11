package com.exo.musicplayer.ui.moods

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exo.musicplayer.data.db.Track
import com.exo.musicplayer.data.weather.Affinity
import com.exo.musicplayer.data.weather.WeatherSnapshot
import com.exo.musicplayer.ui.MoodState
import com.exo.musicplayer.ui.common.TrackRow
import kotlin.math.roundToInt

@Composable
fun MoodsScreen(
    state: MoodState,
    currentTrackId: Long?,
    isPlaying: Boolean,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onPlayMix: () -> Unit,
    onPlayFrom: (Int) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onFixTags: (Track) -> Unit,
    onEditDetails: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        when (state) {
            is MoodState.NeedsPermission -> Centered {
                Icon(
                    Icons.Default.LocationOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text("Match music to the weather", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Resonate needs your approximate location to look up local " +
                        "conditions. It reads your last known position only, never " +
                        "turns on GPS, and the coordinates go to the forecast service " +
                        "and nowhere else.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onRequestPermission) { Text("Allow location") }
            }

            is MoodState.Loading -> Centered {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    "Checking the weather…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            is MoodState.Unavailable -> Centered {
                Text("Couldn't get the weather", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "No recent location fix, or the forecast service is unreachable. " +
                        "Opening a maps app once usually gives Android a position to cache.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Try again")
                }
            }

            is MoodState.Learning -> {
                WeatherHeader(state.weather, onRefresh)
                Centered {
                    Text(
                        "Still learning your taste",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Resonate needs a bit of listening history before it can tell " +
                            "which songs you reach for in which weather. Keep playing — " +
                            "it works this out on its own.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(20.dp))
                    LinearProgressIndicator(
                        progress = {
                            (state.playsRecorded.toFloat() / state.playsNeeded).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${state.playsRecorded} of ${state.playsNeeded} plays recorded",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            is MoodState.Ready -> {
                WeatherHeader(state.weather, onRefresh)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = onPlayMix) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Play mix")
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${state.tracks.size} songs you play more when it's " +
                            state.weather.condition.label.lowercase(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(state.tracks, key = { _, t -> t.id }) { index, track ->
                        Column {
                            TrackRow(
                                track = track,
                                isCurrent = track.id == currentTrackId,
                                isPlaying = isPlaying,
                                onClick = { onPlayFrom(index) },
                                onPlayNext = { onPlayNext(track) },
                                onAddToQueue = { onAddToQueue(track) },
                                onAddToPlaylist = { onAddToPlaylist(track) },
                                onToggleFavorite = { onToggleFavorite(track) },
                                onFixTags = { onFixTags(track) },
                                onEditDetails = { onEditDetails(track) },
                                onDelete = { }
                            )
                            state.affinities[track.id]?.let { affinity ->
                                AffinityNote(affinity, state.weather)
                            }
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

/** Explains *why* a track is in the mix, so the ranking isn't a black box. */
@Composable
private fun AffinityNote(affinity: Affinity, weather: WeatherSnapshot) {
    val percent = ((affinity.lift - 1.0) * 100).roundToInt()
    Text(
        text = "${affinity.playsInCondition} of ${affinity.totalPlays} plays in " +
            "${weather.condition.label.lowercase()} · ${percent}% above your average",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 82.dp, bottom = 8.dp, end = 16.dp)
    )
}

@Composable
private fun WeatherHeader(weather: WeatherSnapshot, onRefresh: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(weather.condition.emoji, fontSize = 40.sp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = weather.condition.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (!weather.temperatureC.isNaN()) {
                    Text(
                        text = "${weather.temperatureC.roundToInt()}°C right now",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh weather",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) { content() }
    }
}
