package dev.atvremote.protocol.goldentrace

import dev.atvremote.protocol.MediaCommand
import dev.atvremote.protocol.session.AppsController
import dev.atvremote.protocol.session.FakeProtocol
import dev.atvremote.protocol.session.HidCommands
import dev.atvremote.protocol.session.MediaController
import dev.atvremote.protocol.session.PowerController
import dev.atvremote.protocol.session.TouchTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plan-2 device-session **conformance** suite (runbook tasks T6/T8/T10/T12).
 *
 * Drives the already-validated production transports
 * ([TouchTransport]/[HidCommands]/[AppsController]/[MediaController]/[PowerController])
 * through [FakeProtocol] and asserts the emitted frame *structure* matches the
 * REAL-DEVICE golden traces captured from 客厅 / AppleTV14,1 / **tvOS 26.5** via
 * pyatv 0.17.0 (`protocol/src/test/resources/goldentrace/<kind>.json`,
 * `mode == "realDevice"`).
 *
 * Discipline:
 *  - Asserts **structure / identifier / phase ordering ONLY**. The fixtures'
 *    `_ns` (session-relative monotonic ns) and `_x` (xid) are non-deterministic
 *    capture artifacts and are NEVER asserted.
 *  - This is additive TEST CODE; no production `src/main` code is touched.
 *  - `MiniJson` parses integral JSON numbers as [Long]; our production code
 *    emits [Int]. Comparisons normalise both sides through [Number.toLong].
 */
class CommandsGoldenTest {

    private fun Any?.asLong(): Long = (this as Number).toLong()

    /**
     * T6 — `touch-swipe.json`.
     *
     * The real-device capture wraps the gesture in session-lifecycle framing:
     * `_touchStart` (_t2) … 31×`_hidT` (_t1, fire-and-forget) … `_touchStop` (_t2).
     * Per the pyatv-wins touch model (CLAUDE.md / docs/PROTOCOL.md),
     * `_touchStart`/`_touchStop` are sent ONCE by connect()/close() — NOT by
     * `swipe()` — so `TouchTransport.swipe()` legitimately emits only the
     * `_hidT` subsequence. We assert that subsequence's identifier + `_tPh`
     * phase ordering (first=Press(1), middle all Hold(3), last=Release(4))
     * matches the fixture's `_hidT` steps, matching the fixture's step count.
     */
    @Test fun swipeMatchesFixtureStructure() = runTest {
        val gt = GoldenTrace.load("touch-swipe.json")
        assertEquals("realDevice", gt.mode)

        val out = gt.outDecoded()
        // Full out sequence as captured: _touchStart, then N×_hidT, then _touchStop.
        val outNames = out.map { it["_i"] }
        assertEquals("_touchStart", outNames.first(), "fixture must open with _touchStart")
        assertEquals("_touchStop", outNames.last(), "fixture must close with _touchStop")

        // The _hidT subsequence is what swipe() is responsible for.
        val fixtureHidT = out.filter { it["_i"] == "_hidT" }
        val fixtureHidTCount = fixtureHidT.size
        assertTrue(fixtureHidTCount >= 3, "expected >=3 _hidT frames (Press+Hold*+Release)")

        @Suppress("UNCHECKED_CAST")
        val fixturePhases = fixtureHidT.map { (it["_c"] as Map<String, Any?>)["_tPh"].asLong() }
        // First Press, last Release, every middle Hold.
        assertEquals(1L, fixturePhases.first(), "first _hidT must be Press (_tPh=1)")
        assertEquals(4L, fixturePhases.last(), "last _hidT must be Release (_tPh=4)")
        assertTrue(fixturePhases.drop(1).dropLast(1).all { it == 3L },
            "all middle _hidT must be Hold (_tPh=3)")

        // Drive the real transport with steps == fixture _hidT count.
        val fake = FakeProtocol()
        var now = 0L
        val t = TouchTransport(fake, baseNs = 0L) { now }
        // The trailing lambda double-duties as both the stepDelay substitute
        // (no real suspension under runTest) and the nanoClock advance. The
        // 16 ms increment is arbitrary: `_ns` is intentionally never asserted
        // (non-deterministic capture artifact), so its exact value is irrelevant.
        t.swipe(0, 0, 100, 0, steps = fixtureHidTCount) { now += 16_000_000L }

        // swipe() emits ONLY _hidT (sendEvent); none of the _touchStart/_touchStop framing.
        assertTrue(fake.exchanges.none { it.first == "_touchStart" || it.first == "_touchStop" },
            "swipe() must not emit per-gesture _touchStart/_touchStop (pyatv-wins)")
        val emitted = fake.sentEvents
        assertTrue(emitted.all { it.first == "_hidT" }, "swipe() must emit only _hidT events")
        assertEquals(fixtureHidTCount, emitted.size,
            "emitted _hidT count must match the fixture's _hidT count")

        val emittedPhases = emitted.map { it.second["_tPh"].asLong() }
        assertEquals(fixturePhases, emittedPhases,
            "emitted _tPh phase sequence must equal the fixture's _hidT phase sequence")
        // Identifier sequence parity (all _hidT, exactly as the fixture subsequence).
        assertEquals(fixtureHidT.map { it["_i"] }, emitted.map { it.first })
    }

