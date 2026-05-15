package dev.atvremote.protocol.crypto
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
object Hkdf {
    fun expand(salt: String, info: String, ikm: ByteArray, len: Int = 32): ByteArray {
        val g = HKDFBytesGenerator(SHA512Digest())
        g.init(HKDFParameters(ikm, salt.toByteArray(), info.toByteArray()))
        return ByteArray(len).also { g.generateBytes(it, 0, len) }
    }
}
