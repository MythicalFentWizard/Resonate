package com.exo.musicplayer.data.youtube

/**
 * One row of a YouTube search.
 *
 * Deliberately a separate type from MusicMatch. A MusicMatch is a claim about
 * what a song *is* — artist, album, year — assembled from catalogues that agree
 * on those fields. A YouTube result is a video: it has a channel rather than an
 * artist, a view count, an upload date, and a title that may be anything from
 * "Artist - Song (Official Video)" to "song i found at 3am". Squeezing it into
 * MusicMatch would mean pretending the uploader is the artist, which is exactly
 * the kind of wrong-but-plausible metadata that ends up written into tags.
 *
 * Fields that a given backend cannot supply are null rather than invented. The
 * yt-dlp path has no upload date or channel avatar in flat mode; the row simply
 * omits them instead of showing a fabricated "recently".
 */
data class YouTubeVideo(
    val id: String,
    val title: String,
    val channel: String,
    val channelVerified: Boolean = false,
    val channelAvatarUrl: String? = null,
    val viewCount: Long? = null,
    /** Human text as the source phrased it, e.g. "2 months ago". */
    val uploadedText: String? = null,
    val description: String? = null,
    val durationSeconds: Int? = null,
    /** Shorts are usually not what someone searching for a song wants. */
    val isShort: Boolean = false,
    /** Which backend answered, for the "via" note under the results. */
    val source: String = ""
) {
    val watchUrl: String get() = "https://www.youtube.com/watch?v=$id"

    /**
     * Thumbnail URL, built from the video id rather than taken from whichever
     * backend answered.
     *
     * Piped hands back a URL through its own image proxy, which adds a hop and
     * dies with the instance; yt-dlp hands back a signed i.ytimg.com URL with
     * expiring query parameters. The unsigned canonical path needs neither -
     * verified reachable directly, and present at every size for old and new
     * videos alike.
     */
    fun thumbnail(size: ThumbSize = ThumbSize.WIDE): String =
        "https://i.ytimg.com/vi/$id/${size.file}"

    enum class ThumbSize(val file: String) {
        /** 320x180. Enough for a phone row, a tenth the bytes of the big one. */
        SMALL("mqdefault.jpg"),
        /** 1280x720, for the wider cards on desktop. */
        WIDE("hq720.jpg")
    }

    /** "236K views · 2 months ago", skipping whichever half is missing. */
    val subtitle: String
        get() = listOfNotNull(
            viewCount?.let { YouTubeFormat.views(it) },
            uploadedText
        ).joinToString(" · ")
}

/** Number and duration formatting, in YouTube's own shorthand. */
object YouTubeFormat {

    /**
     * "236K views", "1.2M views", "847 views".
     *
     * One decimal place only below 10 of a unit, which is what YouTube does and
     * what keeps the string short enough to sit on one line next to a date.
     */
    fun views(count: Long): String {
        val text = when {
            count < 1_000 -> count.toString()
            count < 1_000_000 -> compact(count / 1_000.0, "K")
            count < 1_000_000_000 -> compact(count / 1_000_000.0, "M")
            else -> compact(count / 1_000_000_000.0, "B")
        }
        return if (count == 1L) "1 view" else "$text views"
    }

    private fun compact(value: Double, unit: String): String =
        if (value < 10) {
            // 1.2K, but 1K rather than 1.0K - a trailing .0 is noise.
            val rounded = (value * 10).toLong() / 10.0
            if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()}$unit"
            else "$rounded$unit"
        } else {
            "${value.toLong()}$unit"
        }

    /** "1:49", or "1:02:03" once there is an hour to show. */
    fun duration(totalSeconds: Int): String {
        val seconds = totalSeconds.coerceAtLeast(0)
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            "$hours:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
        } else {
            "$minutes:${secs.toString().padStart(2, '0')}"
        }
    }
}
