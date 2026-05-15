package dev.atvremote.tracetools

import dev.atvremote.protocol.crypto.ChaCha
import dev.atvremote.protocol.crypto.Curves
import dev.atvremote.protocol.crypto.Hkdf
import dev.atvremote.protocol.crypto.Srp
import dev.atvremote.protocol.crypto.Srp3072Group
import dev.atvremote.protocol.opack.Opack
import dev.atvremote.protocol.tlv8.Tlv8
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest

/**
 * ============================================================================
 *  SYNTHETIC golden-trace generator / reference oracle  (Task 10, DEVICE-SKIP)
 * ============================================================================
 *
 * This program is an INDEPENDENT, deterministic reference implementation of the
 * Apple TV Companion-protocol pairing/HID roles. It implements BOTH the
 * controller (Android-side) AND the accessory (Apple TV / tvOS) sides of
 * pair-setup, pair-verify and the post-handshake HID command, using ONLY the
 * already-shipped low-level primitives (Opack / Tlv8 / Srp / Curves / Hkdf /
 * ChaCha). It does NOT depend on the future `PairSetup` / `PairVerify` classes
 * (Tasks 11/12) — those will be cross-checked AGAINST the fixtures this program
 * emits, so the oracle must stand alone.
 *
 *  >>> THE FIXTURES IT WRITES ARE NOT REAL tvOS CAPTURES. <<<
 *
 * They are byte-deterministic synthetic traces (fixed seeds, no RNG) that
 * validate protocol-logic self-consistency only. The authoritative real-device
 * end-to-end validation is Task 17 (CLI smoke test) plus replacing these
 * fixtures with a real pyatv capture per
 * `trace-tools/.../CaptureGuide.md` (which also flips `"mode"` to
 * `"realDevice"`).
 *
 * Run:  ./gradlew :trace-tools:run -PgenGolden
 *   (or)  ./gradlew :trace-tools:runGoldenTraceGen
 * Output: protocol/src/test/resources/goldentrace/{pair-setup,pair-verify,hid-menu}.json
 *
 * Before writing anything this program runs the WHOLE synthetic handshake
 * end-to-end (SRP proofs, signatures, decrypts, derived atvLtpk, connection
 * keys) and aborts loudly if any step is internally inconsistent.
 */
object GoldenTraceGen {

    // ── Fixed deterministic inputs (NO RNG anywhere) ────────────────────────
    // Distinct, easily-recognisable constant seeds so regeneration is
    // byte-identical and a human can eyeball the fixture provenance.

    /** Controller (Android) Ed25519 long-term auth private seed (32B). */
    val CTRL_ED_SEED = hex("01".repeat(32))

    /** Controller pairing identifier (sent as TLV Identifier in M5 / PV-M3). */
    val CTRL_PAIRING_ID = "AA:BB:CC:DD:EE:FF".toByteArray(Charsets.UTF_8)

    /** The PIN the (synthetic) Apple TV "displays". */
    const val PIN = "1234"

    /** Accessory (Apple TV) Ed25519 long-term seed + its accessory pairing id. */
    val ATV_ED_SEED = hex("02".repeat(32))
    val ATV_ID = "11:22:33:44:55:66".toByteArray(Charsets.UTF_8)

    /** SRP accessory ephemeral private (b) seed + the device salt — fixed. */
    val SRP_B_SEED = hex("03".repeat(32))
    val SRP_SALT = hex("04".repeat(16))

    /** Controller X25519 ephemeral key pair for pair-verify (fixed, clamped). */
    val CTRL_X_PRIV = clampX25519(hex("05".repeat(32)))
    val CTRL_X_PUB = Curves.x25519Base(CTRL_X_PRIV)

    /** Accessory X25519 ephemeral key pair for pair-verify (fixed, clamped). */
    val ATV_X_PRIV = clampX25519(hex("06".repeat(32)))
    val ATV_X_PUB = Curves.x25519Base(ATV_X_PRIV)

