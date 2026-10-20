/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.browser.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.infinity.mpvz.R
import app.infinity.mpvz.domain.thumbnail.EmbeddedArtworkResolver
import app.infinity.mpvz.preferences.PlayerPreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.browser.NavigationBarState
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.DeclaredPlaybackMediaKind
import app.infinity.mpvz.ui.player.MediaPlaybackService
import app.infinity.mpvz.ui.player.InAppVideoPlayback
import app.infinity.mpvz.ui.player.PlaybackPhase
import app.infinity.mpvz.ui.player.PlaybackSession
import app.infinity.mpvz.ui.player.PlayerActivity
import app.infinity.mpvz.ui.player.TrackNode
import app.infinity.mpvz.ui.player.declaredMediaKind
import app.infinity.mpvz.ui.player.toObject
import app.infinity.mpvz.ui.utils.LocalBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject

@Composable
fun MiniPlayer(modifier: Modifier = Modifier) {
  val isServiceRunning = MediaPlaybackService.isForegroundActive()
  val context = LocalContext.current
  val sessionState by PlaybackSession.state.collectAsStateWithLifecycle()
  val playerPreferences: PlayerPreferences = koinInject()
  val enableVideoMiniPlayer by playerPreferences.enableVideoMiniPlayer.collectAsState()
  val inAppPlaybackActive by InAppVideoPlayback.active.collectAsStateWithLifecycle()
  val videoMiniPlayerEnabled = enableVideoMiniPlayer || inAppPlaybackActive

  val backstack = LocalBackStack.current
  val currentScreen = backstack.lastOrNull()
  val isSettingsScreen = currentScreen != null &&
    (currentScreen.javaClass.name.startsWith("app.infinity.mpvz.ui.preferences") ||
     currentScreen.javaClass.name.startsWith("app.infinity.mpvz.ui.editor"))

  val currentItem = sessionState.currentItem
  val trackListNode by PlaybackSession.propNode["track-list"].collectAsStateWithLifecycle()
  val json: Json = koinInject()

  val tracks = remember(trackListNode) { trackListNode?.toObject<List<TrackNode>>(json).orEmpty() }
  val hasRealVideo = tracks.any { it.isVideo && !it.isAlbumArtwork }
  val hasAlbumArt = tracks.any { it.isAlbumArtwork }

  val isAudioOnlyItem =
    when (currentItem?.declaredMediaKind() ?: DeclaredPlaybackMediaKind.UNKNOWN) {
      DeclaredPlaybackMediaKind.AUDIO -> true
      DeclaredPlaybackMediaKind.VIDEO -> false
      DeclaredPlaybackMediaKind.UNKNOWN -> !hasRealVideo && (hasAlbumArt || tracks.any { it.isAudio })
    }

  val isMiniPlayerAllowed = isAudioOnlyItem || videoMiniPlayerEnabled

  val isMediaActive = (isServiceRunning || inAppPlaybackActive) && currentItem != null &&
    !isSettingsScreen &&
    isMiniPlayerAllowed &&
    sessionState.phase != PlaybackPhase.IDLE &&
    sessionState.phase != PlaybackPhase.STOPPING &&
    sessionState.phase != PlaybackPhase.UNINITIALIZED &&
    sessionState.phase != PlaybackPhase.ERROR

  // The root layout reserves the measured player height, including during selection.
  SideEffect {
    NavigationBarState.isMiniPlayerVisible = isMediaActive
  }

  AnimatedVisibility(
    visible = isMediaActive,
    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
  ) {
    Box(modifier) {
      MiniPlayerContent(
        context = context,
        isAudioOnlyItem = isAudioOnlyItem,
        hasRealVideo = hasRealVideo,
      )
    }
  }
}

