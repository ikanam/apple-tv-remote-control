package dev.atvremote.tracetools

import dev.atvremote.protocol.AppleTvDevice
import dev.atvremote.protocol.AppleTvRemote
import dev.atvremote.protocol.ConnectionState
import dev.atvremote.protocol.PairingState
import dev.atvremote.protocol.RemoteButton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * CLI smoke test entry point (Task 17).
 *
 * Compiled class: `dev.atvremote.tracetools.SmokeCliKt`  — matches the locked
 * `application.mainClass` in `trace-tools/build.gradle.kts`.
 *
 * ## Commands
 *
 * | Command              | Description                                          |
 * |----------------------|------------------------------------------------------|
 * | (no args) / `scan`   | Discover Apple TVs on the LAN and print them.        |
 * | `pair <id>`          | Pair with the device; saves credentials to disk.     |
 * | `menu <id>`          | Send a Menu button press (requires prior pairing).   |
 * | `resilience <id> [seconds] [dropAtSec] [restoreAtSec]` | Run the ResilientSession reconnect e2e harness (T18).|
 *
 * ## Credential file
 *
 * Credentials are stored in plain text at `~/.atvremote/credentials`
 * (one `deviceId=serializedCreds` line per device).
 * See [CredentialStore] for the format.  Android production code (Plan 3)
 * uses the Android Keystore / EncryptedSharedPreferences instead.
 *
 * ## Device-skip note (Task 17, Steps 3–4 — DEFERRED)
 *
 * Steps 3 and 4 (live-device pairing and the menu command against a real
 * tvOS 18 Apple TV) are deferred — see `SmokeCli.md` in the same directory
 * for the exact commands and expected output.  Network operations are bounded
 * with [withTimeoutOrNull] so the CLI exits cleanly in environments without a
 * real device.
 */

/** Default credential file: `~/.atvremote/credentials`. */
private val defaultFile: File =
    File(System.getProperty("user.home"), ".atvremote/credentials")

/** Timeout (ms) for mDNS discovery's first emission (used by pair/menu). */
private const val DISCOVERY_TIMEOUT_MS = 8_000L

/**
 * Window (ms) during which `scan` collects ALL arriving discovery emissions
 * before printing the final accumulated device set. Using a bounded window
 * rather than `first { isNotEmpty() }` ensures that a slightly-later-resolving
 * Apple TV (e.g., after a Mac companion-link peer has already resolved) is not missed.
 */
private const val SCAN_WINDOW_MS = 10_000L

/** Timeout (ms) for pair-setup to reach a terminal state. */
private const val PAIR_TIMEOUT_MS = 60_000L

/** Timeout (ms) for connect + button. */
private const val CONNECT_TIMEOUT_MS = 15_000L

private fun usage() {
    println("Usage: atvremote [scan|pair <id>|menu <id>|resilience <id> [seconds] [dropAtSec] [restoreAtSec]]")
}

/** Default observation window (s) for the `resilience` harness. */
private const val RESILIENCE_DEFAULT_SECONDS = 90

/** Default elapsed time (s) at which the operator is told to turn Wi-Fi OFF. */
private const val RESILIENCE_DEFAULT_DROP_AT_SEC = 15

/** Default elapsed time (s) at which the operator is told to turn Wi-Fi back ON. */
private const val RESILIENCE_DEFAULT_RESTORE_AT_SEC = 30

/** Heartbeat interval (ms) inside the `resilience` harness. */
private const val RESILIENCE_HEARTBEAT_MS = 3_000L

/** Post-close quiet-observation window (ms) for the spurious-reconnect check. */
private const val RESILIENCE_POST_CLOSE_MS = 6_000L

private fun err(msg: String, code: Int = 1): Nothing {
    System.err.println("error: $msg")
    exitProcess(code)
}

/**
 * Awaits the first discovery emission that contains a device whose
 * [AppleTvDevice.id] equals [id], bounded by [timeoutMs]. Returns that device,
 * or `null` on timeout (or if the flow completes without ever containing it).
 *
 * [dev.atvremote.protocol.DeviceDiscovery.devices] emits a *cumulative
 * snapshot* of all devices resolved so far on every resolve/remove. A naive
 * `first { it.isNotEmpty() }` stops at the first non-empty snapshot — which on
 * a multi-homed host is typically just the local Mac's own `_companion-link`
 * advert (resolves near-instantly via the local mDNS cache) and does NOT yet
 * contain the later-resolving remote Apple TV. `pair` / `menu` target a
 * *specific* device by id, so we keep collecting snapshots until that id
 * appears (or the timeout fires) instead of stopping at the first list.
 */
