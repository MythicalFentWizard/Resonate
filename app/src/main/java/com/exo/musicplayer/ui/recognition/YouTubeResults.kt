package com.exo.musicplayer.ui.recognition

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.exo.musicplayer.data.youtube.PreviewState
import com.exo.musicplayer.data.youtube.YouTubeFormat
import com.exo.musicplayer.data.youtube.YouTubeVideo

/**
 * YouTube search results, laid out the way YouTube lays them out.
 *
 * Not the compact card the catalogue results use. A YouTube hit is chosen by
 * looking at it: the thumbnail says whether this is the official upload or a
 * slowed edit, the view count says whether it is the one everybody means, the
 * duration says whether it is the song or a two-hour mix. Reducing that to a
 * line of text throws away the information the choice actually rests on.
 *
 * Emitted into the Identify screen's own list rather than being a list of its
 * own, so the results scroll together with the header instead of being
 * squeezed into whatever height the header leaves.
 *
 * Tapping the thumbnail previews the audio; the buttons underneath spell the
 * same thing out, because a tappable image with no label is a guess.
 */
fun LazyListScope.youTubeResults(
    videos: List<YouTubeVideo>,
    preview: PreviewState,
    onPreview: (YouTubeVideo) -> Unit,
    onDownload: (YouTubeVideo) -> Unit,
    /** Positions in [videos] picked for downloading together. */
    selected: Set<Int> = emptySet(),
    selecting: Boolean = false,
    onToggle: (Int) -> Unit = {}
) {
    // Keyed by position as well as id: a search can return the same video
    // twice, and a duplicate key crashes a lazy list.
    itemsIndexed(videos, key = { index, video -> "youtube-$index-${video.id}" }) { index, video ->
        YouTubeRow(
            video = video,
            preview = preview,
            selected = index in selected,
            selecting = selecting,
            onToggle = { onToggle(index) },
            onPreview = { onPreview(video) },
            onDownload = { onDownload(video) }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun YouTubeRow(
    video: YouTubeVideo,
    preview: PreviewState,
    selected: Boolean,
    selecting: Boolean,
    onToggle: () -> Unit,
    onPreview: () -> Unit,
    onDownload: () -> Unit
) {
    val isPreviewing = preview.videoId == video.id
    val isLoading = isPreviewing && preview.loading

    Surface(
        color = if (selected || isPreviewing) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            // Long-press starts picking several; once picking, a tap adds or removes.
            .combinedClickable(onClick = { if (selecting) onToggle() }, onLongClick = onToggle)
    ) {
        Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 2.dp)) {
            Row {
                Box(
                    Modifier
                        .width(140.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { if (selecting) onToggle() else onPreview() }
                ) {
                    AsyncImage(
                        // The small rendition on purpose: at this size the large
                        // one is four times the bytes for no visible gain, and a
                        // result list loads twenty of them at once.
                        model = video.thumbnail(YouTubeVideo.ThumbSize.SMALL),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )

                    if (selected) {
                        Box(
                            Modifier.fillMaxSize().background(Color(0x99000000)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CheckCircle, "Selected", Modifier.size(30.dp), tint = Color.White)
                        }
                    }

                    if (isLoading) {
                        Box(
                            Modifier.fillMaxSize().background(Color(0xAA000000)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        }
                    } else if (isPreviewing) {
                        Box(
                            Modifier.fillMaxSize().background(Color(0x55000000)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Stop, null, Modifier.size(28.dp), tint = Color.White)
                        }
                    }

                    // Duration, or how far the preview has got: while it plays
                    // that is the more useful number, and it sits in the corner
                    // people already look at for length.
                    video.durationSeconds?.let { seconds ->
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xCC000000))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                if (isPreviewing && preview.playing) {
                                    YouTubeFormat.duration(preview.secondsPlayed) + " / " +
                                        YouTubeFormat.duration(seconds)
                                } else {
                                    YouTubeFormat.duration(seconds)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                        }
                    }
                }

                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        video.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        video.subtitle.ifBlank { "view count not reported" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        video.channelAvatarUrl?.let { avatar ->
                            AsyncImage(
                                model = avatar,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(16.dp).clip(CircleShape)
                            )
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            video.channel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (video.channelVerified) {
                            Spacer(Modifier.width(3.dp))
                            Icon(
                                Icons.Default.CheckCircle,
                                "Verified",
                                Modifier.size(11.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            preview.error?.takeIf { isPreviewing }?.let { message ->
                Spacer(Modifier.height(4.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onPreview,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(
                        if (isPreviewing) Icons.Default.Stop else Icons.Default.PlayArrow,
                        null,
                        Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (isPreviewing) "Stop" else "Preview")
                }
                Spacer(Modifier.width(4.dp))
                FilledTonalButton(
                    onClick = onDownload,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.Download, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Download mp3")
                }
            }
        }
    }
}
