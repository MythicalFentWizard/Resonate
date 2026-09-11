package com.exo.musicplayer.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.exo.musicplayer.MainActivity
import com.exo.musicplayer.MusicApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Hosts the ExoPlayer instance and its MediaSession. Because this is a
 * MediaSessionService, Media3 puts up the media notification and wires the
 * lockscreen and Bluetooth controls for us, and playback keeps running when the
 * UI goes away.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val fx = AudioFxController()
    private var focus: AudioFocusController? = null
    private var outputs: MultiOutputController? = null
    private val tap = MultiDeviceTap()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()

        // A custom renderers factory is the only hook for tapping decoded PCM;
        // TeeAudioProcessor hands us each buffer as it passes through.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                // Two taps in series. Both are pass-through, so the order
                // only decides which sees the buffer first, and neither
                // changes what the next one gets.
                .setAudioProcessors(
                    arrayOf(
                        TeeAudioProcessor(tap),
                        TeeAudioProcessor(SpectrumTap(Spectrum.analyser))
                    )
                )
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ false
            )
            // Pause instead of blaring out of the speaker when headphones are pulled.
            .setHandleAudioBecomingNoisy(true)
            .build()

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openAppIntent())
            .build()

        // The UI writes to the same settings object; both live in this process,
        // so effects follow a slider without a round trip through the session.
        val app = application as MusicApp

        // Focus is handled here rather than by ExoPlayer: a call starting must
        // silence playback outright, while starting playback during a call
        // should be allowed quietly. See AudioFocusController.
        focus = AudioFocusController(
            context = this,
            scope = serviceScope,
            settingsProvider = { app.interruption.state.value }
        ).also { it.attach(player) }

        serviceScope.launch {
            app.audioFx.state.collect { state -> fx.apply(player, state) }
        }

        outputs = MultiOutputController(tap)
        serviceScope.launch {
            // Re-pin whenever the choice changes or a device connects.
            combine(
                app.audioOutputs.selectedKeys,
                app.audioOutputs.mirrorEnabled,
                app.audioOutputs.outputs
            ) { _, mirror, _ -> mirror }.collect { mirror ->
                outputs?.apply(player, app.audioOutputs.selectedDevices(), mirror)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /** Swiping the app away should not kill audio that is still playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        focus?.release()
        focus = null
        outputs?.release()
        outputs = null
        fx.release()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
