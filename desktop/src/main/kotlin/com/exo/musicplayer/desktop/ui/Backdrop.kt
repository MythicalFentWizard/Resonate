package com.exo.musicplayer.desktop.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalWindowInfo
import com.exo.musicplayer.data.audio.SpectrumAnalyser
import com.exo.musicplayer.desktop.audio.BeatTap
import com.exo.musicplayer.desktop.audio.SongGraph
import com.exo.musicplayer.desktop.audio.SongShape
import kotlin.math.log10
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** What is drawn behind the sidebar, the library and the lyrics. */
enum class BackdropStyle(val label: String, val note: String) {
    NONE("None", "A plain background."),
    STARS("Stars", "A slow twinkle, as on the phone."),
    AURORA("Aurora", "Curtains of light sweeping slowly across the window."),
    FIREFLIES("Fireflies", "Warm specks of light wandering and glowing."),
    SNOW("Snow", "Flakes falling gently."),
    WAVES("Waves", "A graph of the song scrolling past: what has played to the left, what is coming to the right."),
    REACTIVE("Reactive", "Moves with whatever is playing, in one of two looks.");

    companion object {
        fun fromName(name: String?): BackdropStyle = entries.firstOrNull { it.name == name } ?: STARS
    }
}

/** Reactive's two looks. */
enum class ReactiveMode(val label: String, val note: String) {
    BALL("Ball", "A glowing ball in the middle, ringed by the spectrum, that swells and shakes on the beat."),
    BARS("Bars", "The Effects panel's output meter, across the bottom of the window.");

    companion object {
        fun fromName(name: String?): ReactiveMode = entries.firstOrNull { it.name == name } ?: BALL
    }
}

private const val TAU = (2 * PI).toFloat()

/** How much of the song the Waves graph shows across the window, and the slice it is read in. */
private const val GRAPH_SPAN_MS = 12_000.0
private const val SLICE_MS = 10.0

internal class Mote(val x: Float, val y: Float, val size: Float, val phase: Float, val speed: Float)

internal fun motes(count: Int, seed: Int = 11): List<Mote> {
    val random = Random(seed)
    return List(count) {
        Mote(
            random.nextFloat(), random.nextFloat(), random.nextFloat(),
            random.nextFloat() * TAU, 0.5f + random.nextFloat()
        )
    }
}

/** Where this surface sits in its window, so effects run on across neighbouring surfaces. */
internal class Placement {
    var x = 0f
    var width = 0f
}

/**
 * The music, as the reactive effects read it, stretched so that it visibly moves.
 *
 * Each band is measured against its own recent floor and ceiling rather than
 * against silence. A bass-heavy song sits near the top of the analyser's range
 * the whole time, so levels taken as they come barely moved and the ball stood
 * still; stretched between where that band has been over the last second or
 * two, the same song swings the whole way on every beat. A little of the plain
 * level is kept in the mix, so a loud song still looks louder than a quiet one,
 * and anything near silence stays still.
 *
 * [punch] is the bass as it is heard right now, from a [BeatTap] when there is
 * one, stretched the same way, and [kick] jumps on each beat and dies away
 * within about a fifth of a second. The analyser's reading is held back by how
 * far it runs ahead of the speakers, so the bars and the ball move with the
 * sound rather than before it.
 */
internal class Pulse(bands: Int = 14) {
    val levels = FloatArray(bands.coerceAtLeast(4))
    var bass = 0f
        private set
    var mid = 0f
        private set
    var treble = 0f
        private set
    var energy = 0f
        private set

    /** The heard bass, 0..1 across its own recent range. */
    var punch = 0f
        private set

    /** How hard the last beat hit, dying away after it. */
    var kick = 0f
        private set
    var travel = 0f
        private set

    /** When each ring was sent out, in effect seconds. */
    val rings = FloatArray(6) { -10f }

    private val floors = FloatArray(levels.size)
    private val ceilings = FloatArray(levels.size)
    private var heardFloor = 1f
    private var heardCeiling = 0f
    private var heard = 0f
    private var heardSlow = 0f
    private var kickPeak = 0f
    private var lastRing = -10f
    private var lastTime = -1f

    private val history = Array(HISTORY) { FloatArray(levels.size) }
    private val historyAt = FloatArray(HISTORY) { -10f }
    private var historyNext = 0

