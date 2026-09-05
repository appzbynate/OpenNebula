package org.opennebula.air.shell

import org.opennebula.air.browser.WebApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherPinsTest {
    @Test
    fun default_is_the_original_home_set() {
        assertEquals(12, LauncherPins.DEFAULT.size)
        assertEquals(ShellApps.SCREEN, LauncherPins.DEFAULT[0])
        assertEquals(ShellApps.EXTENDED, LauncherPins.DEFAULT[1])
        assertTrue(LauncherPins.DEFAULT.contains("web:parsec"))
        assertFalse(LauncherPins.DEFAULT.contains("web:netflix"))
    }

    @Test
    fun parse_uses_default_until_customized() {
        assertEquals(LauncherPins.DEFAULT, LauncherPins.parse(null, false))
        assertEquals(emptyList<String>(), LauncherPins.parse("", true))
        assertEquals(listOf("web:netflix", "web:gmail"), LauncherPins.parse("web:netflix,web:gmail", true))
    }

    @Test
    fun pin_and_unpin_keep_order() {
        val added = LauncherPins.apply(LauncherPins.DEFAULT, "web:netflix", true)
        assertTrue(added.contains("web:netflix"))
        val removed = LauncherPins.apply(added, "web:parsec", false)
        assertFalse(removed.contains("web:parsec"))
        assertTrue(removed.contains("web:youtube"))
    }

    @Test
    fun grid_order_is_shell_then_catalog() {
        val order = LauncherPins.gridOrder(listOf("web:netflix", ShellApps.SCREEN, "web:youtube"))
        assertEquals(listOf(ShellApps.SCREEN, "web:youtube", "web:netflix"), order)
        assertTrue(WebApps.find("netflix")!!.desktopChrome)
    }
}
