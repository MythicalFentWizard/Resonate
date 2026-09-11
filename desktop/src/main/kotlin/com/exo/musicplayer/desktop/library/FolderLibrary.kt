package com.exo.musicplayer.desktop.library

import androidx.compose.runtime.Immutable
import com.exo.musicplayer.util.AudioTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/** Immutable so Compose can skip rows whose track hasn't changed. */
@Immutable
data class DesktopTrack(
    val file: File,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val trackNumber: Int?,
    val year: Int?,
    val sizeBytes: Long,
    /** The album artist tag, where the file has one. */
    val albumArtist: String? = null
) {
    val displayArtist: String get() = artist?.takeIf { it.isNotBlank() } ?: "Unknown artist"
    val displayAlbum: String get() = album?.takeIf { it.isNotBlank() } ?: "—"
}

/**
 * Reads a music library straight off the filesystem.
 *
 * The desktop import model is deliberately different from Android's: there is no
 * share sheet to receive from, and no reason to copy files into private storage
 * when the user already has them organised in folders. Point it at a directory
 * and it reads what is there, leaving the files exactly where they are.
 */
object FolderLibrary {

    init {
        // jaudiotagger logs an INFO line per file, which is unusable noise when
        // scanning thousands of tracks.
        Logger.getLogger("org.jaudiotagger").level = Level.SEVERE
    }

    private const val MAX_DEPTH = 12

    suspend fun scan(
        roots: List<File>,
        onProgress: (scanned: Int, current: String) -> Unit = { _, _ -> }
    ): List<DesktopTrack> = withContext(Dispatchers.IO) {
        val files = mutableListOf<File>()
        roots.filter { it.isDirectory }.forEach { collect(it, files, 0) }

        // A download folder inside a music folder is scanned from both roots, so
        // the same file would otherwise appear twice.
        files.distinctBy { it.absolutePath.lowercase() }.mapIndexedNotNull { index, file ->
            onProgress(index + 1, file.name)
            read(file)
        }.sortedWith(
            compareBy({ it.displayArtist.lowercase() }, { it.title.lowercase() })
        )
    }

    /**
     * Reads particular files, so a finished download can join the library without
     * re-reading every tag in it. Anything that is not audio is skipped, by the
     * same test a scan applies.
     */
    suspend fun readFiles(files: List<File>): List<DesktopTrack> = withContext(Dispatchers.IO) {
        files.filter { it.isFile && AudioTypes.isProbablyAudio(null, it.name) }
            .mapNotNull { read(it) }
    }

    private fun collect(dir: File, into: MutableList<File>, depth: Int) {
        if (depth > MAX_DEPTH) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            when {
                child.isDirectory -> collect(child, into, depth + 1)
                // Reuses the same extension table the Android app imports with,
                // so both platforms agree on what counts as audio.
                AudioTypes.isProbablyAudio(null, child.name) -> into += child
            }
        }
    }

    private fun read(file: File): DesktopTrack? = runCatching {
        val audio = AudioFileIO.read(file)
        val tag = audio.tag
        val header = audio.audioHeader

        fun field(key: FieldKey): String? =
            runCatching { tag?.getFirst(key)?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()

        DesktopTrack(
            file = file,
            title = field(FieldKey.TITLE) ?: file.nameWithoutExtension,
            artist = field(FieldKey.ARTIST) ?: field(FieldKey.ALBUM_ARTIST),
            album = field(FieldKey.ALBUM),
            durationMs = (header?.preciseTrackLength ?: 0.0).times(1000).toLong(),
            trackNumber = field(FieldKey.TRACK)?.substringBefore('/')?.toIntOrNull(),
            year = field(FieldKey.YEAR)?.take(4)?.toIntOrNull(),
            sizeBytes = file.length(),
            albumArtist = field(FieldKey.ALBUM_ARTIST)
        )
    }.getOrElse {
        // A file jaudiotagger cannot parse is still probably playable, so it is
        // kept with filename-derived metadata rather than dropped.
        DesktopTrack(
            file = file,
            title = file.nameWithoutExtension,
            artist = null,
            album = null,
            durationMs = 0L,
            trackNumber = null,
            year = null,
            sizeBytes = file.length()
        )
    }
}
