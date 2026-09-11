package com.exo.musicplayer.desktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.onClick
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.desktop.data.CollectionKind
import com.exo.musicplayer.desktop.data.DesktopController
import com.exo.musicplayer.desktop.data.DownloadEntry
import com.exo.musicplayer.desktop.data.SortMode
import com.exo.musicplayer.desktop.library.DesktopTrack
import com.exo.musicplayer.desktop.system.Explorer
import com.exo.musicplayer.util.asDuration
import kotlinx.coroutines.delay
import java.awt.Desktop
import java.io.File
import java.util.Locale

enum class Destination(val label: String, val icon: ImageVector) {
    LIBRARY("Library", Icons.Default.LibraryMusic),
    ALBUMS("Albums", Icons.Default.Album),
    ARTISTS("Artists", Icons.Default.Person),
    PLAYLISTS("Playlists", Icons.AutoMirrored.Filled.QueueMusic),
    IDENTIFY("Identify", Icons.Default.Fingerprint),
    MOODS("Moods", Icons.Default.Cloud),
    STATS("Stats", Icons.Default.BarChart),
    DOWNLOAD("Download", Icons.Default.Download)
}

/**
 * The desktop shell: navigation rail, content pane, docked panel, transport bar.
 *
 * This is a desktop layout, not the phone's translated. Navigation is a
 * persistent left rail rather than bottom tabs, the library is a dense sortable
 * table rather than 72dp list rows, right-click does what right-click does on
 * Windows, and playback controls live in a fixed bar across the bottom — the
 * arrangement every desktop music player has converged on, because it survives a
 * 1400px-wide window where a phone layout does not.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DesktopApp(
    controller: DesktopController,
    onChooseFolder: () -> File?,
    onChooseMedia: () -> File?,
    onPickPlaylistFile: (save: Boolean, suggested: String) -> File?
) {
    var destination by remember { mutableStateOf(Destination.LIBRARY) }
    var showSettings by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<DialogKind?>(null) }
    var playlistTargets by remember { mutableStateOf<List<DesktopTrack>>(emptyList()) }

    val status by controller.engine.status.collectAsState()
    val visible = controller.visibleTracks

    // Being on the Download page counts as having seen what finished, so the
    // Download item stops shining once you have been there.
    val onDownloadPage = !showSettings && destination == Destination.DOWNLOAD
    LaunchedEffect(onDownloadPage, controller.downloads) {
        if (onDownloadPage) controller.acknowledgeDownloads()
    }

    // Songs, folders and links dropped anywhere on the window.
    val dropTarget = remember(controller) {
        fileDropTarget(
            onHover = { controller.dropHover = it },
            onFiles = controller::importDropped,
            onText = controller::importDroppedText
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
    ) {
        Wallpaper(controller.wallpaper, controller.wallpaperDim, Modifier.matchParentSize())
        Column(
            Modifier
                .fillMaxSize()
                .background(if (controller.wallpaper != null) Color.Transparent else Palette.Base)
        ) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                NavigationRail(
                    controller = controller,
                    current = if (showSettings) null else destination,
                    settingsOpen = showSettings,
                    onSelect = { destination = it; showSettings = false },
                    onSettings = { showSettings = !showSettings }
                )

                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Palette.Content.copy(alpha = if (controller.wallpaper != null) 0.3f else 1f))
                ) {
                    Backdrop(
                        style = controller.backdrop,
                        color = Palette.Stars,
                        spectrum = controller.engine.spectrum,
                        graph = controller.songGraph,
                        beat = controller.engine.beat,
                        reactiveMode = controller.reactiveMode,
                        modifier = Modifier.matchParentSize()
                    )
                    Column(Modifier.fillMaxSize()) {
                        ContentHeader(
                            controller = controller,
                            title = if (showSettings) "Settings" else destination.label,
                            destination = destination,
                            showSettings = showSettings,
                            shownCount = visible.size,
                            onAddFolder = { onChooseFolder()?.let(controller::addFolder) },
                            onBulk = { dialog = DialogKind.BULK },
                            onDuplicates = {
                                controller.findDuplicates()
                                dialog = DialogKind.DUPLICATES
                            }
                        )

                        ArchiveStrip(controller)

                        if (controller.scanning) {
                            LinearProgressIndicator(
                                Modifier.fillMaxWidth().height(2.dp),
                                color = Palette.Accent,
                                trackColor = Palette.Line
                            )
                        }

                        when {
                            showSettings -> SettingsScreen(controller, onChooseFolder)
                            destination == Destination.LIBRARY ->
                                LibraryPane(
                                    controller = controller,
                                    tracks = visible,
                                    nowPlaying = status.track,
                                    onAddFolder = { onChooseFolder()?.let(controller::addFolder) },
                                    onAddToPlaylist = { items ->
                                        playlistTargets = items
                                        controller.refreshPlaylists()
                                        dialog = DialogKind.ADD_TO_PLAYLIST
                                    },
                                    onIdentify = { track ->
                                        controller.identifyTarget = track
                                        destination = Destination.IDENTIFY
                                        controller.identifyFile(track)
                                    }
                                )
                            destination == Destination.ALBUMS || destination == Destination.ARTISTS ->
                                CollectionScreen(
                                    controller = controller,
                                    kind = if (destination == Destination.ALBUMS) {
                                        CollectionKind.ALBUMS
                                    } else {
                                        CollectionKind.ARTISTS
                                    },
                                    onMerge = { dialog = DialogKind.MERGE },
                                    onClearDuplicates = { within ->
                                        controller.findDuplicates(within)
                                        dialog = DialogKind.DUPLICATES
                                    }
                                )
                            destination == Destination.PLAYLISTS ->
                                PlaylistsScreen(controller, onPickPlaylistFile)
                            destination == Destination.IDENTIFY ->
                                IdentifyScreen(controller, onChooseMedia)
                            destination == Destination.MOODS -> MoodsScreen(controller)
                            destination == Destination.STATS -> StatsScreen(controller)
                            destination == Destination.DOWNLOAD ->
                                DownloadScreen(controller, onChooseFolder)
                        }
                    }
                }

                val panel = controller.sidePanel
                if (panel != null) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Line))
                    SidePanel(
                        kind = panel,
                        controller = controller,
                        track = status.track,
                        positionMs = status.positionMs,
                        onClose = { controller.sidePanel = null }
                    )
                }
            }

            TransportBar(
                controller = controller,
                track = status.track,
                isPlaying = status.playing,
                positionMs = status.positionMs,
                durationMs = status.durationMs
            )
        }

        // Editing is driven off the controller rather than the local dialog
        // state, because it is opened from the row context menu and from the
        // Identify screen, and neither should have to know about the other.
        controller.editTarget?.let { track ->
            EditTrackDialog(controller, track) { controller.dismissEdit() }
        }

        when (dialog) {
            DialogKind.BULK -> BulkToolsDialog(controller) { dialog = null }
            DialogKind.DUPLICATES -> DuplicatesDialog(controller) { dialog = null }
            DialogKind.MERGE -> MergeDialog(controller) { dialog = null }
            DialogKind.ADD_TO_PLAYLIST ->
                AddToPlaylistDialog(controller, playlistTargets) { dialog = null }
            null -> Unit
        }

        if (controller.dropHover) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Palette.Base.copy(alpha = 0.82f))
                    .padding(28.dp)
                    .border(2.dp, Palette.Accent, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Drop to add to your library",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Palette.Text
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Songs are copied into your music folder, folders are added where " +
                            "they are, and links start downloading.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextDim
                    )
                }
            }
        }

        controller.dropNote?.let { note ->
            LaunchedEffect(note) {
                delay(5000)
                controller.dismissDropNote()
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.Raised)
                    .border(1.dp, Palette.Line, RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(note, style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
            }
        }
    }
}

@Composable
private fun NavigationRail(
    controller: DesktopController,
    current: Destination?,
    settingsOpen: Boolean,
    onSelect: (Destination) -> Unit,
    onSettings: () -> Unit
) {
    Box(
        Modifier
            .width(212.dp)
            .fillMaxHeight()
            .background(Palette.Sidebar.copy(alpha = if (controller.wallpaper != null) 0.6f else 1f))
    ) {
        Backdrop(
            style = controller.backdrop,
            color = Palette.Stars,
            spectrum = controller.engine.spectrum,
            graph = controller.songGraph,
            beat = controller.engine.beat,
            reactiveMode = controller.reactiveMode,
            modifier = Modifier.matchParentSize(),
            count = 40,
            centerpiece = false
        )
        Column(Modifier.fillMaxSize().padding(vertical = 14.dp)) {
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(Palette.Accent))
                Spacer(Modifier.width(10.dp))
                Text("Resonate", style = MaterialTheme.typography.titleLarge, color = Palette.Text)
            }

            Spacer(Modifier.height(14.dp))
            Destination.entries.forEach { entry ->
                if (entry == Destination.DOWNLOAD) {
                    val downloads = controller.downloads
                    RailItem(
                        label = entry.label,
                        icon = entry.icon,
                        selected = entry == current,
                        badge = downloads.count { !it.done },
                        shine = controller.unseenFinishedDownloads > 0
                    ) { onSelect(entry) }
                    RailDownloads(downloads) { onSelect(Destination.DOWNLOAD) }
                } else {
                    RailItem(entry.label, entry.icon, entry == current) { onSelect(entry) }
                }
            }

            Spacer(Modifier.weight(1f))
            RailItem("Settings", Icons.Default.Settings, settingsOpen, onClick = onSettings)
            Text(
                "made by lucent",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextDim,
                modifier = Modifier.padding(start = 20.dp, top = 10.dp)
            )
        }
    }
}

@Composable
private fun RailItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    /** A count at the top right; for the Download item, how many are running. */
    badge: Int = 0,
    /** A band of light sweeping across, while there is something new to look at. */
    shine: Boolean = false,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        selected -> Palette.Selected
        hovered -> Palette.Hover
        else -> Color.Transparent
    }
    // Only animated while shining, so an idle rail costs no frames. The sweep is
    // read inside the draw call, which repaints the item without recomposing it.
    val sweep = if (shine) {
        rememberInfiniteTransition(label = "rail-shine").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
            label = "sweep"
        )
    } else {
        null
    }
    val shape = RoundedCornerShape(7.dp)

    Box(
        Modifier
            .padding(horizontal = 10.dp, vertical = 1.dp)
            .fillMaxWidth()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(background)
                .then(
                    if (shine) Modifier.border(1.dp, Palette.Accent.copy(alpha = 0.55f), shape)
                    else Modifier
                )
                .drawWithContent {
                    drawContent()
                    val progress = sweep?.value ?: return@drawWithContent
                    val band = size.width * 0.45f
                    val x = -band + (size.width + band) * progress
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                Palette.Accent.copy(alpha = 0.30f),
                                Color.Transparent
                            ),
                            startX = x,
                            endX = x + band
                        )
                    )
                }
                .hoverable(interaction)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected || shine) Palette.Accent else Palette.TextDim,
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(11.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Palette.Text else Palette.TextDim
            )
        }
        if (badge > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 6.dp)
                    .clip(CircleShape)
                    .background(Palette.Accent)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    badge.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.OnAccent
                )
            }
        }
    }
}

