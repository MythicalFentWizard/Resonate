package com.exo.musicplayer.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exo.musicplayer.data.db.HourBucket
import com.exo.musicplayer.data.db.Lyrics
import com.exo.musicplayer.data.archive.ArchiveEntry
import com.exo.musicplayer.data.archive.MusicArchive
import com.exo.musicplayer.data.db.PlaylistSummary
import com.exo.musicplayer.data.playlist.ImportResult as PlaylistImport
import com.exo.musicplayer.data.playlist.PlaylistEntry
import com.exo.musicplayer.data.playlist.PlaylistFile
import com.exo.musicplayer.data.db.Track
import com.exo.musicplayer.data.db.TrackListenTime
import com.exo.musicplayer.data.db.WeatherBucket
import com.exo.musicplayer.data.ingest.FolderScanner
import com.exo.musicplayer.data.library.DuplicateFinder
import com.exo.musicplayer.data.library.DuplicateGroup
import com.exo.musicplayer.data.lyrics.LrcParser
import com.exo.musicplayer.data.lyrics.LyricLine
import com.exo.musicplayer.data.lyrics.LyricsFetch
import com.exo.musicplayer.data.ingest.ImportResult
import com.exo.musicplayer.data.recognition.AudioSampler
import com.exo.musicplayer.data.recognition.AudiusProvider
import com.exo.musicplayer.data.recognition.DeezerProvider
import com.exo.musicplayer.data.recognition.GeniusMetadataProvider
import com.exo.musicplayer.data.recognition.ITunesProvider
import com.exo.musicplayer.data.recognition.MetadataProviderChain
import com.exo.musicplayer.data.recognition.MusicBrainzProvider
import com.exo.musicplayer.data.recognition.MusicMatch
import com.exo.musicplayer.data.recognition.RecognitionResult
import com.exo.musicplayer.data.recognition.YouTubeSearchProvider
import com.exo.musicplayer.data.repo.SortMode
import com.exo.musicplayer.data.weather.Affinity
import com.exo.musicplayer.data.weather.WeatherAffinity
import com.exo.musicplayer.data.weather.WeatherSnapshot
import com.exo.musicplayer.musicApp
import com.exo.musicplayer.playback.AudioFxState
import com.exo.musicplayer.playback.AudioOutput
import com.exo.musicplayer.playback.InterruptionBehavior
import com.exo.musicplayer.playback.InterruptionState
import com.exo.musicplayer.playback.FxPreset
import com.exo.musicplayer.playback.PlaybackState
import com.exo.musicplayer.playback.ReverbRoom
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File

/** What the Moods tab should be showing right now. */
sealed interface MoodState {
    data object NeedsPermission : MoodState
    data object Loading : MoodState
    data object Unavailable : MoodState
    data class Learning(val playsRecorded: Int, val playsNeeded: Int, val weather: WeatherSnapshot) :
        MoodState
    data class Ready(
        val weather: WeatherSnapshot,
        val tracks: List<Track>,
        val affinities: Map<Long, Affinity>
    ) : MoodState
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application.musicApp
    private val library = app.library
    private val playback = app.playback
    private val stats = app.stats
    private val weather = app.weather

    private val started = SharingStarted.WhileSubscribed(5_000)

    // The same sources and order as the Windows cover tool: YouTube first, then
    // the stores and free catalogues, MusicBrainz last.
    private val coverSearch = MetadataProviderChain(
        listOf(
            YouTubeSearchProvider(),
            ITunesProvider(),
            DeezerProvider(),
            AudiusProvider(),
            GeniusMetadataProvider(),
            MusicBrainzProvider()
        )
    )

    private val _sort = MutableStateFlow(SortMode.TITLE)
    val sort: StateFlow<SortMode> = _sort.asStateFlow()

    val tracks: StateFlow<List<Track>> = _sort
        .flatMapLatest { library.observeTracks(it) }
        .stateIn(viewModelScope, started, emptyList())

    val favorites: StateFlow<List<Track>> = library.observeFavorites()
        .stateIn(viewModelScope, started, emptyList())

    val playlists: StateFlow<List<PlaylistSummary>> = library.observePlaylists()
        .stateIn(viewModelScope, started, emptyList())

    val playbackState: StateFlow<PlaybackState> = playback.state

