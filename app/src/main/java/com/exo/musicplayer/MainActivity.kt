package com.exo.musicplayer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exo.musicplayer.data.db.Track
import com.exo.musicplayer.ui.MainViewModel
import com.exo.musicplayer.ui.MoodState
import com.exo.musicplayer.ui.common.AddToPlaylistDialog
import com.exo.musicplayer.ui.common.BulkImportDialog
import com.exo.musicplayer.ui.common.DuplicatesDialog
import com.exo.musicplayer.ui.common.EditTrackDialog
import com.exo.musicplayer.ui.common.FixTagsDialog
import com.exo.musicplayer.ui.common.LibraryToolsDialog
import com.exo.musicplayer.ui.download.DownloadRequest
import com.exo.musicplayer.ui.download.DownloadScreen
import com.exo.musicplayer.ui.download.DownloadViewModel
import com.exo.musicplayer.ui.library.LibraryScreen
import com.exo.musicplayer.ui.moods.MoodsScreen
import com.exo.musicplayer.ui.player.MiniPlayer
import com.exo.musicplayer.ui.player.PlayerScreen
import com.exo.musicplayer.ui.playlists.PlaylistDetailScreen
import com.exo.musicplayer.share.ShareTracks
import com.exo.musicplayer.ui.playlists.PlaylistsScreen
import com.exo.musicplayer.ui.recognition.RecognitionScreen
import com.exo.musicplayer.ui.recognition.RecognitionViewModel
import com.exo.musicplayer.ui.stats.StatsScreen
import com.exo.musicplayer.ui.settings.AppearanceScreen
import com.exo.musicplayer.ui.settings.SettingsScreen
import com.exo.musicplayer.ui.settings.SoundScreen
import com.exo.musicplayer.ui.splash.SplashScreen
import com.exo.musicplayer.ui.theme.LocalStarfieldActive
import com.exo.musicplayer.ui.theme.LocalStarsVisible
import com.exo.musicplayer.ui.theme.MusicPlayerTheme
import com.exo.musicplayer.ui.theme.ScreenBackdrop
import com.exo.musicplayer.ui.theme.Starfield
import kotlinx.coroutines.delay
import com.exo.musicplayer.ui.theme.ThemeSettings
import com.exo.musicplayer.ui.theme.ThemeState

private enum class SettingsRoute { HOME, APPEARANCE, SOUND }

