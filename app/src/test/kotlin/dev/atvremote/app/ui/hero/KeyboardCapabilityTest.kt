// app/src/test/kotlin/dev/atvremote/app/ui/hero/KeyboardCapabilityTest.kt
package dev.atvremote.app.ui.hero

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyboardCapabilityTest {
    @Test fun falseWhenTextGetThrowsNotImplemented() = runTest {
        val available = keyboardAvailable { throw NotImplementedError("stub") }
        assertFalse(available)
    }
    @Test fun trueWhenTextGetSucceeds() = runTest {
        val available = keyboardAvailable { "" }
        assertTrue(available)
    }
    @Test fun falseOnAnyOtherError() = runTest {
        val available = keyboardAvailable { error("boom") }
        assertFalse(available)
    }
}
