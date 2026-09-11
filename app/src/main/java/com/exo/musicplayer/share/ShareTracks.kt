package com.exo.musicplayer.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.exo.musicplayer.data.db.Track
import java.io.File

/**
 * Hands tracks out to other apps.
 *
 * The counterpart to [ShareReceiverActivity]: songs arrive from Telegram and can
 * now go back the other way, to a chat, a cloud drive, or a friend's phone over
 * Bluetooth.
 *
 * Imported files live in app-private storage, so a `file://` path is unreadable
 * to the receiving app and on API 24+ throws `FileUriExposedException` outright.
 * Everything therefore goes out as a `content://` URI from the FileProvider,
 * with a read grant attached to the intent. That grant is scoped to the activity
 * that receives it and lapses with it — the same mechanism Telegram uses when it
 * shares *into* this app, and the reason the importer has to copy the bytes
 * rather than keep the URI.
 */
object ShareTracks {

    private const val AUTHORITY_SUFFIX = ".shared"

    /**
     * Builds a share intent for [tracks], or null if none of their files exist.
     *
     * Files can go missing between the database and the filesystem — storage
     * unmounted, a file deleted from outside the app — and a share carrying a
     * URI to nothing produces a failure in the *other* app, where the user
     * cannot tell what went wrong. Missing files are dropped here instead.
     */
    fun intentFor(context: Context, tracks: List<Track>): Intent? {
        val authority = context.packageName + AUTHORITY_SUFFIX
        val uris = ArrayList<Uri>(tracks.size)

        for (track in tracks) {
            val file = File(track.filePath)
            if (!file.isFile) continue
            val uri = runCatching {
                FileProvider.getUriForFile(context, authority, file)
            }.getOrNull() ?: continue
            uris += uri
        }
        if (uris.isEmpty()) return null

        // A single file goes out as ACTION_SEND. Some receivers - older
        // Bluetooth stacks and a few messaging apps among them - only register
        // for SEND and would not appear in the sheet for SEND_MULTIPLE.
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }

        return intent.apply {
            type = commonMimeType(tracks)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Named so the sheet and the receiving app have something to show.
            putExtra(Intent.EXTRA_SUBJECT, subjectFor(tracks))
            putExtra(Intent.EXTRA_TEXT, listFor(tracks))
            // ClipData carries the grant to receivers that read it from there
            // rather than from EXTRA_STREAM, which not all of them do.
            clipData = android.content.ClipData.newUri(
                context.contentResolver,
                subjectFor(tracks),
                uris.first()
            ).also { clip ->
                for (index in 1 until uris.size) {
                    clip.addItem(android.content.ClipData.Item(uris[index]))
                }
            }
        }
    }

    /**
     * The narrowest type that covers every file.
     *
     * A mixed selection has to go out as a wildcard audio type; claiming
     * `audio/mpeg` for a set that includes a FLAC would have receivers reject
     * it or mis-handle it.
     */
    private fun commonMimeType(tracks: List<Track>): String {
        val concrete = tracks.mapNotNull { track ->
            track.mimeType?.trim()?.takeIf { it.contains('/') && !it.endsWith("/*") }
        }.distinct()
        return concrete.singleOrNull() ?: "audio/*"
    }

    private fun subjectFor(tracks: List<Track>): String = when (tracks.size) {
        0 -> "Music"
        1 -> tracks.first().let { track ->
            listOfNotNull(track.artist?.takeIf { it.isNotBlank() }, track.title)
                .joinToString(" — ")
        }
        else -> "${tracks.size} songs"
    }

    /** A readable track list, for receivers that show the text and not the files. */
    private fun listFor(tracks: List<Track>): String = tracks.joinToString("\n") { track ->
        listOfNotNull(track.artist?.takeIf { it.isNotBlank() }, track.title)
            .joinToString(" — ")
    }
}
