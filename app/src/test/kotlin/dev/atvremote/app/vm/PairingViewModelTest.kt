package dev.atvremote.app.vm

import dev.atvremote.protocol.HapCredentials
import dev.atvremote.protocol.PairingHandle
import dev.atvremote.protocol.PairingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeHandle : PairingHandle {
        val flow = MutableStateFlow<PairingState>(PairingState.AwaitingPin)
        var submitted: String? = null
        var canceled = false
        override val state: StateFlow<PairingState> = flow
        override suspend fun submitPin(pin: String) { submitted = pin }
        override fun cancel() { canceled = true }
    }

    private fun creds() = HapCredentials(
        ByteArray(1), ByteArray(1), ByteArray(1), ByteArray(1), ByteArray(1)
    )

    @Test fun awaitingPinThenCompletedPersistsCredentials() = runTest {
        val handle = FakeHandle()
        var savedId: String? = null
        var savedBlob: String? = null
        val vm = PairingViewModel(
            deviceId = "dev-A",
            handle = handle,
            persist = { id, blob -> savedId = id; savedBlob = blob },
        )
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.first() is PairingUiState.AwaitingPin)

        vm.submitPin("1234")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("1234", handle.submitted)

        handle.flow.value = PairingState.Completed(creds())
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.first() is PairingUiState.Completed)
        assertEquals("dev-A", savedId)
        assertEquals(creds().serialize(), savedBlob)
    }

    @Test fun failedSurfacesReason() = runTest {
        val handle = FakeHandle()
        val vm = PairingViewModel("dev-A", handle, persist = { _, _ -> })
        handle.flow.value = PairingState.Failed("wrong pin")
        dispatcher.scheduler.advanceUntilIdle()
        val s = vm.state.first { it is PairingUiState.Failed } as PairingUiState.Failed
        assertEquals("wrong pin", s.reason)
    }
}
