package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Desktop visual language.
 *
 * Deliberately not the phone's theme scaled up. Desktop wants a denser type
 * ramp, tighter corners and a layered surface stack (window / sidebar / content
 * / raised bar) rather than the phone's single flat background — that layering
 * is most of what separates a native-feeling desktop app from a stretched
 * mobile one.
 */
object Palette {

    /**
     * Every theme carries its own surfaces and its own text tones.
     *
     * They used to be generated: one hue, fixed lightness per layer, text at a
     * fixed brightness above it. That kept the layering consistent but said
     * nothing about whether the text could actually be read on it, and on the
     * brighter accents it could not. Each theme is now a published scheme whose
     * surface and text tones were chosen together, and the contrast of every
     * pairing is measured rather than assumed.
     */
    private val chosen = mutableStateOf(AccentChoice.MOCHA)
    private val scheme = mutableStateOf(AccentChoice.MOCHA.colors)
    private val customScheme = mutableStateOf(AccentChoice.MOCHA.colors)
    private val starsOverride = mutableStateOf<Color?>(null)
    private val lyricsActiveOverride = mutableStateOf<Color?>(null)
    private val lyricsInactiveOverride = mutableStateOf<Color?>(null)

    val choice: AccentChoice get() = chosen.value

    /** The colours in use right now. */
    val colors: ThemeColors get() = scheme.value

    /** The Custom theme's colours, whether or not it is the one in use. */
    val custom: ThemeColors get() = customScheme.value

    val Base: Color get() = scheme.value.base          // window, deepest layer
    val Sidebar: Color get() = scheme.value.sidebar    // navigation rail
    val Content: Color get() = scheme.value.content    // main surface
    val Raised: Color get() = scheme.value.raised      // transport bar, headers
    val Hover: Color get() = scheme.value.hover
    val Line: Color get() = scheme.value.line

    val Text: Color get() = scheme.value.text
    val TextDim: Color get() = scheme.value.textDim
    val TextFaint: Color get() = scheme.value.textFaint

    val Accent: Color get() = scheme.value.accent
    val AccentSoft: Color get() = scheme.value.soft
    val Selected: Color get() = scheme.value.selected

    /** Foreground for anything sitting on [Accent]. */
    val OnAccent: Color get() = scheme.value.onAccent

    /** The resting fill of the main buttons. */
    val Button: Color get() = scheme.value.button

    /** Foreground for anything sitting on [Button]. */
    val OnButton: Color get() = ThemeColors.readableOn(scheme.value.button, scheme.value)

    /** The background effect's colour: the user's own pick, or the accent. */
    val Stars: Color get() = starsOverride.value ?: scheme.value.accent

    fun use(choice: AccentChoice) {
        chosen.value = choice
        scheme.value = if (choice == AccentChoice.CUSTOM) customScheme.value else choice.colors
    }

    fun setCustom(colors: ThemeColors) {
        customScheme.value = colors
        if (chosen.value == AccentChoice.CUSTOM) scheme.value = colors
    }

    fun setStars(color: Color?) {
        starsOverride.value = color
    }

    /** The line being sung: the user's own colour, or the accent. */
    val LyricsActive: Color get() = lyricsActiveOverride.value ?: scheme.value.accent

    /** Every other line: the user's own colour, or the dimmed text colour. */
    val LyricsInactive: Color get() = lyricsInactiveOverride.value ?: TextDim

    fun setLyricsActive(color: Color?) {
        lyricsActiveOverride.value = color
    }

    fun setLyricsInactive(color: Color?) {
        lyricsInactiveOverride.value = color
    }
}