/** Opens the Telegram handle, falling back to the browser if Telegram isn't installed. */
private fun openTelegram(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Eth4wn"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "Couldn't open t.me/Eth4wn", Toast.LENGTH_SHORT).show() }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    LIBRARY("Library", Icons.Default.LibraryMusic),
    IDENTIFY("Identify", Icons.Default.Sensors),
    MOODS("Moods", Icons.Default.Cloud),
    PLAYLISTS("Lists", Icons.Default.QueueMusic),
    STATS("Stats", Icons.Default.BarChart)
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val recognitionViewModel: RecognitionViewModel by viewModels()
    private val downloadViewModel: DownloadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        musicApp.playback.connect()
        val openPlayerOnLaunch = intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)

        val themeSettings = musicApp.themeSettings
        setContent {
            val theme by themeSettings.state.collectAsStateWithLifecycle()
            MusicPlayerTheme(theme = theme) {
                NotificationPermissionGate()
                AppScaffold(
                    viewModel = viewModel,
                    recognition = recognitionViewModel,
                    download = downloadViewModel,
                    theme = theme,
                    themeSettings = themeSettings,
                    openPlayerOnLaunch = openPlayerOnLaunch
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}

/** Android 13+ hides the media notification without this, so ask once on first run. */
@Composable
private fun NotificationPermissionGate() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun AppScaffold(
    viewModel: MainViewModel,
    recognition: RecognitionViewModel,
    download: DownloadViewModel,
    theme: ThemeState,
    themeSettings: ThemeSettings,
    openPlayerOnLaunch: Boolean
) {
    val context = LocalContext.current
    var settingsRoute by remember { mutableStateOf<SettingsRoute?>(null) }
    // Read once: the flag is flipped immediately so a recomposition can't replay it.
    val app = context.applicationContext as MusicApp
    var showSplash by remember { mutableStateOf(!app.splashShown) }
    LaunchedEffect(Unit) {
        if (showSplash) {
            app.splashShown = true
            delay(1700)
            showSplash = false
        }
    }
    var showDownload by remember { mutableStateOf(false) }
    // With stars painted behind everything, opaque containers would hide them.
    val starry = LocalStarfieldActive.current
    val containerColor = if (starry) Color.Transparent else MaterialTheme.colorScheme.background
    // Material derives content colour from the container, and there is no "on"
    // colour for Transparent — it resolves to unspecified and falls back to
    // black. With the starfield on, that turned every header dark-on-dark, so
    // the content colour is stated explicitly.
    val onContainerColor = MaterialTheme.colorScheme.onBackground
    var tab by remember { mutableStateOf(Tab.LIBRARY) }
    var playerOpen by remember { mutableStateOf(openPlayerOnLaunch) }
    var addingToPlaylist by remember { mutableStateOf<Track?>(null) }

    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val playlistNote by viewModel.playlistNote.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val archiveState by viewModel.archive.collectAsStateWithLifecycle()
    var selectionForPlaylist by remember { mutableStateOf<List<Track>>(emptyList()) }
    val playlistImport by viewModel.importResult.collectAsStateWithLifecycle()
    val state by viewModel.playbackState.collectAsStateWithLifecycle()
    val currentTrackId by viewModel.currentTrackId.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.searchResults.collectAsStateWithLifecycle()
    val openPlaylist by viewModel.openPlaylist.collectAsStateWithLifecycle()
    val openPlaylistTracks by viewModel.openPlaylistTracks.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val mood by viewModel.mood.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val editTarget by viewModel.editTarget.collectAsStateWithLifecycle()
    val tagTarget by viewModel.tagTarget.collectAsStateWithLifecycle()
    val tagBusy by viewModel.tagBusy.collectAsStateWithLifecycle()
    val tagResult by viewModel.tagResult.collectAsStateWithLifecycle()
    val bulk by viewModel.bulk.collectAsStateWithLifecycle()
    val bulkCounts by viewModel.bulkCounts.collectAsStateWithLifecycle()
    var showTools by remember { mutableStateOf(false) }
    var showDuplicates by remember { mutableStateOf(false) }
    val duplicates by viewModel.duplicates.collectAsStateWithLifecycle()
    val duplicateScanning by viewModel.duplicateScanning.collectAsStateWithLifecycle()
    val audioFx by viewModel.audioFx.collectAsStateWithLifecycle()
    val audioOutputs by viewModel.audioOutputs.collectAsStateWithLifecycle()
    val selectedOutputs by viewModel.selectedOutputs.collectAsStateWithLifecycle()
    val mirrorOutputs by viewModel.mirrorOutputs.collectAsStateWithLifecycle()
    val interruption by viewModel.interruption.collectAsStateWithLifecycle()
    val lyrics by viewModel.currentLyrics.collectAsStateWithLifecycle()
    val lyricLines by viewModel.currentLyricLines.collectAsStateWithLifecycle()
    val lyricsBusy by viewModel.lyricsBusy.collectAsStateWithLifecycle()
    val lyricsMessage by viewModel.lyricsMessage.collectAsStateWithLifecycle()

    // Audio files chosen here go through the same import pipeline as a share.
    val audioImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.importFromUris(uris) }

    val folderImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.importFolder(treeUri, treeUri.lastPathSegment?.substringAfterLast('/'))
        }
    }

    val identifyVideoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { recognition.identify(it, it.lastPathSegment) } }

    val identifyAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { recognition.identify(it, it.lastPathSegment) } }

    // Playlist exchange goes through the storage picker, so the file lands
    // wherever the user can actually find and share it from.
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val playlistExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val text = pendingExport
        pendingExport = null
        if (uri != null && text != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }
        }
    }
    val playlistImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text != null) viewModel.importPlaylist(text, "Imported playlist")
        }
    }

    playlistImport?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissImport() },
            title = { Text("\"${pending.name}\"") },
            text = {
                Column {
                    Text(
                        "${pending.matched.size} of ${pending.total} tracks are already " +
                            "on this device. Nothing is downloaded — the playlist just " +
                            "links up what you have."
                    )
                    if (pending.missing.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Missing",
                            style = MaterialTheme.typography.labelLarge
                        )
                        pending.missing.take(6).forEach {
                            Text(
                                it.display,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                        if (pending.missing.size > 6) {
                            Text(
                                "and ${pending.missing.size - 6} more",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmImport(pending) },
                    enabled = pending.matched.isNotEmpty()
                ) { Text("Add ${pending.matched.size} tracks") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissImport() }) { Text("Cancel") }
            }
        )
    }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.refreshMood(force = true) }

    LaunchedEffect(tab) { if (tab == Tab.MOODS) viewModel.refreshMood() }

    LaunchedEffect(notice) {
        notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeNotice()
        }
    }

    Scaffold(
        // The bars below handle their own insets; letting Scaffold consume the
        // status bar too would double-pad every screen.
        contentWindowInsets = WindowInsets.statusBars,
        containerColor = containerColor,
        contentColor = onContainerColor,
        bottomBar = {
            Column {
                MiniPlayer(
                    track = currentTrack,
                    state = state,
                    onExpand = { playerOpen = true },
                    onTogglePlayPause = viewModel::togglePlayPause,
                    onNext = { viewModel.next() }
                )
                NavigationBar {
                    Tab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = { Icon(entry.icon, contentDescription = entry.label) },
                            label = { Text(entry.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Surface(
            color = containerColor,
            contentColor = onContainerColor,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (tab) {
                Tab.LIBRARY -> {
                // Selection actions operate on what is on screen, which is the
                // search results while a query is active and the full library
                // otherwise - selecting a search result and then acting on the
                // unfiltered list would hit the wrong tracks.
                val visibleTracks = if (query.isNotBlank()) results else tracks
                LibraryScreen(
                    tracks = tracks,
                    searchResults = results,
                    query = query,
                    sort = sort,
                    currentTrackId = currentTrackId,
                    isPlaying = isPlaying,
                    onQueryChange = viewModel::setQuery,
                    onAddFiles = { audioImportLauncher.launch(AUDIO_MIME_TYPES) },
                    onAddFolder = { folderImportLauncher.launch(null) },
                    onOpenSettings = { settingsRoute = SettingsRoute.HOME },
                    onUpdateCovers = { showTools = true },
                    onOpenDownload = { showDownload = true },
                    onSortChange = viewModel::setSort,
                    onPlayFrom = { list, index -> viewModel.playFrom(list, index) },
                    onPlayAll = viewModel::playAll,
                    onShuffleAll = viewModel::shuffleAll,
                    onPlayNext = viewModel::playNext,
                    onAddToQueue = viewModel::addToQueue,
                    onAddToPlaylist = { addingToPlaylist = it },
                    onToggleFavorite = { viewModel.toggleFavorite(it) },
                    onFixTags = { viewModel.startFixTags(it) },
                    onEditDetails = { viewModel.editTrack(it) },
                    onDelete = { viewModel.deleteTrack(it) },
                    selectedIds = selectedIds,
                    onToggleSelect = { viewModel.toggleSelected(it.id) },
                    onClearSelection = viewModel::clearSelection,
                    onSelectAll = { viewModel.selectAll(visibleTracks) },
                    onShareSelected = {
                        val chosen = viewModel.selectedTracks(visibleTracks)
                        val intent = ShareTracks.intentFor(context, chosen)
                        if (intent == null) {
                            Toast.makeText(
                                context,
                                "Those files are missing from storage.",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            context.startActivity(
                                Intent.createChooser(
                                    intent,
                                    if (chosen.size == 1) "Share song" else "Share ${chosen.size} songs"
                                )
                            )
                            viewModel.clearSelection()
                        }
                    },
                    onPlaylistSelected = { selectionForPlaylist = viewModel.selectedTracks(visibleTracks) },
                    onFavoriteSelected = { viewModel.favoriteSelected(visibleTracks) },
                    onDeleteSelected = { viewModel.deleteSelected(visibleTracks) }
                )
                }

                Tab.IDENTIFY -> {
                    val stage by recognition.stage.collectAsStateWithLifecycle()
                    val result by recognition.result.collectAsStateWithLifecycle()
                    val rquery by recognition.query.collectAsStateWithLifecycle()
                    val label by recognition.sourceLabel.collectAsStateWithLifecycle()
                    val rmode by recognition.mode.collectAsStateWithLifecycle()

                    val rartist by recognition.artist.collectAsStateWithLifecycle()
                    val rtitle by recognition.title.collectAsStateWithLifecycle()
                    val youtubeResults by recognition.youtubeResults.collectAsStateWithLifecycle()
                    val youtubeStatus by recognition.youtubeStatus.collectAsStateWithLifecycle()
                    val preview by recognition.preview.state.collectAsStateWithLifecycle()

                    RecognitionScreen(
                        stage = stage,
                        result = result,
                        query = rquery,
                        sourceLabel = label,
                        onQueryChange = recognition::setQuery,
                        artist = rartist,
                        onArtistChange = recognition::setArtist,
                        title = rtitle,
                        onTitleChange = recognition::setTitle,
                        onSearch = recognition::runSearch,
                        mode = rmode,
                        onModeChange = recognition::setMode,
                        onPickVideo = { identifyVideoLauncher.launch(VIDEO_MIME_TYPES) },
                        onPickAudio = { identifyAudioLauncher.launch(AUDIO_MIME_TYPES) },
                        onFindInLibrary = { match ->
                            viewModel.setQuery(match.title)
                            tab = Tab.LIBRARY
                        },
                        onDownload = { match ->
                            // The match's own fields rather than a joined-up
                            // string, so the YouTube finder can check the title,
                            // the artist and the length separately.
                            download.downloadMatch(
                                downloadUrl = match.downloadUrl,
                                artist = match.artist,
                                title = match.title,
                                durationMs = match.durationMs
                            )
                            showDownload = true
                        },
                        youtubeResults = youtubeResults,
                        youtubeStatus = youtubeStatus,
                        preview = preview,
                        onPreviewYouTube = recognition::previewYouTube,
                        onDownloadYouTube = { video ->
                            // A real watch URL, so this takes the direct path
                            // rather than the search-by-name fallback.
                            download.downloadMatch(
                                downloadUrl = video.watchUrl,
                                artist = null,
                                title = video.title,
                                durationMs = null
                            )
                            // The preview would otherwise keep playing over the
                            // download screen it just opened.
                            recognition.stopPreview()
                            showDownload = true
                        },
                        onDownloadMany = { matches, videos ->
                            download.downloadAll(
                                matches.map { DownloadRequest(it.downloadUrl, it.artist, it.title, it.durationMs) } +
                                    videos.map { DownloadRequest(it.watchUrl, null, it.title, null) }
                            )
                            recognition.stopPreview()
                            showDownload = true
                        },
                        onCopy = { match ->
                            copyToClipboard(context, match.display)
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }
                    )
                }

                Tab.MOODS -> MoodsScreen(
                    state = mood,
                    currentTrackId = currentTrackId,
                    isPlaying = isPlaying,
                    onRequestPermission = {
                        locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    },
                    onRefresh = { viewModel.refreshMood(force = true) },
                    onPlayMix = viewModel::playMood,
                    onPlayFrom = { index ->
                        (mood as? MoodState.Ready)?.let { viewModel.playFrom(it.tracks, index) }
                    },
                    onPlayNext = viewModel::playNext,
                    onAddToQueue = viewModel::addToQueue,
                    onAddToPlaylist = { addingToPlaylist = it },
                    onToggleFavorite = { viewModel.toggleFavorite(it) },
                    onFixTags = { viewModel.startFixTags(it) },
                    onEditDetails = { viewModel.editTrack(it) }
                )

                Tab.PLAYLISTS -> {
                    val open = openPlaylist
                    if (open == null) {
                        PlaylistsScreen(
                            playlists = playlists,
                            onOpen = { viewModel.showPlaylist(it.id) },
                            onCreate = { viewModel.createPlaylist(it) },
                            onDelete = { viewModel.deletePlaylist(it) },
                            onPlay = { viewModel.playPlaylist(it) },
                            onExport = { playlist ->
                                viewModel.exportPlaylist(playlist) { name, text ->
                                    pendingExport = text
                                    playlistExportLauncher.launch("$name.txt")
                                    true
                                }
                            },
                            onImport = { playlistImportLauncher.launch(arrayOf("text/*")) },
                            note = playlistNote
                        )
                    } else {
                        PlaylistDetailScreen(
                            name = open.name,
                            tracks = openPlaylistTracks,
                            currentTrackId = currentTrackId,
                            isPlaying = isPlaying,
                            onBack = { viewModel.showPlaylist(null) },
                            onPlayFrom = { index ->
                                viewModel.playFrom(openPlaylistTracks, index)
                            },
                            onPlayNext = viewModel::playNext,
                            onAddToQueue = viewModel::addToQueue,
                            onToggleFavorite = { viewModel.toggleFavorite(it) },
                            onFixTags = { viewModel.startFixTags(it) },
                            onEditDetails = { viewModel.editTrack(it) },
                            onRemoveFromPlaylist = {
                                viewModel.removeFromPlaylist(open.id, it.id)
                            }
                        )
                    }
                }

                Tab.STATS -> {
                    val totalMs by viewModel.totalListenedMs.collectAsStateWithLifecycle()
                    val plays by viewModel.totalPlays.collectAsStateWithLifecycle()
                    val distinct by viewModel.distinctTracksPlayed.collectAsStateWithLifecycle()
                    val top by viewModel.topTracks.collectAsStateWithLifecycle()
                    val week by viewModel.topThisWeek.collectAsStateWithLifecycle()
                    val hours by viewModel.listeningByHour.collectAsStateWithLifecycle()
                    val weathers by viewModel.listeningByWeather.collectAsStateWithLifecycle()

                    StatsScreen(
                        totalListenedMs = totalMs,
                        totalPlays = plays,
                        distinctTracks = distinct,
                        topTracks = top,
                        topThisWeek = week,
                        favorites = favorites,
                        byHour = hours,
                        byWeather = weathers,
                        currentTrackId = currentTrackId,
                        onPlayTrack = { list, index -> viewModel.playFrom(list, index) }
                    )
                }
            }
        }
    }

    // Rendered above the Scaffold, so it applies system-bar insets itself.
    AnimatedVisibility(
        visible = playerOpen,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut()
    ) {
        PlayerScreen(
            track = currentTrack,
            state = state,
            queue = queue,
            lyrics = lyrics,
            lyricLines = lyricLines,
            lyricsBusy = lyricsBusy,
            lyricsMessage = lyricsMessage,
            onCollapse = { playerOpen = false },
            onTogglePlayPause = viewModel::togglePlayPause,
            onNext = { viewModel.next() },
            onPrevious = { viewModel.previous() },
            onSeek = viewModel::seekToFraction,
            onToggleShuffle = viewModel::toggleShuffle,
            onCycleRepeat = viewModel::cycleRepeat,
            onToggleFavorite = { currentTrack?.let { viewModel.toggleFavorite(it) } },
            onQueueItemClick = { viewModel.jumpToQueueIndex(it) },
            fx = audioFx,
            onPreset = viewModel::applyFxPreset,
            onSpeed = viewModel::setFxSpeed,
            onPitch = viewModel::setFxPitch,
            onReverbEnabled = viewModel::setReverbEnabled,
            onReverbRoom = viewModel::setReverbRoom,
            onReverbAmount = viewModel::setReverbAmount,
            onResetFx = viewModel::resetFx,
            outputs = audioOutputs,
            selectedOutputs = selectedOutputs,
            mirrorOutputs = mirrorOutputs,
            onPickOutput = viewModel::pickOutput,
            onMirrorOutputs = viewModel::setMirrorOutputs,
            onFetchLyrics = { viewModel.fetchLyrics() },
            onSaveLyrics = { viewModel.saveLyrics(it) },
            onDeleteLyrics = { viewModel.deleteLyrics() }
        )
    }

    if (archiveState.running || archiveState.note != null) {
        AlertDialog(
            onDismissRequest = { if (!archiveState.running) viewModel.dismissArchive() },
            title = { Text(if (archiveState.running) "Zipping" else "Zip and ship") },
            text = {
                Column {
                    if (archiveState.running) {
                        LinearProgressIndicator(
                            progress = { archiveState.fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            archiveState.current,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                    }
                    archiveState.note?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    // The path is the whole point, so it is spelled out rather
                    // than left for the user to hunt for.
                    archiveState.file?.let { file ->
                        Spacer(Modifier.height(10.dp))
                        Text("Saved to", style = MaterialTheme.typography.labelMedium)
                        Text(
                            file.parent ?: file.absolutePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(file.name, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                val file = archiveState.file
                if (archiveState.running) {
                    TextButton(onClick = { viewModel.cancelArchive() }) { Text("Stop") }
                } else if (file != null) {
                    TextButton(onClick = {
                        val uri = runCatching {
                            androidx.core.content.FileProvider.getUriForFile(
                                context, context.packageName + ".shared", file
                            )
                        }.getOrNull()
                        if (uri == null) {
                            Toast.makeText(context, "Couldn't share that file.", Toast.LENGTH_SHORT)
                                .show()
                        } else {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, file.name)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Send archive"))
                        }
                    }) { Text("Ship it") }
                } else {
                    TextButton(onClick = { viewModel.dismissArchive() }) { Text("Close") }
                }
            },
            dismissButton = if (archiveState.running) null else {
                { TextButton(onClick = { viewModel.dismissArchive() }) { Text("Close") } }
            }
        )
    }

    addingToPlaylist?.let { track ->
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { addingToPlaylist = null },
            onPick = { playlistId ->
                viewModel.addToPlaylist(playlistId, track.id)
                addingToPlaylist = null
            },
            onCreate = { name -> viewModel.createPlaylist(name, listOf(track.id)) }
        )
    }

    // The same dialog for a whole selection. Kept separate from the
    // single-track case rather than made nullable-plural, because the two
    // clear different state on the way out.
    if (selectionForPlaylist.isNotEmpty()) {
        val chosen = selectionForPlaylist
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { selectionForPlaylist = emptyList() },
            onPick = { playlistId ->
                viewModel.addSelectedToPlaylist(playlistId, chosen)
                selectionForPlaylist = emptyList()
            },
            onCreate = { name ->
                viewModel.createPlaylist(name, chosen.map { it.id })
                viewModel.clearSelection()
                selectionForPlaylist = emptyList()
            }
        )
    }

    AnimatedVisibility(
        visible = showDownload,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut()
    ) {
        val dlState by download.state.collectAsStateWithLifecycle()
        val dlUrl by download.url.collectAsStateWithLifecycle()
        val dlQuality by download.quality.collectAsStateWithLifecycle()
        Surface(
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                if (starry) ScreenBackdrop()
                Box(Modifier.windowInsetsPadding(WindowInsets.systemBars)) {
            DownloadScreen(
                state = dlState,
                url = dlUrl,
                onUrlChange = download::setUrl,
                onDownload = download::download,
                quality = dlQuality,
                onQualityChange = download::setQuality,
                onCancel = download::cancel,
                onUpdate = download::update,
                onBack = { showDownload = false }
            )
                }
            }
        }
    }

    AnimatedVisibility(
        visible = settingsRoute != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut()
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                if (starry) ScreenBackdrop()
                Box(Modifier.windowInsetsPadding(WindowInsets.systemBars)) {
                    when (settingsRoute) {
                        SettingsRoute.APPEARANCE -> AppearanceScreen(
                            theme = theme,
                            onBack = { settingsRoute = SettingsRoute.HOME },
                            onPalette = themeSettings::setPalette,
                            onMode = themeSettings::setMode,
                            onDynamic = themeSettings::setDynamicColor,
                            onStars = themeSettings::setStars,
                            onWallpaper = themeSettings::setWallpaper,
                            onClearWallpaper = themeSettings::clearWallpaper,
                            onWallpaperDim = themeSettings::setWallpaperDim,
                            onLyricsActive = themeSettings::setLyricsActive,
                            onLyricsInactive = themeSettings::setLyricsInactive
                        )

                        SettingsRoute.SOUND -> SoundScreen(
                            interruption = interruption,
                            onInterruption = viewModel::setInterruptionBehavior,
                            onPlayDuringCalls = viewModel::setPlayDuringCalls,
                            onBack = { settingsRoute = SettingsRoute.HOME }
                        )

                        else -> SettingsScreen(
                            onBack = { settingsRoute = null },
                            onAppearance = { settingsRoute = SettingsRoute.APPEARANCE },
                            onSound = { settingsRoute = SettingsRoute.SOUND },
                            onContact = { openTelegram(context) }
                        )
                    }
                }
            }
        }
    }

    if (showTools) {
        LibraryToolsDialog(
            counts = bulkCounts,
            onCovers = { redo -> showTools = false; viewModel.updateAllCovers(redo) },
            onIdentify = { redo -> showTools = false; viewModel.identifyAll(redo) },
            onLyrics = { redo -> showTools = false; viewModel.fetchAllLyrics(redo) },
            onZip = { showTools = false; viewModel.zipLibrary() },
            onDuplicates = {
                showTools = false
                showDuplicates = true
                viewModel.scanDuplicates()
            },
            onDismiss = { showTools = false }
        )
    }

    if (showDuplicates) {
        DuplicatesDialog(
            groups = duplicates,
            scanning = duplicateScanning,
            onRemove = { chosen ->
                viewModel.removeDuplicates(chosen)
                showDuplicates = false
            },
            onDismiss = { showDuplicates = false; viewModel.clearDuplicates() }
        )
    }

    bulk?.let { progress ->
        BulkImportDialog(progress = progress, onCancel = { viewModel.cancelBulkImport() })
    }

    editTarget?.let { track ->
        EditTrackDialog(
            track = track,
            onSave = { title, artist, album, year ->
                viewModel.saveTrackDetails(track, title, artist, album, year)
            },
            onDismiss = { viewModel.dismissEdit() }
        )
    }

    tagTarget?.let { track ->
        FixTagsDialog(
            track = track,
            busy = tagBusy,
            result = tagResult,
            onApply = { viewModel.applyTagMatch(it) },
            onDismiss = { viewModel.dismissFixTags() }
        )
    }

    AnimatedVisibility(visible = showSplash, exit = fadeOut()) {
        SplashScreen(showStars = LocalStarsVisible.current)
    }

    BackHandler(enabled = showDownload) { showDownload = false }
    BackHandler(enabled = !showDownload && settingsRoute != null) {
        settingsRoute = if (settingsRoute == SettingsRoute.HOME) null else SettingsRoute.HOME
    }
    BackHandler(enabled = settingsRoute == null && playerOpen) { playerOpen = false }
    BackHandler(enabled = settingsRoute == null && !playerOpen && openPlaylist != null && tab == Tab.PLAYLISTS) {
        viewModel.showPlaylist(null)
    }
    BackHandler(enabled = settingsRoute == null && !playerOpen && tab != Tab.LIBRARY) { tab = Tab.LIBRARY }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Song", text))
}

/**
 * Some providers report audio documents as octet-stream, so the picker asks for
 * that too; TrackImporter rejects anything that isn't really audio.
 */
private val AUDIO_MIME_TYPES = arrayOf("audio/*", "application/ogg", "application/octet-stream")
private val VIDEO_MIME_TYPES = arrayOf("video/*")
