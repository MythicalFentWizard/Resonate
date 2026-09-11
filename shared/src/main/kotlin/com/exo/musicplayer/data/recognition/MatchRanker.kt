package com.exo.musicplayer.data.recognition

import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * Ranks search results against what the user actually typed.
 *
 * The catalogues are searched in parallel and each returns its own idea of a
 * match, which is where the old behaviour went wrong: merging them untouched
 * meant MusicBrainz answering "nirvana come as you are" with "Hello Darkness My
 * Old Friend" and that landing second in the list. Every provider is entitled to
 * a loose interpretation; deciding which of their answers is worth showing is
 * this file's job, not theirs.
 *
 * The query is broken into tokens and each one is credited against the best
 * field it can be found in, so words can be given in any order and still match —
 * "come as you are nirvana" and "nirvana come as you are" score identically.
 * Three things then separate a real match from a coincidence:
 *
 *  - **coverage** — how much of what was typed the result accounts for;
 *  - **contiguity** — whether those words appear together rather than scattered;
 *  - **artist** — whether the query names the performer, which is how people
 *    distinguish two songs that share words.
 *
 * A token that matches nothing is penalised hard, because a result ignoring part
 * of the query is the exact failure being fixed. Typos are forgiven within one
 * edit for short words and two for long ones, so "bohemain rhapsody" still finds
 * Queen.
 */
object MatchRanker {

    /** Below this a result is treated as unrelated and dropped. */
    const val MIN_SCORE = 0.45

    /**
     * Words that say nothing about which song this is. Dropping them stops
     * "(Official Video)" from padding a match, and stops "the" from carrying
     * weight it has not earned.
     */
    private val NOISE = setOf(
        "official", "video", "audio", "lyrics", "lyric", "hd", "hq", "remaster",
        "remastered", "explicit", "clean", "visualizer", "mv", "full", "album",
        "version", "feat", "ft", "featuring", "with", "the", "a", "an", "and"
    )

    private val BRACKETED = Regex("""\(.*?\)|\[.*?]""")
    private val SEPARATORS = Regex("""[^a-z0-9]+""")