    // Position updates twice a second. Screens that only need identity or
    // play/pause subscribe to these instead, so a tick doesn't recompose lists.
    val currentTrackId: StateFlow<Long?> = playback.state
        .map { it.currentTrackId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, started, null)

    val isPlaying: StateFlow<Boolean> = playback.state
        .map { it.isPlaying }
        .distinctUntilChanged()
        .stateIn(viewModelScope, started, false)

    val currentTrack: StateFlow<Track?> = combine(playback.state, tracks) { state, all ->
        all.firstOrNull { it.id == state.currentTrackId }
    }.stateIn(viewModelScope, started, null)

    val queue: StateFlow<List<Track>> = combine(playback.state, tracks) { state, all ->
        val byId = all.associateBy { it.id }
        state.queueTrackIds.mapNotNull { byId[it] }
    }.stateIn(viewModelScope, started, emptyList())

    // ---- Search ----

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val searchResults: StateFlow<List<Track>> = _query
        .debounce(180)
        .flatMapLatest { text ->
            if (text.isBlank()) flowOf(emptyList()) else library.search(text.trim())
        }
        .stateIn(viewModelScope, started, emptyList())

    fun setQuery(value: String) { _query.value = value }
    fun setSort(value: SortMode) { _sort.value = value }

    // ---- Stats ----

    val totalListenedMs: StateFlow<Long> = stats.observeTotalListenedMs()
        .stateIn(viewModelScope, started, 0L)

    val totalPlays: StateFlow<Int> = stats.observePlayCount()
        .stateIn(viewModelScope, started, 0)

    val distinctTracksPlayed: StateFlow<Int> = stats.observeDistinctTracksPlayed()
        .stateIn(viewModelScope, started, 0)

    val topTracks: StateFlow<List<Pair<Track, TrackListenTime>>> =
        combine(stats.observeTopByListenTime(25), tracks) { rows, all ->
            val byId = all.associateBy { it.id }
            rows.mapNotNull { row -> byId[row.trackId]?.let { it to row } }
        }.stateIn(viewModelScope, started, emptyList())

    val topThisWeek: StateFlow<List<Pair<Track, TrackListenTime>>> =
        combine(stats.observeTopThisWeek(10), tracks) { rows, all ->
            val byId = all.associateBy { it.id }
            rows.mapNotNull { row -> byId[row.trackId]?.let { it to row } }
        }.stateIn(viewModelScope, started, emptyList())

    val listeningByHour: StateFlow<List<HourBucket>> = stats.observeByHour()
        .stateIn(viewModelScope, started, emptyList())

    val listeningByWeather: StateFlow<List<WeatherBucket>> = stats.observeByWeather()
        .stateIn(viewModelScope, started, emptyList())

    // ---- Moods (weather matching) ----

    private val _mood = MutableStateFlow<MoodState>(MoodState.Loading)
    val mood: StateFlow<MoodState> = _mood.asStateFlow()

    /** Recomputed on demand: a network call and several aggregate queries. */
    fun refreshMood(force: Boolean = false) = viewModelScope.launch {
        if (!weather.hasLocationPermission()) {
            _mood.value = MoodState.NeedsPermission
            return@launch
        }
        _mood.value = MoodState.Loading

        val snapshot = runCatching { weather.currentWeather(force) }.getOrNull()
        if (snapshot == null) {
            _mood.value = MoodState.Unavailable
            return@launch
        }

        val recorded = runCatching { stats.weatherTaggedPlays() }.getOrDefault(0)
        if (recorded < WeatherAffinity.MIN_HISTORY) {
            _mood.value = MoodState.Learning(recorded, WeatherAffinity.MIN_HISTORY, snapshot)
            return@launch
        }

        val affinities = runCatching { stats.affinitiesFor(snapshot.condition) }
            .getOrDefault(emptyList())
        val ranked = library.tracksByIds(affinities.map { it.trackId })
        _mood.value = if (ranked.isEmpty()) {
            MoodState.Learning(recorded, WeatherAffinity.MIN_HISTORY, snapshot)
        } else {
            MoodState.Ready(snapshot, ranked, affinities.associateBy { it.trackId })
        }
    }

    fun playMood() {
        val ready = _mood.value as? MoodState.Ready ?: return
        if (ready.tracks.isNotEmpty()) playback.play(ready.tracks, 0)
    }

