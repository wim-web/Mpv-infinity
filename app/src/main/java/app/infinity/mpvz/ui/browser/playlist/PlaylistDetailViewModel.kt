/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.browser.playlist

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.infinity.mpvz.database.entities.PlaylistEntity
import app.infinity.mpvz.database.entities.PlaylistItemEntity
import app.infinity.mpvz.database.repository.PlaylistRepository
import android.net.Uri
import app.infinity.mpvz.domain.media.model.Video
import app.infinity.mpvz.repository.MediaFileRepository
import app.infinity.mpvz.ui.browser.base.BaseBrowserViewModel
import app.infinity.mpvz.ui.player.extractLocalPath
import app.infinity.mpvz.ui.player.resolveUri
import app.infinity.mpvz.utils.media.M3UParser
import app.infinity.mpvz.utils.storage.FileTypeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

data class PlaylistVideoItem(
  val playlistItem: PlaylistItemEntity,
  val video: Video,
)

class PlaylistDetailViewModel(
  application: Application,
  private val playlistId: Int,
) : BaseBrowserViewModel(application),
  KoinComponent {
  private val playlistRepository: PlaylistRepository by inject()
  // Using MediaFileRepository singleton directly

  private val _playlist = MutableStateFlow<PlaylistEntity?>(null)
  val playlist: StateFlow<PlaylistEntity?> = _playlist.asStateFlow()

  private val _videoItems = MutableStateFlow<List<PlaylistVideoItem>>(emptyList())
  val videoItems: StateFlow<List<PlaylistVideoItem>> = _videoItems.asStateFlow()

  private val _categories = MutableStateFlow<List<String>>(emptyList())
  val categories: StateFlow<List<String>> = _categories.asStateFlow()

  // Start as loading to avoid briefly showing "No videos in playlist" before the first DB emission arrives,
  // especially noticeable for very large playlists.
  private val _isLoading = MutableStateFlow(true)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  companion object {
    private const val TAG = "PlaylistDetailViewModel"

    fun factory(
      application: Application,
      playlistId: Int,
    ) = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PlaylistDetailViewModel(application, playlistId) as T
    }
  }

  init {
    viewModelScope.launch {
      app.infinity.mpvz.utils.media.MediaLibraryEvents.changes.collectLatest {
        _videoItems.value = emptyList()
        refreshNow()
      }
    }
    // Observe playlist info
    viewModelScope.launch(Dispatchers.IO) {
      playlistRepository.observePlaylistById(playlistId).collectLatest { playlist ->
        _playlist.value = playlist
      }
    }

    viewModelScope.launch(Dispatchers.IO) {
      playlistRepository.observeDistinctCategories(playlistId).collectLatest { categories ->
        _categories.value = categories
      }
    }

    // Observe playlist items and load video metadata
    viewModelScope.launch(Dispatchers.IO) {
      combine(playlistRepository.observePlaylistById(playlistId), playlistRepository.observePlaylistItems(playlistId)) { playlist, items -> playlist to items }
        .collectLatest { (currentPlaylist, items) ->
        _playlist.value = currentPlaylist
        _isLoading.value = true
        try {
          if (items.isEmpty()) {
            _videoItems.value = emptyList()
          } else {
            // Check if this is an M3U playlist
            val playlist = _playlist.value
            val isM3uPlaylist = playlist?.isM3uPlaylist == true

            if (isM3uPlaylist) {
              val videoItems = buildM3UVideoItems(playlist, items)

              Log.d(TAG, "Loaded ${videoItems.size} M3U playlist items")
              _videoItems.value = videoItems
            } else {
              val videoItems = loadLocalPlaylistItems(items)

              Log.d(TAG, "Loaded ${videoItems.size} items out of ${items.size} playlist items")
              _videoItems.value = videoItems
            }
          }
        } finally {
          _isLoading.value = false
        }
      }
    }
  }

  private suspend fun loadLocalPlaylistItems(items: List<PlaylistItemEntity>): List<PlaylistVideoItem> {
    val videos = MediaFileRepository.getAllVideos(getApplication(), includeAudioOverride = true)
    val byUri = videos.associateBy { it.path }
    val byDocument = videos.associateBy { app.infinity.mpvz.utils.storage.SelectedFolderLibrary.documentKey(it.path) }
    val byLegacyPath = videos.mapNotNull { video ->
      app.infinity.mpvz.utils.storage.SelectedFolderLibrary.legacyPath(video.uri)?.let { it to video }
    }.toMap()
    return items.mapNotNull { item ->
      val video = byUri[item.filePath] ?: byLegacyPath[item.filePath.removePrefix("file://")]
        ?: app.infinity.mpvz.utils.storage.SelectedFolderLibrary.documentKey(item.filePath)?.let { byDocument[it] }
      val playable = video ?: item.filePath.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { url ->
        Video(id = item.id.toLong(), title = item.fileName, displayName = item.fileName, path = url, uri = Uri.parse(url),
          duration = 0, durationFormatted = "", size = 0, sizeFormatted = "", dateModified = item.addedAt, dateAdded = item.addedAt,
          mimeType = if (_playlist.value?.isAudio == true) "audio/*" else "video/*", bucketId = "", bucketDisplayName = "",
          width = 0, height = 0, fps = 0f, resolution = "", isAudio = _playlist.value?.isAudio == true)
      }
      playable?.let { PlaylistVideoItem(item, it) }
    }
  }

  override fun refresh() {
    // Refresh is handled automatically through Flow observation
    viewModelScope.launch(Dispatchers.IO) { refreshNow() }
  }

  /**
   * Refresh playlist items and associated [Video] metadata, awaiting completion.
   *
   * This is useful for UI gestures like pull-to-refresh that need to know when refreshing is done.
   */
  suspend fun refreshNow() {
    try {
      _isLoading.value = true
      // Trigger a refresh by reloading playlist items
      val items = playlistRepository.getPlaylistItems(playlistId)
      val playlist = _playlist.value
      val isM3uPlaylist = playlist?.isM3uPlaylist == true

      if (items.isNotEmpty()) {
        if (isM3uPlaylist) {
          _videoItems.value = buildM3UVideoItems(playlist, items)
        } else {
          val videoItems = loadLocalPlaylistItems(items)
          _videoItems.value = videoItems
        }
      } else {
        _videoItems.value = emptyList()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error refreshing playlist videos", e)
    } finally {
      _isLoading.value = false
    }
  }

  suspend fun updatePlaylistName(newName: String) {
    _playlist.value?.let { playlist ->
      playlistRepository.updatePlaylist(playlist.copy(name = newName))
    }
  }

  suspend fun removeVideoFromPlaylist(video: Video) {
    val items = playlistRepository.getPlaylistItems(playlistId)
    val itemToRemove = items.find { it.filePath == video.path }
    itemToRemove?.let {
      playlistRepository.removeItemFromPlaylist(it)
    }
  }

  suspend fun removeVideosFromPlaylist(videos: List<Video>) {
    val items = playlistRepository.getPlaylistItems(playlistId)
    val videoPaths = videos.map { it.path }.toSet()
    val itemsToRemove = items.filter { it.filePath in videoPaths }
    playlistRepository.removeItemsFromPlaylist(itemsToRemove)
  }

  suspend fun addVideosToPlaylist(videos: List<Video>) {
    playlistRepository.addItemsToPlaylist(playlistId, videos.map { it.path to it.displayName })
  }

  suspend fun updatePlayHistory(
    filePath: String,
    position: Long = 0,
  ) {
    playlistRepository.updatePlayHistory(playlistId, filePath, position)
  }

  suspend fun reorderPlaylistItems(
    fromIndex: Int,
    toIndex: Int,
  ) {
    val currentItems = _videoItems.value.toMutableList()
    if (fromIndex < 0 || fromIndex >= currentItems.size || toIndex < 0 || toIndex >= currentItems.size) {
      return
    }

    // Reorder the list locally first
    val item = currentItems.removeAt(fromIndex)
    currentItems.add(toIndex, item)
    _videoItems.value = currentItems

    // Persist to database
    val newOrder = currentItems.map { it.playlistItem.id }
    playlistRepository.reorderPlaylistItems(playlistId, newOrder)
  }

  suspend fun refreshM3UPlaylist(): Result<Unit> =
    try {
      _isLoading.value = true
      playlistRepository.refreshM3UPlaylist(playlistId)
    } finally {
      _isLoading.value = false
    }

  suspend fun toggleFavorite(itemId: Int) {
    playlistRepository.toggleFavorite(itemId)
  }

  private suspend fun buildM3UVideoItems(
    playlist: PlaylistEntity?,
    items: List<PlaylistItemEntity>,
  ): List<PlaylistVideoItem> =
    withContext(Dispatchers.IO) {
      items.mapNotNull { item ->
        try {
          val mediaReference = M3UParser.normalizeLocalMediaReference(item.filePath)
          val mediaUri = android.net.Uri.parse(mediaReference)
          val isNetwork = mediaUri.scheme?.lowercase() !in setOf(null, "file", "content")

          var resolvedVideo: Video? = null
          val localPath =
            if (!isNetwork) {
              if (mediaUri.scheme.equals("content", true) || mediaUri.scheme.equals("file", true)) {
                mediaUri.extractLocalPath()
              } else {
                mediaReference.substringBefore('|')
              }
            } else {
              null
            }

          if (localPath != null) {
            val file = File(localPath)
            if (file.exists()) {
              val videos = MediaFileRepository.getVideosFromFiles(getApplication(), listOf(file))
              resolvedVideo =
                videos.firstOrNull()?.copy(
                  id = item.id.toLong(),
                )
            }
          }

          val fallbackPath = localPath ?: mediaReference
          val fallbackUri =
            if (localPath != null) {
              android.net.Uri.fromFile(File(localPath))
            } else {
              android.net.Uri.parse(mediaReference)
            }

          val video =
            resolvedVideo ?: Video(
              id = item.id.toLong(),
              title = item.fileName,
              displayName = item.fileName,
              path = fallbackPath,
              uri = fallbackUri,
              duration = 0L,
              durationFormatted = "--",
              size = 0L,
              sizeFormatted = "--",
              dateModified = item.addedAt,
              dateAdded = item.addedAt,
              mimeType = "video/*",
              bucketId = "m3u_playlist_$playlistId",
              bucketDisplayName = playlist?.name ?: "M3U Playlist",
              width = 0,
              height = 0,
              fps = 0f,
              resolution = "--",
            )
          PlaylistVideoItem(item, video)
        } catch (e: Exception) {
          Log.w(TAG, "Failed to create video item for URL: ${item.filePath}", e)
          null
        }
      }
    }
}
