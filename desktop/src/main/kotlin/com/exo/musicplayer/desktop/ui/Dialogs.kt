package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.desktop.data.BulkKind
import com.exo.musicplayer.desktop.data.DesktopController
import com.exo.musicplayer.desktop.data.DesktopDuplicateGroup
import com.exo.musicplayer.desktop.library.DesktopTrack
import com.exo.musicplayer.util.asDuration

/** Which modal is open, if any. */
enum class DialogKind { BULK, DUPLICATES, ADD_TO_PLAYLIST, MERGE }

/**
 * Modal surface.
 *
 * An in-window scrim rather than a second OS window: a real dialog window on
 * Windows gets its own taskbar entry and title bar, which is wrong for something
 * this transient.
 */
@Composable
fun ScrimDialog(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC07050C))
            // Swallows clicks so they don't reach the library underneath;
            // clicking the backdrop closes, as it does everywhere else.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .heightIn(max = 620.dp)
                .padding(32.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.Raised)
                .border(1.dp, Palette.Line, RoundedCornerShape(12.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Text)
                    subtitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.TextDim
                        )
                    }
                }
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, "Close", Modifier.size(15.dp), tint = Palette.TextDim)
                }
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

/**
 * The four bulk tools.
 *
 * Each records which tracks it has already been through, so a second run only
 * touches what is new — unless the redo box is ticked. Without that, running
 * "Lyrics" over a 900-track library a second time would spend twenty minutes
 * re-asking four services about songs it already knows have none.
 */
@Composable
fun BulkToolsDialog(controller: DesktopController, onDismiss: () -> Unit) {
    var redo by remember { mutableStateOf(false) }
    val job = controller.bulk

    ScrimDialog(
        title = "Bulk tools",
        subtitle = "${controller.tracks.size} tracks in the library",
        onDismiss = onDismiss
    ) {
        CheckRow(
            label = "Redo tracks that have already been through this",
            checked = redo,
            enabled = !job.running,
            note = "Off means each tool only visits tracks it hasn't seen before"
        ) { redo = it }

        Spacer(Modifier.height(16.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BulkOption(
                kind = BulkKind.COVERS,
                icon = Icons.Default.Album,
                description = "Finds missing cover art, five songs at a time, asking six " +
                    "sources at once, YouTube first. Tracks that already have art are left alone.",
                enabled = !job.running
            ) { controller.runBulk(BulkKind.COVERS, redo) }

            BulkOption(
                kind = BulkKind.TAGS,
                icon = Icons.AutoMirrored.Filled.Label,
                description = "Looks songs up by name, five at a time, and fills in artist, " +
                    "album and year, each from the best source that has it.",
                enabled = !job.running
            ) { controller.runBulk(BulkKind.TAGS, redo) }

            BulkOption(
                kind = BulkKind.LYRICS,
                icon = Icons.Default.Lyrics,
                description = "Four services in turn — LRCLIB, NetEase, lyrics.ovh, " +
                    "Genius — keeping timed lyrics where they exist.",
                enabled = !job.running
            ) { controller.runBulk(BulkKind.LYRICS, redo) }

            BulkOption(
                kind = BulkKind.IDENTIFY,
                icon = Icons.Default.Fingerprint,
                description = "Fingerprints the audio itself. Slower, but it works on " +
                    "files with no usable name at all.",
                enabled = !job.running
            ) { controller.runBulk(BulkKind.IDENTIFY, redo) }
        }

        if (job.running || job.finishedNote != null) {
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.Content)
                    .padding(14.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (job.running) {
                                "${job.label} — ${job.done} of ${job.total}"
                            } else {
                                "${job.label} finished"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Palette.Text,
                            modifier = Modifier.weight(1f)
                        )
                        if (job.running) GhostButton("Stop") { controller.cancelBulk() }
                    }
                    Spacer(Modifier.height(9.dp))
                    if (job.running) {
                        ThinProgress(job.progress)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            job.current,
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.TextFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    job.finishedNote?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Accent
                        )
                    }
                    if (job.running && job.skipped > 0) {
                        Text(
                            "${job.skipped} skipped as already done",
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.TextFaint
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BulkOption(
    kind: BulkKind,
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onRun: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.Content)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(17.dp), tint = Palette.Accent)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                kind.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Palette.Text
            )
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextFaint
            )
        }
        Spacer(Modifier.width(12.dp))
        GhostButton("Run", enabled = enabled, onClick = onRun)
    }
}

/**
 * Duplicate review.
 *
 * Nothing is removed without being shown first, and what is removed goes to the
 * Recycle Bin. These are the user's own files in their own folders — a wrong
 * guess has to be undoable.
 */
