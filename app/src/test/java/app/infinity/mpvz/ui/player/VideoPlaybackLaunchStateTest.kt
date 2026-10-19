package app.infinity.mpvz.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackLaunchStateTest {
  @Test
  fun pipLaunchWaitsForBothMediaAndForegroundFocus() {
    val state = VideoPlaybackLaunchState()
    state.onMediaLaunch(preferPip = true)

    assertFalse(state.readyToEnter(mediaReady = false))
    assertFalse(state.readyToEnter(isForeground = false))
    assertTrue(state.readyToEnter())
    assertFalse(state.readyToEnter())
  }

  @Test
  fun fullscreenSelectionReplacesAnUnfinishedPipLaunch() {
    val state = VideoPlaybackLaunchState()
    state.onMediaLaunch(preferPip = true)
    assertFalse(state.readyToEnter(mediaReady = false))

    state.onMediaLaunch(preferPip = false)

    assertFalse(state.readyToEnter())
  }

  @Test
  fun selectingAnotherVideoInPipDoesNotShrinkAgainAfterExpansion() {
    val state = VideoPlaybackLaunchState()
    state.onMediaLaunch(preferPip = true)
    assertTrue(state.readyToEnter())

    state.onMediaLaunch(preferPip = true)
    assertFalse(state.readyToEnter(mediaReady = false, isInPip = true, isForeground = false))
    assertFalse(state.readyToEnter(isInPip = true, isForeground = false))

    // The user expands the existing PiP window. Later ready/focus events must keep it fullscreen.
    assertFalse(state.readyToEnter())
  }

  @Test
  fun selectingNewVideoAfterManualExpansionAppliesPipDefaultAgain() {
    val state = VideoPlaybackLaunchState()
    state.onMediaLaunch(preferPip = true)
    assertTrue(state.readyToEnter())
    assertFalse(state.readyToEnter())

    state.onMediaLaunch(preferPip = true)

    assertTrue(state.readyToEnter())
  }

  @Test
  fun audioClassificationFromPreviousItemDoesNotCancelVideoWhileLoading() {
    val state = VideoPlaybackLaunchState()
    state.onMediaLaunch(preferPip = true)

    assertFalse(state.readyToEnter(mediaReady = false, isAudio = true))
    assertTrue(state.readyToEnter())
  }

  @Test
  fun audioDetectedWhenReadyCancelsPendingPipEvenWhileBackgrounded() {
    val state = VideoPlaybackLaunchState()
    state.onMediaLaunch(preferPip = true)

    assertFalse(state.readyToEnter(isAudio = true, isForeground = false))
    assertFalse(state.readyToEnter())
  }

  @Test
  fun homeOrManualPipEntrySatisfiesDefaultBeforeForegroundReadyCallback() {
    val state = VideoPlaybackLaunchState()
    state.onMediaLaunch(preferPip = true)
    assertFalse(state.readyToEnter(isForeground = false))

    state.onPipEntered()

    assertFalse(state.readyToEnter())
  }

  @Test
  fun failedSystemEntryDoesNotRetryOnEveryFocusOrReadyEvent() {
    val state = VideoPlaybackLaunchState()
    state.onMediaLaunch(preferPip = true)
    assertTrue(state.readyToEnter())

    // Android rejects entry (for example, PiP permission is disabled). Keep fullscreen usable.
    assertFalse(state.readyToEnter(isForeground = false))
    assertFalse(state.readyToEnter())
  }

  private fun VideoPlaybackLaunchState.readyToEnter(
    mediaReady: Boolean = true,
    isAudio: Boolean = false,
    isInPip: Boolean = false,
    isForeground: Boolean = true,
  ): Boolean = consumePipEntry(mediaReady, isAudio, isInPip, isForeground)
}
