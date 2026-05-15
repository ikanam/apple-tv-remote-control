package dev.atvremote.protocol.discovery

import dev.atvremote.protocol.AppleTvDevice
import dev.atvremote.protocol.DeviceDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

private const val SERVICE_TYPE = "_companion-link._tcp.local."

/**
 * Interface name prefixes that indicate virtual / VPN / non-LAN interfaces.
 * jmDNS bound to these never receives LAN mDNS multicast.
 */
private val EXCLUDED_NIC_PREFIXES = listOf(
    "utun", "llw", "awdl", "bridge", "ppp", "ipsec", "tap", "tun",
)

/**
 * Lightweight, pure-data model of a network interface candidate.
 * Using a data class avoids the need to mock [NetworkInterface]
 * (which has no public constructor) in unit tests.
 */
internal data class NicCandidate(
    val name: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val isVirtual: Boolean,
    val supportsMulticast: Boolean,
    val ipv4: List<InetAddress>,
)

/**
 * Selects a routable LAN IPv4 address to bind jmDNS to.
 *
 * Rules (applied in order):
 * 1. Interface must be `isUp && !isLoopback && supportsMulticast && !isVirtual`.
 * 2. Interface [NicCandidate.name] must NOT start with any of:
 *    `utun`, `llw`, `awdl`, `bridge`, `ppp`, `ipsec`, `tap`, `tun` (case-insensitive).
 * 3. From the remaining interfaces, take the first IPv4 address that is
 *    `!isLoopbackAddress && !isLinkLocalAddress` (i.e., a routable site-local / private address).
 *
 * Returns `null` when no qualifying address is found.
 */
internal fun selectBindAddress(cands: List<NicCandidate>): InetAddress? {
    for (cand in cands) {
        if (!cand.isUp) continue
        if (cand.isLoopback) continue
        if (!cand.supportsMulticast) continue
        if (cand.isVirtual) continue
        val nameLower = cand.name.lowercase()
        if (EXCLUDED_NIC_PREFIXES.any { nameLower.startsWith(it) }) continue
        val addr = cand.ipv4.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        if (addr != null) return addr
    }
    return null
}