    /** Fixed HID transaction id for the Menu command fixture. */
    val HID_XID = hex("0708090a0b0c0d0e")

    // ── HAP / Companion protocol constants (from pyatv, per task brief) ──────

    private const val PS_ENC_SALT = "Pair-Setup-Encrypt-Salt"
    private const val PS_ENC_INFO = "Pair-Setup-Encrypt-Info"
    private const val PS_CTRL_SIGN_SALT = "Pair-Setup-Controller-Sign-Salt"
    private const val PS_CTRL_SIGN_INFO = "Pair-Setup-Controller-Sign-Info"
    private const val PS_ACC_SIGN_SALT = "Pair-Setup-Accessory-Sign-Salt"
    private const val PS_ACC_SIGN_INFO = "Pair-Setup-Accessory-Sign-Info"
    private const val PV_ENC_SALT = "Pair-Verify-Encrypt-Salt"
    private const val PV_ENC_INFO = "Pair-Verify-Encrypt-Info"
    private const val CONN_OUT_INFO = "ClientEncrypt-main"
    private const val CONN_IN_INFO = "ServerEncrypt-main"

    // ────────────────────────────────────────────────────────────────────────
    //  PAIR-SETUP oracle (M1..M6) — produces ordered OPACK payloads.
    // ────────────────────────────────────────────────────────────────────────

    data class Step(
        val dir: String,            // "out" (controller→ATV) | "in" (ATV→controller)
        val frameType: Int,
        val opack: ByteArray,
        val decoded: Map<String, Any?>,
    )

    /** Result of a full synthetic pair-setup: ordered steps + derived creds. */
    class PairSetupTrace(
        val steps: List<Step>,
        val srpSessionKey: ByteArray,
        val atvLtpk: ByteArray,
    )

