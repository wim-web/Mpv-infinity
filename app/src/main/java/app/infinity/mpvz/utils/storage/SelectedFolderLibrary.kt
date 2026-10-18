package app.infinity.mpvz.utils.storage

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.DateUtils
import app.infinity.mpvz.domain.media.model.Video
import app.infinity.mpvz.domain.media.model.VideoFolder
import app.infinity.mpvz.preferences.FoldersPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Local media is read exclusively through persisted, user-selected document trees. */
object SelectedFolderLibrary : KoinComponent {
  private val preferences: FoldersPreferences by inject()
  private val mutex = Mutex()
  @Volatile private var generation = 0L
  private val snapshots = ConcurrentHashMap<String, Snapshot>()
  private val metadata = ConcurrentHashMap<String, Entry>()

  data class Entry(val video: Video, val artist: String = "", val album: String = "", val year: Int = 0, val track: Int = 0)
  data class Directory(val uri: String, val name: String, val parent: String?)
  data class Snapshot(val directories: List<Directory>, val videos: List<Video>, val createdAt: Long = System.currentTimeMillis())
  private data class Document(val uri: Uri, val id: String, val name: String, val mime: String, val size: Long, val modified: Long, val parent: String?) {
    val directory get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
  }

  fun invalidate() {
    generation++
    snapshots.clear()
    metadata.clear()
  }

  fun selectedTrees(audio: Boolean): Set<String> =
    if (audio) preferences.selectedAudioTrees.get() else preferences.selectedVideoTrees.get()

  fun grantedTrees(context: Context, audio: Boolean): Set<String> {
    val grants = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
    return selectedTrees(audio).intersect(grants)
  }

  fun contains(context: Context, uri: String, audio: Boolean): Boolean =
    runCatching {
      val document = Uri.parse(uri)
      if (!DocumentsContract.isTreeUri(document)) return false
      grantedTrees(context, audio).any { root ->
        val tree = Uri.parse(root)
        tree.authority == document.authority && DocumentsContract.getTreeDocumentId(tree) == DocumentsContract.getTreeDocumentId(document)
      }
    }.getOrDefault(false)

  fun scopeKey(context: Context): String =
    listOf(grantedTrees(context, false).sorted(), grantedTrees(context, true).sorted(), preferences.includeNoMediaFolders.get(), preferences.hiddenFolderMarkerNames.get().sorted()).toString()