internal suspend fun awaitDevice(
    flow: Flow<List<AppleTvDevice>>,
    id: String,
    timeoutMs: Long,
): AppleTvDevice? = withTimeoutOrNull(timeoutMs) {
    flow.mapNotNull { list -> list.find { it.id == id } }.firstOrNull()
}

private suspend fun cmdScan() {
    println("Scanning for Apple TV devices (${SCAN_WINDOW_MS / 1000}s)…")
    // Collect ALL emissions for the full window so a slightly-later-resolving
    // Apple TV is not missed because a faster companion-link peer arrived first.
    var latest: List<AppleTvDevice> = emptyList()
    withTimeoutOrNull(SCAN_WINDOW_MS) {
        AppleTvRemote.discovery().devices().collect { list ->
            latest = list
        }
    }
    if (latest.isEmpty()) {
        println("no devices found")
        return
    }
    println("%-40s  %-20s  %-22s  %-14s  %s".format("id", "name", "host:port", "model", "pairable"))
    for (d in latest) {
        println("%-40s  %-20s  %-22s  %-14s  %s".format(
            d.id,
            d.name,
            "${d.host}:${d.port}",
            d.model ?: "-",
            d.pairable,
        ))
    }
}

private suspend fun cmdPair(id: String) {
    println("Scanning for Apple TV devices (${DISCOVERY_TIMEOUT_MS / 1000}s)…")
    val device = awaitDevice(AppleTvRemote.discovery().devices(), id, DISCOVERY_TIMEOUT_MS)
        ?: err("device '$id' not found in scan results; ensure the Apple TV is on the same network")

    val store = CredentialStore(defaultFile)
    val handle = AppleTvRemote.pair(device)

    val completed = withTimeoutOrNull(PAIR_TIMEOUT_MS) {
        when (val s = handle.state.first { it is PairingState.AwaitingPin || it is PairingState.Completed || it is PairingState.Failed }) {
            is PairingState.AwaitingPin -> {
                print("Enter PIN shown on TV: ")
                System.out.flush()
                val pin = readlnOrNull() ?: run { handle.cancel(); err("no PIN entered; aborting") }
                handle.submitPin(pin)
                // Now wait for terminal state
                handle.state.first { it is PairingState.Completed || it is PairingState.Failed }
            }
            is PairingState.Completed, is PairingState.Failed -> s
        }
    }

    if (completed == null) {
        handle.cancel()
        err("pairing timed out after ${PAIR_TIMEOUT_MS / 1000}s")
    }

    when (completed) {
        is PairingState.Completed -> {
            store.save(device.id, completed.credentials)
            println("Paired: ${device.id}")
            println("Credentials saved to: ${defaultFile.absolutePath}")
        }
        is PairingState.Failed -> {
            err("Pairing failed: ${completed.reason}", 2)
        }
        else -> err("unexpected pairing state: $completed")
    }
}

private suspend fun cmdMenu(id: String) {
    println("Scanning for Apple TV devices (${DISCOVERY_TIMEOUT_MS / 1000}s)…")
    val device = awaitDevice(AppleTvRemote.discovery().devices(), id, DISCOVERY_TIMEOUT_MS)
        ?: err("device '$id' not found in scan results; ensure the Apple TV is on the same network")

    val store = CredentialStore(defaultFile)
    val creds = store.load(device.id)
        ?: err("no stored credentials for '${device.id}'; run 'pair ${device.id}' first")

    val result = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
        val session = AppleTvRemote.connect(device, creds)
        try {
            session.button(RemoteButton.Menu, true)
            session.button(RemoteButton.Menu, false)
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }

    if (result == null) {
        err("connect/button timed out after ${CONNECT_TIMEOUT_MS / 1000}s")
    }
    println("OK")
}

