package io.github.seancheung.airplayer.music

import com.dd.plist.*
import java.io.Closeable
import java.io.EOFException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class MusicReceiver(
    private val name: String,
    private val id: String,
    private val newSink: () -> MusicSink,
    private val log: (String) -> Unit
) : Closeable {
    private val listener = ServerSocket(0, 4)
    private val clients = mutableSetOf<Socket>()
    private val playing = AtomicBoolean()
    @Volatile private var volume = 0f
    val port get() = listener.localPort
    val records = mapOf("deviceid" to id, "features" to "0x200,0x40", "model" to "AirPlayer",
        "srcvers" to "366.0", "protovers" to "1.1", "flags" to "0x4", "pw" to "false")

    fun start() {
        thread(name = "AirPlay2-listen", isDaemon = true) {
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (e: java.io.IOException) {
                    if (!listener.isClosed) log("AirPlay 2 listener: ${e.message}")
                    break
                }
                synchronized(clients) {
                    if (listener.isClosed || clients.size >= 4) socket.close() else {
                        clients.add(socket)
                        thread(name = "AirPlay2-control", isDaemon = true) {
                            try { socket.use(::session) }
                            catch (_: EOFException) { }
                            catch (e: Exception) { if (!listener.isClosed) log("AirPlay 2 session: ${e.message}") }
                            finally { synchronized(clients) { clients.remove(socket) } }
                        }
                    }
                }
            }
        }
    }

    private fun session(socket: Socket) {
        socket.soTimeout = 15000
        val wire = MusicWire(socket)
        var pairing: PairSetup? = null
        var authenticated = false
        var stream: MusicStream? = null
        var sink: MusicSink? = null
        var clockReady = false
        var ownsOutput = false
        var audioReady = false
        try {
            while (!socket.isClosed) {
                val r = wire.request()
                when {
                    r.method == "GET" && r.path == "/info" -> wire.reply(r, plist(
                        "deviceID" to id, "name" to name, "model" to "AirPlayer",
                        "features" to ((1L shl 38) or 512), "sourceVersion" to "366.0", "initialVolume" to volume.toDouble()))
                    !authenticated && r.method == "POST" && r.path == "/pair-setup" -> {
                        val fields = readTlv(r.body)
                        when (fields[6]?.singleOrNull()?.toInt()) {
                            1 -> {
                                require(fields[19]?.firstOrNull() == 16.toByte() && pairing == null)
                                pairing = PairSetup()
                                wire.reply(r, pairing.challenge(), "application/octet-stream")
                            }
                            3 -> {
                                val (reply, secret) = requireNotNull(pairing).finish(fields)
                                wire.reply(r, reply, "application/octet-stream")
                                wire.secure(secret)
                                authenticated = true
                                pairing = null
                            }
                            else -> error("Unexpected pair-setup state")
                        }
                    }
                    !authenticated -> { wire.reply(r, status = "403 Forbidden"); return }
                    r.method == "SETUP" -> {
                        val req = BinaryPropertyListParser.parse(r.body) as NSDictionary
                        val streams = req.objectForKey("streams") as? NSArray
                        if (streams == null) {
                            require(!clockReady && req.objectForKey("timingProtocol").toString() == "NTP")
                            val timingPort = req.port("timingPort")
                            if (!playing.compareAndSet(false, true)) {
                                wire.reply(r, status = "453 Not Enough Bandwidth")
                                return
                            }
                            ownsOutput = true
                            sink = newSink()
                            sink.volume(volume)
                            stream = MusicStream(socket.inetAddress, sink)
                            stream.startClock(timingPort)
                            clockReady = true
                            wire.reply(r, plist("timingPort" to stream.timingPort))
                        } else {
                            require(clockReady && !audioReady && streams.count() == 1)
                            val format = streams.objectAtIndex(0) as NSDictionary
                            require(format.num("type") == 96 && format.num("ct") == 2 &&
                                format.num("audioFormat") == 0x40000 && format.num("sr") == 44100 && format.num("spf") == 352)
                            val key = (format.objectForKey("shk") as NSData).bytes()
                            require(key.size == 32)
                            val receiver = requireNotNull(stream)
                            receiver.startAudio(key, format.port("dataPort"), format.port("controlPort"))
                            audioReady = true
                            val result = dictionary("type" to 96, "dataPort" to receiver.data.localPort,
                                "controlPort" to receiver.control.localPort)
                            wire.reply(r, plist("streams" to NSArray(result)))
                        }
                    }
                    r.method == "RECORD" && clockReady -> wire.reply(r)
                    r.method == "POST" && r.path == "/feedback" -> wire.reply(r)
                    r.method == "GET_PARAMETER" -> wire.reply(r, "volume: $volume\r\n".toByteArray(), "text/parameters")
                    r.method == "SET_PARAMETER" -> {
                        if (r.headers["content-type"] == "text/parameters") {
                            r.body.decodeToString().lineSequence().firstOrNull { it.startsWith("volume:") }?.let {
                                val db = it.substringAfter(':').trim().toFloat()
                                require(db in -144f..0f)
                                volume = db
                                sink?.volume(db)
                            }
                        }
                        wire.reply(r)
                    }
                    r.method == "TEARDOWN" -> { wire.reply(r); return }
                    else -> wire.reply(r, status = "501 Not Implemented")
                }
            }
        } finally {
            try { if (stream != null) stream.close() else sink?.close() }
            finally { if (ownsOutput) playing.set(false) }
        }
    }

    override fun close() {
        listener.close()
        synchronized(clients) { clients.forEach(Socket::close) }
    }

    private fun NSDictionary.num(key: String) = (objectForKey(key) as NSNumber).intValue()
    private fun NSDictionary.port(key: String) = num(key).also { require(it in 1..65535) }
    private fun dictionary(vararg items: Pair<String, Any>) = NSDictionary().apply {
        items.forEach { (key, value) -> put(key, value) }
    }
    private fun plist(vararg items: Pair<String, Any>) = BinaryPropertyListWriter.writeToArray(dictionary(*items))
}
