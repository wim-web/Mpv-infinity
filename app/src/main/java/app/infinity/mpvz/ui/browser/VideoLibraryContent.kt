package app.infinity.mpvz.ui.browser

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import app.infinity.mpvz.R
import app.infinity.mpvz.ui.browser.folderlist.FolderListScreen
import app.infinity.mpvz.ui.browser.playlist.PlaylistScreen

internal data class VideoLibraryNavigation(val playlists: Boolean, val select: (Boolean) -> Unit)
internal val LocalVideoLibraryNavigation = staticCompositionLocalOf<VideoLibraryNavigation?> { null }

@Composable
internal fun VideoLibraryContent() {
  var playlists by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(playlists) { if (playlists) NavigationBarState.isDualPaneFolderSelected = false }
  CompositionLocalProvider(LocalVideoLibraryNavigation provides VideoLibraryNavigation(playlists) { playlists = it }) {
    if (playlists) PlaylistScreen.Content() else FolderListScreen.Content()
  }
}

@Composable
internal fun VideoLibraryTabs() {
  val navigation = LocalVideoLibraryNavigation.current ?: return
  app.infinity.mpvz.ui.browser.components.LibraryTabs(
    titles = listOf("Folders", "Playlists"), selectedIndex = if (navigation.playlists) 1 else 0,
    onSelect = { navigation.select(it == 1) },
  )
}

@Composable
internal fun VideoLibraryLeadingActions() {
  val backStack = app.infinity.mpvz.ui.utils.LocalBackStack.current
  var expanded by remember { mutableStateOf(false) }
  app.infinity.mpvz.ui.icons.Icon(
    app.infinity.mpvz.ui.icons.Icons.RoundedFilled.VideoLibrary, contentDescription = "video",
    modifier = androidx.compose.ui.Modifier.size(26.dp), tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
  )
  app.infinity.mpvz.ui.browser.components.LibrarySourceSelector(
    title = stringResource(R.string.music_source_local), expanded = expanded, onExpandedChange = { expanded = it },
    icon = { app.infinity.mpvz.ui.icons.Icon(app.infinity.mpvz.ui.icons.Icons.RoundedFilled.Folder, contentDescription = null, modifier = androidx.compose.ui.Modifier.size(20.dp)) },
    modifier = androidx.compose.ui.Modifier.padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
  ) {
    androidx.compose.material3.DropdownMenuItem(
      text = { Text(stringResource(R.string.music_source_local)) },
      leadingIcon = { app.infinity.mpvz.ui.icons.Icon(app.infinity.mpvz.ui.icons.Icons.RoundedFilled.Folder, contentDescription = null) },
      onClick = { expanded = false },
    )
    androidx.compose.material3.DropdownMenuItem(
      text = { Text(stringResource(R.string.library_folders)) },
      leadingIcon = { app.infinity.mpvz.ui.icons.Icon(app.infinity.mpvz.ui.icons.Icons.RoundedFilled.Settings, contentDescription = null) },
      onClick = { expanded = false; backStack.add(app.infinity.mpvz.ui.preferences.LibraryFoldersScreen) },
    )
  }
}
