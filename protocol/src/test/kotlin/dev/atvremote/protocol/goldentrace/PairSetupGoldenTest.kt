package dev.atvremote.protocol.goldentrace

import dev.atvremote.protocol.pairing.PairSetup
import kotlin.test.Test
import kotlin.test.assertTrue

class PairSetupGoldenTest {
    @Test fun m1m3m5MatchFixture() {
        val gt = GoldenTrace.load("pair-setup.json")
        val ps = PairSetup(seed = gt.fixedSeed, pairingId = gt.fixedPairingId, pin = gt.pin)
        val m1 = ps.buildM1()
        assertTrue(m1.contentEquals(gt.out(0)))
        ps.consumeM2(gt.inFrame(1))
        val m3 = ps.buildM3()
        assertTrue(m3.contentEquals(gt.out(2)))
        ps.consumeM4(gt.inFrame(3))
        val m5 = ps.buildM5()
        assertTrue(m5.contentEquals(gt.out(4)))
        val creds = ps.consumeM6(gt.inFrame(5))
        assertTrue(creds.atvLtpk.size == 32)
    }
}
