package dev.atvremote.protocol.pairing

import dev.atvremote.protocol.HapCredentials
import dev.atvremote.protocol.crypto.Curves
import dev.atvremote.protocol.opack.Opack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real-device (Bug C) regression for the pair-verify OPACK wrapper.
 *
 * Verified against pyatv (`pyatv/protocols/companion/auth.py`), the reference
 * this code is ported from and which interoperates with real Apple TVs:
 *
 *  - **M1** (`PV_Start`):  `{ "_pd": <tlv>, "_auTy": 4 }`
 *  - **M3** (`PV_Next`):   `{ "_pd": <tlv> }`  — **NO `_auTy` key**
 *
 * The old code wrapped *all* pair-verify messages with `_auTy: 4`; real tvOS 18
 * rejects an M3 that carries the extra `_auTy`, replying with a short `PV_Next`
 * error (observed on device 2026-05-16). The synthetic golden fixture encoded
 * the same wrong assumption, so it could not catch this.
 *
 * Only [PairVerify.buildM1] needs valid pair-verify state, so M3 is exercised
 * through a full M1→consumeM2→M3 round-trip against an in-test accessory.
 */
class PairVerifyWrapperTest {

    @Suppress("UNCHECKED_CAST")
    private fun opackKeys(frame: ByteArray): Map<String, Any?> =
        Opack.unpack(frame).first as Map<String, Any?>

    @Test
    fun `M1 carries _auTy 4 and _pd`() {
        val (sk, _) = Curves.newEd25519()
        val creds = HapCredentials(
            clientId = "client".toByteArray(),
            clientLtsk = sk,
            clientLtpk = Curves.ed25519PublicFromSeed(sk),
            atvId = "atv".toByteArray(),
            atvLtpk = ByteArray(32),
        )
        val (xPriv, xPub) = Curves.newX25519()
        val m1 = opackKeys(PairVerify(creds, xPriv, xPub).buildM1())

        assertTrue(m1.containsKey("_pd"), "M1 must contain _pd")
        assertEquals(4L, (m1["_auTy"] as? Number)?.toLong(),
            "M1 must carry _auTy=4 (pyatv PV_Start)")
    }

    @Test
    fun `M3 is _pd-only with no _auTy`() {
        // Controller credentials + ephemeral keys.
        val clientSeed = Curves.newEd25519()
        val atvSeed = Curves.newEd25519()
        val creds = HapCredentials(
            clientId = "client-id".toByteArray(),
            clientLtsk = clientSeed.first,
            clientLtpk = clientSeed.second,
            atvId = "atv-id".toByteArray(),
            atvLtpk = atvSeed.second,
        )
        val (ctrlPriv, ctrlPub) = Curves.newX25519()
        val pv = PairVerify(creds, ctrlPriv, ctrlPub)
        pv.buildM1()

        // Minimal accessory M2 so consumeM2 can establish pair-verify state.
        val (accPriv, accPub) = Curves.newX25519()
        val shared = Curves.x25519(accPriv, ctrlPub)
        val sk = dev.atvremote.protocol.crypto.Hkdf.expand(
            "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared,
        )
        val accSig = Curves.ed25519Sign(atvSeed.first, accPub + creds.atvId + ctrlPub)
        val innerTlv = dev.atvremote.protocol.tlv8.Tlv8.write(
            linkedMapOf(
                dev.atvremote.protocol.tlv8.Tlv8.Identifier to creds.atvId,
                dev.atvremote.protocol.tlv8.Tlv8.Signature to accSig,
            ),
        )
        val enc = dev.atvremote.protocol.crypto.ChaCha(sk, sk)
            .encryptFixed("PV-Msg02".toByteArray(), innerTlv)
        val m2Tlv = dev.atvremote.protocol.tlv8.Tlv8.write(
            linkedMapOf(
                dev.atvremote.protocol.tlv8.Tlv8.PublicKey to accPub,
                dev.atvremote.protocol.tlv8.Tlv8.EncryptedData to enc,
            ),
        )
        val m2 = Opack.pack(linkedMapOf<String, Any?>("_pd" to m2Tlv))
        pv.consumeM2(m2)

        val m3 = opackKeys(pv.buildM3())
        assertTrue(m3.containsKey("_pd"), "M3 must contain _pd")
        assertFalse(m3.containsKey("_auTy"),
            "M3 must be _pd-only — real tvOS rejects M3 carrying _auTy (pyatv PV_Next)")
    }
}
