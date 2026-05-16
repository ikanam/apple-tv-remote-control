package dev.atvremote.protocol.session

import dev.atvremote.protocol.InstalledApp
import dev.atvremote.protocol.connection.CommandChannel

/**
 * Apps over Companion (pyatv `pyatv/protocols/companion/api.py`).
 *
 * list   = command `FetchLaunchableApplicationsEvent`, content {}
 *          response `_c` = { bundleId: name } map
 *          (pyatv `api.py app_list` L274-276, `companion/__init__.py` L168-175)
 *
 * launch = command `_launchApp`, content `{"_bundleID": v}` or `{"_urlS": v}`
 *          when v is a URL/scheme (has a scheme component per urllib.parse).
 *          (pyatv `api.py launch_app` L262-272, `pyatv/support/url.py is_url_or_scheme`)
 *
 * Both are request/response commands (`_send_command` → `exchange`),
 * NOT fire-and-forget events.
 */
internal class AppsController(private val ch: CommandChannel) {
    /**
     * Matches pyatv `support/url.py is_url_or_scheme`:
     * `bool(urlparse(url).scheme)` — true when there is any scheme prefix
     * (i.e. the string starts with `<alpha><alnum|+|.|->*:`).
     */
    private val urlLike = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    suspend fun listApps(): List<InstalledApp> {
        val resp = ch.exchange("FetchLaunchableApplicationsEvent", emptyMap())
        val c = resp["_c"] as? Map<*, *> ?: return emptyList()
        return c.entries.map { (k, v) -> InstalledApp(k.toString(), v.toString()) }
    }

    suspend fun launch(value: String) {
        val key = if (urlLike.containsMatchIn(value)) "_urlS" else "_bundleID"
        ch.exchange("_launchApp", mapOf(key to value))
    }
}
