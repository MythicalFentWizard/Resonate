package com.exo.musicplayer.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.exo.musicplayer.data.db.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlaybackState(
    val isConnected: Boolean = false,
    val currentTrackId: Long? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queueTrackIds: List<Long> = emptyList(),
    val queueIndex: Int = -1
) {
    val hasNext: Boolean get() = queueIndex in 0 until queueTrackIds.lastIndex
    val hasPrevious: Boolean get() = queueIndex > 0
    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * The UI's handle on the player. Wraps a [MediaController] bound to
 * [PlaybackService] and republishes it as a [StateFlow] Compose can collect.
 *
 * Lives on the Application so playback state survives the Activity, and so the
 * share-import screen can start playback without opening the main UI.
 */
class PlaybackConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Called when the player moves to a new track. */
    private val onTrackStarted: (Long) -> Unit,
    /** Wall-clock milliseconds actually spent playing since the last tick. */
    private val onElapsed: (Long) -> Unit = {},
    /** Called when playback stops, so pending listening time can be persisted. */
    private val onPlaybackPaused: () -> Unit = {}
) {

    private var controller: MediaController? = null
    private var ticker: Job? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            // Any real player event may have changed the queue; a position tick
            // never does, which is what the cache below exists to exploit.
            queueDirty = true
            publish()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.trackId()?.let(onTrackStarted)
            // Media3 fires this before onEvents, so without marking the queue
            // stale here this call would publish the previous one for an
            // instant before onEvents corrected it.
            queueDirty = true
            publish()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startTicker() else stopTicker()
            queueDirty = true
            publish()
        }
    }

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                controller = runCatching { future.get() }.getOrNull()?.also {
                    it.addListener(listener)
                }
                publish()
                if (controller?.isPlaying == true) startTicker()
            },
            androidx.core.content.ContextCompat.getMainExecutor(context)
        )
    }

    // ---- Commands ----

    /** Replaces the queue with [tracks] and starts at [startIndex]. */
    fun play(tracks: List<Track>, startIndex: Int = 0) {
        val player = controller ?: return
        if (tracks.isEmpty()) return
        val index = startIndex.coerceIn(0, tracks.lastIndex)
        player.setMediaItems(tracks.map { it.toMediaItem() }, index, 0L)
        player.prepare()
        player.play()
        tracks[index].id.let(onTrackStarted)
        publish()
    }

    fun togglePlayPause() {
        val player = controller ?: return
        if (player.isPlaying) player.pause() else player.play()
        publish()
    }

    fun next() = controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }

    /** Restarts the track first, like every other music player. */
    fun previous() {
        val player = controller ?: return
        if (player.currentPosition > RESTART_THRESHOLD_MS || !player.hasPreviousMediaItem()) {
            player.seekTo(0)
        } else {
            player.seekToPreviousMediaItem()
        }
        publish()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        publish()
    }

    fun seekToFraction(fraction: Float) {
        val duration = controller?.duration ?: return
        if (duration > 0) seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    fun toggleShuffle() {
        val player = controller ?: return
        player.shuffleModeEnabled = !player.shuffleModeEnabled
        publish()
    }

    fun cycleRepeat() {
        val player = controller ?: return
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        publish()
    }

    fun addToQueue(tracks: List<Track>) {
        val player = controller ?: return
        if (tracks.isEmpty()) return
        player.addMediaItems(tracks.map { it.toMediaItem() })
        if (player.mediaItemCount == tracks.size) {
            player.prepare()
        }
        publish()
    }

    fun playNext(track: Track) {
        val player = controller ?: return
        val insertAt = (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount)
        player.addMediaItem(insertAt, track.toMediaItem())
        publish()
    }

    fun removeFromQueue(index: Int) {
        val player = controller ?: return
        if (index in 0 until player.mediaItemCount) player.removeMediaItem(index)
        publish()
    }

    fun jumpToQueueIndex(index: Int) {
        val player = controller ?: return
        if (index in 0 until player.mediaItemCount) {
            player.seekTo(index, 0L)
            player.play()
        }
        publish()
    }

    /** Drops a deleted track from the queue so playback never points at a missing file. */
    fun evictTrack(trackId: Long) {
        val player = controller ?: return
        for (index in player.mediaItemCount - 1 downTo 0) {
            if (player.getMediaItemAt(index).trackId() == trackId) {
                player.removeMediaItem(index)
            }
        }
        publish()
    }

    // ---- Internals ----

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            // Wall clock rather than player position: seeking around a track
            // must not be counted as time spent listening to it.
            var last = System.currentTimeMillis()
            while (true) {
                delay(POSITION_POLL_MS)
                val now = System.currentTimeMillis()
                if (controller?.isPlaying == true) onElapsed(now - last)
                last = now
                publish()
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
        onPlaybackPaused()
    }

    /**
     * The queue, rebuilt only when something has actually changed it.
     *
     * [publish] runs twice a second for the whole time anything is playing, and
     * it used to walk every item in the queue and allocate a fresh list on each
     * pass - thousands of calls a second on a large queue, to produce the same
     * answer every time. A position tick cannot reorder the queue, so the list
     * is cached and only recomputed when a player event says it may have moved.
     */
    private var cachedQueue: List<Long> = emptyList()
    private var queueDirty = true

    private fun queueOf(player: Player): List<Long> {
        if (!queueDirty) return cachedQueue
        cachedQueue = (0 until player.mediaItemCount).mapNotNull {
            player.getMediaItemAt(it).trackId()
        }
        queueDirty = false
        return cachedQueue
    }

    private fun publish() {
        val player = controller
        if (player == null) {
            _state.value = PlaybackState()
            cachedQueue = emptyList()
            queueDirty = true
            return
        }
        val queue = queueOf(player)
        _state.value = PlaybackState(
            isConnected = true,
            currentTrackId = player.currentMediaItem?.trackId(),
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0L } ?: 0L,
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            queueTrackIds = queue,
            queueIndex = player.currentMediaItemIndex
        )
    }

    private companion object {
        const val POSITION_POLL_MS = 500L
        const val RESTART_THRESHOLD_MS = 3_000L
    }
}
