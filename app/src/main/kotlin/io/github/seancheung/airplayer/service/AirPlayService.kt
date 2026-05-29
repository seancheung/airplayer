package io.github.seancheung.airplayer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import io.github.seancheung.airplayer.MainActivity
import io.github.seancheung.airplayer.Prefs
import io.github.seancheung.airplayer.R
import io.github.seancheung.airplayer.audio.DacpController
import io.github.seancheung.airplayer.audio.DmapParser
import io.github.seancheung.airplayer.audio.TrackInfo
import io.github.seancheung.airplayer.bridge.NativeBridge
import io.github.seancheung.airplayer.bridge.RaopCallbackHandler
import io.github.seancheung.airplayer.discovery.NsdServiceManager
import io.github.seancheung.airplayer.renderer.AudioRenderer
import io.github.seancheung.airplayer.renderer.HlsVideoPlayer
import io.github.seancheung.airplayer.renderer.ProxyConfig
import io.github.seancheung.airplayer.renderer.VideoRenderer
import io.github.seancheung.airplayer.viewmodel.DebugInfo
import java.net.NetworkInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AirPlayService : Service(), RaopCallbackHandler {

    private var nativeHandle = 0L
    private var nsdManager: NsdServiceManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false

    val videoRenderer = VideoRenderer()
    val audioRenderer = AudioRenderer()
    val hlsVideoPlayer by lazy { HlsVideoPlayer(this) }

    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState = _serverState.asStateFlow()

    private val _connectionCount = MutableStateFlow(0)
    val connectionCount = _connectionCount.asStateFlow()

    private val _videoAspect = MutableStateFlow(16f / 9f)
    val videoAspect = _videoAspect.asStateFlow()

    private val _videoResolution = MutableStateFlow("")
    val videoResolution = _videoResolution.asStateFlow()

    private val _audioOnly = MutableStateFlow(false)
    val audioOnly = _audioOnly.asStateFlow()

    // AirPlay video (HLS) casting active (YouTube etc. in-app cast, not screen mirroring)
    private val _videoCasting = MutableStateFlow(false)
    val videoCasting = _videoCasting.asStateFlow()

    // AirPlay photo currently displayed (null = none). Photos app / slideshow via PUT /photo.
    private val _photo = MutableStateFlow<Bitmap?>(null)
    val photo = _photo.asStateFlow()

    // Small MRU cache for preloaded (cacheOnly) photos, looked up on displayCached.
    private val photoCache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > 4
    }

    private val _trackInfo = MutableStateFlow(TrackInfo())
    val trackInfo = _trackInfo.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs = _durationMs.asStateFlow()

    private val _playing = MutableStateFlow(true)
    val playing = _playing.asStateFlow()

    @Volatile private var _progressBaseMs = 0L
    @Volatile private var _progressBaseTime = 0L

    fun currentPositionMs(): Long {
        if (_progressBaseTime == 0L || !_playing.value) return _positionMs.value
        val elapsed = SystemClock.elapsedRealtime() - _progressBaseTime
        return (_progressBaseMs + elapsed).coerceIn(0, _durationMs.value)
    }

    var dacpController: DacpController? = null
        private set
    private var mediaSession: MediaSessionCompat? = null
    private var mediaReceiver: BroadcastReceiver? = null

    var logCallback: ((String) -> Unit)? = null
    var modeCallback: ((Boolean) -> Unit)? = null

    @Volatile private var _lastPin: String? = null
    var pinCallback: ((String?) -> Unit)? = null
        set(value) {
            field = value
            // ui replay only: binding the activity must not mint a new native pin
            value?.invoke(_lastPin)
        }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logCallback?.invoke(msg)
    }

    inner class LocalBinder : Binder() {
        val service: AirPlayService
            get() = this@AirPlayService
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        dacpController = DacpController(this)
        mediaSession = MediaSessionCompat(this, "AirPlay").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    _setPlaying(true)
                    dacpController?.play()
                }
                override fun onPause() {
                    _setPlaying(false)
                    dacpController?.pause()
                }
                override fun onSkipToNext() { dacpController?.nextItem() }
                override fun onSkipToPrevious() { dacpController?.prevItem() }
            })
        }
        mediaReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_PLAY_PAUSE -> togglePlayPause()
                    ACTION_NEXT -> dacpController?.nextItem()
                    ACTION_PREV -> dacpController?.prevItem()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREV)
        }
        ContextCompat.registerReceiver(this, mediaReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        hlsVideoPlayer.logCallback = { msg -> log(msg) }
        applyHlsPrefs()
        hlsVideoPlayer.onActiveChanged = { active ->
            _videoCasting.value = active
            if (active) {
                // video cast supersedes audio-only / mirror UI
                _audioOnly.value = false
                if (shouldLaunchOnConnect()) launchMainActivity()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_SERVER) {
            promoteToForeground()
            val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            val name = prefs.getString(Prefs.SERVER_NAME, Prefs.DEF_SERVER_NAME) ?: Prefs.DEF_SERVER_NAME
            startServer(name, ensureServiceStarted = false)
            if (_serverState.value != ServerState.RUNNING) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    fun startServer(name: String) {
        startServer(name, ensureServiceStarted = true)
    }

    private fun startServer(name: String, ensureServiceStarted: Boolean) {
        if (_serverState.value == ServerState.RUNNING) return
        val effectiveName = name.ifBlank { Prefs.DEF_SERVER_NAME }

        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airplay:server").apply { acquire() }

        nsdManager = NsdServiceManager(this).apply { acquireMulticastLock() }

        val hwAddr = getHwAddr()
        val keyFile = filesDir.resolve("airplay.pem").absolutePath
        val nohold = prefs.getBoolean(Prefs.ALLOW_NEW_CONN, Prefs.DEF_ALLOW_NEW_CONN)
        val requirePin = prefs.getBoolean(Prefs.REQUIRE_PIN, Prefs.DEF_REQUIRE_PIN)

        nativeHandle = NativeBridge.nativeInit(this, hwAddr, effectiveName, keyFile, nohold, requirePin)
        if (nativeHandle == 0L) {
            log("Native init failed")
            _failStart()
            return
        }

        // apply settings from preferences
        val maxFps = prefs.getInt(Prefs.MAX_FPS, Prefs.DEF_MAX_FPS)
        val overscanned = prefs.getBoolean(Prefs.OVERSCANNED, Prefs.DEF_OVERSCANNED)
        val audioLatencyMs = prefs.getInt(Prefs.AUDIO_LATENCY_MS, Prefs.DEF_AUDIO_LATENCY_MS)
        val h265 = prefs.getBoolean(Prefs.H265_ENABLED, Prefs.DEF_H265_ENABLED)
        val alac = prefs.getBoolean(Prefs.ALAC_ENABLED, Prefs.DEF_ALAC_ENABLED)
        val aac = prefs.getBoolean(Prefs.AAC_ENABLED, Prefs.DEF_AAC_ENABLED)

        audioRenderer.swAlacEnabled = prefs.getBoolean(Prefs.SW_ALAC_ENABLED, Prefs.DEF_SW_ALAC_ENABLED)
        audioRenderer.audioBufferMultiplier = prefs.getInt(Prefs.AUDIO_BUFFER_MULTIPLIER, Prefs.DEF_AUDIO_BUFFER_MULTIPLIER)
        videoRenderer.enforceSdr = prefs.getBoolean(Prefs.ENFORCE_SDR, Prefs.DEF_ENFORCE_SDR)
        videoRenderer.keyAllowFrameDrop = prefs.getBoolean(Prefs.KEY_ALLOW_FRAME_DROP, Prefs.DEF_KEY_ALLOW_FRAME_DROP)
        val realtimePriority = prefs.getBoolean(Prefs.KEY_PRIORITY, Prefs.DEF_KEY_PRIORITY)
        videoRenderer.realtimeDecoderPriority = realtimePriority
        videoRenderer.operatingRateHint = prefs.getBoolean(Prefs.KEY_OPERATING_RATE, Prefs.DEF_KEY_OPERATING_RATE)
        videoRenderer.benchmarkLog = prefs.getBoolean(Prefs.BENCHMARK_LOG, Prefs.DEF_BENCHMARK_LOG)
        videoRenderer.benchmarkLogCallback = { msg -> logCallback?.invoke(msg) }
        videoRenderer.scheduledOutputBufferRelease = prefs.getBoolean(Prefs.SCHEDULED_OUTPUT_BUFFER_RELEASE, Prefs.DEF_SCHEDULED_OUTPUT_BUFFER_RELEASE)
        videoRenderer.decoderMode = prefs.getString(Prefs.VIDEO_DECODER, Prefs.DEF_VIDEO_DECODER)!!
        audioRenderer.realtimeDecoderPriority = realtimePriority
        NativeBridge.nativeSetH265Enabled(nativeHandle, h265)
        // Enable AirPlay video / HLS so in-app cast (YouTube etc.) streams video, not just audio.
        // Must run before nativeStart() registers the dnssd feature record.
        val hlsEnabled = prefs.getBoolean(Prefs.HLS_VIDEO_ENABLED, Prefs.DEF_HLS_VIDEO_ENABLED)
        NativeBridge.nativeSetHlsEnabled(nativeHandle, hlsEnabled)
        applyHlsPrefs()
        NativeBridge.nativeSetCodecs(nativeHandle, alac, aac)
        NativeBridge.nativeSetPlist(nativeHandle, "maxFPS", maxFps)
        NativeBridge.nativeSetPlist(nativeHandle, "overscanned", if (overscanned) 1 else 0)
        if (audioLatencyMs >= 0) {
            NativeBridge.nativeSetPlist(nativeHandle, "audio_delay_micros", audioLatencyMs * 1000)
        }

        // set display params
        val dm = resources.displayMetrics
        val res = prefs.getString(Prefs.RESOLUTION, Prefs.DEF_RESOLUTION)!!
        val (w, h) = if (res != "auto" && res.contains("x")) {
            val parts = res.split("x")
            parts[0].toInt() to parts[1].toInt()
        } else {
            dm.widthPixels to dm.heightPixels
        }
        videoRenderer.setResolution(w, h)
        _videoResolution.value = "${w}x${h}"
        _videoAspect.value = w.toFloat() / h
        NativeBridge.nativeSetDisplaySize(nativeHandle, w, h, maxFps)

        val requestedPort = prefs.getInt(Prefs.SERVER_PORT, Prefs.DEF_SERVER_PORT).coerceIn(1, 65535)
        val port = NativeBridge.nativeStart(nativeHandle, requestedPort)
        if (port < 0) {
            log("Failed to start on port $requestedPort")
            _failStart()
            return
        }

        // register mdns services
        val raopTxt = NativeBridge.nativeGetRaopTxtRecords(nativeHandle) ?: emptyMap()
        val airplayTxt = NativeBridge.nativeGetAirplayTxtRecords(nativeHandle) ?: emptyMap()
        val raopName = NativeBridge.nativeGetRaopServiceName(nativeHandle) ?: "AirPlay"
        val resolvedName = NativeBridge.nativeGetServerName(nativeHandle) ?: effectiveName

        nsdManager?.registerRaop(raopName, port, raopTxt)
        nsdManager?.registerAirplay(resolvedName, port, airplayTxt)

        _serverState.value = ServerState.RUNNING
        if (ensureServiceStarted) {
            ContextCompat.startForegroundService(this, Intent(this, AirPlayService::class.java))
        }
        promoteToForeground()
        log("Server started on port $port")
    }

    private fun _failStart() {
        if (nativeHandle != 0L) {
            NativeBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        nsdManager?.release()
        nsdManager = null
        wakeLock?.release()
        wakeLock = null
        _serverState.value = ServerState.ERROR
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
    }

    fun stopServer() {
        _teardownSession()
        dacpController?.release()
        mediaSession?.isActive = false
        _serverState.value = ServerState.STOPPED
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
        log("Server stopped")
    }

    /**
     * Soft restart: kick all currently connected clients and refresh the mDNS registration,
     * but keep the service process / foreground notification / dacp / mediaSession alive so
     * the receiver immediately starts accepting new connections again. Used by the "End
     * cast" back-button flow.
     */
    fun restartServer() {
        if (_serverState.value != ServerState.RUNNING) return
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(Prefs.SERVER_NAME, Prefs.DEF_SERVER_NAME) ?: Prefs.DEF_SERVER_NAME
        _teardownSession()
        mediaSession?.isActive = false
        // startServer() bails out unless state is non-RUNNING; flip to STOPPED so it proceeds,
        // then it will promote us back to RUNNING + foreground.
        _serverState.value = ServerState.STOPPED
        log("Restarting server (ending current session)")
        startServer(name, ensureServiceStarted = false)
    }

    /** Release native + nsd + renderers + per-session state. Does NOT touch service lifecycle. */
    private fun _teardownSession() {
        if (nativeHandle != 0L) {
            NativeBridge.nativeStop(nativeHandle)
            NativeBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        nsdManager?.release()
        nsdManager = null
        wakeLock?.release()
        wakeLock = null
        videoRenderer.release()
        audioRenderer.release()
        hlsVideoPlayer.release()
        clearPhoto()
        _audioOnly.value = false
        _videoCasting.value = false
        _trackInfo.value = TrackInfo()
        _positionMs.value = 0
        _durationMs.value = 0
        _connectionCount.value = 0
    }

    fun setVideoSurface(surface: Surface?) {
        videoRenderer.setSurface(surface)
    }

    /** Read HLS video prefs (proxy + max resolution) into the player; effective next cast session. */
    fun applyHlsPrefs() {
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        hlsVideoPlayer.proxyConfig = if (prefs.getBoolean(Prefs.PROXY_ENABLED, Prefs.DEF_PROXY_ENABLED)) {
            ProxyConfig(
                socks = (prefs.getString(Prefs.PROXY_TYPE, Prefs.DEF_PROXY_TYPE) ?: Prefs.DEF_PROXY_TYPE) == "socks",
                host = prefs.getString(Prefs.PROXY_HOST, Prefs.DEF_PROXY_HOST) ?: "",
                port = prefs.getInt(Prefs.PROXY_PORT, Prefs.DEF_PROXY_PORT)
            )
        } else null
        hlsVideoPlayer.maxVideoHeight = when (prefs.getString(Prefs.MAX_CAST_RESOLUTION, Prefs.DEF_MAX_CAST_RESOLUTION)) {
            "1080" -> 1080
            "1440" -> 1440
            "2160" -> 2160
            else -> Int.MAX_VALUE // auto / unlimited
        }
    }

    override fun onDestroy() {
        stopServer()
        mediaReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        mediaReceiver = null
        dacpController?.release()
        dacpController = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    // RaopCallbackHandler (called from native threads)

    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        videoRenderer.feedFrame(data, ntpTimeNs, isH265)
    }

    override fun onAudioData(data: ByteArray, ct: Int, ntpTimeNs: Long, seqNum: Int) {
        audioRenderer.feedAudio(data, ct, ntpTimeNs)
    }

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        clearPin()
        if (!usingScreen && !_audioOnly.value) {
            // pure music streaming (not screen mirroring audio)
            onAudioOnly(true)
        }
        log("Audio format: ct=$ct spf=$spf screen=$usingScreen")
    }

    override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {
        clearPin()
        if (w > 0 && h > 0) {
            _videoAspect.value = w / h
            _videoResolution.value = "${w.toInt()}x${h.toInt()}"
            videoRenderer.setResolution(w.toInt(), h.toInt())
        }
        log("Video size: ${srcW}x${srcH} -> ${w}x${h}")
    }

    override fun onVolumeChange(volume: Float) {
        audioRenderer.setVolume(volume)
    }

    override fun onConnectionInit() {
        val firstConnection = _connectionCount.value == 0
        _connectionCount.value++
        log("Client connected (${_connectionCount.value})")
        if (!firstConnection) return
        // conn_init is only a tcp pre-auth signal. pin-required sessions must wait for
        // onDisplayPin, otherwise the server ui can move before the client pin is current
        if (requiresPin()) return
        if (!shouldLaunchOnConnect()) return
        launchMainActivity()
    }

    override fun onConnectionDestroy() {
        _connectionCount.value = (_connectionCount.value - 1).coerceAtLeast(0)
        if (_connectionCount.value == 0) {
            hlsVideoPlayer.stop()
            clearPhoto()
            _audioOnly.value = false
            _trackInfo.value = TrackInfo()
            _positionMs.value = 0
            _durationMs.value = 0
            mediaSession?.isActive = false
        }
        log("Client disconnected (${_connectionCount.value})")
    }

    override fun onConnectionReset(reason: Int) {
        log("Connection reset: $reason")
    }

    override fun onDisplayPin(pin: String) {
        // a new pin is the sync point with the client prompt: show every new value immediately
        if (_lastPin == pin) return
        _lastPin = pin
        pinCallback?.invoke(pin)
        _updateMediaNotification()
    }

    override fun onMetadata(data: ByteArray) {
        val map = DmapParser.parse(data)
        val info = TrackInfo.fromDmap(map, _trackInfo.value.coverArt)
        _trackInfo.value = info
        _durationMs.value = info.durationMs
        _updateMediaMetadata()
        log("Track: ${info.artist} - ${info.title}")
    }

    override fun onCoverArt(data: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return
        _trackInfo.value = _trackInfo.value.copy(coverArt = bmp)
        _updateMediaMetadata()
    }

    override fun onProgress(start: Long, curr: Long, end: Long) {
        val rate = 44100.0
        val posMs = ((curr - start) / rate * 1000).toLong().coerceAtLeast(0)
        val durMs = ((end - start) / rate * 1000).toLong().coerceAtLeast(0)
        _positionMs.value = posMs
        _durationMs.value = durMs
        _progressBaseMs = posMs
        _progressBaseTime = SystemClock.elapsedRealtime()
        _playing.value = true
        _updatePlaybackState()
    }

    override fun onDacpId(dacpId: String, activeRemote: String) {
        dacpController?.update(dacpId, activeRemote)
        log("DACP: $dacpId")
    }

    // AirPlay video (HLS) callbacks — fired from native http threads, marshalled by HlsVideoPlayer

    override fun onVideoPlay(location: String, startPosition: Float) {
        clearPin()
        hlsVideoPlayer.play(location, startPosition)
    }

    override fun onVideoScrub(position: Float) {
        hlsVideoPlayer.scrub(position)
    }

    override fun onVideoRate(rate: Float) {
        hlsVideoPlayer.setRate(rate)
    }

    override fun onVideoStop() {
        hlsVideoPlayer.stop()
        clearPhoto()
    }

    override fun onPhoto(data: ByteArray, assetKey: String, action: String, transition: String) {
        val bmp = if (data.isNotEmpty()) BitmapFactory.decodeByteArray(data, 0, data.size) else null
        when {
            action.equals("cacheOnly", ignoreCase = true) -> {
                if (bmp != null && assetKey.isNotEmpty()) synchronized(photoCache) { photoCache[assetKey] = bmp }
            }
            action.equals("displayCached", ignoreCase = true) -> {
                val cached = synchronized(photoCache) { photoCache[assetKey] }
                if (cached != null) showPhoto(cached)
            }
            else -> {
                if (bmp != null) {
                    if (assetKey.isNotEmpty()) synchronized(photoCache) { photoCache[assetKey] = bmp }
                    showPhoto(bmp)
                }
            }
        }
    }

    private fun showPhoto(bmp: Bitmap) {
        val firstPhoto = _photo.value == null
        _audioOnly.value = false
        hlsVideoPlayer.stop() // photo supersedes any active video cast
        _photo.value = bmp
        log("Photo displayed (${bmp.width}x${bmp.height})")
        if (firstPhoto && shouldLaunchOnConnect()) launchMainActivity()
    }

    private fun clearPhoto() {
        if (_photo.value != null) _photo.value = null
        synchronized(photoCache) { photoCache.clear() }
    }

    override fun onVideoPlaybackInfo(): DoubleArray = hlsVideoPlayer.playbackInfo()

    override fun onVideoPlaylistRemove(): Float = hlsVideoPlayer.playlistRemovePosition()

    override fun onAudioOnly(audioOnly: Boolean) {
        val prev = _audioOnly.value
        _audioOnly.value = audioOnly
        if (audioOnly && !prev) {
            mediaSession?.isActive = true
            modeCallback?.invoke(true)
            log("Audio mode")
        } else if (!audioOnly && prev) {
            mediaSession?.isActive = false
            _trackInfo.value = TrackInfo()
            _positionMs.value = 0
            _durationMs.value = 0
            modeCallback?.invoke(false)
            log("Mirror mode")
        }
    }

    private fun _updateMediaMetadata() {
        val info = _trackInfo.value
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, info.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, info.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, info.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, _durationMs.value)
        info.coverArt?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        mediaSession?.setMetadata(builder.build())
        _updateMediaNotification()
    }

    fun togglePlayPause() {
        val nowPlaying = !_playing.value
        _setPlaying(nowPlaying)
        dacpController?.playPause()
    }

    private fun _setPlaying(playing: Boolean) {
        _playing.value = playing
        if (playing) {
            // resume extrapolation from current position
            _progressBaseMs = _positionMs.value
            _progressBaseTime = SystemClock.elapsedRealtime()
        } else {
            // freeze position
            _positionMs.value = currentPositionMs()
            _progressBaseTime = 0
        }
        _updatePlaybackState()
    }

    private fun _updatePlaybackState() {
        val isPlaying = _playing.value
        val pbState = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val speed = if (isPlaying) 1f else 0f
        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(pbState, _positionMs.value, speed, SystemClock.elapsedRealtime())
            .build()
        mediaSession?.setPlaybackState(state)
        _updateMediaNotification()
    }

    private fun clearPin() {
        _lastPin = null
        pinCallback?.invoke(null)
        _updateMediaNotification()
    }

    fun collectDebugInfo() = DebugInfo(
        videoCodec = videoRenderer.codecName,
        videoRes = _videoResolution.value,
        videoFps = videoRenderer.fps,
        videoBitrate = videoRenderer.bitrateBps,
        videoFrames = videoRenderer.frameCount,
        droppedFrames = videoRenderer.droppedFrames,
        framePacingJitterUs = videoRenderer.framePacingJitterUs,
        audioCodec = audioRenderer.codecLabel,
        audioVolume = (audioRenderer.volume * 100).toInt(),
        connections = _connectionCount.value,
    )

    // helpers

    private fun getHwAddr(): ByteArray {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (iface.name.startsWith("wlan") || iface.name.startsWith("eth")) {
                    val mac = iface.hardwareAddress
                    if (mac != null && mac.size == 6) return mac
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get hardware address", e)
        }
        // fallback: random-ish address
        return byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        // Separate HIGH-importance channel used ONLY to fire the full-screen-intent that
        // brings the activity to the foreground when a client connects while the app is
        // backgrounded / the screen is locked. Silent (no sound / vibration / lights) —
        // we don't want a chime, just the lift.
        nm.createNotificationChannel(
            NotificationChannel(
                LAUNCH_CHANNEL_ID,
                getString(R.string.notification_channel_launch),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        return _buildMediaNotification()
    }

    private fun promoteToForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        foregroundStarted = true
    }

    private fun requiresPin(): Boolean {
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(Prefs.REQUIRE_PIN, Prefs.DEF_REQUIRE_PIN)
    }

    private fun shouldLaunchOnConnect(): Boolean {
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(Prefs.LAUNCH_ON_CONNECT, Prefs.DEF_LAUNCH_ON_CONNECT)
    }

    private fun _buildMediaNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val info = _trackInfo.value
        val isAudio = _audioOnly.value && info.title.isNotEmpty()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)

        if (isAudio) {
            builder.setContentTitle(info.title).setContentText(info.artist).setSubText(info.album)
            info.coverArt?.let { builder.setLargeIcon(it) }
            mediaSession?.sessionToken?.let { token ->
                builder.setStyle(
                    MediaNotificationCompat.MediaStyle()
                        .setMediaSession(token)
                        .setShowActionsInCompactView(0, 1, 2)
                )
                // transport action buttons
                builder.addAction(android.R.drawable.ic_media_previous, getString(R.string.cd_previous), _mediaAction(ACTION_PREV))
                builder.addAction(android.R.drawable.ic_media_pause, getString(R.string.cd_play_pause), _mediaAction(ACTION_PLAY_PAUSE))
                builder.addAction(android.R.drawable.ic_media_next, getString(R.string.cd_next), _mediaAction(ACTION_NEXT))
            }
        } else {
            if (_lastPin != null) {
                // passive handoff only: do not launch/reorder the activity during pin auth
                builder.setContentTitle(getString(R.string.notification_pin_title))
                    .setContentText(getString(R.string.notification_pin_text, _lastPin))
            } else {
                builder.setContentTitle(getString(R.string.notification_title))
                    .setContentText(getString(R.string.notification_text))
            }
        }
        return builder.build()
    }

    private fun launchMainActivity() {
        Handler(Looper.getMainLooper()).post {
            val launchIntent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            // Best-effort direct launch first — succeeds when the app is already foreground
            // or recently visible; on Android 10+ the system silently blocks it when the
            // service is fully backgrounded.
            try { startActivity(launchIntent) } catch (e: Exception) { Log.w(TAG, "startActivity failed", e) }
            // Reliable fallback: fire a full-screen-intent notification. The system promotes
            // it to a full-screen activity launch when the device is locked / the app is
            // backgrounded, otherwise degrades to a silent heads-up. Auto-dismissed quickly
            // so it doesn't linger next to the persistent foreground notification.
            val pi = PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(this, LAUNCH_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .setSilent(true)
                .setTimeoutAfter(3000)
                .build()
            try {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(LAUNCH_NOTIFICATION_ID, n)
            } catch (e: SecurityException) {
                Log.w(TAG, "Full-screen-intent denied", e)
            }
        }
    }

    private fun _mediaAction(action: String): PendingIntent {
        val intent = Intent(action).setPackage(packageName)
        return PendingIntent.getBroadcast(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun _updateMediaNotification() {
        if (_serverState.value != ServerState.RUNNING) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, _buildMediaNotification())
    }

    enum class ServerState {
        STOPPED,
        RUNNING,
        ERROR
    }

    companion object {
        private const val TAG = "AirPlayService"
        private const val CHANNEL_ID = "airplay_service"
        private const val LAUNCH_CHANNEL_ID = "airplay_launch"
        private const val NOTIFICATION_ID = 1
        private const val LAUNCH_NOTIFICATION_ID = 2
        const val ACTION_PLAY_PAUSE = "io.github.seancheung.airplayer.PLAY_PAUSE"
        const val ACTION_NEXT = "io.github.seancheung.airplayer.NEXT"
        const val ACTION_PREV = "io.github.seancheung.airplayer.PREV"
        const val ACTION_START_SERVER = "io.github.seancheung.airplayer.START_SERVER"
    }
}
