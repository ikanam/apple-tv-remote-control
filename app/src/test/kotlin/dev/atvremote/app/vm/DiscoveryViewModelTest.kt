package dev.atvremote.app.vm

import dev.atvremote.protocol.AppleTvDevice
import dev.atvremote.protocol.DeviceDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val d1 = AppleTvDevice("dev-A", "Living Room", "10.0.0.5", 49152, "AppleTV14,1", true)
    private val d2 = AppleTvDevice("dev-B", "Bedroom", "10.0.0.6", 49152, "AppleTV11,1", true)

    private fun discovery(list: List<AppleTvDevice>) = object : DeviceDiscovery {
        override fun devices(): Flow<List<AppleTvDevice>> = flowOf(list)
    }

    @Test fun emitsDiscoveredDevicesAndFlagsPaired() = runTest {
        val vm = DiscoveryViewModel(
            discovery = discovery(listOf(d1, d2)),
            pairedDeviceIds = { setOf("dev-B") },
        )
        dispatcher.scheduler.advanceUntilIdle()
        val state = vm.state.first { it.devices.isNotEmpty() }
        assertEquals(2, state.devices.size)
        assertTrue(state.devices.first { it.device.id == "dev-B" }.paired)
        assertTrue(!state.devices.first { it.device.id == "dev-A" }.paired)
    }
}
