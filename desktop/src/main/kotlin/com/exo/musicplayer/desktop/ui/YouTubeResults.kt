package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.data.youtube.YouTubeFormat
import com.exo.musicplayer.data.youtube.YouTubeVideo
import com.exo.musicplayer.data.youtube.PreviewState

/**
 * YouTube search results, laid out the way YouTube lays them out.
 *
 * Not the compact metadata row the catalogue results use. A YouTube hit is
 * chosen by looking at it: the thumbnail says whether this is the official
 * upload or a slowed edit, the view count says whether it is the one everybody
 * means, the duration says whether it is the song or a two-hour mix. Shrinking
 * that to a line of text would remove exactly the information the choice
 * depends on.
 */
@Composable
fun YouTubeResultList(
    videos: List<YouTubeVideo>,
    preview: PreviewState,
    onPreview: (YouTubeVideo) -> Unit,
    onDownload: (YouTubeVideo) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier.fillMaxSize()) {
        items(videos, key = { it.id }) { video ->
            YouTubeRow(
                video = video,
                preview = preview,
                onPreview = { onPreview(video) },
                onDownload = { onDownload(video) }
            )
            Spacer(Modifier.height(10.dp))
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun YouTubeRow(
    video: YouTubeVideo,
    preview: PreviewState,
    onPreview: () -> Unit,
    onDownload: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val isPreviewing = preview.videoId == video.id
    val isLoading = isPreviewing && preview.loading

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (hovered || isPreviewing) Palette.Hover else Palette.Raised)
            .border(
                1.dp,
                if (isPreviewing) Palette.Accent else Palette.Line,
                RoundedCornerShape(10.dp)
            )
            .hoverable(interaction)
            .padding(12.dp)
    ) {
        // The whole thumbnail is the preview control, as it is on YouTube
        // itself. The labelled button stays as well, for anyone who would
        // rather read than guess.
        Box(
            Modifier
                .width(190.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onPreview)
        ) {
            RemoteImage(
                video.thumbnail(YouTubeVideo.ThumbSize.WIDE),
                Modifier.fillMaxSize(),
                corner = 8.dp
            )

            if (isLoading) {
                Box(
                    Modifier.fillMaxSize().background(Color(0xAA000000)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        Modifier.size(26.dp),
                        color = Palette.Accent,
                        strokeWidth = 2.dp
                    )
                }
            } else if (hovered || isPreviewing) {
                Box(
                    Modifier.fillMaxSize().background(Color(0x55000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isPreviewing) Icons.Default.Stop else Icons.Default.PlayArrow,
                        null,
                        Modifier.size(34.dp),
                        tint = Color.White
                    )
                }
            }

            // Duration, or how far the preview has got. The same corner does
            // both, because while it plays that is the more useful number.
            video.durationSeconds?.let { seconds ->
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
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

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text(
                video.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = Palette.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                video.subtitle.ifBlank { "view count not reported" },
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextDim
            )
            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                video.channelAvatarUrl?.let { avatar ->
                    RemoteImage(avatar, Modifier.size(20.dp), corner = 10.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    video.channel,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (video.channelVerified) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.CheckCircle,
                        "Verified",
                        Modifier.size(12.dp),
                        tint = Palette.TextFaint
                    )
                }
            }

            video.description?.let { text ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextFaint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            preview.error?.takeIf { isPreviewing }?.let { message ->
                Spacer(Modifier.height(6.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.Accent
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(horizontalAlignment = Alignment.End) {
            AccentButton("Download mp3", icon = Icons.Default.Download) { onDownload() }
            Spacer(Modifier.height(7.dp))
            GhostButton(
                if (isPreviewing) "Stop" else "Preview",
                icon = if (isPreviewing) Icons.Default.Stop else Icons.Default.PlayArrow
            ) { onPreview() }
        }
    }
}
