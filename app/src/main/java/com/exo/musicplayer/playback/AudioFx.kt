package com.exo.musicplayer.playback

import com.exo.musicplayer.data.audio.EffectPreset

import kotlin.math.pow
import kotlin.math.roundToInt

/** Room character for the reverb send. */
enum class ReverbRoom(
    val label: String,
    val decayMs: Int,
    val roomLevelMb: Int,
    val decayHfRatio: Int,
    val reverbLevelMb: Int,
    val diffusion: Int,
    val density: Int
) {
    ROOM("Room", decayMs = 1100, roomLevelMb = -1200, decayHfRatio = 600, reverbLevelMb = -400, diffusion = 900, density = 900),
    HALL("Hall", decayMs = 2400, roomLevelMb = -1000, decayHfRatio = 700, reverbLevelMb = -200, diffusion = 1000, density = 1000),
    CATHEDRAL("Cathedral", decayMs = 4500, roomLevelMb = -900, decayHfRatio = 800, reverbLevelMb = 0, diffusion = 1000, density = 1000),
    PLATE("Plate", decayMs = 1800, roomLevelMb = -1100, decayHfRatio = 1000, reverbLevelMb = -300, diffusion = 700, density = 600);

    companion object {
        fun fromName(name: String?): ReverbRoom =
            entries.firstOrNull { it.name == name } ?: HALL
    }
}

/**
 * The three effects, held together.
 *
 * Deliberately not an enum of modes: "slowed", "sped up" and "reverb" are
 * independent axes, and the whole point of the request is that they stack. The
 * presets below just set all three at once.
 */
data class AudioFxState(
    /** Tempo multiplier. 1.0 is untouched. */
    val speed: Float = 1f,
    /** Pitch shift in semitones, independent of tempo. */
    val pitchSemitones: Float = 0f,
    val reverbEnabled: Boolean = false,
    val reverbRoom: ReverbRoom = ReverbRoom.HALL,
    /** Wet send level, 0..1. */
    val reverbAmount: Float = 0.4f
) {
    /** Media3 wants a frequency ratio, not semitones. */
    val pitchRatio: Float get() = 2f.pow(pitchSemitones / 12f)

    val isDefault: Boolean
        get() = speed == 1f && pitchSemitones == 0f && !reverbEnabled

    val speedLabel: String get() = "${(speed * 100).roundToInt() / 100f}×"

    val pitchLabel: String
        get() {
            val rounded = (pitchSemitones * 10).roundToInt() / 10f
            return when {
                rounded > 0 -> "+$rounded st"
                rounded < 0 -> "$rounded st"
                else -> "0 st"
            }
        }

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f
        const val MIN_SEMITONES = -12f
        const val MAX_SEMITONES = 12f
    }
}

/** One-tap combinations. Every slider stays adjustable afterwards. */
/**
 * The preset list, taken from [EffectPreset] so Android and Windows cannot
 * offer different sets — which is exactly what had happened.
 *
 * Only the reverb mapping is platform-specific: Android's hardware effect takes
 * a named room, so the shared abstract room size is bucketed onto one.
 */
enum class FxPreset(val preset: EffectPreset) {
    NORMAL(EffectPreset.NORMAL),
    SLOWED(EffectPreset.SLOWED),
    SLOWED_REVERB(EffectPreset.SLOWED_REVERB),
    SPED_UP(EffectPreset.SPED_UP),
    NIGHTCORE(EffectPreset.NIGHTCORE),
    DEEP(EffectPreset.DEEP),
    CAVERN(EffectPreset.CAVERN);

    val label: String get() = preset.label
    val note: String get() = preset.note

    val state: AudioFxState
        get() = AudioFxState(
            speed = preset.speed,
            pitchSemitones = preset.pitchSemitones,
            reverbEnabled = preset.reverb,
            reverbRoom = roomFor(preset.roomSize),
            reverbAmount = preset.reverbAmount
        )

    private companion object {
        /** Buckets the shared 0..1 room size onto the nearest hardware room. */
        fun roomFor(size: Float): ReverbRoom = when {
            size < 0.35f -> ReverbRoom.ROOM
            size < 0.6f -> ReverbRoom.PLATE
            size < 0.85f -> ReverbRoom.HALL
            else -> ReverbRoom.CATHEDRAL
        }
    }
}
