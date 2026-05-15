package dev.atvremote.protocol.pairing

import dev.atvremote.protocol.HapCredentials
import dev.atvremote.protocol.crypto.ChaCha
import dev.atvremote.protocol.crypto.Curves
import dev.atvremote.protocol.crypto.Hkdf
import dev.atvremote.protocol.opack.Opack
import dev.atvremote.protocol.tlv8.Tlv8

/**
 * HAP pair-verify controller (Android side) for the Apple TV Companion protocol.
 *
 * Implements the three-message X25519 + Ed25519 pair-verify exchange (M1..M3)
 * as the *controller*, using only the shipped low-level primitives
 * ([Curves], [Hkdf], [ChaCha], [Opack], [Tlv8]). It does NOT reuse the Task-10
 * reference oracle's algorithm code; the oracle is a separate, independent
 * implementation of both roles. Byte-equality of M1/M3 against the synthetic
 * golden trace therefore proves this controller is a correct, independent
 * implementation that interoperates with the oracle's accessory, and that the
 * derived connection keys match.
 *
 * Wire framing (must match the protocol contract the fixture encodes):
 *  - Each step is an OPACK dict `{ "_pd": <TLV8 bytes>, "_auTy": 4 }` with
 *    `_pd` first and `_auTy` an integer `4` (OPACK small-int). All three
 *    pair-verify messages (M1/M2/M3) carry the same `_auTy: 4` wrapper —
 *    confirmed against the fixture; M3 is NOT `_pd`-only.
 *  - TLV item order is significant and fixed per message (see each builder):
 *    M1 `{ SeqNo, PublicKey }`, M3 `{ SeqNo, EncryptedData }`.
 *  - Fixed-nonce ChaCha20-Poly1305 (`PV-Msg02` decrypt / `PV-Msg03` encrypt);
 *    a fresh [ChaCha] is used per encrypt/decrypt operation (the explicit-nonce
 *    path is counter-independent).
 *
 * > NOTE: The golden trace this is validated against is SYNTHETIC (Task 10,
 * > deterministic in-repo oracle, no RNG). Passing it proves protocol-logic
 * > self-consistency only. Authoritative real-device end-to-end validation is
 * > deferred to **Task 17** (CLI smoke test) plus replacing the fixture with a
 * > real pyatv capture.
 *
 * Lifecycle: call [buildM1], [consumeM2], [buildM3] in order, then
 * [connectionKeys]; the instance holds the ECDH shared secret and session key
 * across calls and is single-use / not thread-safe.
 *
 * @param credentials the [HapCredentials] a prior pair-setup persisted; supplies
 *   the controller long-term key/id used to authenticate to the accessory and
 *   the accessory id/long-term public key used to verify the accessory in M2.
 * @param x25519Priv controller ephemeral X25519 private scalar (32B, clamped).
 * @param x25519Pub controller ephemeral X25519 public key (32B).
 */