/** Builds the list of [NicCandidate]s from the real [NetworkInterface] enumeration. */
private fun buildNicCandidates(): List<NicCandidate> {
    return runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { ni ->
            val ipv4 = ni.inetAddresses.toList().filter { it.address.size == 4 }
            NicCandidate(
                name = ni.name,
                isUp = runCatching { ni.isUp }.getOrDefault(false),
                isLoopback = runCatching { ni.isLoopback }.getOrDefault(false),
                isVirtual = runCatching { ni.isVirtual }.getOrDefault(false),
                supportsMulticast = runCatching { ni.supportsMulticast() }.getOrDefault(false),
                ipv4 = ipv4,
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * jmDNS-based implementation of [DeviceDiscovery] for Apple TV Companion-protocol devices.
 *
 * Discovery is performed by listening for `_companion-link._tcp.local.` mDNS services.
 * The `rpfl` TXT record flag encodes pairing availability; `rpmd` carries the device model.
 *
 * ## Interface selection
 *
 * On multi-homed hosts (e.g., macOS with VPN `utun` interfaces), jmDNS's no-arg
 * `create()` binds to whatever `InetAddress.getLocalHost()` returns — often a VPN
 * or link-local interface that never receives LAN mDNS multicast. This implementation
 * auto-selects a routable LAN IPv4 address via [selectBindAddress] so jmDNS binds to
 * the correct interface and receives Apple TV advertisements.
 *
 * ## Override mechanisms (highest to lowest precedence)
 *
 * 1. **Constructor param** `bindAddress` — pass an explicit [InetAddress] (useful in tests
 *    or when the auto-detection heuristic picks the wrong NIC).
 * 2. **Env var** `ATVREMOTE_MDNS_ADDR` — set to a dotted IPv4 string (e.g. `192.168.7.131`);
 *    takes effect without code changes.
 * 3. **Auto-detection** via [selectBindAddress] — scans live NICs and returns the first
 *    routable LAN IPv4, excluding VPN (`utun`), loopback, virtual, link-local, and other
 *    non-LAN interfaces.
 * 4. **Fallback** — if all of the above yield nothing, `JmDNS.create()` (no-arg) is used;
 *    this matches the previous behaviour and avoids regressions on hosts where the heuristic
 *    finds nothing useful.
 *
 * ## Real-device validation
 *
 * Validated against a real Apple TV 4K (`客厅`, `192.168.7.134:49153`, `rpMd=AppleTV14,1`,
 * `rpFl=0x36782`) on the `en1` LAN interface (`192.168.7.131`) on 2026-05-16. Discovery
 * completes within the default 10-second scan window.
 *
 * @param bindAddress Optional explicit bind address. `null` → auto-detect (see above).
 */
class JmdnsDiscovery(private val bindAddress: InetAddress? = null) : DeviceDiscovery {

    /**
     * Returns a [Flow] that emits the current list of discovered Apple TV devices whenever
     * the set changes (device added, resolved, or removed).
     *
     * The blocking [JmDNS.create] socket-bind / multicast-join is executed on [Dispatchers.IO]
     * via [withContext] inside the [callbackFlow] producer block. [JmDNS.close] is likewise
     * offloaded to [Dispatchers.IO] inside [awaitClose] so the cancellation path is non-blocking
     * on the caller's dispatcher. [ServiceListener] callbacks arrive on JmDNS's internal executor
     * threads; the device map is a [ConcurrentHashMap] so concurrent adds/removes are safe.
     *
     * Only services that resolve with at least one routable IPv4 address are emitted.
     * Services that resolve with only link-local or IPv6 addresses are silently skipped;
     * jmDNS will re-resolve them and invoke [ServiceListener.serviceResolved] again with
     * updated addresses (proven by Phase-1 probe on 2026-05-16).
     */
    override fun devices(): Flow<List<AppleTvDevice>> = callbackFlow {
        val jmdns = withContext(Dispatchers.IO) {
            // Determine bind address (env override → ctor param → auto-detect → fallback)
            val addr = resolveBindAddress()
            if (addr != null) JmDNS.create(addr) else JmDNS.create()
        }
        val devices = ConcurrentHashMap<String, AppleTvDevice>()

        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                // Request full resolution; result arrives in serviceResolved.
                jmdns.requestServiceInfo(event.type, event.name)
            }

            override fun serviceResolved(event: ServiceEvent) {
                runCatching {
                    val info = event.info

                    // Fix 3: require a routable IPv4 address; link-local/IPv6-only → skip.
                    // jmDNS re-resolves and calls serviceResolved again with IPv4 present.
                    val host = info.inet4Addresses
                        .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                        ?.hostAddress
                        ?: return

                    // Fix 2: build TXT map with lowercase keys (RFC 6763 §6.4 case-insensitive).
                    // The LOCKED toDevice() reads txt["rpmd"] / txt["rpfl"] (already lowercase).
                    val txt = info.propertyNames.toList()
                        .associate { it.lowercase() to (info.getPropertyString(it) ?: "") }

                    val device = toDevice(
                        name = event.name,
                        host = host,
                        port = info.port,
                        txt = txt,
                    )
                    devices[event.name] = device
                    // best-effort; on buffer-full the next event re-emits the full current list
                    trySend(devices.values.toList())
                }
            }

            override fun serviceRemoved(event: ServiceEvent) {
                devices.remove(event.name)
                // best-effort; on buffer-full the next event re-emits the full current list
                trySend(devices.values.toList())
            }
        }

        jmdns.addServiceListener(SERVICE_TYPE, listener)

        awaitClose {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) { jmdns.close() }
        }
    }

    /**
     * Resolves the bind address using the precedence chain:
     * env var `ATVREMOTE_MDNS_ADDR` → constructor [bindAddress] → auto-detect → null (fallback).
     */
    private fun resolveBindAddress(): InetAddress? {
        // 1. Environment variable override (highest precedence)
        val envAddr = System.getenv("ATVREMOTE_MDNS_ADDR")?.trim()
        if (!envAddr.isNullOrEmpty()) {
            return runCatching { InetAddress.getByName(envAddr) }.getOrNull()
        }
        // 2. Constructor parameter
        if (bindAddress != null) return bindAddress
        // 3. Auto-detect from live NICs
        return selectBindAddress(buildNicCandidates())
        // 4. If null: caller falls back to JmDNS.create() (no-arg)
    }

    companion object {
        /**
         * Converts raw mDNS TXT record entries into an [AppleTvDevice].
         *
         * Pairing availability is determined from the `rpfl` bitmask:
         * - bit 0x4000 set → device advertises pairing support
         * - bit 0x04  set → pairing currently disabled (e.g., requires Settings unlock)
         * Both conditions must hold: `pairable = (rpfl and 0x4000) != 0 && (rpfl and 0x04) == 0`.
         *
         * @param name  mDNS service name (used as part of the device ID)
         * @param host  resolved IPv4 address string
         * @param port  TCP port advertised in the SRV record
         * @param txt   key/value map from the mDNS TXT record (keys must be lowercase)
         */
        fun toDevice(name: String, host: String, port: Int, txt: Map<String, String>): AppleTvDevice {
            val rpfl = Integer.parseInt(txt["rpfl"]?.removePrefix("0x") ?: "0", 16)
            val pairable = (rpfl and 0x4000) != 0 && (rpfl and 0x04) == 0
            val model = txt["rpmd"]?.takeIf { it.isNotEmpty() }
            val id = "$name@$host:$port"
            return AppleTvDevice(id, name, host, port, model, pairable)
        }
    }
}
