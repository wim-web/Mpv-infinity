/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.player

/** Applies the default display once per selected video, allowing explicit PiP expansion. */
internal class VideoPlaybackLaunchState {
  private var pendingPipEntry = false

  fun onMediaLaunch(preferPip: Boolean) {
    pendingPipEntry = preferPip
  }

  fun onPipEntered() {
    pendingPipEntry = false
  }

  fun consumePipEntry(
    mediaReady: Boolean,
    isAudio: Boolean,
    isInPip: Boolean,
    isForeground: Boolean,
  ): Boolean {
    if (!pendingPipEntry) return false
    if (isInPip) {
      pendingPipEntry = false
      return false
    }
    if (!mediaReady) return false
    if (isAudio) {
      pendingPipEntry = false
      return false
    }
    if (!isForeground) return false
    pendingPipEntry = false
    return true
  }
}