@Composable
fun DuplicatesDialog(controller: DesktopController, onDismiss: () -> Unit) {
    var excluded by remember { mutableStateOf(setOf<String>()) }
    val groups = controller.duplicates
    val selected = groups.filter { it.keep.file.absolutePath !in excluded }

    ScrimDialog(
        title = "Duplicates",
        subtitle = when {
            controller.duplicatesScanning -> "Comparing titles, artists and lengths…"
            groups.isEmpty() -> controller.duplicatesNote ?: "Nothing to review."
            else -> "${groups.size} sets · ${groups.sumOf { it.remove.size }} files to remove"
        },
        onDismiss = onDismiss
    ) {
        when {
            controller.duplicatesScanning -> {
                ThinProgress(null)
            }

            groups.isEmpty() -> {
                Hint(
                    controller.duplicatesNote
                        ?: "Nothing looks duplicated. Byte-identical copies never " +
                        "reach this list — only different files of the same recording do."
                )
                Spacer(Modifier.height(16.dp))
                AccentButton("Scan again") { controller.findDuplicates() }
            }

            else -> {
                Hint(
                    "The largest file is kept, on the assumption it is the better rip. " +
                        "Untick a set to leave it alone."
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 340.dp)) {
                    items(groups, key = { it.keep.file.absolutePath }) { group ->
                        DuplicateRow(
                            group = group,
                            included = group.keep.file.absolutePath !in excluded,
                            onToggle = {
                                val path = group.keep.file.absolutePath
                                excluded = if (path in excluded) excluded - path else excluded + path
                            }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${selected.sumOf { it.remove.size }} files will go to the Recycle Bin",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextDim,
                        modifier = Modifier.weight(1f)
                    )
                    GhostButton("Cancel", onClick = onDismiss)
                    Spacer(Modifier.width(8.dp))
                    AccentButton("Remove duplicates", enabled = selected.isNotEmpty()) {
                        controller.removeDuplicates(selected)
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicateRow(
    group: DesktopDuplicateGroup,
    included: Boolean,
    onToggle: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.Content)
            .padding(13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                CheckRow(
                    label = group.keep.title,
                    checked = included,
                    note = "${group.keep.displayArtist} · ${group.keep.durationMs.asDuration()}"
                ) { onToggle() }
            }
            Text(
                "${group.remove.size + 1} copies",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextFaint
            )
        }
        Spacer(Modifier.height(9.dp))
        FileLine("keep", group.keep, Palette.Accent)
        group.remove.forEach { FileLine("remove", it, Palette.TextFaint) }
    }
}

@Composable
private fun FileLine(label: String, track: DesktopTrack, colour: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = colour,
            modifier = Modifier.width(52.dp)
        )
        Text(
            track.file.name,
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            "%.1f MB".format(track.sizeBytes / 1_048_576.0),
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextFaint
        )
    }
}

/** Pick a playlist for the tracks the user right-clicked. */
@Composable
fun AddToPlaylistDialog(
    controller: DesktopController,
    tracks: List<DesktopTrack>,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }

    ScrimDialog(
        title = "Add to playlist",
        subtitle = if (tracks.size == 1) {
            tracks.first().title
        } else {
            "${tracks.size} tracks"
        },
        onDismiss = onDismiss
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextInput(
                value = newName,
                onValueChange = { newName = it },
                placeholder = "New playlist name",
                modifier = Modifier.weight(1f),
                onSubmit = {
                    controller.createPlaylist(newName, tracks)
                    onDismiss()
                }
            )
            Spacer(Modifier.width(10.dp))
            AccentButton("Create", enabled = newName.isNotBlank()) {
                controller.createPlaylist(newName, tracks)
                onDismiss()
            }
        }

        Spacer(Modifier.height(16.dp))

        if (controller.playlists.isEmpty()) {
            Hint("No playlists yet — name one above.")
        } else {
            LazyColumn(Modifier.heightIn(max = 300.dp)) {
                items(controller.playlists, key = { it.id }) { playlist ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Palette.Content)
                            .clickable {
                                controller.addToPlaylist(playlist, tracks)
                                onDismiss()
                            }
                            .padding(horizontal = 13.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.ContentCopy, null,
                            Modifier.size(14.dp), tint = Palette.TextFaint
                        )
                        Spacer(Modifier.width(11.dp))
                        Text(
                            playlist.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Palette.Text,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${playlist.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.TextFaint
                        )
                    }
                }
            }
        }
    }
}

/**
 * Hand-edit a track's details.
 *
 * Identification gets it right most of the time and not all of it — live
 * bootlegs, uncatalogued tracks, names the services transliterate differently.
 * Emptying a box clears that tag rather than leaving the old value, because
 * deleting a wrong album name has to be possible.
 */
@Composable
fun EditTrackDialog(
    controller: DesktopController,
    track: DesktopTrack,
    onDismiss: () -> Unit
) {
    var title by remember(track.file.absolutePath) { mutableStateOf(track.title) }
    var artist by remember(track.file.absolutePath) { mutableStateOf(track.artist.orEmpty()) }
    var album by remember(track.file.absolutePath) { mutableStateOf(track.album.orEmpty()) }
    var year by remember(track.file.absolutePath) {
        mutableStateOf(track.year?.toString().orEmpty())
    }

    ScrimDialog(title = "Edit details", subtitle = track.file.name, onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(track, 76.dp, corner = 8.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                FieldLabel("Song name")
                TextInput(title, { title = it }, "Required", Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                FieldLabel("Artist")
                TextInput(artist, { artist = it }, "Unknown artist", Modifier.fillMaxWidth())
            }
        }

        Spacer(Modifier.height(12.dp))
        Row {
            Column(Modifier.weight(2f)) {
                FieldLabel("Album")
                TextInput(album, { album = it }, "None", Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.width(96.dp)) {
                FieldLabel("Year")
                TextInput(year, { year = it.filter(Char::isDigit).take(4) }, "----",
                    Modifier.fillMaxWidth())
            }
        }

        Spacer(Modifier.height(14.dp))
        Hint(
            if (controller.writeTags) {
                "Saved into the file's own tags, so other players see it too."
            } else {
                "\"Write tags into files\" is off in Settings, so this will not be saved " +
                    "to the file. Turn it on first."
            }
        )
        controller.editNote?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Accent)
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            GhostButton("Cancel", onClick = onDismiss)
            Spacer(Modifier.width(8.dp))
            AccentButton("Save", enabled = controller.writeTags) {
                controller.saveTrackDetails(track, title, artist, album, year)
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextFaint,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}
