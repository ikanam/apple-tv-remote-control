package dev.atvremote.app.conn

import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MulticastLockHolderTest {

    private fun holder() =
        MulticastLockHolder(ApplicationProvider.getApplicationContext())

    @Test fun acquireHoldsLock() {
        val h = holder()
        assertFalse(h.isHeld(), "fresh holder must not be held")
        h.acquire()
        assertTrue(h.isHeld(), "after acquire() isHeld() must be true")
    }

    @Test fun releaseClearsLock() {
        val h = holder()
        h.acquire()
        assertTrue(h.isHeld(), "acquire() should hold the lock")
        h.release()
        assertFalse(h.isHeld(), "after release() isHeld() must be false")
    }

    @Test fun acquireReleaseIdempotent() {
        val h = holder()
        // double acquire → single release clears (not refcount-balanced)
        h.acquire()
        h.acquire()
        h.release()
        assertFalse(h.isHeld(), "single release after double acquire must clear held (setReferenceCounted=false)")
        // double release → no throw, stays false
        h.release()
        assertFalse(h.isHeld(), "double release must not throw and must stay false")
        // re-acquire → held again
        h.acquire()
        assertTrue(h.isHeld(), "re-acquire after full release must set held=true")
    }
}
