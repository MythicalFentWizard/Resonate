package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.data.playlist.ImportResult
import com.exo.musicplayer.desktop.data.DesktopController
import com.exo.musicplayer.desktop.data.StoredPlaylist
import com.exo.musicplayer.desktop.library.DesktopTrack
import com.exo.musicplayer.util.asDuration
import java.io.File

/** Playlists: a grid of covers, and one open playlist at a time. */
@Composable
fun PlaylistsScreen(
    controller: DesktopController,
    onPickPlaylistFile: (save: Boolean, suggested: String) -> File?
) {
    LaunchedEffect(Unit) { controller.refreshPlaylists() }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    val open = controller.openPlaylist
    if (open != null) {
        OpenPlaylist(controller, open, onPickPlaylistFile)
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Your playlists") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GhostButton("Import…", icon = Icons.Default.FileOpen) {
                        onPickPlaylistFile(false, "")?.let { controller.importPlaylist(it) }
                    }
                    Spacer(Modifier.width(8.dp))
                    AccentButton("New playlist", icon = Icons.Default.Add) { creating = true }
                }
            }
        }

        if (creating) {
            Spacer(Modifier.height(12.dp))
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextInput(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = "Playlist name",
                        modifier = Modifier.weight(1f),
                        onSubmit = {
                            controller.createPlaylist(newName)
                            newName = ""
                            creating = false
                        }
                    )
                    Spacer(Modifier.width(10.dp))
                    AccentButton("Create", enabled = newName.isNotBlank()) {
                        controller.createPlaylist(newName)
                        newName = ""
                        creating = false
                    }
                    Spacer(Modifier.width(8.dp))
                    GhostButton("Cancel") { creating = false; newName = "" }
                }
            }
        }

        controller.playlistNote?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Accent)
        }

        controller.importResult?.let { result ->
            Spacer(Modifier.height(12.dp))
            ImportReview(
                result = result,
                onConfirm = { controller.confirmImport(result) },
                onCancel = { controller.dismissImport() }
            )
        }

        Spacer(Modifier.height(14.dp))

        if (controller.playlists.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                title = "No playlists yet",
                body = "Make one here, or right-click any track and add it to a new playlist."
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(170.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(controller.playlists, key = { it.id }) { playlist ->
                    PlaylistCard(
                        playlist = playlist,
                        onOpen = { controller.show(playlist) },
                        onDelete = { controller.deletePlaylist(playlist) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistCard(
    playlist: StoredPlaylist,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (hovered) Palette.Hover else Palette.Raised)
            .border(1.dp, Palette.Line, RoundedCornerShape(10.dp))
            .hoverable(interaction)
            .clickable(onClick = onOpen)
            .padding(14.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(112.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Palette.Content),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(30.dp), tint = Palette.TextFaint)
        }
        Spacer(Modifier.height(11.dp))
        Text(
            playlist.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = Palette.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${playlist.size} track${if (playlist.size == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextFaint,
                modifier = Modifier.weight(1f)
            )
            if (hovered) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Delete, "Delete playlist",
                        Modifier.size(13.dp), tint = Palette.TextDim
                    )
                }
            }
        }
    }
}

/** What an imported file matched, before anything is created. */
@Composable
private fun ImportReview(
    result: ImportResult<DesktopTrack>,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Panel(Modifier.fillMaxWidth()) {
        SectionTitle("\"${result.name}\" — ${result.matched.size} of ${result.total} found") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GhostButton("Cancel", onClick = onCancel)
                Spacer(Modifier.width(8.dp))
                AccentButton(
                    "Add ${result.matched.size} tracks",
                    enabled = result.matched.isNotEmpty(),
                    onClick = onConfirm
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Hint(
            "Nothing is downloaded — this links up songs you already have. " +
                "Anything missing is listed so you can go and find it."
        )

        if (result.missing.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Not in your library",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextDim
            )
            Spacer(Modifier.height(4.dp))
            result.missing.take(8).forEach {
                Text(
                    it.display,
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (result.missing.size > 8) {
                Text(
                    "and ${result.missing.size - 8} more",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextFaint
                )
            }
        }
    }
}

@Composable
private fun OpenPlaylist(
    controller: DesktopController,
    playlist: StoredPlaylist,
    onPickPlaylistFile: (save: Boolean, suggested: String) -> File?
) {
    LaunchedEffect(playlist.id, controller.tracks) { controller.show(playlist) }
    var renaming by remember(playlist.id) { mutableStateOf(false) }
    var name by remember(playlist.id) { mutableStateOf(playlist.name) }
    val items = controller.openPlaylistTracks

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { controller.show(null) },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(16.dp), tint = Palette.TextDim)
            }
            Spacer(Modifier.width(10.dp))
            if (renaming) {
                TextInput(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "Playlist name",
                    modifier = Modifier.width(280.dp),
                    onSubmit = {
                        controller.renamePlaylist(playlist, name)
                        renaming = false
                    }
                )
                Spacer(Modifier.width(8.dp))
                AccentButton("Save") {
                    controller.renamePlaylist(playlist, name)
                    renaming = false
                }
            } else {
                Column(Modifier.weight(1f)) {
                    Text(
                        playlist.name,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Palette.Text
                    )
                    Hint(
                        "${items.size} track${if (items.size == 1) "" else "s"} · " +
                            items.sumOf { it.durationMs }.asDuration()
                    )
                }
                AccentButton("Play", icon = Icons.Default.PlayArrow) {
                    items.firstOrNull()?.let { controller.play(it, items) }
                }
                Spacer(Modifier.width(8.dp))
                GhostButton("Rename") { renaming = true }
                Spacer(Modifier.width(8.dp))
                GhostButton(
                    "Zip and ship",
                    enabled = !controller.archiveRunning,
                    icon = Icons.Default.Archive
                ) { controller.zipPlaylist(playlist) }
                Spacer(Modifier.width(8.dp))
                GhostButton("Export…", icon = Icons.Default.Save) {
                    onPickPlaylistFile(true, playlist.name)?.let {
                        controller.exportPlaylist(playlist, it)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        if (items.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                title = "Empty playlist",
                body = "Right-click tracks in the library and add them here.\n" +
                    "Tracks whose files have moved won't show up until the next rescan."
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(items, key = { _, t -> t.file.absolutePath }) { index, track ->
                    PlaylistTrackRow(
                        position = index + 1,
                        track = track,
                        isCurrent = track.file == controller.engine.status.value.track?.file,
                        onPlay = { controller.play(track, items) },
                        onRemove = { controller.removeFromPlaylist(playlist, track) }
                    )
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun PlaylistTrackRow(
    position: Int,
    track: DesktopTrack,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(
                when {
                    isCurrent -> Palette.Selected
                    hovered -> Palette.Hover
                    else -> Color.Transparent
                }
            )
            .hoverable(interaction)
            .clickable(onClick = onPlay)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(28.dp)) {
            if (hovered) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(15.dp), tint = Palette.Text)
            } else {
                Text(
                    position.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isCurrent) Palette.Accent else Palette.TextFaint
                )
            }
        }
        Artwork(track, 34.dp)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isCurrent) Palette.Accent else Palette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                track.displayArtist,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            track.durationMs.asDuration(),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextFaint
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(5.dp))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            if (hovered) {
                Icon(Icons.Default.Delete, "Remove", Modifier.size(13.dp), tint = Palette.TextDim)
            }
        }
    }
}
