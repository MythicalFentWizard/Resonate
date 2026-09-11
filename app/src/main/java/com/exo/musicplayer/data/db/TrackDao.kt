package com.exo.musicplayer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE ASC")
    fun observeByTitle(): Flow<List<Track>>

    @Query("SELECT * FROM tracks ORDER BY addedAt DESC")
    fun observeByRecentlyAdded(): Flow<List<Track>>

    @Query(
        "SELECT * FROM tracks ORDER BY " +
            "CASE WHEN artist IS NULL OR artist = '' THEN 1 ELSE 0 END, " +
            "artist COLLATE NOCASE ASC, title COLLATE NOCASE ASC"
    )
    fun observeByArtist(): Flow<List<Track>>

    /**
     * Grouped by album, and within an album by track number where there is one.
     *
     * The Windows build has a whole Albums view for this. A sixth tab would
     * crowd a phone, and sorting the library people already know reaches the
     * same information, so this is the album view on Android.
     *
     * Untagged albums sort last rather than first: a run of blanks at the top
     * pushes everything that is actually grouped off the screen.
     */
    @Query(
        "SELECT * FROM tracks ORDER BY " +
            "CASE WHEN album IS NULL OR album = '' THEN 1 ELSE 0 END, " +
            "album COLLATE NOCASE ASC, " +
            "CASE WHEN trackNumber IS NULL OR trackNumber = 0 THEN 1 ELSE 0 END, " +
            "trackNumber ASC, title COLLATE NOCASE ASC"
    )
    fun observeByAlbum(): Flow<List<Track>>

    @Query("SELECT * FROM tracks ORDER BY durationMs DESC, title COLLATE NOCASE ASC")
    fun observeByDuration(): Flow<List<Track>>

    @Query(
        "SELECT * FROM tracks ORDER BY playCount DESC, " +
            "lastPlayedAt DESC, title COLLATE NOCASE ASC"
    )
    fun observeByPlayCount(): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE isFavorite = 1 ORDER BY title COLLATE NOCASE ASC")
    fun observeFavorites(): Flow<List<Track>>

    @Query(
        "SELECT * FROM tracks WHERE " +
            "title LIKE '%' || :query || '%' COLLATE NOCASE OR " +
            "artist LIKE '%' || :query || '%' COLLATE NOCASE OR " +
            "album LIKE '%' || :query || '%' COLLATE NOCASE " +
            "ORDER BY title COLLATE NOCASE ASC"
    )
    fun search(query: String): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun findById(id: Long): Track?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<Long>): List<Track>

    @Query("SELECT * FROM tracks WHERE contentHash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): Track?

    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE ASC")
    suspend fun allOnce(): List<Track>

    @Query("SELECT COUNT(*) FROM tracks")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(track: Track): Long

    @Update
    suspend fun update(track: Track)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE tracks SET artCheckedAt = :at WHERE id = :id")
    suspend fun markArtChecked(id: Long, at: Long)

    @Query("UPDATE tracks SET identifiedAt = :at WHERE id = :id")
    suspend fun markIdentified(id: Long, at: Long)

    @Query("UPDATE tracks SET lyricsCheckedAt = :at WHERE id = :id")
    suspend fun markLyricsChecked(id: Long, at: Long)

    @Query("UPDATE tracks SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query(
        "UPDATE tracks SET playCount = playCount + 1, lastPlayedAt = :playedAt WHERE id = :id"
    )
    suspend fun markPlayed(id: Long, playedAt: Long)
}
