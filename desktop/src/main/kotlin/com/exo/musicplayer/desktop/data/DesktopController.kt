package com.exo.musicplayer.desktop.data

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.exo.musicplayer.data.archive.ArchiveEntry
import com.exo.musicplayer.data.archive.MusicArchive
import com.exo.musicplayer.data.download.DownloadQuality
import com.exo.musicplayer.data.download.LinkResolver
import com.exo.musicplayer.data.download.ResolvedLink
import com.exo.musicplayer.data.library.DuplicateMatcher
import com.exo.musicplayer.data.lyrics.GeniusLyricsProvider
import com.exo.musicplayer.data.lyrics.LrcLibProvider
import com.exo.musicplayer.data.lyrics.LyricsFetch
import com.exo.musicplayer.data.lyrics.LyricsOvhProvider
import com.exo.musicplayer.data.lyrics.LyricsProviderChain
import com.exo.musicplayer.data.lyrics.NeteaseLyricsProvider
import com.exo.musicplayer.data.recognition.AudiusProvider
import com.exo.musicplayer.data.recognition.DeezerProvider
import com.exo.musicplayer.data.recognition.GeniusLyricSearch
import com.exo.musicplayer.data.recognition.GeniusMetadataProvider
import com.exo.musicplayer.data.recognition.ITunesProvider
import com.exo.musicplayer.data.recognition.InternetArchiveProvider
import com.exo.musicplayer.data.recognition.MetadataProviderChain
import com.exo.musicplayer.data.recognition.MusicBrainzProvider
import com.exo.musicplayer.data.recognition.MatchRanker
import com.exo.musicplayer.data.recognition.MusicMatch
import com.exo.musicplayer.data.recognition.NeteaseLyricSearch
import com.exo.musicplayer.data.recognition.RecognitionResult
import com.exo.musicplayer.data.recognition.ShazamClient
import com.exo.musicplayer.data.recognition.YouTubeSearchProvider
import com.exo.musicplayer.data.playlist.ImportResult
import com.exo.musicplayer.data.playlist.PlaylistEntry
import com.exo.musicplayer.data.playlist.PlaylistFile
import com.exo.musicplayer.data.weather.Affinity
import com.exo.musicplayer.data.weather.GeoPlace
import com.exo.musicplayer.data.weather.OpenMeteo
import com.exo.musicplayer.data.weather.WeatherAffinity
import com.exo.musicplayer.data.weather.WeatherCondition
import com.exo.musicplayer.data.weather.WeatherSnapshot
import com.exo.musicplayer.desktop.audio.AudioDevices
import com.exo.musicplayer.desktop.audio.DesktopAudioOutput
import com.exo.musicplayer.desktop.audio.MediaAudio
import com.exo.musicplayer.desktop.audio.SampleOutcome
import com.exo.musicplayer.data.youtube.PipedYouTubeBackend
import com.exo.musicplayer.data.youtube.YouTubeFormat
import com.exo.musicplayer.data.youtube.YouTubeLinkFinder
import com.exo.musicplayer.data.youtube.YouTubeSearch
import com.exo.musicplayer.data.youtube.YouTubeVideo
import com.exo.musicplayer.desktop.audio.PreviewPlayer
import com.exo.musicplayer.desktop.audio.SongGraph
import com.exo.musicplayer.desktop.download.DesktopYouTubeBackend
import com.exo.musicplayer.desktop.audio.PlaybackEngine
import com.exo.musicplayer.desktop.download.DownloadProgress
import com.exo.musicplayer.desktop.download.ToolStatus
import com.exo.musicplayer.desktop.download.YtDlp
import com.exo.musicplayer.desktop.system.DuckKey
import com.exo.musicplayer.desktop.system.Explorer
import com.exo.musicplayer.desktop.system.GlobalHotkey
import com.exo.musicplayer.desktop.library.DesktopTrack
import com.exo.musicplayer.desktop.library.FolderLibrary
import com.exo.musicplayer.desktop.ui.AccentChoice
import com.exo.musicplayer.desktop.ui.BackdropStyle
import com.exo.musicplayer.desktop.ui.DesktopFxState
import com.exo.musicplayer.desktop.ui.Palette
import com.exo.musicplayer.desktop.ui.ReactiveMode
import com.exo.musicplayer.desktop.ui.SidePanelKind
import com.exo.musicplayer.desktop.ui.ThemeColors
import com.exo.musicplayer.util.AudioTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** How the library table is ordered. */
enum class SortMode(val label: String) {
    ARTIST("Artist"),
    TITLE("Title"),
    ALBUM("Album"),
    DURATION("Duration"),
    PLAYS("Times played"),
    LISTEN_TIME("Time listened"),
    ADDED("Date added")
}

/** Progress of one of the bulk tools. */
data class BulkJob(
    val label: String = "",
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val updated: Int = 0,
    val alreadyHad: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val current: String = "",
    val finishedNote: String? = null
) {
    val progress: Float get() = if (total > 0) done.toFloat() / total else 0f
}

/** One line in the download window's history. */
data class DownloadEntry(
    val id: Long,
    val target: String,
    var display: String,
    var percent: Float = 0f,
    var status: String = "Queued",
    var done: Boolean = false,
    var failed: Boolean = false,
    var files: List<File> = emptyList(),
    var note: String? = null
)

/** A duplicate set as the desktop sees it. */
data class DesktopDuplicateGroup(val keep: DesktopTrack, val remove: List<DesktopTrack>)

/**
 * Everything the Windows UI reads and acts on.
 *
 * A single observable state holder rather than thirty hoisted parameters. The
 * screens grew past the point where threading callbacks through the shell was
 * readable, and unlike Android there is no ViewModel to inherit — so this plays
 * the same role, owning the store, the playback engine and the provider chains,
 * and exposing plain Compose state.
 *
 * Every network and disk call goes through [io]; Compose state is only ever
 * written from the caller's (main) dispatcher.
 */
@Stable
class DesktopController(parent: CoroutineScope) {

    /**
     * The controller's own scope, supervised.
     *
     * Everything in here is launched from it. As a plain child of the window's
     * scope, the first job to throw - an unreadable file, a service dying
     * mid-call - cancelled that scope, and with it every later scan, lyric
     * fetch, download and identification, silently, until the app was
     * restarted. Supervised, a failure stays where it happened, and the handler
     * clears whatever was marked as running so nothing is left spinning for
     * work that has died.
     */
    private val scope = CoroutineScope(
        parent.coroutineContext +
            SupervisorJob(parent.coroutineContext[Job]) +
            CoroutineExceptionHandler { _, error ->
                scanning = false
                lyricsLoading = false
                youtubeBusy = false
                identifyBusy = false
                merging = false
                System.err.println("Resonate: a background job failed: ${error.stackTraceToString()}")
            }
    )

    val settings = DesktopSettings()
    val store = DesktopStore(AppDirs.database)
    val engine = PlaybackEngine()

    /** The playing song read ahead, for the Waves background. */
    val songGraph = SongGraph(engine)

    init {
        ThemeColors.decode(settings.customTheme)?.let(Palette::setCustom)
        Palette.setStars(ThemeColors.parse(settings.backdropColor))
        Palette.setLyricsActive(ThemeColors.parse(settings.lyricsActiveColor))
        Palette.setLyricsInactive(ThemeColors.parse(settings.lyricsInactiveColor))
        Palette.use(AccentChoice.fromName(settings.accentName))
    }

    // ---- Providers ----------------------------------------------------------
    //
    // Seven catalogues, four lyric tiers. Identical to the Android build: the
    // implementations live in :shared, so neither platform has its own copy to
    // fall out of date.

    private val catalogue = MetadataProviderChain(
        listOf(
            ITunesProvider(),
            DeezerProvider(),
            MusicBrainzProvider(),
            AudiusProvider(),
            InternetArchiveProvider(),
            YouTubeSearchProvider(),
            GeniusMetadataProvider()
        )
    )

    /**
     * Where the bulk cover and tag tools look, best first: YouTube, then the
     * stores and the other free catalogues, and MusicBrainz last - its search
     * answers loosely and most of its cover links are dead. Internet Archive is
     * left out, since its results carry neither art nor an album.
     */
    private val libraryLookup = MetadataProviderChain(
        listOf(
            YouTubeSearchProvider(),
            ITunesProvider(),
            DeezerProvider(),
            AudiusProvider(),
            GeniusMetadataProvider(),
            MusicBrainzProvider()
        )
    )

    private val lyricsChain = LyricsProviderChain(
        listOf(
            LrcLibProvider(),
            NeteaseLyricsProvider(),
            LyricsOvhProvider(),
            GeniusLyricsProvider()
        )
    )

    /**
     * Lyric-text search, kept separate from the catalogue chain.
     *
     * Only these two index the words themselves; sending a remembered line to
     * iTunes or MusicBrainz returns nothing, so mixing them in would only add
     * latency and empty rows.
     */
    private val lyricSearch = MetadataProviderChain(
        listOf(GeniusLyricSearch(), NeteaseLyricSearch())
    )

    private val shazam = ShazamClient()

    // ---- Library ------------------------------------------------------------

    var folders by mutableStateOf(settings.folders)
        private set
    var tracks by mutableStateOf<List<DesktopTrack>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    var scanned by mutableStateOf(0)
        private set
    var scanningName by mutableStateOf("")
        private set

    var query by mutableStateOf("")
    var sort by mutableStateOf(SortMode.ARTIST)
    var favourites by mutableStateOf<Set<String>>(emptySet())
        private set
    var favouritesOnly by mutableStateOf(false)

    private var listenTimes by mutableStateOf<Map<String, Long>>(emptyMap())
    private var playCounts by mutableStateOf<Map<String, Int>>(emptyMap())

    /**
     * The rows the table should show, after search, filter and sort.
     *
     * Derived rather than computed on read: the transport bar updates several
     * times a second, and without this the whole library would be filtered and
     * re-sorted on every one of those ticks.
     */
    val visibleTracks: List<DesktopTrack> get() = visibleTracksState.value

    private val visibleTracksState = derivedStateOf {
        val text = query.trim().lowercase(Locale.getDefault())
        val filtered = tracks.filter { track ->
            (!favouritesOnly || track.file.absolutePath in favourites) &&
                (text.isEmpty() ||
                    track.title.lowercase().contains(text) ||
                    track.displayArtist.lowercase().contains(text) ||
                    track.displayAlbum.lowercase().contains(text))
        }
        when (sort) {
            SortMode.ARTIST -> filtered.sortedWith(
                compareBy({ it.displayArtist.lowercase() }, { it.title.lowercase() })
            )
            SortMode.TITLE -> filtered.sortedBy { it.title.lowercase() }
            SortMode.ALBUM -> filtered.sortedWith(
                compareBy({ it.displayAlbum.lowercase() }, { it.trackNumber ?: 0 })
            )
            SortMode.DURATION -> filtered.sortedByDescending { it.durationMs }
            SortMode.PLAYS -> filtered.sortedByDescending {
                playCounts[it.file.absolutePath] ?: 0
            }
            SortMode.LISTEN_TIME -> filtered.sortedByDescending {
                listenTimes[it.file.absolutePath] ?: 0L
            }
            SortMode.ADDED -> filtered.sortedByDescending { it.file.lastModified() }
        }
    }

