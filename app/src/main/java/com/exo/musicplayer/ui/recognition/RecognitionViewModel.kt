package com.exo.musicplayer.ui.recognition

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exo.musicplayer.data.recognition.AudioSampler
import com.exo.musicplayer.data.recognition.AudiusProvider
import com.exo.musicplayer.data.recognition.DeezerProvider
import com.exo.musicplayer.data.recognition.GeniusMetadataProvider
import com.exo.musicplayer.data.recognition.InternetArchiveProvider
import com.exo.musicplayer.data.recognition.YouTubeSearchProvider
import com.exo.musicplayer.data.recognition.ITunesProvider
import com.exo.musicplayer.data.download.DownloadOutcome
import com.exo.musicplayer.data.download.DownloadQuality
import com.exo.musicplayer.data.download.YtDlpDownloader
import com.exo.musicplayer.data.recognition.GeniusLyricSearch
import com.exo.musicplayer.data.recognition.MatchRanker
import com.exo.musicplayer.data.recognition.MetadataProviderChain
import com.exo.musicplayer.data.recognition.NeteaseLyricSearch
import com.exo.musicplayer.data.recognition.MusicBrainzProvider
import com.exo.musicplayer.data.recognition.RecognitionResult
import com.exo.musicplayer.data.recognition.ShazamClient
import com.exo.musicplayer.data.youtube.AndroidYouTubeBackend
import com.exo.musicplayer.data.youtube.PipedYouTubeBackend
import com.exo.musicplayer.data.youtube.YouTubePreviewPlayer
import com.exo.musicplayer.data.youtube.YouTubeSearch
import com.exo.musicplayer.data.youtube.YouTubeVideo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class RecognitionStage(val message: String) {
    IDLE(""),
    FETCHING("Fetching the audio…"),
    EXTRACTING("Reading the audio…"),
    IDENTIFYING("Listening…"),
    SEARCHING("Searching…")
}

