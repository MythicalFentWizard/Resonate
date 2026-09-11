package com.exo.musicplayer.desktop.download

import com.exo.musicplayer.data.download.DownloadQuality
import com.exo.musicplayer.data.youtube.YouTubeBackend
import com.exo.musicplayer.data.youtube.YouTubeVideo
import com.exo.musicplayer.data.youtube.YtDlpFlatSearch
import com.exo.musicplayer.desktop.data.NetworkProxy
import com.exo.musicplayer.desktop.data.ToolPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * YouTube search through the bundled yt-dlp.
 *
 * The reliable half of the search chain. Slower than a Piped call — around
 * three seconds against half a second, measured — but it is the same binary
 * that performs the download, so if a search works the download will too, and
 * it keeps working when every public front-end is down.
 *
 * Stream resolution lives here as well, for previews. It is a second process
 * and a slower one (about six seconds, because it has to run YouTube's player
 * JS to sign the URL), which is why the UI shows the row as loading rather than
 * pretending the tap was instant.
 */
class DesktopYouTubeBackend : YouTubeBackend {

    override val label = "yt-dlp"

    override suspend fun search(query: String, limit: Int): List<YouTubeVideo> =
        withContext(Dispatchers.IO) {
            val exe = ToolPaths.ytDlp
            if (!exe.isFile) return@withContext emptyList()

            runCatching {
                val process = ProcessBuilder(
                    buildList {
                        add(exe.absolutePath)
                        add(YtDlpFlatSearch.target(query, limit))
                        addAll(YtDlpFlatSearch.ARGUMENTS)
                        addAll(NetworkProxy.ytDlpArgs())
                        addAll(YT_DLP_NETWORK)
                    }
                )
                    // Kept apart, unlike the download path: stderr carries
                    // deprecation notices that would corrupt the JSON document
                    // if they were folded into stdout.
                    .redirectErrorStream(false)
                    .also(NetworkProxy::configure)
                    .start()

                val json = process.inputStream.bufferedReader().use { it.readText() }
                process.errorStream.use { it.readBytes() }
                process.waitFor()
                YtDlpFlatSearch.parse(json, label)
            }.getOrDefault(emptyList())
        }

    /**
     * Resolves a direct, playable audio URL for [videoId].
     *
     * What comes back is a signed googlevideo.com URL, usually Opus in a WebM
     * container, valid for a few hours. ffmpeg decodes it for playback; nothing
     * is written to disk, because a preview is not a download.
     */
    suspend fun audioStreamUrl(
        videoId: String,
        quality: DownloadQuality = DownloadQuality.LOW
    ): String? = withContext(Dispatchers.IO) {
        val exe = ToolPaths.ytDlp
        if (!exe.isFile) return@withContext null

        runCatching {
            val process = ProcessBuilder(
                exe.absolutePath,
                "https://www.youtube.com/watch?v=$videoId",
                // The preview only has to be recognisable, so the smallest
                // rendition is the right one - it starts sooner.
                "-f", quality.formatSelector,
                "-g",
                "--no-warnings",
                "--no-playlist",
                "--ignore-config",
                *(NetworkProxy.ytDlpArgs() + YT_DLP_NETWORK).toTypedArray()
            ).redirectErrorStream(false).also(NetworkProxy::configure).start()

            val out = process.inputStream.bufferedReader().use { it.readText() }
            process.errorStream.use { it.readBytes() }
            process.waitFor()
            // -g prints one URL per selected stream; audio-only selectors give
            // exactly one, but take the first either way.
            out.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("http") }
        }.getOrNull()
    }
}