    fun playCountOf(track: DesktopTrack): Int = playCounts[track.file.absolutePath] ?: 0

    fun listenedMsOf(track: DesktopTrack): Long = listenTimes[track.file.absolutePath] ?: 0L

    fun addFolder(dir: File) {
        folders = (folders + dir.absolutePath).distinct()
        settings.folders = folders
        rescan()
    }

    fun removeFolder(path: String) {
        folders = folders - path
        settings.folders = folders
        rescan()
    }

    /**
     * Adds freshly downloaded files to the library straight away.
     *
     * Downloads used to appear only when the download folder happened to sit
     * inside a library folder, which by default it does not. Reading just the new
     * files also spares re-reading every tag in the library after each download.
     */
    private fun addToLibrary(files: List<File>) {
        if (files.isEmpty()) return
        scope.launch {
            val added = FolderLibrary.readFiles(files)
            if (added.isEmpty()) return@launch
            val paths = added.map { it.file.absolutePath }.toSet()
            tracks = (tracks.filterNot { it.file.absolutePath in paths } + added)
                .sortedWith(compareBy({ it.displayArtist.lowercase() }, { it.title.lowercase() }))
            refreshAggregates()
        }
    }

    fun rescan() {
        // The download folder always counts, so anything downloaded is in the
        // library whether or not that folder was ever added by hand.
        val roots = (folders + settings.downloadDir)
            .map(::File)
            .filter { it.isDirectory }
            .distinctBy { it.absoluteFile.normalize().path.lowercase() }
        if (roots.isEmpty()) {
            tracks = emptyList()
            scanning = false
            return
        }
        scope.launch {
            scanning = true
            scanned = 0
            val found = FolderLibrary.scan(roots) { count, name ->
                // Called from the IO dispatcher; only cheap value writes here.
                scanned = count
                scanningName = name
            }
            tracks = found
            scanning = false
            refreshAggregates()
        }
    }

    private suspend fun refreshAggregates() {
        val listen = io { store.listenTimeByPath() }
        val plays = io { store.playCountByPath() }
        val favs = io { store.favourites() }
        listenTimes = listen
        playCounts = plays
        favourites = favs
    }

    fun toggleFavourite(track: DesktopTrack) {
        val path = track.file.absolutePath
        val next = path !in favourites
        favourites = if (next) favourites + path else favourites - path
        scope.launch { io { store.setFavourite(path, next) } }
    }

    // ---- Playback -----------------------------------------------------------

    // Backed by explicit state objects rather than `by mutableStateOf` so the
    // setter can push straight to the audio thread: assigning is the whole API,
    // and there is no way to change one without the engine hearing about it.
    private val fxState = mutableStateOf(DesktopFxState())
    var fx: DesktopFxState
        get() = fxState.value
        set(value) {
            fxState.value = value
            engine.effects.speed = value.speed
            engine.effects.pitchSemitones = value.pitchSemitones
            engine.effects.reverb.enabled = value.reverbEnabled
            engine.effects.reverb.mix = value.reverbMix
            engine.effects.reverb.decay = value.reverbDecay
            engine.effects.equalizer.enabled = value.eqEnabled
            engine.effects.equalizer.setGains(value.eqGains)
        }

    private val volumeState = mutableStateOf(settings.volume)
    var volume: Float
        get() = volumeState.value
        set(value) {
            volumeState.value = value
            settings.volume = value
            pushVolume()
        }

    /** True while the duck hotkey has the music turned down. */
    var ducked by mutableStateOf(false)
        private set

    private val duckEnabledState = mutableStateOf(settings.duckEnabled)
    var duckEnabled: Boolean
        get() = duckEnabledState.value
        set(value) {
            duckEnabledState.value = value
            settings.duckEnabled = value
            applyDuckBinding()
        }

    private val duckKeyState = mutableStateOf(DuckKey.fromName(settings.duckKeyName))
    var duckKey: DuckKey
        get() = duckKeyState.value
        set(value) {
            duckKeyState.value = value
            settings.duckKeyName = value.name
            applyDuckBinding()
        }

    private val duckPercentState = mutableStateOf(settings.duckPercent)
    var duckPercent: Int
        get() = duckPercentState.value
        set(value) {
            duckPercentState.value = value.coerceIn(10, 100)
            settings.duckPercent = duckPercentState.value
            if (ducked) pushVolume()
        }

    var duckError by mutableStateOf<String?>(null)
        private set

    private val hotkey = GlobalHotkey()

    /** Volume actually sent to the engine, after any ducking. */
    private fun pushVolume() {
        val factor = if (ducked) (100 - duckPercent) / 100f else 1f
        engine.setVolume(volume * factor)
    }

    private fun applyDuckBinding() {
        hotkey.unbind()
        duckError = null
        if (ducked) {
            ducked = false
            pushVolume()
        }
        if (!duckEnabled) return

        hotkey.bind(duckKey.virtualKey) {
            // Runs on the hotkey thread. Snapshot state is thread-safe, and the
            // engine reads volume from a volatile, so no hop to the UI thread is
            // needed - which matters, because the whole point is that this
            // responds while a game has focus and the UI is not being drawn.
            ducked = !ducked
            pushVolume()
        }
        duckError = hotkey.lastError
    }


    val outputs: List<DesktopAudioOutput> = AudioDevices.outputs()
    var selectedOutputs by mutableStateOf(
        settings.outputs.filter { name -> outputs.any { it.name == name } }
            .ifEmpty { listOf(outputs.first().name) }
    )
        private set

    /** The list playback walks through — whatever the user is currently looking at. */
    private var queue: List<DesktopTrack> = emptyList()

    /**
     * The play currently being timed.
     *
     * The row id only exists once the insert has landed, which is a dispatch
     * later than the play itself — so it is carried in a deferred rather than a
     * field. Skipping quickly through tracks used to close the wrong row.
     */
    private class OpenPlay(val path: String) {
        val rowId = CompletableDeferred<Long>()
    }

    private var openPlay: OpenPlay? = null

    /** Which docked panel is open, if any. */
    var sidePanel by mutableStateOf<SidePanelKind?>(null)

    var accent: AccentChoice
        get() = Palette.choice
        set(value) {
            Palette.use(value)
            settings.accentName = value.name
        }

    private val backdropState = mutableStateOf(BackdropStyle.fromName(settings.backdrop))

    /** What is drawn behind the sidebar, the library and the lyrics. */
    var backdrop: BackdropStyle
        get() = backdropState.value
        set(value) {
            backdropState.value = value
            settings.backdrop = value.name
        }

    private val reactiveModeState = mutableStateOf(ReactiveMode.fromName(settings.reactiveMode))

    /** Which of Reactive's two looks is shown. */
    var reactiveMode: ReactiveMode
        get() = reactiveModeState.value
        set(value) {
            reactiveModeState.value = value
            settings.reactiveMode = value.name
        }

    /** Lyrics popped out into a window of their own. */
    var lyricsDetached by mutableStateOf(false)

    /** Stores the Custom theme and switches to it. */
    fun saveCustomTheme(colors: ThemeColors) {
        Palette.setCustom(colors)
        settings.customTheme = colors.encode()
        accent = AccentChoice.CUSTOM
    }

    /** The background effect's own colour, or null to follow the theme. */
    fun setBackdropColor(color: Color?) {
        Palette.setStars(color)
        settings.backdropColor = color?.let { ThemeColors.hex(it) }.orEmpty()
    }

    /** The lyric line being sung, or null to follow the theme. */
    fun setLyricsActiveColor(color: Color?) {
        Palette.setLyricsActive(color)
        settings.lyricsActiveColor = color?.let { ThemeColors.hex(it) }.orEmpty()
    }

    /** The other lyric lines, or null to follow the theme. */
    fun setLyricsInactiveColor(color: Color?) {
        Palette.setLyricsInactive(color)
        settings.lyricsInactiveColor = color?.let { ThemeColors.hex(it) }.orEmpty()
    }

    // ---- Wallpaper ----------------------------------------------------------

    /** The user's own background picture, scaled to screen size; null for none. */
    var wallpaper by mutableStateOf<ImageBitmap?>(null)
        private set

    private val wallpaperDimState = mutableStateOf(settings.wallpaperDim)

    /** How far the picture is darkened under everything, 0 to 0.9. */
    var wallpaperDim: Float
        get() = wallpaperDimState.value
        set(value) {
            wallpaperDimState.value = value.coerceIn(0f, 0.9f)
            settings.wallpaperDim = wallpaperDimState.value
        }

    var wallpaperNote by mutableStateOf<String?>(null)
        private set

    /** Resonate's own copy, so moving or deleting the original doesn't take the wallpaper with it. */
    private val wallpaperFile: File get() = File(AppDirs.root, "wallpaper.img")

    fun setWallpaper(file: File) {
        scope.launch {
            val image = io {
                runCatching {
                    val decoded = decodeWallpaper(file) ?: return@runCatching null
                    if (file.canonicalFile != wallpaperFile.canonicalFile) {
                        file.copyTo(wallpaperFile, overwrite = true)
                    }
                    decoded
                }.getOrNull()
            }
            if (image == null) {
                wallpaperNote = "Couldn't read that picture. JPEG, PNG, WebP and BMP work."
                return@launch
            }
            wallpaper = image
            wallpaperNote = null
            settings.hasWallpaper = true
        }
    }

    fun clearWallpaper() {
        wallpaper = null
        wallpaperNote = null
        settings.hasWallpaper = false
        runCatching { wallpaperFile.delete() }
    }

    private fun loadWallpaper() {
        if (!settings.hasWallpaper) return
        scope.launch {
            wallpaper = io { runCatching { decodeWallpaper(wallpaperFile) }.getOrNull() }
        }
    }

    /** Longest edge 2560: sharp on a 1440p window, a fraction of a 4K photo's memory. */
    private fun decodeWallpaper(file: File): ImageBitmap? =
        Thumbnails.decodeScaled(file.readBytes(), maxEdge = 2560)

    // ---- Proxy --------------------------------------------------------------

    private val proxyState = mutableStateOf(
        ProxyConfig(ProxyMode.fromName(settings.proxyMode), settings.proxyHost, settings.proxyPort)
    )

    init {
        // Before anything goes online: every request the app makes, and every
        // yt-dlp it starts, reads the route from here.
        NetworkProxy.apply(proxyState.value)
    }

    /** Applied the moment it changes, so the next connection already takes the new route. */
    var proxy: ProxyConfig
        get() = proxyState.value
        set(value) {
            proxyState.value = value
            settings.proxyMode = value.mode.name
            settings.proxyHost = value.host
            settings.proxyPort = value.port
            NetworkProxy.apply(value)
            proxyTestNote = null
        }

