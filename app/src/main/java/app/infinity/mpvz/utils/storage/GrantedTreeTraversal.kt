package app.infinity.mpvz.utils.storage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Traversal starts only at granted roots. Providers supply opaque document identifiers. */
internal suspend fun <T> traverseGrantedTrees(
  roots: List<T>,
  identity: (T) -> String,
  children: suspend (T) -> List<T>,
  isDirectory: (T) -> Boolean,
  visit: suspend (T, List<T>) -> Unit,
) {
  val pending = ArrayDeque(roots)
  val visited = mutableSetOf<String>()
  while (pending.isNotEmpty()) {
    currentCoroutineContext().ensureActive()
    val directory = pending.removeFirst()
    if (!visited.add(identity(directory))) continue
    val entries = children(directory)
    visit(directory, entries)
    entries.filter(isDirectory).forEach(pending::addLast)
  }
}
