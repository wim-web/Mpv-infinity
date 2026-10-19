package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppPlaybackQueueTest {
  @Test
  fun resolvingSelectedNetworkVideoPreservesQueueAndOtherItems() {
    val first = PlaybackItem.fromUri("content://videos/1", title = "First")
    val selected = PlaybackItem.fromUri(
      "mpvinfinity-network://3/movies/episode.mkv",
      title = "Episode", networkSource = NetworkPlaybackSource(3L, "/movies/episode.mkv"),
      headers = mapOf("Authorization" to "test-authorization"),
    )
    val last = PlaybackItem.fromUri("content://videos/3", title = "Last")
    val original = InAppPlaybackQueue(listOf(first, selected, last), 1, explicit = true)

    val resolved = original.replaceSelected(selected.copy(playableUri = "http://127.0.0.1/resolved"))

    assertEquals(listOf(first, selected, last), original.items)
    assertEquals(first, resolved.items[0])
    assertEquals(last, resolved.items[2])
    assertEquals(1, resolved.index)
    assertEquals(selected.stableId, resolved.selected.stableId)
    assertEquals(selected.originalUri, resolved.selected.originalUri)
    assertEquals(selected.networkSource, resolved.selected.networkSource)
    assertEquals(selected.headers, resolved.selected.headers)
    assertTrue(resolved.explicit)
  }

  @Test
  fun torrentResolutionCarriesSelectedFileAndPreparationWithoutReordering() {
    val selected = PlaybackItem.fromUri("magnet:?xt=urn:btih:test").copy(torrentFileIndex = 4)
    val original = InAppPlaybackQueue(listOf(selected), 0, explicit = true, torrentPreparationId = "prepared")
    val resolvedItem = selected.copy(
      stableId = PlaybackIdentity.forTorrent("test", 4),
      playableUri = "http://127.0.0.1/torrent/4", title = "Episode 4.mkv",
    )

    val resolved = original.replaceSelected(resolvedItem)

    assertEquals("prepared", resolved.torrentPreparationId)
    assertEquals(4, resolved.selected.torrentFileIndex)
    assertEquals(resolvedItem, resolved.selected)
    assertFalse(resolved.selected.requiresTorrentResolution())
    assertTrue(original.selected.requiresTorrentResolution())
  }

  @Test(expected = IllegalArgumentException::class)
  fun emptyQueueCannotLaunchPlayback() {
    InAppPlaybackQueue(emptyList(), 0)
  }

  @Test(expected = IllegalArgumentException::class)
  fun invalidSelectionCannotSilentlyPlayDifferentVideo() {
    InAppPlaybackQueue(listOf(PlaybackItem.fromUri("content://videos/1")), 1)
  }

  @Test
  fun newerSelectionPreventsDelayedOldLoadFromCommitting() {
    val first = PlaybackActivityOwner.claim()
    val second = PlaybackActivityOwner.claim()
    var loaded = "none"

    val staleResult = PlaybackActivityOwner.runIfOwner(first, -1L) { loaded = "first"; 1L }
    val latestResult = PlaybackActivityOwner.runIfOwner(second, -1L) { loaded = "second"; 2L }

    assertEquals(-1L, staleResult)
    assertEquals(2L, latestResult)
    assertEquals("second", loaded)
  }

  @Test
  fun fullscreenHandoffInvalidatesPendingInlineLoadWithoutStoppingSharedMedia() {
    val inlineOwner = PlaybackActivityOwner.claim()
    val fullscreenOwner = PlaybackActivityOwner.claim()
    var mutated = false

    assertFalse(PlaybackActivityOwner.beginRequest(inlineOwner) { mutated = true })
    assertFalse(mutated)
    assertTrue(PlaybackActivityOwner.owns(fullscreenOwner))
    assertNotEquals(inlineOwner, fullscreenOwner)
  }
}
