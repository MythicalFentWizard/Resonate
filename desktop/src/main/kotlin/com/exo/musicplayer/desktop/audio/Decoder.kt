package com.exo.musicplayer.desktop.audio

import com.exo.musicplayer.desktop.data.ToolPaths
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.UnsupportedAudioFileException
import kotlin.math.ceil

/** Consecutive empty reads tolerated before a stream is taken to have stalled. */
private const val MAX_IDLE_READS = 64

/** Everything is decoded to this rate before it reaches the effects and the device. */
private val OUTPUT_RATE: Float get() = AudioDevices.FORMAT.sampleRate

/** An audio file as 44.1 kHz interleaved stereo floats in -1..1. */
interface Decoder : AutoCloseable {

    /** Total frames at the output rate, or -1 when the container doesn't say. */
    val totalFrames: Long

    /** Output frames read or skipped so far, counted from the start of the file. */
    val positionFrames: Long

    /** Up to [frames] output frames, or null at the end of the stream. */
    fun read(frames: Int): FloatArray?

    /** Moves forward by [frames] output frames without producing them. */
    fun skip(frames: Long): Long

    companion object {

        /**
         * What goes through Java Sound in-process.
         *
         * Deliberately narrow. OGG and FLAC have Java Sound providers too, but the
         * Vorbis one returns empty reads mid-stream, which the skip took for the end
         * of the file: seeking an .ogg to 22 s left it playing from the start. ffmpeg
         * decodes both and seeks them properly, so they go there.
         */
        private val JAVA_SOUND_EXTENSIONS =
            setOf("mp3", "wav", "wave", "aif", "aiff", "aifc", "au")

        /**
         * Opens [file] positioned at [startFrame], with whichever decoder can read it.
         *
         * Java Sound first for the formats its providers handle, because it runs
         * in-process. Everything else, and anything Java Sound refuses (an Opus
         * stream in a .ogg file, say), goes to the ffmpeg bundled with the app.
         * Before this, m4a, AAC, ALAC, Opus, WebM, Matroska, WMA, WavPack, AC-3 and
         * TTA files were listed in the library and then could not be played.
         */
        fun open(file: File, startFrame: Long = 0L): Decoder {
            if (file.extension.lowercase() in JAVA_SOUND_EXTENSIONS) {
                val native = runCatching { JavaSoundDecoder(file) }.getOrNull()
                if (native != null) {
                    if (startFrame > 0) native.skip(startFrame)
                    return native
                }
            }
            val ffmpeg = ToolPaths.ffmpeg
            if (ffmpeg.isFile) return FfmpegDecoder(ffmpeg, file, startFrame)

            // No ffmpeg, as when running from a plain classpath: whatever Java Sound
            // manages is better than nothing.
            val fallback = runCatching { JavaSoundDecoder(file) }.getOrNull()
                ?: throw UnsupportedAudioFileException(
                    "Playing .${file.extension} files needs ffmpeg, which is missing from this installation."
                )
            if (startFrame > 0) fallback.skip(startFrame)
            return fallback
        }
    }
}

/**
 * MP3, Vorbis, FLAC, WAV and AIFF through the Java Sound service providers.
 *
 * Two things here are shaped by measurements on a five-minute MP3:
 *
 *  - Rate conversion goes through [StreamingResampler] rather than the
 *    fingerprinting resampler this used to share, which cost 11.6 ms of every
 *    92.9 ms buffer on a 48 kHz file.
 *  - [skip] reads and discards decoded audio in large chunks. Seeking used to
 *    decode, convert and resample everything up to the target (22.8 s to reach
 *    three minutes into a 48 kHz file); AudioInputStream.skip was slower still,
 *    at 50 to 62 s. Chunked reads take about 0.9 s.
 *
 * Buffers are reused between reads. This runs on the playback thread, where a
 * steady stream of garbage is what turns into audible hitches.
 */
class JavaSoundDecoder(file: File) : Decoder {

    private val source: AudioInputStream = AudioSystem.getAudioInputStream(file)
    private val pcm: AudioInputStream
    private val sourceRate: Float
    private val sourceChannels: Int

    /** Source frames per output frame. */
    private val step: Double
    private val resampling: Boolean
    private val resampler = StreamingResampler(2)

    override val totalFrames: Long

    override var positionFrames: Long = 0L
        private set

    private var bytes = ByteArray(0)
    private var stereo = FloatArray(0)

    init {
        val base = source.format
        sourceRate = base.sampleRate.takeIf { it > 0 } ?: OUTPUT_RATE
        sourceChannels = base.channels.coerceAtLeast(1)
        step = sourceRate.toDouble() / OUTPUT_RATE
        resampling = sourceRate.toInt() != OUTPUT_RATE.toInt()

        val decoded = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            sourceRate,
            16,
            sourceChannels,
            sourceChannels * 2,
            sourceRate,
            false
        )
        pcm = AudioSystem.getAudioInputStream(decoded, source)

