package dev.atvremote.protocol.goldentrace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * The single test for Task 10 itself: asserts the SYNTHETIC fixture/loader
 * contract that Tasks 11/12 will rely on. It does NOT exercise PairSetup /
 * PairVerify (those classes don't exist yet) — it only proves the fixtures are
 * well-formed, flagged synthetic, indexable per the documented frame order, and
 * that the embedded fixed inputs round-trip.
 */
class GoldenTraceLoaderTest {

    @Test fun allFixturesAreFlaggedSynthetic() {
        for (name in listOf("pair-setup.json", "pair-verify.json", "hid-menu.json")) {
            val gt = GoldenTrace.load(name)
            assertEquals("synthetic", gt.mode, "$name must be flagged synthetic")
            assertTrue(
                gt.note.contains("SYNTHETIC") && gt.note.contains("NOT a real"),
                "$name note must clearly state it is synthetic / not a real capture",
            )
            assertTrue(gt.note.contains("Task 17"), "$name note must point at Task 17 as authoritative")
        }
    }

    @Test fun pairSetupStructureAndIndexing() {
        val gt = GoldenTrace.load("pair-setup.json")
        assertEquals("pair-setup", gt.kind)
        assertEquals(6, gt.steps.size)

        // dir order: [out, in, out, in, out, in]
        assertEquals(listOf("out", "in", "out", "in", "out", "in"), gt.steps.map { it.dir })
        // frame types: M1 = PS_Start(3); M2..M6 = PS_Next(4)
        assertEquals(listOf(3, 4, 4, 4, 4, 4), gt.steps.map { it.frameType })

        // Task 11 indexing must resolve without throwing.
        val m1 = gt.out(0); val m2 = gt.inFrame(1); val m3 = gt.out(2)
        val m4 = gt.inFrame(3); val m5 = gt.out(4); val m6 = gt.inFrame(5)
        for (b in listOf(m1, m2, m3, m4, m5, m6)) assertTrue(b.isNotEmpty())

        // OPACK wrapper keys for setup: {_pd, _pwTy}
        gt.steps.forEach { st ->
            assertTrue(st.decoded.containsKey("_pd"), "setup step missing _pd")
            assertTrue(st.decoded.containsKey("_pwTy"), "setup step missing _pwTy")
            assertTrue(st.decoded["_pd"] is ByteArray, "_pd must decode to bytes")
        }

        // Fixed inputs round-trip to expected hex lengths.
        assertEquals(32, gt.fixedSeed.size, "controller Ed25519 seed must be 32B")
        assertEquals("1234", gt.pin)
        assertTrue(gt.fixedPairingId.isNotEmpty())
    }

    @Test fun pairVerifyStructureAndIndexing() {
        val gt = GoldenTrace.load("pair-verify.json")
        assertEquals("pair-verify", gt.kind)
        assertEquals(3, gt.steps.size)

        assertEquals(listOf("out", "in", "out"), gt.steps.map { it.dir })
        // frame types: M1 = PV_Start(5); M2, M3 = PV_Next(6)
        assertEquals(listOf(5, 6, 6), gt.steps.map { it.frameType })

        val m1 = gt.out(0); val m2 = gt.inFrame(1); val m3 = gt.out(2)
        for (b in listOf(m1, m2, m3)) assertTrue(b.isNotEmpty())

        // OPACK wrapper keys (verified vs pyatv companion/auth.py, real-device
        // truth — Task 17): every step has _pd; M1/M2 carry _auTy:4, but M3
        // (controller PV_Next, step index 2) is _pd-ONLY — real tvOS 18 rejects
        // an M3 carrying _auTy.
        gt.steps.forEach { st ->
            assertTrue(st.decoded.containsKey("_pd"), "verify step missing _pd")
        }
        assertTrue(gt.steps[0].decoded.containsKey("_auTy"), "M1 must carry _auTy")
        assertTrue(gt.steps[1].decoded.containsKey("_auTy"), "M2 must carry _auTy")
        assertTrue(!gt.steps[2].decoded.containsKey("_auTy"),
            "M3 must be _pd-only (no _auTy) — pyatv PV_Next / real tvOS")

        // Fixed inputs for Task 12.
        assertEquals(32, gt.fixedX25519Priv.size, "X25519 priv must be 32B")
        assertEquals(32, gt.fixedX25519Pub.size, "X25519 pub must be 32B")

        val creds = gt.credentials
        assertEquals(32, creds.clientLtsk.size, "clientLtsk (Ed25519 seed) must be 32B")
        assertEquals(32, creds.clientLtpk.size, "clientLtpk must be 32B")
        assertEquals(32, creds.atvLtpk.size, "atvLtpk must be 32B")
        assertTrue(creds.clientId.isNotEmpty())
        assertTrue(creds.atvId.isNotEmpty())
        // credentials must survive the project's own serialize/parse round-trip
        assertContentEquals(creds.atvLtpk, dev.atvremote.protocol.HapCredentials.parse(creds.serialize()).atvLtpk)
    }

    @Test fun hidMenuStructure() {
        val gt = GoldenTrace.load("hid-menu.json")
        assertEquals("hid-menu", gt.kind)
        assertEquals(2, gt.steps.size)
        assertEquals(listOf("out", "out"), gt.steps.map { it.dir })
        // both frames are E_OPACK(8)
        assertEquals(listOf(8, 8), gt.steps.map { it.frameType })

        gt.steps.forEachIndexed { idx, st ->
            assertTrue(st.decoded.containsKey("_i"), "hid step $idx missing _i")
            assertTrue(st.decoded.containsKey("_t"), "hid step $idx missing _t")
            assertTrue(st.decoded.containsKey("_c"), "hid step $idx missing _c")
            assertEquals("_hidC", st.decoded["_i"])
            @Suppress("UNCHECKED_CAST")
            val c = st.decoded["_c"] as Map<String, Any?>
            assertEquals(5L, (c["_hidC"] as Number).toLong(), "Menu hid code must be 5")
        }
        // down then up
        @Suppress("UNCHECKED_CAST")
        val downC = gt.steps[0].decoded["_c"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val upC = gt.steps[1].decoded["_c"] as Map<String, Any?>
        assertEquals(1L, (downC["_hBtS"] as Number).toLong(), "first frame is button-down (_hBtS=1)")
        assertEquals(2L, (upC["_hBtS"] as Number).toLong(), "second frame is button-up (_hBtS=2)")
    }

    @Test fun outAndInFrameDirectionGuardsHold() {
        val gt = GoldenTrace.load("pair-setup.json")
        // out(1) is actually an inbound step → must throw.
        var threw = false
        try { gt.out(1) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw, "out(i) must reject an inbound step")
        threw = false
        try { gt.inFrame(0) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw, "inFrame(i) must reject an outbound step")
    }
}