    /**
     * [snapshot] is the analyser's reading at [t] seconds, [aheadSeconds] how
     * far that runs ahead of the speakers, and [heardBass] the bass a [BeatTap]
     * says is being heard as RMS of full scale - negative where there is none.
     */
    fun update(snapshot: FloatArray, t: Float, aheadSeconds: Float = 0f, heardBass: Float = -1f) {
        val dt = if (lastTime < 0f) 0f else (t - lastTime).coerceIn(0f, 0.1f)
        lastTime = t
        val reading = delayed(snapshot, t, aheadSeconds)

        val attack = ease(dt, 0.03f)
        val release = ease(dt, 0.12f)
        val settle = ease(dt, 1.6f)
        for (i in levels.indices) {
            val value = reading[i]
            floors[i] = if (value < floors[i]) value else floors[i] + (value - floors[i]) * settle
            ceilings[i] = if (value > ceilings[i]) value else ceilings[i] + (value - ceilings[i]) * settle
            val swing = ((value - floors[i]) / max(ceilings[i] - floors[i], MIN_SPAN)).coerceIn(0f, 1f)
            // Near silence the stretch is faded out, so a quiet hiss doesn't dance.
            val target = (value * 0.3f + swing * 0.7f) * (value / 0.08f).coerceAtMost(1f)
            levels[i] += (target - levels[i]) * (if (target > levels[i]) attack else release)
        }

        val lowEnd = (levels.size * 0.22f).toInt().coerceAtLeast(1)
        val midEnd = (levels.size * 0.6f).toInt().coerceAtLeast(lowEnd + 1)
        bass = average(0, lowEnd)
        mid = average(lowEnd, midEnd)
        treble = average(midEnd, levels.size)
        energy = average(0, levels.size)

        // The heard bass on a decibel scale, sixty decibels down to full scale.
        val loud = if (heardBass >= 0f) {
            if (heardBass < 1e-6f) 0f else ((20f * log10(heardBass) + 60f) / 60f).coerceIn(0f, 1f)
        } else {
            var raw = 0f
            for (i in 0 until lowEnd) raw += reading[i]
            raw / lowEnd
        }
        // The floor and ceiling give way slowly, so one loud moment doesn't
        // become the new normal and flatten everything after it.
        heardFloor = if (loud < heardFloor) loud else heardFloor + (loud - heardFloor) * ease(dt, 1.6f)
        heardCeiling = if (loud > heardCeiling) loud else heardCeiling + (loud - heardCeiling) * ease(dt, 1.6f)
        val stretched = ((loud - heardFloor) / max(heardCeiling - heardFloor, MIN_HEARD_SPAN)).coerceIn(0f, 1f)
        // A fifth of it is the plain loudness, so a quiet passage still sits
        // lower than a loud one instead of both being stretched to fill the ball.
        val plain = ((loud - 0.25f) / 0.75f).coerceIn(0f, 1f)
        val target = (stretched * 0.8f + plain * 0.2f) * (loud / 0.1f).coerceAtMost(1f)
        heard += (target - heard) * (if (target > heard) ease(dt, 0.012f) else ease(dt, 0.11f))
        punch = heard

        // A beat is the bass jumping above where it has been for the last quarter second.
        heardSlow += (heard - heardSlow) * ease(dt, 0.25f)
        val onset = ((heard - heardSlow) * 2.4f).coerceIn(0f, 1f)
        kickPeak = max(kickPeak * exp(-dt / 0.14f), onset)
        kick += (kickPeak - kick) * ease(dt, 0.015f)

        travel += dt * (0.015f + energy * 0.1f)
        if (onset > 0.45f && t - lastRing > 0.22f) {
            var oldest = 0
            for (s in rings.indices) if (rings[s] < rings[oldest]) oldest = s
            rings[oldest] = t
            lastRing = t
        }
    }

    /** Mean level of bands [from] until [to]. */
    fun average(from: Int, to: Int): Float {
        val end = min(to, levels.size)
        if (end <= from) return 0f
        var sum = 0f
        for (i in from until end) sum += levels[i]
        return sum / (end - from)
    }

    /** Keeps [snapshot] and gives back the reading from [ahead] seconds ago, spread across [levels]' bands. */
    private fun delayed(snapshot: FloatArray, t: Float, ahead: Float): FloatArray {
        val slot = history[historyNext]
        for (i in slot.indices) {
            slot[i] = if (snapshot.isEmpty()) {
                0f
            } else {
                snapshot[(i * snapshot.size / slot.size).coerceAtMost(snapshot.size - 1)].coerceIn(0f, 1f)
            }
        }
        historyAt[historyNext] = t
        historyNext = (historyNext + 1) % HISTORY
        if (ahead <= 0f) return slot
        var best = slot
        var bestAt = -1f
        for (k in 0 until HISTORY) {
            val at = historyAt[k]
            if (at in 0f..(t - ahead) && at > bestAt) {
                bestAt = at
                best = history[k]
            }
        }
        return best
    }

    private fun ease(dt: Float, seconds: Float): Float = 1f - exp(-dt / seconds)

