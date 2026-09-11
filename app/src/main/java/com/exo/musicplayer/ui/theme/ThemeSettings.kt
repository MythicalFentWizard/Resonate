package com.exo.musicplayer.ui.theme

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class ThemeState(
    val palette: AppPalette = AppPalette.MIDNIGHT,
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val stars: Boolean = true,
    /** When the wallpaper was last set, so a new picture reloads; 0 for none. */
    val wallpaper: Long = 0L,
    val wallpaperDim: Float = 0.45f,
    /** ARGB colours for the lyric line being sung and the others; null follows the theme. */
    val lyricsActive: Int? = null,
    val lyricsInactive: Int? = null
)

/**
 * Appearance preferences.
 *
 * Kept in SharedPreferences and mirrored into a StateFlow so the whole UI
 * recomposes the moment a palette is tapped — a theme picker that only takes
 * effect after a restart is a bad theme picker.
 */
class ThemeSettings(context: Context) {

    private val appContext = context.applicationContext

    private val prefs =
        appContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    /** Resonate's own copy of the wallpaper, so moving or deleting the original doesn't lose it. */
    val wallpaperFile: File get() = File(appContext.filesDir, "wallpaper.img")

    private val _state = MutableStateFlow(
        ThemeState(
            palette = AppPalette.fromName(prefs.getString(KEY_PALETTE, null)),
            mode = ThemeMode.fromName(prefs.getString(KEY_MODE, null)),
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC, false),
            stars = prefs.getBoolean(
                KEY_STARS,
                AppPalette.fromName(prefs.getString(KEY_PALETTE, null)).starsByDefault
            ),
            wallpaper = prefs.getLong(KEY_WALLPAPER, 0L),
            wallpaperDim = prefs.getFloat(KEY_WALLPAPER_DIM, 0.45f),
            lyricsActive = if (prefs.contains(KEY_LYRICS_ACTIVE)) prefs.getInt(KEY_LYRICS_ACTIVE, 0) else null,
            lyricsInactive = if (prefs.contains(KEY_LYRICS_INACTIVE)) prefs.getInt(KEY_LYRICS_INACTIVE, 0) else null
        )
    )
    val state: StateFlow<ThemeState> = _state.asStateFlow()

    fun setPalette(palette: AppPalette) {
        // Switching to a palette the user has never tuned adopts that palette's
        // own recommendation for stars; an explicit choice is respected below.
        val starsTouched = prefs.contains(KEY_STARS)
        val stars = if (starsTouched) _state.value.stars else palette.starsByDefault
        prefs.edit().putString(KEY_PALETTE, palette.name).apply()
        _state.value = _state.value.copy(palette = palette, stars = stars, dynamicColor = false)
        prefs.edit().putBoolean(KEY_DYNAMIC, false).apply()
    }

    fun setMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _state.value = _state.value.copy(mode = mode)
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC, enabled).apply()
        _state.value = _state.value.copy(dynamicColor = enabled)
    }

    fun setStars(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STARS, enabled).apply()
        _state.value = _state.value.copy(stars = enabled)
    }

    /** Copies the picture in on a background thread, then shows it. */
    fun setWallpaper(uri: Uri) {
        Thread {
            val copied = runCatching {
                appContext.contentResolver.openInputStream(uri)!!.use { input ->
                    wallpaperFile.outputStream().use { input.copyTo(it) }
                }
            }.isSuccess
            if (copied) {
                val stamp = System.currentTimeMillis()
                prefs.edit().putLong(KEY_WALLPAPER, stamp).apply()
                _state.value = _state.value.copy(wallpaper = stamp)
            }
        }.apply { isDaemon = true }.start()
    }

    fun clearWallpaper() {
        runCatching { wallpaperFile.delete() }
        prefs.edit().remove(KEY_WALLPAPER).apply()
        _state.value = _state.value.copy(wallpaper = 0L)
    }

    fun setWallpaperDim(dim: Float) {
        val clamped = dim.coerceIn(0f, 0.9f)
        prefs.edit().putFloat(KEY_WALLPAPER_DIM, clamped).apply()
        _state.value = _state.value.copy(wallpaperDim = clamped)
    }

    fun setLyricsActive(argb: Int?) {
        prefs.edit().apply { if (argb == null) remove(KEY_LYRICS_ACTIVE) else putInt(KEY_LYRICS_ACTIVE, argb) }.apply()
        _state.value = _state.value.copy(lyricsActive = argb)
    }

    fun setLyricsInactive(argb: Int?) {
        prefs.edit().apply { if (argb == null) remove(KEY_LYRICS_INACTIVE) else putInt(KEY_LYRICS_INACTIVE, argb) }.apply()
        _state.value = _state.value.copy(lyricsInactive = argb)
    }

    private companion object {
        const val KEY_WALLPAPER = "wallpaper"
        const val KEY_WALLPAPER_DIM = "wallpaper_dim"
        const val KEY_LYRICS_ACTIVE = "lyrics_active"
        const val KEY_LYRICS_INACTIVE = "lyrics_inactive"
        const val KEY_PALETTE = "palette"
        const val KEY_MODE = "mode"
        const val KEY_DYNAMIC = "dynamic"
        const val KEY_STARS = "stars"
    }
}
