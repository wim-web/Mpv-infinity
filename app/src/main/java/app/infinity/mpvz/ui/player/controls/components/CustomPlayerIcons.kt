/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player.controls.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import app.infinity.mpvz.ui.theme.AppMotion

@Composable
fun AnimatedPlayPauseIcon(
  isPlaying: Boolean,
  modifier: Modifier = Modifier,
  tint: Color = LocalContentColor.current,
) {
  val transition = updateTransition(targetState = isPlaying, label = "play_pause_icon")
  val playAlpha =
    transition.animateFloat(
      transitionSpec = {
        spring(
          dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
          stiffness = AppMotion.Spatial.Expressive.stiffness,
        )
      },
      label = "play_alpha",
    ) { playing -> if (playing) 0f else 1f }
  val playScale =
    transition.animateFloat(
      transitionSpec = {
        spring(
          dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
          stiffness = AppMotion.Spatial.Expressive.stiffness,
        )
      },
      label = "play_scale",
    ) { playing -> if (playing) 0.82f else 1f }
  val playRotation =
    transition.animateFloat(
      transitionSpec = {
        spring(
          dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
          stiffness = AppMotion.Spatial.Expressive.stiffness,
        )
      },
      label = "play_rotation",
    ) { playing -> if (playing) -12f else 0f }

  val pauseAlpha =
    transition.animateFloat(
      transitionSpec = {
        spring(
          dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
          stiffness = AppMotion.Spatial.Expressive.stiffness,
        )
      },
      label = "pause_alpha",
    ) { playing -> if (playing) 1f else 0f }
  val pauseScale =
    transition.animateFloat(
      transitionSpec = {
        spring(
          dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
          stiffness = AppMotion.Spatial.Expressive.stiffness,
        )
      },
      label = "pause_scale",
    ) { playing -> if (playing) 1f else 0.82f }
  val barSpread =
    transition.animateFloat(
      transitionSpec = {
        spring(
          dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
          stiffness = AppMotion.Spatial.Expressive.stiffness,
        )
      },
      label = "pause_spread",
    ) { playing -> if (playing) 1f else 0f }

  Box(
    modifier = modifier,
    contentAlignment = Alignment.Center,
  ) {
    Canvas(
      modifier =
        Modifier
          .fillMaxSize()
          .graphicsLayer(
            alpha = playAlpha.value,
            scaleX = playScale.value,
            scaleY = playScale.value,
            rotationZ = playRotation.value,
          ),
    ) {
      val triangle =
        Path().apply {
          moveTo(size.width * 0.34f, size.height * 0.20f)
          quadraticTo(size.width * 0.30f, size.height * 0.24f, size.width * 0.30f, size.height * 0.31f)
          lineTo(size.width * 0.30f, size.height * 0.69f)
          quadraticTo(size.width * 0.30f, size.height * 0.76f, size.width * 0.34f, size.height * 0.80f)
          lineTo(size.width * 0.76f, size.height * 0.56f)
          quadraticTo(size.width * 0.83f, size.height * 0.52f, size.width * 0.83f, size.height * 0.50f)
          quadraticTo(size.width * 0.83f, size.height * 0.48f, size.width * 0.76f, size.height * 0.44f)
          close()
        }
      drawPath(
        path = triangle,
        color = tint,
      )
    }

    Canvas(
      modifier =
        Modifier
          .fillMaxSize()
          .graphicsLayer(
            alpha = pauseAlpha.value,
            scaleX = pauseScale.value,
            scaleY = pauseScale.value,
          ),
    ) {
      val barWidth = size.width * 0.18f
      val barHeight = size.height * 0.58f
      val top = (size.height - barHeight) / 2f
      val spread = size.width * (0.015f + (0.11f * barSpread.value))
      val leftX = (size.width / 2f) - spread - barWidth / 2f
      val rightX = (size.width / 2f) + spread - barWidth / 2f
      val radius = CornerRadius(barWidth * 0.7f, barWidth * 0.7f)

      drawRoundRect(
        color = tint,
        topLeft = Offset(leftX, top),
        size = Size(barWidth, barHeight),
        cornerRadius = radius,
      )
      drawRoundRect(
        color = tint,
        topLeft = Offset(rightX, top),
        size = Size(barWidth, barHeight),
        cornerRadius = radius,
      )
    }
  }
}
