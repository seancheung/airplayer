package io.github.seancheung.airplayer.renderer

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/** Proxy used for cast-video network fetches (segments/playlists), bypassing loopback. */
data class ProxyConfig(val socks: Boolean, val host: String, val port: Int) {
    val valid: Boolean get() = host.isNotBlank() && port in 1..65535
}

/**
 * ExoPlayer wrapper that plays the local HLS URL the native AirPlay-video stack hands us
 * (http://localhost:<port>/master.m3u8). The native httpd proxies the real media chunks from
 * the iOS device over the /reverse channel, so ExoPlayer only ever talks to localhost.
 *
 * Threading: ExoPlayer is single-threaded — it is created on and only ever touched from the
 * main thread. on_video_play/scrub/rate/stop arrive on native http threads and are marshalled
 * onto the main thread. The /playback-info answer is synchronous on a native thread, so it must
 * never call into ExoPlayer; instead a 250ms main-thread poller copies the player state into
 * volatile fields that [playbackInfo] reads lock-free.
 *
 * Rendering: the UI binds [playerFlow]'s ExoPlayer to a media3 PlayerView, which owns the
 * SurfaceView and handles aspect-ratio letterboxing. The player instance lives for one cast
 * session and is recreated on the next [play].
 */
class HlsVideoPlayer(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    private val _playerFlow = MutableStateFlow<ExoPlayer?>(null)
    /** Current ExoPlayer for PlayerView binding; null when no session is active. Main thread. */
    val playerFlow: StateFlow<ExoPlayer?> = _playerFlow.asStateFlow()

    // Thread-safe snapshot consumed by the synchronous /playback-info callback.
    @Volatile private var durationSec = 0.0
    @Volatile private var positionSec = -1.0   // -1 => not ready yet
    @Volatile private var rate = 0f
    @Volatile private var ready = false
    @Volatile private var bufferEmpty = true
    @Volatile private var bufferFull = false
    @Volatile private var likelyKeepUp = false
    @Volatile private var finished = false
    @Volatile var active = false
        private set

    /** Optional proxy for video fetches; applied when the player is (re)built on next play. */
    @Volatile var proxyConfig: ProxyConfig? = null

    /** Max video height to select (1080/1440/2160); Int.MAX_VALUE = unlimited (auto). */
    @Volatile var maxVideoHeight: Int = 1080

    /** Fired on the main thread when a session starts (true) or ends (false). */
    var onActiveChanged: ((Boolean) -> Unit)? = null
    var logCallback: ((String) -> Unit)? = null

    private fun log(msg: String) = logCallback?.invoke(msg)

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_ENDED -> { finished = true; log("HLS video ended") }
                Player.STATE_READY -> { ready = true; finished = false }
                Player.STATE_BUFFERING -> { ready = false }
            }
            snapshot()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) = snapshot()
        override fun onPlayerError(error: PlaybackException) {
            log("HLS video error: ${error.errorCodeName}")
            finished = true
            snapshot()
        }
    }

    private val poller = object : Runnable {
        override fun run() {
            snapshot()
            if (active) main.postDelayed(this, 250)
        }
    }

    @OptIn(UnstableApi::class)
    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val builder = ExoPlayer.Builder(context)
        val cfg = proxyConfig
        if (cfg != null && cfg.valid) {
            // Route fetches through the proxy, but never the on-device HLS proxy (loopback),
            // otherwise YouTube's localhost:7000/master.m3u8 hop would be sent to the proxy too.
            val proxy = Proxy(
                if (cfg.socks) Proxy.Type.SOCKS else Proxy.Type.HTTP,
                InetSocketAddress(cfg.host, cfg.port)
            )
            val selector = object : ProxySelector() {
                override fun select(uri: URI?): MutableList<Proxy> {
                    val host = uri?.host
                    return if (host == null || host == "localhost" || host == "127.0.0.1" || host == "::1")
                        mutableListOf(Proxy.NO_PROXY) else mutableListOf(proxy)
                }
                override fun connectFailed(uri: URI?, sa: SocketAddress?, e: IOException?) {}
            }
            val client = OkHttpClient.Builder()
                .proxySelector(selector)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            val dsFactory = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(client))
            builder.setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory))
            log("HLS video proxy: ${if (cfg.socks) "socks" else "http"} ${cfg.host}:${cfg.port}")
        }
        val p = builder.build().apply { addListener(listener) }
        player = p
        _playerFlow.value = p
        return p
    }

    /** Snapshot player state into the volatile cache. Main thread only. */
    private fun snapshot() {
        val p = player
        if (p == null) {
            positionSec = -1.0
            return
        }
        val durMs = p.duration
        durationSec = if (durMs == C.TIME_UNSET || durMs < 0) 0.0 else durMs / 1000.0
        val state = p.playbackState
        ready = state == Player.STATE_READY || state == Player.STATE_ENDED
        // position stays -1 until the player has a real timeline, so the client keeps showing "loading"
        positionSec = if (ready && durationSec > 0) (p.currentPosition / 1000.0).coerceAtLeast(0.0) else -1.0
        rate = if (p.isPlaying) (p.playbackParameters.speed.takeIf { it > 0f } ?: 1f) else 0f
        bufferEmpty = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
        bufferFull = p.bufferedPercentage >= 100
        likelyKeepUp = state == Player.STATE_READY
    }

    fun play(location: String, startPositionSec: Float) {
        main.post {
            log("HLS play: $location @ ${startPositionSec}s")
            finished = false
            ready = false
            positionSec = -1.0
            val p = ensurePlayer()
            // Cap selection by height. At <=1080p also prefer H.264 (most reliable); above that,
            // allow any codec (YouTube 4K is VP9/AV1 only) at the cost of TextureView GPU load.
            val tp = p.trackSelectionParameters.buildUpon()
            if (maxVideoHeight in 1..4320) tp.setMaxVideoSize(Int.MAX_VALUE, maxVideoHeight)
            else tp.clearVideoSizeConstraints()
            tp.setPreferredVideoMimeType(if (maxVideoHeight in 1..1080) MimeTypes.VIDEO_H264 else null)
            p.trackSelectionParameters = tp.build()
            p.setMediaItem(MediaItem.fromUri(location))
            if (startPositionSec > 0f) p.seekTo((startPositionSec * 1000).toLong())
            p.playWhenReady = true
            p.prepare()
            if (!active) {
                active = true
                onActiveChanged?.invoke(true)
                main.post(poller)
            }
        }
    }

    fun scrub(positionSec: Float) {
        main.post { player?.seekTo((positionSec * 1000).toLong().coerceAtLeast(0)) }
    }

    /** AirPlay rate: 0 = pause, >0 = play (and playback speed). */
    fun setRate(rate: Float) {
        main.post {
            val p = player ?: return@post
            if (rate <= 0f) {
                p.playWhenReady = false
            } else {
                p.playbackParameters = PlaybackParameters(rate)
                p.playWhenReady = true
            }
        }
    }

    fun stop() {
        main.post {
            if (!active && player == null) return@post
            log("HLS stop")
            active = false
            finished = false
            ready = false
            positionSec = -1.0
            main.removeCallbacks(poller)
            _playerFlow.value = null
            player?.let {
                it.removeListener(listener)
                it.stop()
                it.release()
            }
            player = null
            onActiveChanged?.invoke(false)
        }
    }

    fun release() = stop()

    /**
     * Returns the 9-element state vector the native /playback-info handler expects.
     * Called synchronously from a native http thread — reads volatile fields only.
     */
    fun playbackInfo(): DoubleArray {
        if (!active) return doubleArrayOf(0.0, -1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0)
        val dur = if (finished) -1.0 else durationSec
        return doubleArrayOf(
            dur,                                 // duration (-1 => finished)
            if (finished) 0.0 else positionSec,  // position (-1 => not ready)
            rate.toDouble(),
            if (ready) 1.0 else 0.0,
            if (bufferEmpty) 1.0 else 0.0,
            if (bufferFull) 1.0 else 0.0,
            if (likelyKeepUp) 1.0 else 0.0,
            0.0,                                 // seekStart
            durationSec                          // seekDuration (whole timeline seekable)
        )
    }

    /** Resume position (seconds) reported when a playlist entry is removed. */
    fun playlistRemovePosition(): Float =
        if (positionSec > 0) positionSec.toFloat() else 0f
}
