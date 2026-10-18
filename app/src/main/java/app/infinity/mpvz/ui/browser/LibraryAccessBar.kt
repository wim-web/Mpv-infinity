/* SPDX-License-Identifier: AGPL-3.0-or-later */

package app.infinity.mpvz.ui.browser

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.infinity.mpvz.R
import app.infinity.mpvz.preferences.AudioPreferences
import app.infinity.mpvz.preferences.FoldersPreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.browser.playlist.PlaylistScreen
import app.infinity.mpvz.ui.preferences.LibraryFoldersScreen
import app.infinity.mpvz.ui.utils.LocalBackStack
import org.koin.compose.koinInject

@Composable
internal fun LibraryAccessBar(showVideoPlaylists: Boolean) {
  val backStack = LocalBackStack.current
  val folders = koinInject<FoldersPreferences>()
  val videos by folders.selectedVideoFolders.collectAsState()
  val audio by folders.selectedAudioFolders.collectAsState()
  Column(modifier = Modifier.statusBarsPadding()) {
    Row(modifier = Modifier.fillMaxWidth()) {
      TextButton(onClick = { backStack.add(LibraryFoldersScreen) }) { Text(stringResource(R.string.library_folders)) }
      if (showVideoPlaylists) {
        TextButton(onClick = { backStack.add(PlaylistScreen) }) { Text(stringResource(R.string.ui_playlists)) }
      }
    }
    if ((if (showVideoPlaylists) videos else audio).isEmpty()) {
      Text(stringResource(R.string.library_select_prompt), modifier = Modifier.padding(horizontal = 12.dp))
    }
    BackgroundPlaybackPermissionNotice()
  }
}

@Composable
private fun BackgroundPlaybackPermissionNotice() {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val preferences = koinInject<AudioPreferences>()
  val videoBackground by preferences.backgroundPlayback.collectAsState()
  val audioBackground by preferences.audioBackgroundPlayback.collectAsState()
  val prompted by preferences.backgroundPermissionExplained.collectAsState()
  var granted by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
  var showExplanation by remember { mutableStateOf(!prompted && !granted) }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    granted = NotificationManagerCompat.from(context).areNotificationsEnabled()
  }
  DisposableEffect(lifecycleOwner, context) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        granted = NotificationManagerCompat.from(context).areNotificationsEnabled()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  if (granted || (!videoBackground && !audioBackground)) return

  fun openSettings() {
    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
      putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    })
  }
  TextButton(onClick = { openSettings() }) {
    Text(stringResource(R.string.library_notification_blocked))
  }
  if (showExplanation) {
    AlertDialog(
      onDismissRequest = {
        preferences.backgroundPermissionExplained.set(true)
        showExplanation = false
      },
      title = { Text(stringResource(R.string.library_notification_title)) },
      text = { Text(stringResource(R.string.library_notification_help)) },
      confirmButton = {
        TextButton(onClick = {
          preferences.backgroundPermissionExplained.set(true)
          showExplanation = false
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
          } else {
            openSettings()
          }
        }) { Text(stringResource(R.string.library_notification_allow)) }
      },
      dismissButton = {
        TextButton(onClick = {
          preferences.backgroundPermissionExplained.set(true)
          showExplanation = false
        }) { Text(stringResource(R.string.library_later)) }
      },
    )
  }
}