    var proxyTestNote by mutableStateOf<String?>(null)
        private set

    fun testProxy() {
        val tested = proxy
        proxyTestNote = "Testing…"
        scope.launch {
            val result = NetworkProxy.test()
            // A result for a setting changed since would describe the wrong route.
            if (proxy == tested) proxyTestNote = result
        }
    }

    // ---- Music folder -------------------------------------------------------

    /** Opens the folder downloads and dropped songs are saved to. */
    fun openMusicFolder() {
        val dir = File(settings.downloadDir)
        runCatching {
            dir.mkdirs()
            java.awt.Desktop.getDesktop().open(dir)
        }
    }

    // ---- Drag and drop ------------------------------------------------------

    /** True while something is being dragged over the window. */
    var dropHover by mutableStateOf(false)

    var dropNote by mutableStateOf<String?>(null)
        private set

    fun dismissDropNote() { dropNote = null }

    /**
     * Files and folders dropped on the window.
     *
     * Folders join the library where they are, the same as adding one by hand.
     * Loose songs are copied into the music folder, where downloads go, so they
     * stay in the library if the originals are moved or deleted. Songs already
     * inside the library are added as they are rather than copied again.
     */
    fun importDropped(files: List<File>) {
        val droppedFolders = files.filter { it.isDirectory }
        val songs = files.filter { it.isFile && AudioTypes.isProbablyAudio(null, it.name) }
        val ignored = files.size - droppedFolders.size - songs.size

        scope.launch {
            val music = File(settings.downloadDir)
            // Compared as lowercase text: Windows paths ignore case, File.startsWith does not.
            val roots = (folders + settings.downloadDir)
                .map { File(it).absoluteFile.normalize().path.trimEnd('\\', '/').lowercase() + File.separator }
            val placed = io {
                songs.mapNotNull { song ->
                    val source = song.absoluteFile.normalize()
                    if (roots.any { source.path.lowercase().startsWith(it) }) {
                        source
                    } else {
                        runCatching {
                            music.mkdirs()
                            source.copyTo(freeName(music, source.name))
                        }.getOrNull()
                    }
                }
            }

            if (droppedFolders.isNotEmpty()) {
                folders = (folders + droppedFolders.map { it.absolutePath }).distinct()
                settings.folders = folders
                // Covers the copied songs too: the music folder is always scanned.
                rescan()
            } else {
                addToLibrary(placed)
            }

            dropNote = buildList {
                if (placed.isNotEmpty()) add("added ${placed.size} song${if (placed.size == 1) "" else "s"}")
                if (droppedFolders.isNotEmpty()) {
                    add("added ${droppedFolders.size} folder${if (droppedFolders.size == 1) "" else "s"}")
                }
                if (songs.size > placed.size) add("${songs.size - placed.size} couldn't be copied")
                if (ignored > 0) add("skipped $ignored that aren't audio")
            }.joinToString(", ").replaceFirstChar { it.uppercase() }.ifBlank { "Nothing there to add." }
        }
    }

    /** A link dropped on the window starts downloading it. */
    fun importDroppedText(text: String): Boolean {
        val link = text.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        if (!link.startsWith("http", ignoreCase = true)) return false
        startDownload(link)
        dropNote = if (tools.ready) {
            "Downloading that link. It's in the Download list."
        } else {
            "Install yt-dlp first, on the Download page, then drop the link again."
        }
        return true
    }

