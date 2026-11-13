package io.github.seancheung.airplayer.music

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// HAP transient pair-setup, RFC 5054 3072-bit group and SHA-512.
internal class PairSetup {
    private val random = SecureRandom()
    private val salt = ByteArray(16).also(random::nextBytes)
    private val b = BigInteger(256, random)
    private val verifier = g.modPow(integer(hash(salt, hash("Pair-Setup:3939".toByteArray()))), n)
    private val public = (integer(hash(pad(n), pad(g))) * verifier + g.modPow(b, n)).mod(n)

    fun challenge() = tlv(6 to byteArrayOf(2), 2 to salt, 3 to pad(public))

    fun finish(fields: Map<Int, ByteArray>): Pair<ByteArray, ByteArray> {
        val client = fields.getValue(3)
        require(client.size == 384)
        val a = integer(client)
        require(a.signum() > 0 && a < n)
        val u = integer(hash(client, pad(public)))
        require(u.signum() > 0)
        val shared = (a * verifier.modPow(u, n)).modPow(b, n)
        val key = hash(unsigned(shared))
        val hn = hash(unsigned(n))
        val hg = hash(unsigned(g))
        for (i in hn.indices) hn[i] = (hn[i].toInt() xor hg[i].toInt()).toByte()
        val proof = hash(hn, hash("Pair-Setup".toByteArray()), salt, unsigned(a), unsigned(public), key)
        require(MessageDigest.isEqual(proof, fields[4]))
        return tlv(6 to byteArrayOf(4), 4 to hash(client, proof, key)) to key
    }

    companion object {
        private val n = BigInteger("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF", 16)
        private val g = BigInteger.valueOf(5)
        private fun integer(b: ByteArray) = BigInteger(1, b)
        private fun unsigned(v: BigInteger) = v.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        private fun pad(v: BigInteger) = unsigned(v).let { ByteArray(384 - it.size) + it }
        private fun hash(vararg parts: ByteArray) = MessageDigest.getInstance("SHA-512").run {
            parts.forEach(::update)
            digest()
        }
    }
}

internal fun tlv(vararg fields: Pair<Int, ByteArray>) = java.io.ByteArrayOutputStream().apply {
    for ((tag, value) in fields) {
        var offset = 0
        while (offset < value.size) {
            val length = minOf(255, value.size - offset)
            write(tag)
            write(length)
            write(value, offset, length)
            offset += length
        }
    }
}.toByteArray()

internal fun readTlv(bytes: ByteArray): Map<Int, ByteArray> {
    require(bytes.size <= 1024)
    val fields = mutableMapOf<Int, ByteArray>()
    var offset = 0
    while (offset < bytes.size) {
        val tag = bytes[offset++].toInt() and 255
        val size = bytes[offset++].toInt() and 255
        require(offset + size <= bytes.size)
        fields[tag] = (fields[tag] ?: byteArrayOf()) + bytes.copyOfRange(offset, offset + size)
        offset += size
    }
    return fields
}

internal fun controlKey(secret: ByteArray, direction: String): ByteArray {
    fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA512").run {
        init(SecretKeySpec(key, algorithm))
        doFinal(data)
    }
    val prk = hmac("Control-Salt".toByteArray(), secret)
    return hmac(prk, "Control-${direction}-Encryption-Key".toByteArray() + 1.toByte()).copyOf(32)
}
