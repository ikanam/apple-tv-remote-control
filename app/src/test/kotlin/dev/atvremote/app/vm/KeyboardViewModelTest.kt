package dev.atvremote.app.vm

import dev.atvremote.app.testutil.FakeSession
import dev.atvremote.protocol.KeyboardFocusState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class KeyboardViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun focusShowsKeyboardAndLoadsExistingText() = runTest {
        val s = FakeSession().apply { text = "hel" }
        val vm = KeyboardViewModel { s }
        s.focusFlow.value = KeyboardFocusState.Focused
        dispatcher.scheduler.advanceUntilIdle()
        val st = vm.state.first { it.visible }
        assertTrue(st.visible)
        assertEquals("hel", st.text)
    }

    @Test fun unfocusHidesKeyboard() = runTest {
        val s = FakeSession()
        val vm = KeyboardViewModel { s }
        s.focusFlow.value = KeyboardFocusState.Focused
        dispatcher.scheduler.advanceUntilIdle()
        s.focusFlow.value = KeyboardFocusState.Unfocused
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(!vm.state.first().visible)
    }

    @Test fun editsPushToSession() = runTest {
        val s = FakeSession()
        val vm = KeyboardViewModel { s }
        vm.setText("hello")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("hello", s.text)
        vm.append("!")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("hello!", s.text)
        vm.clear()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("", s.text)
    }
}