/**
 * The latest few downloads, under the Download item.
 *
 * A glance rather than the list: newest first, four at most, each one a click
 * away from the full Download page.
 */
@Composable
private fun RailDownloads(downloads: List<DownloadEntry>, onOpen: () -> Unit) {
    if (downloads.isEmpty()) return
    val recent = downloads.asReversed().take(4)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 26.dp, end = 12.dp, top = 2.dp, bottom = 6.dp)
    ) {
        recent.forEach { entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(5.dp))
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
                    when {
                        entry.failed -> Icon(
                            Icons.Default.ErrorOutline, "Failed",
                            Modifier.size(12.dp), tint = Palette.TextFaint
                        )
                        entry.done -> Icon(
                            Icons.Default.Check, "Done",
                            Modifier.size(12.dp), tint = Palette.Accent
                        )
                        else -> CircularProgressIndicator(
                            Modifier.size(10.dp),
                            color = Palette.Accent,
                            strokeWidth = 1.5.dp,
                            trackColor = Palette.Line
                        )
                    }
                }
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.display,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (entry.done) Palette.TextFaint else Palette.TextDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!entry.done) {
                        Spacer(Modifier.height(3.dp))
                        LinearProgressIndicator(
                            progress = { entry.percent.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = Palette.Accent,
                            trackColor = Palette.Line
                        )
                    }
                }
            }
        }
        if (downloads.size > recent.size) {
            Text(
                "+${downloads.size - recent.size} more",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextFaint,
                modifier = Modifier.padding(start = 25.dp, top = 2.dp)
            )
        }
    }
}

