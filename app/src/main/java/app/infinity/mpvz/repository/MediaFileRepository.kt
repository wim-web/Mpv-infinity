/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.repository

import app.infinity.mpvz.utils.storage.SelectedFolderLibrary
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import app.infinity.mpvz.database.MpvInfinityDatabase
import app.infinity.mpvz.domain.browser.FileSystemItem
import app.infinity.mpvz.domain.browser.PathComponent
import app.infinity.mpvz.domain.media.model.Video
import app.infinity.mpvz.domain.media.model.VideoFolder
import app.infinity.mpvz.domain.playbackstate.repository.PlaybackStateRepository
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.BrowserPreferences
import app.infinity.mpvz.preferences.FoldersPreferences
import app.infinity.mpvz.utils.media.MediaInfoOps
import app.infinity.mpvz.utils.media.VideoResolutionFormatter
import app.infinity.mpvz.utils.storage.FileTypeUtils
import app.infinity.mpvz.utils.storage.FolderViewScanner
import app.infinity.mpvz.utils.storage.MediaScanOptions
import app.infinity.mpvz.utils.storage.TreeViewScanner
import app.infinity.mpvz.utils.storage.VideoScanUtils
import app.infinity.mpvz.utils.storage.mediaPathKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

/**
 * Unified repository for ALL media file operations
 * Consolidates FileSystemRepository, VideoRepository functionality
 *
 * This repository handles:
 * - Video folder discovery (album view)
 * - File system browsing (tree view)
 * - Video file listing
 * - Metadata extraction
 * - Path operations
 * - Storage volume detection
 */
object MediaFileRepository : KoinComponent {
  private const val TAG = "MediaFileRepository"
  private val foldersPreferences: FoldersPreferences by inject()
  private val appearancePreferences: AppearancePreferences by inject()
  private val browserPreferences: BrowserPreferences by inject()
  private val playbackStateRepository: PlaybackStateRepository by inject()
  private val database: MpvInfinityDatabase by inject()

  fun currentScanOptions(includeAudioOverride: Boolean? = null): MediaScanOptions =
    MediaScanOptions(
      videoFolders = emptySet(),
      audioFolders = emptySet(),
      includeNoMediaFolders = foldersPreferences.includeNoMediaFolders.get(),
      hiddenFolderMarkerNames = foldersPreferences.hiddenFolderMarkerNames.get(),
      includeAudio = includeAudioOverride ?: browserPreferences.includeAudioBrowser.get(),
      minimumAudioDurationSeconds = browserPreferences.minimumAudioDurationSeconds.get(),
    )

  private suspend fun getTreeViewNewBadgeParams(): Triple<Boolean, Int, Set<String>> {
    val showNewLabels = appearancePreferences.showUnplayedOldVideoLabel.get()
    val thresholdDays = appearancePreferences.unplayedOldVideoDays.get()
    val playedMediaTitles =
      if (showNewLabels) {
        playbackStateRepository
          .getAllPlaybackStates()
          // Only treat a video as "played" (and drop its NEW badge) once it has
          // actually been watched to the threshold. Threshold 0 keeps the badge.
          .filter { it.hasBeenWatched }
          .mapTo(mutableSetOf()) { it.mediaTitle }
      } else {
        emptySet()
      }

    return Triple(showNewLabels, thresholdDays, playedMediaTitles)
  }

  /**
   * Clears all caches
   * Call this when media library changes are detected or when forcing a hard refresh
   */
  fun clearCache() {
    SelectedFolderLibrary.invalidate()
    Log.d(TAG, "Clearing all caches (FolderViewScanner + TreeViewScanner)")
    FolderViewScanner.clearCache()
    TreeViewScanner.clearCache()
    app.infinity.mpvz.utils.media.MediaSearchEngine.buildIndex(emptyList(), emptyMap())
  }

  /** Invalidates only the album MediaStore snapshot, preserving tree and filesystem indexes. */
  fun invalidateFolderCache() {
    SelectedFolderLibrary.invalidate()
    FolderViewScanner.clearCache()
  }

  fun invalidateTreeCache() {
    SelectedFolderLibrary.invalidate()
    TreeViewScanner.clearCache()
  }

  // =============================================================================
  // FOLDER OPERATIONS (Album View)
  // =============================================================================

