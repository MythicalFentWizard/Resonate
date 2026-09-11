package com.exo.musicplayer.data.youtube

import com.exo.musicplayer.data.recognition.MatchRanker
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10

/**
 * Picks the YouTube video that is actually the song, so yt-dlp is handed a real
 * link rather than a search.
 *
 * "Find & download" used to pass `ytsearch1:artist title` to yt-dlp, which went
 * wrong in two separate ways. On Android it never reached yt-dlp at all: the
 * download path refused anything that did not start with http before yt-dlp saw
 * it. And where it did run, the first search result is not the song. Measured
 * against live YouTube while this was written:
 *
 *  - `an4rch martine rose` ranks a 73-second "[speed up]" re-upload first.
 *  - `whatsaheart howling` returns, beside the official upload, an
 *    "(Official Instrumental)" and an "(8d Audio)" that are both exactly the
 *    song's length, so neither position nor duration is enough on its own.
 *  - Two different songs are called "Martine Rose", at 87 and 186 seconds, and
 *    only the catalogue's duration tells them apart.
 *  - The official whatsaheart upload is titled just "howling", with the artist
 *    only in the channel name, and "an4rch" is an alias that appears in none of
 *    the correct asteria uploads. So an artist can raise a score but can never
 *    be required.
 *
 * The rules that follow: the title has to match; edits are refused outright
 * unless the search itself asked for one; a known duration refuses cuts,
 * previews and concert rips; and among whatever is left, the artist, the
 * standing of the channel and the view count decide.
 *
 * When nothing survives, nothing is downloaded. Saving the wrong song silently
 * is worse than saying so, because it looks like it worked.
 */
class YouTubeLinkFinder(private val search: YouTubeSearch) {

    /** What is being looked for. */
    data class Wanted(
        /** The song title, or the whole query when the artist is not known separately. */
        val title: String,
        val artist: String? = null,
        /** From the catalogue match, when there is one. The best defence against edits. */
        val durationMs: Long? = null
    ) {
        val query: String
            get() = listOfNotNull(artist?.trim()?.takeIf { it.isNotEmpty() }, title.trim())
                .joinToString(" ")
    }

    /** One candidate and the verdict on it. */
    data class Scored(
        val video: YouTubeVideo,
        val score: Double,
        /** Why it was refused outright, or null when it is eligible. */
        val rejected: String? = null
    )

    sealed interface Outcome {
        data class Found(val pick: Scored, val via: String?) : Outcome
        data class NothingSuitable(val message: String, val closest: Scored?) : Outcome
    }

    suspend fun find(wanted: Wanted, limit: Int = 15): Outcome {
        val query = wanted.query
        if (query.isBlank()) return Outcome.NothingSuitable("Nothing to search for.", null)

        val result = search.search(query, limit)
        if (result.videos.isEmpty()) {
            return Outcome.NothingSuitable(
                "YouTube returned nothing for \"$query\". yt-dlp may need updating.",
                null
            )
        }

        val ranked = rank(wanted, result.videos)
        val best = ranked.firstOrNull { it.rejected == null && it.score >= MIN_SCORE }
        return if (best != null) {
            Outcome.Found(best, result.via)
        } else {
            Outcome.NothingSuitable(
                "None of the ${result.videos.size} YouTube results for \"$query\" looked like " +
                    "the original song. They were edits, covers or different songs, so " +
                    "nothing was downloaded. Pick one yourself in Identify, YouTube tab.",
                ranked.firstOrNull()
            )
        }
    }