@Composable
private fun MiniPlayerContent(
  context: Context,
  isAudioOnlyItem: Boolean,
  hasRealVideo: Boolean,
) {
  val sessionState by PlaybackSession.state.collectAsStateWithLifecycle()
  val queueState by PlaybackSession.queue.collectAsStateWithLifecycle()
  val currentItem = sessionState.currentItem
  val paused by PlaybackSession.propBoolean["pause"].collectAsStateWithLifecycle()
  val rawMediaTitle by PlaybackSession.propString["media-title"].collectAsStateWithLifecycle()
  val duration by PlaybackSession.propInt["duration"].collectAsStateWithLifecycle()
  val positionState = PlaybackSession.propInt["time-pos"].collectAsStateWithLifecycle()
  val seekable by PlaybackSession.propBoolean["seekable"].collectAsStateWithLifecycle()
  val preferences: PlayerPreferences = koinInject()

  val isPlaying = paused == false
  val title =
    queueState.currentItem?.title?.takeIf { queueState.isExplicitQueue && it.isNotBlank() }
      ?: rawMediaTitle?.takeIf { it.isNotBlank() }
      ?: currentItem?.title?.takeIf { it.isNotBlank() }
      ?: "Media Track"

  // Visibility already checks playback ownership. Keep the surface alive through the exit
  // animation so opening fullscreen can take ownership before the mini player detaches it.
  val isVideoMode =
    (hasRealVideo || currentItem?.declaredMediaKind() == DeclaredPlaybackMediaKind.VIDEO) && !isAudioOnlyItem

  DisposableEffect(isVideoMode) {
    if (isVideoMode) {
      PlaybackSession.setPropertyBoolean("sub-visibility", false)
    }
    onDispose {
      PlaybackSession.setPropertyBoolean("sub-visibility", true)
    }
  }

  val coverArtPath =
    currentItem?.originalUri?.takeIf { it.isNotBlank() }
      ?: currentItem?.playableUri?.takeIf { it.isNotBlank() }
  val coverArt =
    rememberMiniPlayerCoverArt(
      pathOrUri = if (isAudioOnlyItem) coverArtPath else null,
      artworkUri = if (isAudioOnlyItem) currentItem?.artworkUri else null,
    )

  val launchPlayer = remember(context, isAudioOnlyItem) {
    {
      val intent = Intent(context, PlayerActivity::class.java).apply {
        action = MediaPlaybackService.ACTION_OPEN_PLAYER
        putExtra("is_audio", isAudioOnlyItem)
        putExtra("internal_launch", true)
        putExtra("launch_source", "mini_player")
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
      PlayerActivity.launch(context, intent)
      if (context is Activity) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          context.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, R.anim.slide_in_up, 0)
        } else {
          @Suppress("DEPRECATION")
          context.overridePendingTransition(R.anim.slide_in_up, 0)
        }
      }
    }
  }

  val dismissPlayer: () -> Unit = remember(context) {
    {
      if (InAppVideoPlayback.active.value) {
        InAppVideoPlayback.stop()
      } else context.startService(
        Intent(context, MediaPlaybackService::class.java).setAction(
          MediaPlaybackService.ACTION_NOTIFICATION_STOP,
        ),
      )
    }
  }

  MiniPlayerBar(
    playbackGeneration = sessionState.generation,
    title = title,
    playing = isPlaying,
    positionSeconds = (positionState.value ?: 0).toFloat(),
    durationSeconds = (duration ?: 0).toFloat(),
    seekable = seekable == true,
    repeatMode = queueState.repeatMode,
    repeatEnabled = currentItem?.audiobook == null,
    onSeek = { seconds ->
      PlaybackSession.commandForGeneration(sessionState.generation, "seek", seconds.toString(), "absolute+exact")
    },
    onRepeat = { mode ->
      preferences.repeatMode.set(mode)
      PlaybackSession.setRepeatMode(mode)
    },
    onPlayPause = {
      if (InAppVideoPlayback.active.value) {
        InAppVideoPlayback.setPlaying(!isPlaying)
      } else context.startService(
        Intent(context, MediaPlaybackService::class.java).setAction(MediaPlaybackService.ACTION_NOTIFICATION_PLAY_PAUSE),
      )
    },
    onNext = if (queueState.hasItems && PlaybackSession.hasNext()) {
      {
        if (InAppVideoPlayback.active.value) {
          InAppVideoPlayback.playNext()
        } else context.startService(
          Intent(context, MediaPlaybackService::class.java).setAction(MediaPlaybackService.ACTION_NOTIFICATION_NEXT),
        )
      }
    } else null,
    onOpen = launchPlayer,
    onClose = dismissPlayer,
    artwork = {
      if (isVideoMode) {
        AndroidView(
          modifier = Modifier.fillMaxSize(),
          factory = { viewContext ->
            SurfaceView(viewContext).apply {
              setZOrderMediaOverlay(true)
              holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                  val attached = PlaybackSession.bindSurface(
                    surface = holder.surface,
                    owner = this@apply,
                    ownerIsActive = { MediaPlaybackService.isForegroundActive() || InAppVideoPlayback.active.value },
                  )
                  if (attached) PlaybackSession.setPropertyBoolean("sub-visibility", false)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                  if (holder.surface.isValid) PlaybackSession.resizeSurface(width, height, owner = this@apply)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                  if (PlaybackSession.unbindSurface(this@apply)) PlaybackSession.setPropertyBoolean("sub-visibility", true)
                }
              })
            }
          },
          update = { it.keepScreenOn = isPlaying },
        )
      } else {
        val artworkImageBitmap = remember(coverArt) { coverArt?.asImageBitmap() }
        if (artworkImageBitmap != null) {
          Image(bitmap = artworkImageBitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
          Icon(Icons.RoundedFilled.Audiotrack, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
      }
    },
  )
}

/**
 * Extracts embedded album art for the current track so the mini player can show a
 * square cover instead of a bare icon. Returns null when no artwork is available.
 */
@Composable
private fun rememberMiniPlayerCoverArt(
  pathOrUri: String?,
  artworkUri: String?,
): Bitmap? {
  val context = LocalContext.current
  var bitmap by remember { mutableStateOf<Bitmap?>(null) }
  LaunchedEffect(pathOrUri, artworkUri) {
    if (pathOrUri.isNullOrBlank()) {
      bitmap = null
      return@LaunchedEffect
    }
    withContext(Dispatchers.IO) {
      runCatching {
        EmbeddedArtworkResolver.decodeArtworkUri(context, artworkUri)?.let { return@runCatching it }
        val cleanPath =
          when {
            pathOrUri.startsWith("file://", ignoreCase = true) -> Uri.parse(pathOrUri).path
            pathOrUri.startsWith("content://", ignoreCase = true) -> null
            else -> pathOrUri
          }
        val retriever = MediaMetadataRetriever()
        try {
          if (cleanPath != null) {
            retriever.setDataSource(cleanPath)
          } else {
            retriever.setDataSource(context, Uri.parse(pathOrUri))
          }
          EmbeddedArtworkResolver.decodeEmbeddedArtwork(cleanPath, retriever)
        } finally {
          runCatching { retriever.release() }
        }
      }.onSuccess { loaded ->
        bitmap = loaded
      }.onFailure {
        bitmap = null
      }
    }
  }
  return bitmap
}
