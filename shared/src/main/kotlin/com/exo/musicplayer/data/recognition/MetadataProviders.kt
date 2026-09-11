package com.exo.musicplayer.data.recognition

import com.exo.musicplayer.data.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReferenceArray

/** One place track names, tags and cover art can come from. */
interface MetadataProvider {
    val label: String
    suspend fun search(query: String, limit: Int): List<MusicMatch>
}

/**
 * Tries each provider until one returns usable results.
 *
 * The three have genuinely different coverage, which is the point: iTunes is
 * strongest on mainstream Western releases, Deezer often has tracks and cover
 * art iTunes lacks, and MusicBrainz covers the long tail of obscure and
 * non-commercial releases that neither store carries.
 *
 * [findArtwork] additionally skips results with no image, because a
 * perfect title match that carries no cover is useless to the cover refresh.
 */
class MetadataProviderChain(private val providers: List<MetadataProvider>) {

    /**
     * Queries every provider at once and merges the results.
     *
     * Different from [search], which stops at the first provider that answers.
     * For a browsable catalogue the point is breadth: iTunes and Deezer cover
     * commercial releases, MusicBrainz the long tail, Audius independent
     * artists, Internet Archive live and public-domain recordings. One provider
     * being slow or down just means fewer rows, never an empty list.
     */
    suspend fun searchAll(query: String, limitPer: Int = 6): List<MusicMatch> = coroutineScope {
        val batches = providers.map { provider ->
            async {
                runCatching { provider.search(query, limitPer) }
                    .getOrDefault(emptyList())
                    .map { if (it.provider.isBlank()) it.copy(provider = provider.label) else it }
            }
        }.awaitAll()

        // Interleaved so the list opens with one row per service, rather than
        // twenty from whichever provider happened to answer first.
        val queues = batches.filter { it.isNotEmpty() }.map { it.toMutableList() }
        val merged = mutableListOf<MusicMatch>()
        while (queues.any { it.isNotEmpty() }) {
            for (queue in queues) if (queue.isNotEmpty()) merged += queue.removeAt(0)
        }
        merged.distinctBy { "${it.artist.orEmpty()}|${it.title}".lowercase() }
    }

    suspend fun search(query: String, limit: Int = 20): Pair<List<MusicMatch>, String?> {
        for (provider in providers) {
            val results = runCatching { provider.search(query, limit) }.getOrDefault(emptyList())
            if (results.isNotEmpty()) return results to provider.label
        }
        return emptyList<MusicMatch>() to null
    }

    /**
     * Where the lookups run. Deliberately not the caller's scope: once a song is
     * settled, a service still thinking is left to finish on its own instead of
     * holding the song up until it answers.
     */
    private val lookups = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** What [askAll]'s caller makes of the answers so far. */
    sealed interface Verdict<out T> {
        /** Not enough to go on yet: look again when another provider answers. */
        data object Wait : Verdict<Nothing>

        /** Settled, with or without a result. */
        data class Done<out T>(val value: T?) : Verdict<T>
    }

    /**
     * Asks every provider at the same time and hands [decide] the answers each
     * time one arrives: in provider order, null for a provider still thinking.
     *
     * The old chain asked them one after another, so every song waited on each
     * service in turn: on a real 521-track library that was 6.5 s a song, with
     * MusicBrainz alone sometimes taking 16 s where the stores took 0.3 s.
     *
     * `patient` holds until [patienceMs] has passed, while a better-placed
     * provider is still worth waiting for; after that the best answer in hand
     * should be used. `last` means nothing more is coming - everyone answered,
     * or [giveUpMs] passed - so the verdict has to be final.
     */
    suspend fun <T : Any> askAll(
        query: String,
        patienceMs: Long = 1_500,
        giveUpMs: Long = 15_000,
        decide: suspend (answers: List<List<MusicMatch>?>, patient: Boolean, last: Boolean) -> Verdict<T>
    ): T? {
        val results = AtomicReferenceArray<List<MusicMatch>?>(providers.size)
        // Only wakes the loop. Every pass re-reads the results, so a wake-up
        // that is missed loses nothing.
        val wake = Channel<Unit>(Channel.CONFLATED)
        providers.forEachIndexed { index, provider ->
            lookups.launch {
                results.set(index, runCatching { provider.search(query, 5) }.getOrDefault(emptyList()))
                wake.trySend(Unit)
            }
        }

        val started = System.currentTimeMillis()
        try {
            while (true) {
                val elapsed = System.currentTimeMillis() - started
                val answers = List(providers.size) { results.get(it) }
                val last = answers.none { it == null } || elapsed >= giveUpMs
                when (val verdict = decide(answers, elapsed < patienceMs, last)) {
                    is Verdict.Done -> return verdict.value
                    Verdict.Wait -> if (last) return null
                }
                val now = System.currentTimeMillis() - started
                withTimeoutOrNull((if (now < patienceMs) patienceMs else giveUpMs) - now) {
                    wake.receive()
                }
            }
        } finally {
            wake.close()
        }
    }

