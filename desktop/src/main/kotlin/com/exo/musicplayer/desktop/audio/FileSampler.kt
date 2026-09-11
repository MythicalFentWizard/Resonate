package com.exo.musicplayer.desktop.audio

import com.exo.musicplayer.data.recognition.Dsp
import com.exo.musicplayer.data.recognition.ShazamSignature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Decodes a window of a file to mono 16 kHz, ready to fingerprint.
 *
 * Sampled from the middle of the track for the same reason the Android build
 * does it: intros are frequently silence, a label sting or spoken word, none of
 * which produce peaks Shazam can match. Everything here runs through the same
 * [Decoder] used for playback, so any format the player can open can also be
 * identified.
 */
object FileSampler {

    suspend fun sampleMono16k(
        file: File,
        durationMs: Long,
        seconds: Int = ShazamSignature.WINDOW_SECONDS
    ): FloatArray? = withContext(Dispatchers.IO) {
        val rate = AudioDevices.FORMAT.sampleRate.toInt()
        val wantedFrames = seconds * rate

        Decoder.open(file).use { decoder ->
            // Half-way in, but never so far that less than a full window is left.
            val startFrame = if (durationMs > seconds * 1000L) {
                val midpoint = (durationMs / 2) * rate / 1000
                val latest = (durationMs * rate / 1000) - wantedFrames
                midpoint.coerceIn(0, maxOf(0, latest))
            } else {
                0L
            }

            // Skipped rather than read: reading converted and resampled every frame
            // on the way to the middle of the track, only to throw it away.
            decoder.skip(startFrame)

            val mono = FloatArray(wantedFrames)
            var filled = 0
            while (filled < wantedFrames) {
                val chunk = decoder.read(8192) ?: break
                val frames = chunk.size / 2
                for (i in 0 until frames) {
                    if (filled >= wantedFrames) break
                    mono[filled++] = (chunk[i * 2] + chunk[i * 2 + 1]) * 0.5f
                }
            }

            if (filled < rate) return@withContext null      // under a second of audio
            // Left zero-padded past `filled`, which is what the reference client
            // does for short recordings.
            Dsp.resample(mono, rate, ShazamSignature.SAMPLE_RATE)
        }
    }
}
