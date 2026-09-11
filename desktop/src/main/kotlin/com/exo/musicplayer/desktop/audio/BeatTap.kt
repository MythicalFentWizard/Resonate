package com.exo.musicplayer.desktop.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The bass of what is playing, 256 samples (about 6 ms) at a time, each
 * reading stamped with when it comes out of the speakers.
 *
 * For the reactive backgrounds. The spectrum analyser suits a meter but not
 * something meant to move on the beat: it looks at 23 ms in every 93 and lets
 * its bars fall slowly on purpose, so a kick often lands between its looks,
 * and the audio it reads still has the output line's buffer - up to 186 ms -
 * to go before anyone hears it. This reads every sample, and [bassAt] answers
 * for the moment actually being heard.
 */
class BeatTap {

    /** Off unless a reactive background is showing. The delay is measured either way. */
    @Volatile var enabled = false

    /** How far the audio being fed is ahead of the speakers, as last measured. */
    @Volatile var delayNanos = 0L
        private set

    private val heardAt = LongArray(CAPACITY)
    private val bass = FloatArray(CAPACITY)
    @Volatile private var written = 0L

    // Playback thread only.
    private var low = 0f
    private var lower = 0f
    private var sum = 0.0
    private var inBlock = 0

    /**
     * [stereo] is interleaved, at [rate] frames a second, and about to be
     * written to a line that already holds [queuedFrames].
     */
    fun feed(stereo: FloatArray, queuedFrames: Int, rate: Int, nowNanos: Long = System.nanoTime()) {
        val delay = queuedFrames * 1_000_000_000L / rate
        delayNanos = delay
        if (!enabled) return
        // Two one-pole low-passes at 150 Hz: the kick and the bass line, not the vocal.
        val a = (1.0 - exp(-2.0 * PI * BASS_HZ / rate)).toFloat()
        val frames = stereo.size / 2
        for (i in 0 until frames) {
            val mono = (stereo[i * 2] + stereo[i * 2 + 1]) * 0.5f
            low += (mono - low) * a
            lower += (low - lower) * a
            sum += lower * lower
            if (++inBlock == BLOCK) {
                val slot = (written % CAPACITY).toInt()
                heardAt[slot] = nowNanos + delay + (i + 1 - BLOCK) * 1_000_000_000L / rate
                bass[slot] = sqrt(sum / BLOCK).toFloat()
                written++
                sum = 0.0
                inBlock = 0
            }
        }
    }

    fun reset() {
        written = 0L
        low = 0f
        lower = 0f
        sum = 0.0
        inBlock = 0
    }

    /**
     * The strongest bass heard in the 20 ms up to [nanos], as RMS of full
     * scale; 0 when nothing fed has been heard in the last fifth of a second -
     * paused, stopped, or not reached the speakers yet.
     */
    fun bassAt(nanos: Long): Float {
        val total = written
        if (total == 0L) return 0f
        val oldest = max(0L, total - CAPACITY)
        var index = total - 1
        // The newest readings are still in the line's buffer: step back to the one being heard.
        while (index >= oldest && heardAt[(index % CAPACITY).toInt()] > nanos) index--
        if (index < oldest || heardAt[(index % CAPACITY).toInt()] < nanos - STALE_NANOS) return 0f
        var peak = bass[(index % CAPACITY).toInt()]
        index--
        while (index >= oldest) {
            val slot = (index % CAPACITY).toInt()
            if (heardAt[slot] < nanos - WINDOW_NANOS) break
            peak = max(peak, bass[slot])
            index--
        }
        return peak
    }

    private companion object {
        const val BLOCK = 256
        /** About 47 seconds of readings. */
        const val CAPACITY = 8192
        const val BASS_HZ = 150.0
        const val WINDOW_NANOS = 20_000_000L
        const val STALE_NANOS = 200_000_000L
    }
}
