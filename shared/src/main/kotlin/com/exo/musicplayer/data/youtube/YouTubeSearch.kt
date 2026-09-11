package com.exo.musicplayer.data.youtube

/** One way of asking YouTube what it has. */
interface YouTubeBackend {
    val label: String
    suspend fun search(query: String, limit: Int): List<YouTubeVideo>
}

/**
 * Tries each backend in turn and takes the first that answers.
 *
 * In order, not in parallel, which is the opposite of how the metadata
 * catalogues are queried — and for a different reason. The catalogues are
 * complementary: each knows songs the others do not, so their results are
 * merged. Here every backend is answering the same question about the same
 * index, so a second opinion adds nothing but latency and duplicate rows. The
 * first usable answer wins.
 *
 * The fast backend goes first and the reliable one last.
 */
class YouTubeSearch(private val backends: List<YouTubeBackend>) {

    suspend fun search(query: String, limit: Int = 20): Result {
        if (query.isBlank()) return Result(emptyList(), null)

        val tried = ArrayList<String>(backends.size)
        for (backend in backends) {
            val found = runCatching { backend.search(query, limit) }.getOrDefault(emptyList())
            if (found.isNotEmpty()) {
                return Result(
                    videos = found,
                    via = backend.label,
                    // Only interesting when something had to be skipped, so the
                    // UI can say "Piped was down" rather than staying silent.
                    skipped = tried.toList()
                )
            }
            tried += backend.label
        }
        return Result(emptyList(), null, tried.toList())
    }

    data class Result(
        val videos: List<YouTubeVideo>,
        /** Which backend answered, or null when none did. */
        val via: String?,
        val skipped: List<String> = emptyList()
    )
}