    /**
     * T8 — `hid-click.json`.
     *
     * RESOLVED SPEC DECISION: `hid-click.json` was captured from pyatv
     * `atvremote select` → pyatv `RemoteControl.select` →
     * `_press_button(HidCommand.Select)` — a **BUTTON** path: exactly two
     * `_hidC` frames `{_hBtS:1,_hidC:6}` (down) then `{_hBtS:2,_hidC:6}` (up),
     * no `_hidT`.
     *
     * This is a DIFFERENT operation from our `HidCommands.click(InputAction)`,
     * which is the faithful port of pyatv `CompanionAPI.click()` — a touch path
     * that additionally emits a trailing `_hidT` Click. `click()`'s trailing
     * `_hidT` is correct and is validated against pyatv source separately
     * (see `HidClickTest`); it is intentionally NOT asserted here.
     *
     * So this conformance test asserts our **Select button down/up `_hidC`
     * sequence** (`press(SELECT)` then `release(SELECT)`) matches the fixture's
     * two out `_hidC` frames on (name, `_hBtS`, `_hidC`).
     */
    @Test fun selectButtonMatchesFixture() = runTest {
        val gt = GoldenTrace.load("hid-click.json")
        assertEquals("realDevice", gt.mode)

        val out = gt.outDecoded()
        assertEquals(2, out.size, "select fixture must have exactly two out _hidC frames")

        @Suppress("UNCHECKED_CAST")
        fun row(d: Map<String, Any?>): Triple<Any?, Long, Long> {
            val c = d["_c"] as Map<String, Any?>
            return Triple(d["_i"], c["_hBtS"].asLong(), c["_hidC"].asLong())
        }
        val fixtureSeq = out.map { row(it) }
        assertEquals(
            listOf(
                Triple<Any?, Long, Long>("_hidC", 1L, 6L), // button down, Select=6
                Triple<Any?, Long, Long>("_hidC", 2L, 6L), // button up,   Select=6
            ),
            fixtureSeq,
            "fixture must be Select button down then up",
        )

        // Drive the real button path: press(SELECT) then release(SELECT).
        val fake = FakeProtocol()
        val h = HidCommands(fake)
        h.press(HidCommands.SELECT)
        h.release(HidCommands.SELECT)

        val emittedSeq = fake.exchanges.map { (name, c) ->
            Triple<Any?, Long, Long>(name, c["_hBtS"].asLong(), c["_hidC"].asLong())
        }
        assertTrue(fake.sentEvents.isEmpty(),
            "the Select BUTTON path emits no _hidT (that is the click() touch path, asserted elsewhere)")
        assertEquals(fixtureSeq, emittedSeq,
            "Select button down/up _hidC sequence must equal the fixture's two out _hidC frames")
    }

    /**
     * T10 — `launch-app.json`. Drive `AppsController.launch("com.apple.TVSettings")`
     * and assert the emitted `_launchApp` frame's content `_c` equals the
     * fixture's out request `_c` (`{"_bundleID":"com.apple.TVSettings"}`).
     */
    @Test fun launchAppMatchesFixture() = runTest {
        val gt = GoldenTrace.load("launch-app.json")
        assertEquals("realDevice", gt.mode)

        val req = gt.outDecoded().single()
        assertEquals("_launchApp", req["_i"])
        @Suppress("UNCHECKED_CAST")
        val fixtureC = req["_c"] as Map<String, Any?>
        assertEquals(mapOf("_bundleID" to "com.apple.TVSettings"), fixtureC)

        val fake = FakeProtocol()
        AppsController(fake).launch("com.apple.TVSettings")

        val (name, content) = fake.exchanges.single()
        assertEquals("_launchApp", name)
        assertEquals(fixtureC, content, "emitted _launchApp _c must match the fixture request")
    }

