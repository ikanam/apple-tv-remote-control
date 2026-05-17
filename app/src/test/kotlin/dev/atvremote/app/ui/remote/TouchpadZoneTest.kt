package dev.atvremote.app.ui.remote

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import dev.atvremote.app.swipe.SwipeTuning
import dev.atvremote.app.swipe.TouchEvent
import dev.atvremote.protocol.RemoteButton
import dev.atvremote.protocol.TouchPhase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TDD for the Touchpad discrete-tap zone hit-test. The pure [zoneFor] function
 * is the exact port of remote.jsx:15-34 (atan2 / `width*0.18` inner radius);
 * these cases pin every boundary the design defines so a later refactor of the
 * Compose gesture wiring cannot silently drift the mapping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TouchpadZoneTest {

    @get:Rule val rule = createComposeRule()

    private val w = 240f
    // remote.jsx:22 — innerR = width * 0.18 ⇒ 43.2 for a 240 box.
    private val innerR = w * 0.18f

    // --- center (Select) --------------------------------------------------

    @Test fun deadCenterIsSelect() {
        assertEquals(RemoteButton.Select, zoneFor(0f, 0f, w))
    }

    @Test fun justInsideInnerRadiusIsSelect() {
        // r slightly < innerR along +x.
        assertEquals(RemoteButton.Select, zoneFor(innerR - 0.5f, 0f, w))
    }

    @Test fun justOutsideInnerRadiusIsNotSelect() {
        // r slightly > innerR along +x ⇒ angle 0 ⇒ Right (remote.jsx:28).
        assertEquals(RemoteButton.Right, zoneFor(innerR + 0.5f, 0f, w))
    }

    @Test fun exactlyOnInnerRadiusIsDirectional() {
        // remote.jsx:25 uses strict `<`, so r == innerR falls through to angle.
        assertEquals(RemoteButton.Right, zoneFor(innerR, 0f, w))
    }

    // --- cardinal directions ---------------------------------------------
    // Compose/screen Y grows DOWNWARD, identical to the JSX clientY math, so
    // +y ⇒ Down, -y ⇒ Up (remote.jsx:29-31).

    @Test fun rightCardinal() {
        assertEquals(RemoteButton.Right, zoneFor(100f, 0f, w))
    }

    @Test fun downCardinal() {
        assertEquals(RemoteButton.Down, zoneFor(0f, 100f, w))
    }

    @Test fun upCardinal() {
        assertEquals(RemoteButton.Up, zoneFor(0f, -100f, w))
    }

    @Test fun leftCardinal() {
        assertEquals(RemoteButton.Left, zoneFor(-100f, 0f, w))
    }

    // --- angle boundaries (remote.jsx:28-31) ------------------------------
    // ang = atan2(y, x) deg. [-45,45)=Right, [45,135)=Down, [-135,-45)=Up,
    // else Left. Boundaries are inclusive-lower, exclusive-upper.

    @Test fun boundaryMinus45IsRight() {
        // ang == -45 (y = -x, x>0) ⇒ -45 is the inclusive lower edge of Right.
        assertEquals(RemoteButton.Right, zoneFor(100f, -100f, w))
    }

    @Test fun boundaryPlus45IsDown() {
        // ang == +45 (y = x, x>0) ⇒ +45 is the inclusive lower edge of Down.
        assertEquals(RemoteButton.Down, zoneFor(100f, 100f, w))
    }

    @Test fun boundaryPlus135IsLeft() {
        // ang == +135 (x<0, y>0) ⇒ not < 135 ⇒ falls to the `else` = Left.
        assertEquals(RemoteButton.Left, zoneFor(-100f, 100f, w))
    }

    @Test fun boundaryMinus135IsUp() {
        // ang == -135 (x<0, y<0) ⇒ inclusive lower edge of Up [-135,-45).
        assertEquals(RemoteButton.Up, zoneFor(-100f, -100f, w))
    }

    @Test fun justAboveMinus45IsRight() {
        // ang slightly > -45 ⇒ still Right.
        assertEquals(RemoteButton.Right, zoneFor(100f, -99f, w))
    }

    @Test fun justBelowMinus45IsUp() {
        // ang slightly < -45 ⇒ Up zone.
        assertEquals(RemoteButton.Up, zoneFor(100f, -101f, w))
    }

    @Test fun straightDownNegativeXStillDown() {
        // ang == 90 (pure +y) ⇒ Down regardless of tiny x.
        assertEquals(RemoteButton.Down, zoneFor(-1f, 100f, w))
    }

    // --- Compose harness: testTag + tap near top edge ---------------------

    @Test fun trackpadTagExistsAndTopTapFiresUp() {
        var fired: RemoteButton? = null
        rule.setContent {
            Touchpad(
                tuning = SwipeTuning.DEFAULT,
                onDirection = { fired = it },
                onTouchEvent = {},
            )
        }
        rule.onNodeWithTag("trackpad").assertIsDisplayed()
        // Tap near the top edge (well above center, inside the disk) ⇒ Up zone.
        rule.onNodeWithTag("trackpad").performTouchInput {
            click(Offset(width / 2f, height * 0.10f))
        }
        rule.waitForIdle()
        assertEquals(RemoteButton.Up, fired)
    }

    // --- Compose wiring: tap ≠ drag (no double-fire), C1/I2 path ----------
    // These exercise the real awaitPointerEventScope loop (latched pointer id,
    // rememberUpdatedState callbacks, uptimeMillis timestamps).

    @Test fun pureClickFiresExactlyOneDirectionAndZeroTouchEvents() {
        var fired: RemoteButton? = null
        var dirCount = 0
        val events = mutableListOf<TouchEvent>()
        rule.setContent {
            Touchpad(
                tuning = SwipeTuning.DEFAULT,
                onDirection = { fired = it; dirCount++ },
                onTouchEvent = { events += it },
            )
        }
        // A discrete click near the top edge ⇒ Up zone, no drag.
        rule.onNodeWithTag("trackpad").performTouchInput {
            click(Offset(width / 2f, height * 0.10f))
        }
        rule.waitForIdle()
        assertEquals(RemoteButton.Up, fired)
        assertEquals(1, dirCount, "a tap must fire exactly one onDirection")
        assertTrue(
            events.isEmpty(),
            "a tap must deliver ZERO onTouchEvent (no SwipeEngine leak): $events",
        )
    }

    @Test fun dragBeyondSlopFeedsSwipeEngineAndNeverFiresDirection() {
        var fired: RemoteButton? = null
        val events = mutableListOf<TouchEvent>()
        rule.setContent {
            Touchpad(
                tuning = SwipeTuning.DEFAULT,
                onDirection = { fired = it },
                onTouchEvent = { events += it },
            )
        }
        rule.onNodeWithTag("trackpad").performTouchInput {
            val slop = viewConfiguration.touchSlop
            down(center)
            // Move clearly beyond touch-slop in a few samples.
            moveBy(Offset(slop + 8f, 0f))
            moveBy(Offset(40f, 0f))
            moveBy(Offset(40f, 0f))
            up()
        }
        rule.waitForIdle()
        assertNull(fired, "a drag must NEVER fire a tap-zone onDirection")
        assertTrue(events.isNotEmpty(), "a drag must feed the SwipeEngine stream")
        assertEquals(
            1,
            events.filterIsInstance<TouchEvent.Move>()
                .count { it.phase == TouchPhase.Release },
            "a drag must be closed by exactly one terminal Move(Release): $events",
        )
    }

    @Test fun cancelledGestureEmitsTerminatingReleaseAndNoDirection() {
        var fired: RemoteButton? = null
        val events = mutableListOf<TouchEvent>()
        rule.setContent {
            Touchpad(
                tuning = SwipeTuning.DEFAULT,
                onDirection = { fired = it },
                onTouchEvent = { events += it },
            )
        }
        rule.onNodeWithTag("trackpad").performTouchInput {
            val slop = viewConfiguration.touchSlop
            down(center)
            moveBy(Offset(slop + 30f, 0f)) // classified as a drag
            moveBy(Offset(40f, 0f))
            cancel() // ancestor/gesture cancellation
        }
        rule.waitForIdle()
        assertNull(fired, "a cancelled gesture must not fire a direction")
        assertEquals(
            1,
            events.filterIsInstance<TouchEvent.Move>()
                .count { it.phase == TouchPhase.Release },
            "cancel must synthesize exactly one terminal Release (engine.onUp): $events",
        )
    }
}
