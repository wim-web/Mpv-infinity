/* SPDX-License-Identifier: AGPL-3.0-or-later */

package app.infinity.mpvz.ui.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.R
import app.infinity.mpvz.preferences.FoldersPreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.presentation.Screen
import app.infinity.mpvz.repository.MediaFileRepository
import app.infinity.mpvz.ui.browser.dialogs.FolderPickerDialog
import app.infinity.mpvz.ui.utils.LocalBackStack
import app.infinity.mpvz.ui.utils.popSafely
import app.infinity.mpvz.utils.media.MediaLibraryEvents
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import java.io.File

@Serializable
object LibraryFoldersScreen : Screen {
  @Composable
  override fun Content() {
    val preferences = koinInject<FoldersPreferences>()
    val videos by preferences.selectedVideoFolders.collectAsState()
    val audio by preferences.selectedAudioFolders.collectAsState()
    val backStack = LocalBackStack.current
    var pickingAudio by remember { mutableStateOf<Boolean?>(null) }

    fun update(isAudio: Boolean, paths: Set<String>) {
      val preference = if (isAudio) preferences.selectedAudioFolders else preferences.selectedVideoFolders
      preference.set(paths)
      MediaFileRepository.clearCache()
      MediaLibraryEvents.notifyChanged()
    }

    Scaffold { padding ->
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item {
          TextButton(onClick = { backStack.popSafely() }) { Text(stringResource(R.string.library_back)) }
          Text(stringResource(R.string.library_folders), style = MaterialTheme.typography.headlineSmall)
          Text(stringResource(R.string.library_folder_help))
        }
        listOf(false, true).forEach { isAudio ->
          val paths = if (isAudio) audio else videos
          item {
            Column {
              Text(stringResource(if (isAudio) R.string.library_audio_folders else R.string.library_video_folders),
                style = MaterialTheme.typography.titleLarge)
              if (paths.isEmpty()) Text(stringResource(R.string.library_select_prompt))
              Button(onClick = { pickingAudio = isAudio }) { Text(stringResource(R.string.library_add_folder)) }
            }
          }
          items(paths.sorted(), key = { "$isAudio:$it" }) { path ->
            Row(modifier = Modifier.fillMaxWidth()) {
              Text(path, modifier = Modifier.weight(1f))
              TextButton(onClick = { update(isAudio, paths - path) }) {
                Text(stringResource(R.string.library_remove))
              }
            }
          }
        }
      }
    }
    pickingAudio?.let { isAudio ->
      FolderPickerDialog(
        isOpen = true,
        currentPath = "",
        onDismiss = { pickingAudio = null },
        onFolderSelected = { path ->
          val normalized = runCatching { File(path).canonicalPath }.getOrDefault(path)
          update(isAudio, (if (isAudio) audio else videos) + normalized)
          pickingAudio = null
        },
      )
    }
  }
}