class RecognitionViewModel(
    private val application: Application
) : AndroidViewModel(application) {

    private val shazam = ShazamClient()

    /**
     * Only built when a link is actually identified.
     *
     * Constructing it is cheap, but the first download unpacks a Python runtime,
     * and nobody who never pastes a link should pay for that.
     */
    private val downloader by lazy { YtDlpDownloader(application) }
    // Every keyless service, queried in parallel. YouTube last only in
    // declaration order; results are interleaved so each service shows up.
    private val search = MetadataProviderChain(
        listOf(
            ITunesProvider(),
            DeezerProvider(),
            MusicBrainzProvider(),
            AudiusProvider(),
            InternetArchiveProvider(),
            GeniusMetadataProvider(),
            YouTubeSearchProvider()
        )
    )

    /**
     * Lyric-text search, kept apart from the catalogue chain.
     *
     * Only these two index the words of a song; sending a remembered line to
     * iTunes or MusicBrainz returns nothing, so mixing them in would add
     * latency and empty rows and nothing else.
     */
    private val lyricSearch = MetadataProviderChain(
        listOf(GeniusLyricSearch(), NeteaseLyricSearch())
    )

    // ---- YouTube search ----
    //
    // A separate list from the catalogue results, because a YouTube hit is a
    // different kind of thing: a video with a channel, a view count and an
    // upload date, not a catalogue making a claim about what a song is.
    // Merging them would mean treating an uploader as an artist, which is how
    // wrong metadata ends up written into tags.
    //
    // Piped is asked first because it answers in well under a second and
    // carries the upload date that the yt-dlp path cannot report. yt-dlp is
    // the fallback and always works, but on a phone it has to start a bundled
    // Python runtime first, so it is noticeably slower.

    private val youtubeBackend by lazy { AndroidYouTubeBackend(downloader) }

    private val youtube by lazy {
        YouTubeSearch(listOf(PipedYouTubeBackend(), youtubeBackend))
    }

    val preview by lazy { YouTubePreviewPlayer(application, viewModelScope) }

    private val _youtubeResults = MutableStateFlow<List<YouTubeVideo>>(emptyList())
    val youtubeResults: StateFlow<List<YouTubeVideo>> = _youtubeResults.asStateFlow()

    private val _youtubeStatus = MutableStateFlow<String?>(null)
    val youtubeStatus: StateFlow<String?> = _youtubeStatus.asStateFlow()

    fun searchYouTube() {
        val text = _query.value.trim()
        if (text.isEmpty()) return
        _sourceLabel.value = null

        viewModelScope.launch {
            _youtubeResults.value = emptyList()
            _stage.value = RecognitionStage.SEARCHING
            _youtubeStatus.value = null

            val result = youtube.search(text, limit = 25)
            _youtubeResults.value = result.videos
            _youtubeStatus.value = when {
                result.videos.isEmpty() ->
                    "Nothing came back. Try updating yt-dlp from the download screen."
                result.skipped.isEmpty() ->
                    "${result.videos.size} results via ${result.via}"
                else ->
                    // Named rather than hidden: a slow search is worth
                    // explaining, and a dead Piped instance is the usual cause.
                    "${result.videos.size} results via ${result.via} — " +
                        "${result.skipped.joinToString(", ")} did not answer"
            }
            _stage.value = RecognitionStage.IDLE
        }
    }

    /** Plays a result without downloading it. Tapping the same row stops it. */
    fun previewYouTube(video: YouTubeVideo) {
        preview.toggle(video.id) { id -> youtubeBackend.audioStreamUrl(id, quality) }
    }

    fun stopPreview() = preview.stop()

    /**
     * The quality a preview is fetched at.
     *
     * Deliberately the lowest rung rather than the download setting: a preview
     * only has to be recognisable, and the smaller rendition starts sooner and
     * costs less of a phone data allowance.
     */
    private val quality = DownloadQuality.LOW

    fun clearYouTube() {
        _youtubeResults.value = emptyList()
        _youtubeStatus.value = null
        preview.stop()
    }

    override fun onCleared() {
        preview.release()
        super.onCleared()
    }

    private val _mode = MutableStateFlow(SearchMode.NAME)
    val mode: StateFlow<SearchMode> = _mode.asStateFlow()

    private val _stage = MutableStateFlow(RecognitionStage.IDLE)
    val stage: StateFlow<RecognitionStage> = _stage.asStateFlow()

    private val _result = MutableStateFlow<RecognitionResult?>(null)
    val result: StateFlow<RecognitionResult?> = _result.asStateFlow()

    /**
     * Artist and title asked for separately, as on the desktop.
     *
     * Two boxes rather than one because it is what lets the ranker score each
     * half against the right field. Given "Bohemian Rhapsody" and "Queen" as
     * one string, a cover uploaded as "Bohemian Rhapsody - Queen" by somebody
     * who is not Queen scores almost as highly as the real recording; told
     * which half is the performer, it does not.
     */
    private val _artist = MutableStateFlow("")
    val artist: StateFlow<String> = _artist.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    fun setArtist(value: String) { _artist.value = value }

    fun setTitle(value: String) { _title.value = value }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _sourceLabel = MutableStateFlow<String?>(null)
    val sourceLabel: StateFlow<String?> = _sourceLabel.asStateFlow()

    fun setQuery(value: String) { _query.value = value }

    fun setMode(value: SearchMode) { _mode.value = value }

    /** Runs whichever search the current mode calls for. */
    fun runSearch() {
        when (_mode.value) {
            SearchMode.NAME -> searchByName()
            SearchMode.LYRICS -> searchByLyrics()
            SearchMode.LINK -> identifyLink()
            SearchMode.YOUTUBE -> searchYouTube()
        }
    }

    /**
     * Pulls the audio behind a link down and fingerprints it.
     *
     * The file is a means to an end, so it goes to the cache directory and is
     * deleted as soon as the fingerprint is taken — identifying a song should
     * not quietly fill the device with videos.
     *
     * Fetched at the lowest quality on purpose: this needs twelve seconds of
     * recognisable audio, not a keepable copy, and on a phone the smaller
     * download is the difference between a few seconds and a wait.
     */
    fun identifyLink(url: String = _query.value) {
        val target = url.trim()
        if (target.isEmpty()) return
        _sourceLabel.value = null
        viewModelScope.launch {
            _result.value = null
            _stage.value = RecognitionStage.FETCHING

            val outcome = runCatching {
                downloader.downloadAudio(target, DownloadQuality.LOW) { _, _, _ -> }
            }.getOrElse { DownloadOutcome.Failed(it.message ?: "Couldn't fetch that link.") }

            when (outcome) {
                is DownloadOutcome.NotReady -> {
                    _stage.value = RecognitionStage.IDLE
                    _result.value = RecognitionResult.Error(
                        "The downloader isn't ready yet. Open the download window once " +
                            "so it can finish setting itself up, then try again."
                    )
                }

                is DownloadOutcome.Failed -> {
                    _stage.value = RecognitionStage.IDLE
                    _result.value = RecognitionResult.Error(outcome.message)
                }

                is DownloadOutcome.Done -> {
                    try {
                        _stage.value = RecognitionStage.EXTRACTING
                        val samples = runCatching {
                            AudioSampler.sampleMono16k(
                                getApplication(),
                                Uri.fromFile(outcome.file)
                            )
                        }.getOrNull()

                        if (samples == null) {
                            _result.value = RecognitionResult.Error(
                                "Downloaded the audio but couldn't decode it."
                            )
                        } else {
                            _stage.value = RecognitionStage.IDENTIFYING
                            _result.value = runCatching { shazam.recognize(samples) }
                                .getOrElse {
                                    RecognitionResult.Error(
                                        it.message ?: "Identification failed."
                                    )
                                }
                        }
                    } finally {
                        // The whole working directory, not just the file, since
                        // yt-dlp may have left thumbnails beside it.
                        runCatching {
                            outcome.file.parentFile?.deleteRecursively()
                                ?: outcome.file.delete()
                        }
                        _stage.value = RecognitionStage.IDLE
                    }
                }
            }
        }
    }

    fun clearResult() {
        _result.value = null
        _sourceLabel.value = null
    }

    /** Fingerprints a snippet of [uri] and asks Shazam what it is. */
    fun identify(uri: Uri, label: String?) {
        _sourceLabel.value = label
        viewModelScope.launch {
            _result.value = null
            _stage.value = RecognitionStage.EXTRACTING
            val samples = runCatching {
                AudioSampler.sampleMono16k(getApplication(), uri)
            }.getOrNull()

            if (samples == null) {
                _stage.value = RecognitionStage.IDLE
                _result.value = RecognitionResult.Error(
                    "Couldn't read any audio from that file. If it's a video, it may " +
                        "have no audio track or use a codec this device can't decode."
                )
                return@launch
            }

            _stage.value = RecognitionStage.IDENTIFYING
            _result.value = runCatching { shazam.recognize(samples) }
                .getOrElse { RecognitionResult.Error(it.message ?: "Identification failed.") }
            _stage.value = RecognitionStage.IDLE
        }
    }

    /**
     * By-name search over the catalogue chain.
     *
     * Uses the two fields when either is filled, and falls back to the single
     * query box otherwise - so a pasted "artist - title" still works.
     */
    fun searchByName() {
        val artistText = _artist.value.trim()
        val titleText = _title.value.trim()
        if (artistText.isNotEmpty() || titleText.isNotEmpty()) {
            searchByFields(artistText, titleText)
            return
        }
        val text = _query.value.trim()
        if (text.isEmpty()) return
        _sourceLabel.value = null
        viewModelScope.launch {
            _result.value = null
            _stage.value = RecognitionStage.SEARCHING
            _result.value = runCatching {
                val found = search.searchAll(text)
                // Each service answers loosely and none can see the others, so
                // relevance is decided here rather than trusting the order they
                // happened to come back in. Without this a search for one song
                // returns another that merely shares a couple of words.
                val matches = MatchRanker.rank(text, found)
                val dropped = found.size - matches.size
                _sourceLabel.value = buildString {
                    append(
                        matches.map { it.provider }.distinct()
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                    )
                    if (dropped > 0) append("  — $dropped unrelated hidden")
                }.takeIf { it.isNotBlank() }
                if (matches.isEmpty()) RecognitionResult.NoMatch
                else RecognitionResult.Found(matches)
            }.getOrElse { RecognitionResult.Error(it.message ?: "Search failed.") }
            _stage.value = RecognitionStage.IDLE
        }
    }

    private fun searchByFields(artistText: String, titleText: String) {
        _sourceLabel.value = null
        viewModelScope.launch {
            _result.value = null
            _stage.value = RecognitionStage.SEARCHING
            _result.value = runCatching {
                // The providers take one string, so the halves are joined for
                // the lookup and separated again only for scoring.
                val combined = listOf(artistText, titleText)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                val found = search.searchAll(combined)
                val matches = MatchRanker.rankSplit(artistText, titleText, found)
                val dropped = found.size - matches.size
                _sourceLabel.value = buildString {
                    append(
                        matches.map { it.provider }.distinct()
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                    )
                    if (dropped > 0) append("  — $dropped unrelated hidden")
                }.takeIf { it.isNotBlank() }
                if (matches.isEmpty()) RecognitionResult.NoMatch
                else RecognitionResult.Found(matches)
            }.getOrElse { RecognitionResult.Error(it.message ?: "Search failed.") }
            _stage.value = RecognitionStage.IDLE
        }
    }

    /**
     * Finds a song from a line of its lyrics.
     *
     * Not ranked: the query is the words of the song, not its name, so scoring
     * the query against titles would throw away every correct answer.
     */
    fun searchByLyrics() {
        val text = _query.value.trim()
        if (text.isEmpty()) return
        _sourceLabel.value = null
        viewModelScope.launch {
            _result.value = null
            _stage.value = RecognitionStage.SEARCHING
            _result.value = runCatching {
                val matches = lyricSearch.searchAll(text, limitPer = 8)
                _sourceLabel.value = matches.map { it.provider }.distinct()
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .takeIf { it.isNotBlank() }
                if (matches.isEmpty()) RecognitionResult.NoMatch
                else RecognitionResult.Found(matches)
            }.getOrElse { RecognitionResult.Error(it.message ?: "Search failed.") }
            _stage.value = RecognitionStage.IDLE
        }
    }
}

/** How the Identify box reads what was typed. */
enum class SearchMode(val label: String, val hint: String) {
    NAME("Name", "Artist and title, in any order"),
    LYRICS("Lyrics", "A line you remember, however roughly"),
    LINK("Link", "TikTok, Instagram, YouTube link"),
    YOUTUBE("YouTube", "Search YouTube for anything")
}
