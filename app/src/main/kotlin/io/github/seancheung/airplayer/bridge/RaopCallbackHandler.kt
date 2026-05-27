package io.github.seancheung.airplayer.bridge

interface RaopCallbackHandler {
    fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean)
    fun onAudioData(data: ByteArray, ct: Int, ntpTimeNs: Long, seqNum: Int)
    fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean)
    fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float)
    fun onVolumeChange(volume: Float)
    fun onConnectionInit()
    fun onConnectionDestroy()
    fun onConnectionReset(reason: Int)
    fun onDisplayPin(pin: String)
    fun onMetadata(data: ByteArray)
    fun onCoverArt(data: ByteArray)
    fun onProgress(start: Long, curr: Long, end: Long)
    fun onDacpId(dacpId: String, activeRemote: String)
    fun onAudioOnly(audioOnly: Boolean)

    // AirPlay video (HLS) playback. location is a local http://localhost:<port>/master.m3u8
    // URL served & proxied by the native httpd; feed it straight to ExoPlayer.
    fun onVideoPlay(location: String, startPosition: Float)
    fun onVideoScrub(position: Float)
    fun onVideoRate(rate: Float)
    fun onVideoStop()

    // Called synchronously from a native http thread while answering GET /playback-info.
    // Must return a 9-element array read from a thread-safe cache (never touch ExoPlayer here):
    // [duration, position, rate, ready, bufEmpty, bufFull, likelyKeepUp, seekStart, seekDuration].
    // duration == -1.0 -> finished; position == -1.0 -> not ready yet.
    fun onVideoPlaybackInfo(): DoubleArray

    // Returns the resume position (seconds) when a playlist item is removed.
    fun onVideoPlaylistRemove(): Float

    // AirPlay photo (PUT /photo). data is JPEG bytes (empty for displayCached). action is
    // X-Apple-AssetAction ("cacheOnly" = preload only, "displayCached" = show cached assetKey,
    // empty = display now); transition is the X-Apple-Transition effect.
    fun onPhoto(data: ByteArray, assetKey: String, action: String, transition: String)
}
