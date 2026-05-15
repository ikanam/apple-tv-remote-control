package dev.atvremote.protocol.discovery

import dev.atvremote.protocol.AppleTvDevice
import dev.atvremote.protocol.DeviceDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

private const val SERVICE_TYPE = "_companion-link._tcp.local."

/**
 * jmDNS-based implementation of [DeviceDiscovery] for Apple TV Companion-protocol devices.
 *
 * Discovery is performed by listening for `_companion-link._tcp.local.` mDNS services.
 * The `rpfl` TXT record flag encodes pairing availability; `rpmd` carries the device model.
 *
 * **Task 17 flag:** Live mDNS multicast is NOT unit-tested (CI has no multicast loopback).
 * End-to-end validation of [devices] on a real Apple TV is deferred to Task 17 (CLI smoke test).
 *
 * The [toDevice] parsing function is fully covered by `DiscoveryParseTest`.
 */
class JmdnsDiscovery : DeviceDiscovery {

    /**
     * Returns a [Flow] that emits the current list of discovered Apple TV devices whenever
     * the set changes (device added, resolved, or removed).
     *
     * The flow uses [callbackFlow] with JmDNS running on [Dispatchers.IO] (blocking I/O).
     * The JmDNS instance is closed when the flow collector cancels (via [awaitClose]).
     */
    override fun devices(): Flow<List<AppleTvDevice>> = callbackFlow {
        val jmdns = JmDNS.create()
        val devices = mutableMapOf<String, AppleTvDevice>()

        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                // Request full resolution; result arrives in serviceResolved.
                jmdns.requestServiceInfo(event.type, event.name)
            }

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info
                val inet4 = info.inet4Addresses
                val host = if (inet4.isNotEmpty()) inet4[0].hostAddress ?: info.hostAddresses.firstOrNull() ?: return
                          else info.hostAddresses.firstOrNull() ?: return
                val txt = mapOf(
                    "rpmd" to (info.getPropertyString("rpmd") ?: ""),
                    "rpfl" to (info.getPropertyString("rpfl") ?: ""),
                )
                val device = toDevice(
                    name = event.name,
                    host = host,
                    port = info.port,
                    txt = txt,
                )
                devices[event.name] = device
                trySend(devices.values.toList())
            }

            override fun serviceRemoved(event: ServiceEvent) {
                devices.remove(event.name)
                trySend(devices.values.toList())
            }
        }

        jmdns.addServiceListener(SERVICE_TYPE, listener)

        awaitClose { jmdns.close() }
    }.flowOn(Dispatchers.IO)

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
         * @param txt   key/value map from the mDNS TXT record
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
