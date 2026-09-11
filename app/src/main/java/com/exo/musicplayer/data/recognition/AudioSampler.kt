package com.exo.musicplayer.data.recognition

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a mono 16 kHz snippet from any media file Android can open — an mp3,
 * or the audio track buried inside an mp4.
 *
 * Uses the platform MediaExtractor/MediaCodec rather than FFmpeg: the
 * fingerprint needs twelve seconds of intelligible audio, and shipping an
 * FFmpeg build would add tens of megabytes for something the OS already does.
 */
object AudioSampler {

    private const val TAG = "AudioSampler"
    private const val TIMEOUT_US = 10_000L

    /**
     * @return mono PCM at [ShazamSignature.SAMPLE_RATE], padded to the full
     *   window, or null if the file has no decodable audio track.
     */
    suspend fun sampleMono16k(
        context: Context,
        uri: Uri,
        seconds: Int = ShazamSignature.WINDOW_SECONDS
    ): FloatArray? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)

            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    trackIndex = i
                    inputFormat = format
                    break
                }
            }
            if (trackIndex < 0 || inputFormat == null) {
                Log.w(TAG, "No audio track in $uri")
                return@withContext null
            }
            extractor.selectTrack(trackIndex)

            // Take the middle of the recording, as the reference client does:
            // intros are often silence, a logo sting, or spoken word.
            val durationUs = runCatching {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            }.getOrDefault(0L)
            val windowUs = seconds * 1_000_000L
            if (durationUs > windowUs) {
                extractor.seekTo(
                    (durationUs / 2 - windowUs / 2).coerceAtLeast(0L),
                    MediaExtractor.SEEK_TO_CLOSEST_SYNC
                )
            }

            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var sampleRate = inputFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var channels = inputFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2)

            // Decode a little extra so resampling has material at the edges.
            val wantedInputSamples = (seconds + 1) * sampleRate
            var pcm = FloatArray(wantedInputSamples)
            var written = 0

            val info = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var sawOutputEnd = false

            while (!sawOutputEnd && written < wantedInputSamples) {
                if (!sawInputEnd) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val read = extractor.readSampleData(buffer, 0)
                        if (read < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        val newRate = out.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = out.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        if (newRate != sampleRate) {
                            sampleRate = newRate
                            pcm = pcm.copyOf((seconds + 1) * sampleRate)
                        }
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            written = appendMono(buffer, channels, pcm, written)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEnd = true
                        }
                    }
                }
            }

            if (written == 0) return@withContext null

            val decoded = pcm.copyOf(written)
            val resampled = Dsp.resample(decoded, sampleRate, ShazamSignature.SAMPLE_RATE)

            // The reference pads short input rather than fingerprinting a stub,
            // and copyOf does both halves of that: it truncates a long window and
            // zero-pads a short one.
            val target = seconds * ShazamSignature.SAMPLE_RATE
            resampled.copyOf(target)
        } catch (t: Throwable) {
            Log.w(TAG, "Sampling failed for $uri", t)
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Downmixes interleaved 16-bit PCM to mono floats in [-1, 1). */
    private fun appendMono(
        buffer: ByteBuffer,
        channels: Int,
        out: FloatArray,
        offset: Int
    ): Int {
        val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val safeChannels = channels.coerceAtLeast(1)
        val frames = shorts.remaining() / safeChannels
        var written = offset
        for (frame in 0 until frames) {
            if (written >= out.size) break
            var sum = 0
            for (channel in 0 until safeChannels) {
                sum += shorts.get(frame * safeChannels + channel).toInt()
            }
            out[written++] = (sum.toFloat() / safeChannels) / 32768f
        }
        return written
    }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        runCatching { getInteger(key) }.getOrDefault(fallback)
}