    companion object {

        /** Below this, nothing is downloaded rather than something wrong. */
        const val MIN_SCORE = 0.5

        /** Share of the wanted title that has to appear before a video counts as that song. */
        private const val TITLE_GATE = 0.75

        /**
         * Words that mark a video as a version of the song rather than the song.
         *
         * Matched as whole words against the full title, brackets included,
         * because that is exactly where "(slowed)" and "[speed up]" live. Any
         * marker that also appears in what was asked for is ignored, so a search
         * for "bohemian rhapsody live aid" can still land on the live version.
         */
        private val EDIT_MARKERS = listOf(
            "sped up", "speed up", "spedup", "speedup", "slowed", "slow", "reverb",
            "nightcore", "daycore", "8d", "bass boosted", "remix", "cover", "karaoke",
            "instrumental", "acapella", "cappella", "live", "concert", "performance",
            "hour", "hours", "loop", "extended", "mashup", "reaction", "tutorial",
            "lesson", "pitch", "pitched", "chipmunk", "amv", "edit", "flashmob",
            "flash mob", "type beat", "acoustic", "tiktok", "phonk", "lofi", "lo fi",
            "fanmade", "fan made", "snippet", "preview", "teaser", "leak", "leaked", "demo"
        )

        private val SEPARATORS = Regex("""[^a-z0-9]+""")
        private val YEAR = Regex("""^(19|20)\d{2}$""")

        fun rank(wanted: Wanted, videos: List<YouTubeVideo>): List<Scored> =
            videos.map { score(wanted, it) }
                .sortedWith(
                    compareBy<Scored> { it.rejected != null }.thenByDescending { it.score }
                )

        fun score(wanted: Wanted, video: YouTubeVideo): Scored {
            val wantedArtist = MatchRanker.tokens(wanted.artist)
            // Brackets and years dropped from what is wanted: catalogues append
            // "(Remastered 2011)" and the like, which no upload repeats word for
            // word, and requiring it would refuse the right video.
            val wantedTitle = MatchRanker.tokens(wanted.title).filterNot { YEAR.matches(it) }
            if (wantedTitle.isEmpty() && wantedArtist.isEmpty()) {
                return Scored(video, 0.0, "nothing to match against")
            }
            val splitMode = wantedArtist.isNotEmpty()

            val isTopic = video.channel.endsWith(" - Topic")
            val channelTokens = MatchRanker.tokens(video.channel.removeSuffix(" - Topic"))
            // Brackets kept here, unlike on the wanted side: "(Live Aid 1985)" is
            // part of what the video is.
            val titleTokens = MatchRanker.tokens(video.title, stripBracketed = false)
            val rawTitle = rawTokens(video.title)

            if (video.isShort || "shorts" in rawTitle) {
                return Scored(video, 0.0, "a Short")
            }

            val asked = padded(rawTokens(wanted.artist) + rawTokens(wanted.title))
            val titleText = padded(rawTitle)
            EDIT_MARKERS.firstOrNull { marker ->
                titleText.contains(" $marker ") && !asked.contains(" $marker ")
            }?.let { marker ->
                return Scored(video, 0.0, "an edit (\"$marker\")")
            }

            // With the artist given separately the title is checked against the
            // video title alone; from a single box of text, the channel counts
            // too, since "whatsaheart howling" puts half of itself there.
            val titleCoverage = if (splitMode) {
                MatchRanker.coverage(wantedTitle, titleTokens)
            } else {
                MatchRanker.coverage(wantedTitle, titleTokens + channelTokens)
            }
            if (titleCoverage < TITLE_GATE) {
                return Scored(video, 0.0, "a different song")
            }

            val durationFit = durationFit(wanted.durationMs, video.durationSeconds)
            if (durationFit is Fit.Refused) {
                return Scored(video, 0.0, durationFit.reason)
            }

            val artistCoverage = if (splitMode) {
                MatchRanker.coverage(wantedArtist, titleTokens + channelTokens)
            } else {
                titleCoverage
            }

            // The artist's own channel, and better still their auto-generated
            // "Artist - Topic" channel, which carries the released audio itself.
            val channelIsArtist = channelTokens.isNotEmpty() && if (splitMode) {
                MatchRanker.coverage(wantedArtist, channelTokens) >= 0.8
            } else {
                MatchRanker.coverage(channelTokens, wantedTitle) >= 0.8
            }
            val authority = (
                (if (channelIsArtist) (if (isTopic) 1.0 else 0.8) else 0.0) +
                    (if (video.channelVerified) 0.2 else 0.0)
                ).coerceAtMost(1.0)

            // Log-scaled so 2 billion views beats 50 thousand without making
            // popularity the deciding factor between two correct uploads.
            val popularity = video.viewCount
                ?.let { (log10(it + 1.0) / 10.0).coerceIn(0.0, 1.0) }
                ?: 0.0

            // Words in the title nobody asked for, like "Soundcloud Exclusive".
            // Noise words such as "official" and "lyrics" do not count.
            val explained = (wantedArtist + wantedTitle + channelTokens).toSet()
            val meaningful = rawTitle.filter { MatchRanker.tokens(it).isNotEmpty() }
            val unexplained = if (meaningful.isEmpty()) {
                0.0
            } else {
                meaningful.count { it !in explained }.toDouble() / meaningful.size
            }

            val fit = (durationFit as? Fit.Score)?.value ?: 0.5
            val score = 0.40 * titleCoverage +
                0.20 * artistCoverage +
                0.15 * fit +
                0.10 * authority +
                0.10 * popularity +
                0.05 * (1.0 - unexplained)

            return Scored(video, score.coerceIn(0.0, 1.0))
        }

        private sealed interface Fit {
            data class Score(val value: Double) : Fit
            data class Refused(val reason: String) : Fit
            data object Unknown : Fit
        }

        /**
         * How well a video's length fits the song's, when both are known.
         *
         * Asymmetric on purpose. A video longer than the track is usually the
         * same recording with an intro or an outro, so it is tolerated a long
         * way. A video shorter than the track is almost never the song: it is
         * sped up, cut down or a preview.
         */
        private fun durationFit(wantedMs: Long?, videoSeconds: Int?): Fit {
            if (wantedMs == null || wantedMs <= 0 || videoSeconds == null || videoSeconds <= 0) {
                return Fit.Unknown
            }
            val want = wantedMs / 1000.0
            val have = videoSeconds.toDouble()
            val ratio = (have - want) / want
            return when {
                ratio < -0.15 ->
                    Fit.Refused("shorter than the song (${YouTubeFormat.duration(videoSeconds)})")
                ratio > 0.60 ->
                    Fit.Refused("much longer than the song (${YouTubeFormat.duration(videoSeconds)})")
                abs(have - want) <= 4 || abs(ratio) <= 0.03 -> Fit.Score(1.0)
                abs(ratio) <= 0.10 -> Fit.Score(0.7)
                abs(ratio) <= 0.25 -> Fit.Score(0.35)
                else -> Fit.Score(0.1)
            }
        }

        private fun rawTokens(text: String?): List<String> =
            text.orEmpty().lowercase(Locale.ROOT).split(SEPARATORS).filter { it.isNotEmpty() }

        private fun padded(tokens: List<String>): String = " " + tokens.joinToString(" ") + " "
    }
}