    // ---- Playback actions ----

    fun playFrom(list: List<Track>, index: Int) = playback.play(list, index)
    fun playAll() = playback.play(tracks.value, 0)

    fun shuffleAll() {
        val all = tracks.value
        if (all.isEmpty()) return
        playback.play(all.shuffled(), 0)
    }

    fun togglePlayPause() = playback.togglePlayPause()
    fun next() = playback.next()
    fun previous() = playback.previous()
    fun seekToFraction(fraction: Float) = playback.seekToFraction(fraction)
    fun toggleShuffle() = playback.toggleShuffle()
    fun cycleRepeat() = playback.cycleRepeat()
    fun playNext(track: Track) = playback.playNext(track)
    fun addToQueue(track: Track) = playback.addToQueue(listOf(track))
    fun jumpToQueueIndex(index: Int) = playback.jumpToQueueIndex(index)
    fun removeFromQueue(index: Int) = playback.removeFromQueue(index)

    // ---- Importing from the file picker ----

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun consumeNotice() { _notice.value = null }

    /** Live progress for a bulk folder import. */
    data class BulkProgress(
        val done: Int = 0,
        val total: Int = 0,
        val added: Int = 0,
        val duplicates: Int = 0,
        val failed: Int = 0,
        val currentName: String? = null,
        val scanning: Boolean = false
    ) {
        val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
    }

    private val _bulk = MutableStateFlow<BulkProgress?>(null)
    val bulk: StateFlow<BulkProgress?> = _bulk.asStateFlow()

    private var bulkJob: Job? = null

    fun cancelBulkImport() {
        bulkJob?.cancel()
        bulkJob = null
        _bulk.value = null
    }

    /**
     * Imports files chosen through the system picker. Same pipeline as a Telegram
     * share -- copied, hashed, tagged -- so files added this way behave
     * identically and deduplicate against songs already in the library.
     */
    fun importFromUris(uris: List<Uri>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        _importing.value = true
        var added = 0
        var duplicates = 0
        var failed = 0
        for (uri in uris) {
            when (app.importer.import(uri, sourceApp = "Files")) {
                is ImportResult.Imported -> added++
                is ImportResult.Duplicate -> duplicates++
                else -> failed++
            }
        }
        _importing.value = false
        _notice.value = summarise(added, duplicates, failed)
    }

    /**
     * Bulk-imports every audio file under a folder the user granted access to.
     *
     * Safe to re-run: content hashing means already-imported songs are detected
     * as duplicates and skipped, so an interrupted import can simply be started
     * again rather than resumed.
     */
    fun importFolder(treeUri: Uri, label: String?) {
        bulkJob?.cancel()
        bulkJob = viewModelScope.launch {
            _bulk.value = BulkProgress(scanning = true)
            val found = runCatching {
                FolderScanner.findAudio(getApplication(), treeUri)
            }.getOrDefault(emptyList())

            if (found.isEmpty()) {
                _bulk.value = null
                _notice.value = "No audio files found in that folder"
                return@launch
            }

            var added = 0
            var duplicates = 0
            var failed = 0
            _bulk.value = BulkProgress(total = found.size)

            for ((index, item) in found.withIndex()) {
                if (!isActive) return@launch
                _bulk.value = BulkProgress(
                    done = index,
                    total = found.size,
                    added = added,
                    duplicates = duplicates,
                    failed = failed,
                    currentName = item.name
                )
                when (app.importer.import(item.uri, sourceApp = label ?: "Folder")) {
                    is ImportResult.Imported -> added++
                    is ImportResult.Duplicate -> duplicates++
                    else -> failed++
                }
            }

            _bulk.value = null
            _notice.value = summarise(added, duplicates, failed)
        }
    }

    private fun summarise(added: Int, duplicates: Int, failed: Int): String = buildString {
        append(if (added == 1) "Added 1 song" else "Added $added songs")
        if (duplicates > 0) append(", $duplicates already in library")
        if (failed > 0) append(", $failed skipped")
    }

    // ---- Bulk cover refresh ----

    /** How much work each bulk job has left, for the tools dialog. */
    data class BulkCounts(val covers: Int, val identify: Int, val lyrics: Int, val total: Int)