@Composable
private fun ContentHeader(
    controller: DesktopController,
    title: String,
    destination: Destination,
    showSettings: Boolean,
    shownCount: Int,
    onAddFolder: () -> Unit,
    onBulk: () -> Unit,
    onDuplicates: () -> Unit
) {
    val subtitle = when {
        showSettings -> "Folders, downloads and weather"
        controller.scanning -> "Scanning — ${controller.scanned} files"
        destination == Destination.LIBRARY && controller.tracks.isEmpty() ->
            "No folders added yet"
        destination == Destination.LIBRARY && controller.query.isNotBlank() ->
            "$shownCount of ${controller.tracks.size} tracks"
        destination == Destination.LIBRARY ->
            "${controller.tracks.size} tracks · ${controller.folders.size} folders"
        destination == Destination.IDENTIFY -> "Seven catalogues, plus fingerprinting"
        destination == Destination.DOWNLOAD -> "YouTube, SoundCloud, Bandcamp, Spotify"
        destination == Destination.MOODS -> "Songs that match the weather"
        destination == Destination.ALBUMS || destination == Destination.ARTISTS ->
            controller.mergeNote
                ?: if (controller.merging) {
                    "Merging…"
                } else {
                    "Ctrl+click to pick several, then merge them"
                }
        else -> "${controller.tracks.size} tracks"
    }

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, color = Palette.Text)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (destination == Destination.LIBRARY && !showSettings) {
                TextInput(
                    value = controller.query,
                    onValueChange = { controller.query = it },
                    placeholder = "Search",
                    leading = Icons.Default.Search,
                    modifier = Modifier.width(240.dp)
                )
                Spacer(Modifier.width(10.dp))
                IconButton(onClick = { controller.rescan() }) {
                    Icon(
                        Icons.Default.Refresh, "Rescan",
                        tint = Palette.TextDim, modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onAddFolder) {
                    Icon(
                        Icons.Default.CreateNewFolder, "Add folder",
                        tint = Palette.TextDim, modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
                GhostButton("Music folder", icon = Icons.Default.FolderOpen) {
                    controller.openMusicFolder()
                }
            }
        }

        if (destination == Destination.LIBRARY && !showSettings && controller.tracks.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Seven sort options, a filter and two buttons do not fit
                // across the content pane once a side panel is open, and a Row
                // resolves that by crushing its children - which turned "Bulk
                // tools" into one letter per line. The sort chips scroll
                // instead, and the buttons sit outside the weighted region so
                // they keep their intrinsic width whatever else happens.
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SegmentedRow(
                        options = SortMode.entries,
                        selected = controller.sort,
                        label = { it.label },
                        onSelect = { controller.sort = it }
                    )
                    Spacer(Modifier.width(10.dp))
                    FilterToggle(
                        label = "Favourites",
                        active = controller.favouritesOnly
                    ) { controller.favouritesOnly = !controller.favouritesOnly }
                    Spacer(Modifier.width(10.dp))
                }

                GhostButton("Bulk tools", icon = Icons.Default.Tune, onClick = onBulk)
                Spacer(Modifier.width(8.dp))
                GhostButton(
                    "Duplicates",
                    icon = Icons.Default.ContentCopy,
                    onClick = onDuplicates
                )
                Spacer(Modifier.width(8.dp))
                GhostButton(
                    "Zip and ship",
                    enabled = !controller.archiveRunning,
                    icon = Icons.Default.Archive
                ) { controller.zipLibrary() }
            }
        }
    }
}

