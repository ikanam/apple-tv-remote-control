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
}
