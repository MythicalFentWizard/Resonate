package com.exo.musicplayer.desktop.audio

import com.exo.musicplayer.data.audio.SpectrumAnalyser
import com.exo.musicplayer.desktop.library.DesktopTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

data class PlaybackStatus(
    val track: DesktopTrack? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * The Windows playback engine.
 *
 * One decode feeds every output. A dedicated thread pulls PCM from [Decoder],
 * runs it through [EffectChain], and writes the *same* buffer to each open
 * line — so playing to several devices costs one decode, and every device is
 * sample-identical by construction rather than by periodic resynchronisation.
 * That is the piece Android could not do properly.
 *
 * Writes to secondary devices are best-effort: a stalled or unplugged endpoint
 * is dropped rather than allowed to block the device actually being listened to.
 */
class PlaybackEngine {

    /** Position updates coarser than this are invisible on screen. */
    private val PUBLISH_INTERVAL_MS = 250L

    private val _status = MutableStateFlow(PlaybackStatus())
    val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    val effects = EffectChain()

    /** Band levels for the mixer display; idle unless something is showing it. */
    val spectrum = SpectrumAnalyser()

    /** The bass as it reaches the speakers, for the reactive backgrounds. */
    val beat = BeatTap()

    private var worker: Thread? = null
    private var lines = listOf<SourceDataLine>()
    private var outputs = listOf<DesktopAudioOutput>()

    @Volatile private var playing = false
    @Volatile private var stopRequested = false
    @Volatile private var seekToMs: Long? = null
    @Volatile private var outputsDirty = false
    @Volatile private var framesPlayed = 0L
    private var lastPublished = -1L

    /** Devices to play through. The first is primary; the rest are mirrors. */
    fun setOutputs(selected: List<DesktopAudioOutput>) {
        outputs = selected
        // Flagged rather than applied here: opening and closing lines from the
        // UI thread while the playback thread is writing to them is a race.
        // The worker picks this up on its next pass.
        outputsDirty = true
    }

    fun play(track: DesktopTrack) {
        stop()
        stopRequested = false
        playing = true
        framesPlayed = 0
        lastPublished = -1
        effects.reset()
        spectrum.reset()
        beat.reset()
        _status.value = PlaybackStatus(track = track, playing = true, durationMs = track.durationMs)

        // Above normal priority: a playback thread that loses its time slice to
        // the UI thread is an audible gap, and it does very little per wake-up.
        worker = thread(name = "resonate-playback", isDaemon = true, priority = Thread.MAX_PRIORITY) {
            run(track)
        }
    }

    fun togglePlay() {
        if (_status.value.track == null) return
        playing = !playing
        _status.value = _status.value.copy(playing = playing)
    }

    fun seekTo(ms: Long) { seekToMs = ms.coerceAtLeast(0) }

    fun stop() {
        stopRequested = true
        playing = false
        spectrum.reset()
        worker?.join(600)
        worker = null
        closeLines()
        _status.value = _status.value.copy(playing = false)
    }

    fun setVolume(value: Float) { effects.volume = value.coerceIn(0f, 1f) }

    private fun run(track: DesktopTrack) {
        var decoder: Decoder? = null
        try {
            decoder = Decoder.open(track.file)
            reopenLines()
            if (lines.isEmpty()) {
                _status.value = _status.value.copy(
                    playing = false,
                    error = "No audio output could be opened."
                )
                return
            }

            val rate = AudioDevices.FORMAT.sampleRate.toInt()
            val duration = if (track.durationMs > 0) track.durationMs else -1L

            while (!stopRequested) {
                if (outputsDirty) {
                    outputsDirty = false
                    reopenLines()
                }

                // Handled before the pause check, so a seek made while paused
                // moves the position straight away rather than on resume.
                seekToMs?.let { target ->
                    seekToMs = null
                    decoder = seek(decoder, track, target * rate / 1000)
                    framesPlayed = decoder?.positionFrames ?: 0L
                    effects.reset()
                    spectrum.reset()
                    beat.reset()
                    // Drops what is already queued at the old position. Without
                    // it, up to a fifth of a second of the old spot still plays
                    // after the jump, which is what makes a seek feel sluggish.
                    lines.forEach { runCatching { it.flush() } }
                    publishPosition(duration, rate, force = true)
                }

                if (!playing) { Thread.sleep(40); continue }

                val raw = decoder?.read(4096) ?: break

                val processed = effects.process(raw)
                framesPlayed += raw.size / 2
                if (processed.isEmpty()) continue
                // After the chain, so speed, pitch and reverb are all visible
                // in the meter rather than it showing the untouched source.
                spectrum.feed(processed)
                beat.feed(processed, queuedFrames(), rate)

                writeToAll(toBytes(processed))
                publishPosition(duration, rate)
            }
        } catch (t: Throwable) {
            _status.value = _status.value.copy(
                playing = false,
                error = t.message ?: "Playback failed."
            )
        } finally {
            runCatching { decoder?.close() }
            if (!stopRequested) {
                _status.value = _status.value.copy(playing = false)
            }
        }
    }

    /**
     * Moves playback to [frame], reopening the file only when that is behind the
     * decoder.
     *
     * Forward is the common case, a click a little further along, and carrying on
     * from where the decoder already is avoids passing over the start of the file
     * again. A compressed stream cannot go backwards, so that reopens.
     */
    private fun seek(current: Decoder?, track: DesktopTrack, frame: Long): Decoder? {
        if (current != null && frame >= current.positionFrames) {
            current.skip(frame - current.positionFrames)
            return current
        }
        runCatching { current?.close() }
        return Decoder.open(track.file, frame)
    }

    /**
     * Published four times a second rather than once per buffer. Every emission
     * recomposes the transport bar and repaints a frame, and at about eleven
     * buffers a second that was an order of magnitude more redrawing than a 3px
     * progress bar can show.
     */
    private fun publishPosition(duration: Long, rate: Int, force: Boolean = false) {
        val positionMs = framesPlayed * 1000 / rate
        val slot = positionMs / PUBLISH_INTERVAL_MS
        if (!force && slot == lastPublished) return
        lastPublished = slot
        _status.value = _status.value.copy(
            positionMs = positionMs,
            durationMs = if (duration > 0) duration else positionMs
        )
    }

    /** Frames handed to the primary line that it has not played yet. */
    private fun queuedFrames(): Int = runCatching {
        val line = lines.firstOrNull() ?: return 0
        (line.bufferSize - line.available()) / line.format.frameSize
    }.getOrDefault(0)

    private fun writeToAll(bytes: ByteArray) {
        val current = lines
        if (current.isEmpty()) return
        // The primary write blocks, which is what paces playback.
        runCatching { current[0].write(bytes, 0, bytes.size) }
        for (i in 1 until current.size) {
            // Mirrors must never stall the primary, so they get whatever the
            // line can take right now and drop the rest.
            runCatching {
                val room = current[i].available()
                if (room > 0) current[i].write(bytes, 0, minOf(room, bytes.size))
            }
        }
    }

    /**
     * Scratch buffer for PCM conversion, reused across the whole track.
     *
     * Allocating one per buffer meant roughly eleven short-lived 16 KB arrays a
     * second for as long as anything was playing — enough steady garbage to keep
     * the heap growing for no reason. Only the playback thread touches it.
     */
    private var pcmScratch = ByteArray(0)

    private fun toBytes(samples: FloatArray): ByteArray {
        val needed = samples.size * 2
        if (pcmScratch.size != needed) pcmScratch = ByteArray(needed)
        val out = pcmScratch
        for (i in samples.indices) {
            val clamped = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            out[i * 2] = (clamped and 0xFF).toByte()
            out[i * 2 + 1] = ((clamped shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun reopenLines() {
        closeLines()
        val targets = outputs.ifEmpty { listOf(DesktopAudioOutput("System default", null)) }
        lines = targets.mapNotNull { AudioDevices.openLine(it) }
    }

    private fun closeLines() {
        lines.forEach {
            runCatching { it.drain() }
            runCatching { it.stop() }
            runCatching { it.close() }
        }
        lines = emptyList()
    }

    fun release() {
        stop()
    }
}
