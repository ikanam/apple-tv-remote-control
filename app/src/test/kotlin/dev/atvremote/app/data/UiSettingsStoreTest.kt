package dev.atvremote.app.data

import androidx.test.core.app.ApplicationProvider
import dev.atvremote.app.swipe.SwipeTuning
import dev.atvremote.app.ui.remote.RemoteLayoutStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UiSettingsStoreTest {
    private fun store() =
        UiSettingsStore(ApplicationProvider.getApplicationContext())

    @BeforeTest fun clearStore() = runTest {
        store().clear()
    }

    @Test fun layoutStyleDefaultsToPhysical() = runTest {
        assertEquals(RemoteLayoutStyle.Physical, store().layoutStyle.first())
    }

    @Test fun layoutStylePersistsAcrossStoreInstances() = runTest {
        store().saveLayoutStyle(RemoteLayoutStyle.Iphone)

        assertEquals(RemoteLayoutStyle.Iphone, store().layoutStyle.first())
    }

    @Test fun dragStepFractionPersistsAcrossStoreInstances() = runTest {
        store().saveDragStepFraction(0.24f)

        assertEquals(0.24f, store().dragStepFraction.first())
    }

    @Test fun dragStepFractionDefaultsToCurrentTuningDefault() = runTest {
        assertEquals(SwipeTuning.DEFAULT.dragStepFraction, store().dragStepFraction.first())
    }
}