@Composable
private fun FilterToggle(label: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(
                when {
                    active -> Palette.Selected
                    hovered -> Palette.Hover
                    else -> Palette.Content
                }
            )
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (active) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            null,
            Modifier.size(13.dp),
            tint = if (active) Palette.Accent else Palette.TextDim
        )
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) Palette.Text else Palette.TextDim
        )
    }
}

@Composable
private fun LibraryPane(
    controller: DesktopController,
    tracks: List<DesktopTrack>,
    nowPlaying: DesktopTrack?,
    onAddFolder: () -> Unit,
    onAddToPlaylist: (List<DesktopTrack>) -> Unit,
    onIdentify: (DesktopTrack) -> Unit
) {
    if (controller.tracks.isEmpty() && !controller.scanning) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Default.FolderOpen,
                title = "No music yet",
                body = "Point Resonate at a folder and it reads what's inside.\n" +
                    "Your files stay exactly where they are.",
                action = { AccentButton("Choose folder", onClick = onAddFolder) }
            )
        }
        return
    }

    // Ctrl and Shift are read at click time rather than tracked, because a
    // modifier held down between compositions is not an event.
    val windowInfo = LocalWindowInfo.current

    Column(Modifier.fillMaxSize()) {
        if (controller.hasSelection) {
            SelectionBar(controller, tracks)
        }
        controller.selectionNote?.let { note ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Accent,
                    modifier = Modifier.weight(1f)
                )
                GhostButton("Dismiss") { controller.dismissSelectionNote() }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 32.dp, bottom = 8.dp)
        ) {
            HeaderCell("#", Modifier.width(40.dp))
            Spacer(Modifier.width(46.dp))
            HeaderCell("Title", Modifier.weight(2.2f))
            HeaderCell("Artist", Modifier.weight(1.4f))
            HeaderCell("Album", Modifier.weight(1.4f))
            HeaderCell(controller.sort.trailingColumn, Modifier.width(100.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))

        val listState = rememberLazyListState()
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(tracks, key = { _, t -> t.file.absolutePath }) { index, track ->
                TrackRow(
                    index = index + 1,
                    track = track,
                    controller = controller,
                    isCurrent = track.file == nowPlaying?.file,
                    isFavourite = track.file.absolutePath in controller.favourites,
                    isSelected = track.file.absolutePath in controller.selectedPaths,
                    onPlay = {
                        // A plain click plays, and drops any selection - leaving
                        // one active behind the thing you just started would be a trap.
                        controller.clearSelection()
                        controller.play(track, tracks)
                    },
                    onAddToPlaylist = { onAddToPlaylist(listOf(track)) },
                    onIdentify = { onIdentify(track) },
                    onToggleSelect = { controller.toggleSelection(track) },
                    onExtendSelect = { controller.extendSelection(track, tracks) }
                )
            }
        }
    }
}

