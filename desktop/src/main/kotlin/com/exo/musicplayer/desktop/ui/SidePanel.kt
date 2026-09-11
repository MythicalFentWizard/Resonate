package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import com.exo.musicplayer.data.audio.SpectrumAnalyser
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.data.audio.EffectPreset
import com.exo.musicplayer.desktop.audio.DesktopAudioOutput
import com.exo.musicplayer.desktop.data.DesktopController
import com.exo.musicplayer.desktop.library.DesktopTrack
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Which side panel is showing, if any. */
enum class SidePanelKind { EFFECTS, OUTPUT, LYRICS }

data class DesktopFxState(
    val speed: Float = 1f,
    val pitchSemitones: Float = 0f,
    val reverbEnabled: Boolean = false,
    val reverbMix: Float = 0.35f,
    val reverbDecay: Float = 0.82f,
    val eqEnabled: Boolean = false,
    val eqGains: List<Float> = List(10) { 0f }
) {
    val isDefault: Boolean
        get() = abs(speed - 1f) < 0.005f && abs(pitchSemitones) < 0.05f && !reverbEnabled && !eqEnabled

    /**
     * Maps a shared preset onto this platform's reverb model.
     *
     * The shared definition gives room size abstractly, 0..1; here that becomes
     * the Schroeder decay coefficient, whose useful range stops at 0.94 because
     * beyond that the comb filters ring rather than decay.
     */
    fun applying(preset: EffectPreset) = copy(
        speed = preset.speed,
        pitchSemitones = preset.pitchSemitones,
        reverbEnabled = preset.reverb,
        reverbMix = preset.reverbAmount,
        reverbDecay = 0.40f + preset.roomSize * 0.54f
    )

    /** Whether these settings are still the ones [preset] would set. */
    fun matches(preset: EffectPreset): Boolean {
        if (reverbEnabled != preset.reverb) return false
        if (abs(speed - preset.speed) > 0.005f) return false
        if (abs(pitchSemitones - preset.pitchSemitones) > 0.05f) return false
        if (!reverbEnabled) return true
        return abs(reverbMix - preset.reverbAmount) < 0.02f &&
            abs(reverbDecay - (0.40f + preset.roomSize * 0.54f)) < 0.02f
    }
}

/**
 * Docked right-hand panel.
 *
 * A panel rather than a modal sheet: on a 1180px window there is room to leave
 * the controls open while browsing the library, and adjusting speed while a
 * track plays is exactly when you want to see the list too. The phone build had
 * to take over the screen; here that would be a regression.
 */
@Composable
fun SidePanel(
    kind: SidePanelKind,
    controller: DesktopController,
    track: DesktopTrack?,
    positionMs: Long,
    onClose: () -> Unit
) {
    Box(
        Modifier
            .width(if (kind == SidePanelKind.OUTPUT) 288.dp else 348.dp)
            .fillMaxHeight()
            .background(Palette.Sidebar.copy(alpha = if (controller.wallpaper != null) 0.75f else 1f))
    ) {
    if (kind == SidePanelKind.LYRICS) {
        Backdrop(
            style = controller.backdrop,
            color = Palette.Stars,
            spectrum = controller.engine.spectrum,
            graph = controller.songGraph,
            beat = controller.engine.beat,
            reactiveMode = controller.reactiveMode,
            modifier = Modifier.matchParentSize(),
            count = 50,
            centerpiece = false
        )
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when (kind) {
                    SidePanelKind.EFFECTS -> "Effects"
                    SidePanelKind.OUTPUT -> "Output"
                    SidePanelKind.LYRICS -> "Lyrics"
                },
                style = MaterialTheme.typography.titleLarge,
                color = Palette.Text,
                modifier = Modifier.weight(1f)
            )
            if (kind == SidePanelKind.LYRICS) {
                IconButton(onClick = { controller.lyricsDetached = true; onClose() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew, "Open lyrics in their own window",
                        Modifier.size(16.dp), tint = Palette.TextDim
                    )
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, "Close panel", Modifier.size(16.dp), tint = Palette.TextDim)
            }
        }
        Spacer(Modifier.height(10.dp))

        // The title and close button sit outside the scroll region: the
        // effects panel is taller than the window on a short display, and
        // scrolling the header away takes the way out with it.
        Column(
            Modifier.then(
                if (kind == SidePanelKind.LYRICS) {
                    Modifier
                } else {
                    Modifier.verticalScroll(rememberScrollState())
                }
            )
        ) {
        when (kind) {
            SidePanelKind.EFFECTS -> EffectsControls(
                fx = controller.fx,
                spectrum = controller.engine.spectrum,
                playing = controller.engine.status.value.playing,
                onFx = { controller.fx = it }
            )
            SidePanelKind.OUTPUT -> OutputControls(
                controller.outputs,
                controller.selectedOutputs,
                controller::toggleOutput
            )
            SidePanelKind.LYRICS -> LyricsPanel(controller, track, positionMs)
        }
        }
    }
    }
}

