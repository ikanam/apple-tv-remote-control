package dev.atvremote.protocol

import dev.atvremote.protocol.connection.CompanionConnection
import dev.atvremote.protocol.connection.CompanionProtocol
import dev.atvremote.protocol.crypto.Curves
import dev.atvremote.protocol.frame.FrameType
import dev.atvremote.protocol.pairing.PairVerify
import dev.atvremote.protocol.pairing.PairingHandleImpl
import dev.atvremote.protocol.session.CompanionSessionImpl
import dev.atvremote.protocol.session.SessionHandshake
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
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

            // Await the accessory's pair-verify M4 (PV_Next, `{ _pd: { State: 4 } }`)
            // BEFORE enabling encryption — matches pyatv, whose `exchange_auth`
            // awaits the M3 response. [conn.frames] is replay-buffered, so M2 is
            // still cached as the 1st PV_Next; positionally skip it and take the
            // 2nd PV_Next (= M4). If we enabled encryption first, this plaintext
            // M4 would be fed to the cipher and corrupt the read loop.
            withTimeout(5_000) {
                conn.frames()
                    .filter { (ft, _) -> ft == FrameType.PV_Next }
                    .drop(1)
                    .first()
            }

            // Enable session encryption with the derived connection keys.
            val (outKey, inKey) = pv.connectionKeys()
            conn.enableEncryption(outKey, inKey)

            // 4. Session handshake (five exchanges that complete the Companion session setup).
            //    [credentials.clientId] is the UTF-8 bytes of the pair-setup
            //    pairing id (a UUID string) — the SAME identity the Apple TV
            //    just authenticated in pair-verify. It must be sent VERBATIM as
            //    the controller id (`_idsID`/`_i`/`_pubID`), NOT hex-re-encoded
            //    (pyatv sends `creds.client_id`). A mismatched `_idsID` makes
            //    tvOS ack `_systemInfo` but silently drop `_touchStart`.
            val clientIdStr = String(credentials.clientId, Charsets.UTF_8)
            val handshake = SessionHandshake(
                proto,
                deviceId = clientIdStr,
                clientId = clientIdStr,
                name = "Android",
                model = "Android",
            )
            handshake.run()

            // 5. Return a live CompanionSession; onClose tears down protocol +
            //    connection. The negotiated [SessionHandshake.sid] is passed so
            //    `_sessionStop` is accepted by tvOS (else "No sessionID").
            return CompanionSessionImpl(proto, sid = handshake.sid, onClose = {
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
        try {
            runBlocking { conn.connect() }
        } catch (t: Throwable) {
            runCatching { runBlocking { conn.close() } }
            throw t
        }
        return PairingHandleImpl(conn)
    }
}
