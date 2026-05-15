package dev.atvremote.protocol.crypto

import java.math.BigInteger
import java.security.MessageDigest

/**
 * RFC 5054 Appendix A 3072-bit group used by the HAP / Apple TV pair-setup SRP-6a exchange.
 *
 * `N` is the standard RFC 5054 3072-bit safe prime (768 hex chars / exactly 3072 bits),
 * identical to `srptools.constants.PRIME_3072` that pyatv/hap_srp.py feeds the SRP client.
 * `g = 5`.
 */
object Srp3072Group {
    val N: BigInteger = BigInteger(
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
            "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
            "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
            "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" +
            "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
            "83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
            "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9" +
            "DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
            "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64" +
            "ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
            "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6B" +
            "F12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
            "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB31" +
            "43DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
        16,
    )
    val g: BigInteger = BigInteger.valueOf(5)
}

/**
 * SRP-6a client for HAP pair-setup, ported from pyatv `pyatv/auth/hap_srp.py` (which drives
 * `srptools`). Identity is fixed to `"Pair-Setup"`; the password is the device PIN string.
 *
 * Hash is SHA-512 throughout (k, x, u, M1, M2, session key). Definitions, matched exactly to
 * srptools so the derived key is interoperable with a real Apple TV:
 *
 *  - `x  = SHA512(salt || SHA512("Pair-Setup" || ":" || pin))`
 *  - `k  = SHA512(N || PAD(g))`
 *  - `A  = g^a mod N`,  `u = SHA512(PAD(A) || PAD(B))`
 *  - `S  = (B - k*g^x)^(a + u*x) mod N`
 *  - `K  = SHA512(S)`                          (hashed premaster; NOT RFC 2945 interleaved)
 *  - `M1 = SHA512((H(N) XOR H(g)) || H(I) || salt || A || B || K)`
 *  - `M2 = SHA512(A || M1 || K)`
 *
 * Integer serialization mirrors srptools `int_to_bytes`: minimal unsigned big-endian
 * (no sign byte, no leading zero byte; value 0 -> a single 0x00). `PAD(x)` left zero-pads
 * that to N's byte length. Only g (in k) and A,B (in u) are PADded; A,B,S and the hash-int
 * operands inside M1/M2 use the minimal form. `salt` is the raw device salt bytes and
 * `H(I)` uses the ASCII bytes of `"Pair-Setup"`, exactly as pyatv passes them through.
 *
 * The client ephemeral private `a` is derived deterministically from the 32-byte Ed25519
 * auth private seed (pyatv seeds the srptools client with `hexlify(auth_private)`, i.e. the
 * seed bytes interpreted as a big-endian integer). This determinism is required for the
 * golden-trace reproducibility relied upon by pair-setup.
 */
class Srp(authPrivateSeed: ByteArray) {

    private val N = Srp3072Group.N
    private val g = Srp3072Group.g
    private val nLen = (N.bitLength() + 7) / 8

    /**
     * Deterministic client private `a`. pyatv passes `int(hexlify(auth_private), 16)`, i.e.
     * the seed as an unsigned big-endian integer, unreduced. We reduce mod (N-1) (a no-op for
     * a 256-bit seed against a 3072-bit modulus, so behaviour matches pyatv) and guard the
     * degenerate zero case so `A = g^a` is never 1.
     */
    private val a: BigInteger = run {
        val raw = BigInteger(1, authPrivateSeed).mod(N.subtract(BigInteger.ONE))
        if (raw.signum() == 0) BigInteger.ONE else raw
    }

    private var pin: String = ""
    private var aPub: BigInteger = BigInteger.ZERO
    private var premaster: BigInteger? = null
    private var key: ByteArray? = null
    private var m1: ByteArray? = null

    private fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        for (p in parts) md.update(p)
        return md.digest()
    }

    /** Minimal unsigned big-endian (srptools `int_to_bytes`); 0 -> single 0x00 byte. */
    private fun unsigned(x: BigInteger): ByteArray {
        if (x.signum() == 0) return byteArrayOf(0)
        val b = x.toByteArray()
        return if (b.size > 1 && b[0].toInt() == 0) b.copyOfRange(1, b.size) else b
    }

    /** Left zero-pad the minimal bytes to N's byte length (srptools `pad`). */
    private fun pad(x: BigInteger): ByteArray {
        val u = unsigned(x)
        if (u.size >= nLen) return u
        return ByteArray(nLen - u.size) + u
    }

    private fun bi(b: ByteArray): BigInteger = BigInteger(1, b)

    private fun kMultiplier(): BigInteger = bi(sha512(unsigned(N), pad(g)))

    private fun xValue(salt: ByteArray): BigInteger {
        val inner = sha512(("Pair-Setup:$pin").toByteArray(Charsets.UTF_8))
        return bi(sha512(salt, inner))
    }

    /** First pairing step: stash the PIN and compute the client public `A = g^a mod N`. */
    fun step1(pin: String) {
        this.pin = pin
        aPub = g.modPow(a, N)
    }

    /**
     * Second pairing step. Consumes the device salt and public `B`, derives x,u,S,K and the
     * client proof M1. Rejects a malicious `B` with `B mod N == 0`.
     *
     * @return (A bytes, M1 proof bytes) — both minimal unsigned big-endian / 64-byte digest.
     */
    fun step2(salt: ByteArray, serverB: ByteArray): Pair<ByteArray, ByteArray> {
        check(aPub.signum() != 0) { "step1(pin) must be called before step2" }
        val bPub = bi(serverB)
        require(bPub.mod(N).signum() != 0) { "invalid server public B (B mod N == 0)" }

        val k = kMultiplier()
        val x = xValue(salt)
        val u = bi(sha512(pad(aPub), pad(bPub)))

        // S = (B - k*g^x)^(a + u*x) mod N
        val base = bPub.subtract(k.multiply(g.modPow(x, N))).mod(N)
        val exp = a.add(u.multiply(x))
        val s = base.modPow(exp, N)
        premaster = s

        val kKey = sha512(unsigned(s))
        key = kKey

        // M1 = SHA512((H(N) XOR H(g)) || H("Pair-Setup") || salt || A || B || K)
        val hn = sha512(unsigned(N))
        val hg = sha512(unsigned(g))
        val hnXorHg = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        val hu = sha512("Pair-Setup".toByteArray(Charsets.US_ASCII))
        val proof = sha512(
            unsigned(bi(hnXorHg)),
            unsigned(bi(hu)),
            salt,
            unsigned(aPub),
            unsigned(bPub),
            kKey,
        )
        m1 = proof
        return unsigned(aPub) to proof
    }

    /** Session key `K = SHA512(S)` (64 bytes). Valid only after [step2]. */
    val sessionKey: ByteArray
        get() = key?.copyOf() ?: error("sessionKey unavailable: call step1 then step2 first")

    /**
     * Verifies the server proof `M2`, recomputing the expected `SHA512(A || M1 || K)` and
     * comparing in constant time. Valid only after [step2].
     */
    fun verifyServerProof(m2: ByteArray): Boolean {
        val proof = m1 ?: error("verifyServerProof: call step1 then step2 first")
        val k = key ?: error("verifyServerProof: call step1 then step2 first")
        val expected = sha512(unsigned(aPub), proof, k)
        return MessageDigest.isEqual(expected, m2)
    }
}
