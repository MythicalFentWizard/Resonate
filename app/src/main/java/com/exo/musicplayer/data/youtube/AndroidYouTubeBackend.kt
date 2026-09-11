package com.exo.musicplayer.data.youtube

import com.exo.musicplayer.data.download.DownloadQuality
import com.exo.musicplayer.data.download.YtDlpDownloader
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * YouTube search through the yt-dlp already on the device.
 *
 * The Android binding does more than [YoutubeDL.getInfo]: `execute` returns the
 * process's stdout, so an arbitrary invocation works — including
 * `ytsearchN:query --flat-playlist -J`, which is a result list rather than a
 * single video. An earlier comment in this project claimed the binding could
 * not do this; it can, and this is the same command the Windows build runs, so
 * the two platforms parse identical JSON through shared code.
 *
 * Slower here than on a desktop: the binding starts a bundled Python runtime
 * per call. That is why [PipedYouTubeBackend] is tried first and this is the
 * fallback, and why the first search after a cold start takes noticeably longer
 * than the ones after it.
 */
class AndroidYouTubeBackend(
    private val downloader: YtDlpDownloader
) : YouTubeBackend {

    override val label = "yt-dlp"

    override suspend fun search(query: String, limit: Int): List<YouTubeVideo> =
        withContext(Dispatchers.IO) {
            // Unpacks the Python runtime on first use. Without this the call
            // below throws rather than simply returning nothing.
            if (!downloader.ensureReady()) return@withContext emptyList()

            runCatching {
                val request = YoutubeDLRequest(YtDlpFlatSearch.target(query, limit))
                YtDlpFlatSearch.ARGUMENTS.forEach { request.addOption(it) }
                val response = YoutubeDL.getInstance().execute(request)
                YtDlpFlatSearch.parse(response.out, label)
            }.getOrDefault(emptyList())
        }

    /**
     * Resolves a direct, playable audio URL for [videoId].
     *
     * ExoPlayer cannot open a YouTube watch page, but it plays the signed
     * googlevideo.com URL behind one natively — Opus in WebM is a format it
     * already decodes — so a preview needs no download and no temporary file.
     */
    suspend fun audioStreamUrl(
        videoId: String,
        quality: DownloadQuality = DownloadQuality.LOW
    ): String? = withContext(Dispatchers.IO) {
        if (!downloader.ensureReady()) return@withContext null

        runCatching {
            val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId").apply {
                addOption("-f", quality.formatSelector)
                addOption("-g")
                addOption("--no-warnings")
                addOption("--no-playlist")
                addOption("--ignore-config")
            }
            YoutubeDL.getInstance().execute(request).out
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("http") }
        }.getOrNull()
    }
}
