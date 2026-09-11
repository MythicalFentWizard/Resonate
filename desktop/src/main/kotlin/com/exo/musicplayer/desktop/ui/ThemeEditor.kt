package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.exo.musicplayer.desktop.data.DesktopController
import kotlin.math.roundToInt
import java.awt.Dimension

private enum class ColourField(val label: String, val note: String) {
    PRIMARY("Primary", "Highlights, sliders, the playing track. The window takes its hue."),
    SECONDARY("Secondary", "Soft accents and meters."),
    TERTIARY("Tertiary", "Selected rows and chosen options."),
    BUTTON("Buttons", "The fill of the main buttons."),
    BACKGROUND("Background effect", "Stars, aurora and the rest. Works with every theme."),
    LYRICS_ACTIVE("Lyrics: current line", "The line being sung. Works with every theme."),
    LYRICS_INACTIVE("Lyrics: other lines", "Every other line. Works with every theme.")
}

/** The colour editor, in a window of its own so the app stays in view as it changes. */
@Composable
fun ThemeEditorWindow(controller: DesktopController, onClose: () -> Unit) {
    DialogWindow(
        onCloseRequest = onClose,
        title = "Colours · Resonate",
        state = rememberDialogState(size = DpSize(640.dp, 700.dp))
    ) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(460, 460) }
        ResonateDesktopTheme {
            ScaledToWindow(designWidth = 640.dp, designHeight = 700.dp) { ThemeEditor(controller, onClose) }
        }
    }
}

@Composable
private fun ThemeEditor(controller: DesktopController, onClose: () -> Unit) {
    // Starts from what is on screen, so a preset can be tweaked rather than rebuilt.
    var draft by remember {
        mutableStateOf(if (controller.accent == AccentChoice.CUSTOM) Palette.custom else Palette.colors)
    }
    var field by remember { mutableStateOf(ColourField.PRIMARY) }

    fun colourOf(f: ColourField): Color = when (f) {
        ColourField.PRIMARY -> draft.accent
        ColourField.SECONDARY -> draft.soft
        ColourField.TERTIARY -> draft.selected
        ColourField.BUTTON -> draft.button
        ColourField.BACKGROUND -> Palette.Stars
        ColourField.LYRICS_ACTIVE -> Palette.LyricsActive
        ColourField.LYRICS_INACTIVE -> Palette.LyricsInactive
    }

    fun apply(f: ColourField, colour: Color) {
        when (f) {
            ColourField.BACKGROUND -> return controller.setBackdropColor(colour)
            ColourField.LYRICS_ACTIVE -> return controller.setLyricsActiveColor(colour)
            ColourField.LYRICS_INACTIVE -> return controller.setLyricsInactiveColor(colour)
            else -> Unit
        }
        draft = ThemeColors.from(
            primary = if (f == ColourField.PRIMARY) colour else draft.accent,
            secondary = if (f == ColourField.SECONDARY) colour else draft.soft,
            tertiary = if (f == ColourField.TERTIARY) colour else draft.selected,
            button = if (f == ColourField.BUTTON) colour else draft.button
        )
        controller.saveCustomTheme(draft)
    }

    Column(Modifier.fillMaxSize().background(Palette.Base).padding(20.dp)) {
        Text("Colours", style = MaterialTheme.typography.titleLarge, color = Palette.Text)
        Spacer(Modifier.height(4.dp))
        Hint(
            "Changing any of the first four switches to the Custom theme, and it shows at once. " +
                "The background effect and lyric colours work with every theme."
        )
        Spacer(Modifier.height(14.dp))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ColourField.entries.forEach { f ->
                    FieldRow(f, colourOf(f), f == field) { field = f }
                }
                Spacer(Modifier.height(8.dp))
                GhostButton("Background follows theme") { controller.setBackdropColor(null) }
                Spacer(Modifier.height(6.dp))
                GhostButton("Lyrics follow theme") {
                    controller.setLyricsActiveColor(null)
                    controller.setLyricsInactiveColor(null)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                HsvPicker(field, colourOf(field)) { apply(field, it) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            AccentButton("Done", onClick = onClose)
        }
    }
}

@Composable
private fun FieldRow(field: ColourField, colour: Color, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Palette.Selected else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colour)
                .border(1.dp, Palette.Line, RoundedCornerShape(6.dp))
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(field.label, style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
            Text(field.note, style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
        }
    }
}