@Composable
private fun EffectsControls(
    fx: DesktopFxState,
    spectrum: SpectrumAnalyser,
    playing: Boolean,
    onFx: (DesktopFxState) -> Unit
) {
    Spectrum(spectrum, playing)

    Spacer(Modifier.height(18.dp))
    Text(
        "PRESETS",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = Palette.TextFaint
    )
    Spacer(Modifier.height(8.dp))
    // Two rows: seven presets do not fit across the panel, and shrinking them
    // to fit would make them unreadable rather than compact.
    val presets = EffectPreset.entries
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        presets.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { preset ->
                    Preset(preset.label, fx.matches(preset)) { onFx(fx.applying(preset)) }
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        presets.firstOrNull { fx.matches(it) }?.note ?: "Custom",
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextFaint
    )

    Spacer(Modifier.height(20.dp))
    Readout("Speed", "%.2f×".format(fx.speed), "this slider moves tempo alone")
    Slider(
        value = fx.speed,
        onValueChange = { onFx(fx.copy(speed = it)) },
        valueRange = 0.5f..2f,
        steps = 29,
        colors = sliderColours()
    )
    TickRow(listOf("0.5", "1.0", "1.5", "2.0"))

    Spacer(Modifier.height(18.dp))
    Readout("Pitch", formatSemitones(fx.pitchSemitones), intervalName(fx.pitchSemitones, fx.speed))
    PitchBar(fx.pitchSemitones)
    Slider(
        value = fx.pitchSemitones,
        onValueChange = { onFx(fx.copy(pitchSemitones = it)) },
        valueRange = -12f..12f,
        steps = 95,
        colors = sliderColours()
    )
    TickRow(listOf("-12", "-6", "0", "+6", "+12"))
    Text(
        "×%.3f frequency".format(2f.pow(fx.pitchSemitones / 12f)),
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextFaint
    )

    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Reverb",
            style = MaterialTheme.typography.titleMedium,
            color = Palette.Text,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = fx.reverbEnabled,
            onCheckedChange = { onFx(fx.copy(reverbEnabled = it)) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.Base,
                checkedTrackColor = Palette.Accent,
                uncheckedTrackColor = Palette.Hover,
                uncheckedThumbColor = Palette.TextDim,
                uncheckedBorderColor = Palette.Line
            )
        )
    }
    if (fx.reverbEnabled) {
        Spacer(Modifier.height(8.dp))
        Readout("Amount", "${(fx.reverbMix * 100).roundToInt()}%", "dry against wet")
        Slider(
            value = fx.reverbMix,
            onValueChange = { onFx(fx.copy(reverbMix = it)) },
            valueRange = 0f..1f,
            colors = sliderColours()
        )
        Spacer(Modifier.height(6.dp))
        Readout(
            "Room size",
            "${(fx.reverbDecay * 100).roundToInt()}%",
            if (fx.reverbDecay > 0.9f) "close to ringing" else "how long it takes to die away"
        )
        Slider(
            value = fx.reverbDecay,
            onValueChange = { onFx(fx.copy(reverbDecay = it)) },
            // Above ~0.95 the comb filters stop decaying and ring indefinitely.
            valueRange = 0.4f..0.94f,
            colors = sliderColours()
        )
        DecayCurve(fx.reverbDecay, fx.reverbMix)
    } else {
        Spacer(Modifier.height(6.dp))
        Text(
            "Off. Speed and pitch still apply.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextDim
        )
    }

    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Equalizer",
            style = MaterialTheme.typography.titleMedium,
            color = Palette.Text,
            modifier = Modifier.weight(1f)
        )
        if (fx.eqEnabled) {
            GhostButton("Flat") { onFx(fx.copy(eqGains = List(EQ_LABELS.size) { 0f })) }
            Spacer(Modifier.width(8.dp))
        }
        Switch(
            checked = fx.eqEnabled,
            onCheckedChange = { onFx(fx.copy(eqEnabled = it)) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.Base,
                checkedTrackColor = Palette.Accent,
                uncheckedTrackColor = Palette.Hover,
                uncheckedThumbColor = Palette.TextDim,
                uncheckedBorderColor = Palette.Line
            )
        )
    }
    if (fx.eqEnabled) {
        Spacer(Modifier.height(10.dp))
        EqualizerBands(fx.eqGains) { band, db ->
            onFx(fx.copy(eqGains = fx.eqGains.toMutableList().also { it[band] = db }))
        }
    } else {
        Spacer(Modifier.height(6.dp))
        Text(
            "Off. Ten bands from 31 Hz to 16 kHz, up to 12 dB either way.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextDim
        )
    }

    Spacer(Modifier.height(24.dp))
}