    private companion object {
        /** A band is never stretched from a range narrower than this, so a steady hum stays still. */
        const val MIN_SPAN = 0.12f
        const val MIN_HEARD_SPAN = 0.10f
        /** A second and a half of readings, at sixty a second. */
        const val HISTORY = 90
    }
}

/**
 * The chosen background effect, in [color].
 *
 * Every style follows Starfield's rules: a graphics layer of its own, the clock
 * read inside the draw call so a tick repaints without recomposing anything in
 * front, and no ticking while the window isn't focused - unless
 * [pauseWhenUnfocused] is false, as for the lyrics window, which mostly sits
 * beside whatever has focus. Waves and Reactive redraw every display frame and
 * keep the analyser and [beat] on while shown; Waves also follows [graph], the
 * playing song read ahead. Aurora, Waves and Reactive's bars are laid out
 * across the whole window rather than each surface, so they carry on unbroken
 * from the sidebar into the library. Reactive's ball sits in the middle of the
 * [centerpiece] surface - the page itself - and the sidebar and side panel
 * leave it out.
 */
@Composable
fun Backdrop(
    style: BackdropStyle,
    color: Color,
    spectrum: SpectrumAnalyser?,
    modifier: Modifier = Modifier,
    count: Int = 90,
    pauseWhenUnfocused: Boolean = true,
    graph: SongShape? = null,
    reactiveMode: ReactiveMode = ReactiveMode.BALL,
    beat: BeatTap? = null,
    centerpiece: Boolean = true
) {
    when {
        style == BackdropStyle.NONE -> Unit
        style == BackdropStyle.REACTIVE && reactiveMode == ReactiveMode.BALL && !centerpiece -> Unit
        style == BackdropStyle.STARS -> Starfield(
            enabled = true,
            color = color,
            modifier = modifier,
            count = count,
            pauseWhenUnfocused = pauseWhenUnfocused
        )
        else ->
            Animated(style, color, spectrum, modifier, count, pauseWhenUnfocused, graph, reactiveMode, beat, centerpiece)
    }
}

@Composable
private fun Animated(
    style: BackdropStyle,
    color: Color,
    spectrum: SpectrumAnalyser?,
    modifier: Modifier,
    count: Int,
    pauseWhenUnfocused: Boolean,
    graph: SongShape?,
    reactiveMode: ReactiveMode,
    beat: BeatTap?,
    centerpiece: Boolean
) {
    val motes = remember(count) { motes(count) }
    val seconds = remember { mutableFloatStateOf(0f) }
    val pulse = remember(spectrum) { Pulse(spectrum?.bands() ?: 14) }
    val place = remember { Placement() }
    val focused = LocalWindowInfo.current.isWindowFocused || !pauseWhenUnfocused
    val moving = style == BackdropStyle.REACTIVE || style == BackdropStyle.WAVES
    // Null unless this style listens, so the tick has one thing to test.
    val analyser = if (moving) spectrum else null
    val listening = analyser != null

    LaunchedEffect(focused, style) {
        if (!focused) return@LaunchedEffect
        val origin = System.nanoTime() - (seconds.floatValue * 1e9f).toLong()
        val raw = FloatArray(spectrum?.bands() ?: 1)
        var lastTick = 0L
        while (isActive) {
            if (moving) {
                // Every display frame, up to about sixty a second: these move with
                // the music, and at thirty updates a second their motion stepped.
                withFrameNanos { }
                val tick = System.nanoTime()
                if (tick - lastTick < 15_000_000L) continue
                lastTick = tick
            } else {
                delay(50L)
            }
            val nanos = System.nanoTime()
            val now = (nanos - origin) / 1e9f
            if (analyser != null) {
                // Re-asserted every tick: the effects panel's meter switches the
                // analyser off when it closes, and this still needs it.
                analyser.enabled = true
                beat?.enabled = true
                pulse.update(
                    analyser.snapshot(raw),
                    now,
                    aheadSeconds = (beat?.delayNanos ?: 0L) / 1e9f,
                    heardBass = if (beat == null) -1f else beat.bassAt(nanos)
                )
            }
            if (style == BackdropStyle.WAVES) (graph as? SongGraph)?.sync()
            seconds.floatValue = now
        }
    }
    DisposableEffect(analyser) {
        onDispose {
            if (analyser != null) {
                analyser.enabled = false
                beat?.enabled = false
            }
        }
    }

    Spacer(
        modifier
            .onGloballyPositioned { coordinates ->
                place.x = coordinates.positionInRoot().x
                place.width = coordinates.findRootCoordinates().size.width.toFloat()
            }
            .graphicsLayer()
            .drawBehind {
                val t = seconds.floatValue
                clipRect {
                    when (style) {
                        BackdropStyle.AURORA -> aurora(t, color, place)
                        BackdropStyle.FIREFLIES -> fireflies(t, color, motes)
                        BackdropStyle.SNOW -> snow(t, color, motes)
                        BackdropStyle.WAVES ->
                            if (graph != null && graph.active && graph.filled > 0) {
                                songGraph(color, graph, if (listening) pulse else null, place)
                            } else {
                                ribbon(t, color, if (listening) pulse else null, place)
                            }
                        BackdropStyle.REACTIVE -> when (reactiveMode) {
                            ReactiveMode.BALL -> reactiveBall(t, color, pulse)
                            ReactiveMode.BARS -> reactiveBars(color, pulse, place, centerpiece)
                        }
                        else -> Unit
                    }
                }
            }
    )
}

