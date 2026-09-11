package com.exo.musicplayer.desktop.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import java.util.concurrent.atomic.AtomicReference

/**
 * Time-stretching for "slowed" and "sped up" without moving the pitch.
 *
 * Built the way SoundTouch's time-domain stretcher is. The song is cut into
 * sequences of 45 to 100 ms, longer the slower it plays. Each is copied
 * untouched apart from a 12 ms crossfade into the next, and where the next one
 * starts is chosen within a 15 to 22 ms search by normalised cross-correlation
 * against the end of the one before, so the join lands in phase. Almost every
 * output sample is an original sample.
 *
 * The stretcher before this crossfaded continuously: every output sample was a
 * blend of two copies of the song a few milliseconds apart, spliced afresh 86
 * times a second, which comb-filters the sound slightly and was heard as a small
 * loss of clarity whenever a track was slowed down. Its search also compared
 * only 11 ms over a 6 ms range with an unnormalised product, so loud passages
 * beat well-matched ones and the bass could not be kept in phase.
 *
 * Operates on interleaved stereo with both channels in the match, so neither
 * side drifts. Buffers are kept and reused, so the playback thread makes no
 * garbage.
 */
class TimeStretcher(private val sampleRate: Int = 44_100) {

    @Volatile
    var factor: Float = 1f      // >1 plays faster

    private val overlap = frames(12.0)

    /** Input not yet consumed, interleaved stereo, and how much of it is valid. */
    private var pending = FloatArray(0)
    private var pendingLength = 0
    private var output = FloatArray(0)

    /** The last [overlap] frames of the previous sequence, to crossfade from. */
    private val previous = FloatArray(overlap * 2)
    private var primed = false
    private var skipCarry = 0.0

    /** Rises from 0 to 1 across the crossfade; with 1 - w it always sums to 1. */
    private val fade = FloatArray(overlap) { i ->
        (0.5 - 0.5 * cos(PI * (i + 0.5) / overlap)).toFloat()
    }

    private fun frames(ms: Double) = (sampleRate * ms / 1000.0).toInt()

    /** 100 ms sequences at half speed down to 45 ms at double, as SoundTouch scales them. */
    private fun sequenceFrames(f: Float) = frames(100.0 - (f.coerceIn(0.5f, 2f) - 0.5) / 1.5 * 55.0)

    /** How far each join may move to find its match: 22 ms at half speed, 15 ms at double. */
    private fun seekFrames(f: Float) = frames(22.0 - (f.coerceIn(0.5f, 2f) - 0.5) / 1.5 * 7.0)

    fun reset() {
        pendingLength = 0
        primed = false
        skipCarry = 0.0
        previous.fill(0f)
    }

    fun process(input: FloatArray): FloatArray {
        val f = factor
        if (f in 0.999f..1.001f) {
            // Leftovers from an earlier speed would be spliced into the next
            // time a speed is set; start that from clean instead.
            if (primed || pendingLength > 0) reset()
            return input
        }

        if (pending.size < pendingLength + input.size) {
            pending = pending.copyOf(maxOf(pendingLength + input.size, pending.size * 2))
        }
        System.arraycopy(input, 0, pending, pendingLength, input.size)
        pendingLength += input.size

        val sequence = sequenceFrames(f)
        val seek = seekFrames(f)
        val body = sequence - 2 * overlap
        val advance = f * (sequence - overlap)

        var written = 0
        var read = 0
        while ((read + seek + sequence) * 2 <= pendingLength) {
            val start = read + if (primed) bestOffset(read, seek) else 0
            val needed = written + (sequence - overlap) * 2
            if (output.size < needed) output = output.copyOf(maxOf(needed, output.size * 2))

            // Crossfade from the end of the previous sequence into this one.
            for (i in 0 until overlap) {
                val w = if (primed) fade[i] else 1f
                val at = (start + i) * 2
                output[written++] = previous[i * 2] * (1f - w) + pending[at] * w
                output[written++] = previous[i * 2 + 1] * (1f - w) + pending[at + 1] * w
            }
            // The middle goes through untouched.
            System.arraycopy(pending, (start + overlap) * 2, output, written, body * 2)
            written += body * 2
            // Its last stretch is held back, to fade from next time.
            System.arraycopy(pending, (start + sequence - overlap) * 2, previous, 0, overlap * 2)
            primed = true

            skipCarry += advance
            val skip = skipCarry.toInt()
            skipCarry -= skip
            read += skip
        }

        if (read > 0) {
            val keepFrom = (read * 2).coerceAtMost(pendingLength)
            System.arraycopy(pending, keepFrom, pending, 0, pendingLength - keepFrom)
            pendingLength -= keepFrom
        }
        return output.copyOf(written)
    }

