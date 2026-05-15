package dev.atvremote.protocol

import dev.atvremote.protocol.connection.CompanionConnection
import dev.atvremote.protocol.connection.CompanionProtocol
import dev.atvremote.protocol.crypto.Curves
import dev.atvremote.protocol.frame.FrameType
import dev.atvremote.protocol.pairing.PairVerify
import dev.atvremote.protocol.pairing.PairingHandleImpl
import dev.atvremote.protocol.session.CompanionSessionImpl
import dev.atvremote.protocol.session.SessionHandshake
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Internal entry point for [AppleTvRemote.connect].
 *
 * Pair-verify integration note:
 *   [CompanionProtocol.sendAuth] expects an unpacked `Map<String, Any?>` and
 *   calls `Opack.pack()` internally before sending the frame. However,
 *   [PairVerify.buildM1] / [PairVerify.buildM3] already return a fully packed
 *   OPACK `ByteArray` (they call `Opack.pack` themselves in `wrap()`). Repacking
 *   a byte array as a map would corrupt the payload. Therefore pair-verify frames
 *   are driven directly through [CompanionConnection.send] + [CompanionConnection.frames],
 *   which is the lower-level [FrameTransport] API that accepts raw bytes without
 *   any additional OPACK wrapping. This avoids modifying either [PairVerify] or
 *   [CompanionProtocol].
 *
 * NOTE: This real-device connect path is NOT covered by unit tests (Task 14's
 * locked ButtonTest exercises only the [CompanionSessionImpl] + [CommandChannel]
 * surface). End-to-end validation is deferred to Task 17 (CLI smoke test on a
 * real Apple TV).
 */
internal object RemoteConnect {

    suspend fun connect(
        device: AppleTvDevice,
        credentials: HapCredentials,
    ): CompanionSession {
        // 1. Open TCP connection to Apple TV.
        val conn = CompanionConnection(device.host, device.port)
        conn.connect()

        // 2. Build the protocol layer on top.
        val proto = CompanionProtocol(conn)

        try {
            // 3. HAP Pair-Verify (M1 → M2 → M3).
            //
            //    Integration path: send raw bytes via conn.send(), await PV_Next from
            //    conn.frames(). See file-level KDoc for why sendAuth() is not used here.
            val (xPriv, xPub) = Curves.newX25519()
            val pv = PairVerify(credentials, xPriv, xPub)

            // M1: controller → ATV (PV_Start frame, pre-packed OPACK payload)
            conn.send(FrameType.PV_Start, pv.buildM1())

            // M2: ATV → controller (PV_Next frame, pre-packed OPACK payload).
            // Bounded to 5 s — parity with CompanionProtocol.sendAuth timeout.
            val (_, m2Payload) = withTimeout(5_000) {
                conn.frames().first { (ft, _) -> ft == FrameType.PV_Next }
            }
            pv.consumeM2(m2Payload)

            // M3: controller → ATV (PV_Next frame for the response, pre-packed OPACK payload)
            conn.send(FrameType.PV_Next, pv.buildM3())

            // Enable session encryption with the derived connection keys.
            val (outKey, inKey) = pv.connectionKeys()
            conn.enableEncryption(outKey, inKey)

            // 4. Session handshake (five exchanges that complete the Companion session setup).
            //    clientId bytes → hex string used as the controller identifier in the handshake.
            val clientIdStr = credentials.clientId.joinToString("") { "%02x".format(it) }
            SessionHandshake(
                proto,
                deviceId = device.id,
                clientId = clientIdStr,
                name = "Android",
                model = "Android",
            ).run()

            // 5. Return a live CompanionSession; onClose tears down protocol + connection.
            return CompanionSessionImpl(proto, onClose = {
                proto.close()
                conn.close()
            })
        } catch (t: Throwable) {
            runCatching { proto.close() }
            runCatching { conn.close() }
            throw t
        }
    }

    /**
     * Entry point for [AppleTvRemote.pair].
     *
     * Opens a real [CompanionConnection] to the Apple TV and returns a
     * [PairingHandleImpl] driving HAP pair-setup over it. The locked
     * [AppleTvRemote.pair] signature is non-suspend, so the (real-clock,
     * Dispatchers.IO) socket connect is performed via [runBlocking] —
     * consistent with [RemoteConnectTimeoutTest]'s real-clock convention; it
     * returns once the TCP socket is established, before any PIN is submitted.
     *
     * The handle uses a fresh random Ed25519 seed + pairing id because the
     * production [CompanionConnection] does not implement
     * [dev.atvremote.protocol.pairing.PairingKeys] (only the scripted test
     * double does).
     *
     * NOTE: this real-device pair path is NOT covered by unit tests — only the
     * scripted golden path (`PairingHandleTest`) is. End-to-end validation on a
     * real Apple TV is deferred to **Task 17** (CLI smoke test).
     */
    fun pair(device: AppleTvDevice): PairingHandle {
        val conn = CompanionConnection(device.host, device.port)
        runBlocking { conn.connect() }
        return PairingHandleImpl(conn)
    }
}