  /**
   * Scans all storage volumes to find all folders containing videos
   */
  suspend fun getAllVideoFolders(
    context: Context,
    forceFileSystemCheck: Boolean = false,
    includeAudioOverride: Boolean? = null,
  ): List<VideoFolder> {
    if (forceFileSystemCheck) SelectedFolderLibrary.invalidate()
    val video = SelectedFolderLibrary.folders(context, false)
    val audio = if (includeAudioOverride ?: browserPreferences.includeAudioBrowser.get())
      SelectedFolderLibrary.folders(context, true, browserPreferences.minimumAudioDurationSeconds.get()) else emptyList()
    return (video + audio).groupBy { it.bucketId }.map { (_, folders) ->
      folders.first().copy(videoCount = folders.sumOf { it.videoCount }, totalSize = folders.sumOf { it.totalSize },
        totalDuration = folders.sumOf { it.totalDuration })
    }.sortedBy { it.name.lowercase() }
  }

  suspend fun getAllVideoFoldersFast(
    context: Context,
    onProgress: ((Int) -> Unit)? = null,
    forceFileSystemCheck: Boolean = false,
    includeAudioOverride: Boolean? = null,
  ): List<VideoFolder> = getAllVideoFolders(context, forceFileSystemCheck, includeAudioOverride).also { onProgress?.invoke(it.size) }

  suspend fun getIndexedNoMediaFolders(): List<VideoFolder> = emptyList()

  suspend fun getAllAudioFolders(context: Context, minimumAudioDurationSeconds: Int = 0): List<VideoFolder> =
    SelectedFolderLibrary.folders(context, true, minimumAudioDurationSeconds)

  suspend fun searchAudio(context: Context, query: String, limit: Int = 50): List<Video> =
    SelectedFolderLibrary.videos(context, true).filter { video ->
      val entry = SelectedFolderLibrary.entry(context, video)
      listOf(video.title, video.displayName, entry.artist, entry.album).any { it.contains(query.trim(), ignoreCase = true) }
    }.take(limit)

  fun scanNoMediaFoldersIncrementally(context: Context, forceDiscovery: Boolean = false): Flow<List<VideoFolder>> =
    kotlinx.coroutines.flow.emptyFlow()

  suspend fun enrichVideoFolders(
    context: Context,
    folders: List<VideoFolder>,
    onProgress: ((Int, Int) -> Unit)? = null,
  ): List<VideoFolder> = folders

  // =============================================================================
  // VIDEO FILE OPERATIONS
  // =============================================================================

  /**
   * Gets all videos in a specific folder
   * @param bucketId Folder path
   */
  suspend fun getVideosInFolder(
    context: Context,
    bucketId: String,
    forceFileSystemCheck: Boolean = false,
    includeAudioOverride: Boolean? = null,
  ): List<Video> {
    if (forceFileSystemCheck) SelectedFolderLibrary.invalidate()
    val video = SelectedFolderLibrary.videos(context, false, bucketId)
    val audio = if (includeAudioOverride ?: browserPreferences.includeAudioBrowser.get())
      SelectedFolderLibrary.videos(context, true, bucketId).filter { currentScanOptions(true).includesAudioDuration(it.duration) }
      else emptyList()
    return (video + audio).distinctBy { it.path }.sortedBy { it.displayName.lowercase() }
  }

  /**
   * Gets videos from multiple folders
   * Shows all videos including hidden ones.
   */
  suspend fun getVideosForBuckets(
    context: Context,
    bucketIds: Set<String>,
    includeAudioOverride: Boolean? = null,
  ): List<Video> =
    withContext(Dispatchers.IO) {
      val result = linkedMapOf<String, Video>()
      for (id in bucketIds) {
        runCatching {
          getVideosInFolder(
            context,
            id,
            includeAudioOverride = includeAudioOverride,
          ).forEach { media ->
            val key = mediaPathKey(media.path) ?: media.path
            val existing = result[key]
            if (existing == null || shouldReplaceMedia(existing, media)) {
              result[key] = media
            }
          }
        }
      }
      result.values.toList()
    }