        val frames = source.frameLength
        totalFrames = if (frames > 0) (frames * (OUTPUT_RATE / sourceRate)).toLong() else -1L
    }

    override fun read(frames: Int): FloatArray? {
        val frameBytes = sourceChannels * 2
        val sourceFrames = if (resampling) ceil(frames * step).toInt() + 2 else frames
        val wanted = sourceFrames * frameBytes
        if (bytes.size < wanted) bytes = ByteArray(wanted)

        var filled = 0
        var idle = 0
        while (filled < wanted) {
            val read = pcm.read(bytes, filled, wanted - filled)
            if (read < 0) break
            // A provider may return 0 with more to come; only -1 is the end.
            if (read == 0) {
                if (++idle > MAX_IDLE_READS) break
                continue
            }
            idle = 0
            filled += read
        }
        val frameCount = filled / frameBytes
        if (frameCount <= 0) return null

        // 16-bit little-endian to float, folding to stereo.
        if (stereo.size < frameCount * 2) stereo = FloatArray(frameCount * 2)
        val mono = sourceChannels == 1
        for (i in 0 until frameCount) {
            val base = i * frameBytes
            val left = ((bytes[base + 1].toInt() shl 8) or (bytes[base].toInt() and 0xFF)) / 32768f
            stereo[i * 2] = left
            stereo[i * 2 + 1] = if (mono) {
                left
            } else {
                val o = base + 2
                ((bytes[o + 1].toInt() shl 8) or (bytes[o].toInt() and 0xFF)) / 32768f
            }
        }

        val out = if (resampling) {
            resampler.process(stereo, frameCount, step)
        } else {
            stereo.copyOf(frameCount * 2)
        }
        positionFrames += out.size / 2
        return out
    }

    override fun skip(frames: Long): Long {
        if (frames <= 0) return 0L
        val frameBytes = (sourceChannels * 2).toLong()
        val target = (frames * step).toLong() * frameBytes
        var remaining = target
        var idle = 0

        while (remaining > 0) {
            val chunk = (minOf(remaining, 65536L) / frameBytes * frameBytes).toInt()
            if (chunk <= 0) break
            if (bytes.size < chunk) bytes = ByteArray(chunk)
            val read = pcm.read(bytes, 0, chunk)
            if (read < 0) break
            if (read == 0) {
                if (++idle > MAX_IDLE_READS) break
                continue
            }
            idle = 0
            remaining -= read
        }

        // The interpolation history belongs to the old position.
        resampler.reset()
        val moved = (((target - remaining) / frameBytes) / step).toLong()
        positionFrames += moved
        return moved
    }

    override fun close() {
        runCatching { pcm.close() }
        runCatching { source.close() }
    }
}

/**
 * Everything Java Sound cannot open, decoded by the ffmpeg that ships with the app.
 *
 * ffmpeg is asked for exactly what the output wants, 44.1 kHz stereo 16-bit PCM
 * on stdout, so nothing is resampled here. A long skip restarts it with -ss
 * before -i, which seeks within the container instead of decoding up to the
 * target; a short one just reads on.
 */
class FfmpegDecoder(
    private val ffmpeg: File,
    private val file: File,
    startFrame: Long = 0L
) : Decoder {

    override val totalFrames: Long = -1L

    override var positionFrames: Long = 0L
        private set

    private var process: Process? = null
    private var input: InputStream? = null
    private var bytes = ByteArray(0)

    /** Beyond this, restarting at the new position is quicker than reading up to it. */
    private val restartFrames = (OUTPUT_RATE * 5).toLong()

    init {
        start(startFrame.coerceAtLeast(0L))
    }

    private fun start(frame: Long) {
        stop()
        val command = buildList {
            add(ffmpeg.absolutePath)
            addAll(listOf("-hide_banner", "-loglevel", "error", "-nostdin"))
            if (frame > 0) {
                add("-ss")
                add(String.format(Locale.ROOT, "%.3f", frame / OUTPUT_RATE.toDouble()))
            }
            addAll(
                listOf(
                    "-i", file.absolutePath,
                    "-vn",   // embedded cover art is a video stream; ignore it
                    "-f", "s16le", "-ac", "2", "-ar", OUTPUT_RATE.toInt().toString(),
                    "pipe:1"
                )
            )
        }
        val started = ProcessBuilder(command)
            // Discarded rather than piped: an unread stderr fills its buffer and
            // stalls ffmpeg mid-song, which sounds exactly like a hang.
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process = started
        input = BufferedInputStream(started.inputStream, 1 shl 16)
        positionFrames = frame
    }

    override fun read(frames: Int): FloatArray? {
        val stream = input ?: return null
        val wanted = frames * 4
        if (bytes.size < wanted) bytes = ByteArray(wanted)
        var filled = 0
        while (filled < wanted) {
            val read = stream.read(bytes, filled, wanted - filled)
            if (read <= 0) break
            filled += read
        }
        val frameCount = filled / 4
        if (frameCount <= 0) return null

        val out = FloatArray(frameCount * 2)
        for (i in out.indices) {
            val b = i * 2
            out[i] = ((bytes[b + 1].toInt() shl 8) or (bytes[b].toInt() and 0xFF)) / 32768f
        }
        positionFrames += frameCount
        return out
    }

    override fun skip(frames: Long): Long {
        if (frames <= 0) return 0L
        if (frames > restartFrames) {
            start(positionFrames + frames)
            return frames
        }
        val stream = input ?: return 0L
        var remaining = frames * 4
        while (remaining > 0) {
            val chunk = minOf(remaining, 65536L).toInt()
            if (bytes.size < chunk) bytes = ByteArray(chunk)
            val read = stream.read(bytes, 0, chunk)
            if (read <= 0) break
            remaining -= read
        }
        val moved = (frames * 4 - remaining) / 4
        positionFrames += moved
        return moved
    }

    override fun close() = stop()

    private fun stop() {
        runCatching { input?.close() }
        process?.let { running ->
            running.destroy()
            runCatching { running.waitFor(500, TimeUnit.MILLISECONDS) }
            if (running.isAlive) running.destroyForcibly()
        }
        process = null
        input = null
    }
}
