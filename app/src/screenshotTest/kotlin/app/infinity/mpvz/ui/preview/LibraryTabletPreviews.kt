package app.infinity.mpvz.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import app.infinity.mpvz.R
import app.infinity.mpvz.database.entities.PlaylistEntity
import app.infinity.mpvz.di.PreferencesModule
import app.infinity.mpvz.domain.media.model.VideoFolder
import app.infinity.mpvz.domain.thumbnail.ThumbnailRepository
import app.infinity.mpvz.presentation.Screen
import app.infinity.mpvz.ui.browser.*
import app.infinity.mpvz.ui.browser.cards.FolderCard
import app.infinity.mpvz.ui.browser.music.LibraryPlaylistCard
import app.infinity.mpvz.ui.browser.components.BrowserTopBar
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.preferences.LibraryFoldersContent
import app.infinity.mpvz.ui.theme.*
import app.infinity.mpvz.ui.utils.LocalBackStack
import com.android.tools.screenshot.PreviewTest
import org.koin.android.ext.koin.androidContext
import org.koin.compose.KoinApplication
import org.koin.dsl.module

// Logical tablet sizes, not physical pixels. Japanese text and long names are intentional.
@Preview(name = "Tablet portrait", widthDp = 800, heightDp = 1280, locale = "ja", apiLevel = 35)
@Preview(name = "Tablet landscape", widthDp = 1280, heightDp = 800, locale = "ja", apiLevel = 35)
@Preview(name = "Tablet dark", widthDp = 800, heightDp = 1280, locale = "ja", apiLevel = 35, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class TabletPreviews

/** Isolated preferences and sample data: no app database, scanner, or media engine. */
@Composable
private fun PreviewEnvironment(content: @Composable () -> Unit) {
  val context = LocalContext.current
  KoinApplication(application = {
    androidContext(context)
    modules(PreferencesModule, module { single { ThumbnailRepository(context) } })
  }) {
    CompositionLocalProvider(LocalBackStack provides remember { NavBackStack<Screen>(MainScreen) }) {
      MpvInfinityTheme(showCustomThemeBackdrop = false) { content() }
    }
  }
}

@PreviewTest
@TabletPreviews
@Composable
fun SelectedFoldersPreview() = PreviewEnvironment {
  val video = "content://com.android.externalstorage.documents/tree/primary%3AMovies%2F映画"
  val audio = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2Fアルバム"
  LibraryFoldersContent(setOf(video), setOf(audio), mapOf(video to "映画", audio to "アルバム"), {}, {}, { _, _ -> })
}

@PreviewTest
@TabletPreviews
@Composable
fun EmptyLibraryPreview() = PreviewEnvironment {
  LibraryPreviewFrame {
    LibraryFolderEmptyState(Modifier.fillMaxSize(), onChoose = {})
  }
}

@PreviewTest
@TabletPreviews
@Composable
fun FolderListPreview() = PreviewEnvironment {
  LibraryPreviewFrame {
    LazyColumn {
      item { FolderCard(sampleFolder("映画", 12, 18_400_000_000), onClick = {}) }
      item { FolderCard(sampleFolder("旅行の記録 — 京都と奈良", 8, 3_200_000_000), onClick = {}) }
    }
  }
}

@PreviewTest
@TabletPreviews
@Composable
fun PlaylistsPreview() = PreviewEnvironment {
  LibraryPreviewFrame(playlists = true) {
    LazyColumn(contentPadding = PaddingValues(top = 8.dp, end = 32.dp)) {
      item { LibraryPlaylistCard(PlaylistEntity(1, "あとで観る", 0, 0), 6, emptyList(), false, false, 48.dp, {}, {}) }
      item { LibraryPlaylistCard(PlaylistEntity(2, "旅行の記録", 0, 0), 24, emptyList(), false, false, 48.dp, {}, {}) }
    }
  }
}

/** Preview harness around the production toolbar, cards, empty state and navigation bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryPreviewFrame(playlists: Boolean = false, content: @Composable () -> Unit) {
  val selected = MainScreen.MainTab.HOME
  Box(Modifier.fillMaxSize()) {
    Scaffold(
      topBar = {
        app.infinity.mpvz.ui.browser.components.LibraryHeaderColumn {
        BrowserTopBar(
          useVideoLibraryHeader = true,
          title = stringResource(R.string.ui_videos),
          isInSelectionMode = false, selectedCount = 0, totalCount = 0, onCancelSelection = {},
          onSearchClick = {}, onSortClick = if (playlists) null else ({}),
          onSettingsClick = null,
          additionalActions = { LibraryMenu() },
        )
        CompositionLocalProvider(LocalVideoLibraryNavigation provides VideoLibraryNavigation(playlists) {}) { VideoLibraryTabs() }
        if (playlists) Surface(color = MaterialTheme.colorScheme.background) {
          Text("2 Playlists", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        }
      },
      floatingActionButton = {
        if (playlists) app.infinity.mpvz.ui.browser.components.LibraryCreatePlaylistButton(
          onClick = {}, modifier = Modifier.padding(bottom = 72.dp),
        )
      },
    ) { padding -> Box(Modifier.fillMaxSize().padding(padding).padding(bottom = 88.dp)) { content() } }
    ExpressivePillNavigationBar(
      visibleTabs = listOf(MainScreen.MainTab.HOME, MainScreen.MainTab.MUSIC),
      selectedTab = selected, onTabSelected = {}, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
    )
  }
}

private fun sampleFolder(name: String, count: Int, bytes: Long): VideoFolder {
  val documentId = android.net.Uri.encode("primary:Movies/$name")
  val uri = "content://com.android.externalstorage.documents/tree/$documentId/document/$documentId"
  return VideoFolder(uri, name, uri, count, bytes)
}
