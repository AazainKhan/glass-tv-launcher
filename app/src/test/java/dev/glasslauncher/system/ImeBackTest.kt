package dev.glasslauncher.system

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.home.SystemTextField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P230: Back with the keyboard up is taken before the keyboard sees it (Android's pre-IME stage, no accessibility service), ends the
 * editing through onBack, and is not taken when the field has no focus (so a later Back is the screen's own).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ImeBackTest {
    @get:Rule val compose = createComposeRule()

    private var view: android.view.View? = null
    private fun back(action: Int) = view!!.dispatchKeyEventPreIme(android.view.KeyEvent(action, android.view.KeyEvent.KEYCODE_BACK))

    private fun show(onBack: () -> Unit, onFocusLost: () -> Unit = {}, field: FocusRequester) {
        compose.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            Column {
                SystemTextField("abc", {}, field, onFocusLost = onFocusLost, onBack = onBack, modifier = Modifier.semantics { testTag = "field" })
                Box(Modifier.semantics { testTag = "other" })
            }
        }
    }

    @Test fun backBeforeTheKeyboardEndsTheEditingOnceThroughOnBack() {
        var backs = 0
        val field = FocusRequester()
        show(onBack = { backs++ }, field = field)
        compose.runOnIdle { field.requestFocus() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("taken (down)", back(android.view.KeyEvent.ACTION_DOWN))
            assertTrue("taken (up)", back(android.view.KeyEvent.ACTION_UP))
        }
        compose.waitForIdle()
        assertEquals("the caller ended the editing once", 1, backs)
    }

    @Test fun aFieldWithoutFocusLeavesBackAlone() {
        var backs = 0
        show(onBack = { backs++ }, field = FocusRequester())
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse("not taken: no keyboard of this field is up", back(android.view.KeyEvent.ACTION_DOWN))
            assertFalse(back(android.view.KeyEvent.ACTION_UP))
        }
        assertEquals(0, backs)
    }

    @Test fun onBackDefaultsToTheFocusLostCallback() {
        var lost = 0
        val field = FocusRequester()
        compose.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            SystemTextField("abc", {}, field, onFocusLost = { lost++ }, modifier = Modifier.semantics { testTag = "field" })
        }
        compose.runOnIdle { field.requestFocus() }
        compose.waitForIdle()
        compose.runOnIdle { back(android.view.KeyEvent.ACTION_DOWN); back(android.view.KeyEvent.ACTION_UP) }
        compose.waitForIdle()
        assertEquals(1, lost)
    }
}
