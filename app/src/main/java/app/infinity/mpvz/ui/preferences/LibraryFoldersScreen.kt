package app.infinity.mpvz.ui.preferences

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.R
import app.infinity.mpvz.preferences.FoldersPreferences
import app.infinity.mpvz.preferences.preference.PreferenceStore
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.presentation.Screen
import app.infinity.mpvz.repository.MediaFileRepository
import app.infinity.mpvz.ui.browser.components.BrowserTopBar
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.theme.AppShapeScale
import app.infinity.mpvz.ui.utils.LocalBackStack
import app.infinity.mpvz.ui.utils.popSafely
import app.infinity.mpvz.utils.media.MediaLibraryEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
object LibraryFoldersScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val prefs = koinInject<FoldersPreferences>()
    val store = koinInject<PreferenceStore>()
    val video by prefs.selectedVideoTrees.collectAsState()
    val audio by prefs.selectedAudioTrees.collectAsState()
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val labels by produceState<Map<String, String>>(emptyMap(), video, audio) {
      value = withContext(Dispatchers.IO) {
        (video + audio).associateWith { app.infinity.mpvz.utils.media.openPersistedTreeDocument(context, it)?.name.orEmpty() }
      }
    }
    val picker = app.infinity.mpvz.ui.browser.rememberLibraryFolderPicker()
    fun changed() { MediaFileRepository.clearCache(); MediaLibraryEvents.notifyChanged() }
    fun remove(isAudio: Boolean, uri: String) {
      val preference = if (isAudio) prefs.selectedAudioTrees else prefs.selectedVideoTrees
      preference.set(preference.get() - uri)
      val used = prefs.selectedVideoTrees.get() + prefs.selectedAudioTrees.get()
      val otherReferences = store.getAll().filterKeys { it !in setOf("selected_video_trees", "selected_audio_trees", "library_owned_tree_grants") }
        .values.any { it == uri || (it is Set<*> && uri in it) }
      if (uri !in used && uri in prefs.libraryOwnedTreeGrants.get() && !otherReferences) {
        runCatching {
          val grant = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri.toString() == uri }
          val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (grant?.isWritePermission == true) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
          context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), flags)
        }
        prefs.libraryOwnedTreeGrants.set(prefs.libraryOwnedTreeGrants.get() - uri)
      }
      changed()
    }
    LibraryFoldersContent(video, audio, labels, onBack = { backStack.popSafely() }, onAdd = picker, onRemove = ::remove)
  }
}

/** Shared by the screen and device-free previews; permission handling stays in the screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryFoldersContent(
  video: Set<String>,
  audio: Set<String>,
  labels: Map<String, String>,
  onBack: () -> Unit,
  onAdd: (Boolean) -> Unit,
  onRemove: (Boolean, String) -> Unit,
) {
  Scaffold(topBar = {
    BrowserTopBar(title = stringResource(R.string.library_folders), isInSelectionMode = false,
      selectedCount = 0, totalCount = 0, onCancelSelection = {}, onBackClick = onBack)
  }) { padding ->
    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(24.dp)) {
      listOf(false, true).forEach { isAudio ->
        item {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (isAudio) R.string.library_audio_folders else R.string.library_video_folders),
              style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(horizontal = 16.dp))
            Surface(shape = AppShapeScale.large, color = MaterialTheme.colorScheme.surfaceContainer) {
              Column {
                (if (isAudio) audio else video).sorted().forEach { uri ->
                  val label = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(uri)).substringAfter(':').ifBlank { DocumentsContract.getTreeDocumentId(Uri.parse(uri)) } }.getOrDefault(uri)
                  ListItem(headlineContent = { Text(labels[uri]?.takeIf { it.isNotBlank() } ?: label.substringAfterLast('/')) },
                    supportingContent = if ('/' in label) ({ Text(label) }) else null,
                    leadingContent = { Icon(Icons.RoundedFilled.Folder, contentDescription = null) },
                    trailingContent = {
                      IconButton(onClick = { onRemove(isAudio, uri) }) {
                        Icon(Icons.RoundedFilled.Close, contentDescription = stringResource(R.string.library_remove))
                      }
                    }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
                }
                TextButton(onClick = { onAdd(isAudio) }, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                  Icon(Icons.RoundedFilled.Add, contentDescription = null)
                  Spacer(Modifier.width(8.dp))
                  Text(stringResource(R.string.library_add_folder))
                }
              }
            }
          }
        }
      }
    }
  }
}
