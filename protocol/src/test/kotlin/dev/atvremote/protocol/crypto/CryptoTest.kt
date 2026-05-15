package dev.atvremote.protocol.crypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
private fun h(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
class CryptoTest {
    @Test fun ed25519SignVerifyRoundTrip() {
        val (sk, pk) = Curves.newEd25519()
        val sig = Curves.ed25519Sign(sk, "hello".toByteArray())
        assertTrue(Curves.ed25519Verify(pk, "hello".toByteArray(), sig))
    }
    @Test fun x25519RfcVector() {
        // RFC 7748 §6.1 Alice/Bob shared secret
        val aPriv = h("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bPub  = h("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        assertEquals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742",
            hex(Curves.x25519(aPriv, bPub)))
    }
    @Test fun hkdfSha512Length32() {
        assertEquals(32, Hkdf.expand("salt", "info", ByteArray(32) { 0x0b }).size)
    }
    @Test fun chacha8ByteNonceRoundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val c = ChaCha(key, key)
        val ct = c.encryptFixed("PS-Msg05".toByteArray(), "secret".toByteArray())
        assertTrue(c.decryptFixed("PS-Msg05".toByteArray(), ct).contentEquals("secret".toByteArray()))
    }

    /**
     * Bug C regression. The Companion *connection* ChaCha20-Poly1305 nonce
     * (pyatv `connection.py` uses the base `Chacha20Cipher(nonce_length=12)`)
     * must be `counter.to_bytes(12, "little")` — the 64-bit counter
     * little-endian in bytes 0..7, bytes 8..11 zero, NO 4-byte leading pad.
     * (The 4-byte-pad layout is the `Chacha20Cipher8byteNonce` subclass used
     * only for pair-verify PV-Msg, NOT the connection.)
     *
     * Counter 0 is all-zeros under either layout, so this asserts counter == 1,
     * where a wrong layout makes the auth tag mismatch a real Apple TV.
     */
    @Test fun connectionNonceIsLeCounterInLowBytes() {
        val key = ByteArray(32) { it.toByte() }
        val aad = byteArrayOf(0x08, 0x00, 0x00, 0x10) // a plausible frame header
        val pt = "hello-session".toByteArray()

        val c = ChaCha(key, key)
        c.encryptOut(pt, aad)            // counter 0 (discarded)
        val ours = c.encryptOut(pt, aad) // counter 1

        // Independent oracle: BouncyCastle with the base-cipher nonce for
        // counter 1 = counter.to_bytes(12,"little") → byte0=0x01, rest zero.
        val nonce = ByteArray(12)
        for (i in 0..7) nonce[i] = ((1L shr (8 * i)) and 0xFF).toByte()
        val e = org.bouncycastle.crypto.modes.ChaCha20Poly1305()
        e.init(true, org.bouncycastle.crypto.params.ParametersWithIV(
            org.bouncycastle.crypto.params.KeyParameter(key), nonce))
        e.processAADBytes(aad, 0, aad.size)
        val ref = ByteArray(e.getOutputSize(pt.size))
        val off = e.processBytes(pt, 0, pt.size, ref, 0)
        e.doFinal(ref, off)

        assertEquals(hex(ref), hex(ours),
            "connection nonce for counter=1 must be counter.to_bytes(12,'little') " +
                "(LE counter in low bytes, no pad) — pyatv base Chacha20Cipher")
    }
}
