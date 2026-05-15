package dev.atvremote.protocol.pairing

import dev.atvremote.protocol.HapCredentials
import dev.atvremote.protocol.crypto.ChaCha
import dev.atvremote.protocol.crypto.Curves
import dev.atvremote.protocol.crypto.Hkdf
import dev.atvremote.protocol.crypto.Srp
import dev.atvremote.protocol.opack.Opack
import dev.atvremote.protocol.tlv8.Tlv8

/**
 * HAP pair-setup controller (Android side) for the Apple TV Companion protocol.
 *
 * Implements the six-message SRP-6a + Ed25519 pair-setup exchange (M1..M6) as
 * the *controller*, driving only the shipped low-level primitives
 * ([Srp], [Curves], [Hkdf], [ChaCha], [Opack], [Tlv8]). It does NOT reuse the
 * Task-10 reference oracle's algorithm code; the oracle is a separate,
 * independent implementation of both roles. Byte-equality of M1/M3/M5 against
 * the synthetic golden trace therefore proves this controller is a correct,
 * independent implementation that interoperates with the oracle's accessory.
 *
 * Wire framing (must match the protocol contract the fixture encodes):
 *  - Each step is an OPACK dict `{ "_pd": <TLV8 bytes>, "_pwTy": 1 }` with
 *    `_pd` first and `_pwTy` an integer `1` (OPACK small-int).
 *  - TLV item order is significant and fixed per message (see each builder).
 *  - Fixed-nonce ChaCha20-Poly1305 (`PS-Msg05` / `PS-Msg06`); a fresh [ChaCha]
 *    is used per encrypt/decrypt operation (the explicit-nonce path is
 *    counter-independent).
 *
 * > NOTE: The golden trace this is validated against is SYNTHETIC (Task 10,
 * > deterministic in-repo oracle, no RNG). Passing it proves protocol-logic
 * > self-consistency only. Authoritative real-device end-to-end validation is
 * > deferred to **Task 17** (CLI smoke test) plus replacing the fixture with a
 * > real pyatv capture.
 *
 * Lifecycle: call [buildM1], [consumeM2], [buildM3], [consumeM4], [buildM5],
 * [consumeM6] in order; the instance holds SRP and key state across calls and
 * is single-use / not thread-safe.
 *
 * @param seed controller Ed25519 long-term auth private seed (32B); becomes
 *   [HapCredentials.clientLtsk].
 * @param pairingId controller pairing identifier bytes; sent as TLV
 *   `Identifier` in M5 and becomes [HapCredentials.clientId].
 * @param pin the PIN the Apple TV displays.
 */
