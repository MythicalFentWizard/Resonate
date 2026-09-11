package com.exo.musicplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.exo.musicplayer.data.db.Lyrics
import com.exo.musicplayer.data.db.Track
import com.exo.musicplayer.data.lyrics.LyricLine
import com.exo.musicplayer.playback.AudioFxState
import com.exo.musicplayer.playback.AudioOutput
import com.exo.musicplayer.playback.FxPreset
import com.exo.musicplayer.playback.PlaybackState
import com.exo.musicplayer.playback.ReverbRoom
import com.exo.musicplayer.ui.common.Artwork
import com.exo.musicplayer.ui.common.ArtworkLarge
import com.exo.musicplayer.util.asDuration

@Composable
fun PlayerScreen(
    track: Track?,
    state: PlaybackState,
    queue: List<Track>,
    lyrics: Lyrics?,
    lyricLines: List<LyricLine>,
    lyricsBusy: Boolean,
    lyricsMessage: String?,
    onCollapse: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    onQueueItemClick: (Int) -> Unit,
    onFetchLyrics: () -> Unit,
    onSaveLyrics: (String) -> Unit,
    onDeleteLyrics: () -> Unit,
    fx: AudioFxState,
    onPreset: (FxPreset) -> Unit,
    onSpeed: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onReverbEnabled: (Boolean) -> Unit,
    onReverbRoom: (ReverbRoom) -> Unit,
    onReverbAmount: (Float) -> Unit,
    onResetFx: () -> Unit,
    outputs: List<AudioOutput>,
    selectedOutputs: Set<String>,
    mirrorOutputs: Boolean,
    onPickOutput: (String) -> Unit,
    onMirrorOutputs: (Boolean) -> Unit
) {
    var showLyrics by remember(track?.id) { mutableStateOf(false) }
    // Lyrics and effects share the same slot, so opening one closes the other.
    var showEffects by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(
            Modifier
                .fillMaxSize()
                // This screen sits above the Scaffold, so it gets no content
                // padding from it: without this the header slides under the
                // status bar and the controls under the gesture bar.
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 24.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCollapse) {
                    Icon(Icons.Default.Close, contentDescription = "Close player")
                }
                Text(
                    text = when {
                        showEffects -> "Effects"
                        showLyrics -> "Lyrics"
                        else -> "Now playing"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showLyrics = !showLyrics; showEffects = false }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Article,
                        contentDescription = "Lyrics",
                        tint = if (showLyrics) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                IconButton(onClick = { showEffects = !showEffects; showLyrics = false }) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "Effects",
                        tint = if (showEffects || !fx.isDefault) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (track?.isFavorite == true) {
                            Icons.Default.Favorite
                        } else {
                            Icons.Default.FavoriteBorder
                        },
                        contentDescription = "Favourite",
                        tint = if (track?.isFavorite == true) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            // Scrollable area. Exactly one vertical scroller lives here at a
            // time; nesting the lyrics list inside the queue list would crash.
            Box(Modifier.weight(1f)) {
                if (showEffects) {
                    EffectsSheet(
                        state = fx,
                        onPreset = onPreset,
                        onSpeed = onSpeed,
                        onPitch = onPitch,
                        onReverbEnabled = onReverbEnabled,
                        onReverbRoom = onReverbRoom,
                        onReverbAmount = onReverbAmount,
                        onReset = onResetFx,
                        outputs = outputs,
                        selectedOutputs = selectedOutputs,
                        mirrorOutputs = mirrorOutputs,
                        onPickOutput = onPickOutput,
                        onMirrorOutputs = onMirrorOutputs,
                        playing = state.isPlaying
                    )
                } else if (showLyrics) {
                    LyricsPanel(
                        lyrics = lyrics,
                        lines = lyricLines,
                        positionMs = state.positionMs,
                        busy = lyricsBusy,
                        message = lyricsMessage,
                        onFetch = onFetchLyrics,
                        onSave = onSaveLyrics,
                        onDelete = onDeleteLyrics
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Spacer(Modifier.height(12.dp))
                            ArtworkLarge(track = track, modifier = Modifier.aspectRatio(1f))
                            Spacer(Modifier.height(20.dp))
                            if (queue.size > 1) {
                                Text(
                                    text = "Up next",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                        itemsIndexed(queue, key = { i, t -> "$i-${t.id}" }) { index, item ->
                            QueueRow(
                                track = item,
                                isCurrent = index == state.queueIndex,
                                onClick = { onQueueItemClick(index) }
                            )
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = track?.title ?: "Nothing playing",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(track?.artist, track?.album)
                    .ifEmpty { listOf("Unknown artist") }
                    .joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(12.dp))
            Scrubber(state = state, onSeek = onSeek)
            Spacer(Modifier.height(4.dp))
            Controls(
                state = state,
                onTogglePlayPause = onTogglePlayPause,
                onNext = onNext,
                onPrevious = onPrevious,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Scrubber(state: PlaybackState, onSeek: (Float) -> Unit) {
    // While the thumb is held, show the finger position rather than the player's.
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    val shown = if (scrubbing) scrubValue else state.progress

    Column {
        Slider(
            value = shown,
            onValueChange = {
                scrubbing = true
                scrubValue = it
            },
            onValueChangeFinished = {
                onSeek(scrubValue)
                scrubbing = false
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = (shown * state.durationMs).toLong().asDuration(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = state.durationMs.asDuration(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Controls(
    state: PlaybackState,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onToggleShuffle) {
            Icon(
                imageVector = Icons.Default.Shuffle,
                contentDescription = "Shuffle",
                tint = if (state.shuffleEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        IconButton(onClick = onPrevious) {
            Icon(
                Icons.Default.SkipPrevious,
                contentDescription = "Previous",
                modifier = Modifier.size(36.dp)
            )
        }

        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onTogglePlayPause),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(34.dp)
            )
        }

        IconButton(onClick = onNext, enabled = state.hasNext) {
            Icon(
                Icons.Default.SkipNext,
                contentDescription = "Next",
                modifier = Modifier.size(36.dp)
            )
        }

        IconButton(onClick = onCycleRepeat) {
            Icon(
                imageVector = if (state.repeatMode == Player.REPEAT_MODE_ONE) {
                    Icons.Default.RepeatOne
                } else {
                    Icons.Default.Repeat
                },
                contentDescription = "Repeat",
                tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
        }
    }
}

@Composable
private fun QueueRow(track: Track, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (isCurrent) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                } else {
                    Color.Transparent
                }
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Artwork(track = track, size = 40.dp, cornerRadius = 6.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist ?: "Unknown artist",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = track.durationMs.asDuration(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
