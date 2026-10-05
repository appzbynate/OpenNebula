package org.opennebula.air.shell

import org.opennebula.air.browser.WebApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellAppsTest {
    @Test
    fun launcher_starts_with_shell_then_web_catalog() {
        val ids = ShellApps.launcherIds()
        assertEquals(ShellApps.SCREEN, ids[0])
        assertEquals(ShellApps.EXTENDED, ids[1])
        assertEquals(ShellApps.STEAMVR, ids[2])
        assertEquals(ShellApps.DESKTOP, ids[3])
        assertTrue(ids.size > 20)
        assertTrue(ids.contains("web:netflix"))
        assertEquals(WebApps.catalog.map { it.component }, ids.drop(4))
    }

    @Test
    fun screen_and_extended_are_shell_not_web() {
        assertTrue(ShellApps.isComponent(ShellApps.SCREEN))
        assertTrue(ShellApps.isComponent(ShellApps.EXTENDED))
        assertTrue(ShellApps.isScreen("shell:screen"))
        assertTrue(ShellApps.isScreen("screen"))
        assertTrue(ShellApps.isExtended(ShellApps.EXTENDED))
        assertFalse(ShellApps.isScreen("web:youtube"))
        assertFalse(WebApps.isComponent(ShellApps.SCREEN))
    }
}
