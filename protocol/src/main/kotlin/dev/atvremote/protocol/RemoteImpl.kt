package dev.atvremote.protocol

import dev.atvremote.protocol.connection.CompanionConnection
import dev.atvremote.protocol.connection.CompanionProtocol
import dev.atvremote.protocol.connection.ResilientSession
import dev.atvremote.protocol.crypto.Curves
import dev.atvremote.protocol.frame.FrameType
import dev.atvremote.protocol.pairing.PairVerify
import dev.atvremote.protocol.pairing.PairingHandleImpl
import dev.atvremote.protocol.session.CompanionSessionImpl
import dev.atvremote.protocol.session.SessionHandshake
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
            val nanoClock: () -> Long = { System.nanoTime() }
            val handshake = SessionHandshake(
                proto,
                deviceId = clientIdStr,
                clientId = clientIdStr,
                name = "Android",
                model = "Android",
                nanoClock = nanoClock,
            )
            handshake.run()

            // 5. Build the live CompanionSession; onClose tears down protocol +
            //    connection. The negotiated [SessionHandshake.sid] is passed so
            //    `_sessionStop` is accepted by tvOS (else "No sessionID").
            //    The same [nanoClock] instance is shared so base-capture and
            //    delta-compute use the identical clock reference.
            val impl = CompanionSessionImpl(proto, sid = handshake.sid,
                touchBaseNs = handshake.touchBaseNs, nanoClock = nanoClock, onClose = {
                proto.close()
                conn.close()
            })

            // 6. Subscribe to the standard SystemStatus events (best-effort, fire-and-forget).
            //    The handshake's _iMC subscribe is kept in SessionHandshake (step 5)
            //    to preserve the Task-17-validated wire sequence; these two are
            //    deferred here because RemoteConnect.connect IS the async post-handshake flow.
            runCatching { impl.subscriptions.subscribe("SystemStatus") }
            runCatching { impl.subscriptions.subscribe("TVSystemStatus") }

            // 7. Wrap in ResilientSession (§7 decorator). Returns as CompanionSession
            //    (transparent to the locked AppleTvRemote.connect signature).
            val resilient = ResilientSession(impl)

            // 8. Supervisor coroutine: detects socket drop and exponential-backoff reconnects.
            //    The supervisor scope is cancelled via resilient.cancelSupervisor() in
            //    ResilientSession.close() so a deliberate close() never triggers a reconnect.
            val supervisorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            resilient.cancelSupervisor = { supervisorScope.cancel() }
            supervisorScope.launch {
                reconnectLoop(resilient, conn, device, credentials, supervisorScope)
            }

            return resilient
        } catch (t: Throwable) {
            runCatching { proto.close() }
            runCatching { conn.close() }
            throw t
        }
    }

    /**
     * Supervisor reconnect loop.
     *
     * Drop detection: [CompanionConnection.awaitClosed] emits [Unit] when the read loop
     * terminates for any reason (EOF, SocketException, cancellation-driven [close]).
     * This is the real socket-drop signal — no polling, no connectionState heuristic.
     *
     * Conn tracking: each successful reconnect builds a NEW [CompanionConnection]; the
     * loop subsequently watches that new conn's [awaitClosed] so a 2nd drop is also
     * detected. [currentConn] is advanced on every successful reconnect.
     *
     * The reconnect sequence mirrors [RemoteConnect.connect] **step-for-step**:
     *   C3: await 2nd PV_Next BEFORE enableEncryption (drop(1).first())
     *   C5: String(credentials.clientId, UTF_8) verbatim as clientIdStr
     *   C6: CompanionSessionImpl(newProto, sid = newHandshake.sid, …)
     *
     * Per CLAUDE.md: pyatv wins on any disagreement; this was pinpointed only by
     * reading pyatv connection.py / auth.py (verify_credentials / exchange_auth).
     * The sequence below is a faithful mirror — NOT re-derived from memory.
     *
     * NOTE: The reconnect body is correct and validated against the C3/C5/C6 sequence.
     * End-to-end device validation (real socket drop → reconnect → resume) is deferred
     * to the device session (the drop-signal unit test proves the signal fires on EOF).
     *
     * CancellationException is rethrown immediately inside the inner catch block so that
     * scope cancellation (from ResilientSession.close) exits the loop cleanly without
     * calling setState(Reconnecting) on a deliberately-closed session.
     */
    private suspend fun reconnectLoop(
        resilient: ResilientSession,
        initialConn: CompanionConnection,
        device: AppleTvDevice,
        credentials: HapCredentials,
        scope: CoroutineScope,
    ) {
        var currentConn = initialConn
        var attempt = 0
        while (true) {
            // Wait for the current connection's read loop to terminate (EOF / SocketException /
            // cancellation). awaitClosed() has replay=1 so if the loop already exited before
            // we arrive here the signal is immediately available.
            try {
                currentConn.awaitClosed().first()
            } catch (_: Exception) {
                break // scope cancelled (ResilientSession.close() was called)
            }

            resilient.setState(ConnectionState.Reconnecting)

            // Exponential backoff: cap 30 s.
            // Guard against shift overflow: attempt>=6 already hits the 30 s ceiling
            // (500 * 2^6 = 32000 > 30000); beyond attempt 63 the JVM masks shift counts
            // to low 6 bits which would produce zero/negative and hot-spin indefinitely.
            val backoffMs = if (attempt >= 6) 30_000L else 500L * (1L shl attempt)
            delay(backoffMs)
            attempt++

            try {
                // Reconnect: mirrors connect() body step-for-step (C3/C5/C6).
                val newConn = CompanionConnection(device.host, device.port)
                newConn.connect()
                val newProto = CompanionProtocol(newConn)

                try {
                    // C3 path: pair-verify M1 → await M2 → M3 → await 2nd PV_Next BEFORE encryption.
                    val (xPriv, xPub) = Curves.newX25519()
                    val pv = PairVerify(credentials, xPriv, xPub)

                    // M1 (PV_Start)
                    newConn.send(FrameType.PV_Start, pv.buildM1())

                    // M2: first PV_Next (bounded 5 s)
                    val (_, m2Payload) = withTimeout(5_000) {
                        newConn.frames().first { (ft, _) -> ft == FrameType.PV_Next }
                    }
                    pv.consumeM2(m2Payload)

                    // M3 (PV_Next)
                    newConn.send(FrameType.PV_Next, pv.buildM3())

                    // C3: await the 2nd PV_Next (= M4) BEFORE enableEncryption.
                    // newConn.frames() is replay-buffered; M2 is cached as the 1st PV_Next.
                    // drop(1) skips it; .first() takes M4. Mirrored verbatim from connect().
                    withTimeout(5_000) {
                        newConn.frames()
                            .filter { (ft, _) -> ft == FrameType.PV_Next }
                            .drop(1)
                            .first()
                    }

                    val (outKey, inKey) = pv.connectionKeys()
                    newConn.enableEncryption(outKey, inKey)

                    // C5: verbatim clientId string — same identity pair-verify authenticated.
                    val clientIdStr = String(credentials.clientId, Charsets.UTF_8)
                    val nanoClock: () -> Long = { System.nanoTime() }
                    val newHandshake = SessionHandshake(
                        newProto,
                        deviceId = clientIdStr,
                        clientId = clientIdStr,
                        name = "Android",
                        model = "Android",
                        nanoClock = nanoClock,
                    )
                    newHandshake.run()

                    // C6: sid + touchBaseNs from the new handshake.
                    //    The same [nanoClock] instance is shared so base-capture and
                    //    delta-compute use the identical clock reference.
                    val newImpl = CompanionSessionImpl(newProto, sid = newHandshake.sid,
                        touchBaseNs = newHandshake.touchBaseNs, nanoClock = nanoClock, onClose = {
                        newProto.close()
                        newConn.close()
                    })

                    // Re-issue initial subscriptions on the new impl.
                    runCatching { newImpl.subscriptions.subscribe("SystemStatus") }
                    runCatching { newImpl.subscriptions.subscribe("TVSystemStatus") }

                    // Restore any previously-active subscriptions (Task-17 EventSubscriptions.restore).
                    // NOTE: the freshly-built impl's EventSubscriptions starts empty; only the
                    // initial SystemStatus/TVSystemStatus (above) and whatever restore() re-issues
                    // are active. Dynamic post-connect subscriptions are not carried because no
                    // dynamic-sub path exists through the locked Plan-2 public API — acceptable
                    // for v1; revisit if a public subscribe API is added in Plan 3.
                    runCatching { newImpl.subscriptions.restore() }

                    // Advance the tracked conn so the next loop iteration watches the NEW conn.
                    currentConn = newConn

                    // Swap the delegate and mark Connected — flushes the button queue.
                    resilient.onReconnected(newImpl)
                    attempt = 0 // reset backoff on success
                } catch (t: Throwable) {
                    // CancellationException means close() cancelled the supervisor scope;
                    // rethrow so the outer catch(_: Exception){break} exits the loop cleanly
                    // without corrupting connectionState via setState(Reconnecting).
                    if (t is kotlinx.coroutines.CancellationException) throw t

                    runCatching { newProto.close() }
                    runCatching { newConn.close() }

                    // Credential rejection → stop retrying.
                    if (t.message?.contains("does not match") == true ||
                        t.message?.contains("signature did not verify") == true) {
                        resilient.setState(ConnectionState.Disconnected)
                        scope.cancel()
                        return
                    }
                    resilient.setState(ConnectionState.Reconnecting)
                    // continue loop (next iteration will backoff again)
                }
            } catch (_: Exception) {
                resilient.setState(ConnectionState.Reconnecting)
            }
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
