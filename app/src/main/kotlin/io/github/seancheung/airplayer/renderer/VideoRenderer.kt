package io.github.seancheung.airplayer.renderer

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

class VideoRenderer {

    private val lock = Object()
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var currentH265 = false
    @Volatile private var running = false
    private var videoWidth = 0
    private var videoHeight = 0

    // cache last keyframe so decoder can bootstrap after late surface attach
    private var cachedKeyframe: ByteArray? = null
    private var cachedKeyframePts: Long = 0
    private var cachedKeyframeH265 = false
    // cached parameter sets (SPS/PPS[/VPS]) supplied as csd-0 at configure time;
    // some hardware decoders (e.g. MediaTek) never emit output without it
    private var cachedCsd: ByteArray? = null
    // hardware-decoder-first with software fallback. Some hardware decoders (MediaTek)
    // accept input but never emit output; once detected we switch to software and
    // remember it for the rest of the app session (hwDecoderFailed is not reset on release).
    private var hwDecoderFailed = false
    private var usingSoftwareDecoder = false
    private var codecStartFrame = 0L

    // stats
    @Volatile var fps = 0; private set
    @Volatile var bitrateBps = 0L; private set
    @Volatile var frameCount = 0L; private set
    @Volatile var codecName = ""; private set
    @Volatile var droppedFrames = 0L; private set
    @Volatile var framePacingJitterUs = 0L; private set

    var enforceSdr = true
    var keyAllowFrameDrop = true
    var realtimeDecoderPriority = true
    var operatingRateHint = false
    var scheduledOutputBufferRelease = true
    var decoderMode = "auto" // "auto" (hardware-first + fallback) | "hardware" | "software"
    var benchmarkLog = false
    var benchmarkLogCallback: ((String) -> Unit)? = null
    private var _framesThisSec = 0
    private var _bytesThisSec = 0L
    private var _lastStatReset = 0L
    private val _frameIntervalsNs = LongArray(120)
    private var _frameIntervalIdx = 0
    private var _frameIntervalCount = 0
    private var _lastOutputFrameNs = 0L
    // anchors that map decoder PTS (us) to System.nanoTime() for scheduled rendering
    private var _ptsBaseUs = Long.MIN_VALUE
    private var _wallBaseNs = 0L
    // output-side diagnostics
    private var _renderedTotal = 0L
    private var _lastRenderLogMs = 0L

    fun setResolution(w: Int, h: Int) {
        videoWidth = w
        videoHeight = h
    }

    fun setSurface(surface: Surface?) = synchronized(lock) {
        val changed = this.surface !== surface
        this.surface = surface
        if (changed && codec != null) stopCodec()
    }

    private fun _updateStats(size: Int) {
        val now = System.currentTimeMillis()
        if (now - _lastStatReset >= 1000) {
            fps = _framesThisSec
            bitrateBps = _bytesThisSec * 8
            framePacingJitterUs = _computeFramePacingJitterUs()
            _framesThisSec = 0
            _bytesThisSec = 0
            _lastStatReset = now
            if (benchmarkLog) _emitBenchmarkLine()
        }
        _framesThisSec++
        _bytesThisSec += size
        frameCount++
    }

    private fun _emitBenchmarkLine() {
        val msg = "fps=$fps bitrate=${bitrateBps / 1000}kbps " +
            "jitter=${framePacingJitterUs}us frames=$frameCount " +
            "dropped=$droppedFrames codec=$codecName " +
            "res=${videoWidth}x${videoHeight}"
        Log.i(BENCH_TAG, msg)
        benchmarkLogCallback?.invoke(msg)
    }

    fun feedFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        _updateStats(data.size)

        // always cache keyframes, even without a surface
        if (_isKeyframe(data, isH265)) {
            cachedKeyframe = data.copyOf()
            cachedKeyframePts = ntpTimeNs
            cachedKeyframeH265 = isH265
            _extractCsd(data, isH265)?.let { cachedCsd = it }
        }

