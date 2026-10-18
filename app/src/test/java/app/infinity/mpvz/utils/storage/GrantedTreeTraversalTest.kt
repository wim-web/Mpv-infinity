package app.infinity.mpvz.utils.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class GrantedTreeTraversalTest {
  private data class Node(val id: String, val directory: Boolean)

  @Test
  fun noGrantsNeverQueriesAProvider() = runBlocking {
    var queries = 0
    traverseGrantedTrees<Node>(emptyList(), { it.id }, { queries++; emptyList() }, { it.directory }, { _, _ -> })
    assertEquals(0, queries)
  }

  @Test
  fun onlySelectedTreesAndTheirChildrenAreRead() = runBlocking {
    val visited = mutableListOf<String>()
    val root = Node("selected", true)
    val child = Node("child", true)
    val other = Node("unselected", true)
    val nodes = mapOf(root.id to listOf(child, Node("movie", false)), child.id to listOf(Node("song", false)), other.id to listOf(Node("private", false)))
    traverseGrantedTrees(listOf(root), { it.id }, { visited += it.id; nodes[it.id].orEmpty() }, { it.directory }, { _, _ -> })
    assertEquals(listOf("selected", "child"), visited)
  }

  @Test
  fun overlappingRootsAndProviderCyclesAreVisitedOnce() = runBlocking {
    val root = Node("root", true)
    val nested = Node("nested", true)
    val queried = mutableListOf<String>()
    traverseGrantedTrees(listOf(root, nested), { it.id }, {
      queried += it.id
      if (it == root) listOf(nested) else listOf(root)
    }, { it.directory }, { _, _ -> })
    assertEquals(listOf("root", "nested"), queried)
  }

  @Test
  fun changingSelectionDoesNotKeepPreviousRoots() = runBlocking {
    suspend fun scan(roots: List<Node>): List<String> {
      val queried = mutableListOf<String>()
      traverseGrantedTrees(roots, { it.id }, { queried += it.id; emptyList() }, { it.directory }, { _, _ -> })
      return queried
    }
    assertEquals(listOf("first"), scan(listOf(Node("first", true))))
    assertEquals(listOf("second"), scan(listOf(Node("second", true))))
    assertEquals(emptyList<String>(), scan(emptyList()))
  }
}