/**
 * Band levels of what is currently playing.
 *
 * Measured after the effect chain, so slowing a track down or opening the
 * reverb visibly changes the bars. Polled about sixteen times a second while
 * this panel is on screen and not computed at all while it is not — the
 * analyser is switched off on the way out.
 */
@Composable
private fun Spectrum(spectrum: SpectrumAnalyser, playing: Boolean) {
    var levels by remember { mutableStateOf(FloatArray(spectrum.bands())) }

    DisposableEffect(spectrum) {
        spectrum.enabled = true
        onDispose { spectrum.enabled = false }
    }

    LaunchedEffect(spectrum) {
        val buffer = FloatArray(spectrum.bands())
        while (true) {
            // A reactive background switches the analyser off when it goes away.
            spectrum.enabled = true
            levels = spectrum.snapshot(buffer).copyOf()
            withFrameNanos { }
            kotlinx.coroutines.delay(60)
        }
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "OUTPUT",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Palette.TextFaint,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (playing) "live" else "idle",
                style = MaterialTheme.typography.labelSmall,
                color = if (playing) Palette.Accent else Palette.TextFaint
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Palette.Content)
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            for (level in levels) {
                val height = (4f + level * 66f).dp
                Box(
                    Modifier
                        .weight(1f)
                        .height(height)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (level > 0.02f) Palette.Accent else Palette.Line)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("40 Hz", style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
            Spacer(Modifier.weight(1f))
            Text("22 kHz", style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
        }
    }
}

/** Label, value and a line of explanation, on one row. */
@Composable
private fun Readout(label: String, value: String, note: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = Palette.Text)
            Text(note, style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
        }
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Palette.Accent
        )
    }
}

/** Scale markings under a slider, so the range is legible without dragging it. */
@Composable
private fun TickRow(labels: List<String>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        labels.forEachIndexed { index, label ->
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextFaint
            )
            if (index != labels.lastIndex) Spacer(Modifier.weight(1f))
        }
    }
}

/** Sketch of the reverb tail, so "room size" means something before you hear it. */
@Composable
private fun DecayCurve(decay: Float, mix: Float) {
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.Content)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Each bar is one reflection, falling off at the decay coefficient.
        var amplitude = mix.coerceIn(0.05f, 1f)
        repeat(22) {
            Box(
                Modifier
                    .weight(1f)
                    .height((2f + amplitude * 22f).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Palette.AccentSoft)
            )
            amplitude *= decay
        }
    }
}

@Composable
private fun OutputControls(
    outputs: List<DesktopAudioOutput>,
    selected: List<String>,
    onToggle: (DesktopAudioOutput) -> Unit
) {
    Text(
        "Tick more than one to play through several devices at once.",
        style = MaterialTheme.typography.bodySmall,
        color = Palette.TextDim
    )
    Spacer(Modifier.height(12.dp))

    outputs.forEach { output ->
        val index = selected.indexOf(output.name)
        OutputRow(
            output = output,
            position = index,
            onClick = { onToggle(output) }
        )
    }

    Spacer(Modifier.height(14.dp))
    Text(
        // Worth stating: this is the capability Android could not give properly.
        "One decode feeds every device, so they stay sample-identical. Each " +
            "endpoint still has its own hardware latency, so mixing a Bluetooth " +
            "speaker with a wired one can sound offset.",
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextFaint
    )
}

@Composable
private fun OutputRow(output: DesktopAudioOutput, position: Int, onClick: () -> Unit) {
    val selected = position >= 0
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Palette.Selected else Palette.Hover)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (output.isDefault) Icons.Default.Speaker else Icons.Default.Headphones,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = if (selected) Palette.Accent else Palette.TextDim
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                output.name,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) Palette.Text else Palette.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (position == 0) {
                Text("primary", style = MaterialTheme.typography.labelSmall, color = Palette.Accent)
            } else if (position > 0) {
                Text("mirror", style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
            }
        }
        if (selected) {
            Icon(Icons.Default.Check, null, Modifier.size(15.dp), tint = Palette.Accent)
        }
    }
}

