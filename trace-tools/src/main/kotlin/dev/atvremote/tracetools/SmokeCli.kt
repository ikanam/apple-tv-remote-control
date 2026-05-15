package dev.atvremote.tracetools

import dev.atvremote.protocol.AppleTvDevice
import dev.atvremote.protocol.AppleTvRemote
import dev.atvremote.protocol.PairingState
import dev.atvremote.protocol.RemoteButton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
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
    println("Usage: atvremote [scan|pair <id>|menu <id>]")
}

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
                args[0] == "help" || args[0] == "--help" || args[0] == "-h" -> usage()
                else -> { usage(); exitProcess(1) }
            }
        } catch (e: Exception) {
            System.err.println("error: ${e.message ?: e.javaClass.simpleName}")
            exitProcess(1)
        }
    }
}