private fun wrap(value: Float) = ((value % 1f) + 1f) % 1f

// Reused on the UI thread, where every backdrop draws.
private val glowPaint = Paint()
private val softPaint = Paint()

/**
 * A glow whose brightness falls away along a bell curve rather than a straight
 * line, dithered, so it fades into the background without a visible edge or
 * banding on a dark theme.
 */
private fun DrawScope.softCircle(color: Color, alpha: Float, center: Offset, radius: Float) {
    if (radius <= 0f || alpha <= 0.002f) return
    val brush = Brush.radialGradient(
        0f to color.copy(alpha = alpha),
        0.2f to color.copy(alpha = alpha * 0.82f),
        0.4f to color.copy(alpha = alpha * 0.52f),
        0.6f to color.copy(alpha = alpha * 0.24f),
        0.8f to color.copy(alpha = alpha * 0.07f),
        1f to Color.Transparent,
        center = center,
        radius = radius
    )
    drawIntoCanvas { canvas ->
        brush.applyTo(size, softPaint, 1f)
        softPaint.asFrameworkPaint().isDither = true
        canvas.drawCircle(center, radius, softPaint)
    }
}

/** A line blurred into a soft halo, for the glow under a crisp stroke. */
private fun DrawScope.glowPath(path: Path, color: Color, width: Float, blur: Float) {
    drawIntoCanvas { canvas ->
        glowPaint.color = color
        glowPaint.style = PaintingStyle.Stroke
        glowPaint.strokeWidth = width
        glowPaint.strokeCap = StrokeCap.Round
        glowPaint.asFrameworkPaint().maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, blur)
        canvas.drawPath(path, glowPaint)
    }
}

/** A smooth curve through the points: quadratic segments meeting at each midpoint. */
private fun smoothThrough(path: Path, xs: FloatArray, ys: FloatArray, moveFirst: Boolean) {
    if (moveFirst) path.moveTo(xs[0], ys[0])
    for (i in 1 until xs.size) {
        path.quadraticTo(xs[i - 1], ys[i - 1], (xs[i - 1] + xs[i]) / 2f, (ys[i - 1] + ys[i]) / 2f)
    }
    path.lineTo(xs[xs.size - 1], ys[ys.size - 1])
}

// ---- Aurora --------------------------------------------------------------------

/**
 * Curtains of light computed per pixel on the graphics card. Each hangs from a
 * lower edge that sweeps across the window in a slow S-curve, comes and goes
 * along the width, glows green along that edge and turns towards the theme's
 * colour as it fades upward, and is broken into drifting vertical rays.
 */
internal class AuroraShader private constructor(effect: RuntimeEffect) {

    private val builder = RuntimeShaderBuilder(effect)

    fun brush(t: Float, windowWidth: Float, height: Float, offsetX: Float, color: Color): ShaderBrush {
        val edge = lerp(color, Color(0xFF3DF2A6), 0.55f)
        val violet = lerp(color, Color(0xFFB36BFF), 0.45f)
        builder.uniform("iResolution", windowWidth, height)
        builder.uniform("iOffset", offsetX)
        builder.uniform("iTime", t)
        builder.uniform("c1", color.red, color.green, color.blue)
        builder.uniform("c2", edge.red, edge.green, edge.blue)
        builder.uniform("c3", violet.red, violet.green, violet.blue)
        builder.uniform("strength", 0.9f)
        return ShaderBrush(builder.makeShader())
    }

    companion object {
        /** Null where runtime shaders can't be compiled; the drawn aurora stands in. */
        val instance: AuroraShader? by lazy {
            runCatching { AuroraShader(RuntimeEffect.makeForShader(AURORA_SKSL)) }.getOrNull()
        }
    }
}

