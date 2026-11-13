package io.github.seancheung.airplayer.music

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import kotlin.concurrent.thread

internal interface MusicSink : Closeable {
    fun play(alac: ByteArray)
    fun volume(db: Float)
}

internal class MusicStream(private val peer: InetAddress, private val sink: MusicSink) : Closeable {
    val data = DatagramSocket()
    val control = DatagramSocket()
    private val timing = DatagramSocket()
    val timingPort get() = timing.localPort
    private val workers = mutableListOf<Thread>()
    private data class Frame(val sequence: Int, val stamp: Int, val bytes: ByteArray)
    private val frames = ArrayBlockingQueue<Frame>(512)
    private data class Anchor(val stamp: Int, val millis: Long)
    @Volatile private var anchor: Anchor? = null
    @Volatile private var offsetMs = 0L

    private fun worker(name: String, fn: () -> Unit) {
        workers += thread(name = "AirPlay2-$name", isDaemon = true) {
            try { fn() } catch (_: java.net.SocketException) {
                if (!timing.isClosed) throw IllegalStateException("Music socket closed unexpectedly")
            } catch (_: InterruptedException) { }
        }
    }

    fun startClock(port: Int) {
        timing.connect(peer, port)
        timing.soTimeout = 1000
        worker("timing") {
            var seq = 0
            while (!timing.isClosed) {
                val now = System.currentTimeMillis()
                val request = ByteBuffer.allocate(32).put(0x80.toByte()).put(0xd2.toByte()).putShort((seq++).toShort())
                request.putLong(24, ((now / 1000 + 2208988800L) shl 32) or (((now % 1000) shl 32) / 1000))
                timing.send(DatagramPacket(request.array(), 32))
                val reply = DatagramPacket(ByteArray(32), 32)
                try {
                    timing.receive(reply)
                    val buf = ByteBuffer.wrap(reply.data)
                    if (reply.length == 32 && buf.get(1) == 0xd3.toByte() && buf.getLong(8) == request.getLong(24)) {
                        offsetMs = ((now - ntpMillis(buf.getLong(16))) +
                            (System.currentTimeMillis() - ntpMillis(buf.getLong(24)))) / 2
                    }
                } catch (_: SocketTimeoutException) { }
                Thread.sleep(2000)
            }
        }
    }

    fun startAudio(key: ByteArray, dataPort: Int, controlPort: Int) {
        data.connect(peer, dataPort)
        control.connect(peer, controlPort)
        worker("sync") {
            val p = DatagramPacket(ByteArray(64), 64)
            while (!control.isClosed) {
                p.length = p.data.size
                control.receive(p)
                if (p.length == 20 && p.data[1] == 0xd4.toByte()) {
                    val b = ByteBuffer.wrap(p.data)
                    anchor = Anchor(b.getInt(4), ntpMillis(b.getLong(8)) + offsetMs)
                }
            }
        }
        worker("receive") {
            val packet = DatagramPacket(ByteArray(4096), 4096)
            while (!data.isClosed) {
                packet.length = packet.data.size
                data.receive(packet)
                val b = packet.data
                val n = packet.length
                if (n < 36 || b[0] != 0x80.toByte() || b[1].toInt() and 127 != 96) continue
                val plain = try {
                    crypt(Cipher.DECRYPT_MODE, key, ByteArray(4) + b.copyOfRange(n - 8, n),
                        b.copyOfRange(4, 12), b.copyOfRange(12, n - 8))
                } catch (_: AEADBadTagException) { continue }
                val header = ByteBuffer.wrap(b)
                frames.offer(Frame(header.getShort(2).toInt() and 65535, header.getInt(4), plain))
            }
        }
        worker("play") {
            val pending = mutableMapOf<Int, Frame>()
            var next = -1
            while (!data.isClosed) {
                val frame = frames.poll(16, TimeUnit.MILLISECONDS)
                if (frame != null) {
                    if (next < 0) next = frame.sequence
                    if ((frame.sequence - next) and 65535 < 512) pending[frame.sequence] = frame
                    else if (pending.isEmpty() && (frame.sequence - next) and 65535 < 32768) {
                        next = frame.sequence
                        pending[next] = frame
                    }
                }
                while (pending.isNotEmpty()) {
                    val head = pending.remove(next)
                    if (head == null && frame != null && pending.size < 4) break
                    next = (next + 1) and 65535
                    if (head != null) {
                        anchor?.let {
                            val due = it.millis + (head.stamp - it.stamp).toLong() * 1000 / 44100
                            val delay = due - System.currentTimeMillis()
                            if (delay in 1..2000) Thread.sleep(delay)
                        }
                        sink.play(head.bytes)
                    }
                }
            }
        }
    }

    override fun close() {
        timing.close()
        control.close()
        data.close()
        workers.forEach(Thread::interrupt)
        workers.forEach(Thread::join)
        sink.close()
    }

    private fun ntpMillis(t: Long) = ((t ushr 32) - 2208988800L) * 1000 + ((t and 0xffffffffL) * 1000 ushr 32)
}
