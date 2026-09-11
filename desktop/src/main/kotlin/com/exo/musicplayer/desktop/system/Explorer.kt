package com.exo.musicplayer.desktop.system

import java.awt.Desktop
import java.io.File

/** Opens File Explorer on a file, with the file selected. */
object Explorer {

    /**
     * explorer.exe's arguments for [file]: `/select,` and the path as two
     * arguments, which Java turns into `explorer.exe /select, "G:\Music\a song.mp3"`.
     *
     * As one argument, `/select,G:\Music\a song.mp3`, Java quotes all of it
     * whenever the path has a space, and Explorer doesn't read a quoted
     * `"/select,..."` as /select: it opened Documents instead of the song's
     * folder, for nearly every song. Split in two, only the path is quoted.
     */
    fun arguments(file: File): List<String> {
        val target = file.absoluteFile
        return when {
            target.isFile -> listOf("explorer.exe", "/select,", target.path)
            target.isDirectory -> listOf("explorer.exe", target.path)
            else -> listOf("explorer.exe", (target.parentFile ?: target).path)
        }
    }

    fun reveal(file: File) {
        val started = runCatching { ProcessBuilder(arguments(file)).start() }.isSuccess
        if (!started) {
            runCatching { Desktop.getDesktop().open(file.absoluteFile.parentFile) }
        }
    }
}