/**
 * Resilience e2e harness (T18) — exercises the [dev.atvremote.protocol]
 * `ResilientSession` reconnect supervisor against a real Apple TV while the
 * operator manually toggles the Mac's network.
 *
 * This is intentionally device-driven and NOT bounded by [withTimeoutOrNull]:
 * a socket drop must NOT end the harness — the whole point is to keep the
 * session alive across the drop and watch the supervisor reconnect. The window
 * (default [RESILIENCE_DEFAULT_SECONDS] s, overridable via the optional 2nd
 * arg) is the only timer; on expiry we deliberately [CompanionSession.close]
 * and then observe for [RESILIENCE_POST_CLOSE_MS] that a *deliberate* close
 * does not trigger a spurious reconnect (the supervisor must be cancelled by
 * `close()`).
 *
 * All lines carry a wall-clock timestamp and a `T+<ms>` elapsed marker so the
 * operator can correlate log lines with their manual network toggling.
 */
private suspend fun cmdResilience(
    id: String,
    seconds: Int,
    dropAtSec: Int,
    restoreAtSec: Int,
) = coroutineScope {
    val tsFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    val started = System.nanoTime()
    fun elapsedMs(): Long = (System.nanoTime() - started) / 1_000_000
    fun log(msg: String) {
        val wall = java.time.LocalTime.now().format(tsFmt)
        println("[$wall][T+${elapsedMs()}ms] $msg")
    }

    println("=== resilience e2e harness (T18) ===")
    println(
        "While this runs: after you see state=Connected and a Menu reaction on the TV, " +
            "disable the Mac's network (or Wi-Fi) for ~10-15 s, then re-enable it; watch for " +
            "Reconnecting → Connected and a burst of queued Menu reactions on the TV.",
    )
    println("Follow the >>> ACTION NOW <<< prompts below for Wi-Fi timing; do NOT pre-toggle.")
    println("Observation window: ${seconds}s, then a deliberate close + ${RESILIENCE_POST_CLOSE_MS / 1000}s spurious-reconnect check.")
    println()

    println("Scanning for Apple TV devices (${DISCOVERY_TIMEOUT_MS / 1000}s)…")
    val device = awaitDevice(AppleTvRemote.discovery().devices(), id, DISCOVERY_TIMEOUT_MS)
        ?: err("device '$id' not found in scan results; ensure the Apple TV is on the same network")

    val store = CredentialStore(defaultFile)
    val creds = store.load(device.id)
        ?: err("no stored credentials for '${device.id}'; run 'pair ${device.id}' first")

    log("connecting to ${device.id}…")
    val session = AppleTvRemote.connect(device, creds)
    log("connected; initial connectionState=${session.connectionState.value}")

    // Marker after which any transition to Reconnecting is "spurious".
    // closed=false → every Reconnecting before close is expected/manual.
    // The collector coroutine and the heartbeat run on the same single-thread
    // runBlocking dispatcher (no IO dispatcher), so plain vars are visible
    // across suspension points — a holder keeps the mutation explicit.
    class CloseMarker {
        var closed = false
        var spuriousReconnect = false
    }
    val marker = CloseMarker()

    // Long-running collector: prints EVERY state value (StateFlow replays the
    // current value to a new collector, so the initial state prints at once).
    // Stays alive through the deliberate close + post-close observation so we
    // can detect a reconnect that fires AFTER close().
    val collector = launch {
        session.connectionState.collect { st ->
            log("state=$st")
            if (st == ConnectionState.Reconnecting && marker.closed) {
                marker.spuriousReconnect = true
                log("!! transition to Reconnecting AFTER deliberate close (spurious)")
            }
        }
    }

    // Dedicated operator-prompt coroutine: a sibling of the collector/heartbeat
    // inside this coroutineScope. It only sleeps then prints loud banners via
    // the shared log() helper, so it can never delay the heartbeat/collector;
    // it is cancelled cleanly before this function returns (no hang).
    val barLine = "=".repeat(60)
    val prompter = launch {
        delay(dropAtSec * 1000L)
        log(barLine)
        log(">>> ACTION NOW: turn Mac Wi-Fi OFF (keep OFF ~12-15s) <<<")
        log("    (this also disconnects Claude — expected; harness keeps logging)")
        log(barLine)
        delay((restoreAtSec - dropAtSec) * 1000L)
        log(barLine)
        log(">>> ACTION NOW: turn Mac Wi-Fi back ON <<<")
        log(barLine)
    }

    var listAppsProbed = false
    val deadlineMs = seconds.toLong() * 1000L
    while (elapsedMs() < deadlineMs) {
        delay(RESILIENCE_HEARTBEAT_MS)
        if (elapsedMs() >= deadlineMs) break
        val state = session.connectionState.value
        log("heartbeat: connectionState=$state")

        // First time we observe Reconnecting from the heartbeat: assert that a
        // session-scoped call throws BEFORE delegating (ResilientSession guard).
        if (!listAppsProbed && state == ConnectionState.Reconnecting) {
            listAppsProbed = true
            try {
                val apps = session.listApps()
                log("listApps during Reconnecting returned ${apps.size} app(s) — UNEXPECTED (guard should have thrown)")
            } catch (e: Exception) {
                log("listApps during Reconnecting threw ${e.javaClass.simpleName}: ${e.message} (expected: CompanionUnavailableException)")
            }
        }

        try {
            session.button(RemoteButton.Menu, true)
            session.button(RemoteButton.Menu, false)
            when (state) {
                ConnectionState.Connected ->
                    log("Menu press+release sent while Connected (TV should react)")
                else ->
                    log("Menu press+release issued during $state (expected: queued/flushed on reconnect)")
            }
        } catch (e: Exception) {
            log("Menu attempt threw ${e.javaClass.simpleName}: ${e.message} (continuing)")
        }
    }

    println("=== deliberate close ===")
    log("=== deliberate close === (window ${seconds}s elapsed; closing session)")
    marker.closed = true
    withContext(NonCancellable) { session.close() }
    log("close() returned; observing ${RESILIENCE_POST_CLOSE_MS / 1000}s for spurious reconnect…")

    delay(RESILIENCE_POST_CLOSE_MS)
    collector.cancel()
    // Cancel the operator-prompt coroutine too (it has normally already
    // finished both banners by now; cancel covers a short-`seconds` run that
    // ends before restoreAtSec). Ensures no lingering child → no hang.
    prompter.cancel()

    val verdict = if (marker.spuriousReconnect) {
        "RESULT: SPURIOUS-RECONNECT-AFTER-CLOSE"
    } else {
        "RESULT: clean-close-no-spurious-reconnect"
    }
    log(verdict)
    println(verdict)
}