    fun runPairSetup(): PairSetupTrace {
        // --- M1 (controller → ATV): start, Method=0, SeqNo=1 -----------------
        val srpClient = Srp(CTRL_ED_SEED)
        srpClient.step1(PIN)
        val m1Tlv = Tlv8.write(
            linkedMapOf(
                Tlv8.Method to byteArrayOf(0x00),
                Tlv8.SeqNo to byteArrayOf(0x01),
            ),
        )
        val m1Opack = Opack.pack(linkedMapOf<String, Any?>("_pd" to m1Tlv, "_pwTy" to 1L))

        // --- M2 (ATV → controller): SeqNo=2, Salt, PublicKey=B ---------------
        // Accessory SRP side: standard SRP-6a verifier-based server.
        val srvB = srpServerComputeB()
        val m2Tlv = Tlv8.write(
            linkedMapOf(
                Tlv8.SeqNo to byteArrayOf(0x02),
                Tlv8.Salt to SRP_SALT,
                Tlv8.PublicKey to srvB.bPubBytes,
            ),
        )
        val m2Opack = Opack.pack(linkedMapOf<String, Any?>("_pd" to m2Tlv, "_pwTy" to 1L))

        // --- controller consumes M2, derives A + M1srp -----------------------
        val (aPub, clientM1) = srpClient.step2(SRP_SALT, srvB.bPubBytes)
        val srpKey = srpClient.sessionKey

        // accessory verifies client proof, builds its proof M2srp
        val srv = srpServerFinish(srvB, aPub, clientM1)
        check(srv.clientProofOk) { "ORACLE FAIL: SRP client proof (M1srp) did not verify on accessory side" }
        check(srpClient.verifyServerProof(srv.serverProof)) {
            "ORACLE FAIL: SRP server proof (M2srp) did not verify on controller side"
        }
        check(srv.sessionKey.contentEquals(srpKey)) {
            "ORACLE FAIL: SRP session key mismatch controller vs accessory"
        }

        // --- M3 (controller → ATV): SeqNo=3, PublicKey=A, Proof=M1srp --------
        val m3Tlv = Tlv8.write(
            linkedMapOf(
                Tlv8.SeqNo to byteArrayOf(0x03),
                Tlv8.PublicKey to aPub,
                Tlv8.Proof to clientM1,
            ),
        )
        val m3Opack = Opack.pack(linkedMapOf<String, Any?>("_pd" to m3Tlv, "_pwTy" to 1L))

        // --- M4 (ATV → controller): SeqNo=4, Proof=M2srp ---------------------
        val m4Tlv = Tlv8.write(
            linkedMapOf(
                Tlv8.SeqNo to byteArrayOf(0x04),
                Tlv8.Proof to srv.serverProof,
            ),
        )
        val m4Opack = Opack.pack(linkedMapOf<String, Any?>("_pd" to m4Tlv, "_pwTy" to 1L))

        // --- M5 (controller → ATV): SeqNo=5, EncryptedData -------------------
        // Inner = { Identifier=clientPairingId, PublicKey=clientLtpk, Signature }
        // Signature over: HKDF(controller-sign) + clientPairingId + clientLtpk
        val clientLtpk = Curves.ed25519PublicFromSeed(CTRL_ED_SEED)
        val ctrlSignKey = Hkdf.expand(PS_CTRL_SIGN_SALT, PS_CTRL_SIGN_INFO, srpKey, 32)
        val ctrlSigMsg = ctrlSignKey + CTRL_PAIRING_ID + clientLtpk
        val clientSig = Curves.ed25519Sign(CTRL_ED_SEED, ctrlSigMsg)
        val m5InnerTlv = Tlv8.write(
            linkedMapOf(
                Tlv8.Identifier to CTRL_PAIRING_ID,
                Tlv8.PublicKey to clientLtpk,
                Tlv8.Signature to clientSig,
            ),
        )
        val psEncKey = Hkdf.expand(PS_ENC_SALT, PS_ENC_INFO, srpKey, 32)
        val cipher5 = ChaCha(psEncKey, psEncKey)
        val m5Enc = cipher5.encryptFixed("PS-Msg05".toByteArray(Charsets.UTF_8), m5InnerTlv)
        val m5Tlv = Tlv8.write(
            linkedMapOf(
                Tlv8.SeqNo to byteArrayOf(0x05),
                Tlv8.EncryptedData to m5Enc,
            ),
        )
        val m5Opack = Opack.pack(linkedMapOf<String, Any?>("_pd" to m5Tlv, "_pwTy" to 1L))

        // accessory decrypts + verifies client identity
        run {
            val dec = ChaCha(psEncKey, psEncKey)
                .decryptFixed("PS-Msg05".toByteArray(Charsets.UTF_8), m5Enc)
            val inner = Tlv8.read(dec)
            val cid = inner[Tlv8.Identifier]!!
            val cltpk = inner[Tlv8.PublicKey]!!
            val csig = inner[Tlv8.Signature]!!
            val msg = ctrlSignKey + cid + cltpk
            check(Curves.ed25519Verify(cltpk, msg, csig)) {
                "ORACLE FAIL: controller M5 signature did not verify on accessory side"
            }
            check(cid.contentEquals(CTRL_PAIRING_ID)) { "ORACLE FAIL: M5 client id mismatch" }
        }

        // --- M6 (ATV → controller): SeqNo=6, EncryptedData ------------------
        // Inner = { Identifier=atvId, PublicKey=atvLtpk, Signature }
        // Accessory signs: HKDF(accessory-sign) + atvId + atvLtpk  (std HAP)
        val atvLtpk = Curves.ed25519PublicFromSeed(ATV_ED_SEED)
        val accSignKey = Hkdf.expand(PS_ACC_SIGN_SALT, PS_ACC_SIGN_INFO, srpKey, 32)
        val accSigMsg = accSignKey + ATV_ID + atvLtpk
        val atvSig = Curves.ed25519Sign(ATV_ED_SEED, accSigMsg)
        val m6InnerTlv = Tlv8.write(
            linkedMapOf(
                Tlv8.Identifier to ATV_ID,
                Tlv8.PublicKey to atvLtpk,
                Tlv8.Signature to atvSig,
            ),
        )
        val cipher6 = ChaCha(psEncKey, psEncKey)
        val m6Enc = cipher6.encryptFixed("PS-Msg06".toByteArray(Charsets.UTF_8), m6InnerTlv)
        val m6Tlv = Tlv8.write(
            linkedMapOf(
                Tlv8.SeqNo to byteArrayOf(0x06),
                Tlv8.EncryptedData to m6Enc,
            ),
        )
        val m6Opack = Opack.pack(linkedMapOf<String, Any?>("_pd" to m6Tlv, "_pwTy" to 1L))

        // controller decrypts M6 + verifies accessory identity
        run {
            val dec = ChaCha(psEncKey, psEncKey)
                .decryptFixed("PS-Msg06".toByteArray(Charsets.UTF_8), m6Enc)
            val inner = Tlv8.read(dec)
            val aid = inner[Tlv8.Identifier]!!
            val altpk = inner[Tlv8.PublicKey]!!
            val asig = inner[Tlv8.Signature]!!
            val msg = accSignKey + aid + altpk
            check(Curves.ed25519Verify(altpk, msg, asig)) {
                "ORACLE FAIL: accessory M6 signature did not verify on controller side"
            }
            check(altpk.size == 32) { "ORACLE FAIL: atvLtpk not 32 bytes" }
        }

        val steps = listOf(
            Step("out", 3, m1Opack, decodeOpackDict(m1Opack)),
            Step("in", 4, m2Opack, decodeOpackDict(m2Opack)),
            Step("out", 4, m3Opack, decodeOpackDict(m3Opack)),
            Step("in", 4, m4Opack, decodeOpackDict(m4Opack)),
            Step("out", 4, m5Opack, decodeOpackDict(m5Opack)),
            Step("in", 4, m6Opack, decodeOpackDict(m6Opack)),
        )
        return PairSetupTrace(steps, srpKey, atvLtpk)
    }