/** One theme's colours: what it is made of, and what every surface takes. */
data class ThemeColors(
    val accent: Color,
    val soft: Color,
    val selected: Color,
    val onAccent: Color,
    val button: Color,
    val base: Color,
    val sidebar: Color,
    val content: Color,
    val raised: Color,
    val hover: Color,
    val line: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color
) {
    /**
     * Primary, secondary, tertiary and button colours as ARGB hex, for the
     * settings file. Only those four: the rest of a custom theme is worked out
     * from the primary, so an old saved theme still loads.
     */
    fun encode(): String = listOf(accent, soft, selected, button).joinToString(",") { hex(it) }

    companion object {
        fun hex(color: Color): String = "%08X".format(color.toArgb())

        fun parse(text: String?): Color? {
            val digits = text?.trim()?.removePrefix("#") ?: return null
            val value = digits.toLongOrNull(16) ?: return null
            return when (digits.length) {
                6 -> Color(0xFF000000L or value)
                8 -> Color(value)
                else -> null
            }
        }

        fun decode(text: String): ThemeColors? {
            val parts = text.split(",").mapNotNull { parse(it) }
            return if (parts.size == 4) from(parts[0], parts[1], parts[2], parts[3]) else null
        }

        /**
         * A custom theme: the surfaces take the primary colour's hue, and the
         * text tones are set well above them, because a colour picked for its
         * looks says nothing about what can be read on it.
         */
        fun from(primary: Color, secondary: Color, tertiary: Color, button: Color): ThemeColors {
            val hsb = java.awt.Color.RGBtoHSB(
                (primary.red * 255).roundToInt(),
                (primary.green * 255).roundToInt(),
                (primary.blue * 255).roundToInt(),
                null
            )
            val hue = (hsb[0] * 360f) % 360f
            val tint = hsb[1].coerceIn(0.08f, 0.75f)

            fun surface(saturation: Float, lightness: Float): Color =
                Color.hsl(hue, (saturation * tint).coerceIn(0f, 1f), lightness)

            return ThemeColors(
                accent = primary,
                soft = secondary,
                selected = tertiary,
                onAccent = contrastingWith(primary),
                button = button,
                base = surface(0.30f, 0.061f),
                sidebar = surface(0.33f, 0.094f),
                content = surface(0.24f, 0.098f),
                raised = surface(0.24f, 0.129f),
                hover = surface(0.26f, 0.169f),
                line = surface(0.22f, 0.180f),
                text = surface(0.18f, 0.967f),
                textDim = surface(0.13f, 0.820f),
                textFaint = surface(0.10f, 0.660f)
            )
        }

        /**
         * A published scheme. Its surfaces and text are its own; the soft
         * accent, the selected row and the button fill are mixed from its
         * accent towards its own content colour, which is how these schemes are
         * used in the editors they come from.
         */
        fun fromScheme(
            accent: Color,
            base: Color,
            sidebar: Color,
            content: Color,
            raised: Color,
            hover: Color,
            line: Color,
            text: Color,
            textDim: Color,
            textFaint: Color
        ): ThemeColors = ThemeColors(
            accent = accent,
            soft = lerp(accent, content, 0.45f),
            selected = lerp(accent, content, 0.82f),
            onAccent = contrastingWith(accent),
            button = lerp(accent, content, 0.38f),
            base = base,
            sidebar = sidebar,
            content = content,
            raised = raised,
            hover = hover,
            line = line,
            text = text,
            textDim = textDim,
            textFaint = textFaint
        )

        /** Near-black or near-white, whichever stands out on [colour]. */
        fun contrastingWith(colour: Color): Color =
            if (colour.luminance() > 0.32f) Color(0xFF10101A) else Color(0xFFF6F6FB)

        /** The theme's own darkest or brightest tone, whichever reads on [colour]. */
        fun readableOn(colour: Color, scheme: ThemeColors): Color =
            if (colour.luminance() > 0.32f) scheme.base else scheme.text
    }
}

/**
 * The colour themes.
 *
 * Each is a published scheme rather than a hue this app invented: the people
 * who made them picked the surface and text tones against each other, which is
 * what keeps text readable at every layer. Purple still leads, because that is
 * what Resonate has always been. Custom takes its colours from the editor; the
 * values here are only where it starts.
 */
