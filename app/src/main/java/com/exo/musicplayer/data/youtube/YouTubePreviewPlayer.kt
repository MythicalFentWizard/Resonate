package com.exo.musicplayer.data.youtube

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Plays a YouTube result without downloading it.
 *
 * ExoPlayer cannot open a YouTube watch page, but it plays the signed
 * googlevideo.com URL behind one perfectly well: the audio is Opus in a WebM
 * container, which is a format Android has decoded natively since long before
 * this app's minimum API level. So a preview needs no temporary file and no
 * ffmpeg pass, only the URL, and getting that is the slow part.
 *
 * A second player rather than the library's own, for the same reasons the
 * Windows build keeps them apart: a preview must not join the queue, must not
 * survive as "now playing" once it ends, and must not inherit the equaliser,
 * because the point of previewing is to hear what the recording actually
 * sounds like. It also means stopping a preview cannot disturb whatever the
 * user had queued up.
 */
class YouTubePreviewPlayer(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val _state = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    /**
     * Created on first use and kept afterwards.
     *
     * ExoPlayer must be built and driven from the thread that created it, and
     * everything here runs on the main dispatcher, so that is where it lives.
     * Building one costs a few milliseconds, which is not worth paying for
     * anyone who never taps a preview.
     */
    private var player: ExoPlayer? = null

    private var job: Job? = null
    private var ticker: Job? = null

    /** Tapping the row that is already previewing stops it. */
    fun toggle(videoId: String, resolve: suspend (String) -> String?) {
        if (_state.value.videoId == videoId) {
            stop()
            return
        }
        start(videoId, resolve)
    }

    private fun start(videoId: String, resolve: suspend (String) -> String?) {
        job?.cancel()
        ticker?.cancel()
        releasePlayback()

        _state.value = PreviewState(videoId = videoId, loading = true)

        job = scope.launch {
            val url = runCatching { resolve(videoId) }.getOrNull()

            // The row may have been tapped again, or another one started, while
            // the URL was being signed - that takes seconds, not milliseconds.
            if (_state.value.videoId != videoId) return@launch

            if (url == null) {
                _state.value = PreviewState(
                    videoId = videoId,
                    error = "Couldn't get a playable stream. yt-dlp may need updating."
                )
                return@launch
            }

            val active = player ?: ExoPlayer.Builder(context).build().also { built ->
                built.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) stop()
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        val current = _state.value
                        _state.value = PreviewState(
                            videoId = current.videoId,
                            // Signed URLs expire, and a stale one fails here
                            // rather than at resolution time.
                            error = "Playback failed: ${error.errorCodeName}"
                        )
                    }
                })
                player = built
            }

            active.setMediaItem(MediaItem.fromUri(url))
            active.prepare()
            active.play()

            _state.value = PreviewState(videoId = videoId, loading = false, playing = true)

            ticker = scope.launch {
                while (true) {
                    delay(500)
                    val current = _state.value
                    if (current.videoId != videoId) break
                    val seconds = (active.currentPosition / 1000).toInt()
                    if (current.secondsPlayed != seconds) {
                        _state.value = current.copy(secondsPlayed = seconds)
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        ticker?.cancel()
        ticker = null
        releasePlayback()
        _state.value = PreviewState()
    }

    private fun releasePlayback() {
        player?.let {
            runCatching { it.stop() }
            runCatching { it.clearMediaItems() }
        }
    }

    /** Called from the view model's onCleared; the player outlives no screen. */
    fun release() {
        stop()
        runCatching { player?.release() }
        player = null
    }
}
