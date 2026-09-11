package com.exo.musicplayer.ui.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Update
import com.exo.musicplayer.data.download.DownloadQuality
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun DownloadScreen(
    state: DownloadUiState,
    url: String,
    onUrlChange: (String) -> Unit,
    onDownload: () -> Unit,
    quality: DownloadQuality,
    onQualityChange: (DownloadQuality) -> Unit,
    onCancel: () -> Unit,
    onUpdate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 20.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Download",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onUpdate, enabled = !state.busy) {
                Icon(Icons.Default.Update, contentDescription = "Update yt-dlp")
            }
        }

        Text(
            text = "Paste a YouTube, SoundCloud or Bandcamp link, or just type an artist " +
                "and song name and Resonate finds the song on YouTube first. Either way " +
                "the audio arrives as an mp3, tagged and added to your library. Spotify " +
                "links work too, by finding the same track.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                placeholder = { Text("Link, or artist and song name") },
                singleLine = true,
                enabled = !state.busy,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { clipboard.getText()?.text?.let(onUrlChange) },
                enabled = !state.busy
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
            }
        }

        // A Column, not a Row. These used to be siblings in a Row whose first
        // child was itself fillMaxWidth, which took the entire width and pushed
        // the Download button off the right-hand edge - present in the tree,
        // laid out past the screen, invisible.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // Quality is a short ladder, not a slider: sites serve a few
            // fixed renditions and anything finer would be a fiction.
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Quality", style = MaterialTheme.typography.bodySmall)
                DownloadQuality.entries.forEach { option ->
                    FilterChip(
                        selected = option == quality,
                        onClick = { onQualityChange(option) },
                        label = { Text(option.label) }
                    )
                }
            }
            Text(
                quality.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onDownload,
                    enabled = !state.busy && url.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Download")
                }
                if (state.busy) {
                    OutlinedButton(onClick = onCancel) { Text("Stop") }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        if (state.busy) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    if (state.queueSize > 1) {
                        Text(
                            "Song ${state.queuePosition} of ${state.queueSize}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = state.stage,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (state.percent > 0f) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { (state.percent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = buildString {
                                append("${state.percent.roundToInt()}%")
                                if (state.etaSeconds > 0) append("  ·  ${state.etaSeconds}s left")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.title?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    state.note?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        state.message?.let { message ->
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (state.isError) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.isError) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        }
                    )
                    if (state.isError && state.offerUpdate) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onUpdate) {
                            Icon(Icons.Default.Update, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Update yt-dlp and retry")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "A song name is found on YouTube before anything downloads: the " +
                "original upload is picked over sped-up, slowed, cover and live " +
                "versions, and if none of the results is the song, nothing is saved. " +
                "Spotify links are matched the same way, since Spotify's own audio is " +
                "DRM protected.\n\n" +
                "yt-dlp is bundled and runs entirely on your phone — nothing is " +
                "sent to a server. YouTube changes often break it; the update button " +
                "above fetches a newer yt-dlp without reinstalling the app.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(28.dp))
    }
}
