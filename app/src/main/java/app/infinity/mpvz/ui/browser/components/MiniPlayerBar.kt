/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.browser.components

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.R
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.RepeatMode

/** The browser and its FABs occupy a separate, measured area above the player. */
@Composable
internal fun BrowserPlaybackLayout(
  playerVisible: Boolean,
  player: @Composable () -> Unit,
  content: @Composable () -> Unit,
) {
  Column(Modifier.fillMaxSize()) {
    Box(
      Modifier.weight(1f).then(
        if (playerVisible) Modifier.consumeWindowInsets(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)) else Modifier,
      ),
    ) {
      content()
    }
    player()
  }
}

/** Shared video/audio controls. A horizontal drag scrubs; only the close button stops playback. */
@Composable
internal fun MiniPlayerBar(
  playbackGeneration: Long,
  title: String,
  playing: Boolean,
  positionSeconds: Float,
  durationSeconds: Float,
  seekable: Boolean,
  repeatMode: RepeatMode,
  repeatEnabled: Boolean,
  onSeek: (Float) -> Unit,
  onRepeat: (RepeatMode) -> Unit,
  onPlayPause: () -> Unit,
  onNext: (() -> Unit)?,
  onOpen: () -> Unit,
  onClose: () -> Unit,
  artwork: @Composable () -> Unit,
) {
  val canSeek = seekable && durationSeconds > 0f && durationSeconds.isFinite()
  var dragPosition by remember(playbackGeneration, durationSeconds, canSeek) { mutableStateOf<Float?>(null) }
  val latestSeek by rememberUpdatedState(onSeek)
  val shownPosition = (dragPosition ?: positionSeconds).coerceIn(0f, durationSeconds.coerceAtLeast(0f))
  val fraction = if (durationSeconds > 0f) (shownPosition / durationSeconds).coerceIn(0f, 1f) else 0f
  // Keep progress visibly blue even when the app uses a monochrome theme.
  val darkSurface = MaterialTheme.colorScheme.surfaceContainerHigh.luminance() < 0.5f
  val progressColor = if (darkSurface) Color(0xFF83BCFF) else Color(0xFF0066CC)
  val progressFill = when {
    darkSurface && dragPosition != null -> Color(0xFF235783)
    darkSurface -> Color(0xFF173E65)
    dragPosition != null -> Color(0xFFAACFFF)
    else -> Color(0xFFCAE2FF)
  }
  val seekLabel = stringResource(R.string.mini_player_seek)
  val elapsed = DateUtils.formatElapsedTime(shownPosition.toLong())
  val total = if (durationSeconds > 0f) DateUtils.formatElapsedTime(durationSeconds.toLong()) else "--:--"

  val seekHint = stringResource(if (canSeek) R.string.mini_player_seek_hint else R.string.mini_player_no_seek)
  val controls: @Composable () -> Unit = {
    MiniPlayerRepeatButton(repeatMode, repeatEnabled, onRepeat)
    IconButton(onClick = onPlayPause) {
      Icon(
        if (playing) Icons.RoundedFilled.Pause else Icons.RoundedFilled.PlayArrow,
        stringResource(if (playing) R.string.mini_player_pause else R.string.ui_play),
      )
    }
    // Keep the repeat button in the same place while cycling through all three modes.
    IconButton(onClick = { onNext?.invoke() }, enabled = onNext != null) {
      Icon(Icons.RoundedFilled.SkipNext, stringResource(R.string.mini_player_next))
    }
    IconButton(onClick = onOpen) {
      Icon(Icons.RoundedFilled.Fullscreen, stringResource(R.string.in_app_player_fullscreen))
    }
  }

  Surface(
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    tonalElevation = 8.dp,
    shadowElevation = 4.dp,
  ) {
    BoxWithConstraints {
      // Keep short landscape viewports usable; narrow screens get two rows and full touch targets.
      val wide = maxWidth >= 560.dp
      Column(
        Modifier
          .fillMaxWidth()
          .pointerInput(playbackGeneration, canSeek, durationSeconds) {
            if (!canSeek) return@pointerInput
            detectHorizontalDragGestures(
              onDragStart = { offset ->
                dragPosition = (offset.x / size.width.coerceAtLeast(1) * durationSeconds).coerceIn(0f, durationSeconds)
              },
              onHorizontalDrag = { change, _ ->
                change.consume()
                dragPosition = (change.position.x / size.width.coerceAtLeast(1) * durationSeconds).coerceIn(0f, durationSeconds)
              },
              onDragEnd = {
                dragPosition?.let { latestSeek(it) }
                dragPosition = null
              },
              onDragCancel = { dragPosition = null },
            )
          }
          .drawBehind {
            val x = size.width * fraction
            drawRect(progressFill, size = Size(x, size.height))
            val edgeHeight = 4.dp.toPx().coerceAtMost(size.height)
            drawRect(progressColor, topLeft = Offset(0f, size.height - edgeHeight), size = Size(x, edgeHeight))
            if (canSeek) {
              drawLine(progressColor, Offset(x, 0f), Offset(x, size.height), 3.dp.toPx())
            }
          }
          .padding(horizontal = 8.dp, vertical = 4.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            Modifier.size(width = 64.dp, height = 48.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
          ) { artwork() }
          Column(
            Modifier.weight(1f).padding(horizontal = 8.dp).semantics {
              contentDescription = seekLabel
              stateDescription = "$elapsed / $total"
              if (canSeek) {
                progressBarRangeInfo = ProgressBarRangeInfo(shownPosition, 0f..durationSeconds)
                setProgress { requested ->
                  if (requested.isFinite()) {
                    latestSeek(requested.coerceIn(0f, durationSeconds))
                    true
                  } else false
                }
              }
            },
          ) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(
              if (wide) "$elapsed / $total · $seekHint" else "$elapsed / $total",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          if (wide) controls()
          IconButton(onClick = onClose) {
            Icon(Icons.RoundedFilled.Close, stringResource(R.string.mini_player_stop))
          }
        }
        if (!wide) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text(
            seekHint,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
          )
          controls()
        }
      }
    }
  }
}

@Composable
private fun MiniPlayerRepeatButton(
  mode: RepeatMode,
  enabled: Boolean,
  onRepeat: (RepeatMode) -> Unit,
) {
  val label = stringResource(mode.labelResource())
  val description = stringResource(R.string.btn_label_repeat_mode)
  val color = if (enabled && mode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else LocalContentColor.current
  IconButton(
    onClick = {
      onRepeat(
        when (mode) {
          RepeatMode.OFF -> RepeatMode.ONE
          RepeatMode.ONE -> RepeatMode.ALL
          RepeatMode.ALL -> RepeatMode.OFF
        },
      )
    },
    enabled = enabled,
    modifier = Modifier.widthIn(min = 64.dp).semantics {
      contentDescription = description
      stateDescription = label
    },
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Icon(
        when (mode) {
          RepeatMode.OFF -> Icons.RoundedFilled.Repeat
          RepeatMode.ONE -> Icons.RoundedFilled.RepeatOne
          RepeatMode.ALL -> Icons.RoundedFilled.RepeatOn
        },
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(20.dp),
      )
      Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
  }
}

private fun RepeatMode.labelResource(): Int =
  when (this) {
    RepeatMode.OFF -> R.string.mini_player_repeat_off
    RepeatMode.ONE -> R.string.mini_player_repeat_one
    RepeatMode.ALL -> R.string.mini_player_repeat_all
  }