    /**
     * T12 — `media-play.json`. Drive `MediaController.media(Play)` and assert the
     * emitted `_mcc` frame's content `_c` matches the fixture out request
     * (`{"_mcc":1}`).
     */
    @Test fun mediaPlayMatchesFixture() = runTest {
        val gt = GoldenTrace.load("media-play.json")
        assertEquals("realDevice", gt.mode)

        val req = gt.outDecoded().single()
        assertEquals("_mcc", req["_i"])
        @Suppress("UNCHECKED_CAST")
        val fixtureC = req["_c"] as Map<String, Any?>
        assertEquals(1L, fixtureC["_mcc"].asLong(), "fixture _mcc must be 1 (Play)")

        val fake = FakeProtocol()
        MediaController(fake).media(MediaCommand.Play)

        val (name, content) = fake.exchanges.single()
        assertEquals("_mcc", name)
        assertEquals(1L, content["_mcc"].asLong(), "emitted _mcc must be 1 (Play)")
        assertEquals(setOf("_mcc"), content.keys,
            "emitted _mcc _c must contain only the _mcc key (no stray/renamed keys)")
    }

    /**
     * T10 (request-only) — `apps-list.json`.
     *
     * On tvOS 26.5 `FetchLaunchableApplicationsEvent` is UNANSWERED (times out,
     * no reply) — a known upstream Apple tvOS-26 regression
     * (pyatv #2823 / home-assistant #168210); pyatv itself fails identically,
     * so our port is correct. Only the REQUEST shape is capturable / asserted
     * here. Response (`_c` = {bundleId:name}) parsing is covered by the
     * existing `AppsControllerTest`.
     */
    @Test fun appsListRequestShapeMatchesFixture() = runTest {
        val gt = GoldenTrace.load("apps-list.json")
        assertEquals("realDevice", gt.mode)

        val out = gt.outDecoded()
        assertEquals(1, out.size, "apps-list is request-only (no response on tvOS 26.5)")
        assertTrue(gt.inDecoded().isEmpty(), "no response is capturable on tvOS 26.5")
        val req = out.single()
        assertEquals("FetchLaunchableApplicationsEvent", req["_i"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(emptyMap<String, Any?>(), req["_c"] as Map<String, Any?>,
            "request _c must be empty")

        // FakeProtocol.onExchange defaults to emptyMap() — stands in for the
        // tvOS-26.5 no-response behaviour; listApps() degrades to emptyList().
        val fake = FakeProtocol()
        AppsController(fake).listApps()

        val (name, content) = fake.exchanges.single()
        assertEquals("FetchLaunchableApplicationsEvent", name,
            "request identifier must match the fixture")
        assertEquals(emptyMap<String, Any?>(), content,
            "request content must be empty, matching the fixture")
    }

    /**
     * T12 (request-only) — `power-status.json`.
     *
     * On tvOS 26.5 `FetchAttentionState` is UNANSWERED (5 s timeout, no reply)
     * — same upstream Apple tvOS-26 regression (pyatv #2823 / HA #168210);
     * pyatv disables power_state. Only the REQUEST shape is asserted here. The
     * code-first `_c={state:int}` / 0x01–0x04 → PowerStatus mapping is
     * unverifiable on this firmware (no response) and is covered by the
     * existing `PowerControllerTest` with a synthetic response.
     */
    @Test fun powerStatusRequestShapeMatchesFixture() = runTest {
        val gt = GoldenTrace.load("power-status.json")
        assertEquals("realDevice", gt.mode)

        val out = gt.outDecoded()
        assertEquals(1, out.size, "power-status is request-only (no response on tvOS 26.5)")
        assertTrue(gt.inDecoded().isEmpty(), "no response is capturable on tvOS 26.5")
        val req = out.single()
        assertEquals("FetchAttentionState", req["_i"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(emptyMap<String, Any?>(), req["_c"] as Map<String, Any?>,
            "request _c must be empty")

        // FakeProtocol.onExchange defaults to emptyMap() — stands in for the
        // tvOS-26.5 no-response behaviour; status() degrades to Unknown.
        val fake = FakeProtocol()
        PowerController(fake).status()

        val (name, content) = fake.exchanges.single()
        assertEquals("FetchAttentionState", name,
            "request identifier must match the fixture")
        assertEquals(emptyMap<String, Any?>(), content,
            "request content must be empty, matching the fixture")
    }
}