private const val AURORA_SKSL = """
uniform float2 iResolution;
uniform float iOffset;
uniform float iTime;
uniform float3 c1;
uniform float3 c2;
uniform float3 c3;
uniform float strength;

float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x),
               mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}

float fbm(float2 p) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 4; i++) {
        value += amplitude * noise(p);
        p = p * 2.03 + float2(3.1, 1.7);
        amplitude *= 0.5;
    }
    return value;
}

half4 main(float2 fragCoord) {
    float aspect = iResolution.x / max(iResolution.y, 1.0);
    float2 uv = float2((fragCoord.x + iOffset) / max(iResolution.x, 1.0), fragCoord.y / max(iResolution.y, 1.0));
    float t = iTime;
    float x = uv.x * aspect;
    float3 light = float3(0.0);
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float edge = 0.44 - 0.12 * fi
            + 0.13 * sin(x * (1.1 + 0.4 * fi) + t * (0.045 + 0.02 * fi) + fi * 2.1)
            + 0.14 * (fbm(float2(x * 1.7 + fi * 5.0, t * 0.04)) - 0.5);
        float d = uv.y - edge;
        float presence = 0.35 + 0.65 * smoothstep(0.25, 0.60, fbm(float2(x * 0.55 + fi * 9.0, t * 0.015 + fi)));
        float fall = 0.24 + 0.08 * fi;
        float body = d < 0.0 ? exp(d / fall * 1.8) : exp(-d * 30.0);
        float core = d < 0.0 ? exp(d / 0.045) : exp(-d * 30.0);
        float rays = smoothstep(0.25, 0.85, fbm(float2(x * 10.0 + t * 0.06 * (1.0 + fi), t * 0.10 + fi * 3.7)));
        float shimmer = 0.85 + 0.15 * sin(x * 13.0 + t * (0.8 + 0.3 * fi));
        float3 top = i == 1 ? c3 : c1;
        float3 tint = mix(c2, top, clamp(-d / fall * 1.4, 0.0, 1.0));
        float layer = i == 2 ? 0.6 : 1.0;
        light += tint * presence * layer * (body * 0.55 + core * 0.45) * (0.35 + 0.65 * rays) * shimmer;
    }
    light += c1 * 0.035 * (1.0 - uv.y);
    light *= strength;
    // A whisper of noise, below one step of 8-bit colour, so the fades never band.
    light = max(light + (hash(fragCoord + fract(iTime * 7.0)) - 0.5) / 255.0, float3(0.0));
    float alpha = clamp(max(light.r, max(light.g, light.b)), 0.0, 1.0);
    light = min(light, float3(alpha));
    return half4(half3(light), half(alpha));
}
"""

internal fun DrawScope.aurora(t: Float, color: Color, place: Placement) {
    val shader = AuroraShader.instance
    if (shader == null) {
        drawnAurora(t, color)
        return
    }
    drawRect(shader.brush(t, max(place.width, size.width), size.height, place.x, color))
}

/** Soft pools of light on slow orbits, for renderers without runtime shaders. */
private fun DrawScope.drawnAurora(t: Float, color: Color) {
    val w = size.width
    val h = size.height
    val tints = listOf(color, lerp(color, Color(0xFF3FE0C8), 0.5f), lerp(color, Color(0xFFFF6FD8), 0.4f))
    for (i in 0 until 4) {
        val tint = tints[i % tints.size]
        val center = Offset(
            w * (0.5f + 0.38f * sin(t * 0.045f * (i + 1) + i * 1.7f)),
            h * (0.4f + 0.3f * cos(t * 0.035f * (i + 1) + i * 0.9f))
        )
        val radius = max(w, h) * (0.38f + 0.08f * sin(t * 0.08f + i))
        drawCircle(
            Brush.radialGradient(listOf(tint.copy(alpha = 0.13f), Color.Transparent), center, radius),
            radius,
            center
        )
    }
}

// ---- Fireflies and snow ----------------------------------------------------------

private fun DrawScope.fireflies(t: Float, color: Color, motes: List<Mote>) {
    val glow = lerp(color, Color(0xFFFFE08A), 0.4f)
    for (m in motes) {
        val center = Offset(
            wrap(m.x + 0.03f * sin(t * 0.25f * m.speed + m.phase)) * size.width,
            wrap(m.y + 0.03f * cos(t * 0.19f * m.speed + m.phase * 1.3f) - t * 0.004f * m.speed) * size.height
        )
        val pulse = 0.5f + 0.5f * sin(t * 1.1f * m.speed + m.phase)
        val core = (1.2f + m.size * 1.4f) * density
        softCircle(glow, 0.18f * pulse, center, core * 6f)
        drawCircle(glow.copy(alpha = 0.25f + 0.6f * pulse), core, center)
    }
}

private fun DrawScope.snow(t: Float, color: Color, motes: List<Mote>) {
    val flake = lerp(color, Color.White, 0.65f)
    for (m in motes) {
        val center = Offset(
            wrap(m.x + 0.012f * sin(t * 0.7f * m.speed + m.phase)) * size.width,
            wrap(m.y + t * 0.025f * m.speed) * size.height
        )
        drawCircle(flake.copy(alpha = 0.18f + m.size * 0.45f), (0.7f + m.size * 1.9f) * density, center)
    }
}

