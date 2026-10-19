/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.player

/** Immutable selection: preparing one video must never modify its neighbours or reorder the queue. */
internal data class InAppPlaybackQueue(
  val items: List<PlaybackItem>,
  val index: Int,
  val explicit: Boolean = false,
  val isM3u: Boolean = false,
  val torrentPreparationId: String? = null,
) {
  init {
    require(index in items.indices)
  }

  val selected: PlaybackItem get() = items[index]

  fun replaceSelected(item: PlaybackItem): InAppPlaybackQueue =
    copy(items = items.mapIndexed { itemIndex, current -> if (itemIndex == index) item else current })
}