/** The right-hand column swaps to whatever the current sort is about. */
private val SortMode.trailingColumn: String
    get() = when (this) {
        SortMode.PLAYS -> "Plays"
        SortMode.LISTEN_TIME -> "Listened"
        else -> "Time"
    }

@Composable
private fun HeaderCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextFaint,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    index: Int,
    track: DesktopTrack,
    controller: DesktopController,
    isCurrent: Boolean,
    isFavourite: Boolean,
    isSelected: Boolean,
    onPlay: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onIdentify: () -> Unit,
    onToggleSelect: () -> Unit = {},
    onExtendSelect: () -> Unit = {}
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    // Right-click gets the platform's own menu, which is what Windows users
    // reach for and what a hand-drawn popup never quite matches.
    ContextMenuArea(
        items = {
            listOf(
                ContextMenuItem("Play") { onPlay() },
                ContextMenuItem(
                    if (isFavourite) "Remove from favourites" else "Add to favourites"
                ) { controller.toggleFavourite(track) },
                ContextMenuItem("Add to playlist...") { onAddToPlaylist() },
                ContextMenuItem("Identify this track") { onIdentify() },
                ContextMenuItem("Edit details...") { controller.editTarget = track },
                ContextMenuItem("Show in Explorer") { revealInExplorer(track.file) },
                ContextMenuItem("Delete (move to Recycle Bin)") {
                    controller.deleteTracks(listOf(track))
                }
            )
        }
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    when {
                        // Selection outranks "now playing" here: while a
                        // selection exists it is the thing being acted on.
                        isSelected -> Palette.Accent.copy(alpha = 0.20f)
                        isCurrent -> Palette.Selected
                        hovered -> Palette.Hover
                        else -> Color.Transparent
                    }
                )
                .hoverable(interaction)
                // Ctrl and Shift are read from the click itself. They used to come
                // from the window's keyboard state, which a Ctrl+click did not
                // reliably reach, so it was taken for a plain click: it played the
                // track and dropped the selection instead of adding to it.
                .onClick(keyboardModifiers = { isCtrlPressed }, onClick = onToggleSelect)
                .onClick(keyboardModifiers = { isShiftPressed && !isCtrlPressed }, onClick = onExtendSelect)
                .onClick(keyboardModifiers = { !isCtrlPressed && !isShiftPressed }, onClick = onPlay)
                .padding(start = 24.dp, end = 32.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(40.dp)) {
                // The row number gives way to a play affordance on hover, which
                // is how desktop players signal "click here" without a button.
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        null,
                        Modifier.size(15.dp),
                        tint = Palette.Accent
                    )
                } else if (hovered) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(15.dp), tint = Palette.Text)
                } else {
                    Text(
                        index.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isCurrent) Palette.Accent else Palette.TextFaint
                    )
                }
            }
            Artwork(track, 34.dp)
            Spacer(Modifier.width(12.dp))
            Cell(
                track.title,
                Modifier.weight(2.2f),
                if (isCurrent) Palette.Accent else Palette.Text
            )
            Cell(track.displayArtist, Modifier.weight(1.4f), Palette.TextDim)
            Cell(track.displayAlbum, Modifier.weight(1.4f), Palette.TextDim)

            Row(
                Modifier.width(100.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hovered || isFavourite) {
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .clickable { controller.toggleFavourite(track) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isFavourite) {
                                Icons.Default.Favorite
                            } else {
                                Icons.Default.FavoriteBorder
                            },
                            "Favourite",
                            Modifier.size(13.dp),
                            tint = if (isFavourite) Palette.Accent else Palette.TextFaint
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                if (hovered) {
                    // On hover only: a delete button showing on every row would be
                    // a hazard in a long list. It goes to the Recycle Bin anyway.
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .clickable { controller.deleteTracks(listOf(track)) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            "Move to Recycle Bin",
                            Modifier.size(14.dp),
                            tint = Palette.TextFaint
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    trailingValue(controller, track),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextFaint,
                    maxLines = 1
                )
            }
        }
    }
}