    val bulkCounts: StateFlow<BulkCounts> = tracks.map { all ->
        BulkCounts(
            covers = all.count { it.artCheckedAt == null },
            identify = all.count { it.identifiedAt == null },
            lyrics = all.count { it.lyricsCheckedAt == null },
            total = all.size
        )
    }.stateIn(viewModelScope, started, BulkCounts(0, 0, 0, 0))

    /**
     * Fills in missing cover art across the library.
     *
     * Uses the free metadata search on each track's existing tags rather than
     * fingerprinting: a fingerprint costs a decode plus a network round trip per
     * song, which on a large library is minutes of work for art a title lookup
     * already finds.
     *
     * Five songs at a time, each asking every catalogue at once, as on Windows;
     * a dead image link moves on to the next catalogue's picture.
     */
    fun updateAllCovers(redo: Boolean = false) =
        runBulk("cover", redo, parallel = 5) { track ->
            val query = listOfNotNull(track.artist, track.title).joinToString(" ")
            val ok = runCatching {
                coverSearch.findArtwork(query) { url ->
                    library.updateArtwork(track, url).takeIf { it }
                }
            }.getOrNull() == true
            library.markArtChecked(track.id)
            ok
        }

    /**
     * Fingerprints every track and rewrites its tags from what comes back.
     *
     * Far slower than the cover pass — each track is decoded and sent to Shazam —
     * so it is a separate, explicit action rather than part of a general
     * "tidy up my library" button.
     */
    fun identifyAll(redo: Boolean = false) = runBulk("identify", redo) { track ->
        val samples = runCatching {
            AudioSampler.sampleMono16k(getApplication(), Uri.fromFile(File(track.filePath)))
        }.getOrNull()

        val matched = samples != null &&
            (runCatching { app.shazam.recognize(samples) }.getOrNull()
                as? RecognitionResult.Found)
                ?.matches?.firstOrNull()
                ?.let { runCatching { library.applyMatch(track, it) }.isSuccess } == true

        library.markIdentified(track.id)
        matched
    }

    /** Fetches lyrics for the whole library through the four-tier chain. */
    fun fetchAllLyrics(redo: Boolean = false) = runBulk("lyrics", redo, parallel = 5) { track ->
        val found = runCatching { app.lyrics.fetch(track, force = redo) }
            .getOrNull() is LyricsFetch.Found
        library.markLyricsChecked(track.id)
        found
    }

    /**
     * Shared driver for the bulk jobs.
     *
     * Every track is stamped once attempted, successfully or not, so a song the
     * services simply don't know is not retried on every subsequent run. Passing
     * [redo] ignores those stamps and reprocesses everything.
     */
    private fun runBulk(
        kind: String,
        redo: Boolean = false,
        parallel: Int = 1,
        work: suspend (Track) -> Boolean
    ) {
        bulkJob?.cancel()
        bulkJob = viewModelScope.launch {
            val all = tracks.value
            val targets = if (redo) all else all.filter {
                when (kind) {
                    "cover" -> it.artCheckedAt == null
                    "identify" -> it.identifiedAt == null
                    else -> it.lyricsCheckedAt == null
                }
            }
            if (targets.isEmpty()) {
                _notice.value = "Nothing left to do — tick redo to run it again"
                return@launch
            }

            var finished = 0
            var done = 0
            var failed = 0
            val queue = Channel<Track>(Channel.UNLIMITED)
            targets.forEach { queue.trySend(it) }
            queue.close()
            _bulk.value = BulkProgress(total = targets.size)
            // Workers share the main thread between suspensions, so the counters
            // need no locking.
            coroutineScope {
                repeat(parallel) {
                    launch {
                        for (track in queue) {
                            _bulk.value = BulkProgress(
                                done = finished,
                                total = targets.size,
                                added = done,
                                failed = failed,
                                currentName = track.title
                            )
                            if (runCatching { work(track) }.getOrDefault(false)) done++ else failed++
                            finished++
                        }
                    }
                }
            }

            _bulk.value = null
            _notice.value = when (kind) {
                "cover" -> "Updated $done covers, $failed not found"
                "identify" -> "Identified $done tracks, $failed no match"
                else -> "Found lyrics for $done tracks, $failed missing"
            }
        }
    }


    // ---- Duplicates ----