    /**
     * Where, within [seek] frames of [read], the next sequence's opening best
     * continues the end of the previous one: every fourth offset first, then
     * each frame around the best of those.
     */
    private fun bestOffset(read: Int, seek: Int): Int {
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        var offset = 0
        while (offset < seek) {
            val score = similarity(read + offset)
            if (score > bestScore) {
                bestScore = score
                best = offset
            }
            offset += 4
        }
        val coarse = best
        for (fine in (coarse - 3)..(coarse + 3)) {
            if (fine == coarse || fine < 0 || fine >= seek) continue
            val score = similarity(read + fine)
            if (score > bestScore) {
                bestScore = score
                best = fine
            }
        }
        return best
    }

    /**
     * Correlation of the held-back end with the input at [start], both channels,
     * normalised by the input's energy so a loud stretch doesn't win over a
     * matching one.
     */
    private fun similarity(start: Int): Double {
        var correlation = 0.0
        var energy = 0.0
        val from = start * 2
        for (i in 0 until overlap * 2) {
            val sample = pending[from + i].toDouble()
            correlation += previous[i] * sample
            energy += sample * sample
        }
        return correlation / sqrt(energy + 1e-12)
    }
}

/**
 * Schroeder reverb: four parallel comb filters into two series all-pass stages.
 *
 * The classic arrangement, and the right size of tool here — a convolution
 * reverb would need impulse responses shipped with the app for a difference
 * most listeners would not pick out over a music player's output.
 */
class Reverb {

    @Volatile
    var enabled: Boolean = false

    @Volatile
    var mix: Float = 0.35f      // wet proportion

    @Volatile
    var decay: Float = 0.82f    // comb feedback

    private val combDelays = intArrayOf(1557, 1617, 1491, 1422)
    private val allPassDelays = intArrayOf(225, 556)

    private val combL = combDelays.map { FloatArray(it) }
    private val combR = combDelays.map { FloatArray(it + 23) }   // detune for width
    private val combIndexL = IntArray(combDelays.size)
    private val combIndexR = IntArray(combDelays.size)

    private val apL = allPassDelays.map { FloatArray(it) }
    private val apR = allPassDelays.map { FloatArray(it + 11) }
    private val apIndexL = IntArray(allPassDelays.size)
    private val apIndexR = IntArray(allPassDelays.size)

    fun reset() {
        (combL + combR + apL + apR).forEach { it.fill(0f) }
        combIndexL.fill(0); combIndexR.fill(0); apIndexL.fill(0); apIndexR.fill(0)
    }

    fun process(buffer: FloatArray) {
        if (!enabled || mix <= 0.001f) return
        val wet = mix.coerceIn(0f, 1f)
        val dry = 1f - wet * 0.5f      // gentle dry trim, not a full crossfade

        var i = 0
        while (i + 1 < buffer.size) {
            buffer[i] = dry * buffer[i] + wet * channel(
                buffer[i], combL, combIndexL, apL, apIndexL
            )
            buffer[i + 1] = dry * buffer[i + 1] + wet * channel(
                buffer[i + 1], combR, combIndexR, apR, apIndexR
            )
            i += 2
        }
    }

    private fun channel(
        input: Float,
        combs: List<FloatArray>,
        combIndex: IntArray,
        allPass: List<FloatArray>,
        apIndex: IntArray
    ): Float {
        var sum = 0f
        for (c in combs.indices) {
            val line = combs[c]
            val idx = combIndex[c]
            val delayed = line[idx]
            line[idx] = input + delayed * decay
            combIndex[c] = (idx + 1) % line.size
            sum += delayed
        }
        sum /= combs.size

        for (a in allPass.indices) {
            val line = allPass[a]
            val idx = apIndex[a]
            val delayed = line[idx]
            val out = delayed - sum * ALL_PASS_GAIN
            line[idx] = sum + delayed * ALL_PASS_GAIN
            apIndex[a] = (idx + 1) % line.size
            sum = out
        }
        return sum
    }

    private companion object {
        const val ALL_PASS_GAIN = 0.7f
    }
}

/**
 * Ten-band graphic equalizer: peaking filters an octave apart from 31 Hz to
 * 16 kHz, +-12 dB each.
 *
 * Gains arrive from the UI thread and are picked up at the start of the next
 * buffer, where the filters are redesigned; their memory is kept, so moving a
 * band while music plays doesn't click. A band at 0 dB is skipped. Boosts can
 * push peaks past full scale, so anything above the knee eases into a soft
 * ceiling instead of clipping hard.
 */
class Equalizer(private val sampleRate: Double = 44_100.0) {

