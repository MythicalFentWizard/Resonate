package com.exo.musicplayer.ui.library

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.data.db.Track
import com.exo.musicplayer.data.repo.SortMode
import com.exo.musicplayer.ui.common.TrackRow

@Composable
fun LibraryScreen(
    tracks: List<Track>,
    searchResults: List<Track>,
    query: String,
    sort: SortMode,
    currentTrackId: Long?,
    isPlaying: Boolean,
    onQueryChange: (String) -> Unit,
    onAddFiles: () -> Unit,
    onAddFolder: () -> Unit,
    onOpenSettings: () -> Unit,
    onUpdateCovers: () -> Unit,
    onOpenDownload: () -> Unit,
    onSortChange: (SortMode) -> Unit,
    onPlayFrom: (List<Track>, Int) -> Unit,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onFixTags: (Track) -> Unit,
    onEditDetails: (Track) -> Unit,
    onDelete: (Track) -> Unit,
    selectedIds: Set<Long>,
    onToggleSelect: (Track) -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onShareSelected: () -> Unit,
    onPlaylistSelected: () -> Unit,
    onFavoriteSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchOpen by remember { mutableStateOf(false) }
    val searching = query.isNotBlank()
    val shown = if (searching) searchResults else tracks

    if (tracks.isEmpty() && !searching) {
        EmptyLibrary(onAddFiles, onAddFolder, modifier)
        return
    }

    val selecting = selectedIds.isNotEmpty()

    // Leaving selection with the back gesture, which is what the gesture is
    // for. Without this, back would leave the screen and abandon a selection
    // the user can no longer see.
    BackHandler(enabled = selecting, onBack = onClearSelection)

    Column(modifier.fillMaxSize()) {
        if (selecting) {
            SelectionBar(
                count = selectedIds.size,
                total = shown.size,
                onClear = onClearSelection,
                onSelectAll = onSelectAll,
                onShare = onShareSelected,
                onPlaylist = onPlaylistSelected,
                onFavorite = onFavoriteSelected,
                onDelete = onDeleteSelected
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Library",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            AddMenu(
                onAddFiles = onAddFiles,
                onAddFolder = onAddFolder,
                onUpdateCovers = onUpdateCovers,
                onOpenDownload = onOpenDownload
            )
            IconButton(onClick = {
                searchOpen = !searchOpen
                if (!searchOpen) onQueryChange("")
            }) {
                Icon(
                    imageVector = if (searchOpen) Icons.Default.Clear else Icons.Default.Search,
                    contentDescription = if (searchOpen) "Close search" else "Search"
                )
            }
            SortMenu(sort = sort, onSortChange = onSortChange)
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }

        AnimatedVisibility(visible = searchOpen) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Songs, artists, albums") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        if (!searching) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onPlayAll) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play all")
                }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = onShuffleAll) {
                    Icon(Icons.Default.Shuffle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Shuffle")
                }
            }
        }

        Text(
            text = when {
                searching && shown.isEmpty() -> "No matches for \"$query\""
                searching -> "${shown.size} ${if (shown.size == 1) "match" else "matches"}"
                else -> "${tracks.size} ${if (tracks.size == 1) "song" else "songs"}"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp)
        )

        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(shown, key = { _, track -> track.id }) { index, track ->
                TrackRow(
                    track = track,
                    isCurrent = track.id == currentTrackId,
                    isPlaying = isPlaying,
                    // Once a selection exists, tapping extends it rather than
                    // starting playback - otherwise picking a second song
                    // would throw the first away.
                    onClick = {
                        if (selecting) onToggleSelect(track) else onPlayFrom(shown, index)
                    },
                    onPlayNext = { onPlayNext(track) },
                    onAddToQueue = { onAddToQueue(track) },
                    onAddToPlaylist = { onAddToPlaylist(track) },
                    onToggleFavorite = { onToggleFavorite(track) },
                    onFixTags = { onFixTags(track) },
                                onEditDetails = { onEditDetails(track) },
                    onDelete = { onDelete(track) },
                    isSelected = track.id in selectedIds,
                    onLongPress = { onToggleSelect(track) }
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun AddMenu(
    onAddFiles: () -> Unit,
    onAddFolder: () -> Unit,
    onUpdateCovers: () -> Unit,
    onOpenDownload: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Column {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.Add, contentDescription = "Add songs")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Add files…") },
                leadingIcon = { Icon(Icons.Default.LibraryMusic, null) },
                onClick = { open = false; onAddFiles() }
            )
            DropdownMenuItem(
                text = { Text("Import a folder…") },
                leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                onClick = { open = false; onAddFolder() }
            )
            DropdownMenuItem(
                text = { Text("Download from link…") },
                leadingIcon = { Icon(Icons.Default.Download, null) },
                onClick = { open = false; onOpenDownload() }
            )
            DropdownMenuItem(
                text = { Text("Library tools…") },
                leadingIcon = { Icon(Icons.Default.AutoFixHigh, null) },
                onClick = { open = false; onUpdateCovers() }
            )
        }
    }
}

@Composable
private fun SortMenu(sort: SortMode, onSortChange: (SortMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.Sort, contentDescription = "Sort")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = mode.label,
                            color = if (mode == sort) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    },
                    onClick = { onSortChange(mode); open = false }
                )
            }
        }
    }
}

@Composable
private fun EmptyLibrary(
    onAddFiles: () -> Unit,
    onAddFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.LibraryMusic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "No songs yet",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Open a song in Telegram, tap Share, and pick Resonate. " +
                "It gets copied here and stays in your library.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onAddFiles) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add files")
            }
            OutlinedButton(onClick = onAddFolder) {
                Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Import folder")
            }
        }
    }
}

/**
 * Actions for a multi-track selection.
 *
 * Sits above the normal header rather than replacing it, so the library title
 * and count stay visible and it is obvious the selection is a mode you are in
 * rather than a different screen.
 */
@Composable
private fun SelectionBar(
    count: Int,
    total: Int,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onPlaylist: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, contentDescription = "Cancel selection")
            }
            Text(
                text = "$count selected",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (count < total) {
                TextButton(onClick = onSelectAll) { Text("All") }
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = "Share selected")
            }
            IconButton(onClick = onPlaylist) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = "Add selected to a playlist"
                )
            }
            IconButton(onClick = onFavorite) {
                Icon(Icons.Default.FavoriteBorder, contentDescription = "Favourite selected")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete selected")
            }
        }
    }
}
