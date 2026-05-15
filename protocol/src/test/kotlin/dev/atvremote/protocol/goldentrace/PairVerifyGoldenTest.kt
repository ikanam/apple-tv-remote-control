package dev.atvremote.protocol.goldentrace

import dev.atvremote.protocol.pairing.PairVerify
import kotlin.test.Test
import kotlin.test.assertTrue

class PairVerifyGoldenTest {
    @Test fun pvMatchesFixtureAndDerivesKeys() {
        val gt = GoldenTrace.load("pair-verify.json")
        val pv = PairVerify(credentials = gt.credentials, x25519Priv = gt.fixedX25519Priv, x25519Pub = gt.fixedX25519Pub)
        assertTrue(pv.buildM1().contentEquals(gt.out(0)))
        pv.consumeM2(gt.inFrame(1))
        assertTrue(pv.buildM3().contentEquals(gt.out(2)))
        val (outKey, inKey) = pv.connectionKeys()
        assertTrue(outKey.size == 32 && inKey.size == 32)
    }
}
