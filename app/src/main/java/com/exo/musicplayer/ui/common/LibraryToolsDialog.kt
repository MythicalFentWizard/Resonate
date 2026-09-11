package com.exo.musicplayer.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.ui.MainViewModel

/**
 * Runs the three library-wide jobs.
 *
 * Each shows how many tracks it would actually touch, because "identify
 * everything" on a large library is a long, network-heavy operation and the
 * count is the honest way to say so before you start it.
 */
@Composable
fun LibraryToolsDialog(
    counts: MainViewModel.BulkCounts,
    onCovers: (Boolean) -> Unit,
    onIdentify: (Boolean) -> Unit,
    onLyrics: (Boolean) -> Unit,
    onDuplicates: () -> Unit,
    onZip: () -> Unit,
    onDismiss: () -> Unit
) {
    var redo by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Library tools") },
        text = {
            Column {
                Text(
                    "Tracks already processed are skipped, including ones nothing " +
                        "was found for — so a song no service knows isn't retried " +
                        "every time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { redo = !redo },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = redo, onCheckedChange = { redo = it })
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text("Redo already-processed tracks", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Runs over the whole library again",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                ToolRow(
                    icon = Icons.Default.Image,
                    title = "Update covers",
                    pending = counts.covers,
                    total = counts.total,
                    redo = redo,
                    note = "Searches by tags. Fast.",
                    onClick = { onCovers(redo) }
                )
                ToolRow(
                    icon = Icons.Default.AutoFixHigh,
                    title = "Identify & fix tags",
                    pending = counts.identify,
                    total = counts.total,
                    redo = redo,
                    note = "Fingerprints each file. Slow — minutes for a big library.",
                    onClick = { onIdentify(redo) }
                )
                ToolRow(
                    icon = Icons.Default.ContentCopy,
                    title = "Find duplicates",
                    pending = counts.total,
                    total = counts.total,
                    redo = redo,
                    note = "Different copies of the same song. Review before removing.",
                    onClick = onDuplicates
                )
                // Not gated on a pending count like the others: archiving is
                // something you do to the whole library, not to whatever part
                // of it has not been processed yet.
                ToolRow(
                    icon = Icons.Default.Archive,
                    title = "Zip and ship",
                    pending = counts.total,
                    total = counts.total,
                    redo = redo,
                    note = "Bundles every track into one .zip and tells you where it " +
                        "saved it, ready to send on.",
                    onClick = onZip
                )
                ToolRow(
                    icon = Icons.AutoMirrored.Filled.Article,
                    title = "Fetch lyrics",
                    pending = counts.lyrics,
                    total = counts.total,
                    redo = redo,
                    note = "LRCLIB, NetEase, lyrics.ovh, then Genius.",
                    onClick = { onLyrics(redo) }
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun ToolRow(
    icon: ImageVector,
    title: String,
    pending: Int,
    total: Int,
    redo: Boolean,
    note: String,
    onClick: () -> Unit
) {
    val count = if (redo) total else pending
    val enabled = count > 0

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                text = if (enabled) "$count to process · $note" else "Nothing left · $note",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
