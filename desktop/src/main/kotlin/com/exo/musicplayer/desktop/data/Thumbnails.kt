package com.exo.musicplayer.desktop.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlin.math.min
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.util.Collections
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decoded cover art, kept small and kept to a budget.
 *
 * Album art ships at whatever size the service felt like — Deezer's `cover_xl`
 * is 1000×1000, which decodes to 4 MB of pixels. Caching a few hundred of those
 * to draw 34-pixel table rows is how a music player quietly ends up holding
 * several hundred megabytes, and it was.
 *
 * Everything is therefore scaled down to [MAX_EDGE] before it is cached, which
 * is comfortably more than the largest place art is drawn (the 144dp album
 * grid), and the cache is bounded by *bytes* rather than by a count — an
 * entry-count limit says nothing useful when entries range from 8 KB to 4 MB.
 *
 * The full-resolution image is never retained: it is decoded, drawn once into a
 * smaller surface, and closed.
 */
object Thumbnails {

    /**
     * Longest edge kept in memory. 256px covers the 144dp album grid on a
     * standard-density display and every smaller use with room to spare.
     */
    const val MAX_EDGE = 256

    /** Roughly 40 MB of pixels, whatever mix of sizes that turns out to be. */
    private const val BYTE_BUDGET = 40L * 1024 * 1024

    /** Decodes [bytes], scaling down so the longest edge is at most [MAX_EDGE]. */
    fun decodeScaled(bytes: ByteArray, maxEdge: Int = MAX_EDGE): ImageBitmap? = runCatching {
        val source = Image.makeFromEncoded(bytes)
        try {
            val longest = max(source.width, source.height)
            if (longest <= maxEdge) return@runCatching source.toComposeImageBitmap()

            val scale = maxEdge.toFloat() / longest
            val width = (source.width * scale).roundToInt().coerceAtLeast(1)
            val height = (source.height * scale).roundToInt().coerceAtLeast(1)

            val surface = Surface.makeRasterN32Premul(width, height)
            try {
                surface.canvas.drawImageRect(
                    source,
                    Rect.makeWH(width.toFloat(), height.toFloat())
                )
                surface.makeImageSnapshot().toComposeImageBitmap()
            } finally {
                surface.close()
            }
        } finally {
            source.close()
        }
    }.getOrNull()

    /**
     * [bytes] cropped to its centre square and re-encoded as JPEG, when the image
     * is clearly not square; otherwise [bytes] untouched.
     *
     * For YouTube thumbnails, which are 16:9 with the cover art in the middle,
     * so what gets stored and embedded looks like cover art everywhere.
     */
    fun squared(bytes: ByteArray): ByteArray = runCatching {
        val source = Image.makeFromEncoded(bytes)
        try {
            val side = min(source.width, source.height)
            if (max(source.width, source.height) <= side * 1.1f) return@runCatching bytes
            val surface = Surface.makeRasterN32Premul(side, side)
            try {
                surface.canvas.drawImageRect(
                    source,
                    Rect.makeXYWH(
                        ((source.width - side) / 2).toFloat(),
                        ((source.height - side) / 2).toFloat(),
                        side.toFloat(),
                        side.toFloat()
                    ),
                    Rect.makeWH(side.toFloat(), side.toFloat())
                )
                val snapshot = surface.makeImageSnapshot()
                try {
                    snapshot.encodeToData(EncodedImageFormat.JPEG, 92)?.bytes ?: bytes
                } finally {
                    snapshot.close()
                }
            } finally {
                surface.close()
            }
        } finally {
            source.close()
        }
    }.getOrDefault(bytes)

    /** Four bytes a pixel, which is what N32 premultiplied costs. */
    fun sizeOf(bitmap: ImageBitmap): Long = bitmap.width.toLong() * bitmap.height * 4

    /**
     * A least-recently-used cache bounded by total pixel bytes.
     *
     * Access-ordered, so entries fall out in the order they stopped being
     * looked at rather than the order they arrived.
     */
    class ByteBudgetCache(private val budget: Long = BYTE_BUDGET) {

        private var bytes = 0L

        private val entries: MutableMap<String, ImageBitmap> = Collections.synchronizedMap(
            object : LinkedHashMap<String, ImageBitmap>(32, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, ImageBitmap>?
                ): Boolean {
                    if (eldest == null || bytes <= budget) return false
                    bytes -= sizeOf(eldest.value)
                    return true
                }
            }
        )

        operator fun get(key: String): ImageBitmap? = entries[key]

        operator fun set(key: String, value: ImageBitmap) {
            synchronized(entries) {
                entries.put(key, value)?.let { bytes -= sizeOf(it) }
                bytes += sizeOf(value)
                // removeEldestEntry only evicts one entry per insertion, so a
                // run of large images needs the rest trimmed explicitly.
                val iterator = entries.entries.iterator()
                while (bytes > budget && iterator.hasNext()) {
                    val entry = iterator.next()
                    bytes -= sizeOf(entry.value)
                    iterator.remove()
                }
            }
        }

        operator fun minusAssign(key: String) {
            synchronized(entries) {
                entries.remove(key)?.let { bytes -= sizeOf(it) }
            }
        }

        fun clear() = synchronized(entries) {
            entries.clear()
            bytes = 0
        }

        val approximateBytes: Long get() = bytes
    }

    /**
     * A bounded set, for remembering which lookups came back empty.
     *
     * Unbounded, these grow for every track and every failed URL for as long as
     * the app runs. Forgetting the oldest just means one wasted retry.
     */
    class BoundedKeySet(private val limit: Int = 4000) {
        private val keys: MutableSet<String> = Collections.newSetFromMap(
            Collections.synchronizedMap(
                object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
                    override fun removeEldestEntry(
                        eldest: MutableMap.MutableEntry<String, Boolean>?
                    ): Boolean = size > limit
                }
            )
        )

        operator fun contains(key: String): Boolean = keys.contains(key)
        operator fun plusAssign(key: String) { keys.add(key) }
        operator fun minusAssign(key: String) { keys.remove(key) }
    }
}