    // ── Accessory-side SRP-6a (standard verifier server) ─────────────────────

    private val N = Srp3072Group.N
    private val gG = Srp3072Group.g
    private val nLen = (N.bitLength() + 7) / 8

    private fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        for (p in parts) md.update(p)
        return md.digest()
    }

    private fun unsigned(x: BigInteger): ByteArray {
        if (x.signum() == 0) return byteArrayOf(0)
        val b = x.toByteArray()
        return if (b.size > 1 && b[0].toInt() == 0) b.copyOfRange(1, b.size) else b
    }

    private fun pad(x: BigInteger): ByteArray {
        val u = unsigned(x)
        return if (u.size >= nLen) u else ByteArray(nLen - u.size) + u
    }

    private fun bi(b: ByteArray): BigInteger = BigInteger(1, b)

    private class SrvB(
        val b: BigInteger,
        val v: BigInteger,
        val bPub: BigInteger,
        val bPubBytes: ByteArray,
    )

    private class SrvFinish(
        val clientProofOk: Boolean,
        val serverProof: ByteArray,
        val sessionKey: ByteArray,
    )

    /** Accessory: derive verifier v from (salt,pin) and ephemeral B = k*v + g^b. */
    private fun srpServerComputeB(): SrvB {
        // x = SHA512(salt || SHA512("Pair-Setup:pin"))  (matches Srp client)
        val inner = sha512("Pair-Setup:$PIN".toByteArray(Charsets.UTF_8))
        val x = bi(sha512(SRP_SALT, inner))
        val v = gG.modPow(x, N)
        val k = bi(sha512(unsigned(N), pad(gG)))
        val b = bi(SRP_B_SEED).mod(N.subtract(BigInteger.ONE)).let {
            if (it.signum() == 0) BigInteger.ONE else it
        }
        val bPub = k.multiply(v).add(gG.modPow(b, N)).mod(N)
        return SrvB(b, v, bPub, unsigned(bPub))
    }

    /** Accessory: verify client M1srp, compute shared S, K and server proof M2srp. */
    private fun srpServerFinish(srv: SrvB, aPubBytes: ByteArray, clientM1: ByteArray): SrvFinish {
        val aPub = bi(aPubBytes)
        val u = bi(sha512(pad(aPub), pad(srv.bPub)))
        // S = (A * v^u) ^ b mod N
        val s = aPub.multiply(srv.v.modPow(u, N)).modPow(srv.b, N)
        val k = sha512(unsigned(s))

        val hn = sha512(unsigned(N))
        val hg = sha512(unsigned(gG))
        val hnXorHg = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        val hI = sha512("Pair-Setup".toByteArray(Charsets.US_ASCII))
        val expectedM1 = sha512(hnXorHg, hI, SRP_SALT, unsigned(aPub), unsigned(srv.bPub), k)
        val ok = MessageDigest.isEqual(expectedM1, clientM1)
        val m2 = sha512(unsigned(aPub), expectedM1, k)
        return SrvFinish(ok, m2, k)
    }

    // ────────────────────────────────────────────────────────────────────────
    //  PAIR-VERIFY oracle (M1..M3) + connection keys.
    // ────────────────────────────────────────────────────────────────────────

    class PairVerifyTrace(
        val steps: List<Step>,
        val outKey: ByteArray,
        val inKey: ByteArray,
        val atvLtpk: ByteArray,
    )

    /**
     * Synthetic pair-verify. Requires the accessory long-term keypair (atvLtpk
     * is what a prior pair-setup persisted) so the controller can verify M2,
     * exactly as Task 12's `PairVerify` will using the stored `HapCredentials`.
     */
    fun runPairVerify(): PairVerifyTrace {
        val clientLtpk = Curves.ed25519PublicFromSeed(CTRL_ED_SEED)
        val atvLtpk = Curves.ed25519PublicFromSeed(ATV_ED_SEED)

        // --- M1 (controller → ATV): SeqNo=1, PublicKey=ctrlCurve25519Pub -----
        val m1Tlv = Tlv8.write(
            linkedMapOf(
                Tlv8.SeqNo to byteArrayOf(0x01),
                Tlv8.PublicKey to CTRL_X_PUB,
            ),
        )
        val m1Opack = Opack.pack(linkedMapOf<String, Any?>("_pd" to m1Tlv, "_auTy" to 4L))

        // shared ECDH secret (both sides compute the same value)
        val sharedCtrl = Curves.x25519(CTRL_X_PRIV, ATV_X_PUB)
        val sharedAcc = Curves.x25519(ATV_X_PRIV, CTRL_X_PUB)
        check(sharedCtrl.contentEquals(sharedAcc)) { "ORACLE FAIL: PV ECDH mismatch" }
        val shared = sharedCtrl
        val pvEncKey = Hkdf.expand(PV_ENC_SALT, PV_ENC_INFO, shared, 32)

        // --- M2 (ATV → controller): PublicKey=accCurvePub, EncryptedData -----
        // Inner = { Identifier=atvId, Signature(accCurvePub+atvId+ctrlCurvePub) }
        val accSigMsg = ATV_X_PUB + ATV_ID + CTRL_X_PUB
        val accSig = Curves.ed25519Sign(ATV_ED_SEED, accSigMsg)
        val m2InnerTlv = Tlv8.write(
            linkedMapOf(
                Tlv8.Identifier to ATV_ID,
                Tlv8.Signature to accSig,
            ),
        )
        val m2Enc = ChaCha(pvEncKey, pvEncKey)
            .encryptFixed("PV-Msg02".toByteArray(Charsets.UTF_8), m2InnerTlv)
        val m2Tlv = Tlv8.write(
            linkedMapOf(
                Tlv8.PublicKey to ATV_X_PUB,
                Tlv8.EncryptedData to m2Enc,
            ),
        )
        val m2Opack = Opack.pack(linkedMapOf<String, Any?>("_pd" to m2Tlv, "_auTy" to 4L))

        // controller verifies M2 (decrypt, check accessory signature vs atvLtpk)
        run {
            val dec = ChaCha(pvEncKey, pvEncKey)
                .decryptFixed("PV-Msg02".toByteArray(Charsets.UTF_8), m2Enc)
            val inner = Tlv8.read(dec)
            val aid = inner[Tlv8.Identifier]!!
            val asig = inner[Tlv8.Signature]!!
            val msg = ATV_X_PUB + aid + CTRL_X_PUB
            check(Curves.ed25519Verify(atvLtpk, msg, asig)) {
                "ORACLE FAIL: accessory PV-M2 signature did not verify against atvLtpk"
            }
        }

        // --- M3 (controller → ATV): SeqNo=3, EncryptedData ------------------
        // Inner = { Identifier=clientId, Signature(ctrlCurvePub+clientId+accCurvePub) }
        val ctrlSigMsg = CTRL_X_PUB + CTRL_PAIRING_ID + ATV_X_PUB
        val ctrlSig = Curves.ed25519Sign(CTRL_ED_SEED, ctrlSigMsg)
        val m3InnerTlv = Tlv8.write(
            linkedMapOf(
                Tlv8.Identifier to CTRL_PAIRING_ID,
                Tlv8.Signature to ctrlSig,
            ),
        )
        val m3Enc = ChaCha(pvEncKey, pvEncKey)
            .encryptFixed("PV-Msg03".toByteArray(Charsets.UTF_8), m3InnerTlv)
        val m3Tlv = Tlv8.write(
            linkedMapOf(
                Tlv8.SeqNo to byteArrayOf(0x03),
                Tlv8.EncryptedData to m3Enc,
            ),
        )
        // M3 is _pd-only (NO _auTy): verified against pyatv companion/auth.py;
        // real tvOS 18 rejects an M3 carrying _auTy (Task 17, 2026-05-16).
        val m3Opack = Opack.pack(linkedMapOf<String, Any?>("_pd" to m3Tlv))

        // accessory verifies M3 (decrypt, check controller signature vs clientLtpk)
        run {
            val dec = ChaCha(pvEncKey, pvEncKey)
                .decryptFixed("PV-Msg03".toByteArray(Charsets.UTF_8), m3Enc)
            val inner = Tlv8.read(dec)
            val cid = inner[Tlv8.Identifier]!!
            val csig = inner[Tlv8.Signature]!!
            val msg = CTRL_X_PUB + cid + ATV_X_PUB
            check(Curves.ed25519Verify(clientLtpk, msg, csig)) {
                "ORACLE FAIL: controller PV-M3 signature did not verify against clientLtpk"
            }
        }

        // --- connection keys -------------------------------------------------
        val outKey = Hkdf.expand("", CONN_OUT_INFO, shared, 32)
        val inKey = Hkdf.expand("", CONN_IN_INFO, shared, 32)
        check(outKey.size == 32 && inKey.size == 32) { "ORACLE FAIL: connection keys not 32B" }

        val steps = listOf(
            Step("out", 5, m1Opack, decodeOpackDict(m1Opack)),
            Step("in", 6, m2Opack, decodeOpackDict(m2Opack)),
            Step("out", 6, m3Opack, decodeOpackDict(m3Opack)),
        )
        return PairVerifyTrace(steps, outKey, inKey, atvLtpk)
    }

    // ────────────────────────────────────────────────────────────────────────
    //  HID (post-handshake E_OPACK Menu: down then up).
    // ────────────────────────────────────────────────────────────────────────

    fun runHidMenu(): List<Step> {
        // Two E_OPACK frames: button down (_hBtS:1) then up (_hBtS:2), hid=5.
        fun cmd(state: Long): ByteArray = Opack.pack(
            linkedMapOf<String, Any?>(
                "_i" to "_hidC",
                "_t" to 2L,
                "_c" to linkedMapOf<String, Any?>("_hBtS" to state, "_hidC" to 5L),
                "_x" to HID_XID,
            ),
        )
        val down = cmd(1L)
        val up = cmd(2L)
        return listOf(
            Step("out", 8, down, decodeOpackDict(down)),
            Step("out", 8, up, decodeOpackDict(up)),
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) {
            ((Character.digit(clean[it * 2], 16) shl 4) + Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
    }

    fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /** RFC 7748 X25519 private key clamping (so fixed seeds are valid scalars). */
    private fun clampX25519(seed: ByteArray): ByteArray {
        val k = seed.copyOf(32)
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = (k[31].toInt() and 127).toByte()
        k[31] = (k[31].toInt() or 64).toByte()
        return k
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeOpackDict(b: ByteArray): Map<String, Any?> =
        Opack.unpack(b).first as Map<String, Any?>

    // ── JSON writer (no external dependency) ─────────────────────────────────

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.append("\"").toString()
    }

    private fun jsonValue(v: Any?, indent: String): String = when (v) {
        null -> "null"
        is Boolean -> v.toString()
        is Int, is Long -> v.toString()
        is String -> jsonString(v)
        is ByteArray -> jsonString("hex:" + toHex(v))
        is Map<*, *> -> {
            if (v.isEmpty()) "{}" else {
                val ni = "$indent  "
                v.entries.joinToString(",\n", "{\n", "\n$indent}") {
                    "$ni${jsonString(it.key.toString())}: ${jsonValue(it.value, ni)}"
                }
            }
        }
        is List<*> -> {
            if (v.isEmpty()) "[]" else {
                val ni = "$indent  "
                v.joinToString(",\n", "[\n", "\n$indent]") { "$ni${jsonValue(it, ni)}" }
            }
        }
        else -> jsonString(v.toString())
    }

    private fun stepJson(s: Step, indent: String): String {
        val ni = "$indent  "
        return buildString {
            append("{\n")
            append("$ni\"dir\": ${jsonString(s.dir)},\n")
            append("$ni\"frameType\": ${s.frameType},\n")
            append("$ni\"opackHex\": ${jsonString(toHex(s.opack))},\n")
            append("$ni\"decoded\": ${jsonValue(s.decoded, ni)}\n")
            append("$indent}")
        }
    }

    private const val NOTE =
        "SYNTHETIC fixture. Deterministically generated by the in-repo reference " +
            "oracle dev.atvremote.tracetools.GoldenTraceGen (fixed seeds, no RNG). " +
            "This is NOT a real tvOS capture. It validates protocol-logic " +
            "self-consistency only (SRP proofs, signatures, ChaCha decrypt, key " +
            "derivation). Authoritative real-device end-to-end validation is Task " +
            "17 (CLI smoke test) plus replacing this file via CaptureGuide.md " +
            "(which flips the \"mode\" field to \"realDevice\"). Regenerate with: " +
            "./gradlew :trace-tools:runGoldenTraceGen"

    private fun writeFixture(file: File, kind: String, inputs: Map<String, Any?>, steps: List<Step>) {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"mode\": \"synthetic\",\n")
        sb.append("  \"kind\": ${jsonString(kind)},\n")
        sb.append("  \"note\": ${jsonString(NOTE)},\n")
        sb.append("  \"fixedInputs\": ${jsonValue(inputs, "  ")},\n")
        sb.append("  \"steps\": [\n")
        sb.append(steps.joinToString(",\n") { stepJson(it, "    ") })
        sb.append("\n  ]\n")
        sb.append("}\n")
        file.parentFile.mkdirs()
        file.writeText(sb.toString())
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // Resolve protocol test-resources dir relative to repo root (CWD).
        val outDir = File("protocol/src/test/resources/goldentrace").let {
            if (it.isAbsolute) it else File(System.getProperty("user.dir"), it.path)
        }
        println("[GoldenTraceGen] SYNTHETIC generator — output dir: ${outDir.absolutePath}")

        // 1. Run the FULL synthetic handshake end-to-end FIRST. Every internal
        //    `check(...)` throws (fails loudly) before any file is written.
        println("[GoldenTraceGen] running synthetic pair-setup self-check ...")
        val ps = runPairSetup()
        println("  SRP session key: ${ps.srpSessionKey.size}B  atvLtpk: ${ps.atvLtpk.size}B")
        check(ps.srpSessionKey.size == 64) { "SRP session key expected 64B" }
        check(ps.atvLtpk.size == 32) { "atvLtpk expected 32B" }
        check(ps.steps.size == 6) { "pair-setup expected 6 steps" }

        println("[GoldenTraceGen] running synthetic pair-verify self-check ...")
        val pv = runPairVerify()
        println("  outKey: ${pv.outKey.size}B  inKey: ${pv.inKey.size}B")
        check(pv.outKey.size == 32 && pv.inKey.size == 32) { "connection keys expected 32B" }
        check(!pv.outKey.contentEquals(pv.inKey)) { "out/in connection keys must differ" }
        check(pv.steps.size == 3) { "pair-verify expected 3 steps" }

        println("[GoldenTraceGen] building synthetic HID Menu fixture ...")
        val hid = runHidMenu()
        check(hid.size == 2) { "hid expected 2 steps" }

        println("[GoldenTraceGen] self-check PASSED — all proofs/sigs/decrypts/keys consistent.")

        // 2. Only now write the validated fixtures.
        val psInputs = linkedMapOf<String, Any?>(
            "controllerEd25519Seed" to CTRL_ED_SEED,
            "controllerPairingId" to CTRL_PAIRING_ID,
            "pin" to PIN,
            "accessoryEd25519Seed" to ATV_ED_SEED,
            "accessoryId" to ATV_ID,
            "srpAccessoryPrivSeed" to SRP_B_SEED,
            "srpSalt" to SRP_SALT,
        )
        val pvInputs = linkedMapOf<String, Any?>(
            "controllerEd25519Seed" to CTRL_ED_SEED,
            "controllerPairingId" to CTRL_PAIRING_ID,
            "controllerX25519Priv" to CTRL_X_PRIV,
            "controllerX25519Pub" to CTRL_X_PUB,
            "accessoryEd25519Seed" to ATV_ED_SEED,
            "accessoryId" to ATV_ID,
            "accessoryX25519Priv" to ATV_X_PRIV,
            "accessoryX25519Pub" to ATV_X_PUB,
            // Persisted creds a real pair-setup would have produced:
            "clientLtpk" to Curves.ed25519PublicFromSeed(CTRL_ED_SEED),
            "atvLtpk" to Curves.ed25519PublicFromSeed(ATV_ED_SEED),
        )
        val hidInputs = linkedMapOf<String, Any?>(
            "hidTransactionId" to HID_XID,
            "button" to "Menu",
            "hidCode" to 5L,
        )

        writeFixture(File(outDir, "pair-setup.json"), "pair-setup", psInputs, ps.steps)
        writeFixture(File(outDir, "pair-verify.json"), "pair-verify", pvInputs, pv.steps)
        writeFixture(File(outDir, "hid-menu.json"), "hid-menu", hidInputs, hid)

        println("[GoldenTraceGen] wrote pair-setup.json / pair-verify.json / hid-menu.json")
        println("[GoldenTraceGen] DONE (synthetic).")
    }
}

fun main(args: Array<String>) = GoldenTraceGen.main(args)