/** Fills outward from centre so "no shift" reads as neutral. */
@Composable
private fun PitchBar(semitones: Float) {
    val fraction = ((semitones + 12f) / 24f).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Palette.Line)
    ) {
        val from = minOf(fraction, 0.5f)
        val to = maxOf(fraction, 0.5f)
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .padding(start = 0.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(to)
                    .height(4.dp)
                    .background(Color.Transparent)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(if (to > 0f) (to - from) / to else 0f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Palette.Accent)
                )
            }
        }
    }
}

@Composable
private fun Preset(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Palette.Accent else Palette.Hover)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) Palette.Base else Palette.TextDim
        )
    }
}

@Composable
private fun sliderColours() = SliderDefaults.colors(
    thumbColor = Palette.Accent,
    activeTrackColor = Palette.Accent,
    inactiveTrackColor = Palette.Line,
    // The steps are still there to snap to; drawn, they filled the track with dots.
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent
)

private fun formatSemitones(value: Float): String {
    val rounded = (value * 10).roundToInt() / 10f
    val text = if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
    return if (rounded > 0f) "+$text st" else "$text st"
}

/**
 * Names the interval a pitch shift amounts to.
 *
 * A number of semitones is precise and means nothing to most people; "a fifth
 * up" is what they were actually reaching for. Only whole semitones get a name,
 * because anything between them is not an interval.
 */
private fun intervalName(semitones: Float, speed: Float = 1f): String {
    if (abs(semitones) < 0.05f) return "unchanged"

    // A preset imitating a resample pairs pitch to tempo exactly, which lands
    // between semitones by definition. Saying so beats calling it a near miss.
    if (abs(semitones - EffectPreset.resamplePitch(speed)) < 0.1f) {
        return "tracks the tempo exactly"
    }

    val rounded = semitones.roundToInt()
    if (abs(semitones - rounded) > 0.06f) return "between semitones"
    val names = listOf(
        "unchanged", "a semitone", "a whole tone", "a minor third", "a major third",
        "a fourth", "a tritone", "a fifth", "a minor sixth", "a major sixth",
        "a minor seventh", "a major seventh", "an octave"
    )
    val name = names.getOrNull(abs(rounded)) ?: "$rounded semitones"
    return if (rounded > 0) "$name up" else "$name down"
}

private val EQ_LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

/** Ten vertical faders. Click or drag anywhere on one; the middle line is 0 dB. */
@Composable
private fun EqualizerBands(gains: List<Float>, onChange: (Int, Float) -> Unit) {
    val change by rememberUpdatedState(onChange)
    val accent = Palette.Accent
    val track = Palette.Line
    val zero = Palette.TextFaint
    Row(Modifier.fillMaxWidth().height(160.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        EQ_LABELS.forEachIndexed { band, label ->
            val db = gains.getOrElse(band) { 0f }
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (abs(db) < 0.25f) "0" else "%+.0f".format(db),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (abs(db) < 0.25f) Palette.TextFaint else Palette.Accent
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .pointerInput(band) {
                            detectTapGestures { change(band, dbAt(it.y, size.height)) }
                        }
                        .pointerInput(band) {
                            detectVerticalDragGestures(
                                onDragStart = { change(band, dbAt(it.y, size.height)) }
                            ) { pointer, _ ->
                                change(band, dbAt(pointer.position.y, size.height))
                            }
                        }
                        .drawBehind {
                            val x = size.width / 2f
                            val width = 3.dp.toPx()
                            val mid = size.height / 2f
                            val y = mid - (db / 12f) * mid
                            drawRoundRect(track, Offset(x - width / 2f, 0f), Size(width, size.height), CornerRadius(width))
                            drawLine(zero, Offset(x - 6.dp.toPx(), mid), Offset(x + 6.dp.toPx(), mid), 1.dp.toPx())
                            drawRect(accent, Offset(x - width / 2f, minOf(mid, y)), Size(width, abs(y - mid)))
                            drawCircle(accent, 6.dp.toPx(), Offset(x, y))
                        }
                )
                Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
            }
        }
    }
}

/** Half-decibel steps from -12 to +12 for a pointer at [y] on a fader [height] tall. */
private fun dbAt(y: Float, height: Int): Float {
    val fraction = 1f - (y / height).coerceIn(0f, 1f)
    return ((fraction * 24f - 12f) * 2f).roundToInt() / 2f
}
