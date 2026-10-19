/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Xml
import android.widget.Toast
import androidx.core.content.ContextCompat
import app.infinity.mpvz.R
import app.infinity.mpvz.database.repository.PlaylistRepository
import app.infinity.mpvz.domain.network.NetworkPlaybackUri
import app.infinity.mpvz.domain.playbackstate.repository.PlaybackStateRepository
import app.infinity.mpvz.domain.torrent.TorrentStreamRequest
import app.infinity.mpvz.domain.torrent.TorrentStreamingEngine
import app.infinity.mpvz.domain.torrent.isTorrentSource
import app.infinity.mpvz.preferences.AdvancedPreferences
import app.infinity.mpvz.preferences.AudioPreferences
import app.infinity.mpvz.preferences.BrowserPreferences
import app.infinity.mpvz.preferences.PlayerPreferences
import app.infinity.mpvz.network.AndroidCookieJar
import app.infinity.mpvz.network.NetworkUserAgent
import app.infinity.mpvz.ui.player.ytdlp.YtdlpManager
import app.infinity.mpvz.utils.history.RecentlyPlayedOps
import app.infinity.mpvz.utils.media.MediaUtils
import app.infinity.mpvz.utils.media.HttpUtils
import app.infinity.mpvz.utils.media.M3UParser
import app.infinity.mpvz.utils.media.M3UParseResult
import app.infinity.mpvz.utils.media.fileExtension
import app.infinity.mpvz.utils.storage.FileTypeUtils
import `is`.xyz.mpv.Utils
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.xmlpull.v1.XmlPullParser

