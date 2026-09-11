package com.exo.musicplayer.data.recognition

import com.exo.musicplayer.data.youtube.PipedYouTubeBackend
import com.exo.musicplayer.data.youtube.YouTubeBackend

/**
 * YouTube as one voice in the catalogue chain.
 *
 * This is the thin adapter that lets a YouTube hit sit in a list of catalogue
 * matches for the by-name search. The dedicated YouTube tab does not go through
 * here — it keeps the results as [com.exo.musicplayer.data.youtube.YouTubeVideo]
 * so it can show view counts, upload dates and thumbnails, none of which fit in
 * a [MusicMatch].
 *
 * Two things about the previous version of this file were wrong and are worth
 * recording, because both were load-bearing beliefs:
 *
 * It searched with `filter=music_songs`, on the assumption that the YouTube
 * Music index would be cleaner for a music player. Measured against the live
 * endpoint, that filter returns rows with `views: -1`, `uploadedDate: null` and
 * `shortDescription: null` — no view counts, no dates, no descriptions, and a
 * 120 px square thumbnail. The ordinary video search returns all of it, and the
 * results are the ones a person would recognise. `filter=videos` it is.
 *
 * It also claimed yt-dlp could not be used because its Android binding "maps a
 * single video and cannot return a result list". The binding exposes
 * `execute(request)`, which returns the process stdout, so
 * `ytsearchN:query --flat-playlist -J` works on a phone exactly as it does on a
 * desktop. That is now the fallback backend in the YouTube tab; it is not used
 * here only because spawning a Python runtime for one voice in a seven-service
 * parallel search would make every by-name search wait on the slowest member.
 */
class YouTubeSearchProvider(
    private val backend: YouTubeBackend = PipedYouTubeBackend()
) : MetadataProvider {

    override val label = "YouTube"

    override suspend fun search(query: String, limit: Int): List<MusicMatch> =
        backend.search(query, limit).map { video ->
            MusicMatch(
                title = video.title,
                // The channel, not an artist. Presented as one because a
                // MusicMatch has nowhere else to put it, and for the great
                // majority of music uploads it is the artist or their label —
                // but it is why the ranker scores these rows on the title.
                artist = video.channel,
                album = null,
                artworkUrl = video.thumbnail(),
                durationMs = video.durationSeconds?.times(1000L),
                source = MusicMatch.Source.SEARCH,
                provider = label,
                // A real watch URL, so the downloader fetches this exact video
                // rather than searching by name for something similar.
                downloadUrl = video.watchUrl
            )
        }
}