  private fun shouldReplaceMedia(
    existing: Video,
    candidate: Video,
  ): Boolean {
    val extensionIsAudio = FileTypeUtils.isAudioFile(File(candidate.path))
    val existingClassificationIsCorrect = existing.isAudio == extensionIsAudio
    val candidateClassificationIsCorrect = candidate.isAudio == extensionIsAudio
    if (existingClassificationIsCorrect != candidateClassificationIsCorrect) {
      return candidateClassificationIsCorrect
    }
    if ((existing.duration > 0L) != (candidate.duration > 0L)) return candidate.duration > 0L
    if ((existing.size > 0L) != (candidate.size > 0L)) return candidate.size > 0L
    return candidate.dateModified > existing.dateModified
  }

  /**
   * Creates Video objects from a list of files
   */
  suspend fun getVideosFromFiles(
    context: Context,
    files: List<File>,
  ): List<Video> =
    withContext(Dispatchers.IO) {
      files.mapNotNull { file ->
        try {
          val folderPath = file.parent ?: ""
          val folderName = file.parentFile?.name ?: ""
          createVideoFromFile(context, file, folderPath, folderName)
        } catch (e: Exception) {
          Log.w(TAG, "Error creating video from file: ${file.absolutePath}", e)
          null
        }
      }
    }

  /**
   * Creates a Video object from a file with full metadata extraction
   */
  private suspend fun createVideoFromFile(
    context: Context,
    file: File,
    bucketId: String,
    bucketDisplayName: String,
  ): Video {
    val path = file.absolutePath
    val displayName = file.name
    val title = file.nameWithoutExtension
    val dateModified = file.lastModified() / 1000

    val extension = file.extension.lowercase()
    val mimeType = FileTypeUtils.getMimeTypeFromExtension(extension)
    val uri = Uri.fromFile(file)

    // Extract metadata directly (no cache)
    var size = file.length()
    var duration = 0L
    var width = 0
    var height = 0
    var fps = 0f
    var hasEmbeddedSubtitles = false
    var subtitleCodec = ""

    // Extract metadata using MediaInfo
    MediaInfoOps.extractBasicMetadata(context, uri, displayName).onSuccess { metadata ->
      if (metadata.sizeBytes > 0) size = metadata.sizeBytes
      duration = metadata.durationMs
      width = metadata.width
      height = metadata.height
      fps = metadata.fps
      hasEmbeddedSubtitles = metadata.hasEmbeddedSubtitles
      subtitleCodec = metadata.subtitleCodec
    }

    return Video(
      id = path.hashCode().toLong(),
      title = title,
      displayName = displayName,
      path = path,
      uri = uri,
      duration = duration,
      durationFormatted = formatDuration(duration),
      size = size,
      sizeFormatted = formatFileSize(size),
      dateModified = dateModified,
      dateAdded = dateModified,
      mimeType = mimeType,
      bucketId = bucketId,
      bucketDisplayName = bucketDisplayName,
      width = width,
      height = height,
      fps = fps,
      resolution = formatResolutionWithFps(width, height, fps),
      hasEmbeddedSubtitles = hasEmbeddedSubtitles,
      subtitleCodec = subtitleCodec,
    )
  }

  /**
   * Creates a Video object from a file with pre-fetched metadata
   * Use this when metadata has already been batch-extracted
   */
  private fun createVideoFromFileWithMetadata(
    file: File,
    bucketId: String,
    bucketDisplayName: String,
    metadata: MediaInfoOps.VideoMetadata?,
  ): Video {
    val path = file.absolutePath
    val displayName = file.name
    val title = file.nameWithoutExtension
    val dateModified = file.lastModified() / 1000

    val extension = file.extension.lowercase()
    val mimeType = FileTypeUtils.getMimeTypeFromExtension(extension)
    val uri = Uri.fromFile(file)

    // Use pre-fetched metadata
    var size = file.length()
    var duration = 0L
    var width = 0
    var height = 0
    var fps = 0f

    metadata?.let {
      if (it.sizeBytes > 0) size = it.sizeBytes
      duration = it.durationMs
      width = it.width
      height = it.height
      fps = it.fps
    }
    val hasEmbeddedSubtitles = metadata?.hasEmbeddedSubtitles ?: false
    val subtitleCodec = metadata?.subtitleCodec ?: ""

    return Video(
      id = path.hashCode().toLong(),
      title = title,
      displayName = displayName,
      path = path,
      uri = uri,
      duration = duration,
      durationFormatted = formatDuration(duration),
      size = size,
      sizeFormatted = formatFileSize(size),
      dateModified = dateModified,
      dateAdded = dateModified,
      mimeType = mimeType,
      bucketId = bucketId,
      bucketDisplayName = bucketDisplayName,
      width = width,
      height = height,
      fps = fps,
      resolution = formatResolutionWithFps(width, height, fps),
      hasEmbeddedSubtitles = hasEmbeddedSubtitles,
      subtitleCodec = subtitleCodec,
    )
  }