    private val _duplicates = MutableStateFlow<List<DuplicateGroup>>(emptyList())
    val duplicates: StateFlow<List<DuplicateGroup>> = _duplicates.asStateFlow()

    private val _duplicateScanning = MutableStateFlow(false)
    val duplicateScanning: StateFlow<Boolean> = _duplicateScanning.asStateFlow()

    fun scanDuplicates() = viewModelScope.launch {
        _duplicateScanning.value = true
        _duplicates.value = withContext(Dispatchers.Default) {
            DuplicateFinder.find(library.allTracks())
        }
        _duplicateScanning.value = false
    }

    fun clearDuplicates() { _duplicates.value = emptyList() }

    /** Deletes the chosen copies: database rows, audio files and orphaned art. */
    fun removeDuplicates(tracks: List<Track>) = viewModelScope.launch {
        var removed = 0
        for (track in tracks) {
            playback.evictTrack(track.id)
            runCatching { library.deleteTrack(track) }.onSuccess { removed++ }
        }
        _duplicates.value = emptyList()
        _notice.value = if (removed == 1) "Removed 1 duplicate" else "Removed $removed duplicates"
    }

    // ---- Interruption behaviour ----

    val interruption: StateFlow<InterruptionState> = app.interruption.state

    fun setInterruptionBehavior(behavior: InterruptionBehavior) =
        app.interruption.setBehavior(behavior)

    fun setPlayDuringCalls(enabled: Boolean) = app.interruption.setPlayDuringCalls(enabled)

    // ---- Audio output ----

    val audioOutputs: StateFlow<List<AudioOutput>> = app.audioOutputs.outputs
    val selectedOutputs: StateFlow<Set<String>> = app.audioOutputs.selectedKeys
    val mirrorOutputs: StateFlow<Boolean> = app.audioOutputs.mirrorEnabled

    fun pickOutput(key: String) {
        if (app.audioOutputs.mirrorEnabled.value) {
            app.audioOutputs.toggle(key)
        } else {
            app.audioOutputs.select(key)
        }
    }

    fun setMirrorOutputs(enabled: Boolean) {
        app.audioOutputs.setMirrorEnabled(enabled)
        if (!enabled) {
            // Collapse a multi-selection back to a single output.
            app.audioOutputs.selectedKeys.value.firstOrNull()
                ?.let { app.audioOutputs.select(it) }
        }
    }

    fun refreshOutputs() = app.audioOutputs.refresh()

    // ---- Audio effects ----

    val audioFx: StateFlow<AudioFxState> = app.audioFx.state

    fun applyFxPreset(preset: FxPreset) = app.audioFx.applyPreset(preset)
    fun setFxSpeed(value: Float) = app.audioFx.setSpeed(value)
    fun setFxPitch(semitones: Float) = app.audioFx.setPitch(semitones)
    fun setReverbEnabled(enabled: Boolean) = app.audioFx.setReverbEnabled(enabled)
    fun setReverbRoom(room: ReverbRoom) = app.audioFx.setReverbRoom(room)
    fun setReverbAmount(amount: Float) = app.audioFx.setReverbAmount(amount)
    fun resetFx() = app.audioFx.reset()

    // ---- Lyrics ----

    /** Lyrics for whatever is playing, re-queried as the track changes. */
    val currentLyrics: StateFlow<Lyrics?> = playback.state
        .map { it.currentTrackId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else app.lyrics.observe(id) }
        .stateIn(viewModelScope, started, null)

    val currentLyricLines: StateFlow<List<LyricLine>> = currentLyrics
        .map { lyrics -> lyrics?.syncedText?.let(LrcParser::parse).orEmpty() }
        .stateIn(viewModelScope, started, emptyList())

    private val _lyricsBusy = MutableStateFlow(false)
    val lyricsBusy: StateFlow<Boolean> = _lyricsBusy.asStateFlow()

    private val _lyricsMessage = MutableStateFlow<String?>(null)
    val lyricsMessage: StateFlow<String?> = _lyricsMessage.asStateFlow()

    fun clearLyricsMessage() { _lyricsMessage.value = null }

