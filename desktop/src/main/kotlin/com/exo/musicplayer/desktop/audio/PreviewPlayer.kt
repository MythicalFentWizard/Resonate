package com.exo.musicplayer.desktop.audio

import com.exo.musicplayer.data.youtube.PreviewState
import com.exo.musicplayer.desktop.data.NetworkProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plays a YouTube result without downloading it.
 *
 * Kept entirely separate from [PlaybackEngine] rather than added to it, for
 * three reasons. A preview should not join the queue or replace what is playing
 * once it ends; it should not inherit the equaliser and nightcore settings,
 * because the point is to hear what the file actually sounds like; and it has no
 * seekable duration, since it is a pipe rather than a file. Sharing the engine
 * would mean weakening all three of its guarantees for the sake of a feature
 * that only has to answer "is this the right song?".
 *
 * The audio arrives as Opus in a WebM container, which Java Sound cannot open at
 * all — no service provider on the classpath handles it. ffmpeg does, and it
 * already ships with the app for the downloader, so it is used as a decoder
 * here: it is handed the signed URL and writes raw PCM to stdout in exactly the
 * format the output line wants, so nothing has to be converted afterwards.
 *
 * Nothing is written to disk. A preview that left a file behind would be a
 * download, and there is already a button for that.
 */
class PreviewPlayer(private val ffmpegPath: () -> java.io.File) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    private var job: Job? = null

    @Volatile
    private var process: Process? = null

    /**
     * Starts previewing [videoId], resolving its stream URL through [resolve].
     *
     * Tapping the row that is already previewing stops it, which is what a
     * play/pause affordance on a row implies.
     */
    fun toggle(videoId: String, resolve: suspend (String) -> String?) {
        if (_state.value.videoId == videoId) {
            stop()
            return
        }
        start(videoId, resolve)
    }

    private fun start(videoId: String, resolve: suspend (String) -> String?) {
        val previous = job
        job = scope.launch {
            // The old preview is torn down before the new one is announced, so
            // two ffmpeg processes never write to the line at once.
            previous?.cancelAndJoin()
            killProcess()

            _state.value = PreviewState(videoId = videoId, loading = true)

            val ffmpeg = ffmpegPath()
            if (!ffmpeg.isFile) {
                _state.value = PreviewState(
                    videoId = videoId,
                    error = "ffmpeg is missing, so previews cannot be decoded."
                )
                return@launch
            }

            val url = runCatching { resolve(videoId) }.getOrNull()
            if (url == null) {
                _state.value = PreviewState(
                    videoId = videoId,
                    error = "Couldn't get a playable stream for that video."
                )
                return@launch
            }

            _state.value = PreviewState(videoId = videoId, loading = false, playing = true)
            runCatching { stream(ffmpeg, url, videoId) }
                .onFailure {
                    _state.value = PreviewState(videoId = videoId, error = it.message)
                }
        }
    }

    private suspend fun stream(ffmpeg: java.io.File, url: String, videoId: String) =
        withContext(Dispatchers.IO) {
            val format = AudioDevices.FORMAT
            // ffmpeg cannot speak SOCKS, and its own HTTP proxy option would be a
            // second setting to keep in step. So behind a proxy the stream is
            // fetched here, along the same route as everything else, and fed to
            // ffmpeg on stdin; without one ffmpeg reads the URL itself as before.
            val proxied = NetworkProxy.urlFor(url) != null
            val started = ProcessBuilder(
                buildList {
                    add(ffmpeg.absolutePath)
                    addAll(listOf("-hide_banner", "-loglevel", "error"))
                    if (proxied) {
                        addAll(listOf("-i", "pipe:0"))
                    } else {
                        // Keeps a stalled connection from hanging the preview forever.
                        addAll(listOf("-rw_timeout", "15000000", "-i", url))
                    }
                    addAll(
                        listOf(
                            "-f", "s16le",
                            "-ac", format.channels.toString(),
                            "-ar", format.sampleRate.toInt().toString(),
                            "pipe:1"
                        )
                    )
                }
            ).redirectErrorStream(false).start()
            process = started

            if (proxied) {
                Thread {
                    runCatching {
                        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 15_000
                            readTimeout = 15_000
                        }
                        connection.inputStream.use { input ->
                            started.outputStream.use { output -> input.copyTo(output, 1 shl 16) }
                        }
                    }
                    runCatching { started.outputStream.close() }
                }.apply { isDaemon = true; start() }
            }

            // stderr is drained on its own thread: ffmpeg blocks once the pipe
            // buffer fills, and a blocked encoder looks exactly like a hang.
            val drain = Thread { runCatching { started.errorStream.use { it.readBytes() } } }
                .apply { isDaemon = true; start() }

            val line = AudioDevices.openLine(null)
            if (line == null) {
                started.destroy()
                _state.value = PreviewState(videoId = videoId, error = "No audio output.")
                return@withContext
            }

            try {
                val buffer = ByteArray(format.frameSize * 4096)
                var totalBytes = 0L
                val bytesPerSecond = format.sampleRate.toInt() * format.frameSize

                while (true) {
                    val read = started.inputStream.read(buffer)
                    if (read <= 0) break
                    line.write(buffer, 0, read)
                    totalBytes += read

                    val seconds = (totalBytes / bytesPerSecond).toInt()
                    val current = _state.value
                    // Only republished when the whole second ticks over, so a
                    // preview does not recompose the list forty times a second.
                    if (current.videoId == videoId && current.secondsPlayed != seconds) {
                        _state.value = current.copy(secondsPlayed = seconds)
                    }
                }
                line.drain()
            } finally {
                runCatching { line.stop() }
                runCatching { line.close() }
                runCatching { started.destroy() }
                runCatching { drain.join(500) }
                process = null
                // Left alone if something else has already taken over, so a
                // finished preview cannot clear the state of the next one.
                if (_state.value.videoId == videoId) _state.value = PreviewState()
            }
        }

    fun stop() {
        job?.cancel()
        job = null
        killProcess()
        _state.value = PreviewState()
    }

    private fun killProcess() {
        runCatching { process?.destroy() }
        process = null
    }

    fun release() {
        stop()
        runCatching { scope.coroutineContext[Job]?.cancel() }
    }
}