  suspend fun getAllVideos(
    context: Context,
    includeAudioOverride: Boolean? = null,
  ): List<Video> =
    withContext(Dispatchers.IO) {
      val folders = getAllVideoFolders(context, includeAudioOverride = includeAudioOverride)
      val bucketIds = folders.map { it.bucketId }.toSet()
      getVideosForBuckets(context, bucketIds, includeAudioOverride)
    }

  // =============================================================================
  // FILE SYSTEM BROWSING (Tree View)
  // =============================================================================

  /**
   * Gets the default root path for the filesystem browser
   */
  fun getDefaultRootPath(): String = "__STORAGE_ROOTS__"

  fun getPathComponents(path: String): List<PathComponent> =
    if (path.startsWith("content://")) listOf(PathComponent(SelectedFolderLibrary.displayName(path), path)) else emptyList()

  private suspend fun libraryDirectories(context: Context): List<SelectedFolderLibrary.Directory> {
    val video = SelectedFolderLibrary.snapshot(context, false).directories
    val audio = if (browserPreferences.includeAudioBrowser.get()) SelectedFolderLibrary.snapshot(context, true).directories else emptyList()
    return (video + audio).distinctBy { it.uri }
  }

  private suspend fun folderItem(context: Context, folder: SelectedFolderLibrary.Directory): FileSystemItem.Folder {
    val files = getVideosInFolder(context, folder.uri)
    return FileSystemItem.Folder(name = folder.name, path = folder.uri, lastModified = files.maxOfOrNull { it.dateModified } ?: 0,
      videoCount = files.size, totalSize = files.sumOf { it.size }, totalDuration = files.sumOf { it.duration }, hasSubfolders = true)
  }

  suspend fun scanDirectory(context: Context, path: String, showAllFileTypes: Boolean = false,
    useFastCount: Boolean = false, forceFileSystemCheck: Boolean = false): Result<List<FileSystemItem>> = withContext(Dispatchers.IO) {
    if (forceFileSystemCheck) SelectedFolderLibrary.invalidate()
    val folders = libraryDirectories(context).filter { it.parent == path }.map { folderItem(context, it) }
    val videos = getVideosInFolder(context, path).map { video ->
      FileSystemItem.VideoFile(name = video.displayName, path = video.path, lastModified = video.dateModified, video = video)
    }
    Result.success(folders + videos)
  }

  suspend fun getStorageRoots(context: Context, forceFileSystemCheck: Boolean = false): List<FileSystemItem.Folder> {
    if (forceFileSystemCheck) SelectedFolderLibrary.invalidate()
    return libraryDirectories(context).filter { it.parent == null }.map { folderItem(context, it) }
  }

  private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return "0s"

    val seconds = durationMs / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    return when {
      hours > 0 -> String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, secs)
      minutes > 0 -> String.format(Locale.getDefault(), "%d:%02d", minutes, secs)
      else -> "${secs}s"
    }
  }

  private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
    return String.format(
      Locale.getDefault(),
      "%.1f %s",
      bytes / 1024.0.pow(digitGroups.toDouble()),
      units[digitGroups],
    )
  }

  private fun formatResolution(
    width: Int,
    height: Int,
  ): String = VideoResolutionFormatter.format(width, height)

  private fun formatResolutionWithFps(
    width: Int,
    height: Int,
    fps: Float,
  ): String {
    val baseResolution = formatResolution(width, height)
    if (baseResolution == "--" || fps <= 0f) return baseResolution

    // Show only the integer part for frame rates, without rounding
    val fpsFormatted = fps.toInt().toString()

    return "$baseResolution@$fpsFormatted"
  }
}
