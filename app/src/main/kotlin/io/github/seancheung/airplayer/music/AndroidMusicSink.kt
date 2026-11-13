package io.github.seancheung.airplayer.music

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import io.github.seancheung.airplayer.bridge.NativeBridge
import kotlin.math.pow

internal class AndroidMusicSink : MusicSink {
    private val track = AudioTrack(AudioManager.STREAM_MUSIC, 44100, AudioFormat.CHANNEL_OUT_STEREO,
        AudioFormat.ENCODING_PCM_16BIT, maxOf(4096, AudioTrack.getMinBufferSize(44100,
            AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)), AudioTrack.MODE_STREAM)
    private val decoder = NativeBridge.nativeAlacInit(352, 2, 16, 40, 10, 14)
    init {
        if (decoder == 0L || track.state != AudioTrack.STATE_INITIALIZED) {
            close()
            error("Could not initialize AirPlay 2 audio output")
        }
    }
    override fun play(alac: ByteArray) {
        val pcm = NativeBridge.nativeAlacDecode(decoder, alac) ?: return
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
        track.write(pcm, 0, pcm.size)
    }
    override fun volume(db: Float) { track.setVolume(if (db <= -144) 0f else 10.0.pow(db / 20.0).toFloat()) }
    override fun close() {
        track.release()
        if (decoder != 0L) NativeBridge.nativeAlacDestroy(decoder)
    }
}