  suspend fun snapshot(context: Context, audio: Boolean): Snapshot = withContext(Dispatchers.IO) {
    mutex.withLock {
      val roots = grantedTrees(context, audio)
      val revision = generation
      val key = "$revision|$audio|${roots.sorted()}|${preferences.includeNoMediaFolders.get()}|${preferences.hiddenFolderMarkerNames.get().sorted()}"
      snapshots[key]?.takeIf { System.currentTimeMillis() - it.createdAt < 10_000 }?.let { return@withLock it }
      val directories = mutableListOf<Directory>()
      val videos = linkedMapOf<String, Video>()
      val rootDocuments = roots.mapNotNull { value ->
        runCatching {
          val tree = Uri.parse(value)
          val id = DocumentsContract.getTreeDocumentId(tree)
          val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
          context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else Document(uri, id, cursor.getString(0) ?: id, DocumentsContract.Document.MIME_TYPE_DIR, 0, 0, null)
          }
        }.getOrNull()
      }
      traverseGrantedTrees(
        roots = rootDocuments,
        identity = { "${it.uri.authority}:${it.id}" },
        isDirectory = { it.directory },
        children = { directory ->
          currentCoroutineContext().ensureActive()
          if (revision != generation || roots != grantedTrees(context, audio)) return@traverseGrantedTrees emptyList()
          val entries = queryChildren(context, directory)
          val hidden = !preferences.includeNoMediaFolders.get()
          if (hidden && entries.any { it.name in preferences.hiddenFolderMarkerNames.get() }) emptyList()
          else entries.filterNot { hidden && it.name.startsWith('.') }
        },
        visit = { directory, entries ->
          directories += Directory(directory.uri.toString(), directory.name, directory.parent)
          entries.filterNot { it.directory }.forEach { file ->
            val extension = file.name.substringAfterLast('.', "").lowercase()
            val isAudio = file.mime.startsWith("audio/") || extension in FileTypeUtils.AUDIO_EXTENSIONS
            val isVideo = file.mime.startsWith("video/") || FileTypeUtils.isVideoFile(java.io.File(file.name))
            if ((audio && isAudio) || (!audio && isVideo && !isAudio)) {
              val path = file.uri.toString()
              videos.putIfAbsent("${file.uri.authority}:${file.id}", Video(
                id = stableId("${file.uri.authority}:${file.id}"), title = file.name.substringBeforeLast('.'), displayName = file.name,
                path = path, uri = file.uri, duration = 0, durationFormatted = "", size = file.size,
                sizeFormatted = android.text.format.Formatter.formatShortFileSize(context, file.size),
                dateModified = file.modified / 1000, dateAdded = file.modified / 1000, mimeType = file.mime,
                bucketId = directory.uri.toString(), bucketDisplayName = directory.name,
                width = 0, height = 0, fps = 0f, resolution = "", isAudio = audio,
              ))
            }
          }
        },
      )
      currentCoroutineContext().ensureActive()
      if (revision != generation || roots != grantedTrees(context, audio)) return@withLock null
      Snapshot(directories, videos.values.toList()).also { snapshots[key] = it }
    } ?: snapshot(context, audio)
  }

  private fun queryChildren(context: Context, directory: Document): List<Document> {
    val fields = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
    return runCatching {
      val result = mutableListOf<Document>()
      val children = DocumentsContract.buildChildDocumentsUriUsingTree(directory.uri, directory.id)
      context.contentResolver.query(children, fields, null, null, null)?.use { cursor ->
        while (cursor.moveToNext()) {
          val id = cursor.getString(0) ?: continue
          result += Document(DocumentsContract.buildDocumentUriUsingTree(directory.uri, id), id,
            cursor.getString(1) ?: id, cursor.getString(2) ?: "application/octet-stream",
            if (cursor.isNull(3)) 0 else cursor.getLong(3), if (cursor.isNull(4)) 0 else cursor.getLong(4), directory.uri.toString())
        }
      }
      result
    }.getOrDefault(emptyList())
  }

  suspend fun entry(context: Context, video: Video): Entry = withContext(Dispatchers.IO) {
    if (!contains(context, video.path, video.isAudio)) return@withContext Entry(video)
    val key = "${video.path}|${video.dateModified}|${video.size}"
    metadata[key]?.let { return@withContext it }
    val result = runCatching {
      MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(context, video.uri)
        fun field(key: Int) = retriever.extractMetadata(key).orEmpty()
        val duration = field(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0L
        val width = field(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toIntOrNull() ?: 0
        val height = field(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toIntOrNull() ?: 0
        Entry(video.copy(
          title = if (video.isAudio) field(MediaMetadataRetriever.METADATA_KEY_TITLE).ifBlank { video.title } else video.title,
          duration = duration, durationFormatted = DateUtils.formatElapsedTime(duration / 1000),
          width = width, height = height, resolution = if (height > 0) "${height}p" else "",
        ), field(MediaMetadataRetriever.METADATA_KEY_ARTIST), field(MediaMetadataRetriever.METADATA_KEY_ALBUM),
          field(MediaMetadataRetriever.METADATA_KEY_YEAR).toIntOrNull() ?: 0,
          field(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).substringBefore('/').toIntOrNull() ?: 0)
      }
    }.getOrElse { Entry(video) }
    if (contains(context, video.path, video.isAudio)) metadata[key] = result
    result
  }

  suspend fun videos(context: Context, audio: Boolean, folder: String? = null): List<Video> {
    val result = snapshot(context, audio).videos.filter { folder == null || it.bucketId == folder }
    return result.map { currentCoroutineContext().ensureActive(); entry(context, it).video }
      .filter { contains(context, it.path, audio) }
  }

  suspend fun folders(context: Context, audio: Boolean, minimumSeconds: Int = 0): List<VideoFolder> {
    val source = if (audio && minimumSeconds > 0) videos(context, true).filter { it.duration >= minimumSeconds * 1000L }
      else snapshot(context, audio).videos
    return source.groupBy { it.bucketId }.map { (uri, files) ->
      VideoFolder(bucketId = uri, name = files.first().bucketDisplayName, path = uri, videoCount = files.size,
        totalSize = files.sumOf { it.size }, totalDuration = files.sumOf { it.duration }, lastModified = files.maxOf { it.dateModified })
    }.sortedBy { it.name.lowercase() }
  }

  fun displayName(uri: String): String =
    snapshots.values.asSequence().flatMap { it.directories.asSequence() }.firstOrNull { it.uri == uri }?.name
      ?: runCatching { DocumentsContract.getDocumentId(Uri.parse(uri)).substringAfterLast('/').substringAfter(':') }.getOrDefault("")

  fun stableId(value: String): Long = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).mostSignificantBits

  fun documentKey(uri: String): String? = runCatching {
    val parsed = Uri.parse(uri)
    "${parsed.authority}:${DocumentsContract.getDocumentId(parsed)}"
  }.getOrNull()

  fun legacyPath(uri: Uri): String? = runCatching {
    if (uri.authority != "com.android.externalstorage.documents") return null
    val id = DocumentsContract.getDocumentId(uri)
    val volume = id.substringBefore(':')
    (if (volume == "primary") "/storage/emulated/0/" else "/storage/$volume/") + id.substringAfter(':')
  }.getOrNull()
}
