package dev.glasslauncher.featured

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P116: a title with no logo art (TV-app rows such as Nuvio's) shows its name top left, not only the picture. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class ShelfTitleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aTitleWithoutALogoShowsItsName() {
        compose.setContent {
            ShelfTitle(item = FeaturedItem(id = "n1", title = "The Long Night", image = "https://example/bg.jpg", logo = null), expanded = { 0f })
        }
        compose.onNodeWithText("The Long Night").assertIsDisplayed()
    }
}
