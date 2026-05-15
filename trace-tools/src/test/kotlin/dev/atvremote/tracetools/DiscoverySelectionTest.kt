package dev.atvremote.tracetools

import dev.atvremote.protocol.AppleTvDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Device-free unit tests for [awaitDevice] — the discovery-selection logic used
 * by `pair` / `menu` to locate a *specific* device by id.
 *
 * Regression for the real-device Bug A (2026-05-16): on a multi-homed host the
 * local Mac's own `_companion-link` advert resolves before the remote Apple TV,
 * so [dev.atvremote.protocol.DeviceDiscovery.devices] emits a cumulative
 * snapshot of `[localMac]` *first*. The old `first { it.isNotEmpty() }` logic
 * returned that snapshot and then failed `find { it.id == target }` →
 * "device not found", even though the Apple TV resolves a moment later.
 */
class DiscoverySelectionTest {

    private val localMac = AppleTvDevice(
        id = "Shinya的Mac mini@192.168.7.131:49248",
        name = "Shinya的Mac mini", host = "192.168.7.131", port = 49248,
        model = null, pairable = false,
    )
    private val appleTv = AppleTvDevice(
        id = "客厅@192.168.7.134:49153",
        name = "客厅", host = "192.168.7.134", port = 49153,
        model = "AppleTV14,1", pairable = true,
    )

    @Test
    fun `awaitDevice keeps collecting past the first non-empty snapshot until the requested device resolves`() =
        runBlocking {
            // Exact real-device emission ordering: local Mac resolves first,
            // Apple TV appears only in a later cumulative snapshot.
            val flow: Flow<List<AppleTvDevice>> = flowOf(
                listOf(localMac),
                listOf(localMac, appleTv),
            )

            val found = awaitDevice(flow, appleTv.id, timeoutMs = 1_000)

            assertEquals(appleTv, found, "must return the late-resolving Apple TV, not stop at [localMac]")
        }

    @Test
    fun `awaitDevice returns null when the requested device never resolves`() =
        runBlocking {
            val flow: Flow<List<AppleTvDevice>> = flowOf(listOf(localMac))

            val found = awaitDevice(flow, appleTv.id, timeoutMs = 1_000)

            assertNull(found, "device that never appears must yield null (drives the 'not found' error)")
        }

    @Test
    fun `awaitDevice returns null when no matching emission arrives before the timeout`() =
        runBlocking {
            // Mirrors the production callbackFlow: stays open, never completes.
            val neverResolves: Flow<List<AppleTvDevice>> = flow {
                emit(listOf(localMac))
                kotlinx.coroutines.delay(Long.MAX_VALUE)
            }

            val found = awaitDevice(neverResolves, appleTv.id, timeoutMs = 150)

            assertNull(found, "timeout must yield null rather than hang on a never-completing flow")
        }
}
