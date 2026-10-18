package app.infinity.mpvz.ui.browser

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import app.infinity.mpvz.repository.MediaFileRepository
import app.infinity.mpvz.utils.media.MediaLibraryEvents

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.infinity.mpvz.R
import app.infinity.mpvz.preferences.FoldersPreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.browser.playlist.PlaylistScreen
import app.infinity.mpvz.ui.browser.states.EmptyState
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.preferences.LibraryFoldersScreen
import app.infinity.mpvz.ui.utils.LocalBackStack
import app.infinity.mpvz.utils.storage.SelectedFolderLibrary
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.PermissionStatus
import org.koin.compose.koinInject

/** The same system picker is used from the empty library and folder settings. */
@Composable
fun rememberLibraryFolderPicker(): (Boolean) -> Unit {
  val context = LocalContext.current
  val preferences = koinInject<FoldersPreferences>()
  var audio by rememberSaveable { mutableStateOf(false) }
  var failed by remember { mutableStateOf(false) }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null) {
      val existing = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
      runCatching {
        try {
          context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) {
          context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (!existing) preferences.libraryOwnedTreeGrants.set(preferences.libraryOwnedTreeGrants.get() + uri.toString())
        val selected = if (audio) preferences.selectedAudioTrees else preferences.selectedVideoTrees
        selected.set(selected.get() + uri.toString())
        MediaFileRepository.clearCache()
        MediaLibraryEvents.notifyChanged()
      }.onFailure { failed = true }
    }
  }
  if (failed) AlertDialog(onDismissRequest = { failed = false },
    text = { Text(stringResource(R.string.library_folder_unavailable)) },
    confirmButton = { TextButton(onClick = { failed = false }) { Text(stringResource(R.string.generic_ok)) } })
  return { audioOnly -> audio = audioOnly; launcher.launch(null) }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberLibraryPermissionState(audioOnly: Boolean = false, onPermissionGranted: () -> Unit): PermissionState {
  val context = LocalContext.current
  val picker by rememberUpdatedState(rememberLibraryFolderPicker())
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val prefs = koinInject<FoldersPreferences>()
  val trees by (if (audioOnly) prefs.selectedAudioTrees else prefs.selectedVideoTrees).collectAsState()
  var revision by remember { mutableIntStateOf(0) }
  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }
  val granted = remember(trees, revision) { SelectedFolderLibrary.grantedTrees(context, audioOnly).isNotEmpty() }
  val latestCallback by rememberUpdatedState(onPermissionGranted)
  LaunchedEffect(granted, trees, revision) { if (granted) latestCallback() }
  return remember(granted, audioOnly) {
    object : PermissionState {
      override val permission = "selected_document_trees"
      override val status = if (granted) PermissionStatus.Granted else PermissionStatus.Denied(false)
      override fun launchPermissionRequest() { picker(audioOnly) }
    }
  }
}

@Composable
fun LibraryFolderEmptyState(modifier: Modifier = Modifier, onChoose: () -> Unit) {
  EmptyState(icon = Icons.RoundedFilled.FolderOpen, title = stringResource(R.string.library_select_prompt),
    message = "", modifier = modifier, action = {
      FilledTonalButton(onClick = onChoose) { Text(stringResource(R.string.library_add_folder)) }
    })
}

@Composable
fun LibraryMenu(onPlaylists: (() -> Unit)? = null) {
  val backStack = LocalBackStack.current
  var expanded by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { expanded = true }) {
      Icon(Icons.RoundedFilled.MoreVert, contentDescription = stringResource(R.string.library_menu))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      DropdownMenuItem(text = { Text(stringResource(R.string.library_folders)) },
        leadingIcon = { Icon(Icons.RoundedFilled.FolderOpen, contentDescription = null) },
        onClick = { expanded = false; backStack.add(LibraryFoldersScreen) })
      DropdownMenuItem(text = { Text(stringResource(R.string.ui_playlists)) },
        leadingIcon = { Icon(Icons.RoundedFilled.PlaylistPlay, contentDescription = null) },
        onClick = { expanded = false; if (onPlaylists != null) onPlaylists() else backStack.add(PlaylistScreen) })
      DropdownMenuItem(text = { Text(stringResource(R.string.ui_settings)) },
        leadingIcon = { Icon(Icons.RoundedFilled.Settings, contentDescription = null) },
        onClick = { expanded = false; backStack.add(app.infinity.mpvz.ui.preferences.PreferencesScreen) })
    }
  }
}
