/* SPDX-License-Identifier: AGPL-3.0-or-later */

package app.infinity.mpvz.utils.storage

import java.io.File

/** A deny-by-default library scope. SQL uses bound, exact directory prefixes (including the trailing separator). */
data class FolderSelection(val sql: String, val args: Array<String>)

object SelectedMediaFolders {
  fun contains(roots: Set<String>, path: String): Boolean {
    val candidate = runCatching { File(path).canonicalPath }.getOrNull() ?: return false
    return roots.any { root ->
      val normalized = runCatching { File(root).canonicalPath }.getOrNull() ?: return@any false
      candidate == normalized || candidate.startsWith(normalized.trimEnd('/') + "/")
    }
  }

  fun selection(roots: Set<String>): FolderSelection {
    if (roots.isEmpty()) return FolderSelection("0", emptyArray())
    val paths = roots.sorted().map { File(it).absolutePath.trimEnd('/') + "/" }
    return FolderSelection(
      paths.joinToString(" OR ", "(", ")") { "substr(_data, 1, ?) = ? COLLATE BINARY" },
      paths.flatMap { listOf(it.codePointCount(0, it.length).toString(), it) }.toTypedArray(),
    )
  }
}
