package dev.atvremote.protocol.goldentrace

import dev.atvremote.protocol.HapCredentials

/**
 * Loader for the golden-trace fixtures under
 * `protocol/src/test/resources/goldentrace/`.
 *
 * There are **two kinds**, distinguished by the fixture's `mode` field:
 *
 *  - **synthetic** (`mode != "realDevice"`): pair-setup / pair-verify / HID.
 *    Consumed via [out]`(i)` / [inFrame]`(i)` (byte-indexed step payloads).
 *  - **realDevice** (`mode == "realDevice"`): the command fixtures
 *    (touch-swipe / hid-click / launch-app / media-play / apps-list /
 *    power-status), real tvOS-26.5 pyatv 0.17.0 captures. Consumed via
 *    [outDecoded]`()` / [inDecoded]`()` by
 *    [dev.atvremote.protocol.goldentrace.CommandsGoldenTest].
 *
 * > ⚠️ The synthetic caveat below applies **ONLY to the synthetic kind**
 * > (`mode != "realDevice"`) — NOT to the realDevice command fixtures, which
 * > are genuine real-device captures.
 * >
 * > Synthetic fixtures are NOT real tvOS captures. They are byte-deterministic
 * > traces produced by the in-repo reference oracle
 * > `dev.atvremote.tracetools.GoldenTraceGen` (fixed seeds, no RNG). The oracle
 * > implements BOTH the controller and accessory roles of pair-setup,
 * > pair-verify and the HID command using only the low-level primitives, so it
 * > is INDEPENDENT of the future `PairSetup` / `PairVerify` classes — Tasks
 * > 11/12 are cross-checked AGAINST these fixtures, not the other way round.
 * >
 * > They validate protocol-logic self-consistency only (SRP proofs, Ed25519
 * > signatures, ChaCha20-Poly1305 decrypt, HKDF key derivation). The
 * > authoritative real-device end-to-end validation is **Task 17** (CLI smoke
 * > test) plus replacing these fixtures with a real pyatv capture per
 * > `trace-tools/.../CaptureGuide.md` (which also flips `"mode"` →
 * > `"realDevice"`).
 *
 * Frame / index contract for the **synthetic** kind (so Task 11/12 calls mesh
 * exactly):
 *  - pair-setup steps = [out M1, in M2, out M3, in M4, out M5, in M6]
 *    → `out(0)`=M1, `inFrame(1)`=M2, `out(2)`=M3, `inFrame(3)`=M4,
 *      `out(4)`=M5, `inFrame(5)`=M6
 *  - pair-verify steps = [out M1, in M2, out M3]
 *    → `out(0)`=M1, `inFrame(1)`=M2, `out(2)`=M3
 *
 * `out(i)` / `inFrame(i)` return the i-th *step's* full OPACK payload bytes
 * (the controller wraps `{_pd,_pwTy}` / `{_pd,_auTy}` and the accessory replies
 * in kind), indexed by absolute step position so the indices above hold.
 */