    /**
     * Cover art for [query].
     *
     * Each candidate goes to [use], which fetches it and returns null if that
     * failed. A dead link - Cover Art Archive hands them out for most releases
     * it has no art for - then moves on to the next provider's image instead of
     * ending the search with nothing.
     */
    suspend fun <T : Any> findArtwork(
        query: String,
        patienceMs: Long = 1_500,
        giveUpMs: Long = 15_000,
        use: suspend (url: String) -> T?
    ): T? {
        val tried = HashSet<String>()
        return askAll(query, patienceMs, giveUpMs) { answers, patient, last ->
            for (answer in answers) {
                if (answer == null) {
                    // A better-placed provider is still thinking and there is
                    // still time to wait for it.
                    if (patient) return@askAll Verdict.Wait
                    continue
                }
                val url = answer.firstNotNullOfOrNull { it.artworkUrl } ?: continue
                if (!tried.add(url)) continue
                use(url)?.let { return@askAll Verdict.Done(it) }
            }
            if (last) Verdict.Done(null) else Verdict.Wait
        }
    }

    /** A song's details, each field from the best-placed provider that has it. */
    data class SongDetails(
        val title: String?,
        val artist: String?,
        val album: String?,
        val year: Int?
    )

    /**
     * Title, artist, album and year for a song.
     *
     * Each field comes from the best-placed provider that has it for this song,
     * so the album can come from a store even when YouTube, placed first, knew
     * the song: YouTube has no albums. A result only counts when it scores as
     * the same song and its title covers the song's own, so a loose MusicBrainz
     * hit for a different song that shares a word is ignored rather than
     * written into the file.
     *
     * An ordinary YouTube upload's title is whatever the uploader typed and its
     * "artist" is the channel, so only auto-generated "Artist - Topic" uploads,
     * which carry the release's own title and artist, count at all.
     */
    suspend fun findDetails(
        artist: String?,
        title: String,
        patienceMs: Long = 1_500,
        giveUpMs: Long = 15_000
    ): SongDetails? {
        val query = listOfNotNull(artist?.takeIf { it.isNotBlank() }, title).joinToString(" ")
        val wantedTitle = MatchRanker.tokens(title)

        fun sameSong(index: Int, answer: List<MusicMatch>): MusicMatch? {
            val youTube = providers[index].label == YOUTUBE
            return answer
                .filter { !youTube || it.artist.orEmpty().endsWith(TOPIC) }
                .map { it to MatchRanker.score(query, it) }
                .filter { (match, score) ->
                    score >= DETAILS_MIN_SCORE &&
                        MatchRanker.coverage(wantedTitle, MatchRanker.tokens(match.title)) >=
                        DETAILS_TITLE_GATE
                }
                .maxByOrNull { it.second }
                ?.first
                ?.let {
                    if (youTube) {
                        it.copy(artist = it.artist?.removeSuffix(TOPIC), album = null, releaseYear = null)
                    } else {
                        it
                    }
                }
        }

        return askAll(query, patienceMs, giveUpMs) { answers, patient, last ->
            val matches = answers.mapIndexed { index, answer -> answer?.let { sameSong(index, it) } }
            var waiting = false

            // The best-placed provider whose match has this field. While patient,
            // a provider still thinking blocks everything placed below it.
            fun <V : Any> best(field: (MusicMatch) -> V?): V? {
                var pending = false
                for (index in answers.indices) {
                    if (answers[index] == null) {
                        pending = true
                        if (patient) break else continue
                    }
                    matches[index]?.let(field)?.let { return it }
                }
                if (pending) waiting = true
                return null
            }

            val details = SongDetails(
                title = best { it.title.takeIf(String::isNotBlank) },
                artist = best { it.artist?.takeIf(String::isNotBlank) },
                album = best { it.album?.takeIf(String::isNotBlank) },
                year = best { it.releaseYear }
            )
            val empty = details == SongDetails(null, null, null, null)
            when {
                // Past patience a field nobody fast has (Deezer gives no years,
                // YouTube no albums) is not worth holding the song for, once
                // anything at all has been found.
                waiting && !last && (patient || empty) -> Verdict.Wait
                empty -> Verdict.Done(null)
                else -> Verdict.Done(details)
            }
        }
    }