    fun fetchLyrics(force: Boolean = false) {
        val track = currentTrack.value ?: return
        _lyricsBusy.value = true
        _lyricsMessage.value = null
        viewModelScope.launch {
            when (val result = runCatching { app.lyrics.fetch(track, force) }.getOrNull()) {
                is LyricsFetch.Found -> Unit                    // the flow updates the UI
                is LyricsFetch.Instrumental ->
                    _lyricsMessage.value = "This track is marked as instrumental."
                is LyricsFetch.Error -> _lyricsMessage.value = result.message
                else -> _lyricsMessage.value =
                    "No lyrics found. You can add them yourself below."
            }
            _lyricsBusy.value = false
        }
    }

    fun saveLyrics(text: String) {
        val track = currentTrack.value ?: return
        viewModelScope.launch {
            app.lyrics.saveManual(track.id, text)
            _lyricsMessage.value = null
            _notice.value = if (text.isBlank()) "Lyrics removed" else "Lyrics saved"
        }
    }

    fun deleteLyrics() {
        val track = currentTrack.value ?: return
        viewModelScope.launch {
            app.lyrics.delete(track.id)
            _notice.value = "Lyrics removed"
        }
    }

    // ---- Fix tags (Shazam the file already in the library) ----

    private val _tagTarget = MutableStateFlow<Track?>(null)
    val tagTarget: StateFlow<Track?> = _tagTarget.asStateFlow()

    private val _tagBusy = MutableStateFlow(false)
    val tagBusy: StateFlow<Boolean> = _tagBusy.asStateFlow()

    private val _tagResult = MutableStateFlow<RecognitionResult?>(null)
    val tagResult: StateFlow<RecognitionResult?> = _tagResult.asStateFlow()

    /**
     * Identifies a track already in the library by listening to its own file —
     * the fix for Telegram imports that arrive as "audio_2026_03_12.mp3" with no
     * artist and no cover.
     */
    fun startFixTags(track: Track) {
        _tagTarget.value = track
        _tagResult.value = null
        _tagBusy.value = true
        viewModelScope.launch {
            val samples = runCatching {
                AudioSampler.sampleMono16k(getApplication(), Uri.fromFile(File(track.filePath)))
            }.getOrNull()

            _tagResult.value = if (samples == null) {
                RecognitionResult.Error("Couldn't read the audio for this track.")
            } else {
                runCatching { app.shazam.recognize(samples) }
                    .getOrElse { RecognitionResult.Error(it.message ?: "Identification failed.") }
            }
            _tagBusy.value = false
        }
    }

    fun applyTagMatch(match: MusicMatch) {
        val track = _tagTarget.value ?: return
        viewModelScope.launch {
            runCatching { library.applyMatch(track, match) }
            _notice.value = "Tags updated"
            dismissFixTags()
        }
    }

    fun dismissFixTags() {
        _tagTarget.value = null
        _tagResult.value = null
        _tagBusy.value = false
    }

    // ---- Library actions ----

    fun toggleFavorite(track: Track) = viewModelScope.launch {
        library.setFavorite(track.id, !track.isFavorite)
    }

    fun deleteTrack(track: Track) = viewModelScope.launch {
        playback.evictTrack(track.id)
        library.deleteTrack(track)
    }

    // ---- Playlist actions ----

    private val _openPlaylistId = MutableStateFlow<Long?>(null)

    val openPlaylist: StateFlow<PlaylistSummary?> =
        combine(_openPlaylistId, playlists) { id, all -> all.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, started, null)