// ---- Waves: the song as a graph ---------------------------------------------------

/**
 * The song as a graph scrolling past a "now" line three tenths of the way across
 * the window: about three seconds already played to the left, dimmer, and nine
 * still to come to the right, brighter. Loudness is drawn mirrored about a line
 * in the lower half with a glowing edge, sampled between the 10 ms slices and
 * lightly averaged, so it scrolls smoothly instead of shimmering. The now line
 * glows with what is playing this instant.
 */
internal fun DrawScope.songGraph(color: Color, song: SongShape, pulse: Pulse?, place: Placement) {
    val w = size.width
    val h = size.height
    val windowWidth = max(place.width, w)
    val nowX = windowWidth * 0.3f - place.x
    val msPerPx = GRAPH_SPAN_MS / windowWidth
    val centre = h * 0.62f
    val reach = h * 0.24f
    val position = song.positionMs()
    val hot = lerp(color, Color.White, 0.45f)
    val step = 5f * density
    val slices = song.slices
    val filled = min(song.filled, slices.size)
    val scale = 1f / max(song.loudest, 0.02f)

    fun loudness(ms: Double): Float {
        var sum = 0f
        var n = 0
        for (k in -3..3) {
            val s = ms / SLICE_MS + k
            val i = floor(s).toInt()
            if (i < 0 || i + 1 >= filled) continue
            val f = (s - i).toFloat()
            sum += slices[i] + (slices[i + 1] - slices[i]) * f
            n++
        }
        if (n == 0) return 0f
        return ((sum / n) * scale).coerceIn(0f, 1f).pow(0.75f)
    }

    val count = (w / step).toInt() + 3
    val xs = FloatArray(count)
    val tops = FloatArray(count)
    val bottoms = FloatArray(count)
    for (c in 0 until count) {
        val x = (c - 1) * step
        val amplitude = loudness(position + (x - nowX) * msPerPx) * reach + density
        xs[c] = x
        tops[c] = centre - amplitude
        bottoms[c] = centre + amplitude
    }

    val nowFraction = (nowX / w).coerceIn(0.002f, 0.998f)
    fun split(tint: Color, past: Float, future: Float, tail: Float) = Brush.horizontalGradient(
        0f to tint.copy(alpha = past),
        (nowFraction - 0.001f) to tint.copy(alpha = past),
        (nowFraction + 0.001f) to tint.copy(alpha = future),
        1f to tint.copy(alpha = tail),
        startX = 0f,
        endX = w
    )

    val top = Path().also { smoothThrough(it, xs, tops, moveFirst = true) }
    val bottom = Path().also { smoothThrough(it, xs, bottoms, moveFirst = true) }
    val body = Path().apply {
        smoothThrough(this, xs, tops, moveFirst = true)
        lineTo(xs[count - 1], bottoms[count - 1])
        smoothThrough(this, xs.reversedArray(), bottoms.reversedArray(), moveFirst = false)
        close()
    }

    drawLine(color.copy(alpha = 0.10f), Offset(0f, centre), Offset(w, centre), density)
    drawPath(body, split(color, 0.08f, 0.24f, 0.12f))
    glowPath(top, hot.copy(alpha = 0.22f), 2f * density, 6f * density)
    glowPath(bottom, hot.copy(alpha = 0.22f), 2f * density, 6f * density)
    drawPath(top, split(hot, 0.30f, 0.80f, 0.35f), style = Stroke(width = 1.4f * density))
    drawPath(bottom, split(hot, 0.30f, 0.80f, 0.35f), style = Stroke(width = 1.4f * density))

    if (nowX > -24f && nowX < w + 24f) {
        val level = loudness(position)
        val live = pulse?.energy ?: 0f
        val lineTop = centre - reach * 1.2f
        val lineBottom = centre + reach * 1.2f
        val line = Path().apply {
            moveTo(nowX, lineTop)
            lineTo(nowX, lineBottom)
        }
        glowPath(line, hot.copy(alpha = 0.30f + live * 0.40f), 3f * density, (6f + live * 8f) * density)
        drawLine(hot.copy(alpha = 0.85f), Offset(nowX, lineTop), Offset(nowX, lineBottom), 1.5f * density)
        val reachNow = level * reach + density
        for (y in listOf(centre - reachNow, centre + reachNow)) {
            softCircle(hot, 0.45f + live * 0.35f, Offset(nowX, y), (10f + level * 14f) * density)
            drawCircle(hot, (2.4f + level * 2f) * density, Offset(nowX, y))
        }
    }
}

/**
 * Waves before anything is playing: a ribbon of near-parallel lines rolling
 * along, swelling with the bass when there is music but no song to graph.
 */
