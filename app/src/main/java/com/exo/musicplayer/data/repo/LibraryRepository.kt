package com.exo.musicplayer.data.repo

import android.content.Context
import com.exo.musicplayer.data.db.MusicDatabase
import com.exo.musicplayer.data.db.Playlist
import com.exo.musicplayer.data.db.PlaylistSummary
import com.exo.musicplayer.data.db.Track
import com.exo.musicplayer.data.recognition.MusicMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

enum class SortMode(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    RECENTLY_ADDED("Recently added"),
    DURATION("Longest first"),
    MOST_PLAYED("Most played")
}

class LibraryRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db = MusicDatabase.get(appContext)
    private val trackDao = db.trackDao()
    private val playlistDao = db.playlistDao()

    fun observeTracks(sort: SortMode): Flow<List<Track>> = when (sort) {
        SortMode.TITLE -> trackDao.observeByTitle()
        SortMode.ARTIST -> trackDao.observeByArtist()
        SortMode.ALBUM -> trackDao.observeByAlbum()
        SortMode.RECENTLY_ADDED -> trackDao.observeByRecentlyAdded()
        SortMode.DURATION -> trackDao.observeByDuration()
        SortMode.MOST_PLAYED -> trackDao.observeByPlayCount()
    }

    fun observeFavorites(): Flow<List<Track>> = trackDao.observeFavorites()

    fun observeTrackCount(): Flow<Int> = trackDao.observeCount()

    fun search(query: String): Flow<List<Track>> = trackDao.search(query)

    suspend fun trackById(id: Long): Track? = trackDao.findById(id)

    suspend fun tracksByIds(ids: List<Long>): List<Track> {
        if (ids.isEmpty()) return emptyList()
        val byId = trackDao.findByIds(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    suspend fun allTracks(): List<Track> = trackDao.allOnce()

    suspend fun setFavorite(id: Long, favorite: Boolean) = trackDao.setFavorite(id, favorite)

    suspend fun markPlayed(id: Long) = trackDao.markPlayed(id, System.currentTimeMillis())

    suspend fun markArtChecked(id: Long) =
        trackDao.markArtChecked(id, System.currentTimeMillis())

    suspend fun markIdentified(id: Long) =
        trackDao.markIdentified(id, System.currentTimeMillis())

    suspend fun markLyricsChecked(id: Long) =
        trackDao.markLyricsChecked(id, System.currentTimeMillis())

    /**
     * Rewrites a track's tags from a recognition result, fetching cover art when
     * the match carries one. Only fills fields the match actually provides, so a
     * partial result can't blank out good existing metadata.
     */
    suspend fun applyMatch(track: Track, match: MusicMatch): Track = withContext(Dispatchers.IO) {
        val artPath = match.artworkUrl
            ?.let { downloadArtwork(it, track.contentHash) }
            ?: track.artPath

        val updated = track.copy(
            title = match.title.ifBlank { track.title },
            artist = match.artist ?: track.artist,
            album = match.album ?: track.album,
            year = match.releaseYear ?: track.year,
            artPath = artPath
        )
        trackDao.update(updated)
        updated
    }

    /**
     * Writes hand-edited details onto a track.
     *
     * Blank artist, album and year are stored as null rather than skipped:
     * clearing a wrong value has to be possible, which is the whole reason
     * manual editing exists alongside automatic identification. The title
     * falls back to the file name, because a row with no title cannot be shown.
     */
    suspend fun saveDetails(
        track: Track,
        title: String,
        artist: String,
        album: String,
        year: Int?
    ): Track = withContext(Dispatchers.IO) {
        val updated = track.copy(
            title = title.trim().ifBlank {
                File(track.filePath).nameWithoutExtension.ifBlank { track.title }
            },
            artist = artist.trim().takeIf { it.isNotEmpty() },
            album = album.trim().takeIf { it.isNotEmpty() },
            year = year
        )
        trackDao.update(updated)
        updated
    }

    private fun downloadArtwork(url: String, contentHash: String): String? = runCatching {
        val dir = File(appContext.filesDir, "art").apply { if (!exists()) mkdirs() }
        val file = File(dir, "$contentHash.jpg")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            connection.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        file.absolutePath.takeIf { file.length() > 0 }
    }.getOrNull()

    /** Sets only the cover art, leaving every text tag alone. */
    suspend fun updateArtwork(track: Track, url: String): Boolean =
        withContext(Dispatchers.IO) {
            val path = downloadArtwork(url, track.contentHash) ?: return@withContext false
            trackDao.update(track.copy(artPath = path))
            true
        }

    /** Removes the row *and* the audio and artwork files it owns. */
    suspend fun deleteTrack(track: Track) = withContext(Dispatchers.IO) {
        trackDao.deleteById(track.id)
        runCatching { File(track.filePath).delete() }
        track.artPath?.let { path ->
            // Artwork is shared by content hash, so only drop it when nothing points at it.
            val stillUsed = trackDao.allOnce().any { it.artPath == path }
            if (!stillUsed) runCatching { File(path).delete() }
        }
        Unit
    }

    /**
     * Drops rows whose audio file no longer exists — e.g. the user cleared app
     * storage or deleted files by hand through a file manager.
     */
    suspend fun pruneMissingFiles(): Int = withContext(Dispatchers.IO) {
        val missing = trackDao.allOnce().filterNot { File(it.filePath).exists() }
        missing.forEach { trackDao.deleteById(it.id) }
        missing.size
    }

    // ---- Playlists ----

    fun observePlaylists(): Flow<List<PlaylistSummary>> = playlistDao.observeSummaries()

    fun observePlaylist(id: Long): Flow<Playlist?> = playlistDao.observePlaylist(id)

    fun observePlaylistTracks(id: Long): Flow<List<Track>> = playlistDao.observeTracks(id)

    suspend fun playlistTracksOnce(id: Long): List<Track> = playlistDao.tracksOnce(id)

    suspend fun createPlaylist(name: String): Long =
        playlistDao.insertPlaylist(Playlist(name = name.trim()))

    suspend fun deletePlaylist(id: Long) = playlistDao.deletePlaylist(id)

    suspend fun renamePlaylist(id: Long, name: String) =
        playlistDao.renamePlaylist(id, name.trim())

    suspend fun addToPlaylist(playlistId: Long, trackIds: List<Long>) =
        playlistDao.appendTracks(playlistId, trackIds)

    suspend fun removeFromPlaylist(playlistId: Long, trackId: Long) =
        playlistDao.removeEntry(playlistId, trackId)

    suspend fun reorderPlaylist(playlistId: Long, orderedTrackIds: List<Long>) =
        playlistDao.reorder(playlistId, orderedTrackIds)
}