    @Volatile
    var enabled: Boolean = false

    private val incoming = AtomicReference<FloatArray?>(null)
    private var gains = FloatArray(BANDS.size)
    private val b0 = DoubleArray(BANDS.size)
    private val b1 = DoubleArray(BANDS.size)
    private val b2 = DoubleArray(BANDS.size)
    private val a1 = DoubleArray(BANDS.size)
    private val a2 = DoubleArray(BANDS.size)

    // Transposed direct form II state: two values per band per channel.
    private val z1 = DoubleArray(BANDS.size * 2)
    private val z2 = DoubleArray(BANDS.size * 2)

    init {
        design()
    }

    fun setGains(db: List<Float>) {
        incoming.set(FloatArray(BANDS.size) { db.getOrElse(it) { 0f }.coerceIn(-12f, 12f) })
    }

    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    fun process(buffer: FloatArray) {
        incoming.getAndSet(null)?.let {
            gains = it
            design()
        }
        if (!enabled) return
        var boosted = false
        for (band in BANDS.indices) {
            if (abs(gains[band]) < 0.05f) continue
            if (gains[band] > 0f) boosted = true
            val c0 = b0[band]
            val c1 = b1[band]
            val c2 = b2[band]
            val d1 = a1[band]
            val d2 = a2[band]
            var i = 0
            while (i + 1 < buffer.size) {
                for (channel in 0..1) {
                    val k = band * 2 + channel
                    val x = buffer[i + channel].toDouble()
                    val y = c0 * x + z1[k]
                    z1[k] = c1 * x - d1 * y + z2[k]
                    z2[k] = c2 * x - d2 * y
                    buffer[i + channel] = y.toFloat()
                }
                i += 2
            }
        }
        if (boosted) {
            for (i in buffer.indices) {
                val x = buffer[i]
                val magnitude = abs(x)
                if (magnitude > KNEE) {
                    buffer[i] = sign(x) * (KNEE + (1f - KNEE) * tanh((magnitude - KNEE) / (1f - KNEE)))
                }
            }
        }
    }

    /** RBJ cookbook peaking filters. */
    private fun design() {
        for (band in BANDS.indices) {
            val amplitude = 10.0.pow(gains[band] / 40.0)
            val w0 = 2.0 * PI * BANDS[band] / sampleRate
            val alpha = sin(w0) / (2.0 * Q)
            val cosine = cos(w0)
            val a0 = 1.0 + alpha / amplitude
            b0[band] = (1.0 + alpha * amplitude) / a0
            b1[band] = -2.0 * cosine / a0
            b2[band] = (1.0 - alpha * amplitude) / a0
            a1[band] = -2.0 * cosine / a0
            a2[band] = (1.0 - alpha / amplitude) / a0
        }
    }

    companion object {
        val BANDS = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0)
        private const val Q = 1.41
        private const val KNEE = 0.9f
    }
}

/**
 * Speed, pitch and reverb, composed the same way as the Android build: three
 * independent axes that stack.
 *
 * Pitch is a resample (which moves tempo too), then the stretcher restores the
 * requested tempo. So `resample(p)` followed by `stretch(speed / p)` yields
 * tempo = speed and pitch = p, with either adjustable on its own.
 */
class EffectChain {

    val stretcher = TimeStretcher()
    val reverb = Reverb()
    val equalizer = Equalizer()

    /**
     * Continuous across buffers. The pitch shift used to resample each buffer on
     * its own with the fingerprinting filter, which was both slow and seamed at
     * every buffer boundary.
     */
    private val pitchResampler = StreamingResampler(2)
    private var pitchActive = false

    @Volatile
    var speed: Float = 1f

    @Volatile
    var pitchSemitones: Float = 0f

    @Volatile
    var volume: Float = 1f

    fun reset() {
        stretcher.reset()
        reverb.reset()
        equalizer.reset()
        pitchResampler.reset()
        pitchActive = false
    }

    fun process(input: FloatArray): FloatArray {
        val pitch = 2f.pow(pitchSemitones / 12f)
        var buffer = input

        if (pitch !in 0.999f..1.001f) {
            // History from an earlier stretch of pitched audio is stale by now.
            if (!pitchActive) {
                pitchResampler.reset()
                pitchActive = true
            }
            buffer = pitchResampler.process(buffer, buffer.size / 2, pitch.toDouble())
        } else {
            pitchActive = false
        }
        stretcher.factor = speed / pitch
        buffer = stretcher.process(buffer)

        equalizer.process(buffer)
        reverb.process(buffer)

        if (volume !in 0.999f..1.001f) {
            for (i in buffer.indices) buffer[i] *= volume
        }
        return buffer
    }
}