internal fun DrawScope.ribbon(t: Float, color: Color, pulse: Pulse?, place: Placement) {
    val w = size.width
    val h = size.height
    val windowWidth = max(place.width, w)
    val step = 4f * density
    val lines = 7
    val half = (lines - 1) / 2f
    val hot = lerp(color, Color.White, 0.5f)
    val bass = pulse?.bass ?: 0f
    val energy = pulse?.energy ?: 0f
    val kick = pulse?.kick ?: 0f
    val swell = h * (0.08f + bass * 0.17f + kick * 0.05f)
    val spread = h * (0.042f + energy * 0.035f)
    val frequency = TAU * 1.0f / windowWidth
    val ripple = TAU * 2.6f / windowWidth
    val drift = t * 0.32f + (pulse?.travel ?: 0f) * 9f
    val breathe = sin(t * 0.37f) * h * 0.035f
    for (k in 0 until lines) {
        val offset = k - half
        val level = pulse?.let { it.average(k * it.levels.size / lines, (k + 1) * it.levels.size / lines) } ?: 0f
        val base = h * 0.60f + breathe + offset * spread
        val twist = offset * 0.32f
        val path = Path()
        var x = -step
        var first = true
        while (x <= w + step) {
            val along = x + place.x
            val y = base + sin(along * frequency + drift + twist) * swell +
                sin(along * ripple - t * 0.55f + k * 0.9f) * h * 0.016f * (1f + level * 3.2f)
            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
            x += step
        }
        val centreWeight = 1f - abs(offset) / half
        val tint = lerp(color, hot, (0.15f + level * 0.6f) * centreWeight)
        val alpha = ((0.10f + 0.16f * centreWeight) * (0.8f + energy * 1.4f + level * 0.6f)).coerceAtMost(0.6f)
        if (centreWeight > 0.6f) {
            glowPath(path, tint.copy(alpha = alpha * 0.55f), (3f + bass * 5f) * density, (5f + bass * 7f) * density)
        }
        drawPath(path, tint.copy(alpha = alpha), style = Stroke(width = (1.1f + centreWeight * 0.8f + level * 1.2f) * density))
    }
}

// ---- Reactive ------------------------------------------------------------------

/** How big Reactive's ball is in a surface [width] by [height], in pixels. */
internal fun ballRadius(pulse: Pulse, width: Float, height: Float): Float =
    min(width, height) * 0.105f * (0.78f + pulse.punch * 0.68f + pulse.kick * 0.18f)

/** How tall Reactive's bar for [band] stands in a surface [height] tall, in pixels. */
internal fun barHeight(pulse: Pulse, band: Int, height: Float, density: Float): Float {
    val count = pulse.levels.size
    val level = pulse.levels[band].coerceIn(0f, 1f)
    // The bass end is thrown up further on each beat; the top end stays honest.
    val low = (1f - band / (count * 0.35f)).coerceIn(0f, 1f)
    val lift = pulse.kick * low * 0.16f + pulse.punch * low * 0.10f
    return 4f * density + (level.pow(0.9f) + lift).coerceAtMost(1.15f) * height * 0.34f
}

/**
 * A see-through glowing ball in the middle of the page, ringed by the spectrum
 * - mirrored so it is symmetrical, bass at the bottom.
 *
 * It swells and shrinks with the bass as heard, to about twice the size on a
 * hard beat, sends a ring out on each kick, and shakes the way music visualiser
 * videos do: a quick irregular jitter that dies away with the kick.
 */
