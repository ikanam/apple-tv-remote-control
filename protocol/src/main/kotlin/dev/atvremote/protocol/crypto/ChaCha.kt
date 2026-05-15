package dev.atvremote.protocol.crypto
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.*
class ChaCha(private val outKey: ByteArray, private val inKey: ByteArray) {
    private var outCtr = 0L; private var inCtr = 0L
    private fun pad12(n: ByteArray) = ByteArray(12 - n.size) + n
    // pyatv/tvOS Companion CONNECTION nonce = base Chacha20Cipher with
    // nonce_length=12: `counter.to_bytes(12, "little")` — the 64-bit counter
    // little-endian in bytes 0..7, bytes 8..11 zero (NO 4-byte leading pad;
    // that pad belongs to the Chacha20Cipher8byteNonce subclass used only for
    // pair-verify PV-Msg, not the connection).
    private fun ctrNonce(c: Long): ByteArray { val b = ByteArray(12); for (i in 0..7) b[i] = ((c shr (8*i)) and 0xFF).toByte(); return b }
    private fun run(enc: Boolean, key: ByteArray, nonce: ByteArray, data: ByteArray, aad: ByteArray?): ByteArray {
        val e = ChaCha20Poly1305(); e.init(enc, ParametersWithIV(KeyParameter(key), nonce))
        if (aad != null) e.processAADBytes(aad, 0, aad.size)
        val out = ByteArray(e.getOutputSize(data.size)); var off = e.processBytes(data, 0, data.size, out, 0); e.doFinal(out, off); return out
    }
    /** Fixed explicit nonce (pair-setup/verify), left zero-padded to 12. */
    fun encryptFixed(nonce: ByteArray, pt: ByteArray) = run(true, outKey, pad12(nonce), pt, null)
    fun decryptFixed(nonce: ByteArray, ct: ByteArray) = run(false, inKey, pad12(nonce), ct, null)
    /** Connection mode: per-direction LE counter nonce, AAD = frame header. */
    fun encryptOut(pt: ByteArray, aad: ByteArray) = run(true, outKey, ctrNonce(outCtr++), pt, aad)
    fun decryptIn(ct: ByteArray, aad: ByteArray) = run(false, inKey, ctrNonce(inCtr++), ct, aad)
}
