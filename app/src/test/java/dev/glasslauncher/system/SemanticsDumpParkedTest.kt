package dev.glasslauncher.system

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P203's parked Control Center is composed but not shown: the device tests' dump must not list its controls as if they were on Home. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SemanticsDumpParkedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aParkedSubtreeIsLeftOutOfTheDumpAndTheRestStays() {
        compose.setContent {
            Box(Modifier.size(200.dp).semantics { testTag = "home" }) {
                Box(Modifier.size(50.dp).semantics { testTag = "status-pill" })
                Box(Modifier.size(100.dp).clearAndSetSemantics { testTag = SemanticsDump.PARKED }) {
                    Box(Modifier.size(40.dp).semantics { testTag = "weather" })
                }
            }
        }
        compose.waitForIdle()
        val root = compose.onRoot().fetchSemanticsNode().root as RootForTest
        val xml = SemanticsDump.xml(root)
        assertTrue("Home's own nodes are there", "resource-id=\"status-pill\"" in xml)
        assertFalse("the parked tree's nodes are not", "resource-id=\"weather\"" in xml)
    }
}
