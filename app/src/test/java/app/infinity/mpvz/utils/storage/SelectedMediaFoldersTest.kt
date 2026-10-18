package app.infinity.mpvz.utils.storage

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SelectedMediaFoldersTest {
  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun noSelectionDeniesEveryPathAndQuery() {
    assertFalse(SelectedMediaFolders.contains(emptySet(), "/storage/emulated/0/Movies/a.mp4"))
    assertEquals("0", SelectedMediaFolders.selection(emptySet()).sql)
    assertTrue(SelectedMediaFolders.selection(emptySet()).args.isEmpty())
    assertTrue(MediaScanOptions(includeAudio = true).selectedRoots.isEmpty())
  }

  @Test
  fun selectionIncludesDescendantsButNotSiblingPrefixesOrParents() {
    val root = temporaryFolder.newFolder("Movies")
    val roots = setOf(root.path)
    assertTrue(SelectedMediaFolders.contains(roots, root.path))
    assertTrue(SelectedMediaFolders.contains(roots, File(root, "nested/a.mp4").path))
    assertFalse(SelectedMediaFolders.contains(roots, root.path + "-other/a.mp4"))
    assertFalse(SelectedMediaFolders.contains(roots, root.parent))
    assertFalse(SelectedMediaFolders.contains(roots, File(root, "../outside/a.mp4").path))
  }

  @Test
  fun symlinksCannotEscapeTheSelectedTree() {
    val root = temporaryFolder.newFolder("selected")
    val outside = temporaryFolder.newFolder("outside")
    Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
    assertFalse(SelectedMediaFolders.contains(setOf(root.path), File(root, "link/movie.mp4").path))
  }

  @Test
  fun sqlBindsLiteralPrefixesIncludingWildcardsQuotesAndUnicode() {
    val path = "/storage/emulated/0/動画🎵_%'\\"
    val prefix = "$path/"
    val query = SelectedMediaFolders.selection(setOf(path))
    assertEquals("(substr(_data, 1, ?) = ? COLLATE BINARY)", query.sql)
    assertArrayEquals(arrayOf(prefix.codePointCount(0, prefix.length).toString(), prefix), query.args)
    assertFalse(query.sql.contains(path))
  }

  @Test
  fun multipleSelectionsAreStableAndBoundSeparately() {
    val query = SelectedMediaFolders.selection(setOf("/b", "/a/"))
    assertArrayEquals(arrayOf("3", "/a/", "3", "/b/"), query.args)
    assertEquals(
      "(substr(_data, 1, ?) = ? COLLATE BINARY OR substr(_data, 1, ?) = ? COLLATE BINARY)",
      query.sql,
    )
  }

  @Test
  fun audioAndVideoScopesAndCacheKeysStayIndependent() {
    val video = temporaryFolder.newFolder("video")
    val audio = temporaryFolder.newFolder("audio")
    val options = MediaScanOptions(videoFolders = setOf(video.path), audioFolders = setOf(audio.path))
    assertEquals(setOf(video.path), options.selectedRoots)
    assertFalse(options.allowsDirectory(audio))
    assertTrue(options.allowsFile(File(video, "a.mp4")))
    assertFalse(options.allowsFile(File(audio, "a.mp4")))
    assertTrue(options.copy(includeAudio = true).allowsFile(File(audio, "a.mp3")))
    assertFalse(options.allowsFile(File(video, "a.mp3")))
    assertNotEquals(options.cacheKey, options.copy(videoFolders = emptySet()).cacheKey)
    assertNotEquals(options.cacheKey, options.copy(audioFolders = emptySet()).cacheKey)
    assertNotEquals(options.rootDiscoveryCacheKey, options.copy(includeAudio = true).rootDiscoveryCacheKey)
  }
}
