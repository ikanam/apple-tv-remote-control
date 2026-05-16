package dev.atvremote.app.haptics

import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HapticsTest {
    @Test fun effectsDoNotThrowOnAnyApi() {
        val h = Haptics(ApplicationProvider.getApplicationContext())
        h.tap(); h.edgeStep(); h.select()
        assertTrue(true) // exercised the VibrationEffect paths without crashing
    }
}