    private companion object {
        /** Results scoring below this are a different song, not a near miss. */
        const val DETAILS_MIN_SCORE = 0.6

        /** Share of the song's own title words a result's title has to contain. */
        const val DETAILS_TITLE_GATE = 0.75

        const val YOUTUBE = "YouTube"
        const val TOPIC = " - Topic"
    }
}

/** Primary: no key, no rate-limit registration, ships 400px art. */
class ITunesProvider(private val client: MusicSearchClient = MusicSearchClient()) :
    MetadataProvider {

    override val label = "iTunes"

    override suspend fun search(query: String, limit: Int): List<MusicMatch> {
        val result = client.search(query, limit)
        return (result as? RecognitionResult.Found)?.matches.orEmpty()
            .map { it.copy(provider = label) }
    }
}

/** Secondary: free, no key, and its cover art is often higher resolution. */
class DeezerProvider : MetadataProvider {

    override val label = "Deezer"

    override suspend fun search(query: String, limit: Int): List<MusicMatch> =
        withContext(Dispatchers.IO) {
            val body = Http.get(
                "https://api.deezer.com/search?q=${encodeQuery(query)}&limit=$limit"
            ) ?: return@withContext emptyList()

            val data = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull()
                ?: return@withContext emptyList()

            (0 until data.length()).mapNotNull { index ->
                val item = data.optJSONObject(index) ?: return@mapNotNull null
                val title = item.optString("title").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val album = item.optJSONObject("album")
                MusicMatch(
                    title = title,
                    artist = item.optJSONObject("artist")?.optString("name")
                        ?.takeIf { it.isNotBlank() },
                    album = album?.optString("title")?.takeIf { it.isNotBlank() },
                    artworkUrl = album?.optString("cover_xl")?.takeIf { it.isNotBlank() }
                        ?: album?.optString("cover_big")?.takeIf { it.isNotBlank() },
                    durationMs = item.optLong("duration", 0L).takeIf { it > 0 }?.times(1000),
                    source = MusicMatch.Source.SEARCH,
                    provider = label,
                    drmProtected = true
                )
            }
        }
}

/**
 * Tertiary: MusicBrainz for the metadata, Cover Art Archive for the image.
 *
 * Slower than the two stores — it needs a second request per release to know
 * whether art exists — but it is the only one of the three that covers
 * bootlegs, regional releases and self-published tracks.
 */
class MusicBrainzProvider : MetadataProvider {

    override val label = "MusicBrainz"

    override suspend fun search(query: String, limit: Int): List<MusicMatch> =
        withContext(Dispatchers.IO) {
            val body = Http.get(
                "https://musicbrainz.org/ws/2/recording" +
                    "?query=${encodeQuery(query)}&fmt=json&limit=$limit"
            ) ?: return@withContext emptyList()

            val recordings = runCatching {
                JSONObject(body).optJSONArray("recordings")
            }.getOrNull() ?: return@withContext emptyList()

            (0 until recordings.length()).mapNotNull { index ->
                val item = recordings.optJSONObject(index) ?: return@mapNotNull null
                val title = item.optString("title").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val artist = item.optJSONArray("artist-credit")
                    ?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() }
                val release = item.optJSONArray("releases")?.optJSONObject(0)
                val releaseId = release?.optString("id")?.takeIf { it.isNotBlank() }

                MusicMatch(
                    title = title,
                    artist = artist,
                    album = release?.optString("title")?.takeIf { it.isNotBlank() },
                    // The Archive 404s for releases with no art; that is exactly
                    // what a null artworkUrl should mean, so it is left to the
                    // image loader to discover rather than pre-checked here.
                    artworkUrl = releaseId?.let {
                        "https://coverartarchive.org/release/$it/front-500"
                    },
                    releaseYear = item.optString("first-release-date").take(4).toIntOrNull(),
                    durationMs = item.optLong("length", 0L).takeIf { it > 0 },
                    source = MusicMatch.Source.SEARCH,
                    provider = label
                )
            }
        }
}

private fun encodeQuery(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8")