        synchronized(lock) {
            if (surface == null) return

            // (re)start the decoder when needed
            if (codec == null || isH265 != currentH265) {
                _restartCodec(isH265)
            }

            // Hardware-decoder watchdog: if the HW decoder has consumed a batch of frames
            // without ever emitting output (MediaTek black-screen bug), fall back to a
            // software decoder for the rest of this session.
            if (decoderMode == "auto" && !usingSoftwareDecoder && !hwDecoderFailed &&
                _renderedTotal == 0L && frameCount - codecStartFrame >= 30) {
                Log.w(TAG, "hardware decoder emitted no output after ${frameCount - codecStartFrame} frames — falling back to software")
                hwDecoderFailed = true
                _restartCodec(isH265)
            }

            _feedToCodec(data, ntpTimeNs)
            drainOutput()
        }
    }

    // (re)create the decoder and prime it with the most recent keyframe so it can resume
    private fun _restartCodec(isH265: Boolean) {
        stopCodec()
        startCodec(isH265)
        cachedKeyframe?.let { kf ->
            if (cachedKeyframeH265 == isH265) {
                _feedToCodec(kf, cachedKeyframePts)
            }
        }
    }

    private fun _feedToCodec(data: ByteArray, ntpTimeNs: Long) {
        val c = codec ?: return
        var idx = c.dequeueInputBuffer(10000)
        if (idx < 0) {
            // Free buffers by draining output, then wait longer. Dropping an input frame
            // loses a reference frame and causes macroblocking/ghosting until the next
            // keyframe, so we try hard to avoid it.
            drainOutput()
            idx = c.dequeueInputBuffer(40000)
        }
        if (idx >= 0) {
            val buf = c.getInputBuffer(idx) ?: return
            buf.clear()
            buf.put(data)
            c.queueInputBuffer(idx, 0, data.size, ntpTimeNs / 1000, 0)
        } else {
            droppedFrames++
            Log.w(TAG, "Decoder input queue full; dropping frame. drops=$droppedFrames")
        }
    }

    private fun _isKeyframe(data: ByteArray, isH265: Boolean): Boolean {
        if (data.size < 5) return false
        var i = 0
        while (i <= data.size - 5) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                return if (isH265) {
                    val type = (data[i + 4].toInt() shr 1) and 0x3F
                    type == 19 || type == 20 || type == 32 || type == 33
                } else {
                    val type = data[i + 4].toInt() and 0x1F
                    type == 5 || type == 7
                }
            }
            i++
        }
        return false
    }

    // extract the parameter-set prefix (everything before the first VCL NAL) from a
    // keyframe, to be handed to the decoder as csd-0
    private fun _extractCsd(data: ByteArray, isH265: Boolean): ByteArray? {
        var i = 0
        while (i <= data.size - 5) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                val nb = data[i + 4].toInt()
                val type = if (isH265) (nb shr 1) and 0x3F else nb and 0x1F
                val isVcl = if (isH265) type in 0..31 else type in 1..5
                if (isVcl) return if (i > 0) data.copyOfRange(0, i) else null
                i += 4
            } else i++
        }
        return null
    }

    // diagnostic: list NAL unit types found in an Annex-B buffer (4-byte start codes)
    private fun _nalTypes(data: ByteArray, isH265: Boolean): String {
        val types = ArrayList<Int>()
        var i = 0
        while (i <= data.size - 5) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                val nb = data[i + 4].toInt()
                types.add(if (isH265) (nb shr 1) and 0x3F else nb and 0x1F)
                i += 4
            } else i++
        }
        return types.joinToString(",")
    }

    // Find a software decoder for the given mime. Many MediaTek hardware decoders
    // accept input but never emit output to a Surface (known black-screen bug), so we
    // fall back to the Google software decoder which works across devices.
    private fun _findSoftwareDecoder(mime: String): String? {
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
            if (android.os.Build.VERSION.SDK_INT >= 29 && info.isSoftwareOnly) return info.name
            val n = info.name.lowercase()
            if (n.contains("c2.android") || n.contains("omx.google") || n.contains(".sw.")) return info.name
        }
        return null
    }

    private fun startCodec(h265: Boolean) {
        val s = surface ?: return
        currentH265 = h265
        val mime = if (h265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        // MediaTek decoders stall on unaligned dimensions — round up to a multiple of 16
        val alignedW = if (videoWidth > 0) ((videoWidth + 15) / 16) * 16 else 1920
        val alignedH = if (videoHeight > 0) ((videoHeight + 15) / 16) * 16 else 1080
        val format = MediaFormat.createVideoFormat(mime, alignedW, alignedH)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
        // supply SPS/PPS as csd-0 so the decoder can configure its output port up front
        cachedCsd?.let {
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(it))
            Log.i(TAG, "configure with csd-0 size=${it.size} nalTypes=[${_nalTypes(it, h265)}]")
        }
        // Minimal configuration: several MediaTek decoders silently stall (never emit
        // output) when color/priority/operating-rate/frame-drop keys are set. Omit them.

        // Decoder selection: explicit user choice, or "auto" = hardware-first with a
        // watchdog that flips hwDecoderFailed when the HW decoder emits no output.
        val useSoftware = when (decoderMode) {
            "software" -> true
            "hardware" -> false
            else -> hwDecoderFailed
        }
        val swDecoder = if (useSoftware) _findSoftwareDecoder(mime) else null
        codec = (if (swDecoder != null) MediaCodec.createByCodecName(swDecoder)
                 else MediaCodec.createDecoderByType(mime)).also {
            it.configure(format, s, null, 0)
            it.start()
        }
        usingSoftwareDecoder = swDecoder != null
        codecStartFrame = frameCount
        codecName = if (h265) "H.265" else "H.264"
        running = true
        Log.i(TAG, "Video codec started: $mime ${alignedW}x${alignedH} decoder=${codec?.name} sw=$usingSoftwareDecoder")
    }

    private fun stopCodec() {
        running = false
        _frameIntervalIdx = 0
        _frameIntervalCount = 0
        _lastOutputFrameNs = 0L
        _ptsBaseUs = Long.MIN_VALUE
        _wallBaseNs = 0L
        codec?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        codec = null
    }

    private fun drainOutput() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        var gotOutput = false
        var lastNeg = 0
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            when {
                idx >= 0 -> {
                    gotOutput = true
                    _recordOutputFrameTime()
                    _renderedTotal++
                    // render immediately. PTS-based scheduling parked every frame after
                    // the first one (observed first-frame-freeze), so bypass it for now.
                    c.releaseOutputBuffer(idx, true)
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - _lastRenderLogMs >= 1000) {
                        _lastRenderLogMs = nowMs
                        Log.i(TAG, "rendered total=$_renderedTotal ptsUs=${info.presentationTimeUs} size=${info.size} flags=${info.flags}")
                    }
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.i(TAG, "Output format changed: ${c.outputFormat}")
                }
                else -> { lastNeg = idx; break }
            }
        }
        if (!gotOutput && _renderedTotal == 0L) {
            val nowMs = System.currentTimeMillis()
            if (nowMs - _lastRenderLogMs >= 1000) {
                _lastRenderLogMs = nowMs
                Log.w(TAG, "no output yet: inputsFed=$frameCount lastDequeue=$lastNeg dropped=$droppedFrames")
            }
        }
    }

    fun release() = synchronized(lock) {
        stopCodec()
        cachedKeyframe = null
        fps = 0; bitrateBps = 0; frameCount = 0; codecName = ""
        droppedFrames = 0; framePacingJitterUs = 0
        _framesThisSec = 0; _bytesThisSec = 0
        _frameIntervalIdx = 0; _frameIntervalCount = 0; _lastOutputFrameNs = 0L
        // per-connection decoder state (hwDecoderFailed is intentionally kept — it is a
        // device characteristic that persists across reconnects)
        _renderedTotal = 0L; codecStartFrame = 0L; usingSoftwareDecoder = false
        _lastRenderLogMs = 0L
    }

    private fun _recordOutputFrameTime() {
        val now = System.nanoTime()
        if (_lastOutputFrameNs > 0) {
            _frameIntervalsNs[_frameIntervalIdx % _frameIntervalsNs.size] = now - _lastOutputFrameNs
            _frameIntervalIdx++
            _frameIntervalCount++
        }
        _lastOutputFrameNs = now
    }

    private fun _computeFramePacingJitterUs(): Long {
        val count = _frameIntervalCount.coerceAtMost(_frameIntervalsNs.size)
        if (count < 2) return 0

        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until count) {
            val interval = _frameIntervalsNs[i].toDouble()
            sum += interval
            sumSq += interval * interval
        }
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return (kotlin.math.sqrt(variance.coerceAtLeast(0.0)) / 1000.0).toLong()
    }

    companion object {
        private const val TAG = "VideoRenderer"
        private const val BENCH_TAG = "BENCHMARK"

        fun supportsH265(): Boolean {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            return list.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any {
                    it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
                }
            }
        }
    }
}
