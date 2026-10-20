package dev.glasslauncher.system

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.home.SystemTextField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P230: Back with the keyboard up is Glass's, and it ends the editing of the field that has the keyboard. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ImeBackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun backIsGlassOnlyWhenAKeyboardIsUpOverGlassAndAFieldIsThere() {
        assertTrue("keyboard up, Glass in front, a field listening: Glass closes it", ImeBack.takesBack(imeShowing = true, glassInFront = true, fieldListening = true))
        assertFalse("no keyboard: Back is Back (it closes Search)", ImeBack.takesBack(imeShowing = false, glassInFront = true, fieldListening = true))
        assertFalse("a keyboard over another app is that app's", ImeBack.takesBack(imeShowing = true, glassInFront = false, fieldListening = true))
        assertFalse("no field to end: the key is not swallowed for nothing", ImeBack.takesBack(imeShowing = true, glassInFront = true, fieldListening = false))
    }

    @Test fun aTakenBackEndsTheEditingOfTheFocusedField() {
        var lost = 0
        val field = FocusRequester()
        compose.setContent {
            Column {
                SystemTextField("abc", {}, field, onFocusLost = { lost++ }, modifier = Modifier.semantics { testTag = "field" })
                Box(Modifier.semantics { testTag = "other" })
            }
        }
        assertFalse("no field has focus yet: nobody is listening", ImeBack.listening)
        compose.runOnIdle { field.requestFocus() }
        compose.waitForIdle()
        assertTrue("the focused field listens", ImeBack.listening)
        ImeBack.request()
        compose.waitForIdle()
        assertEquals("the field let go of focus once, as the keyboard's own Done would", 1, lost)
    }

    @Test fun backInTheKeyboardsPreImeStageEndsTheEditingWithoutTheService() {
        var lost = 0
        val field = FocusRequester()
        var view: android.view.View? = null
        compose.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            SystemTextField("abc", {}, field, onFocusLost = { lost++ }, modifier = Modifier.semantics { testTag = "field" })
        }
        compose.runOnIdle { field.requestFocus() }
        compose.waitForIdle()
        compose.runOnIdle {
            // The stage before the keyboard sees a key (View.dispatchKeyEventPreIme): the standard path, no accessibility service involved.
            view!!.dispatchKeyEventPreIme(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            view!!.dispatchKeyEventPreIme(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        }
        compose.waitForIdle()
        assertEquals("Back before the keyboard ended the editing", 1, lost)
    }
}
