package com.exo.musicplayer.data.archive

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One file going into the archive, and the name it should have inside. */
data class ArchiveEntry(val source: File, val entryName: String)

/** How far along a zip is. */
data class ArchiveProgress(
    val done: Int,
    val total: Int,
    val currentName: String,
    val bytesWritten: Long
) {
    val fraction: Float get() = if (total > 0) done.toFloat() / total else 0f
}

/** What a finished zip contains. */
data class ArchiveResult(
    val file: File,
    val included: Int,
    val skipped: List<String>,
    val bytes: Long
)

/**
 * Packs a library or a playlist into a single zip.
 *
 * For moving a collection somewhere else — another machine, a backup drive, a
 * friend — where handing over one file beats a few hundred.
 *
 * Music files are already compressed, so entries are **stored, not deflated**.
 * Running deflate over MP3 or FLAC costs real time and typically saves under
 * one percent, because the audio codec has already squeezed the entropy out.
 * Storing makes the zip roughly the sum of its inputs and finishes at disk
 * speed. The cost is that a stored entry must declare its size and CRC up
 * front, so every file is read twice — still far cheaper than compressing it.
 *
 * Duplicate names are disambiguated rather than allowed to collide: two files
 * can easily share a title, and an archive with an entry silently missing is
 * worse than one containing `Song (2).mp3`.
 */
object MusicArchive {

    /**
     * Writes [entries] into [destination].
     *
     * @param shouldContinue polled between files; returning false abandons the
     *   job and deletes the partial archive, so a cancelled zip leaves nothing
     *   half-written behind.
     */
    fun zip(
        entries: List<ArchiveEntry>,
        destination: File,
        onProgress: (ArchiveProgress) -> Unit = {},
        shouldContinue: () -> Boolean = { true }
    ): Result<ArchiveResult> = runCatching {
        destination.parentFile?.mkdirs()

        val skipped = mutableListOf<String>()
        var included = 0
        var bytesWritten = 0L
        val usedNames = HashSet<String>()
        val buffer = ByteArray(64 * 1024)
        var cancelled = false

        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            zip.setMethod(ZipOutputStream.STORED)

            for (entry in entries) {
                if (!shouldContinue()) {
                    cancelled = true
                    break
                }

                val source = entry.source
                if (!source.isFile) {
                    skipped += entry.entryName
                    continue
                }

                val name = uniqueName(entry.entryName, usedNames)
                val size = source.length()

                val crc = CRC32()
                source.inputStream().buffered().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        crc.update(buffer, 0, read)
                    }
                }

                zip.putNextEntry(
                    ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        this.size = size
                        compressedSize = size
                        this.crc = crc.value
                        time = source.lastModified()
                    }
                )
                source.inputStream().buffered().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        zip.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()

                included++
                bytesWritten += size
                onProgress(ArchiveProgress(included, entries.size, source.name, bytesWritten))
            }
        }

        if (cancelled) {
            destination.delete()
            error("Cancelled.")
        }
        if (included == 0) {
            destination.delete()
            error("None of those files could be read.")
        }

        ArchiveResult(destination, included, skipped, destination.length())
    }

    /** Characters Windows forbids in a filename. Android is more permissive. */
    private val FORBIDDEN = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    /**
     * A filename safe on every platform this runs on.
     *
     * Written as a character filter rather than a regex on purpose: the set
     * involves backslash, quote and control codes, which is exactly the kind of
     * pattern that survives one round of escaping and not the next. Spaces and
     * hyphens are legal and belong in song titles, so they stay.
     *
     * An archive made on Windows has to unpack on Android and the reverse, so
     * the stricter platform's rules apply to both.
     */
    fun safeName(raw: String): String {
        val cleaned = buildString(raw.length) {
            for (character in raw) {
                append(if (character in FORBIDDEN || character.code < 0x20) '_' else character)
            }
        }.trim().trimEnd('.', ' ')
        return cleaned.ifEmpty { "track" }.take(120)
    }

    private fun uniqueName(name: String, used: HashSet<String>): String {
        if (used.add(name)) return name
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var index = 2
        while (true) {
            val suffix = if (extension.isEmpty()) "" else ".$extension"
            val candidate = "$base ($index)$suffix"
            if (used.add(candidate)) return candidate
            index++
        }
    }
}
