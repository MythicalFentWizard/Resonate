package com.exo.musicplayer.desktop.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sample-rate conversion for playback, one buffer at a time.
 *
 * Playback used to call the fingerprinting resampler on every buffer, filter
 * maths recomputed for every tap of every sample and each buffer resampled on
 * its own, so it was slow and every buffer boundary was a small discontinuity.
 * This keeps its read position and the input frames the filter still needs
 * between calls, so the output is one continuous signal however it is chunked.
 *
 * The interpolator is a Kaiser-windowed sinc, 32 zero crossings each side, read
 * from a table of 1024 phases with linear interpolation between neighbouring
 * rows. It replaced a four-point Catmull-Rom cubic, which dulled the treble and
 * left images of the top octave not far below the music: heard as a slight loss
 * of clarity whenever a preset moved pitch, such as Slowed. When reading faster
 * than real time (a step above 1) the cutoff drops to just under the new
 * Nyquist, so nothing folds back into the audible band.
 */
class StreamingResampler(private val channels: Int) {

    /** Input frames carried from the previous call: the filter's history. */
    private var carry = FloatArray(channels * TAPS * 2)
    private var carryFrames = 0

    /** Read position in frames, counted from the start of carry-then-input. */
    private var position = 0.0

    private var joined = FloatArray(0)
    private var output = FloatArray(0)

    private var kernelCutoff = -1.0
    private val kernel = FloatArray((PHASES + 1) * TAPS)

    init {
        reset()
    }

    fun reset() {
        // Silence before the first frame, so the filter has a history from the
        // start and the first input frame is the first one out.
        carryFrames = HALF - 1
        if (carry.size < carryFrames * channels) carry = FloatArray(carryFrames * channels)
        carry.fill(0f, 0, carryFrames * channels)
        position = (HALF - 1).toDouble()
    }

    /**
     * Converts [frames] interleaved frames of [input], advancing [step] input
     * frames per output frame (source rate divided by target rate), and returns
     * a new array holding exactly what was produced.
     */
    fun process(input: FloatArray, frames: Int, step: Double): FloatArray {
        val ch = channels
        // Reading faster than real time: 92% of the new Nyquist, so the filter's
        // transition is over before it and a 48 kHz file's top octave cannot fold
        // back. At exactly the new Nyquist a 23 kHz tone leaked at -24 dB.
        val cutoff = if (step > 1.0) (920.0 / step).toInt() / 1000.0 else 1.0
        if (cutoff != kernelCutoff) design(cutoff)

        val total = carryFrames + frames
        if (joined.size < total * ch) joined = FloatArray(total * ch)
        System.arraycopy(carry, 0, joined, 0, carryFrames * ch)
        System.arraycopy(input, 0, joined, carryFrames * ch, frames * ch)

        val capacity = ((((total - position) / step).toInt() + 2).coerceAtLeast(0)) * ch
        if (output.size < capacity) output = FloatArray(capacity)

        var p = position
        var produced = 0
        while (true) {
            val i = p.toInt()
            if (i + HALF >= total || (produced + 1) * ch > output.size) break
            val phase = (p - i) * PHASES
            if (phase == 0.0 && cutoff == 1.0) {
                // Exactly on a sample with nothing to filter out: the sample itself.
                System.arraycopy(joined, i * ch, output, produced * ch, ch)
            } else {
                val row = phase.toInt()
                val mix = (phase - row).toFloat()
                val a = row * TAPS
                val b = a + TAPS
                val first = (i - HALF + 1) * ch
                for (c in 0 until ch) {
                    var sum = 0f
                    var at = first + c
                    for (k in 0 until TAPS) {
                        val weight = kernel[a + k] + (kernel[b + k] - kernel[a + k]) * mix
                        sum += joined[at] * weight
                        at += ch
                    }
                    output[produced * ch + c] = sum
                }
            }
            produced++
            p += step
        }

        // Keep the history the next read position still needs.
        val keepFrom = (p.toInt() - HALF + 1).coerceIn(0, total)
        carryFrames = total - keepFrom
        if (carry.size < carryFrames * ch) carry = FloatArray(carryFrames * ch)
        System.arraycopy(joined, keepFrom * ch, carry, 0, carryFrames * ch)
        position = p - keepFrom

        return output.copyOf(produced * ch)
    }

    /** The windowed sinc at [cutoff] (1 is Nyquist) for every phase, each row summing to 1. */
    private fun design(cutoff: Double) {
        kernelCutoff = cutoff
        val norm = besselI0(BETA)
        for (row in 0..PHASES) {
            val fraction = row.toDouble() / PHASES
            val start = row * TAPS
            var sum = 0.0
            for (k in 0 until TAPS) {
                val x = k - (HALF - 1) - fraction
                val along = x / HALF
                val window = if (abs(along) >= 1.0) 0.0 else besselI0(BETA * sqrt(1.0 - along * along)) / norm
                val sinc = if (abs(x) < 1e-9) cutoff else sin(PI * cutoff * x) / (PI * x)
                val value = sinc * window
                kernel[start + k] = value.toFloat()
                sum += value
            }
            if (sum != 0.0) {
                for (k in 0 until TAPS) kernel[start + k] = (kernel[start + k] / sum).toFloat()
            }
        }
    }

    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val half = x / 2.0
        var k = 1
        while (term > 1e-12 * sum) {
            term *= (half / k) * (half / k)
            sum += term
            k++
        }
        return sum
    }

    private companion object {
        const val HALF = 32
        const val TAPS = HALF * 2
        const val PHASES = 1024
        const val BETA = 7.5
    }
}
