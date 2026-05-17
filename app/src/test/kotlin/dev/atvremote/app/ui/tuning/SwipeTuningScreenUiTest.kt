package dev.atvremote.app.ui.tuning

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.atvremote.app.ui.remote.RemoteLayoutStyle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SwipeTuningScreenUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun settingsOnlyExposeLayoutStyleAndStepThreshold() {
        var selected = RemoteLayoutStyle.Physical
        rule.setContent {
            SwipeTuningScreen(
                layoutStyle = selected,
                onLayoutStyleChange = { selected = it },
                dragStepFraction = 0.18f,
                onDragStepFractionChange = {},
            )
        }

        rule.onNodeWithText("实体遥控器风格").performClick()
        rule.onNodeWithText("iPhone 风格").performClick()
        rule.waitForIdle()
        assertEquals(RemoteLayoutStyle.Iphone, selected)

        rule.onNodeWithText("步进阈值", substring = true).assertExists()
        rule.onAllNodesWithText("增益 · gain").assertCountEquals(0)
        rule.onAllNodesWithText("速度指数 · velocityExponent").assertCountEquals(0)
        rule.onAllNodesWithText("惯性衰减 · inertiaDecay").assertCountEquals(0)
        rule.onAllNodesWithTag("trackpad").assertCountEquals(0)
    }
}