class GoldenTrace private constructor(
    val mode: String,
    val kind: String,
    val note: String,
    private val fixedInputs: Map<String, Any?>,
    val steps: List<StepRec>,
) {
    data class StepRec(
        val dir: String,
        val frameType: Int,
        val opack: ByteArray,
        val decoded: Map<String, Any?>,
    )

    /** i-th step's OPACK payload bytes; asserts it is an outbound (controller) step. */
    fun out(i: Int): ByteArray {
        val s = steps[i]
        require(s.dir == "out") { "step $i is dir='${s.dir}', expected 'out'" }
        return s.opack
    }

    /** i-th step's OPACK payload bytes; asserts it is an inbound (accessory) step. */
    fun inFrame(i: Int): ByteArray {
        val s = steps[i]
        require(s.dir == "in") { "step $i is dir='${s.dir}', expected 'in'" }
        return s.opack
    }

    /**
     * The `decoded` OPACK dicts of every `dir == "out"` step, in capture order.
     *
     * Used by the realDevice command-conformance suite
     * ([dev.atvremote.protocol.goldentrace.CommandsGoldenTest]) to compare the
     * frames our transports emit against the real tvOS capture by *structure*
     * (`_i`, `_c`, `_tPh`, …) — never by raw `_ns`/`_x`. Additive; does not
     * alter the `out`/`inFrame` byte indexing the synthetic Plan-1 goldens use.
     */
    fun outDecoded(): List<Map<String, Any?>> =
        steps.filter { it.dir == "out" }.map { it.decoded }

    /** As [outDecoded] but for every `dir == "in"` step, in capture order. */
    fun inDecoded(): List<Map<String, Any?>> =
        steps.filter { it.dir == "in" }.map { it.decoded }

    // ── Fixed deterministic inputs embedded in the fixture ──────────────────

    /** Controller Ed25519 long-term auth private seed (== HAP clientLtsk seed). */
    val fixedSeed: ByteArray get() = bytes("controllerEd25519Seed")

    /** Controller pairing identifier. */
    val fixedPairingId: ByteArray get() = bytes("controllerPairingId")

    /** The PIN the synthetic Apple TV "displays" (pair-setup fixture only). */
    val pin: String get() = (fixedInputs["pin"]
        ?: error("fixture '$kind' has no fixedInput 'pin' (pair-setup only)")) as String

    /** Controller X25519 ephemeral private (pair-verify fixture only). */
    val fixedX25519Priv: ByteArray get() = bytes("controllerX25519Priv")

    /** Controller X25519 ephemeral public (pair-verify fixture only). */
    val fixedX25519Pub: ByteArray get() = bytes("controllerX25519Pub")

    /**
     * `HapCredentials` a prior (synthetic) pair-setup would have persisted —
     * used to drive the pair-verify fixture (Task 12). `clientLtsk` is the
     * controller Ed25519 seed; `clientLtpk`/`atvLtpk` are the embedded public
     * keys; ids are the embedded controller/accessory identifiers.
     */
    val credentials: HapCredentials
        get() = HapCredentials(
            clientId = bytes("controllerPairingId"),
            clientLtsk = bytes("controllerEd25519Seed"),
            clientLtpk = bytes("clientLtpk"),
            atvId = bytes("accessoryId"),
            atvLtpk = bytes("atvLtpk"),
        )

    private fun bytes(key: String): ByteArray {
        val v = fixedInputs[key] ?: error("fixture '$kind' has no fixedInput '$key'")
        val s = v as String
        require(s.startsWith("hex:")) { "fixedInput '$key' is not a hex string: $s" }
        return hex(s.removePrefix("hex:"))
    }

    companion object {
        /** Load a fixture by file name, e.g. `load("pair-setup.json")`. */
        fun load(name: String): GoldenTrace {
            val path = "/goldentrace/$name"
            val text = GoldenTrace::class.java.getResourceAsStream(path)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: error("golden-trace fixture not found on test classpath: $path")
            val root = MiniJson.parse(text) as Map<*, *>

            @Suppress("UNCHECKED_CAST")
            val stepsRaw = root["steps"] as List<Map<String, Any?>>
            val steps = stepsRaw.map { st ->
                @Suppress("UNCHECKED_CAST")
                StepRec(
                    dir = st["dir"] as String,
                    frameType = (st["frameType"] as Number).toInt(),
                    opack = hex(st["opackHex"] as String),
                    decoded = decodeHexLeaves(st["decoded"]) as Map<String, Any?>,
                )
            }

            @Suppress("UNCHECKED_CAST")
            return GoldenTrace(
                mode = root["mode"] as String,
                kind = root["kind"] as String,
                note = root["note"] as String,
                fixedInputs = root["fixedInputs"] as Map<String, Any?>,
                steps = steps,
            )
        }

        private fun hex(s: String): ByteArray {
            val c = s.removePrefix("hex:")
            return ByteArray(c.length / 2) {
                ((Character.digit(c[it * 2], 16) shl 4) + Character.digit(c[it * 2 + 1], 16)).toByte()
            }
        }

        /** Recursively rewrite `"hex:.."` JSON strings to ByteArray for the decoded tree. */
        private fun decodeHexLeaves(v: Any?): Any? = when (v) {
            is String -> if (v.startsWith("hex:")) hex(v) else v
            is Map<*, *> -> v.entries.associate { it.key as String to decodeHexLeaves(it.value) }
            is List<*> -> v.map { decodeHexLeaves(it) }
            else -> v
        }
    }
}