/** Browser-owned MPV playback. No PlayerActivity is launched until the user opens fullscreen. */
internal object InAppVideoPlayback : KoinComponent {
  private const val TAG = "InAppVideoPlayback"
  private val preferences: PlayerPreferences by inject()
  private val audioPreferences: AudioPreferences by inject()
  private val advancedPreferences: AdvancedPreferences by inject()
  private val browserPreferences: BrowserPreferences by inject()
  private val playbackStates: PlaybackStateRepository by inject()
  private val playlists: PlaylistRepository by inject()
  private val torrents: TorrentStreamingEngine by inject()
  private val cookies: AndroidCookieJar by inject()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val _active = MutableStateFlow(false)
  val active = _active.asStateFlow()
  private var context: Context? = null
  private var ownerToken = 0L
  private var loadJob: Job? = null
  private var hostStarted = true
  private var observersStarted = false
  private var ownsAudioFocus = false
  private var focusRequest: AudioFocusRequest? = null
  private var noisyReceiverRegistered = false
  private val noisyReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (active.value && intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) setPlaying(false)
    }
  }

  fun shouldLaunch(intent: Intent): Boolean =
    intent.action == Intent.ACTION_VIEW &&
      preferences.defaultVideoPlaybackMode.get() == VideoPlaybackMode.IN_APP &&
      !intent.getBooleanExtra("is_audio", false) &&
      !intent.getBooleanExtra("media_library_audio", false) &&
      resolvePlaybackMimeType(intent.type, intent.getStringExtra("mime_type"))?.startsWith("audio/") != true &&
      sequenceOf(intent.dataString, intent.getStringExtra("local_media_path"))
        .filterNotNull().none { it.fileExtension() in FileTypeUtils.AUDIO_EXTENSIONS }

  fun play(context: Context, intent: Intent, releasePlayer: () -> Unit) {
    start(context, releasePlayer = releasePlayer) { readQueue(context.applicationContext, Intent(intent)) }
  }

  fun returnFromPlayer(context: Context, positionSeconds: Double, releasePlayer: () -> Unit): Boolean {
    val queue = PlaybackSession.queue.value
    if (queue.currentItem == null) return false
    val plan = InAppPlaybackQueue(queue.items, queue.currentIndex, queue.isExplicitQueue, queue.isM3u)
    start(context, positionSeconds, releasePlayer) { plan }
    return true
  }

  private fun start(
    context: Context,
    positionSeconds: Double? = null,
    releasePlayer: () -> Unit = {},
    prepare: suspend () -> InAppPlaybackQueue,
  ) {
    savePosition()
    loadJob?.cancel()
    this.context = context.applicationContext
    val token = PlaybackActivityOwner.claim()
    ownerToken = token
    releasePlayer()
    MediaPlaybackService.activityForeground = false
    ensureObservers()
    loadJob = scope.launch {
      try {
        val plan = expandPlaylist(prepare())
        ensureOwner(token)
        prepareCore(context.applicationContext)
        ensureOwner(token)
        check(takeAudioFocus()) { "Audio focus unavailable" }
        val item = withContext(Dispatchers.IO) { resolveItem(plan.selected, plan.torrentPreparationId) }
        ensureOwner(token)
        val existing = PlaybackSession.state.value
        val alreadyPlaying = positionSeconds == null && existing.currentItem?.stableId == item.stableId &&
          existing.phase in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND)
        val resolved = plan.replaceSelected(item)
        if (!PlaybackSession.awaitStopCompletion()) error("Previous playback did not stop")
        ensureOwner(token)
        PlaybackSession.replaceQueue(resolved.items, resolved.index, resolved.explicit, resolved.isM3u)
        PlaybackSession.setRepeatMode(preferences.repeatMode.get())
        PlaybackSession.setShuffleEnabled(preferences.shuffleEnabled.get())
        _active.value = true
        if (alreadyPlaying) {
          startNotificationIfEnabled(item)
          return@launch
        }
        val saved = withContext(Dispatchers.IO) { playbackStates.getVideoDataByTitle(item.stableId) }
        ensureOwner(token)
        val startPosition = positionSeconds ?: saved?.lastPosition?.toDouble()?.takeIf { preferences.savePositionOnQuit.get() }
        val generation = withContext(Dispatchers.IO) {
          PlaybackSession.load(
            item,
            initialPositionSeconds = startPosition,
            commit = { load -> PlaybackActivityOwner.runIfOwner(token, -1L, load) },
          )
        }
        check(generation >= 0L) { "Playback load rejected" }
        withTimeout(60_000L) {
          PlaybackSession.state.first { state ->
            state.generation == generation && state.phase in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND, PlaybackPhase.ERROR)
          }.also { check(it.phase != PlaybackPhase.ERROR) { it.error.orEmpty() } }
        }
        ensureOwner(token)
        PlaybackSession.markBackground()
        startNotificationIfEnabled(item)
        if (!hostStarted) onHostStopped()
        withContext(Dispatchers.IO) {
          RecentlyPlayedOps.addRecentlyPlayed(
            context.applicationContext, item.originalUri, item.title.orEmpty(), videoTitle = item.title,
            duration = ((PlaybackSession.getPropertyDouble("duration") ?: 0.0) * 1000).toLong(),
            launchSource = "in_app_player", aliases = listOf(item.stableId),
          )
        }
      } catch (timeout: TimeoutCancellationException) {
        if (PlaybackActivityOwner.owns(token)) {
          Log.e(TAG, "In-app playback timed out", timeout)
          stop()
          Toast.makeText(context.applicationContext, R.string.toast_playback_load_failed, Toast.LENGTH_LONG).show()
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        if (PlaybackActivityOwner.owns(token)) {
          Log.e(TAG, "Unable to start in-app playback", error)
          stop()
          Toast.makeText(context.applicationContext, R.string.toast_playback_load_failed, Toast.LENGTH_LONG).show()
        }
      }
    }
  }

  private fun ensureOwner(token: Long) {
    if (!PlaybackActivityOwner.owns(token)) throw CancellationException("Playback selection was replaced")
  }

  private suspend fun prepareCore(context: Context) {
    withContext(Dispatchers.IO) {
      if (listOf("mpv.conf", "input.conf", "cacert.pem").any { !File(context.filesDir, it).isFile }) Utils.copyAssets(context)
      for ((name, content) in listOf("mpv.conf" to advancedPreferences.mpvConf.get(), "input.conf" to advancedPreferences.inputConf.get())) {
        if (content.isNotBlank()) File(context.filesDir, name).writeText(content)
      }
    }
    // Reuse the full player's MPV options (decoder, renderer, audio, subtitles and mpv.conf)
    // without attaching a window or a second native core.
    context.resources.getLayout(R.layout.player_layout).use { parser ->
      while (parser.eventType != XmlPullParser.START_TAG) parser.next()
      val view = MPVView(context, Xml.asAttributeSet(parser))
      var result = view.initializeSession(context.filesDir.path, context.cacheDir.path)
      if (result.isFailure) {
        view.forceOpenGlFallback = true
        result = view.initializeSession(context.filesDir.path, context.cacheDir.path)
      }
      view.holder.removeCallback(view)
      result.getOrThrow()
    }
  }

  private suspend fun readQueue(context: Context, intent: Intent): InAppPlaybackQueue {
    val uri = intent.data ?: intent.getStringExtra("uri")?.let(Uri::parse) ?: error("Missing video URI")
    val prepared = if (intent.getBooleanExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_QUEUE, false)) {
      when (val result = PreparedPlaybackLaunchStore.consume(intent.getLongExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_TOKEN, -1L))) {
        is PreparedPlaybackLaunchResult.Accepted -> result.launch
        PreparedPlaybackLaunchResult.Stale -> throw CancellationException("Stale playback selection")
        PreparedPlaybackLaunchResult.Missing -> null
      }
    } else null
    val index = intent.getIntExtra("playlist_index", intent.getIntExtra("playlistIndex", prepared?.currentIndex ?: 0))
    val headers = PlaybackHttpHeaders.fromFlatPairs(intent.getStringArrayExtra("headers"))
    val reference = NetworkPlaybackUri.parse(uri.toString())
    val networkSource = reference?.let { NetworkPlaybackSource(it.connectionId, it.path.value) }
      ?: intent.getStringExtra("network_file_path")?.let { path ->
        intent.getLongExtra("network_connection_id", -1L).takeIf { it > 0 }?.let { NetworkPlaybackSource(it, path) }
      }
    val path = intent.getStringExtra("local_media_path") ?: withContext(Dispatchers.IO) { uri.resolveLocalPath(context) }
    val title = intent.getStringExtra("title") ?: intent.getStringExtra("filename") ?: uri.lastPathSegment ?: "Video"
    val selected = PlaybackItem.fromUri(
      uri.toString(),
      stableId = path?.let(PlaybackIdentity::forLocalPath) ?: intent.getStringExtra("media_identifier"),
      title = title,
      mimeType = resolvePlaybackMimeType(intent.type, intent.getStringExtra("mime_type")) ?: "video/*",
      headers = headers,
      artworkUri = intent.getStringExtra(MediaUtils.EXTRA_MEDIA_POSTER_URL),
      networkSource = networkSource,
      videoWidth = intent.getIntExtra(PlayerActivity.EXTRA_VIDEO_WIDTH, 0).takeIf { it > 0 },
      videoHeight = intent.getIntExtra(PlayerActivity.EXTRA_VIDEO_HEIGHT, 0).takeIf { it > 0 },
    ).copy(torrentFileIndex = intent.getIntExtra(MediaUtils.EXTRA_TORRENT_FILE_INDEX, -1).takeIf { it >= 0 })
    if (prepared != null) {
      val plan = InAppPlaybackQueue(prepared.items, index.coerceIn(prepared.items.indices), prepared.isExplicitQueue, prepared.isM3u)
      check(NetworkPlaybackUri.normalize(plan.selected.originalUri) == NetworkPlaybackUri.normalize(uri.toString()))
      return plan.replaceSelected(plan.selected.copy(
        headers = PlaybackHttpHeaders.merge(plan.selected.headers, headers),
        title = intent.getStringExtra("title") ?: plan.selected.title,
        videoWidth = selected.videoWidth ?: plan.selected.videoWidth,
        videoHeight = selected.videoHeight ?: plan.selected.videoHeight,
      ))
    }
    val playlistId = intent.getIntExtra("playlist_id", -1)
    if (playlistId >= 0) {
      val items = withContext(Dispatchers.IO) { playlists.getPlaylistItems(playlistId) }
      if (items.isNotEmpty()) {
        val queue = items.map { entry ->
          PlaybackItem.fromUri(
            uri = entry.filePath.takeIf { Uri.parse(it).scheme != null } ?: Uri.fromFile(File(entry.filePath)).toString(),
            title = entry.fileName, playlistItemId = entry.id, artworkUri = entry.tvgLogo,
            headers = PlaybackHttpHeaders.merge(entry.userAgent?.let { mapOf("User-Agent" to it) }.orEmpty(), headers),
          )
        }
        val plan = InAppPlaybackQueue(queue, index.coerceIn(queue.indices), explicit = true)
        return plan.replaceSelected(selected.copy(playlistItemId = plan.selected.playlistItemId))
      }
    }
    val legacyQueue = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra("playlist", Uri::class.java)
      else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>("playlist")
    if (!legacyQueue.isNullOrEmpty()) {
      val plan = InAppPlaybackQueue(legacyQueue.map { PlaybackItem.fromUri(it.toString(), headers = headers) }, index.coerceIn(legacyQueue.indices), explicit = true)
      return plan.replaceSelected(selected)
    }
    return InAppPlaybackQueue(listOf(selected), 0, torrentPreparationId = intent.getStringExtra(MediaUtils.EXTRA_TORRENT_PREPARATION_ID))
  }

  private suspend fun expandPlaylist(plan: InAppPlaybackQueue): InAppPlaybackQueue {
    val item = plan.selected
    if (!M3uPlaybackPolicy.shouldExpandInApp(
        item.playableUri, item.originalUri, item.title.orEmpty(), item.mimeType,
        hasExistingPlaylist = plan.explicit, hasPlaylistId = false,
      )
    ) return plan
    val uri = Uri.parse(item.originalUri)
    val result = when (uri.scheme?.lowercase()) {
      "http", "https" -> M3UParser.parseFromUrl(item.originalUri, headers = item.headers)
      "file", "content" -> M3UParser.parseFromUri(context!!, uri)
      else -> return plan
    }
    if (M3UParser.shouldPlayHlsDirectly(result)) return plan
    val parsed = result as? M3UParseResult.Success ?: error("Playlist could not be loaded")
    val items = parsed.items.map { entry ->
      PlaybackItem.fromUri(
        entry.url, title = entry.title, artworkUri = entry.tvgLogo, mimeType = "video/*",
        headers = PlaybackHttpHeaders.merge(item.headers, entry.userAgent?.let { mapOf("User-Agent" to it) }.orEmpty()),
      )
    }
    return InAppPlaybackQueue(items, 0, explicit = true, isM3u = true)
  }

  private suspend fun resolveItem(source: PlaybackItem, preparationId: String?): PlaybackItem {
    val uri = Uri.parse(source.playableUri)
    var headers = source.headers
    if (HttpUtils.isNetworkStream(uri)) {
      headers = PlaybackHttpHeaders.withDefault(headers, "Referer", HttpUtils.extractRefererDomain(uri))
      headers = PlaybackHttpHeaders.withDefault(headers, "User-Agent", NetworkUserAgent.resolve(context!!))
      cookies.exportForPlayback(source.playableUri, AndroidCookieJar.playbackCookieFile(context!!))
    }
    val item = source.copy(headers = headers)
    if (isTorrentSource(item.originalUri, item.mimeType)) {
      check(advancedPreferences.enableP2pStreaming.get()) { "Torrent streaming is disabled" }
      val result = torrents.startStream(TorrentStreamRequest(source = item.originalUri, fileIndex = item.torrentFileIndex, preparationId = preparationId))
      return item.copy(
        stableId = PlaybackIdentity.forTorrent(result.infoHash, result.selectedFile.index),
        originalUri = result.source, playableUri = result.localUrl, title = result.selectedFile.name,
        mimeType = result.selectedFile.mimeType, torrentFileIndex = result.selectedFile.index,
      )
    }
    torrents.stopStream()
    if (!YtdlpManager.requiresYtdlp(item.playableUri)) return item
    check(YtdlpManager.prepareForPlayback(context!!, item.playableUri)) { "yt-dlp preparation failed" }
    val resolved = YtdlpManager.resolveDirectMediaUrl(context!!, item.playableUri) ?: error("Stream URL could not be resolved")
    return item.copy(playableUri = resolved)
  }

  private fun startNotificationIfEnabled(item: PlaybackItem) {
    val context = context ?: return
    if (!audioPreferences.backgroundPlayback.get() || advancedPreferences.notificationStyle.get() == NotificationStyle.None) return
    runCatching {
      ContextCompat.startForegroundService(context, Intent(context, MediaPlaybackService::class.java).apply {
        putExtra("media_title", item.title)
        putExtra("media_uri", item.originalUri)
        putExtra("media_identifier", item.stableId)
        putExtra("audio_background_playback", false)
      })
    }.onFailure { Log.w(TAG, "In-app playback remains available without the notification service", it) }
  }

  fun handoff() {
    if (!active.value && loadJob?.isActive != true) return
    savePosition()
    loadJob?.cancel()
    ownerToken = PlaybackActivityOwner.claim()
    _active.value = false
    abandonAudioFocus()
    MediaPlaybackService.prepareForActivityHandoff()
  }

  fun stop() {
    handoff()
    torrents.stopStream()
    MediaPlaybackService.stopForTerminalDismissal()
  }

  fun setPlaying(playing: Boolean) {
    if (!active.value) return
    if (playing && !(if (hostStarted) takeAudioFocus() else MediaPlaybackService.takeAudioOwnershipForDetachedPlayback())) return
    PlaybackSession.setPropertyBoolean("pause", !playing)
  }

  fun onHostStarted() {
    hostStarted = true
    if (!active.value) return
    MediaPlaybackService.prepareForActivityHandoff()
    MediaPlaybackService.activityForeground = false
    if (!takeAudioFocus()) PlaybackSession.setPropertyBoolean("pause", true)
  }

  fun onHostStopped() {
    hostStarted = false
    if (!active.value) return
    savePosition()
    abandonAudioFocus()
    if (!audioPreferences.backgroundPlayback.get() || !MediaPlaybackService.isForegroundActive() ||
      !MediaPlaybackService.takeAudioOwnershipForDetachedPlayback()
    ) PlaybackSession.setPropertyBoolean("pause", true)
  }

  fun onServiceStopped() {
    if (active.value && !hostStarted) PlaybackSession.setPropertyBoolean("pause", true)
  }

  fun playNext(previous: Boolean = false): Boolean {
    val context = context ?: return false
    val current = PlaybackSession.queue.value
    val queue = (if (previous) PlaybackQueueReducer.previous(current) else PlaybackQueueReducer.next(current)) ?: return false
    // Queue advancement starts at zero, including Repeat All with a single item. It must
    // bypass the browser's same-item shortcut and any saved resume position for the next item.
    start(context, positionSeconds = 0.0) { InAppPlaybackQueue(queue.items, queue.currentIndex, queue.isExplicitQueue, queue.isM3u) }
    return true
  }

  private fun takeAudioFocus(): Boolean {
    if (ownsAudioFocus) return true
    val context = context ?: return false
    val manager = context.getSystemService(AudioManager::class.java)
    val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
      .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
      .setOnAudioFocusChangeListener { change ->
        if (ownsAudioFocus && change != AudioManager.AUDIOFOCUS_GAIN) {
          PlaybackSession.setPropertyBoolean("pause", true)
          if (change == AudioManager.AUDIOFOCUS_LOSS) ownsAudioFocus = false
        }
      }.build().also { focusRequest = it }
    ownsAudioFocus = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    if (!noisyReceiverRegistered) {
      ContextCompat.registerReceiver(context, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
      noisyReceiverRegistered = true
    }
    return ownsAudioFocus
  }

  private fun abandonAudioFocus() {
    ownsAudioFocus = false
    focusRequest?.let { context?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(it) }
    if (noisyReceiverRegistered) {
      context?.unregisterReceiver(noisyReceiver)
      noisyReceiverRegistered = false
    }
  }

  private fun ensureObservers() {
    if (observersStarted) return
    observersStarted = true
    scope.launch {
      PlaybackSession.state.collect { state ->
        if (active.value && state.phase in setOf(PlaybackPhase.STOPPING, PlaybackPhase.UNINITIALIZED)) {
          _active.value = false
          loadJob?.cancel()
          abandonAudioFocus()
        }
      }
    }
    scope.launch {
      PlaybackSession.propBoolean["eof-reached"].collect { ended ->
        if (ended == true && active.value) {
          if (preferences.repeatMode.get() == RepeatMode.ONE) {
            PlaybackSession.command("seek", "0", "absolute")
            setPlaying(true)
          } else if ((!preferences.autoplayNextVideo.get() && preferences.repeatMode.get() != RepeatMode.ALL) || !playNext()) stop()
        }
      }
    }
    scope.launch {
      while (true) {
        delay(5_000L)
        if (active.value) savePosition()
      }
    }
  }

  private fun savePosition() {
    if (!active.value || !PlaybackActivityOwner.owns(ownerToken)) return
    val item = PlaybackSession.state.value.currentItem ?: return
    val position = PlaybackSession.getPropertyDouble("time-pos")?.toInt() ?: return
    val duration = PlaybackSession.getPropertyDouble("duration")?.toInt() ?: 0
    val speed = PlaybackSession.getPropertyDouble("speed") ?: 1.0
    scope.launch(Dispatchers.IO) {
      val old = playbackStates.getVideoDataByTitle(item.stableId)
      val snapshot = PlaybackStateSnapshot(
        item.stableId, item.title.orEmpty(), position, duration, speed,
        old?.videoZoom ?: 0f, old?.videoAspect ?: "Fit", old?.customAspectRatio ?: -1f,
        old?.sid ?: -1, old?.secondarySid ?: -1, old?.subDelay ?: 0, old?.subSpeed ?: 1.0,
        old?.aid ?: -1, old?.audioDelay ?: 0, old?.externalSubtitles.orEmpty(),
      )
      playbackStates.upsert(PlaybackStatePersistence.buildEntity(old, snapshot, preferences.savePositionOnQuit.get(), browserPreferences.watchedThreshold.get()))
    }
  }
}
