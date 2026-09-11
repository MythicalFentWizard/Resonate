package com.exo.musicplayer.desktop.download

import com.exo.musicplayer.data.download.DownloadQuality
import com.exo.musicplayer.desktop.data.AppDirs
import com.exo.musicplayer.desktop.data.NetworkProxy
import com.exo.musicplayer.desktop.data.ToolPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Passed to every yt-dlp call. Many VPNs carry only IPv4, and yt-dlp trying
 * IPv6 first behind one fails outright; tunnels also stall, so the socket
 * timeout is longer than yt-dlp's 20 s.
 */
internal val YT_DLP_NETWORK = listOf("--force-ipv4", "--socket-timeout", "30")

/**
 * Downloads only: a flaky tunnel drops connections mid-file, and yt-dlp's
 * retries otherwise fire back to back and are all spent within the same
 * second. These back off, so a short dropout is ridden out. Kept off search,
 * where waiting half a minute to report failure would be worse.
 */
internal val YT_DLP_RETRIES = listOf(
    "--extractor-retries", "5",
    "--retry-sleep", "exp=1:20",
    "--retry-sleep", "fragment:exp=1:20",
    "--retry-sleep", "extractor:exp=1:10"
)

/** What the downloader can do right now. */
data class ToolStatus(
    val ytDlp: Boolean = false,
    val ffmpeg: Boolean = false,
    val spotdl: Boolean = false,
    val ytDlpVersion: String? = null
) {
    val ready: Boolean get() = ytDlp
}

/** A line of progress from a running job. */
data class DownloadProgress(
    val percent: Float?,
    val line: String
)

/**
 * The bundled command-line downloaders.
 *
 * yt-dlp, ffmpeg and spotdl all ship inside the app image, so there is no first
 * -use install step and no window where the feature exists but does not work.
 * That is most of the installer's size, and it is the right trade: the tools are
 * the feature.
 *
 * yt-dlp still updates itself — extractors break whenever a site changes, often
 * faster than Resonate ships — but it cannot rewrite a binary under Program
 * Files, so updating copies it somewhere writable first.
 */
object YtDlp {

    fun status(): ToolStatus = ToolStatus(
        ytDlp = ToolPaths.ytDlp.isFile,
        ffmpeg = ToolPaths.ffmpeg.isFile,
        spotdl = ToolPaths.spotdl.isFile,
        ytDlpVersion = null
    )

