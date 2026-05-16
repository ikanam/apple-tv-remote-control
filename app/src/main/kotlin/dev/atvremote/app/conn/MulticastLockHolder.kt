package dev.atvremote.app.conn

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Holds a [WifiManager.MulticastLock] while the Devices screen is active.
 *
 * jmDNS (used by `:protocol` for mDNS/Bonjour Apple TV discovery) is a
 * pure-JVM library — it cannot acquire Android Wi-Fi resources. Without this
 * lock the OS silently drops incoming multicast packets on most Wi-Fi adapters,
 * making discovery fail. `:protocol` cannot take the lock (no Android deps);
 * `:app` must hold it on behalf of discovery.
 *
 * S5 wraps `acquire()`/`release()` in a `DisposableEffect` tied to the Devices
 * screen lifetime so the lock is held only while needed (battery).
 *
 * Requires `android.permission.CHANGE_WIFI_MULTICAST_STATE` (declared in the
 * manifest as part of the connectedDevice FGS-type fix).
 *
 * Idempotency: `setReferenceCounted(false)` makes the WifiManager call
 * idempotent at the platform level; the `@Volatile held` guard additionally
 * keeps [isHeld] accurate and prevents unnecessary platform calls on
 * double-acquire.
 *
 * Null/error safety: a null WifiManager (lock is null → `lock?.acquire()` returns
 * null without throwing) or a thrown `SecurityException` both degrade to a no-op
 * — only an exception leaves `held=false`; a null lock is treated as success.
 * Discovery still runs; it just gets whatever the OS delivers without the lock.
 *
 * Thread confinement: intended for single-thread use — S5 drives acquire/release
 * from a Compose `DisposableEffect` on the main thread. `@Volatile` guarantees
 * visibility but acquire/release are not atomic; that is acceptable under this
 * confinement, and `setReferenceCounted(false)` makes a raced double-acquire a
 * safe platform no-op anyway.
 */
class MulticastLockHolder(context: Context) {

    private val wifi =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val lock: WifiManager.MulticastLock? =
        wifi?.createMulticastLock("atvremote-mdns")?.also { it.setReferenceCounted(false) }

    @Volatile private var held = false

    /** Acquires the multicast lock if not already held. Idempotent and null-safe. */
    fun acquire() {
        if (held) return
        runCatching { lock?.acquire() }.onSuccess { held = true }
    }

    /** Releases the multicast lock if currently held. Idempotent and null-safe. */
    fun release() {
        if (!held) return
        runCatching { lock?.release() }
        held = false
    }

    /**
     * Returns true if the lock was acquired (and not yet released) by this holder.
     * Reflects the holder's own state tracking via [held].
     */
    fun isHeld(): Boolean = held
}
