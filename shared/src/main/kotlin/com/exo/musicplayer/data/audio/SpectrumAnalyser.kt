package com.exo.musicplayer.data.audio

import com.exo.musicplayer.data.recognition.Dsp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Band levels taken from the audio actually being played.
 *
 * Real measurement rather than decoration: the samples fed in are the ones on
 * their way to the output, after the effect chain, so slowing a track down or
 * dialling in reverb visibly changes the display. A meter that animated to a
 * canned pattern would be worse than no meter, because it would look like
 * feedback and not be any.
 *
 * Shared, and fed from a different place on each platform: Windows hands it the
 * float buffer on its way to the audio line, Android taps the decoded PCM out of
 * the ExoPlayer sink. The analysis, the band spacing and the decay are therefore
 * identical on both, which is the point - the same track should look the same.
 *
 * Bands are spaced logarithmically because pitch is: an even split across a
 * 22 kHz range would put nearly everything audible into the first two bars.
 *
 * Cost is one 1024-point FFT per fed buffer, roughly eleven times a second,
 * and it is skipped entirely while [enabled] is false — which the UI sets from
 * whether the panel showing it is open, so nothing is computed for a display
 * nobody is looking at. Every buffer is preallocated: this runs on the playback
 * thread, where allocation is the one thing worth avoiding.
 */
class SpectrumAnalyser(private val bandCount: Int = 14) {

    private companion object {
        const val FFT_SIZE = 1024
        /** Below this a band reads as silent rather than as faint noise. */
        const val FLOOR_DB = -68f
        /** Fraction of the previous level retained, so bars fall rather than flicker. */
        const val DECAY = 0.72f
    }

    @Volatile var enabled: Boolean = false

    /**
     * Frames [feed] needs before it will do anything.
     *
     * Exposed because a caller that receives audio in smaller chunks has to
     * accumulate up to this before feeding - the Android sink hands over
     * buffers well under this size, so a tap that fed each one straight
     * through would silently never produce a reading.
     */
    val framesNeeded: Int get() = FFT_SIZE

    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)

    /** Hann window, to stop the block edges smearing energy across every bin. */
    private val window = FloatArray(FFT_SIZE) {
        0.5f - 0.5f * cos(2.0 * PI * it / (FFT_SIZE - 1)).toFloat()
    }

    /**
     * Log-spaced bin boundaries, from ~40 Hz to the Nyquist limit.
     *
     * Below 40 Hz there is little but rumble and DC offset, and the lowest bins
     * of a 1024-point FFT at 44.1 kHz are 43 Hz wide anyway, so a band narrower
     * than that would be describing one bin.
     */
    private val edges: IntArray = run {
        val nyquistBin = FFT_SIZE / 2
        val lowest = 2
        val ratio = (nyquistBin.toDouble() / lowest).pow(1.0 / bandCount)
        IntArray(bandCount + 1) { index ->
            (lowest * ratio.pow(index.toDouble())).toInt().coerceIn(lowest, nyquistBin)
        }
    }

    private val levels = FloatArray(bandCount)

    /**
     * Feeds interleaved stereo samples. Only the first [FFT_SIZE] frames are
     * used; a buffer is short enough that the rest would show the same thing.
     */
    fun feed(interleavedStereo: FloatArray) {
        if (!enabled) return
        val frames = interleavedStereo.size / 2
        if (frames < FFT_SIZE) return

        for (i in 0 until FFT_SIZE) {
            val left = interleavedStereo[i * 2]
            val right = interleavedStereo[i * 2 + 1]
            re[i] = (left + right) * 0.5f * window[i]
            im[i] = 0f
        }
        Dsp.fft(re, im)

        for (band in 0 until bandCount) {
            val from = edges[band]
            val to = max(edges[band + 1], from + 1)
            var sum = 0.0
            for (bin in from until to) {
                sum += re[bin] * re[bin].toDouble() + im[bin] * im[bin].toDouble()
            }
            // RMS across the band, then decibels relative to full scale.
            val rms = sqrt(sum / (to - from)).toFloat() / (FFT_SIZE / 4f)
            val db = if (rms <= 1e-7f) FLOOR_DB else 20f * log10(rms)
            val target = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)

            // Rises immediately, falls slowly: a meter that dropped as fast as
            // the audio does reads as flicker rather than as level.
            levels[band] = if (target > levels[band]) {
                target
            } else {
                levels[band] * DECAY + target * (1f - DECAY)
            }
        }
    }

    /** Copies the current levels, 0..1 per band. */
    fun snapshot(into: FloatArray): FloatArray {
        val out = if (into.size == bandCount) into else FloatArray(bandCount)
        System.arraycopy(levels, 0, out, 0, bandCount)
        return out
    }

    fun bands(): Int = bandCount

    fun reset() {
        levels.fill(0f)
    }
}
