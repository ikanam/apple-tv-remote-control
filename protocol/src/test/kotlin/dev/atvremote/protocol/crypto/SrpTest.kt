package dev.atvremote.protocol.crypto

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertTrue

class SrpTest {
    @Test fun clientServerAgreeOnSharedKey() {
        val pin = "1234"
        val (clientA, server, verifier) = SrpTestHarness.setup(pin)
        val clientS = SrpTestHarness.runClient(clientA, server, verifier, pin)
        val serverS = SrpTestHarness.serverKey()
        assertTrue(clientS.contentEquals(serverS))
    }
}

/**
 * Reference SRP-6a *server* (and harness) implemented independently of [Srp] with plain
 * [BigInteger] + SHA-512. It deliberately re-derives every quantity (its own b, B = k*v + g^b,
 * S = (A * v^u)^b) so that `client K == server SHA512(S)` is a genuine cross-check of the SRP
 * math rather than a tautology against the production code path.
 *
 * Serialization mirrors srptools `int_to_bytes` (minimal unsigned big-endian) and `pad`
 * (left zero-pad to N's byte length), exactly as pyatv/hap_srp.py drives srptools.
 */
object SrpTestHarness {
    private val N = Srp3072Group.N
    private val g = Srp3072Group.g
    private val nLen = (N.bitLength() + 7) / 8
    private val rng = SecureRandom()

    private fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        for (p in parts) md.update(p)
        return md.digest()
    }

    /** Minimal unsigned big-endian, matching srptools int_to_bytes (value 0 -> single 0x00). */
    private fun unsigned(x: BigInteger): ByteArray {
        if (x.signum() == 0) return byteArrayOf(0)
        val b = x.toByteArray()
        return if (b.size > 1 && b[0].toInt() == 0) b.copyOfRange(1, b.size) else b
    }

    /** Left zero-pad minimal bytes to N's byte length (srptools pad()). */
    private fun pad(x: BigInteger): ByteArray {
        val u = unsigned(x)
        if (u.size >= nLen) return u
        return ByteArray(nLen - u.size) + u
    }

    private fun bi(b: ByteArray) = BigInteger(1, b)

    // ---- harness state (server side) ----
    private lateinit var authSeed: ByteArray
    private lateinit var salt: ByteArray
    private lateinit var v: BigInteger
    private var b: BigInteger = BigInteger.ZERO
    private var bigB: BigInteger = BigInteger.ZERO
    private var serverS: BigInteger = BigInteger.ZERO
    private var serverKey: ByteArray = ByteArray(0)

    private fun kMult(): BigInteger = bi(sha512(unsigned(N), pad(g)))

    private fun xValue(saltBytes: ByteArray, pin: String): BigInteger {
        val inner = sha512(("Pair-Setup:$pin").toByteArray(Charsets.UTF_8))
        return bi(sha512(saltBytes, inner))
    }

    /**
     * Sets up the server: random salt, verifier v = g^x mod N, a random Srp client seed.
     * Returns (client, this-harness-as-server-marker, verifier-as-B-bytes-placeholder).
     * The third element is unused structurally but kept to match the spec test shape.
     */
    fun setup(pin: String): Triple<Srp, SrpTestHarness, ByteArray> {
        authSeed = ByteArray(32).also { rng.nextBytes(it) }
        salt = ByteArray(16).also { rng.nextBytes(it) }
        val x = xValue(salt, pin)
        v = g.modPow(x, N)
        // server private b and public B = (k*v + g^b) mod N
        do {
            b = BigInteger(256, rng)
        } while (b.signum() == 0)
        bigB = (kMult().multiply(v).add(g.modPow(b, N))).mod(N)
        val client = Srp(authSeed)
        return Triple(client, this, unsigned(v))
    }

    /**
     * Drives the real [Srp] public API end-to-end and computes the server's independent
     * premaster S and key. Returns the client's session key.
     */
    fun runClient(client: Srp, server: SrpTestHarness, verifier: ByteArray, pin: String): ByteArray {
        client.step1(pin)
        val (clientA, m1) = client.step2(salt, unsigned(bigB))

        // Server independently computes its premaster S = (A * v^u)^b mod N.
        val aBig = bi(clientA)
        require(aBig.mod(N).signum() != 0) { "A mod N == 0" }
        val u = bi(sha512(pad(aBig), pad(bigB)))
        serverS = aBig.multiply(v.modPow(u, N)).modPow(b, N)
        serverKey = sha512(unsigned(serverS))

        // Independently verify the client's M1 proof against the server-side recomputation.
        val hn = sha512(unsigned(N))
        val hg = sha512(unsigned(g))
        val hnXorHg = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        val hu = sha512("Pair-Setup".toByteArray(Charsets.US_ASCII))
        val expectedM1 = sha512(
            unsigned(bi(hnXorHg)), unsigned(bi(hu)), salt,
            unsigned(aBig), unsigned(bigB), serverKey,
        )
        require(m1.contentEquals(expectedM1)) { "client M1 != server-recomputed M1" }

        // Server's proof M2 = SHA512(A || M1 || K); client must accept it.
        val m2 = sha512(unsigned(aBig), expectedM1, serverKey)
        require(client.verifyServerProof(m2)) { "client rejected valid server proof" }
        require(!client.verifyServerProof(ByteArray(64))) { "client accepted bogus server proof" }

        return client.sessionKey
    }

    fun serverKey(): ByteArray = serverKey
}