internal fun DrawScope.reactiveBall(t: Float, color: Color, pulse: Pulse) {
    val w = size.width
    val h = size.height
    val hot = lerp(color, Color.White, 0.4f)
    val punch = pulse.punch
    val kick = pulse.kick
    val shake = (kick * 26f + punch * 4f) * density
    val jitter = Offset(
        (sin(t * 47f) * 0.6f + sin(t * 83f + 1.3f) * 0.4f) * shake,
        (cos(t * 53f) * 0.6f + sin(t * 71f + 2.1f) * 0.4f) * shake
    )
    val centre = Offset(w / 2f, h / 2f) + jitter
    val radius = ballRadius(pulse, w, h)

    softCircle(color, 0.10f + punch * 0.30f + kick * 0.08f, centre, radius * 4.2f)

    for (birth in pulse.rings) {
        val age = t - birth
        if (age < 0f || age > 1.2f) continue
        val progress = age / 1.2f
        val fade = (1f - progress) * (1f - progress)
        drawCircle(
            hot.copy(alpha = 0.55f * fade),
            radius * (1.15f + progress * 3.2f),
            centre,
            style = Stroke(width = (2.8f - 1.8f * progress) * density)
        )
    }

    val bars = 72
    val half = bars / 2
    val bands = pulse.levels.size
    val ring = Path()
    for (i in 0 until bars) {
        val j = if (i < half) i else bars - 1 - i
        val level = pulse.levels[(j * bands / half).coerceAtMost(bands - 1)].coerceIn(0f, 1f)
        val angle = PI / 2 + i.toDouble() / bars * 2 * PI
        val inner = radius * 1.12f
        val outer = inner + radius * (0.05f + level.pow(0.85f) * 1.25f)
        val dx = cos(angle).toFloat()
        val dy = sin(angle).toFloat()
        ring.moveTo(centre.x + dx * inner, centre.y + dy * inner)
        ring.lineTo(centre.x + dx * outer, centre.y + dy * outer)
    }
    glowPath(ring, color.copy(alpha = 0.45f + punch * 0.25f), 3f * density, 7f * density)
    drawPath(ring, hot.copy(alpha = 0.9f), style = Stroke(width = 2.6f * density, cap = StrokeCap.Round))

    drawCircle(
        Brush.radialGradient(
            listOf(hot.copy(alpha = 0.35f + punch * 0.35f), color.copy(alpha = 0.30f), color.copy(alpha = 0.14f)),
            centre,
            radius
        ),
        radius,
        centre
    )
    drawCircle(hot.copy(alpha = 0.85f), radius, centre, style = Stroke(width = (2f + punch * 1.5f) * density))
}

/**
 * The Effects panel's output meter across the bottom of the window: the same
 * rounded bars, one per analyser band, but reaching most of the way up the
 * window and stretched to each band's own recent range.
 *
 * One row of bars laid out across the whole window, each surface drawing the
 * part of it that falls inside itself. Where that part is a menu rather than
 * the page - [centerpiece] is false for the sidebar and the side panel - the
 * bars sink away as they go further in, so they read as something carrying on
 * behind the menu rather than something drawn across it.
 */
internal fun DrawScope.reactiveBars(
    color: Color,
    pulse: Pulse,
    place: Placement,
    centerpiece: Boolean = true
) {
    val w = size.width
    val h = size.height
    val windowWidth = max(place.width, w)
    val count = pulse.levels.size
    val margin = 24f * density
    val gap = 6f * density
    val barWidth = ((windowWidth - margin * 2 - gap * (count - 1)) / count).coerceAtLeast(2f)
    val floorY = h - 14f * density
    val quiet = lerp(color, Color.Black, 0.55f).copy(alpha = 0.55f)
    val lit = lerp(color, Color.White, 0.25f)
    val corner = CornerRadius(min(barWidth / 2f, 4f * density))
    // How far into a menu a bar is, measured from the edge it shares with the page.
    val fadeOver = 170f * density
    val intoMenu: (Float) -> Float = when {
        centerpiece -> { _ -> 0f }
        // The sidebar sits at the window's left edge, so its inner edge is on the right.
        place.x <= 1f -> { centre -> (w - centre) / fadeOver }
        else -> { centre -> centre / fadeOver }
    }
    for (i in 0 until count) {
        val level = pulse.levels[i].coerceIn(0f, 1f)
        val tall = barHeight(pulse, i, h, density)
        val left = margin + i * (barWidth + gap) - place.x
        if (left > w || left + barWidth < 0f) continue
        val top = floorY - tall
        val depth = intoMenu(left + barWidth / 2f).coerceIn(0f, 1f)
        val fade = 1f - (1f - BEHIND_MENUS) * (depth * depth * (3f - 2f * depth))
        if (level > 0.02f) {
            // A column you can see through, with a bright cap along the top: it
            // still reads as a meter, without standing in front of the library.
            // Filled solid, it drowned the list it is supposed to sit behind.
            softCircle(color, (0.04f + level * 0.13f) * fade, Offset(left + barWidth / 2f, top), barWidth * 1.7f)
            drawRoundRect(
                Brush.verticalGradient(
                    0f to color.copy(alpha = 0.05f * fade),
                    0.4f to color.copy(alpha = 0.15f * fade),
                    1f to color.copy(alpha = 0.30f * fade),
                    startY = top,
                    endY = floorY
                ),
                Offset(left, top),
                Size(barWidth, tall),
                corner
            )
            drawRoundRect(
                lit.copy(alpha = 0.40f * fade),
                Offset(left, top),
                Size(barWidth, min(3f * density, tall)),
                corner
            )
        } else {
            drawRoundRect(
                quiet.copy(alpha = quiet.alpha * 0.45f * fade),
                Offset(left, top),
                Size(barWidth, tall),
                corner
            )
        }
    }
}

/** What is left of a bar once it is well inside the sidebar or the side panel. */
private const val BEHIND_MENUS = 0.14f