    /** [name] in [dir], or "name (2)" and so on if that is taken. */
    private fun freeName(dir: File, name: String): File {
        val stem = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "")
        var candidate = File(dir, name)
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, if (extension.isEmpty()) "$stem ($n)" else "$stem ($n).$extension")
            n++
        }
        return candidate
    }

    fun togglePanel(kind: SidePanelKind) {
        // The lyrics button brings popped-out lyrics back into the panel.
        if (kind == SidePanelKind.LYRICS && lyricsDetached) {
            lyricsDetached = false
            sidePanel = kind
            return
        }
        sidePanel = if (sidePanel == kind) null else kind
    }

    fun toggleOutput(output: DesktopAudioOutput) {
        selectedOutputs = if (output.name in selectedOutputs) {
            // Never end up with nothing selected — silence with no way back.
            (selectedOutputs - output.name).ifEmpty { listOf(outputs.first().name) }
        } else {
            selectedOutputs + output.name
        }
        settings.outputs = selectedOutputs
        engine.setOutputs(outputs.filter { it.name in selectedOutputs })
    }

    fun applyOutputs() {
        engine.setOutputs(outputs.filter { it.name in selectedOutputs })
    }

    fun play(track: DesktopTrack, from: List<DesktopTrack> = visibleTracks) {
        queue = from
        closeListenEvent()
        engine.play(track)
        openListenEvent(track)
        loadLyricsFor(track)
    }

    fun togglePlay() = engine.togglePlay()

    fun next() {
        val index = queue.indexOfFirst { it.file == engine.status.value.track?.file }
        if (index >= 0 && index < queue.lastIndex) play(queue[index + 1], queue)
    }

    fun previous() {
        val index = queue.indexOfFirst { it.file == engine.status.value.track?.file }
        if (index > 0) play(queue[index - 1], queue)
    }

    fun seekFraction(fraction: Float) {
        val duration = engine.status.value.durationMs
        if (duration > 0) engine.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    /** Called when a track finishes on its own. */
    fun advance() {
        closeListenEvent()
        next()
    }

    // ---- Listening history --------------------------------------------------

    private fun openListenEvent(track: DesktopTrack) {
        val open = OpenPlay(track.file.absolutePath)
        openPlay = open
        val group = weather?.condition?.name
        scope.launch { open.rowId.complete(io { store.startPlay(open.path, group) }) }
    }

    /** Writes how much of the outgoing track was actually heard. */
    fun closeListenEvent() {
        val open = openPlay ?: return
        openPlay = null
        val listened = engine.status.value.positionMs
        scope.launch {
            val id = open.rowId.await()
            if (id < 0) return@launch
            io { store.updateListened(id, listened) }
            if (listened >= DesktopStore.QUALIFYING_MS) refreshAggregates()
        }
    }

    // ---- Lyrics -------------------------------------------------------------

    var lyricsPlain by mutableStateOf<String?>(null)
        private set
    var lyricsSynced by mutableStateOf<String?>(null)
        private set
    var lyricsSource by mutableStateOf<String?>(null)
        private set
    var lyricsLoading by mutableStateOf(false)
        private set
    var lyricsNote by mutableStateOf<String?>(null)
        private set

    private fun loadLyricsFor(track: DesktopTrack) {
        lyricsPlain = null
        lyricsSynced = null
        lyricsSource = null
        lyricsNote = null
        scope.launch {
            val stored = io { store.lyricsFor(track.file.absolutePath) }
            if (stored != null) {
                lyricsPlain = stored.plain
                lyricsSynced = stored.synced
                lyricsSource = stored.source
            }
        }
    }

    fun fetchLyrics(track: DesktopTrack) {
        lyricsLoading = true
        lyricsNote = null
        scope.launch {
            val (result, provider) = lyricsChain.fetch(
                track.title,
                track.artist,
                track.album,
                track.durationMs
            )
            when (result) {
                is LyricsFetch.Found -> {
                    lyricsPlain = result.plain
                    lyricsSynced = result.synced
                    lyricsSource = provider
                    io {
                        store.saveLyrics(
                            track.file.absolutePath,
                            result.plain,
                            result.synced,
                            provider ?: "unknown"
                        )
                    }
                }
                LyricsFetch.Instrumental -> lyricsNote =
                    "Every service reports this as an instrumental."
                is LyricsFetch.Error -> lyricsNote = result.message
                LyricsFetch.NotFound -> lyricsNote =
                    "None of the four services had lyrics for this. You can paste them in yourself."
            }
            io { store.mark(track.file.absolutePath, "lyricsCheckedAt") }
            lyricsLoading = false
        }
    }

    fun saveManualLyrics(track: DesktopTrack, text: String) {
        val trimmed = text.trim()
        scope.launch {
            if (trimmed.isEmpty()) {
                io { store.deleteLyrics(track.file.absolutePath) }
                lyricsPlain = null
                lyricsSynced = null
                lyricsSource = null
            } else {
                // A pasted LRC is kept as synced so it still highlights in time.
                val looksTimed = Regex("""\[\d{1,3}:\d{1,2}""").containsMatchIn(trimmed)
                val plain = if (looksTimed) {
                    trimmed.lineSequence()
                        .map { it.replace(Regex("""\[[^]]*]"""), "").trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString("\n")
                } else {
                    trimmed
                }
                io {
                    store.saveLyrics(
                        track.file.absolutePath,
                        plain,
                        if (looksTimed) trimmed else null,
                        "Added by hand"
                    )
                    store.mark(track.file.absolutePath, "lyricsCheckedAt")
                }
                lyricsPlain = plain
                lyricsSynced = if (looksTimed) trimmed else null
                lyricsSource = "Added by hand"
            }
            lyricsNote = null
        }
    }

    // ---- Identify -----------------------------------------------------------

    var identifyQuery by mutableStateOf("")
    var identifyResults by mutableStateOf<List<MusicMatch>>(emptyList())
        private set
    var identifyBusy by mutableStateOf(false)
        private set
    var identifyStatus by mutableStateOf<String?>(null)
        private set
    var identifyTarget by mutableStateOf<DesktopTrack?>(null)
    var identifyMode by mutableStateOf(IdentifyMode.NAME)

    /** Artist and title asked for separately, so each can be scored on its own field. */
    var identifyArtist by mutableStateOf("")
    var identifyTitle by mutableStateOf("")

    // ---- YouTube search -----------------------------------------------------
    //
    // A separate list from identifyResults, because a YouTube hit is a
    // different kind of thing: a video with a channel, a view count and an
    // upload date, not a catalogue's claim about what a song is. Merging them
    // would mean deciding that an uploader is an artist, which is how wrong
    // metadata ends up written into tags.
    //
    // Piped is asked first because it answers in well under a second and
    // carries the upload date; the bundled yt-dlp answers in about three and
    // always works. Whichever replies first wins - see YouTubeSearch.

    private val youtubeBackend = DesktopYouTubeBackend()

    private val youtube = YouTubeSearch(
        listOf(PipedYouTubeBackend(), youtubeBackend)
    )

    /**
     * Resolves song names to links for the downloader. yt-dlp is never handed a
     * search: see YouTubeLinkFinder for what "first result" turned out to mean.
     */
    private val linkFinder = YouTubeLinkFinder(youtube)

    /** A `ytsearch1:` prefix left over from before, accepted and ignored. */
    private val searchPrefix = Regex("""^ytsearch\d*:""", RegexOption.IGNORE_CASE)

    val preview = PreviewPlayer { ToolPaths.ffmpeg }

    var youtubeResults by mutableStateOf<List<YouTubeVideo>>(emptyList())
        private set
    var youtubeBusy by mutableStateOf(false)
        private set
    var youtubeStatus by mutableStateOf<String?>(null)
        private set

    fun searchYouTube(text: String = youtubeQuery) {
        val query = text.trim()
        if (query.isBlank() || youtubeBusy) return

        youtubeBusy = true
        youtubeStatus = "Searching YouTube…"
        scope.launch {
            val result = youtube.search(query, limit = 25)
            youtubeResults = result.videos
            youtubeStatus = when {
                result.videos.isEmpty() ->
                    "Nothing came back. yt-dlp may need updating — one click in " +
                        "the Download tab."
                result.skipped.isEmpty() ->
                    "${result.videos.size} results via ${result.via}"
                else ->
                    // Named rather than hidden: a slow search is worth
                    // explaining, and a dead Piped is the usual reason.
                    "${result.videos.size} results via ${result.via} — " +
                        "${result.skipped.joinToString(", ")} did not answer"
            }
            youtubeBusy = false
        }
    }

    var youtubeQuery by mutableStateOf("")

    /** Plays a result without downloading it. Tapping the same row stops it. */
    fun previewYouTube(video: YouTubeVideo) {
        // The main player keeps its place; two things playing at once is never
        // what a preview tap meant.
        if (engine.status.value.playing) engine.togglePlay()
        preview.toggle(video.id) { id -> youtubeBackend.audioStreamUrl(id, downloadQuality) }
    }

    fun stopPreview() = preview.stop()

    /** Hands the video to the existing downloader, as an mp3 at the set quality. */
    fun downloadYouTube(video: YouTubeVideo) {
        startDownload(video.watchUrl)
    }

    fun clearYouTube() {
        youtubeResults = emptyList()
        youtubeStatus = null
        preview.stop()
    }

    /** Set when the last attempt failed only because ffmpeg is missing. */
    var identifyNeedsFfmpeg by mutableStateOf(false)
        private set

    /**
     * Finds a song from a line of its lyrics.
     *
     * The reverse of the lyrics chain, and a genuinely different index: Genius
     * and NetEase both search lyric text, where the seven catalogues only match
     * titles and artists and would return nothing at all for a remembered line.
     */
    fun searchByLyrics(text: String = identifyQuery) {
        if (text.isBlank()) return
        identifyBusy = true
        identifyNeedsFfmpeg = false
        identifyStatus = "Searching lyrics..."
        scope.launch {
            val results = lyricSearch.searchAll(text, limitPer = 8)
            identifyResults = results
            identifyStatus = if (results.isEmpty()) {
                "No song matched those words. Try a longer or more distinctive line."
            } else {
                "${results.size} songs contain those words"
            }
            identifyBusy = false
        }
    }

    /**
     * Identifies any audio or video file on disk.
     *
     * Video included: ffmpeg pulls twelve seconds of mono out of the middle and
     * the existing fingerprint path takes it from there.
     */
    fun identifyMediaFile(file: File) {
        identifyTarget = null
        identifyBusy = true
        identifyNeedsFfmpeg = false
        identifyStatus = "Reading ${file.name}..."
        scope.launch { fingerprint(file, file.nameWithoutExtension, markPath = null) }
    }

    /**
     * Downloads the audio behind a link and identifies it.
     *
     * The same path a TikTok or Instagram link takes in the download window,
     * stopping at a temporary file rather than the library - yt-dlp handles well
     * over a thousand sites, so this is not limited to the obvious three.
     */
    fun identifyLink(url: String = identifyQuery) {
        val target = url.trim()
        if (target.isBlank()) return
        if (!tools.ready) {
            identifyStatus = "yt-dlp is missing from this installation - try reinstalling."
            return
        }
        identifyTarget = null
        identifyBusy = true
        identifyNeedsFfmpeg = false
        identifyStatus = "Fetching audio from that link..."

        scope.launch {
            val staging = io {
                File(AppDirs.tools, "identify-${System.currentTimeMillis()}").apply { mkdirs() }
            }
            try {
                val resolved = io { LinkResolver.resolve(target) }
                val fetchTarget = when (resolved) {
                    is ResolvedLink.Direct -> resolved.url
                    is ResolvedLink.Search -> {
                        identifyStatus = "Finding that track on YouTube..."
                        when (val found = linkFinder.find(YouTubeLinkFinder.Wanted(title = resolved.query))) {
                            is YouTubeLinkFinder.Outcome.Found -> found.pick.video.watchUrl
                            is YouTubeLinkFinder.Outcome.NothingSuitable -> {
                                identifyStatus = found.message
                                identifyBusy = false
                                return@launch
                            }
                        }
                    }
                    is ResolvedLink.Unsupported -> {
                        identifyStatus = resolved.reason
                        identifyBusy = false
                        return@launch
                    }
                }

                val result = YtDlp.download(
                    target = fetchTarget,
                    destination = staging,
                    // No conversion: the fingerprinter only needs samples, and
                    // ffmpeg may not be installed.
                    toMp3 = false,
                    embedThumbnail = false,
                    wholePlaylist = false,
                    onProgress = { progress ->
                        progress.percent?.let {
                            identifyStatus = "Fetching audio... ${(it * 100).toInt()}%"
                        }
                    }
                )

                val file = result.getOrNull()?.firstOrNull()
                if (file == null) {
                    identifyStatus = result.exceptionOrNull()?.message
                        ?: "Couldn't get any audio from that link."
                    identifyBusy = false
                    return@launch
                }
                fingerprint(file, file.nameWithoutExtension, markPath = null)
            } finally {
                io { staging.deleteRecursively() }
            }
        }
    }

    /** Shared tail of every fingerprint route. */
    private suspend fun fingerprint(file: File, fallbackName: String, markPath: String?) {
        when (val outcome = MediaAudio.sample(file)) {
            SampleOutcome.NeedsFfmpeg -> {
                identifyNeedsFfmpeg = true
                identifyStatus = "That format needs ffmpeg - one click in the Download tab."
                identifyBusy = false
            }

            is SampleOutcome.Failed -> {
                identifyStatus = outcome.message
                identifyBusy = false
            }

            is SampleOutcome.Ok -> {
                identifyStatus = "Matching against Shazam..."
                when (val result = shazam.recognize(outcome.samples)) {
                    is RecognitionResult.Found -> {
                        identifyResults = result.matches
                        val best = result.matches.firstOrNull()
                        identifyStatus = "Identified: ${best?.display ?: fallbackName}"
                        identifyQuery = best?.display ?: fallbackName
                    }
                    RecognitionResult.NoMatch -> {
                        identifyStatus = "No fingerprint match. Searching by name instead..."
                        identifyMode = IdentifyMode.NAME
                        identifyQuery = fallbackName
                        identifyBusy = false
                        markPath?.let { io { store.mark(it, "fingerprintedAt") } }
                        searchCatalogues(fallbackName)
                        return
                    }
                    is RecognitionResult.Error -> identifyStatus = result.message
                }
                markPath?.let { io { store.mark(it, "fingerprintedAt") } }
                identifyBusy = false
            }
        }
    }

    /**
     * Searches on the split artist/title fields.
     *
     * Splitting them is not cosmetic: knowing which half is the performer lets
     * the ranker check each against the field it belongs to, which drops the
     * covers and karaoke versions a single box scores almost as highly as the
     * real recording.
     */
    fun searchByFields() {
        val artist = identifyArtist.trim()
        val title = identifyTitle.trim()
        if (artist.isBlank() && title.isBlank()) return

        identifyBusy = true
        identifyNeedsFfmpeg = false
        identifyStatus = "Searching seven catalogues..."
        scope.launch {
            // Providers take a single string, so the halves are joined for the
            // lookup and separated again only for scoring.
            val combined = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
            val found = catalogue.searchAll(combined, limitPer = 8)
            val results = MatchRanker.rankSplit(artist, title, found)
            identifyResults = results
            identifyQuery = combined

            val dropped = found.size - results.size
            val services = results.map { it.provider }.distinct().size
            identifyStatus = when {
                results.isEmpty() ->
                    "Nothing came back. Try fewer words, or just the artist."
                dropped > 0 ->
                    "${results.size} matches from $services services " +
                        "($dropped unrelated hidden)"
                else ->
                    "${results.size} matches from $services services"
            }
            identifyBusy = false
        }
    }

    fun searchCatalogues(text: String = identifyQuery) {
        if (text.isBlank()) return
        identifyBusy = true
        identifyNeedsFfmpeg = false
        identifyStatus = "Searching seven catalogues…"
        scope.launch {
            val found = catalogue.searchAll(text, limitPer = 8)
            // Providers each answer loosely and none of them can see the others,
            // so relevance is decided here rather than trusting whatever order
            // they came back in.
            val results = MatchRanker.rank(text, found)
            identifyResults = results
            val dropped = found.size - results.size
            identifyStatus = when {
                results.isEmpty() -> "Nothing came back. Try fewer words, or just the artist."
                dropped > 0 ->
                    "${results.size} matches from " +
                        "${results.map { it.provider }.distinct().size} services " +
                        "($dropped unrelated results hidden)"
                else ->
                    "${results.size} matches from " +
                        "${results.map { it.provider }.distinct().size} services"
            }
            identifyBusy = false
        }
    }

    /** Fingerprints a track in the library and asks Shazam what it is. */
    fun identifyFile(track: DesktopTrack) {
        identifyTarget = track
        identifyBusy = true
        identifyNeedsFfmpeg = false
        identifyStatus = "Fingerprinting ${track.title}..."
        scope.launch {
            fingerprint(track.file, track.title, markPath = track.file.absolutePath)
        }
    }

    /** Writes a chosen match onto a track: tags, and art if the match has any. */
    fun applyMatch(track: DesktopTrack, match: MusicMatch) {
        identifyBusy = true
        scope.launch {
            if (writeTags) {
                io {
                    TagWriter.write(
                        track.file,
                        match.title,
                        match.artist,
                        match.album,
                        match.releaseYear
                    )
                }
            }
            match.artworkUrl?.let { Covers.fetchAndStore(track, it, writeTags) }
            io { store.mark(track.file.absolutePath, "identifiedAt") }
            identifyStatus = "Applied \"${match.display}\" to ${track.file.name}."
            identifyBusy = false
            rescan()
        }
    }

    /** Mirrored into Compose state so toggling it redraws the switch. */
    private val writeTagsState = mutableStateOf(settings.writeTagsOnIdentify)
    var writeTags: Boolean
        get() = writeTagsState.value
        set(value) {
            writeTagsState.value = value
            settings.writeTagsOnIdentify = value
        }

    // ---- Editing a track by hand --------------------------------------------

    /** The track whose details are open for editing, if any. */
    var editTarget by mutableStateOf<DesktopTrack?>(null)
    var editNote by mutableStateOf<String?>(null)
        private set

    /**
     * Writes edited details into the file's tags.
     *
     * Manual editing exists because automatic identification is right most of
     * the time and not all of it — a live bootleg, a track nobody has catalogued,
     * a name in a script the services transliterate differently. Fields left
     * blank are cleared rather than ignored, because "remove the wrong album
     * name" has to be expressible.
     */
    fun saveTrackDetails(
        track: DesktopTrack,
        title: String,
        artist: String,
        album: String,
        year: String
    ) {
        scope.launch {
            val result = io {
                TagWriter.write(
                    file = track.file,
                    title = title.trim().ifBlank { track.file.nameWithoutExtension },
                    artist = artist.trim(),
                    album = album.trim(),
                    year = year.trim().toIntOrNull(),
                    clearBlanks = true
                )
            }
            editNote = result.fold(
                onSuccess = { "Saved." },
                onFailure = { "Couldn't write those tags: ${it.message}" }
            )
            if (result.isSuccess) {
                editTarget = null
                rescan()
            }
        }
    }

    fun dismissEdit() {
        editTarget = null
        editNote = null
    }

    // ---- Bulk tools ---------------------------------------------------------

    var bulk by mutableStateOf(BulkJob())
        private set
    private var bulkJob: Job? = null

    private enum class BulkOutcome { UPDATED, ALREADY_HAD, NOTHING_FOUND }

    private fun Boolean.asOutcome() = if (this) BulkOutcome.UPDATED else BulkOutcome.NOTHING_FOUND

    fun cancelBulk() {
        bulkJob?.cancel()
        bulkJob = null
        bulk = bulk.copy(running = false, finishedNote = "Stopped.")
    }

    /**
     * @param redo when false, tracks already processed by this tool are skipped.
     *   Skips are counted and reported rather than silently dropped, so it is
     *   obvious why a run of 400 tracks finished in two seconds.
     */
    fun runBulk(kind: BulkKind, redo: Boolean) {
        if (bulk.running) return
        val column = kind.markColumn
        bulkJob = scope.launch {
            val already = if (redo) emptySet() else io { store.markedPaths(column) }
            val queue = tracks.filter { redo || it.file.absolutePath !in already }
            val skipped = tracks.size - queue.size

            bulk = BulkJob(
                label = kind.label,
                running = true,
                total = queue.size,
                skipped = skipped
            )

            var done = 0
            var updated = 0
            var alreadyHad = 0
            var failed = 0
            val inFlight = mutableListOf<String>()
            val work = Channel<DesktopTrack>(Channel.UNLIMITED)
            queue.forEach { work.trySend(it) }
            work.close()

            // Lookups mostly wait on the network, so several songs go at once;
            // identifying decodes audio, so it stays one at a time. Workers share
            // the UI thread between suspensions, so the counters need no locking.
            val workers = if (kind == BulkKind.IDENTIFY) 1 else SONGS_AT_ONCE
            coroutineScope {
                repeat(workers) {
                    launch {
                        for (track in work) {
                            inFlight += track.title
                            bulk = bulk.copy(current = inFlight.joinToString("  ·  "))
                            val outcome = try {
                                when (kind) {
                                    BulkKind.COVERS -> bulkCover(track)
                                    BulkKind.TAGS -> bulkTags(track).asOutcome()
                                    BulkKind.LYRICS -> bulkLyrics(track).asOutcome()
                                    BulkKind.IDENTIFY -> bulkIdentify(track).asOutcome()
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                BulkOutcome.NOTHING_FOUND
                            }
                            when (outcome) {
                                BulkOutcome.UPDATED -> updated++
                                BulkOutcome.ALREADY_HAD -> alreadyHad++
                                BulkOutcome.NOTHING_FOUND -> failed++
                            }
                            io { store.mark(track.file.absolutePath, column) }
                            done++
                            inFlight -= track.title
                            bulk = bulk.copy(
                                done = done,
                                updated = updated,
                                alreadyHad = alreadyHad,
                                failed = failed,
                                current = inFlight.joinToString("  ·  ")
                            )
                        }
                    }
                }
            }

            bulk = bulk.copy(
                running = false,
                done = queue.size,
                current = "",
                finishedNote = buildString {
                    append("$updated updated")
                    if (alreadyHad > 0) append(", $alreadyHad already had art")
                    if (failed > 0) append(", $failed with nothing found")
                    if (skipped > 0) append(", $skipped skipped as already done")
                    append(".")
                }
            )
            bulkJob = null
            // Fetched covers are already in place and on screen, so only the tools
            // that rewrite tags need the library read again.
            if (kind != BulkKind.COVERS) rescan()
        }
    }

    private suspend fun bulkCover(track: DesktopTrack): BulkOutcome {
        if (io { Covers.hasLocalArt(track) }) return BulkOutcome.ALREADY_HAD
        val stored = libraryLookup.findArtwork(track.searchQuery) { url ->
            Covers.fetchAndStore(track, url, writeTags).takeIf { it }
        }
        return if (stored != null) BulkOutcome.UPDATED else BulkOutcome.NOTHING_FOUND
    }

    private suspend fun bulkTags(track: DesktopTrack): Boolean {
        val details = libraryLookup.findDetails(track.artist, track.title, giveUpMs = TAGS_GIVE_UP_MS)
            ?: return false
        return io {
            TagWriter.write(track.file, details.title, details.artist, details.album, details.year)
                .isSuccess
        }
    }

    private suspend fun bulkLyrics(track: DesktopTrack): Boolean {
        val (result, provider) = lyricsChain.fetch(
            track.title, track.artist, track.album, track.durationMs
        )
        if (result !is LyricsFetch.Found) return false
        io {
            store.saveLyrics(
                track.file.absolutePath, result.plain, result.synced, provider ?: "unknown"
            )
        }
        return true
    }

    private suspend fun bulkIdentify(track: DesktopTrack): Boolean {
        // Through MediaAudio rather than the plain sampler, so a bulk run also
        // covers m4a and video files sitting in the library.
        val outcome = MediaAudio.sample(track.file, track.durationMs)
        val samples = (outcome as? SampleOutcome.Ok)?.samples ?: return false
        val result = shazam.recognize(samples)
        val match = (result as? RecognitionResult.Found)?.matches?.firstOrNull() ?: return false
        if (writeTags) {
            io {
                TagWriter.write(
                    track.file, match.title, match.artist, match.album, match.releaseYear
                )
            }
        }
        match.artworkUrl?.let { Covers.fetchAndStore(track, it, writeTags) }
        return true
    }

    // ---- Playlists ----------------------------------------------------------

    var playlists by mutableStateOf<List<StoredPlaylist>>(emptyList())
        private set
    var openPlaylist by mutableStateOf<StoredPlaylist?>(null)
    var openPlaylistTracks by mutableStateOf<List<DesktopTrack>>(emptyList())
        private set

    fun refreshPlaylists() {
        scope.launch { playlists = io { store.playlists() } }
    }

    fun createPlaylist(name: String, initial: List<DesktopTrack> = emptyList()) {
        if (name.isBlank()) return
        scope.launch {
            val id = io { store.createPlaylist(name.trim()) }
            if (initial.isNotEmpty()) {
                io { store.addToPlaylist(id, initial.map { it.file.absolutePath }) }
            }
            playlists = io { store.playlists() }
        }
    }

    fun renamePlaylist(playlist: StoredPlaylist, name: String) {
        if (name.isBlank()) return
        scope.launch {
            io { store.renamePlaylist(playlist.id, name.trim()) }
            playlists = io { store.playlists() }
            if (openPlaylist?.id == playlist.id) openPlaylist = playlist.copy(name = name.trim())
        }
    }

    fun deletePlaylist(playlist: StoredPlaylist) {
        scope.launch {
            io { store.deletePlaylist(playlist.id) }
            if (openPlaylist?.id == playlist.id) openPlaylist = null
            playlists = io { store.playlists() }
        }
    }

    fun addToPlaylist(playlist: StoredPlaylist, items: List<DesktopTrack>) {
        scope.launch {
            io { store.addToPlaylist(playlist.id, items.map { it.file.absolutePath }) }
            playlists = io { store.playlists() }
            if (openPlaylist?.id == playlist.id) show(playlist)
        }
    }

    fun removeFromPlaylist(playlist: StoredPlaylist, track: DesktopTrack) {
        scope.launch {
            io { store.removeFromPlaylist(playlist.id, track.file.absolutePath) }
            playlists = io { store.playlists() }
            if (openPlaylist?.id == playlist.id) show(playlist)
        }
    }

    fun show(playlist: StoredPlaylist?) {
        openPlaylist = playlist
        if (playlist == null) {
            openPlaylistTracks = emptyList()
            return
        }
        scope.launch {
            val paths = io { store.playlistPaths(playlist.id) }
            val byPath = tracks.associateBy { it.file.absolutePath }
            openPlaylistTracks = paths.mapNotNull { byPath[it] }
        }
    }

    // ---- Sharing playlists ---------------------------------------------------

    var importResult by mutableStateOf<ImportResult<DesktopTrack>?>(null)
        private set
    var playlistNote by mutableStateOf<String?>(null)
        private set

    /** Writes a playlist out as text anyone can read, edit or send on. */
    fun exportPlaylist(playlist: StoredPlaylist, target: File) {
        scope.launch {
            val paths = io { store.playlistPaths(playlist.id) }
            val byPath = tracks.associateBy { it.file.absolutePath }
            val entries = paths.mapNotNull { path ->
                byPath[path]?.let {
                    PlaylistEntry(it.artist, it.title, it.durationMs)
                }
            }
            io { target.writeText(PlaylistFile.export(playlist.name, entries)) }
            playlistNote = "Exported ${entries.size} tracks to ${target.name}."
        }
    }

    /**
     * Reads a shared playlist and matches it against this library.
     *
     * Nothing is downloaded: the file names songs, and what is already here gets
     * linked up. Whatever is missing is reported so it can be found separately.
     */
    fun importPlaylist(source: File) {
        scope.launch {
            val parsed = io { PlaylistFile.parse(source.readText(), source.nameWithoutExtension) }
            if (parsed.entries.isEmpty()) {
                playlistNote = "That file had no tracks in it."
                return@launch
            }
            importResult = PlaylistFile.matchAgainst(
                playlist = parsed,
                library = tracks,
                artistOf = { it.artist },
                titleOf = { it.title },
                durationOf = { it.durationMs }
            )
            playlistNote = null
        }
    }

    /** Creates the playlist from a reviewed import. */
    fun confirmImport(result: ImportResult<DesktopTrack>) {
        scope.launch {
            val id = io { store.createPlaylist(result.name) }
            io { store.addToPlaylist(id, result.matched.map { it.second.file.absolutePath }) }
            playlists = io { store.playlists() }
            importResult = null
            playlistNote = buildString {
                append("Added ${result.matched.size} tracks as \"${result.name}\"")
                if (result.missing.isNotEmpty()) {
                    append("; ${result.missing.size} not in your library")
                }
                append(".")
            }
        }
    }

    fun dismissImport() {
        importResult = null
        playlistNote = null
    }

    // ---- Moods (weather) ----------------------------------------------------

    var weather by mutableStateOf<WeatherSnapshot?>(null)
        private set
    var weatherPlace by mutableStateOf(settings.weatherPlace)
        private set
    var weatherNote by mutableStateOf<String?>(null)
        private set
    var moodCondition by mutableStateOf<WeatherCondition?>(null)
    var moodTracks by mutableStateOf<List<Pair<DesktopTrack, Affinity>>>(emptyList())
        private set
    var moodHistorySize by mutableStateOf(0)
        private set
    var placeResults by mutableStateOf<List<GeoPlace>>(emptyList())
        private set

    fun refreshWeather(force: Boolean = false) {
        scope.launch {
            var latitude = settings.weatherLatitude
            var longitude = settings.weatherLongitude
            if (latitude.isNaN() || longitude.isNaN() || force && weatherPlace.isBlank()) {
                weatherNote = "Working out where you are from your IP address…"
                val place = OpenMeteo.locateByIp()
                if (place == null) {
                    weatherNote = "Couldn't work out your location. Set a city in Settings."
                    return@launch
                }
                latitude = place.latitude
                longitude = place.longitude
                settings.weatherLatitude = latitude
                settings.weatherLongitude = longitude
                if (settings.weatherPlace.isBlank()) {
                    settings.weatherPlace = place.display
                    weatherPlace = place.display
                }
            }
            val snapshot = OpenMeteo.current(latitude, longitude)
            if (snapshot == null) {
                weatherNote = "Open-Meteo didn't answer. Try again shortly."
            } else {
                weather = snapshot
                weatherNote = null
                if (moodCondition == null) moodCondition = snapshot.condition
                loadMood(moodCondition ?: snapshot.condition)
            }
        }
    }

    fun searchPlaces(text: String) {
        scope.launch { placeResults = OpenMeteo.geocode(text) }
    }

    fun usePlace(place: GeoPlace) {
        settings.weatherPlace = place.display
        settings.weatherLatitude = place.latitude
        settings.weatherLongitude = place.longitude
        weatherPlace = place.display
        placeResults = emptyList()
        refreshWeather()
    }

    fun useIpLocation() {
        settings.weatherPlace = ""
        settings.weatherLatitude = Double.NaN
        settings.weatherLongitude = Double.NaN
        weatherPlace = ""
        refreshWeather(force = true)
    }

    fun loadMood(condition: WeatherCondition) {
        moodCondition = condition
        scope.launch {
            val inCondition = io { store.playsPerTrackInCondition(condition.name) }
            val overall = io { store.playsPerTrackWithWeather() }
            val totalInCondition = io { store.totalPlaysInCondition(condition.name) }
            val totalWithWeather = io { store.totalPlaysWithWeather() }
            moodHistorySize = totalWithWeather

            val scored = WeatherAffinity.score(
                inCondition, overall, totalInCondition, totalWithWeather
            )
            // Scoring works in hashed ids; map them back to real files.
            val byKey = tracks.associateBy { DesktopStore.pathKey(it.file.absolutePath) }
            moodTracks = scored.mapNotNull { affinity ->
                byKey[affinity.trackId]?.let { it to affinity }
            }
        }
    }

    // ---- Stats --------------------------------------------------------------

    var statsTotalMs by mutableStateOf(0L)
        private set
    var statsPlays by mutableStateOf(0)
        private set
    var statsTracks by mutableStateOf(0)
        private set
    var statsTop by mutableStateOf<List<Pair<DesktopTrack, ListenRow>>>(emptyList())
        private set
    var statsByHour by mutableStateOf<Map<Int, Int>>(emptyMap())
        private set
    var statsByWeather by mutableStateOf<Map<String, Int>>(emptyMap())
        private set

    fun refreshStats() {
        scope.launch {
            statsTotalMs = io { store.totalListenedMs() }
            statsPlays = io { store.playCount() }
            statsTracks = io { store.distinctTracks() }
            statsByHour = io { store.playsByHour() }
            statsByWeather = io { store.playsByWeather() }
            val rows = io { store.topByListenTime(12) }
            val byPath = tracks.associateBy { it.file.absolutePath }
            statsTop = rows.mapNotNull { row -> byPath[row.path]?.let { it to row } }
        }
    }

    // ---- Downloads ----------------------------------------------------------

    var tools by mutableStateOf(ToolStatus())
        private set
    var toolNote by mutableStateOf<String?>(null)
        private set
    var toolProgress by mutableStateOf<Float?>(null)
        private set
    var downloadUrl by mutableStateOf("")
    var downloadToMp3 by mutableStateOf(true)
    var downloadEmbedArt by mutableStateOf(true)
    var downloadPlaylist by mutableStateOf(false)

    private val qualityState = mutableStateOf(
        DownloadQuality.fromName(settings.downloadQualityName)
    )
    var downloadQuality: DownloadQuality
        get() = qualityState.value
        set(value) {
            qualityState.value = value
            settings.downloadQualityName = value.name
        }
    /** Finished downloads the Download page has already shown. */
    private var acknowledgedDownloads by mutableStateOf<Set<Long>>(emptySet())

    /** Downloads that finished since the Download page was last looked at. */
    val unseenFinishedDownloads: Int
        get() = downloads.count { it.done && !it.failed && it.id !in acknowledgedDownloads }

    fun acknowledgeDownloads() {
        val finished = downloads.filter { it.done }.map { it.id }.toSet()
        // Written only on change: this runs from an effect keyed on the list, and
        // an unconditional write would keep re-triggering it.
        if (!acknowledgedDownloads.containsAll(finished)) {
            acknowledgedDownloads = acknowledgedDownloads + finished
        }
    }

    var downloads by mutableStateOf<List<DownloadEntry>>(emptyList())
        private set
    var downloadLog by mutableStateOf<List<String>>(emptyList())
        private set

    fun refreshTools() {
        scope.launch {
            tools = io { YtDlp.status() }
            if (tools.ytDlp) tools = tools.copy(ytDlpVersion = YtDlp.version())
        }
    }

    fun updateYtDlp() {
        scope.launch {
            toolNote = "Updating yt-dlp…"
            val result = YtDlp.update { line -> downloadLog = (downloadLog + line).takeLast(200) }
            toolNote = result.fold(
                onSuccess = { "yt-dlp updated." },
                onFailure = { "Update failed: ${it.message}" }
            )
            refreshTools()
        }
    }

    /** Queues [rawUrl], resolving Spotify links to a searchable name first. */
    fun startDownload(rawUrl: String = downloadUrl) {
        val url = rawUrl.trim()
        if (url.isBlank()) return
        if (!tools.ready) {
            toolNote = "Install yt-dlp first — one click, about 12 MB."
            return
        }

        val entry = DownloadEntry(
            id = System.nanoTime(),
            target = url,
            display = url
        )
        downloads = downloads + entry
        downloadUrl = ""

        scope.launch {
            if (isSpotify(url)) {
                downloadWithSpotdl(entry.id, url)
                return@launch
            }

            // A song name rather than a link: found on YouTube first, and the
            // link that was found is what yt-dlp is given.
            val target = if (!url.startsWith("http", ignoreCase = true)) {
                val text = url.replace(searchPrefix, "").trim()
                update(entry.id) { it.status = "Finding it on YouTube…" }
                findYouTubeLink(entry.id, YouTubeLinkFinder.Wanted(title = text))
                    ?: return@launch
            } else when (val resolved = io { LinkResolver.resolve(url) }) {
                is ResolvedLink.Direct -> {
                    update(entry.id) { it.display = "${resolved.service} · $url" }
                    resolved.url
                }
                is ResolvedLink.Search -> {
                    update(entry.id) {
                        it.display = resolved.display
                        it.note = resolved.note
                    }
                    findYouTubeLink(entry.id, YouTubeLinkFinder.Wanted(title = resolved.query))
                        ?: return@launch
                }
                is ResolvedLink.Unsupported -> {
                    update(entry.id) {
                        it.failed = true
                        it.done = true
                        it.status = resolved.reason
                    }
                    return@launch
                }
            }

            update(entry.id) { it.status = "Starting…" }
            val destination = File(settings.downloadDir)
            val result = YtDlp.download(
                target = target,
                destination = destination,
                toMp3 = downloadToMp3 && tools.ffmpeg,
                embedThumbnail = downloadEmbedArt,
                wholePlaylist = downloadPlaylist,
                quality = downloadQuality,
                onProgress = { progress -> onDownloadProgress(entry.id, progress) }
            )

            result.fold(
                onSuccess = { files ->
                    update(entry.id) {
                        it.done = true
                        it.percent = 1f
                        it.files = files
                        it.status = if (files.isEmpty()) {
                            "Finished, but nothing was produced."
                        } else {
                            "Saved ${files.size} file${if (files.size == 1) "" else "s"}"
                        }
                    }
                    // Straight into the library, wherever the download folder is.
                    addToLibrary(files)
                },
                onFailure = { error ->
                    update(entry.id) {
                        it.done = true
                        it.failed = true
                        it.status = error.message ?: "Download failed."
                    }
                }
            )
        }
    }

    /**
     * Sends a search result to the downloader.
     *
     * Services that host audio (YouTube, Audius, the Internet Archive) carry a
     * real page URL. Pure metadata catalogues — iTunes, MusicBrainz, Genius —
     * do not, so the artist and title are handed to yt-dlp as a search instead,
     * which is the same fallback a Spotify link takes.
     */
    /**
     * Finds the YouTube video that is the song and returns its link.
     *
     * When nothing suitable turns up the entry fails with the reason, rather
     * than saving an edit or a cover that would look like success.
     */
    private suspend fun findYouTubeLink(id: Long, wanted: YouTubeLinkFinder.Wanted): String? =
        when (val outcome = linkFinder.find(wanted)) {
            is YouTubeLinkFinder.Outcome.Found -> {
                val video = outcome.pick.video
                update(id) { entry ->
                    entry.display = "YouTube · ${video.title}"
                    entry.note = listOfNotNull(
                        entry.note,
                        buildString {
                            append("Found: ${video.channel}")
                            video.durationSeconds?.let { append(" · ${YouTubeFormat.duration(it)}") }
                            video.viewCount?.let { append(" · ${YouTubeFormat.views(it)}") }
                            append(" · ${video.watchUrl}")
                        }
                    ).joinToString("\n")
                    entry.status = "Starting…"
                }
                video.watchUrl
            }

            is YouTubeLinkFinder.Outcome.NothingSuitable -> {
                update(id) { entry ->
                    entry.done = true
                    entry.failed = true
                    entry.status = outcome.message
                }
                null
            }
        }

    fun downloadMatch(match: MusicMatch) {
        if (match.drmProtected) {
            toolNote = "${match.provider} streams DRM-protected audio - there is no file " +
                "to download. Search by name to find it somewhere that hosts it."
            return
        }
        if (!tools.ready) {
            toolNote = "Install yt-dlp first — one click, about 12 MB."
            return
        }
        val direct = match.downloadUrl?.takeIf { it.startsWith("http", ignoreCase = true) }
        if (direct != null) {
            startDownload(direct)
            return
        }

        val entry = DownloadEntry(
            id = System.nanoTime(),
            target = match.display,
            display = match.display,
            note = "${match.provider} has no downloadable file, so the same track is " +
                "found on YouTube first and that link is downloaded."
        )
        downloads = downloads + entry
        scope.launch {
            update(entry.id) { it.status = "Finding it on YouTube…" }
            // The match's own title, artist and length, so the finder can refuse
            // an edit that happens to share the name.
            val link = findYouTubeLink(
                entry.id,
                YouTubeLinkFinder.Wanted(
                    title = match.title,
                    artist = match.artist,
                    durationMs = match.durationMs
                )
            ) ?: return@launch
            val result = YtDlp.download(
                target = link,
                destination = File(settings.downloadDir),
                toMp3 = downloadToMp3 && tools.ffmpeg,
                embedThumbnail = downloadEmbedArt,
                wholePlaylist = false,
                quality = downloadQuality,
                onProgress = { progress -> onDownloadProgress(entry.id, progress) }
            )
            result.fold(
                onSuccess = { files ->
                    update(entry.id) {
                        it.done = true
                        it.percent = 1f
                        it.files = files
                        it.status = "Saved ${files.size} file${if (files.size == 1) "" else "s"}"
                    }
                    addToLibrary(files)
                },
                onFailure = { error ->
                    update(entry.id) {
                        it.done = true
                        it.failed = true
                        it.status = error.message ?: "Download failed."
                    }
                }
            )
        }
    }

    private fun isSpotify(url: String): Boolean =
        runCatching { java.net.URI(url).host.orEmpty().lowercase() }
            .getOrDefault("")
            .contains("spotify.com")

    /**
     * Hands a Spotify link to spotdl.
     *
     * spotdl reads Spotify's metadata and fetches the matching recording from
     * YouTube; Spotify's own audio is encrypted and no tool can download it. So
     * what arrives is a match rather than the Spotify file — usually the same
     * recording, occasionally a live take or a remaster. The note says so on the
     * row rather than leaving it to be discovered on listening.
     */
    private suspend fun downloadWithSpotdl(id: Long, url: String) {
        update(id) {
            it.status = "Reading Spotify…"
            it.note = "Spotify's audio is DRM protected, so spotdl looks the track up " +
                "and fetches the same recording from YouTube. Check it is the version " +
                "you wanted."
        }
        val result = YtDlp.downloadSpotify(
            url = url,
            destination = File(settings.downloadDir),
            toMp3 = downloadToMp3,
            quality = downloadQuality,
            onProgress = { progress -> onDownloadProgress(id, progress) }
        )
        result.fold(
            onSuccess = { files ->
                update(id) {
                    it.done = true
                    it.percent = 1f
                    it.files = files
                    it.status = if (files.isEmpty()) {
                        "Finished, but nothing was produced."
                    } else {
                        "Saved ${files.size} file${if (files.size == 1) "" else "s"}"
                    }
                }
                rescan()
            },
            onFailure = { error ->
                update(id) {
                    it.done = true
                    it.failed = true
                    it.status = error.message ?: "Download failed."
                }
            }
        )
    }

    private fun onDownloadProgress(id: Long, progress: DownloadProgress) {
        if (progress.line.isNotBlank()) {
            downloadLog = (downloadLog + progress.line).takeLast(200)
        }
        update(id) {
            progress.percent?.let { percent -> it.percent = percent }
            if (progress.line.startsWith("[ExtractAudio]")) it.status = "Converting…"
            else if (progress.percent != null) it.status = "Downloading"
        }
    }

    private fun update(id: Long, change: (DownloadEntry) -> Unit) {
        downloads = downloads.map { entry ->
            if (entry.id != id) entry else entry.copy().also(change)
        }
    }

    fun clearFinishedDownloads() {
        downloads = downloads.filterNot { it.done }
    }

    fun chooseDownloadDir(dir: File) {
        settings.downloadDir = dir.absolutePath
    }

    val downloadDir: String get() = settings.downloadDir

    // ---- Selecting several tracks -------------------------------------------
    //
    // Windows conventions, because this is a Windows app: Ctrl+click toggles
    // one, Shift+click takes a range from the last thing touched, Ctrl+A takes
    // everything on screen, Escape drops it. A plain click still plays, which
    // is the one place this differs from a file manager - it is a music player
    // first, and losing click-to-play to gain click-to-select would be a poor
    // trade.
    //
    // Held as absolute paths rather than indices: the table is re-sorted and
    // re-filtered constantly, and an index would silently come to mean a
    // different track.

    var selectedPaths by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Where a Shift+click range measures from. */
    private var selectionAnchor: String? = null

    val hasSelection: Boolean get() = selectedPaths.isNotEmpty()

    fun clearSelection() {
        selectedPaths = emptySet()
        selectionAnchor = null
    }

    /** Ctrl+click: add or remove one, and move the anchor here. */
    fun toggleSelection(track: DesktopTrack) {
        val path = track.file.absolutePath
        selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
        selectionAnchor = path
    }

    /**
     * Shift+click: everything between the anchor and here.
     *
     * With no anchor yet this behaves as a plain Ctrl+click, which is what
     * every file manager does on a first Shift+click.
     */
    fun extendSelection(track: DesktopTrack, visible: List<DesktopTrack>) {
        val anchorPath = selectionAnchor
        if (anchorPath == null) {
            toggleSelection(track)
            return
        }
        val from = visible.indexOfFirst { it.file.absolutePath == anchorPath }
        val to = visible.indexOfFirst { it.file.absolutePath == track.file.absolutePath }
        if (from < 0 || to < 0) {
            toggleSelection(track)
            return
        }
        val range = if (from <= to) from..to else to..from
        // Added to what is already selected rather than replacing it, so
        // Ctrl+click then Shift+click builds up as expected.
        selectedPaths = selectedPaths + range.map { visible[it].file.absolutePath }
    }

    fun selectAll(visible: List<DesktopTrack>) {
        selectedPaths = visible.map { it.file.absolutePath }.toSet()
        selectionAnchor = visible.lastOrNull()?.file?.absolutePath
    }

    fun selectedTracks(visible: List<DesktopTrack>): List<DesktopTrack> =
        visible.filter { it.file.absolutePath in selectedPaths }

    /** Plays the selection, in the order it appears on screen. */
    fun playSelection(visible: List<DesktopTrack>) {
        val chosen = selectedTracks(visible)
        chosen.firstOrNull()?.let { play(it, chosen) }
    }

    fun favouriteSelection(visible: List<DesktopTrack>) {
        val chosen = selectedTracks(visible)
        if (chosen.isEmpty()) return
        // One decision for the whole selection: if any are not favourites,
        // favourite all of them. Toggling each would leave a mixed set mixed.
        val makeFavourite = chosen.any { it.file.absolutePath !in favourites }
        scope.launch {
            io {
                for (track in chosen) {
                    store.setFavourite(track.file.absolutePath, makeFavourite)
                }
            }
            favourites = io { store.favourites() }
        }
    }

    fun zipSelection(visible: List<DesktopTrack>) {
        val chosen = selectedTracks(visible)
        clearSelection()
        startArchive("Selection", chosen)
    }

    /** Opens the containing folder, selecting the first of them. */
    fun revealSelection(visible: List<DesktopTrack>) {
        val first = selectedTracks(visible).firstOrNull() ?: return
        Explorer.reveal(first.file)
    }

    /**
     * Sends the selected files to the Recycle Bin.
     *
     * Not [java.io.File.delete]: these are the user's own files in their own
     * folders, and a mis-click has to be recoverable.
     */
    fun deleteSelection(visible: List<DesktopTrack>) {
        val chosen = selectedTracks(visible)
        if (chosen.isEmpty()) return
        clearSelection()
        deleteTracks(chosen)
    }

    /**
     * Moves tracks to the Recycle Bin and takes them out of the library.
     *
     * Not [java.io.File.delete]: these are the user's own files in their own
     * folders, and a mis-click has to be recoverable. If the song that is playing
     * is among them, playback stops first, because Windows will not move a file
     * that is still open and the delete would otherwise fail without a word.
     */
    fun deleteTracks(chosen: List<DesktopTrack>) {
        if (chosen.isEmpty()) return
        val paths = chosen.map { it.file.absolutePath }.toSet()
        if (engine.status.value.track?.file?.absolutePath in paths) engine.stop()

        scope.launch {
            val failed = io {
                chosen.filterNot { track ->
                    runCatching {
                        val desktop = java.awt.Desktop.getDesktop()
                        if (desktop.isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH)) {
                            desktop.moveToTrash(track.file)
                        } else {
                            track.file.delete()
                        }
                    }.getOrDefault(false)
                }
            }
            val removed = chosen.filterNot { it in failed }
            if (removed.isNotEmpty()) {
                val gone = removed.map { it.file.absolutePath }
                io { store.forgetPaths(gone) }
                // Taken out of the list directly: a rescan would re-read every tag
                // in the library to remove one file.
                val goneSet = gone.toSet()
                tracks = tracks.filterNot { it.file.absolutePath in goneSet }
                favourites = favourites - goneSet
            }
            archiveNote = null
            selectionNote = buildString {
                if (removed.isNotEmpty()) {
                    append("Moved ${removed.size} file${if (removed.size == 1) "" else "s"} to the Recycle Bin.")
                }
                if (failed.isNotEmpty()) {
                    if (isNotEmpty()) append(" ")
                    append(
                        "Couldn't move ${failed.first().file.name}" +
                            (if (failed.size > 1) " and ${failed.size - 1} more" else "") +
                            ". It may be open in another program."
                    )
                }
            }
        }
    }

    var selectionNote by mutableStateOf<String?>(null)
        private set

    fun dismissSelectionNote() { selectionNote = null }

    // ---- Zip and ship -------------------------------------------------------

    /** Progress and outcome of the current archive job. */
    var archiveRunning by mutableStateOf(false)
        private set
    var archiveProgress by mutableStateOf(0f)
        private set
    var archiveCurrent by mutableStateOf("")
        private set
    var archiveNote by mutableStateOf<String?>(null)
        private set
    var archiveFile by mutableStateOf<File?>(null)
        private set

    private var archiveJob: Job? = null
    @Volatile private var archiveCancelled = false

    /** Zips the whole library. */
    fun zipLibrary() = startArchive("Library", tracks)

    /** Zips one playlist, in its own order. */
    fun zipPlaylist(playlist: StoredPlaylist) {
        scope.launch {
            val paths = io { store.playlistPaths(playlist.id) }
            val byPath = tracks.associateBy { it.file.absolutePath }
            startArchive(playlist.name, paths.mapNotNull { byPath[it] })
        }
    }

    /**
     * Packs [chosen] into a zip beside the downloads folder.
     *
     * Entry names carry the artist so an archive is navigable once unpacked —
     * a folder of two hundred files called "01.mp3" is not. Numbering keeps a
     * playlist's order, which the filesystem would otherwise sort away.
     */
    internal fun startArchive(label: String, chosen: List<DesktopTrack>) {
        if (archiveRunning) return
        if (chosen.isEmpty()) {
            archiveNote = "Nothing to archive."
            return
        }

        archiveCancelled = false
        archiveRunning = true
        archiveProgress = 0f
        archiveNote = null
        archiveFile = null

        archiveJob = scope.launch {
            val stamp = java.time.LocalDate.now().toString()
            val destination = File(
                File(settings.downloadDir, "archives"),
                MusicArchive.safeName("Resonate $label $stamp") + ".zip"
            )
            val digits = chosen.size.toString().length
            val entries = chosen.mapIndexed { index, track ->
                val number = (index + 1).toString().padStart(digits, '0')
                val artist = track.artist?.takeIf { it.isNotBlank() }
                val stem = listOfNotNull(artist, track.title).joinToString(" - ")
                ArchiveEntry(
                    source = track.file,
                    entryName = "$number ${MusicArchive.safeName(stem)}.${track.file.extension}"
                )
            }

            val result = io {
                MusicArchive.zip(
                    entries = entries,
                    destination = destination,
                    onProgress = { progress ->
                        archiveProgress = progress.fraction
                        archiveCurrent = progress.currentName
                    },
                    shouldContinue = { !archiveCancelled }
                )
            }

            archiveRunning = false
            archiveCurrent = ""
            result.fold(
                onSuccess = { done ->
                    archiveFile = done.file
                    archiveNote = buildString {
                        append("${done.included} tracks, ")
                        append("%.1f MB".format(done.bytes / 1_048_576.0))
                        if (done.skipped.isNotEmpty()) {
                            append(" — ${done.skipped.size} missing from storage")
                        }
                    }
                },
                onFailure = { archiveNote = it.message ?: "Couldn't build the archive." }
            )
            archiveJob = null
        }
    }

    fun cancelArchive() {
        archiveCancelled = true
        archiveJob?.cancel()
        archiveJob = null
        archiveRunning = false
        archiveNote = "Cancelled."
    }

    /** Opens the folder the archive landed in, and selects it. */
    fun revealArchive() {
        val file = archiveFile ?: return
        Explorer.reveal(file)
    }

    fun dismissArchive() {
        archiveNote = null
        archiveFile = null
    }

    // ---- Duplicates ---------------------------------------------------------

    var duplicates by mutableStateOf<List<DesktopDuplicateGroup>>(emptyList())
        private set
    var duplicatesScanning by mutableStateOf(false)
        private set
    var duplicatesNote by mutableStateOf<String?>(null)
        private set

    fun findDuplicates(within: List<DesktopTrack>? = null) {
        duplicatesScanning = true
        duplicatesNote = null
        scope.launch {
            val counts = playCounts
            val found = io {
                // Reading embedded art opens the file, so a comparator that
                // called it directly would re-read the same track O(log n) times.
                val hasArt = HashMap<String, Boolean>()
                DuplicateMatcher.group(
                    items = within ?: tracks,
                    artistOf = { it.artist },
                    titleOf = { it.title },
                    durationOf = { it.durationMs },
                    // Biggest file first as a stand-in for bitrate, then one
                    // with art, then the one actually played.
                    keeperOrder = compareByDescending<DesktopTrack> { it.sizeBytes }
                        .thenByDescending {
                            hasArt.getOrPut(it.file.absolutePath) {
                                Covers.hasLocalArt(it)
                            }
                        }
                        .thenByDescending { counts[it.file.absolutePath] ?: 0 }
                        .thenBy { it.file.lastModified() }
                ).map { DesktopDuplicateGroup(it.keep, it.remove) }
                    .sortedBy { it.keep.title.lowercase(Locale.ROOT) }
            }
            duplicates = found
            duplicatesScanning = false
            if (found.isEmpty()) duplicatesNote = "No duplicates found."
        }
    }

    /**
     * Sends the chosen copies to the Recycle Bin where the platform allows it.
     *
     * Deliberately not [File.delete]: these are the user's own files, sitting in
     * their own folders, and a wrong match should be recoverable.
     */
    fun removeDuplicates(groups: List<DesktopDuplicateGroup>) {
        scope.launch {
            val paths = groups.flatMap { it.remove }.map { it.file }
            val removed = io {
                paths.count { file ->
                    runCatching {
                        val desktop = java.awt.Desktop.getDesktop()
                        if (desktop.isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH)) {
                            desktop.moveToTrash(file)
                        } else {
                            file.delete()
                        }
                    }.getOrDefault(false)
                }
            }
            io { store.forgetPaths(paths.map { it.absolutePath }) }
            duplicates = emptyList()
            duplicatesNote = "Removed $removed file${if (removed == 1) "" else "s"}."
            rescan()
        }
    }

    // ---- Albums and artists -------------------------------------------------

    var albumSort by mutableStateOf(GroupSort.NAME)
    var artistSort by mutableStateOf(GroupSort.NAME)

    /** Keys of the album or artist cards picked with Ctrl or Shift. */
    var pickedGroups by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Which page [pickedGroups] belongs to. */
    var pickedKind by mutableStateOf<CollectionKind?>(null)
        private set

    /** Where a Shift+click on a card measures from. */
    private var pickAnchor: String? = null

    fun clearPicked() {
        pickedGroups = emptySet()
        pickedKind = null
        pickAnchor = null
    }

    /** Ctrl+click on a card: pick it, or unpick it. */
    fun togglePicked(kind: CollectionKind, group: TrackGroup) {
        if (pickedKind != kind) clearPicked()
        pickedKind = kind
        pickedGroups = if (group.key in pickedGroups) pickedGroups - group.key else pickedGroups + group.key
        pickAnchor = group.key
    }

    /** Shift+click on a card: everything from the last Ctrl+click to here. */
    fun extendPicked(kind: CollectionKind, group: TrackGroup, shown: List<TrackGroup>) {
        val from = shown.indexOfFirst { it.key == pickAnchor }
        val to = shown.indexOfFirst { it.key == group.key }
        if (pickedKind != kind || from < 0 || to < 0) {
            togglePicked(kind, group)
            return
        }
        pickedGroups = pickedGroups + (minOf(from, to)..maxOf(from, to)).map { shown[it].key }
    }

    /** What the merge dialog offers: one plan for the picked cards, or one for each duplicate set. */
    var mergePlans by mutableStateOf<List<MergePlan>>(emptyList())
        private set
    var mergeKind by mutableStateOf(CollectionKind.ALBUMS)
        private set
    var mergeFromPicked by mutableStateOf(false)
        private set
    var merging by mutableStateOf(false)
        private set

    /** How the last merge went, shown under the page title. */
    var mergeNote by mutableStateOf<String?>(null)
        private set

    fun planPickedMerge(kind: CollectionKind, shown: List<TrackGroup>) {
        val chosen = shown.filter { it.key in pickedGroups }
        if (pickedKind != kind || chosen.size < 2) return
        mergeKind = kind
        mergeFromPicked = true
        mergeNote = null
        mergePlans = listOf(TrackGroups.plan(kind, chosen))
    }

    fun planDuplicateMerges(kind: CollectionKind, shown: List<TrackGroup>) {
        mergeKind = kind
        mergeFromPicked = false
        mergeNote = null
        mergePlans = TrackGroups.duplicates(kind, shown).map { TrackGroups.plan(kind, it) }
    }

    /**
     * Retags the songs in [plans] so that each set becomes one album or artist,
     * then re-reads only those files.
     *
     * Written into the files themselves: a merge kept only in Resonate would
     * come apart at the next scan, and would show in no other player.
     */
    fun merge(plans: List<MergePlan>) {
        if (plans.isEmpty() || merging) return
        merging = true
        mergeNote = null
        scope.launch {
            val changes = plans.flatMap { TrackGroups.changes(it) }
            // Whatever happens - an unreadable file, a locked one - the merge has
            // to end: leaving it running would disable every merge button for good.
            val outcome = runCatching {
                val written = io { changes.filter { TagWriter.change(it).isSuccess }.map { it.file } }
                val reread = FolderLibrary.readFiles(written).associateBy { it.file.absolutePath }
                tracks = tracks.map { reread[it.file.absolutePath] ?: it }
                written.size
            }
            clearPicked()
            mergePlans = emptyList()
            merging = false
            val what = if (plans.size == 1) {
                "${plans[0].groups.size} ${plans[0].kind.plural} into “${plans[0].name}”"
            } else {
                "${plans.size} sets of ${plans[0].kind.plural}"
            }
            mergeNote = outcome.fold(
                { written ->
                    val failed = changes.size - written
                    "Merged $what · $written songs retagged" +
                        if (failed > 0) " · $failed could not be written - playing, or open elsewhere?" else ""
                },
                { "Merging $what failed: ${it.message ?: it.javaClass.simpleName}" }
            )
        }
    }

    // ---- Lifecycle ----------------------------------------------------------

    fun start() {
        applyOutputs()
        pushVolume()
        applyDuckBinding()
        // The music folder is always part of the library, so there is something to
        // scan even before any folder has been added by hand.
        rescan()
        refreshPlaylists()
        refreshTools()
        refreshWeather()
        loadWallpaper()
    }

    fun release() {
        hotkey.unbind()
        closeListenEvent()
        engine.release()
        store.close()
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}

/** How the Identify box interprets what you typed. */
enum class IdentifyMode(val label: String, val placeholder: String) {
    NAME("Name", "Artist and title, or whatever you can remember"),
    LYRICS("Lyrics", "A line you remember, however roughly"),
    LINK("Link", "TikTok, Instagram, YouTube, SoundCloud link"),
    YOUTUBE("YouTube", "Search YouTube — anything, not just music")
}

/** The four bulk tools, and the mark each one records so reruns can skip. */
/** How many songs the cover and tag tools look up at once. */
private const val SONGS_AT_ONCE = 5

/**
 * How long one song's tag lookup may take. Every track goes through the tag
 * tool, not only the ones missing something, so a slow service is cut off
 * sooner than for covers.
 */
private const val TAGS_GIVE_UP_MS = 10_000L

enum class BulkKind(val label: String, val markColumn: String) {
    COVERS("Covers", "artCheckedAt"),
    TAGS("Names & tags", "identifiedAt"),
    LYRICS("Lyrics", "lyricsCheckedAt"),
    IDENTIFY("Identify by sound", "fingerprintedAt")
}

/** What to send a catalogue when looking a track up. */
private val DesktopTrack.searchQuery: String
    get() = listOfNotNull(artist?.takeIf { it.isNotBlank() }, title).joinToString(" ")