class PairVerify(
    private val credentials: HapCredentials,
    private val x25519Priv: ByteArray,
    private val x25519Pub: ByteArray,
) {
    // Established in consumeM2 and reused by buildM3 / connectionKeys.
    private var shared: ByteArray? = null
    private var serverPub: ByteArray? = null
    private var sessKey: ByteArray? = null

    private fun wrap(tlv: ByteArray): ByteArray =
        Opack.pack(linkedMapOf<String, Any?>("_pd" to tlv, "_auTy" to 4L))

    private fun unwrapPd(payload: ByteArray): Map<Int, ByteArray> {
        @Suppress("UNCHECKED_CAST")
        val dict = Opack.unpack(payload).first as Map<String, Any?>
        val pd = dict["_pd"] as? ByteArray
            ?: error("pair-verify: inbound OPACK has no '_pd' bytes")
        return Tlv8.read(pd)
    }

    /** M1 (controller -> ATV): `{ SeqNo: 1, PublicKey=x25519Pub }`. */
    fun buildM1(): ByteArray {
        val tlv = Tlv8.write(
            linkedMapOf(
                Tlv8.SeqNo to byteArrayOf(0x01),
                Tlv8.PublicKey to x25519Pub,
            ),
        )
        return wrap(tlv)
    }

    /**
     * M2 (ATV -> controller): `{ PublicKey=serverPub, EncryptedData }`.
     *
     * Computes the X25519 shared secret and the pair-verify session key, then
     * decrypts the inner TLV `{ Identifier=atvId, Signature=atvSig }`. Verifies
     * the recovered identifier equals [HapCredentials.atvId] and that the
     * accessory signature over `serverPub || atvId || x25519Pub` validates
     * against the stored [HapCredentials.atvLtpk]; fails clearly otherwise.
     */
    fun consumeM2(payload: ByteArray) {
        val tlv = unwrapPd(payload)
        val srvPub = tlv[Tlv8.PublicKey] ?: error("pair-verify M2: missing PublicKey")
        val enc = tlv[Tlv8.EncryptedData] ?: error("pair-verify M2: missing EncryptedData")

        val sh = Curves.x25519(x25519Priv, srvPub)
        val sk = Hkdf.expand(
            "Pair-Verify-Encrypt-Salt",
            "Pair-Verify-Encrypt-Info",
            sh,
        )
        val dec = ChaCha(sk, sk).decryptFixed("PV-Msg02".toByteArray(), enc)
        val inner = Tlv8.read(dec)
        val atvId = inner[Tlv8.Identifier] ?: error("pair-verify M2: missing Identifier")
        val atvSig = inner[Tlv8.Signature] ?: error("pair-verify M2: missing Signature")

        require(atvId.contentEquals(credentials.atvId)) {
            "pair-verify M2: accessory identifier does not match stored credentials"
        }
        require(Curves.ed25519Verify(credentials.atvLtpk, srvPub + atvId + x25519Pub, atvSig)) {
            "pair-verify M2: accessory signature did not verify against stored atvLtpk"
        }

        shared = sh
        serverPub = srvPub
        sessKey = sk
    }

    /**
     * M3 (controller -> ATV): `{ SeqNo: 3, EncryptedData }`.
     *
     * Inner TLV (before ChaCha): `{ Identifier=clientId, Signature=sig }` where
     * `sig = Ed25519(clientLtsk, x25519Pub || clientId || serverPub)`.
     */
    fun buildM3(): ByteArray {
        val sk = sessKey ?: error("pair-verify: buildM3 called before consumeM2")
        val srvPub = serverPub ?: error("pair-verify: buildM3 called before consumeM2")

        val info = x25519Pub + credentials.clientId + srvPub
        val sig = Curves.ed25519Sign(credentials.clientLtsk, info)
        val innerTlv = Tlv8.write(
            linkedMapOf(
                Tlv8.Identifier to credentials.clientId,
                Tlv8.Signature to sig,
            ),
        )
        val enc = ChaCha(sk, sk).encryptFixed("PV-Msg03".toByteArray(), innerTlv)
        val tlv = Tlv8.write(
            linkedMapOf(
                Tlv8.SeqNo to byteArrayOf(0x03),
                Tlv8.EncryptedData to enc,
            ),
        )
        return wrap(tlv)
    }

    /**
     * Post-handshake Companion connection keys derived from the pair-verify
     * shared secret: `(outKey, inKey)` where `outKey` encrypts controller ->
     * accessory traffic (`ClientEncrypt-main`) and `inKey` decrypts accessory
     * -> controller traffic (`ServerEncrypt-main`). Each is 32 bytes.
     *
     * Requires [consumeM2] to have established the shared secret first.
     */
    fun connectionKeys(): Pair<ByteArray, ByteArray> {
        val sh = shared ?: error("pair-verify: connectionKeys called before consumeM2")
        val outKey = Hkdf.expand("", "ClientEncrypt-main", sh)
        val inKey = Hkdf.expand("", "ServerEncrypt-main", sh)
        return outKey to inKey
    }
}