private fun trailingValue(controller: DesktopController, track: DesktopTrack): String =
    when (controller.sort) {
        SortMode.PLAYS -> controller.playCountOf(track).toString()
        SortMode.LISTEN_TIME -> controller.listenedMsOf(track).asDuration()
        else -> track.durationMs.asDuration()
    }

@Composable
private fun Cell(text: String, modifier: Modifier, colour: Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = colour,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(end = 16.dp)
    )
}

@Composable
private fun TransportBar(
    controller: DesktopController,
    track: DesktopTrack?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long
) {
    val panel = controller.sidePanel
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
        Row(
            Modifier
                .fillMaxWidth()
                .height(76.dp)
                .background(Palette.Raised)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Artwork(track, 46.dp)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.width(200.dp)) {
                Text(
                    track?.title ?: "Nothing playing",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    track?.displayArtist ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (track != null) {
                Spacer(Modifier.width(6.dp))
                val favourite = track.file.absolutePath in controller.favourites
                IconButton(onClick = { controller.toggleFavourite(track) }) {
                    Icon(
                        if (favourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        "Favourite",
                        Modifier.size(16.dp),
                        tint = if (favourite) Palette.Accent else Palette.TextDim
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { controller.previous() }) {
                        Icon(
                            Icons.Default.SkipPrevious, "Previous",
                            Modifier.size(20.dp), tint = Palette.TextDim
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Palette.Accent)
                            .clickable { controller.togglePlay() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (isPlaying) "Pause" else "Play",
                            Modifier.size(18.dp),
                            tint = Palette.OnAccent
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { controller.next() }) {
                        Icon(
                            Icons.Default.SkipNext, "Next",
                            Modifier.size(20.dp), tint = Palette.TextDim
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        positionMs.asDuration(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.TextFaint,
                        modifier = Modifier.width(38.dp)
                    )
                    SeekBar(
                        fraction = if (durationMs > 0) {
                            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                        } else {
                            0f
                        },
                        enabled = track != null,
                        onSeek = { controller.seekFraction(it) }
                    )
                    Text(
                        durationMs.asDuration(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.TextFaint,
                        modifier = Modifier.width(38.dp),
                        textAlign = TextAlign.End
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            BarToggle(
                icon = Icons.Default.Lyrics,
                label = "Lyrics",
                active = panel == SidePanelKind.LYRICS,
                highlight = controller.lyricsPlain != null
            ) { controller.togglePanel(SidePanelKind.LYRICS) }
            BarToggle(
                icon = Icons.Default.GraphicEq,
                label = "Effects",
                active = panel == SidePanelKind.EFFECTS,
                highlight = !controller.fx.isDefault
            ) { controller.togglePanel(SidePanelKind.EFFECTS) }
            BarToggle(
                icon = Icons.Default.Speaker,
                label = "Output",
                active = panel == SidePanelKind.OUTPUT,
                highlight = controller.selectedOutputs.size > 1
            ) { controller.togglePanel(SidePanelKind.OUTPUT) }

            Spacer(Modifier.width(6.dp))
            // Ducking happens while another window has focus, so there has to
            // be something to look at afterwards that says it worked.
            Icon(
                if (controller.ducked) {
                    Icons.AutoMirrored.Filled.VolumeDown
                } else {
                    Icons.AutoMirrored.Filled.VolumeUp
                },
                if (controller.ducked) "Ducked for gaming" else null,
                Modifier.size(16.dp),
                tint = if (controller.ducked) Palette.Accent else Palette.TextDim
            )
            Slider(
                value = controller.volume,
                onValueChange = { controller.volume = it },
                colors = SliderDefaults.colors(
                    thumbColor = Palette.TextDim,
                    activeTrackColor = Palette.TextDim,
                    inactiveTrackColor = Palette.Line
                ),
                modifier = Modifier.width(104.dp)
            )
        }
    }
}

/** Click anywhere on the bar to jump there — a desktop expectation. */
@Composable
private fun SeekBar(fraction: Float, enabled: Boolean, onSeek: (Float) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box(
        Modifier
            .width(330.dp)
            .height(14.dp)
            .hoverable(interaction)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset -> onSeek(offset.x / size.width.toFloat()) }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (hovered) 5.dp else 3.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Palette.Line)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(Palette.Accent)
            )
        }
    }
}

/** Icon toggle that also shows accent colour when the feature is doing something. */
@Composable
private fun BarToggle(
    icon: ImageVector,
    label: String,
    active: Boolean,
    highlight: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(
                when {
                    active -> Palette.Selected
                    hovered -> Palette.Hover
                    else -> Color.Transparent
                }
            )
            .hoverable(interaction)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(17.dp),
            tint = if (active || highlight) Palette.Accent else Palette.TextDim
        )
    }
}

private fun revealInExplorer(file: File) = Explorer.reveal(file)

/**
 * Progress and outcome of a zip, under the toolbar.
 *
 * Shown here rather than in a dialog because archiving a large library takes a
 * while and there is no reason to block the app during it - you can carry on
 * browsing and come back when the path appears.
 */
@Composable
private fun ArchiveStrip(controller: DesktopController) {
    val note = controller.archiveNote
    val file = controller.archiveFile
    if (!controller.archiveRunning && note == null) return

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.Raised)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Archive,
                null,
                Modifier.size(15.dp),
                tint = Palette.Accent
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (controller.archiveRunning) "Building archive" else "Archive ready",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Text,
                modifier = Modifier.weight(1f)
            )
            if (controller.archiveRunning) {
                GhostButton("Stop") { controller.cancelArchive() }
            } else {
                if (file != null) {
                    GhostButton("Open folder", icon = Icons.Default.FolderOpen) {
                        controller.revealArchive()
                    }
                    Spacer(Modifier.width(8.dp))
                }
                GhostButton("Dismiss") { controller.dismissArchive() }
            }
        }

        if (controller.archiveRunning) {
            Spacer(Modifier.height(9.dp))
            ThinProgress(controller.archiveProgress)
            Spacer(Modifier.height(6.dp))
            Text(
                controller.archiveCurrent,
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        note?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Accent)
        }
        // The path is the point of the feature, so it is spelled out in full
        // rather than left for the user to go and find.
        file?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it.absolutePath,
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextDim
            )
        }
    }
}

