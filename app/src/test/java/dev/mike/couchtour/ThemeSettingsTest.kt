package dev.mike.couchtour

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeSettingsTest {

    @Before
    fun setUp() {
        ThemeSettings.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `theme mode defaults to auto`() {
        ThemeSettings.setThemeMode(ThemeMode.AUTO)
        assertEquals(ThemeMode.AUTO, ThemeSettings.themeMode.value)
    }

    @Test
    fun `theme mode updates and persists across init`() {
        ThemeSettings.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, ThemeSettings.themeMode.value)

        // Re-init on same context restores value
        ThemeSettings.init(ApplicationProvider.getApplicationContext())
        assertEquals(ThemeMode.LIGHT, ThemeSettings.themeMode.value)

        ThemeSettings.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, ThemeSettings.themeMode.value)

        ThemeSettings.init(ApplicationProvider.getApplicationContext())
        assertEquals(ThemeMode.DARK, ThemeSettings.themeMode.value)

        // Reset to default
        ThemeSettings.setThemeMode(ThemeMode.AUTO)
        assertEquals(ThemeMode.AUTO, ThemeSettings.themeMode.value)
    }

    @Test
    fun `theme mode storage values mapping`() {
        assertEquals(ThemeMode.AUTO, ThemeMode.fromStorage("auto"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStorage("light"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStorage("dark"))
        assertEquals(ThemeMode.AUTO, ThemeMode.fromStorage("unknown"))
        assertEquals(ThemeMode.AUTO, ThemeMode.fromStorage(null))
    }
}
