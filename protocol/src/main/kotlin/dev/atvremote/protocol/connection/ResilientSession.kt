package dev.atvremote.protocol.connection

import dev.atvremote.protocol.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Decorator over a live CompanionSession implementing spec §7 resilience policy:
 *  - connectionState is OWNED here (authority); mutated only via setState()/
 *    onReconnected() from the supervisor. It does NOT mirror the delegate
 *    (a standalone CompanionSessionImpl is always Connected — Task 19).
 *  - while Reconnecting/Disconnected:
 *      touch()  -> dropped silently (gesture stream is disposable)
 *      button()/click() -> briefly queued, flushed on reconnect
 *      keyboard/app/power/media -> throw CompanionUnavailableException
 *  - onReconnected() replays the queued buttons/clicks in order.
 * The actual socket-drop detection + backoff + pair-verify + handshake + subscription
 * restore is driven by RemoteConnect.connect's supervisor, which calls
 * setState()/swapDelegate()/onReconnected() on this object (revision-log B/C).
 */
class ResilientSession(initial: CompanionSession) : CompanionSession {
    @Volatile private var delegate: CompanionSession = initial
    private val _state = MutableStateFlow(ConnectionState.Connected)
    override val connectionState: StateFlow<ConnectionState> = _state
    private val pending = ConcurrentLinkedQueue<suspend () -> Unit>()

    /**
     * Assigned by [dev.atvremote.protocol.RemoteConnect.connect] immediately after the
     * supervisor coroutine is launched. Invoked in [close] to cancel the supervisor scope
     * before tearing down the delegate, ensuring a deliberate close never triggers a
     * reconnect attempt. Kept minimal — no over-hardening.
     */
    @Volatile internal var cancelSupervisor: (() -> Unit)? = null

    fun setState(s: ConnectionState) { _state.value = s }

    /** Supervisor: install the freshly-reconnected session before flushing the queue. */
    fun swapDelegate(next: CompanionSession) { delegate = next }

    /**
     * Called by the supervisor after a successful reconnect: optionally swaps the delegate,
     * flips state to Connected, and flushes the queued button/click ops in order.
     * [next] non-null on a real reconnect (supervisor swaps in the new session);
     * null in unit tests that exercise queue flush without a delegate swap.
     *
     * NOTE: `keyboardFocus` is a `get()` over the current delegate, so callers should
     * read `session.keyboardFocus` per-use rather than caching the StateFlow across a
     * reconnect (documented decorator limitation; acceptable for v1).
     */
    suspend fun onReconnected(next: CompanionSession? = null) {
        if (next != null) delegate = next
        _state.value = ConnectionState.Connected
        while (true) {
            val op = pending.poll() ?: break
            op()
        }
    }

    private fun connected() = _state.value == ConnectionState.Connected

    private fun requireUp(call: String) {
        if (!connected()) throw CompanionUnavailableException(
            "$call unavailable: connection is ${_state.value}")
    }

    override suspend fun button(button: RemoteButton, down: Boolean) {
        if (connected()) delegate.button(button, down)
        else if (pending.size < 32) pending.add { delegate.button(button, down) }
    }

    override suspend fun click(action: InputAction) {
        if (connected()) delegate.click(action)
        else if (pending.size < 32) pending.add { delegate.click(action) }
    }

    override suspend fun touch(x: Int, y: Int, phase: TouchPhase) {
        if (connected()) delegate.touch(x, y, phase)   // else drop silently
    }

    override suspend fun textGet(): String { requireUp("textGet"); return delegate.textGet() }
    override suspend fun textSet(text: String) { requireUp("textSet"); delegate.textSet(text) }
    override suspend fun textClear() { requireUp("textClear"); delegate.textClear() }
    override suspend fun textAppend(text: String) { requireUp("textAppend"); delegate.textAppend(text) }
    override suspend fun listApps(): List<InstalledApp> { requireUp("listApps"); return delegate.listApps() }
    override suspend fun launchApp(bundleId: String) { requireUp("launchApp"); delegate.launchApp(bundleId) }
    override suspend fun power(on: Boolean) { requireUp("power"); delegate.power(on) }
    override suspend fun powerStatus(): PowerStatus { requireUp("powerStatus"); return delegate.powerStatus() }
    override suspend fun media(command: MediaCommand) { requireUp("media"); delegate.media(command) }

    override val keyboardFocus get() = delegate.keyboardFocus

    override suspend fun close() {
        // Cancel the supervisor first so a deliberate close() does not trigger a reconnect.
        cancelSupervisor?.invoke()
        delegate.close()
    }
}
