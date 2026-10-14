package dev.mike.couchtour

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JamChartBadgeTest {
    @Test fun jamTagWithNotes_showsBadge() = assertTrue(jamChartBadgeVisible(true, "Top tier"))
    @Test fun jamTagWithNullNotes_hidesBadge() = assertFalse(jamChartBadgeVisible(true, null))
    @Test fun jamTagWithBlankNotes_hidesBadge() = assertFalse(jamChartBadgeVisible(true, "   "))
    @Test fun nonJamTrack_hidesBadge() = assertFalse(jamChartBadgeVisible(false, "notes"))
}
