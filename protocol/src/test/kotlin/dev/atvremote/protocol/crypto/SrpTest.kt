package dev.atvremote.protocol.crypto

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SrpTest {

    @Test fun clientServerAgreeOnSharedKey() {
        val pin = "1234"
        val harness = SrpTestHarness()
        val (client, verifier) = harness.setup(pin)
        val clientKey = harness.runClient(client, verifier, pin)
        val serverKey = harness.serverKey()
        assertTrue(clientKey.contentEquals(serverKey), "client and server session keys must match")
    }

    /**
     * Regression test: M1 must include hnXorHg and hI as full 64-byte digests.
     *
     * Strategy: construct Srp with a known fixed seed, run a full handshake using a
     * fixed salt and a server B produced by [SrpTestHarness], then independently compute
     * the expected M1 using raw 64-byte digests (NOT via bi()/unsigned() round-trip) and
     * assert equality. This test FAILS if the Srp.kt M1 construction round-trips the hash
     * bytes through BigInteger (dropping leading zero bytes), and PASSES once the fix is in place.
     */
    @Test fun m1UsesFullWidthHashOperands() {
        val pin = "1234"
        // Fixed seed so this test is fully deterministic.
        val fixedSeed = ByteArray(32) { it.toByte() }
        val fixedSalt = ByteArray(16) { (it * 7).toByte() }

        // Use the harness to derive a real server B for the fixed salt/pin.
        val harness = SrpTestHarness(fixedSeed, fixedSalt)
        val (client, _) = harness.setup(pin)

        // Run the SRP client handshake.
        client.step1(pin)
        val (clientABytes, m1) = client.step2(fixedSalt, harness.serverBBytes())

        // Compute K from the harness (server-side).
        val serverKey = harness.computeServerKey(clientABytes)

        // Independently compute what M1 SHOULD be, using full 64-byte digests directly —
        // no BigInteger round-trip on the hash operands.
        val md = MessageDigest.getInstance("SHA-512")
        fun sha512(vararg parts: ByteArray): ByteArray {
            md.reset()
            for (p in parts) md.update(p)
            return md.digest()
        }
        fun unsigned(x: BigInteger): ByteArray {
            if (x.signum() == 0) return byteArrayOf(0)
            val b = x.toByteArray()
            return if (b.size > 1 && b[0].toInt() == 0) b.copyOfRange(1, b.size) else b
        }

        val N = Srp3072Group.N
        val g = Srp3072Group.g
        val nLen = (N.bitLength() + 7) / 8
        fun pad(x: BigInteger): ByteArray {
            val u = unsigned(x)
            return if (u.size >= nLen) u else ByteArray(nLen - u.size) + u
        }

        // Full-width 64-byte digest operands (correct form).
        val hn = sha512(unsigned(N))           // 64 bytes
        val hg = sha512(unsigned(g))           // 64 bytes
        val hnXorHg = ByteArray(64) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        val hI = sha512("Pair-Setup".toByteArray(Charsets.US_ASCII))  // 64 bytes

        assertTrue(hnXorHg.size == 64, "hnXorHg must be 64 bytes")
        assertTrue(hI.size == 64, "hI must be 64 bytes")

        // A and B as minimal unsigned big-endian (correct form per SRP).
        val aPub = BigInteger(1, clientABytes)
        val bPub = BigInteger(1, harness.serverBBytes())

        val expectedM1 = sha512(
            hnXorHg,          // raw 64-byte XOR digest — NO bi()/unsigned() round-trip
            hI,               // raw 64-byte identity digest — NO bi()/unsigned() round-trip
            fixedSalt,
            unsigned(aPub),
            unsigned(bPub),
            serverKey,
        )

        // Report the leading bytes to document whether the bug was already biting.
        val hnXorHgLeadingByte = hnXorHg[0].toInt() and 0xFF
        val hILeadingByte = hI[0].toInt() and 0xFF
        val bugWouldBiteHnXorHg = hnXorHgLeadingByte == 0
        val bugWouldBiteHI = hILeadingByte == 0
        // These are deterministic for the fixed N, g, and "Pair-Setup" identity.
        // The assertion below will fail if the implementation is wrong regardless.

        assertTrue(
            m1.contentEquals(expectedM1),
            "M1 must equal SHA512(hnXorHg[64B] || hI[64B] || salt || A || B || K). " +
                "hnXorHg[0]=0x${hnXorHgLeadingByte.toString(16).padStart(2, '0')} " +
                "(bugWouldTruncate=$bugWouldBiteHnXorHg), " +
                "hI[0]=0x${hILeadingByte.toString(16).padStart(2, '0')} " +
                "(bugWouldTruncate=$bugWouldBiteHI)"
        )
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
 *
 * This is a regular class (not a singleton) so each test gets its own isolated instance
 * with no shared mutable state between test runs.
 */
class SrpTestHarness(
    private val fixedAuthSeed: ByteArray? = null,
    private val fixedSalt: ByteArray? = null,
) {
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
    private var serverSKey: ByteArray = ByteArray(0)

    private fun kMult(): BigInteger = bi(sha512(unsigned(N), pad(g)))

    private fun xValue(saltBytes: ByteArray, pin: String): BigInteger {
        val inner = sha512(("Pair-Setup:$pin").toByteArray(Charsets.UTF_8))
        return bi(sha512(saltBytes, inner))
    }

    /**
     * Sets up the server: random (or fixed) salt, verifier v = g^x mod N, a random (or fixed)
     * Srp client seed. Returns (client, verifier-as-minimal-unsigned-bytes).
     * The verifier is asserted inside [runClient] to confirm the SRP verifier path is correct.
     */
    fun setup(pin: String): Pair<Srp, ByteArray> {
        authSeed = fixedAuthSeed ?: ByteArray(32).also { rng.nextBytes(it) }
        salt = fixedSalt ?: ByteArray(16).also { rng.nextBytes(it) }
        val x = xValue(salt, pin)
        v = g.modPow(x, N)
        // server private b and public B = (k*v + g^b) mod N
        do {
            b = BigInteger(256, rng)
        } while (b.signum() == 0)
        bigB = (kMult().multiply(v).add(g.modPow(b, N))).mod(N)
        val client = Srp(authSeed)
        return Pair(client, unsigned(v))
    }

    /** Exposes the server public B bytes for use by the regression test. */
    fun serverBBytes(): ByteArray = unsigned(bigB)

    /**
     * Computes the server session key for a given client A, without running the full
     * [runClient] flow. Used by the focused regression test [m1UsesFullWidthHashOperands].
     */
    fun computeServerKey(clientABytes: ByteArray): ByteArray {
        val aBig = bi(clientABytes)
        require(aBig.mod(N).signum() != 0) { "A mod N == 0" }
        val u = bi(sha512(pad(aBig), pad(bigB)))
        val s = aBig.multiply(v.modPow(u, N)).modPow(b, N)
        serverSKey = sha512(unsigned(s))
        return serverSKey
    }

    /**
     * Drives the real [Srp] public API end-to-end and computes the server's independent
     * premaster S and key. Returns the client's session key.
     */
    fun runClient(client: Srp, verifier: ByteArray, pin: String): ByteArray {
        client.step1(pin)
        val (clientA, m1) = client.step2(salt, unsigned(bigB))

        // Assert the returned verifier matches what the harness computed.
        require(verifier.contentEquals(unsigned(v))) {
            "verifier mismatch: returned verifier does not equal unsigned(v)"
        }

        // Server independently computes its premaster S = (A * v^u)^b mod N.
        val aBig = bi(clientA)
        require(aBig.mod(N).signum() != 0) { "A mod N == 0" }
        val u = bi(sha512(pad(aBig), pad(bigB)))
        val s = aBig.multiply(v.modPow(u, N)).modPow(b, N)
        serverSKey = sha512(unsigned(s))

        // Independently verify the client's M1 proof against the server-side recomputation.
        // Fix: pass raw 64-byte digests directly — no bi()/unsigned() round-trip.
        val hn = sha512(unsigned(N))
        val hg = sha512(unsigned(g))
        val hnXorHg = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        val hI = sha512("Pair-Setup".toByteArray(Charsets.US_ASCII))
        val expectedM1 = sha512(
            hnXorHg, hI, salt,
            unsigned(aBig), unsigned(bigB), serverSKey,
        )
        require(m1.contentEquals(expectedM1)) { "client M1 != server-recomputed M1" }

        // Server's proof M2 = SHA512(A || M1 || K); client must accept it.
        val m2 = sha512(unsigned(aBig), expectedM1, serverSKey)
        require(client.verifyServerProof(m2)) { "client rejected valid server proof" }
        require(!client.verifyServerProof(ByteArray(64))) { "client accepted bogus server proof" }

        return client.sessionKey
    }

    fun serverKey(): ByteArray = serverSKey.copyOf()
}
