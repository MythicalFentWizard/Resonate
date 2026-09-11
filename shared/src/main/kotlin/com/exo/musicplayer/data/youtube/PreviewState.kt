package com.exo.musicplayer.data.youtube

/**
 * What the preview player is doing, for the row that asked for it.
 *
 * Shared because both platforms show the same three things on a result row - a
 * spinner while the stream URL is being signed, a stop affordance while it
 * plays, and a running position over the total - even though what produces the
 * audio underneath is completely different: ffmpeg piping PCM on Windows,
 * ExoPlayer on Android.
 */
data class PreviewState(
    /** The video being previewed, or null when nothing is. */
    val videoId: String? = null,
    /** True while the stream URL is still being resolved. */
    val loading: Boolean = false,
    val playing: Boolean = false,
    val secondsPlayed: Int = 0,
    val error: String? = null
)
