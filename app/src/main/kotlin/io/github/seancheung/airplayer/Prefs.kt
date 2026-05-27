package io.github.seancheung.airplayer

import android.media.MediaFormat

/** Centralized preference keys and defaults. */
object Prefs {
    const val NAME = "settings"

    const val SERVER_NAME = "server_name"; const val DEF_SERVER_NAME = "AirPlayer"
    const val SERVER_PORT = "server_port"; const val DEF_SERVER_PORT = 7000
    const val AUTO_START = "auto_start"; const val DEF_AUTO_START = true
    const val BOOT_AUTO_START = "boot_auto_start"; const val DEF_BOOT_AUTO_START = true
    const val H265_ENABLED = "h265_enabled"; const val DEF_H265_ENABLED = true
    const val HLS_VIDEO_ENABLED = "hls_video_enabled"; const val DEF_HLS_VIDEO_ENABLED = true
    // Max cast-video resolution: "1080" | "1440" | "2160" | "auto". 4K/auto lifts the H.264-only
    // preference (allows VP9/HEVC) but may stutter via TextureView on weaker SoCs.
    const val MAX_CAST_RESOLUTION = "max_cast_resolution"; const val DEF_MAX_CAST_RESOLUTION = "1080"
    // Proxy for cast video playback (e.g. so YouTube segments on googlevideo can be reached)
    const val PROXY_ENABLED = "proxy_enabled"; const val DEF_PROXY_ENABLED = false
    const val PROXY_TYPE = "proxy_type"; const val DEF_PROXY_TYPE = "http" // http | socks
    const val PROXY_HOST = "proxy_host"; const val DEF_PROXY_HOST = ""
    const val PROXY_PORT = "proxy_port"; const val DEF_PROXY_PORT = 1080
    const val VIDEO_DECODER = "video_decoder"; const val DEF_VIDEO_DECODER = "auto" // auto | hardware | software
    const val ENFORCE_SDR = "enforce_sdr"; const val DEF_ENFORCE_SDR = true
    val KEY_ALLOW_FRAME_DROP: String = MediaFormat.KEY_ALLOW_FRAME_DROP; const val DEF_KEY_ALLOW_FRAME_DROP = true
    val KEY_PRIORITY: String = MediaFormat.KEY_PRIORITY; const val DEF_KEY_PRIORITY = true
    val KEY_OPERATING_RATE: String = MediaFormat.KEY_OPERATING_RATE; const val DEF_KEY_OPERATING_RATE = false
    const val SCHEDULED_OUTPUT_BUFFER_RELEASE = "scheduled_output_buffer_release"; const val DEF_SCHEDULED_OUTPUT_BUFFER_RELEASE = true
    const val AUDIO_BUFFER_MULTIPLIER = "audio_buffer_multiplier"; const val DEF_AUDIO_BUFFER_MULTIPLIER = 4
    const val ALAC_ENABLED = "alac_enabled"; const val DEF_ALAC_ENABLED = false
    const val SW_ALAC_ENABLED = "sw_alac_enabled"; const val DEF_SW_ALAC_ENABLED = true
    const val AAC_ENABLED = "aac_enabled"; const val DEF_AAC_ENABLED = true
    const val RESOLUTION = "resolution"; const val DEF_RESOLUTION = "auto"
    const val MAX_FPS = "max_fps"; const val DEF_MAX_FPS = 60
    const val OVERSCANNED = "overscanned"; const val DEF_OVERSCANNED = false
    const val REQUIRE_PIN = "require_pin"; const val DEF_REQUIRE_PIN = false
    const val ALLOW_NEW_CONN = "allow_new_conn"; const val DEF_ALLOW_NEW_CONN = false
    const val AUDIO_LATENCY_MS = "audio_latency_ms"; const val DEF_AUDIO_LATENCY_MS = -1
    const val DEBUG_ENABLED = "debug_enabled"; const val DEF_DEBUG_ENABLED = false
    const val DEVELOPER_OPTIONS = "developer_options"; const val DEF_DEVELOPER_OPTIONS = false
    const val BENCHMARK_LOG = "benchmark_log"; const val DEF_BENCHMARK_LOG = false
    const val IDLE_PREVIEW = "idle_preview"; const val DEF_IDLE_PREVIEW = false
    const val AUTO_FULLSCREEN = "auto_fullscreen"; const val DEF_AUTO_FULLSCREEN = true
    const val AUTO_AUDIO_MODE = "auto_audio_mode"; const val DEF_AUTO_AUDIO_MODE = true
    const val LAUNCH_ON_CONNECT = "launch_on_connect"; const val DEF_LAUNCH_ON_CONNECT = true
}