    suspend fun version(): String? = withContext(Dispatchers.IO) {
        val exe = ToolPaths.ytDlp
        if (!exe.isFile) return@withContext null
        runCatching {
            val process = ProcessBuilder(exe.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()
            text.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * Updates yt-dlp in place, after moving it somewhere it can be written.
     */
    suspend fun update(onLine: (String) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val exe = ToolPaths.makeUpdatable("yt-dlp.exe")
                ?: error("Couldn't find yt-dlp to update.")
            onLine("Updating ${exe.absolutePath}")
            val process = ProcessBuilder(listOf(exe.absolutePath, "-U") + NetworkProxy.ytDlpArgs())
                .redirectErrorStream(true)
                .also(NetworkProxy::configure)
                .start()
            process.inputStream.bufferedReader().forEachLine(onLine)
            check(process.waitFor() == 0) { "Update failed." }
        }
    }

    /**
     * Runs one download.
     *
     * Output lands in a private empty directory first and is moved out on
     * success. That is how the finished files are identified: yt-dlp's console
     * output changes between versions and between extractors, whereas "whatever
     * appeared in this empty folder" is always right — and a failed or cancelled
     * job leaves no half-written file in the user's music folder.
     */
    suspend fun download(
        target: String,
        destination: File,
        toMp3: Boolean,
        embedThumbnail: Boolean,
        wholePlaylist: Boolean,
        quality: DownloadQuality = DownloadQuality.DEFAULT,
        onProgress: (DownloadProgress) -> Unit,
        registerProcess: (Process) -> Unit = {}
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        runCatching {
            val exe = ToolPaths.ytDlp
            check(exe.isFile) { "yt-dlp is missing from this installation." }
            destination.mkdirs()

            val ffmpeg = ToolPaths.ffmpeg
            inStaging(destination) { staging ->
                val command = mutableListOf(
                    exe.absolutePath,
                    "--newline",
                    "--no-warnings",
                    "--ignore-config",
                    // Filters what gets pulled down, not just what it is
                    // re-encoded to, so a lower setting saves bandwidth too.
                    "-f", quality.formatSelector,
                    "-o", File(staging, "%(title).180B.%(ext)s").absolutePath
                )
                if (!wholePlaylist) command += "--no-playlist"
                if (toMp3) {
                    check(ffmpeg.isFile) { "Converting to MP3 needs ffmpeg." }
                    command += listOf(
                        "-x", "--audio-format", "mp3", "--audio-quality", quality.audioQuality
                    )
                }
                if (ffmpeg.isFile) {
                    command += listOf("--ffmpeg-location", ffmpeg.absolutePath)
                    // Metadata and cover art only survive the container with
                    // ffmpeg available to rewrite it.
                    command += "--embed-metadata"
                    if (embedThumbnail) command += "--embed-thumbnail"
                }
                command += NetworkProxy.ytDlpArgs()
                command += YT_DLP_NETWORK
                command += YT_DLP_RETRIES
                command += target

                val process = ProcessBuilder(command).redirectErrorStream(true)
                    .also(NetworkProxy::configure)
                    .start()
                registerProcess(process)
                process.inputStream.bufferedReader().forEachLine { line ->
                    onProgress(DownloadProgress(percentOf(line), line.trim()))
                }
                val code = process.waitFor()
                code to "yt-dlp exited with code $code."
            }
        }
    }

    /**
     * Downloads a Spotify link with spotdl.
     *
     * spotdl reads Spotify's public metadata and then fetches the matching
     * recording from YouTube — Spotify's own audio is encrypted and nothing can
     * download it. The result is therefore a *match*: nearly always the same
     * recording, occasionally a live take or a remaster. Doing it this way
     * rather than scraping the page for a title gets exact track, album and
     * artwork metadata, and handles whole albums and playlists.
     */
    suspend fun downloadSpotify(
        url: String,
        destination: File,
        toMp3: Boolean,
        quality: DownloadQuality = DownloadQuality.DEFAULT,
        onProgress: (DownloadProgress) -> Unit,
        registerProcess: (Process) -> Unit = {}
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        runCatching {
            val exe = ToolPaths.spotdl
            check(exe.isFile) { "spotdl is missing from this installation." }
            destination.mkdirs()

            inStaging(destination) { staging ->
                val command = mutableListOf(
                    exe.absolutePath,
                    "download", url,
                    "--output", File(staging, "{artists} - {title}.{output-ext}").absolutePath
                )
                if (toMp3) command += listOf("--format", "mp3")
                command += listOf("--bitrate", quality.spotdlBitrate)
                if (ToolPaths.ffmpeg.isFile) {
                    command += listOf("--ffmpeg", ToolPaths.ffmpeg.absolutePath)
                }

                // spotdl has no SOCKS option of its own; it gets the environment.
                val process = ProcessBuilder(command).redirectErrorStream(true)
                    .also(NetworkProxy::configure)
                    .start()
                registerProcess(process)
                process.inputStream.bufferedReader().forEachLine { line ->
                    onProgress(DownloadProgress(null, line.trim()))
                }
                val code = process.waitFor()
                code to "spotdl exited with code $code."
            }
        }
    }

    /**
     * Runs [block] against a scratch directory and moves whatever it produced
     * into [destination].
     */
    private inline fun inStaging(
        destination: File,
        block: (File) -> Pair<Int, String>
    ): List<File> {
        val staging = File(AppDirs.tools, "staging-${System.nanoTime()}")
        staging.mkdirs()
        try {
            val (code, failure) = block(staging)
            val produced = staging.walkTopDown().filter { it.isFile }.toList()
            if (code != 0 && produced.isEmpty()) error(failure)

            return produced.map { file ->
                val moved = uniqueIn(destination, file.name)
                if (!file.renameTo(moved)) {
                    file.copyTo(moved, overwrite = true)
                    file.delete()
                }
                moved
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    private val PERCENT = Regex("""(\d{1,3}(?:\.\d+)?)%""")

    private fun percentOf(line: String): Float? {
        if (!line.startsWith("[download]")) return null
        return PERCENT.find(line)?.groupValues?.get(1)?.toFloatOrNull()?.div(100f)
    }

    private fun uniqueIn(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var index = 2
        while (candidate.exists()) {
            val suffix = if (extension.isEmpty()) "" else ".$extension"
            candidate = File(dir, "$base ($index)$suffix")
            index++
        }
        return candidate
    }
}
