/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.browser.music

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

object MusicLibraryScanner {

  private const val TAG = "MusicLibraryScanner"
  private val ALBUM_ART_BASE_URI = Uri.parse("content://media/external/audio/albumart")

  suspend fun scanSongs(context: Context): List<MusicSong> = withContext(Dispatchers.IO) {
    val library = app.infinity.mpvz.utils.storage.SelectedFolderLibrary
    library.videos(context, true).map { video ->
      val entry = library.entry(context, video)
      val artist = entry.artist.ifBlank { context.getString(app.infinity.mpvz.R.string.library_unknown_artist) }
      val album = entry.album.ifBlank { video.bucketDisplayName }
      MusicSong(id = video.id, title = video.title, artist = artist, album = album,
        albumId = library.stableId("$artist|$album"), durationMs = video.duration,
        path = video.path, uri = video.uri, dateAdded = video.dateAdded, trackNumber = entry.track, year = entry.year,
        albumArtUri = findArtworkUri(context, video.uri, video.path, 0), size = video.size)
    }
  }

  /** Prefer embedded/sidecar art because the legacy album-art provider is absent on many devices. */
  private fun findArtworkUri(
    context: Context,
    contentUri: Uri,
    path: String,
    albumId: Long,
  ): Uri? {
    val embeddedBytes = runCatching {
      MediaMetadataRetriever().use { retriever ->
        if (path.isNotBlank() && File(path).canRead()) retriever.setDataSource(path)
        else retriever.setDataSource(context, contentUri)
        retriever.embeddedPicture
      }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
    if (embeddedBytes != null) saveArtwork(context, embeddedBytes, "embedded:$path")?.let { return it }

    (if (path.startsWith("content://")) null else app.infinity.mpvz.domain.thumbnail.EmbeddedArtworkResolver.decodeSidecar(path))?.let { bitmap ->
      return try {
        saveArtwork(context, bitmap, "sidecar:$path:${File(path).lastModified()}")
      } finally {
        bitmap.recycle()
      }
    }

    return albumId.takeIf { it > 0 }?.let { ContentUris.withAppendedId(ALBUM_ART_BASE_URI, it) }
  }

  private fun saveArtwork(context: Context, bytes: ByteArray, key: String): Uri? {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    return try {
      saveArtwork(context, bitmap, key)
    } finally {
      bitmap.recycle()
    }
  }

  private fun saveArtwork(context: Context, bitmap: Bitmap, key: String): Uri? {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(key.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
    val file = File(context.filesDir, "music_artwork/$digest.jpg")
    return runCatching {
      file.parentFile?.mkdirs()
      if (!file.isFile || file.length() == 0L) {
        file.outputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) }
      }
      Uri.fromFile(file)
    }.getOrNull()
  }

  suspend fun scanAlbums(context: Context, songs: List<MusicSong>): List<MusicAlbum> = withContext(Dispatchers.IO) {
    if (songs.isNotEmpty()) {
      // Group songs by albumId/album title for exact matching
      songs.groupBy { if (it.albumId > 0) it.albumId else it.album.hashCode().toLong() }
        .map { (albumId, albumSongs) ->
          val firstSong = albumSongs.first()
          MusicAlbum(
            id = albumId,
            title = firstSong.album,
            artist = firstSong.artist,
            songCount = albumSongs.size,
            year = albumSongs.maxOfOrNull { it.year } ?: 0,
            albumArtUri = firstSong.albumArtUri
          )
        }
        .sortedBy { it.title.lowercase() }
    } else {
      emptyList()
    }
  }

  suspend fun scanArtists(context: Context, songs: List<MusicSong>): List<MusicArtist> = withContext(Dispatchers.IO) {
    if (songs.isNotEmpty()) {
      songs.groupBy { it.artist.lowercase().trim() }
        .map { (_, artistSongs) ->
          val firstSong = artistSongs.first()
          val albumCount = artistSongs.map { it.albumId }.distinct().size
          MusicArtist(
            id = firstSong.artist.hashCode().toLong(),
            name = firstSong.artist,
            songCount = artistSongs.size,
            albumCount = albumCount
          )
        }
        .sortedBy { it.name.lowercase() }
    } else {
      emptyList()
    }
  }
}