    val openPlaylistTracks: StateFlow<List<Track>> = _openPlaylistId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else library.observePlaylistTracks(id)
        }
        .stateIn(viewModelScope, started, emptyList())

    fun showPlaylist(playlistId: Long?) { _openPlaylistId.value = playlistId }

    fun createPlaylist(name: String, seedTrackIds: List<Long> = emptyList()) =
        viewModelScope.launch {
            if (name.isBlank()) return@launch
            val id = runCatching { library.createPlaylist(name) }.getOrNull() ?: return@launch
            if (seedTrackIds.isNotEmpty()) library.addToPlaylist(id, seedTrackIds)
        }

    fun addToPlaylist(playlistId: Long, trackId: Long) = viewModelScope.launch {
        library.addToPlaylist(playlistId, listOf(trackId))
    }

    fun removeFromPlaylist(playlistId: Long, trackId: Long) = viewModelScope.launch {
        library.removeFromPlaylist(playlistId, trackId)
    }

    fun deletePlaylist(playlistId: Long) = viewModelScope.launch {
        library.deletePlaylist(playlistId)
    }

    // ---- Editing a track by hand ----

    private val _editTarget = MutableStateFlow<Track?>(null)
    val editTarget: StateFlow<Track?> = _editTarget.asStateFlow()

    fun editTrack(track: Track) { _editTarget.value = track }

    fun dismissEdit() { _editTarget.value = null }

    fun saveTrackDetails(
        track: Track,
        title: String,
        artist: String,
        album: String,
        year: Int?
    ) {
        _editTarget.value = null
        viewModelScope.launch {
            runCatching { library.saveDetails(track, title, artist, album, year) }
        }
    }

    // ---- Zip and ship ----
    //
    // Packs the library or a playlist into one file, reports where it landed,
    // and can hand it straight to another app. Written into the app's own
    // external files directory, which needs no storage permission on any API
    // level and is still a real path a file manager can reach.

    data class ArchiveState(
        val running: Boolean = false,
        val fraction: Float = 0f,
        val current: String = "",
        val note: String? = null,
        val file: File? = null
    )

    private val _archive = MutableStateFlow(ArchiveState())
    val archive: StateFlow<ArchiveState> = _archive.asStateFlow()

    @Volatile private var archiveCancelled = false

    fun zipLibrary() = startArchive("Library") { library.allTracks() }

    fun zipPlaylist(playlist: PlaylistSummary) =
        startArchive(playlist.name) { library.playlistTracksOnce(playlist.id) }

    fun zipSelected(visible: List<Track>) {
        val chosen = selectedTracks(visible)
        clearSelection()
        startArchive("Selection") { chosen }
    }

    private fun startArchive(label: String, load: suspend () -> List<Track>) {
        if (_archive.value.running) return
        archiveCancelled = false
        _archive.value = ArchiveState(running = true)

        viewModelScope.launch {
            val chosen = runCatching { load() }.getOrDefault(emptyList())
            if (chosen.isEmpty()) {
                _archive.value = ArchiveState(note = "Nothing to archive.")
                return@launch
            }

            val context = getApplication<Application>()
            val root = context.getExternalFilesDir(null) ?: context.filesDir
            val stamp = java.time.LocalDate.now().toString()
            val destination = File(
                File(root, "archives"),
                MusicArchive.safeName("Resonate $label $stamp") + ".zip"
            )

            // Numbered so a playlist keeps its order once unpacked, and named
            // with the artist so the folder is navigable rather than a wall of
            // identical-looking files.
            val digits = chosen.size.toString().length
            val entries = chosen.mapIndexed { index, track ->
                val file = File(track.filePath)
                val number = (index + 1).toString().padStart(digits, '0')
                val stem = listOfNotNull(
                    track.artist?.takeIf { it.isNotBlank() }, track.title
                ).joinToString(" - ")
                ArchiveEntry(file, "$number ${MusicArchive.safeName(stem)}.${file.extension}")
            }

            val result = withContext(Dispatchers.IO) {
                MusicArchive.zip(
                    entries = entries,
                    destination = destination,
                    onProgress = { progress ->
                        _archive.value = _archive.value.copy(
                            fraction = progress.fraction,
                            current = progress.currentName
                        )
                    },
                    shouldContinue = { !archiveCancelled }
                )
            }

            _archive.value = result.fold(
                onSuccess = { done ->
                    ArchiveState(
                        note = buildString {
                            append("${done.included} tracks, ")
                            append("%.1f MB".format(done.bytes / 1_048_576.0))
                            if (done.skipped.isNotEmpty()) {
                                append(" - ${done.skipped.size} missing from storage")
                            }
                        },
                        file = done.file
                    )
                },
                onFailure = {
                    ArchiveState(note = it.message ?: "Couldn't build the archive.")
                }
            )
        }
    }

    fun cancelArchive() {
        archiveCancelled = true
        _archive.value = ArchiveState(note = "Cancelled.")
    }

    fun dismissArchive() { _archive.value = ArchiveState() }

    // ---- Selecting several tracks ----
    //
    // Held as a set of ids rather than of Tracks: the list is re-queried
    // constantly as playback counts and favourites change, so holding entities
    // would keep stale copies and break equality against the fresh ones.

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    val selectionMode: StateFlow<Boolean> =
        _selectedIds.map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleSelected(trackId: Long) {
        _selectedIds.value = _selectedIds.value.let {
            if (trackId in it) it - trackId else it + trackId
        }
    }

    fun clearSelection() { _selectedIds.value = emptySet() }

    fun selectAll(tracks: List<Track>) {
        _selectedIds.value = tracks.map { it.id }.toSet()
    }

    /** Resolves the selection to tracks, in the order they appear on screen. */
    fun selectedTracks(visible: List<Track>): List<Track> {
        val chosen = _selectedIds.value
        return visible.filter { it.id in chosen }
    }

    fun favoriteSelected(visible: List<Track>) = viewModelScope.launch {
        val chosen = selectedTracks(visible)
        // One decision for the whole selection: if any are not favourites,
        // favourite everything. Toggling each independently would leave a
        // mixed selection mixed, which is never what was meant.
        val makeFavorite = chosen.any { !it.isFavorite }
        for (track in chosen) {
            if (track.isFavorite != makeFavorite) library.setFavorite(track.id, makeFavorite)
        }
        clearSelection()
    }

    fun addSelectedToPlaylist(playlistId: Long, visible: List<Track>) =
        viewModelScope.launch {
            library.addToPlaylist(playlistId, selectedTracks(visible).map { it.id })
            clearSelection()
        }

    fun deleteSelected(visible: List<Track>) = viewModelScope.launch {
        for (track in selectedTracks(visible)) {
            runCatching { library.deleteTrack(track) }
        }
        clearSelection()
    }

    // ---- Sharing playlists ----
    //
    // Plain text so a playlist can be sent to someone the way any other file is
    // sent. Nothing is uploaded, no account is involved, and an import matches
    // against what is already on the device rather than fetching anything.

    private val _importResult =
        MutableStateFlow<PlaylistImport<Track>?>(null)
    val importResult: StateFlow<PlaylistImport<Track>?> = _importResult.asStateFlow()

    private val _playlistNote = MutableStateFlow<String?>(null)
    val playlistNote: StateFlow<String?> = _playlistNote.asStateFlow()

    fun exportPlaylist(playlist: PlaylistSummary, into: (String, String) -> Boolean) {
        viewModelScope.launch {
            val items = library.playlistTracksOnce(playlist.id)
            val entries = items.map {
                PlaylistEntry(it.artist, it.title, it.durationMs)
            }
            val text = PlaylistFile.export(playlist.name, entries)
            _playlistNote.value = if (into(playlist.name, text)) {
                "Exported ${entries.size} tracks."
            } else {
                "Couldn't write that file."
            }
        }
    }

    fun importPlaylist(text: String, fallbackName: String) {
        viewModelScope.launch {
            val parsed = PlaylistFile.parse(text, fallbackName)
            if (parsed.entries.isEmpty()) {
                _playlistNote.value = "That file had no tracks in it."
                return@launch
            }
            _importResult.value = PlaylistFile.matchAgainst(
                playlist = parsed,
                library = library.allTracks(),
                artistOf = { it.artist },
                titleOf = { it.title },
                durationOf = { it.durationMs }
            )
            _playlistNote.value = null
        }
    }

    fun confirmImport(result: PlaylistImport<Track>) {
        viewModelScope.launch {
            val id = runCatching { library.createPlaylist(result.name) }.getOrNull()
                ?: return@launch
            library.addToPlaylist(id, result.matched.map { it.second.id })
            _importResult.value = null
            _playlistNote.value = buildString {
                append("Added ${result.matched.size} tracks as \"${result.name}\"")
                if (result.missing.isNotEmpty()) {
                    append("; ${result.missing.size} not on this device")
                }
                append(".")
            }
        }
    }

    fun dismissImport() {
        _importResult.value = null
        _playlistNote.value = null
    }

    fun playPlaylist(playlistId: Long, startIndex: Int = 0) = viewModelScope.launch {
        val items = library.playlistTracksOnce(playlistId)
        if (items.isNotEmpty()) playback.play(items, startIndex)
    }

    val libraryIsEmpty: StateFlow<Boolean> = tracks
        .map { it.isEmpty() }
        .stateIn(viewModelScope, started, false)
}
