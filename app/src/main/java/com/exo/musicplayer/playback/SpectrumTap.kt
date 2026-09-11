package com.exo.musicplayer.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.exo.musicplayer.data.audio.SpectrumAnalyser
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Feeds the shared spectrum analyser from the decoded PCM stream.
 *
 * The Windows build reads its levels from the float buffer on its way to the
 * audio line. Android has no such buffer to borrow, but it already taps the
 * sink for multi-device output ([MultiDeviceTap]), and the same
 * [TeeAudioProcessor] mechanism works here: the bytes arriving are the ones
 * about to be played, after decoding and after ExoPlayer's own processing, so
 * the meter measures what is audible rather than animating to a pattern.
 *
 * This runs on the audio thread, so it must never allocate or block. The float
 * scratch buffer is allocated once and reused, work is skipped entirely unless
 * something is watching, and every conversion failure is swallowed: a meter is
 * not worth a glitch in playback.
 *
 * Only 16-bit PCM is handled. It is what ExoPlayer's sink outputs for
 * effectively every music file on Android, and a format this does not
 * understand leaves the meter flat rather than showing a wrong reading.
 */
@UnstableApi
class SpectrumTap(val analyser: SpectrumAnalyser) : TeeAudioProcessor.AudioBufferSink {

    private var channelCount = 2
    private var encoding = C.ENCODING_PCM_16BIT

    /**
     * One analysis window, filled across however many buffers it takes.
     *
     * This is the part that cannot be copied from the desktop. There, the
     * decoder is asked for 4096 frames at a time and every read is more than
     * one window. Here the sink hands over whatever it likes, routinely a few
     * hundred frames, and the analyser needs 1024 before it will produce
     * anything - so feeding each buffer straight through would leave the meter
     * permanently flat. Frames accumulate until a window is full instead.
     *
     * Allocated once, at its final size, because this runs on the audio thread
     * where an allocation is a potential underrun.
     */
    private val window = FloatArray(analyser.framesNeeded * 2)

    /** How much of [window] is filled, in frames. */
    private var filled = 0

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.channelCount = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        // A part-filled window from the previous track would splice two pieces
        // of unrelated audio into one FFT.
        filled = 0
        analyser.reset()
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        // Nothing is looking, so nothing is computed. The UI sets this from
        // whether the panel showing the meter is open.
        if (!analyser.enabled) return
        if (encoding != C.ENCODING_PCM_16BIT) return

        runCatching {
            // A read-only duplicate: consuming the caller's buffer would leave
            // the audio sink with nothing to play.
            val view = buffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
            val samples = view.remaining()
            if (samples <= 0) return

            val frames = samples / channelCount
            if (frames <= 0) return

            val wanted = analyser.framesNeeded
            var frame = 0
            while (frame < frames) {
                // The analyser wants interleaved stereo. Mono is doubled up and
                // anything wider keeps its first two channels, which is what
                // the desktop decoder does when folding to stereo.
                val base = frame * channelCount
                val left = view.get(base) / 32768f
                val right = if (channelCount > 1) view.get(base + 1) / 32768f else left
                window[filled * 2] = left
                window[filled * 2 + 1] = right
                filled++
                frame++

                if (filled == wanted) {
                    analyser.feed(window)
                    // Non-overlapping windows. At 44.1 kHz that is a reading
                    // roughly 43 times a second, far more often than the UI
                    // samples it, so overlapping would only add FFTs nobody
                    // sees.
                    filled = 0
                }
            }
        }
    }
}

/**
 * Where the UI finds the analyser.
 *
 * The playback service and the Compose UI live in the same process, and the
 * analyser is a plain object holding a float array, so a shared reference is
 * all that is needed - no binder, no serialisation, and no copying levels
 * across a boundary sixty times a second.
 */
object Spectrum {
    val analyser = SpectrumAnalyser()
}