/**
 * Actions for a multi-track selection.
 *
 * Ctrl+click to add one, Shift+click for a run, Ctrl+A for everything on
 * screen, Escape to drop it - the shortcuts a Windows user already has in their
 * fingers, so the bar states them rather than teaching them.
 */
@Composable
private fun SelectionBar(controller: DesktopController, visible: List<DesktopTrack>) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.Selected)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${controller.selectedPaths.size} selected",
            style = MaterialTheme.typography.titleMedium,
            color = Palette.Text
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "Ctrl+click to add · Shift+click for a range · Ctrl+A for all · Esc to clear",
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextFaint,
            modifier = Modifier.weight(1f)
        )

        GhostButton("Play", icon = Icons.Default.PlayArrow) {
            controller.playSelection(visible)
        }
        Spacer(Modifier.width(6.dp))
        GhostButton("Favourite", icon = Icons.Default.FavoriteBorder) {
            controller.favouriteSelection(visible)
        }
        Spacer(Modifier.width(6.dp))
        GhostButton("Zip and ship", icon = Icons.Default.Archive) {
            controller.zipSelection(visible)
        }
        Spacer(Modifier.width(6.dp))
        GhostButton("Show in Explorer", icon = Icons.Default.FolderOpen) {
            controller.revealSelection(visible)
        }
        Spacer(Modifier.width(6.dp))
        GhostButton("Delete", icon = Icons.Default.Delete) {
            controller.deleteSelection(visible)
        }
        Spacer(Modifier.width(6.dp))
        GhostButton("Clear") { controller.clearSelection() }
    }
}
