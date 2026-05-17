package dev.atvremote.app.conn

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Read-only, best-effort Wi-Fi info for the ConnectScreen status pill (spec
 * Screen 2 + reconciliation §1). Mirrors [MulticastLockHolder]'s tiny,
 * defensive holder style: a null/unavailable [WifiManager] or any thrown
 * platform exception degrades to `null` (never crashes), and ConnectScreen
 * gracefully degrades — title falls back to `已连接`, the IP subline is hidden.
 *
 * No new dangerous permission is requested. On modern Android, reading the SSID
 * without the location permission yields `<unknown ssid>` / null — that is the
 * spec's *accepted* graceful degradation, not a bug. The local IPv4 (from
 * `WifiManager.connectionInfo.ipAddress`) needs no extra permission and is the
 * only field usually available.
 *
 * Both calls are wrapped in `runCatching`; this class is read-only and holds no
 * mutable state, so it is safe to call from any thread / on every recomposition.
 */
class WifiStatus(context: Context) {

    private val wifi =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    /**
     * The connected network SSID, or `null` when unobtainable. Returns `null`
     * for the platform's unknown/placeholder values (`<unknown ssid>`, `0x`,
     * blank, surrounding quotes only) so ConnectScreen degrades to `已连接`.
     */
    fun ssid(): String? = runCatching {
        @Suppress("DEPRECATION")
        val raw = wifi?.connectionInfo?.ssid ?: return@runCatching null
        // WifiInfo.getSSID() wraps a UTF-8 SSID in double quotes; strip them.
        val s = raw.trim().removeSurrounding("\"")
        if (s.isBlank() ||
            s.equals(WifiManager.UNKNOWN_SSID, ignoreCase = true) ||
            s == "<unknown ssid>" ||
            s == "0x"
        ) {
            null
        } else {
            s
        }
    }.getOrNull()

    /**
     * The device's local IPv4 address as a dotted quad (e.g. `192.168.1.7`), or
     * `null` when unobtainable / unset (address `0`).
     */
    fun localIpv4(): String? = runCatching {
        @Suppress("DEPRECATION")
        val ip = wifi?.connectionInfo?.ipAddress ?: return@runCatching null
        if (ip == 0) return@runCatching null
        // WifiInfo.ipAddress is little-endian per the platform contract.
        "%d.%d.%d.%d".format(
            ip and 0xff,
            ip shr 8 and 0xff,
            ip shr 16 and 0xff,
            ip shr 24 and 0xff,
        )
    }.getOrNull()
}
