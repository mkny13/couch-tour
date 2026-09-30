package dev.mike.couchtour

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier as JavaModifier

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class A11yTagsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `all string constants are non-blank and have no surrounding whitespace`() {
        val constants = A11yTags::class.java.declaredFields
            .filter { JavaModifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.isAccessible = true; it.get(null) as String }
        
        assertTrue("Expected some constants", constants.isNotEmpty())
        for (tag in constants) {
            assertTrue("Tag '$tag' is blank", tag.isNotBlank())
            assertEquals("Tag '$tag' has whitespace", tag.trim(), tag)
        }
    }

    @Test
    fun `home section tags resolve in a Compose tree`() {
        compose.setContent {
            Column(Modifier.testTag(A11yTags.HOME_SECTION_IN_PROGRESS)) {
                Text("In Progress")
            }
            Column(Modifier.testTag(A11yTags.HOME_SECTION_NEXT_TOUR_STOPS)) {
                Text("Next Tour Stops")
            }
            Column(Modifier.testTag(A11yTags.HOME_SECTION_ON_THIS_DATE)) {
                Text("On This Date")
            }
        }

        compose.onNodeWithTag(A11yTags.HOME_SECTION_IN_PROGRESS, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(A11yTags.HOME_SECTION_NEXT_TOUR_STOPS, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(A11yTags.HOME_SECTION_ON_THIS_DATE, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `parameterized helpers produce the expected prefixes and values`() {
        assertEquals("home.section.in-progress.row.test1", A11yTags.homeInProgressRow("test1"))
        assertEquals("home.section.next-tour-stops.row.test2", A11yTags.homeNextTourStopRow("test2"))
        assertEquals("home.section.on-this-date.row.test3", A11yTags.homeOnThisDateRow("test3"))
        assertEquals("favorites.row.test4", A11yTags.favoritesRow("test4"))
    }
}
