package com.exo.musicplayer.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.exo.musicplayer.data.db.Track

/**
 * Editing a track by hand.
 *
 * Automatic identification is right most of the time and not all of it: a live
 * bootleg, a track nobody has catalogued, a name in a script the services
 * transliterate differently. This is the fallback for those, and the same
 * dialog the Windows build has.
 *
 * Blank fields are cleared rather than ignored, because "remove the wrong
 * album name" has to be expressible. Only the title falls back, to the file
 * name, since a track with no title at all cannot be shown in a list.
 */
@Composable
fun EditTrackDialog(
    track: Track,
    onSave: (title: String, artist: String, album: String, year: Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember(track.id) { mutableStateOf(track.title) }
    var artist by remember(track.id) { mutableStateOf(track.artist.orEmpty()) }
    var album by remember(track.id) { mutableStateOf(track.album.orEmpty()) }
    var year by remember(track.id) { mutableStateOf(track.year?.toString().orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit details") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text("Album") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = year,
                    onValueChange = { entered ->
                        // Digits only, and no longer than a year: the keyboard
                        // type is a hint on Android, not a guarantee.
                        year = entered.filter { it.isDigit() }.take(4)
                    },
                    label = { Text("Year") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    // Said plainly rather than implied. The Windows build
                    // rewrites the file's own tags; here there is no tag writer,
                    // so the change is to the library entry. It shows
                    // everywhere in the app, but a copy handed to another
                    // program still carries the old tags.
                    "Updates this track in your library. The file's own embedded tags " +
                        "are left as they are, so anything you export or share will " +
                        "still carry the original ones.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        title.trim(),
                        artist.trim(),
                        album.trim(),
                        year.trim().toIntOrNull()
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
