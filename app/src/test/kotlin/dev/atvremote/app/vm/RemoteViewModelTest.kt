package dev.atvremote.app.vm

import dev.atvremote.app.swipe.TouchEvent
import dev.atvremote.app.testutil.FakeSession
import dev.atvremote.protocol.InputAction
import dev.atvremote.protocol.MediaCommand
import dev.atvremote.protocol.RemoteButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Plan-test reconciliation (reported): base T10's verbatim test does NOT set
// Dispatchers.Main, but RemoteViewModel.viewModelScope defaults to
// Dispatchers.Main.immediate, which is uninitialized under plain runTest
// ("Module with the Main dispatcher had failed to initialize"). Minimal,
// test-side ONLY fix — same StandardTestDispatcher + setMain/resetMain +
// advanceUntilIdle pattern as the existing Pairing/Discovery vm tests.
// No assertion or production code changed; verbatim test bodies' assertions
// are byte-unchanged (only an advanceUntilIdle() drains the launched work).
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun vm(session: FakeSession): Pair<RemoteViewModel, MutableList<String>> {
        val haptics = mutableListOf<String>()
        val vm = RemoteViewModel(
            sessionProvider = { session },
            onTap = { haptics += "tap" },
            onEdge = { haptics += "edge" },
            onSelect = { haptics += "select" },
        )
        return vm to haptics
    }

    @Test fun tapEmitsSingleTapClickAndHaptic() = runTest {
        val s = FakeSession()
        val (vm, h) = vm(s)
        vm.onTouchEvent(TouchEvent.Tap)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(InputAction.SingleTap), s.clicks)
        assertTrue(h.contains("tap"))
    }

    @Test fun longPressEmitsHoldClickAndSelectHaptic() = runTest {
        val s = FakeSession()
        val (vm, h) = vm(s)
        vm.onTouchEvent(TouchEvent.LongPress)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(InputAction.Hold), s.clicks)
        assertTrue(h.contains("select"))
    }

    @Test fun directionalStepPressesAndReleasesButtonWithEdgeHaptic() = runTest {
        val s = FakeSession()
        val (vm, h) = vm(s)
        vm.onTouchEvent(TouchEvent.DirectionalStep(RemoteButton.Right))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf(RemoteButton.Right to true, RemoteButton.Right to false),
            s.buttons,
        )
        assertTrue(h.contains("edge"))
    }

    @Test fun buttonAndVolumeAndMediaMapToProtocol() = runTest {
        val s = FakeSession()
        val (vm, _) = vm(s)
        vm.pressButton(RemoteButton.Menu)
        vm.volumeUp()
        vm.volumeDown()
        vm.media(MediaCommand.Play)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf(RemoteButton.Menu to true, RemoteButton.Menu to false,
                    RemoteButton.VolumeUp to true, RemoteButton.VolumeUp to false,
                    RemoteButton.VolumeDown to true, RemoteButton.VolumeDown to false),
            s.buttons,
        )
        assertEquals(listOf(MediaCommand.Play), s.medias)
    }

    @Test fun wakeCallsPowerTrue_sleepCallsPowerFalse() = runTest {
        val s = FakeSession()
        val (vm, _) = vm(s)
        vm.wake()
        vm.sleep()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(true, false), s.powerCalls)
    }

    @Test fun dpadButtonsMapToProtocol() = runTest {
        val s = FakeSession()
        val (vm, _) = vm(s)
        vm.pressButton(RemoteButton.Up)
        vm.pressButton(RemoteButton.Down)
        vm.pressButton(RemoteButton.Left)
        vm.pressButton(RemoteButton.Right)
        vm.pressButton(RemoteButton.Select)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf(
                RemoteButton.Up to true, RemoteButton.Up to false,
                RemoteButton.Down to true, RemoteButton.Down to false,
                RemoteButton.Left to true, RemoteButton.Left to false,
                RemoteButton.Right to true, RemoteButton.Right to false,
                RemoteButton.Select to true, RemoteButton.Select to false,
            ),
            s.buttons,
        )
    }

}