private fun hsbOf(colour: Color): FloatArray = java.awt.Color.RGBtoHSB(
    (colour.red * 255).roundToInt(), (colour.green * 255).roundToInt(), (colour.blue * 255).roundToInt(), null
)

/**
 * Saturation and brightness in a square, hue in a bar under it, and a hex box.
 * Hue is held separately from the colour, so dragging to grey and back doesn't
 * lose it.
 */
@Composable
private fun HsvPicker(key: Any, colour: Color, onChange: (Color) -> Unit) {
    val emit by rememberUpdatedState(onChange)
    val start = remember(key) { hsbOf(colour) }
    var hue by remember(key) { mutableFloatStateOf(start[0]) }
    var saturation by remember(key) { mutableFloatStateOf(start[1]) }
    var brightness by remember(key) { mutableFloatStateOf(start[2]) }

    fun send() = emit(Color(java.awt.Color.HSBtoRGB(hue, saturation, brightness)))

    fun pickSquare(at: Offset, width: Int, height: Int) {
        saturation = (at.x / width).coerceIn(0f, 1f)
        brightness = 1f - (at.y / height).coerceIn(0f, 1f)
        send()
    }

    fun pickHue(at: Offset, width: Int) {
        hue = (at.x / width).coerceIn(0f, 0.999f)
        send()
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(230.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.horizontalGradient(listOf(Color.White, Color(java.awt.Color.HSBtoRGB(hue, 1f, 1f)))))
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            .pointerInput(key) { detectTapGestures { pickSquare(it, size.width, size.height) } }
            .pointerInput(key) {
                detectDragGestures(onDragStart = { pickSquare(it, size.width, size.height) }) { change, _ ->
                    pickSquare(change.position, size.width, size.height)
                }
            }
            .drawBehind {
                val at = Offset(saturation * size.width, (1f - brightness) * size.height)
                drawCircle(Color.Black.copy(alpha = 0.5f), 8.dp.toPx(), at, style = Stroke(3.dp.toPx()))
                drawCircle(Color.White, 8.dp.toPx(), at, style = Stroke(1.5.dp.toPx()))
            }
    )
    Spacer(Modifier.height(12.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.horizontalGradient(List(7) { Color(java.awt.Color.HSBtoRGB(it / 6f, 1f, 1f)) }))
            .pointerInput(key) { detectTapGestures { pickHue(it, size.width) } }
            .pointerInput(key) {
                detectDragGestures(onDragStart = { pickHue(it, size.width) }) { change, _ ->
                    pickHue(change.position, size.width)
                }
            }
            .drawBehind {
                drawCircle(
                    Color.White,
                    8.dp.toPx(),
                    Offset(hue * size.width, size.height / 2f),
                    style = Stroke(2.dp.toPx())
                )
            }
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(colour))
        Spacer(Modifier.width(10.dp))
        var text by remember(key, colour) { mutableStateOf("#" + ThemeColors.hex(colour).drop(2)) }
        TextInput(
            value = text,
            onValueChange = { typed ->
                text = typed
                ThemeColors.parse(typed)?.let { parsed ->
                    val hsb = hsbOf(parsed)
                    hue = hsb[0]
                    saturation = hsb[1]
                    brightness = hsb[2]
                    emit(parsed)
                }
            },
            placeholder = "#RRGGBB",
            modifier = Modifier.width(140.dp)
        )
    }
}
