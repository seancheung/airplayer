package io.github.seancheung.airplayer.music

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private val cipherTransform = try {
    Cipher.getInstance("ChaCha20/Poly1305/NoPadding").algorithm
} catch (_: java.security.NoSuchAlgorithmException) {
    "ChaCha20-Poly1305"
}

internal fun crypt(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, body: ByteArray): ByteArray =
    Cipher.getInstance(cipherTransform).run {
        init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        updateAAD(aad)
        doFinal(body)
    }

private fun nonce(counter: Long) = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putLong(counter).array()

internal data class MusicRequest(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

internal class MusicWire(socket: Socket) {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = socket.getOutputStream()
    private var reader = input
    private var writeKey: ByteArray? = null
    private var written = 0L

    fun secure(secret: ByteArray) {
        val readKey = controlKey(secret, "Write")
        writeKey = controlKey(secret, "Read")
        reader = DataInputStream(object : InputStream() {
            var frame = byteArrayOf()
            var at = 0
            var count = 0L
            private fun refill() {
                if (at < frame.size) return
                val header = ByteArray(2).also(input::readFully)
                val size = (header[0].toInt() and 255) + ((header[1].toInt() and 255) shl 8)
                require(size in 1..1024)
                frame = crypt(Cipher.DECRYPT_MODE, readKey, nonce(count++), header, ByteArray(size + 16).also(input::readFully))
                at = 0
            }
            override fun read(): Int {
                refill()
                return frame[at++].toInt() and 255
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                refill()
                val n = minOf(len, frame.size - at)
                frame.copyInto(b, off, at, at + n)
                at += n
                return n
            }
        })
    }

    fun request(): MusicRequest {
        val bytes = ByteArrayOutputStream()
        var tail = 0
        while (tail != 0x0d0a0d0a) {
            require(bytes.size() < 16384)
            val byte = reader.readUnsignedByte()
            bytes.write(byte)
            tail = (tail shl 8) or byte
        }
        val lines = bytes.toString("US-ASCII").trimEnd().split("\r\n")
        val first = lines.first().split(' ')
        require(first.size == 3)
        val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val size = headers["content-length"]?.toInt() ?: 0
        require(size in 0..(1 shl 20))
        return MusicRequest(first[0], first[1], headers, ByteArray(size).also(reader::readFully))
    }

    fun reply(req: MusicRequest, body: ByteArray = byteArrayOf(), type: String = "application/x-apple-binary-plist", status: String = "200 OK") {
        val seq = req.headers["cseq"]?.toInt() ?: 0
        val bytes = "RTSP/1.0 $status\r\nCSeq: $seq\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body
        val key = writeKey
        if (key == null) output.write(bytes) else {
            var offset = 0
            while (offset < bytes.size) {
                val size = minOf(1024, bytes.size - offset)
                val header = byteArrayOf(size.toByte(), (size shr 8).toByte())
                output.write(header + crypt(Cipher.ENCRYPT_MODE, key, nonce(written++), header, bytes.copyOfRange(offset, offset + size)))
                offset += size
            }
        }
        output.flush()
    }
}