enum class AccentChoice(
    val label: String,
    /** Where the scheme comes from, shown under the swatches. */
    val credit: String,
    val accent: Color,
    private val base: Color,
    private val sidebar: Color,
    private val content: Color,
    private val raised: Color,
    private val hover: Color,
    private val line: Color,
    private val text: Color,
    private val textDim: Color,
    private val textFaint: Color
) {
    MOCHA(
        "Mocha", "Catppuccin Mocha", Color(0xFFCBA6F7),
        Color(0xFF11111B), Color(0xFF181825), Color(0xFF1E1E2E), Color(0xFF313244),
        Color(0xFF45475A), Color(0xFF585B70),
        Color(0xFFCDD6F4), Color(0xFFBAC2DE), Color(0xFFA6ADC8)
    ),
    DRACULA(
        "Dracula", "Dracula", Color(0xFFBD93F9),
        Color(0xFF1E1F29), Color(0xFF242531), Color(0xFF282A36), Color(0xFF343746),
        Color(0xFF44475A), Color(0xFF525569),
        Color(0xFFF8F8F2), Color(0xFFD5D6E0), Color(0xFFA8AEC8)
    ),
    TOKYO(
        "Tokyo", "Tokyo Night", Color(0xFF7AA2F7),
        Color(0xFF16161E), Color(0xFF1A1B26), Color(0xFF1F2130), Color(0xFF292E42),
        Color(0xFF343A52), Color(0xFF3B4261),
        Color(0xFFC0CAF5), Color(0xFFA9B1D6), Color(0xFF8A93B8)
    ),
    ROSE(
        "Rosé", "Rosé Pine", Color(0xFFC4A7E7),
        Color(0xFF16141F), Color(0xFF191724), Color(0xFF1F1D2E), Color(0xFF26233A),
        Color(0xFF302C4A), Color(0xFF403C5C),
        Color(0xFFE0DEF4), Color(0xFFC7C4DE), Color(0xFF9E9ABA)
    ),
    NORD(
        "Nord", "Nord", Color(0xFF88C0D0),
        Color(0xFF272C36), Color(0xFF2E3440), Color(0xFF333B4A), Color(0xFF3B4252),
        Color(0xFF434C5E), Color(0xFF4C566A),
        Color(0xFFECEFF4), Color(0xFFD8DEE9), Color(0xFFAEB8C8)
    ),
    GRUVBOX(
        "Gruvbox", "Gruvbox dark", Color(0xFFFABD2F),
        Color(0xFF1D2021), Color(0xFF232728), Color(0xFF282828), Color(0xFF32302F),
        Color(0xFF3C3836), Color(0xFF504945),
        Color(0xFFFBF1C7), Color(0xFFEBDBB2), Color(0xFFBDAE93)
    ),
    // Everforest's darker background variants, so its softer foreground still
    // clears the contrast target with room to spare.
    FOREST(
        "Everforest", "Everforest dark", Color(0xFFA7C080),
        Color(0xFF1E2326), Color(0xFF272E33), Color(0xFF2D353B), Color(0xFF343F44),
        Color(0xFF3D484D), Color(0xFF4F585E),
        Color(0xFFD3C6AA), Color(0xFFBEC5AE), Color(0xFF9DA9A0)
    ),
    SOLAR(
        "Solarized", "Solarized dark", Color(0xFF4FA3DB),
        Color(0xFF002B36), Color(0xFF04303B), Color(0xFF073642), Color(0xFF0E4451),
        Color(0xFF17505E), Color(0xFF2C5D68),
        Color(0xFFFDF6E3), Color(0xFFEEE8D5), Color(0xFFA9B5B5)
    ),
    ONEDARK(
        "One Dark", "One Dark", Color(0xFF61AFEF),
        Color(0xFF21252B), Color(0xFF23272E), Color(0xFF282C34), Color(0xFF2F343D),
        Color(0xFF3A3F4B), Color(0xFF474C55),
        Color(0xFFDCDFE4), Color(0xFFABB2BF), Color(0xFF8B93A1)
    ),
    AYU(
        "Ayu", "Ayu Mirage", Color(0xFFFFCC66),
        Color(0xFF1A1F29), Color(0xFF1F2430), Color(0xFF242936), Color(0xFF2C3242),
        Color(0xFF343B4D), Color(0xFF434A5C),
        Color(0xFFD9D7CF), Color(0xFFC0BEB5), Color(0xFF9AA1AB)
    ),
    CUSTOM(
        "Custom", "Yours", Color(0xFFCBA6F7),
        Color(0xFF11111B), Color(0xFF181825), Color(0xFF1E1E2E), Color(0xFF313244),
        Color(0xFF45475A), Color(0xFF585B70),
        Color(0xFFCDD6F4), Color(0xFFBAC2DE), Color(0xFFA6ADC8)
    );

    val colors: ThemeColors
        get() = ThemeColors.fromScheme(
            accent, base, sidebar, content, raised, hover, line, text, textDim, textFaint
        )

    /** Foreground for anything sitting on this theme's accent. */
    val onAccent: Color get() = ThemeColors.contrastingWith(accent)

    companion object {
        fun fromName(name: String?): AccentChoice =
            entries.firstOrNull { it.name == name } ?: MOCHA
    }
}

@Composable
private fun desktopColors() = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.OnAccent,
    primaryContainer = Palette.AccentSoft,
    onPrimaryContainer = Palette.Text,
    background = Palette.Base,
    onBackground = Palette.Text,
    surface = Palette.Content,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Raised,
    onSurfaceVariant = Palette.TextDim,
    outline = Palette.Line
)

/** Smaller than mobile Material: desktop reads at arm's length, not 30cm. */
private val DesktopTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        letterSpacing = 0.3.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 11.5.sp,
        letterSpacing = 0.4.sp
    )
)

private val DesktopShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(14.dp)
)

@Composable
fun ResonateDesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = desktopColors(),
        typography = DesktopTypography,
        shapes = DesktopShapes,
        content = content
    )
}
