package dev.atvremote.tracetools

import dev.atvremote.protocol.AppleTvRemote
import dev.atvremote.protocol.PairingState
import dev.atvremote.protocol.RemoteButton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

/** Timeout (ms) for mDNS discovery's first emission. */
private const val DISCOVERY_TIMEOUT_MS = 8_000L

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
 * Discover devices (bounded by [DISCOVERY_TIMEOUT_MS]).
 * Returns the first non-empty list, or an empty list on timeout / no devices.
 */
private suspend fun discover(): List<dev.atvremote.protocol.AppleTvDevice> {
    val result = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
        AppleTvRemote.discovery().devices().first { it.isNotEmpty() }
    }
    return result ?: emptyList()
}

private suspend fun cmdScan() {
    println("Scanning for Apple TV devices (${DISCOVERY_TIMEOUT_MS / 1000}s)…")
    val devices = discover()
    if (devices.isEmpty()) {
        println("no devices found")
        return
    }
    for (d in devices) {
        println("${d.id}\t${d.name}\t${d.host}:${d.port}\t${d.model ?: "-"}\t${d.pairable}")
    }
}

private suspend fun cmdPair(id: String) {
    println("Scanning for Apple TV devices (${DISCOVERY_TIMEOUT_MS / 1000}s)…")
    val devices = discover()
    val device = devices.find { it.id == id }
        ?: err("device '$id' not found in scan results; ensure the Apple TV is on the same network")

    val store = CredentialStore(defaultFile)
    val handle = AppleTvRemote.pair(device)

    val completed = withTimeoutOrNull(PAIR_TIMEOUT_MS) {
        // Wait for AwaitingPin
        handle.state.first { it is PairingState.AwaitingPin || it is PairingState.Completed || it is PairingState.Failed }

        when (val s = handle.state.value) {
            is PairingState.AwaitingPin -> {
                print("Enter PIN shown on TV: ")
                System.out.flush()
                val pin = readlnOrNull() ?: err("no PIN entered; aborting")
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
    val devices = discover()
    val device = devices.find { it.id == id }
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
            session.close()
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