fun main(args: Array<String>) {
    runBlocking {
        try {
            when {
                args.isEmpty() || args[0] == "scan" -> cmdScan()
                args[0] == "pair" -> {
                    val id = args.getOrNull(1) ?: run { usage(); exitProcess(1) }
                    cmdPair(id)
                }
                args[0] == "menu" -> {
                    val id = args.getOrNull(1) ?: run { usage(); exitProcess(1) }
                    cmdMenu(id)
                }
                args[0] == "resilience" -> {
                    val id = args.getOrNull(1) ?: run { usage(); exitProcess(1) }
                    val seconds = args.getOrNull(2)?.let {
                        it.toIntOrNull()?.takeIf { n -> n > 0 }
                            ?: err("invalid seconds '$it'; expected a positive integer")
                    } ?: RESILIENCE_DEFAULT_SECONDS
                    val dropAtSec = args.getOrNull(3)?.let {
                        it.toIntOrNull()?.takeIf { n -> n > 0 }
                            ?: err("invalid dropAtSec '$it'; expected a positive integer")
                    } ?: RESILIENCE_DEFAULT_DROP_AT_SEC
                    val restoreAtSec = args.getOrNull(4)?.let {
                        it.toIntOrNull()?.takeIf { n -> n > 0 }
                            ?: err("invalid restoreAtSec '$it'; expected a positive integer")
                    } ?: RESILIENCE_DEFAULT_RESTORE_AT_SEC
                    if (!(0 < dropAtSec && dropAtSec < restoreAtSec && restoreAtSec < seconds)) {
                        err(
                            "Wi-Fi prompt offsets must satisfy 0 < dropAtSec < restoreAtSec < seconds " +
                                "(got dropAtSec=$dropAtSec restoreAtSec=$restoreAtSec seconds=$seconds); " +
                                "the defaults are dropAtSec=$RESILIENCE_DEFAULT_DROP_AT_SEC " +
                                "restoreAtSec=$RESILIENCE_DEFAULT_RESTORE_AT_SEC, so a small 'seconds' " +
                                "needs explicit smaller offsets, e.g. 'resilience <id> 20 5 12'",
                        )
                    }
                    cmdResilience(id, seconds, dropAtSec, restoreAtSec)
                }
                args[0] == "help" || args[0] == "--help" || args[0] == "-h" -> usage()
                else -> { usage(); exitProcess(1) }
            }
        } catch (e: Exception) {
            System.err.println("error: ${e.message ?: e.javaClass.simpleName}")
            exitProcess(1)
        }
    }
}
