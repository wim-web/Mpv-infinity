/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Centralized state holder for browser navigation visibility.
 * Uses Compose state so screens recompose only when values actually change.
 */
object NavigationBarState {
  var isInSelectionMode: Boolean by mutableStateOf(false)
    private set

  var isDualPaneFolderSelected: Boolean by mutableStateOf(false)

  // Published by MiniPlayer so the root layout can consume its navigation inset.
  var isMiniPlayerVisible: Boolean by mutableStateOf(false)

  val navigationBarClearance: Dp = 88.dp

  var shouldHideNavigationBar: Boolean by mutableStateOf(false)
    private set

  var isPermissionDenied: Boolean by mutableStateOf(false)
    private set

  var isBrowserBottomBarVisible: Boolean by mutableStateOf(false)
    private set

  var onlyVideosSelected: Boolean by mutableStateOf(false)
    private set

  fun updateSelectionState(
    inSelectionMode: Boolean,
    onlyVideos: Boolean = false,
  ) {
    isInSelectionMode = inSelectionMode
    onlyVideosSelected = onlyVideos
    // Selection actions replace the navigation pill instead of stacking above it.
    shouldHideNavigationBar = inSelectionMode
  }

  fun updatePermissionState(denied: Boolean) {
    isPermissionDenied = denied
  }

  fun updateBottomBarVisibility(visible: Boolean) {
    isBrowserBottomBarVisible = visible
    shouldHideNavigationBar = !visible
  }

  // Scroll-adaptive nav labels: 1f shows icon + label, 0f collapses to icons only so the pill
  // stays legible on narrow screens and gets out of the way while reading a long list.
  var navLabelVisibility: Float by mutableFloatStateOf(1f)
    private set

  private var accumulatedScroll = 0f

  /** Attach once around the tab content; vertical deltas bubble up from the inner lists. */
  val navScrollConnection: NestedScrollConnection =
    object : NestedScrollConnection {
      override fun onPreScroll(
        available: Offset,
        source: NestedScrollSource,
      ): Offset {
        val delta = available.y
        // Reversing direction restarts the run so a small bounce does not toggle the labels.
        if ((delta < 0f && accumulatedScroll > 0f) || (delta > 0f && accumulatedScroll < 0f)) {
          accumulatedScroll = 0f
        }
        accumulatedScroll += delta
        if (accumulatedScroll < -SCROLL_THRESHOLD_PX) {
          navLabelVisibility = 0f
          accumulatedScroll = 0f
        } else if (accumulatedScroll > SCROLL_THRESHOLD_PX) {
          navLabelVisibility = 1f
          accumulatedScroll = 0f
        }
        return Offset.Zero
      }
    }

  fun expandNavLabels() {
    navLabelVisibility = 1f
    accumulatedScroll = 0f
  }

  private const val SCROLL_THRESHOLD_PX = 60f
}
