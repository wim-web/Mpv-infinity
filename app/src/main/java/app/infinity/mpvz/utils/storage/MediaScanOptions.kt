/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.utils.storage

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class MediaScanOptions(
  val videoFolders: Set<String> = emptySet(),
  val audioFolders: Set<String> = emptySet(),
  val includeNoMediaFolders: Boolean = false,
  val hiddenFolderMarkerNames: Set<String> = setOf(".nomedia"),
  val includeAudio: Boolean = false,
  val minimumAudioDurationSeconds: Int = 0,
) {
  val selectedRoots: Set<String>
    get() = videoFolders + if (includeAudio) audioFolders else emptySet()

  fun allowsDirectory(file: File): Boolean = SelectedMediaFolders.contains(selectedRoots, file.path)

  fun allowsFile(file: File): Boolean =
    if (FileTypeUtils.isAudioFile(file)) {
      includeAudio && SelectedMediaFolders.contains(audioFolders, file.path)
    } else {
      SelectedMediaFolders.contains(videoFolders, file.path)
    }

  fun selection(audio: Boolean = false): FolderSelection =
    SelectedMediaFolders.selection(if (audio) audioFolders else videoFolders)

  private val foldersKey: String
    get() = "video=" + videoFolders.sorted().joinToString { "${it.length}:$it" } +
      "|audio=" + audioFolders.sorted().joinToString { "${it.length}:$it" }

  val normalizedHiddenFolderMarkerNames: Set<String> =
    hiddenFolderMarkerNames.mapNotNull(::normalizeHiddenMarkerName).toSet()

  val excludeNoMediaFolders: Boolean
    get() = !includeNoMediaFolders

  val cacheKey: String
    get() {
      val markers = normalizedHiddenFolderMarkerNames.sorted().joinToString("") { "${it.length}:$it" }
      return "$foldersKey|hiddenScanV4=$includeNoMediaFolders|markers=$markers|" +
        "includeAudio=$includeAudio|minAudio=$minimumAudioDurationSeconds"
    }

  val rootDiscoveryCacheKey: String
    get() {
      val markers = normalizedHiddenFolderMarkerNames.sorted().joinToString("") { "${it.length}:$it" }
      return "$foldersKey|hiddenRootDiscoveryV2|includeAudio=$includeAudio|markers=$markers"
    }

  fun includesAudioDuration(durationMs: Long): Boolean =
    minimumAudioDurationSeconds == 0 || durationMs >= minimumAudioDurationSeconds * 1000L
}

class NoMediaPathFilter(
  val options: MediaScanOptions,
) {
  private val exclusionCache = ConcurrentHashMap<String, Boolean>()

  fun shouldExcludeDirectory(directory: File?): Boolean {
    if (!options.excludeNoMediaFolders || directory == null) {
      return false
    }

    // App media inside Android/data is often hidden behind .nomedia, but users still
    // expect those video folders to be discoverable in the browser.
    if (isAndroidDataAccessiblePath(directory)) {
      return false
    }

    return hasHiddenMarkerInPath(directory)
  }

  fun shouldExcludeFile(file: File): Boolean =
    !options.allowsFile(file) || shouldExcludeDirectory(file.parentFile)

  private fun hasHiddenMarkerInPath(directory: File): Boolean {
    val path = runCatching { directory.absolutePath }.getOrElse { return false }
    exclusionCache[path]?.let { return it }

    val result =
      runCatching {
        directory.name.startsWith(".") ||
          options.normalizedHiddenFolderMarkerNames.any { File(directory, it).isFile } ||
          directory.parentFile?.let(::hasHiddenMarkerInPath) == true
      }.getOrElse { error ->
        Log.w(TAG, "Failed checking hidden-folder ancestry for $path", error)
        false
      }

    exclusionCache[path] = result
    return result
  }

  private companion object {
    const val TAG = "NoMediaPathFilter"
  }
}

internal fun normalizeHiddenMarkerName(value: String): String? {
  val name = value.trim()
  if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\\' in name) return null
  return if (name.startsWith('.')) name else ".$name"
}