    fun tokens(text: String?, stripBracketed: Boolean = true): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        var lowered = text.lowercase(Locale.ROOT)
        if (stripBracketed) lowered = lowered.replace(BRACKETED, " ")
        return lowered.split(SEPARATORS).filter { it.isNotEmpty() && it !in NOISE }
    }

    /**
     * Sorts by relevance and drops what does not clear [minScore].
     *
     * Ties are broken by the plainest title, so the studio recording comes
     * before the live cut and the twelve-word remix edit.
     */
    fun rank(
        query: String,
        matches: List<MusicMatch>,
        minScore: Double = MIN_SCORE
    ): List<MusicMatch> {
        if (query.isBlank()) return matches
        val scored = matches.map { it to score(query, it) }
        val kept = scored.filter { it.second >= minScore }

        // If nothing clears the bar the query was probably unusual rather than
        // wrong, so the best few are shown instead of an empty screen.
        val pool = kept.ifEmpty { scored.sortedByDescending { it.second }.take(5) }

        return pool.sortedWith(
            compareByDescending<Pair<MusicMatch, Double>> { it.second }
                .thenBy { tokens(it.first.title, stripBracketed = false).size }
        ).map { it.first }
    }

    /**
     * Ranks results when the artist and the title were given separately.
     *
     * Strictly better than the single-box form, because the ambiguity it has to
     * guess at is simply gone: "queen" is known to be the performer, so a cover
     * literally titled "Bohemian Rhapsody - Queen" by somebody else no longer
     * scores at all, where the combined query cannot tell the two apart.
     *
     * Either field may be left empty — searching by artist alone to browse, or
     * by title alone when the performer is the thing you have forgotten — and
     * the scoring falls back to the combined form in that case.
     */
    fun rankSplit(
        artistQuery: String,
        titleQuery: String,
        matches: List<MusicMatch>,
        minScore: Double = MIN_SCORE
    ): List<MusicMatch> {
        if (artistQuery.isBlank() || titleQuery.isBlank()) {
            return rank(listOf(artistQuery, titleQuery).filter { it.isNotBlank() }
                .joinToString(" "), matches, minScore)
        }
        val scored = matches.map { it to scoreSplit(artistQuery, titleQuery, it) }
        val kept = scored.filter { it.second >= minScore }
        val pool = kept.ifEmpty { scored.sortedByDescending { it.second }.take(5) }
        return pool.sortedWith(
            compareByDescending<Pair<MusicMatch, Double>> { it.second }
                .thenBy { tokens(it.first.title, stripBracketed = false).size }
        ).map { it.first }
    }

    /**
     * Scores a result against a named artist and a named title.
     *
     * Each half is checked against the field it belongs to, and both have to
     * hold up: a right title by the wrong artist and a right artist with the
     * wrong title are both wrong, so the two coverages are combined in a way
     * that a single strong half cannot rescue. The title carries slightly more
     * weight than the artist, because artists get abbreviated ("beatles") far
     * more often than titles do.
     */
    fun scoreSplit(artistQuery: String, titleQuery: String, match: MusicMatch): Double {
        val wantedArtist = tokens(artistQuery)
        val wantedTitle = tokens(titleQuery)
        if (wantedArtist.isEmpty() && wantedTitle.isEmpty()) return 0.0

        val haveArtist = tokens(match.artist)
        val haveTitle = tokens(match.title)

        // The artist is also looked for in the album, because compilations and
        // soundtracks routinely credit "Various Artists" on the track itself.
        val artistFields = haveArtist + tokens(match.album)

        val artistCoverage = coverageOf(wantedArtist, artistFields)
        val titleCoverage = coverageOf(wantedTitle, haveTitle)

        // Neither half may be waved through. Below this a field is not a near
        // miss, it is a different song or a different performer.
        if (artistCoverage < 0.34 || titleCoverage < 0.34) return 0.0

        val phrase = phraseScore(wantedTitle, emptyList(), haveTitle)
        return (0.42 * artistCoverage + 0.48 * titleCoverage + 0.10 * phrase)
            .coerceIn(0.0, 1.0)
    }

    /** Mean best-match score of [wanted] against [fields]. */
    private fun coverageOf(wanted: List<String>, fields: List<String>): Double {
        if (wanted.isEmpty()) return 1.0
        if (fields.isEmpty()) return 0.0
        return wanted.sumOf { tokenScore(it, fields) } / wanted.size
    }

    /**
     * Fuzzy coverage of one token list by another, from 0 to 1.
     *
     * The same per-word matching the ranker uses, forgiving typos and prefixes,
     * exposed so the YouTube link finder scores titles with this implementation
     * instead of growing a second one that would drift from it.
     */
    fun coverage(wanted: List<String>, fields: List<String>): Double =
        coverageOf(wanted, fields)

    fun score(query: String, match: MusicMatch): Double =
        score(query, match.artist, match.title, match.album)

    fun score(query: String, artist: String?, title: String, album: String? = null): Double {
        val queryTokens = tokens(query)
        if (queryTokens.isEmpty()) return 0.0

        val artistTokens = tokens(artist)
        val titleTokens = tokens(title)
        if (artistTokens.isEmpty() && titleTokens.isEmpty()) return 0.0
        val allFields = artistTokens + titleTokens + tokens(album)

        val perToken = queryTokens.map { tokenScore(it, allFields) }
        val coverage = perToken.sum() / queryTokens.size
        val missing = perToken.count { it < 0.5 }.toDouble() / queryTokens.size

        val artistBonus = if (artistTokens.isEmpty()) {
            0.0
        } else {
            queryTokens.sumOf { tokenScore(it, artistTokens) } / queryTokens.size
        }

        val score = 0.60 * coverage +
            0.25 * phraseScore(queryTokens, artistTokens, titleTokens) +
            0.15 * artistBonus -
            0.55 * missing

        return score.coerceIn(0.0, 1.0)
    }

    /** How well one query word is accounted for by any field of the result. */
    private fun tokenScore(query: String, candidates: List<String>): Double {
        var best = 0.0
        for (candidate in candidates) {
            when {
                query == candidate -> return 1.0
                candidate.startsWith(query) || query.startsWith(candidate) ->
                    best = maxOf(best, 0.85)
                query.length >= 4 && (candidate.contains(query) || query.contains(candidate)) ->
                    best = maxOf(best, 0.65)
                withinTypoDistance(query, candidate) -> best = maxOf(best, 0.7)
            }
        }
        return best
    }

    /**
     * Whether the query words appear together rather than scattered.
     *
     * The artist is allowed to sit on either side of the title, so "bohemian
     * rhapsody queen" reads as contiguous against Queen's own recording. Without
     * that, a cover literally titled "Bohemian Rhapsody - Queen" scores higher
     * than the original, which is precisely backwards.
     */
    private fun phraseScore(
        query: List<String>,
        artist: List<String>,
        title: List<String>
    ): Double {
        val joinedQuery = query.joinToString(" ")
        if (joinedQuery.isEmpty()) return 0.0

        val candidates = listOf(
            title.joinToString(" "),
            (title + artist).joinToString(" "),
            (artist + title).joinToString(" ")
        )
        if (candidates.any { it.contains(joinedQuery) }) return 1.0
        if (query.size < 2) return 0.0

        // Partial credit for adjacent pairs, so a mostly-contiguous match still
        // beats one where the same words are strewn across the fields.
        val whole = (artist + title).joinToString(" ")
        val pairs = (0 until query.lastIndex).map { "${query[it]} ${query[it + 1]}" }
        return pairs.count { whole.contains(it) }.toDouble() / pairs.size * 0.6
    }

    private fun withinTypoDistance(a: String, b: String): Boolean {
        val shortest = min(a.length, b.length)
        if (shortest < 4) return false
        val cap = if (shortest < 8) 1 else 2
        return editDistance(a, b, cap) <= cap
    }

    /**
     * Levenshtein distance, abandoned as soon as it is certain to exceed [cap].
     *
     * Bounded because this runs once per query token per result, and the answer
     * "further than we care about" is reached far sooner than the exact figure.
     */
    private fun editDistance(a: String, b: String, cap: Int): Int {
        if (abs(a.length - b.length) > cap) return cap + 1

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            var rowBest = current[0]
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
                rowBest = min(rowBest, current[j])
            }
            if (rowBest > cap) return cap + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
