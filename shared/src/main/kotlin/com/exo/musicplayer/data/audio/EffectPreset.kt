package com.exo.musicplayer.data.audio

/**
 * The effect presets, defined once for both platforms.
 *
 * These used to live only in the Android build, which is exactly how the two
 * drift: the desktop app grew its own shorter list and quietly lost Nightcore
 * and Deep. The reverb model differs between the platforms — Android picks a
 * named room, the desktop sets a decay coefficient — so [roomSize] is given
 * abstractly and each side maps it onto its own.
 *
 * Speed and pitch are independent here, which is worth being deliberate about.
 * Playing a record faster raises its pitch by exactly `12 * log2(speed)`
 * semitones, so a preset imitating that has to pair the two precisely;
 * [NIGHTCORE] does. A preset that is *not* imitating a resample is free to
 * pitch further than its tempo would imply, and [DEEP] does that on purpose —
 * barely slower, but dropped a long way down, which is the whole character of
 * it.
 */
enum class EffectPreset(
    val label: String,
    val speed: Float,
    val pitchSemitones: Float,
    val reverb: Boolean = false,
    val reverbAmount: Float = 0.4f,
    /** 0 = small and tight, 1 = enormous. Mapped per platform. */
    val roomSize: Float = 0.5f,
    val note: String
) {
    NORMAL(
        label = "Normal",
        speed = 1f,
        pitchSemitones = 0f,
        note = "Untouched"
    ),

    SLOWED(
        label = "Slowed",
        speed = 0.85f,
        pitchSemitones = -1.5f,
        note = "Slower, pitch dropped less than the tempo"
    ),

    SLOWED_REVERB(
        label = "Slowed + reverb",
        speed = 0.82f,
        pitchSemitones = -2f,
        reverb = true,
        reverbAmount = 0.55f,
        roomSize = 0.78f,
        note = "The familiar edit: slow, low, and a long tail"
    ),

    SPED_UP(
        label = "Sped up",
        speed = 1.25f,
        pitchSemitones = 1.5f,
        note = "Faster, pitch raised less than the tempo"
    ),

    /**
     * Pitch matched exactly to tempo: `12 * log2(1.30) = +4.54`, so this sounds
     * like the record simply being played fast, which is what nightcore is.
     */
    NIGHTCORE(
        label = "Nightcore",
        speed = 1.30f,
        pitchSemitones = 4.54f,
        note = "Fast and bright, pitch tracking tempo exactly"
    ),

    /**
     * Deliberately *not* a resample. Only slightly slower, but dropped four
     * semitones, which is far below what 0.92x would do on its own.
     */
    DEEP(
        label = "Deep",
        speed = 0.92f,
        pitchSemitones = -4f,
        reverb = true,
        reverbAmount = 0.45f,
        roomSize = 0.9f,
        note = "Barely slower, but dropped well below its tempo"
    ),

    CAVERN(
        label = "Cavern",
        speed = 1f,
        pitchSemitones = 0f,
        reverb = true,
        reverbAmount = 0.6f,
        roomSize = 1f,
        note = "Original speed, enormous space"
    );

    companion object {
        /** Semitone shift a plain resample at [speed] would produce. */
        fun resamplePitch(speed: Float): Float =
            (12.0 * kotlin.math.ln(speed.toDouble()) / kotlin.math.ln(2.0)).toFloat()
    }
}