class PairSetup(
    private val seed: ByteArray,
    private val pairingId: ByteArray,
    private val pin: String,
) {
    private val srp = Srp(seed)

    // SRP results captured in M2/M3 and reused in M4/M5.
    private var srpA: ByteArray? = null
    private var srpM1: ByteArray? = null
    private var sessionKey: ByteArray? = null

    // HKDF-derived keys, available after the SRP session key is established.
    private var encKey: ByteArray? = null

    private fun wrap(tlv: ByteArray): ByteArray =
        Opack.pack(linkedMapOf<String, Any?>("_pd" to tlv, "_pwTy" to 1L))

    private fun unwrapPd(payload: ByteArray): Map<Int, ByteArray> {
        @Suppress("UNCHECKED_CAST")
        val dict = Opack.unpack(payload).first as Map<String, Any?>
        val pd = dict["_pd"] as? ByteArray
            ?: error("pair-setup: inbound OPACK has no '_pd' bytes")
        return Tlv8.read(pd)
    }

    /** M1 (controller -> ATV): `{ Method: 0x00, SeqNo: 1 }`. */
    fun buildM1(): ByteArray {
        srp.step1(pin)
        val tlv = Tlv8.write(
            linkedMapOf(
                Tlv8.Method to byteArrayOf(0x00),
                Tlv8.SeqNo to byteArrayOf(0x01),
            ),
        )
        return wrap(tlv)
    }

    /** M2 (ATV -> controller): `{ SeqNo: 2, Salt, PublicKey=B }`. */
    fun consumeM2(payload: ByteArray) {
        val tlv = unwrapPd(payload)
        val salt = tlv[Tlv8.Salt] ?: error("pair-setup M2: missing Salt")
        val serverB = tlv[Tlv8.PublicKey] ?: error("pair-setup M2: missing PublicKey (B)")
        val (a, m1) = srp.step2(salt, serverB)
        srpA = a
        srpM1 = m1
        sessionKey = srp.sessionKey
    }

    /** M3 (controller -> ATV): `{ SeqNo: 3, PublicKey=A, Proof=M1srp }`. */
    fun buildM3(): ByteArray {
        val a = srpA ?: error("pair-setup: buildM3 called before consumeM2")
        val m1 = srpM1 ?: error("pair-setup: buildM3 called before consumeM2")
        val tlv = Tlv8.write(
            linkedMapOf(
                Tlv8.SeqNo to byteArrayOf(0x03),
                Tlv8.PublicKey to a,
                Tlv8.Proof to m1,
            ),
        )
        return wrap(tlv)
    }

    /** M4 (ATV -> controller): `{ SeqNo: 4, Proof=serverProof }`; verifies M2srp. */
    fun consumeM4(payload: ByteArray) {
        val tlv = unwrapPd(payload)
        val serverProof = tlv[Tlv8.Proof] ?: error("pair-setup M4: missing Proof")
        require(srp.verifyServerProof(serverProof)) {
            "pair-setup M4: SRP server proof (M2) did not verify"
        }
    }

    /**
     * M5 (controller -> ATV): `{ SeqNo: 5, EncryptedData }`.
     *
     * Inner TLV (before ChaCha): `{ Identifier=pairingId, PublicKey=clientLtpk,
     * Signature=sig }` where `sig = Ed25519(seed, signKey || pairingId ||
     * clientLtpk)` and `signKey`/`encKey` are HKDF-SHA512 expansions of the SRP
     * session key.
     */
    fun buildM5(): ByteArray {
        val sk = sessionKey ?: error("pair-setup: buildM5 called before consumeM2")
        val signKey = Hkdf.expand(
            "Pair-Setup-Controller-Sign-Salt",
            "Pair-Setup-Controller-Sign-Info",
            sk,
        )
        val ek = Hkdf.expand("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", sk)
        encKey = ek

        val clientLtpk = Curves.ed25519PublicFromSeed(seed)
        val signedPayload = signKey + pairingId + clientLtpk
        val sig = Curves.ed25519Sign(seed, signedPayload)
        val innerTlv = Tlv8.write(
            linkedMapOf(
                Tlv8.Identifier to pairingId,
                Tlv8.PublicKey to clientLtpk,
                Tlv8.Signature to sig,
            ),
        )
        val enc = ChaCha(ek, ek).encryptFixed("PS-Msg05".toByteArray(), innerTlv)
        val tlv = Tlv8.write(
            linkedMapOf(
                Tlv8.SeqNo to byteArrayOf(0x05),
                Tlv8.EncryptedData to enc,
            ),
        )
        return wrap(tlv)
    }

    /**
     * M6 (ATV -> controller): `{ SeqNo: 6, EncryptedData }`.
     *
     * Decrypts the inner TLV `{ Identifier=atvId, PublicKey=atvLtpk,
     * Signature=atvSig }`. The accessory signature is over
     * `HKDF(accessory-sign, sessionKey) || atvId || atvLtpk`; this controller
     * additionally verifies it with the recovered `atvLtpk` (a correctness
     * strengthening consistent with the oracle — it passes against the fixture)
     * and fails on mismatch. Returns the persisted [HapCredentials].
     */
    fun consumeM6(payload: ByteArray): HapCredentials {
        val ek = encKey ?: error("pair-setup: consumeM6 called before buildM5")
        val sk = sessionKey ?: error("pair-setup: consumeM6 called before consumeM2")
        val tlv = unwrapPd(payload)
        val enc = tlv[Tlv8.EncryptedData] ?: error("pair-setup M6: missing EncryptedData")
        val dec = ChaCha(ek, ek).decryptFixed("PS-Msg06".toByteArray(), enc)
        val inner = Tlv8.read(dec)
        val atvId = inner[Tlv8.Identifier] ?: error("pair-setup M6: missing Identifier")
        val atvLtpk = inner[Tlv8.PublicKey] ?: error("pair-setup M6: missing PublicKey")
        val atvSig = inner[Tlv8.Signature] ?: error("pair-setup M6: missing Signature")

        val accSignKey = Hkdf.expand(
            "Pair-Setup-Accessory-Sign-Salt",
            "Pair-Setup-Accessory-Sign-Info",
            sk,
        )
        require(Curves.ed25519Verify(atvLtpk, accSignKey + atvId + atvLtpk, atvSig)) {
            "pair-setup M6: accessory signature did not verify against atvLtpk"
        }

        return HapCredentials(
            clientId = pairingId,
            clientLtsk = seed,
            clientLtpk = Curves.ed25519PublicFromSeed(seed),
            atvId = atvId,
            atvLtpk = atvLtpk,
        )
    }
}
