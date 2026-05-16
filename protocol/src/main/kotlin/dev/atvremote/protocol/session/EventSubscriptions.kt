package dev.atvremote.protocol.session

import dev.atvremote.protocol.connection.CommandChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * Event subscription manager — ports pyatv/protocols/companion/api.py
 * subscribe_event (L250–254) / unsubscribe_event (L256–260):
 *   subscribe   -> sendEvent("_interest", {"_regEvents":[name]})   then track
 *   unsubscribe -> sendEvent("_interest", {"_deregEvents":[name]}) then untrack
 * (track AFTER a successful send, mirroring pyatv — a thrown sendEvent leaves
 *  the active set unchanged so a retry/restore() re-sends correctly.)
 * `_interest` is a fire-and-forget event (_send_event); restore() re-sends
 * _regEvents for every active name (Task-18 reconnect). Dedup is a membership
 * check (non-atomic, single-session — same property as pyatv's asyncio path).
 */
internal class EventSubscriptions(private val ch: CommandChannel) {
    private val active = ConcurrentHashMap.newKeySet<String>()

    /** Returns an immutable snapshot of the currently-subscribed event names. */
    fun active(): Set<String> = active.toSet()

    /**
     * Subscribe to [name] if not already subscribed.
     * Sends `_interest { _regEvents: [name] }` then records [name] in the active set
     * (track AFTER send — mirrors pyatv subscribe_event L250–254).
     */
    suspend fun subscribe(name: String) {
        if (active.contains(name)) return
        ch.sendEvent("_interest", mapOf("_regEvents" to listOf(name)))
        active.add(name)
    }

    /**
     * Unsubscribe from [name] if currently subscribed.
     * Sends `_interest { _deregEvents: [name] }` then removes [name] from the active set
     * (untrack AFTER send — mirrors pyatv unsubscribe_event L256–260).
     */
    suspend fun unsubscribe(name: String) {
        if (!active.contains(name)) return
        ch.sendEvent("_interest", mapOf("_deregEvents" to listOf(name)))
        active.remove(name)
    }

    /**
     * Re-send `_regEvents` for every currently-active subscription.
     * Called after a reconnect so the Apple TV re-receives all subscriptions
     * (consumed by Task 18's reconnect supervisor).
     */
    suspend fun restore() {
        for (name in active.toList()) {
            ch.sendEvent("_interest", mapOf("_regEvents" to listOf(name)))
        }
    }
}
