package org.opennebula.air.ui

import org.opennebula.air.shell.ShellApps
import org.opennebula.air.usb.GlassesConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectModeTest {
    @Test
    fun prompts_when_usb_starts_streaming() {
        assertTrue(
            ConnectMode.enteredStreaming(
                GlassesConnectionState.Connecting,
                GlassesConnectionState.Streaming(60),
            ),
        )
        assertTrue(
            ConnectMode.enteredStreaming(
                GlassesConnectionState.AwaitingPermission,
                GlassesConnectionState.Streaming(60),
            ),
        )
        assertFalse(
            ConnectMode.enteredStreaming(
                GlassesConnectionState.Streaming(40),
                GlassesConnectionState.Streaming(60),
            ),
        )
        assertFalse(
            ConnectMode.enteredStreaming(
                GlassesConnectionState.Disconnected,
                GlassesConnectionState.Connecting,
            ),
        )
    }

    @Test
    fun extend_shortcuts_are_home_then_pinned_web_apps() {
        val apps = ConnectMode.extendShortcuts(
            listOf(ShellApps.SCREEN, ShellApps.EXTENDED, "web:youtube", "web:parsec"),
        )
        assertEquals(ConnectMode.HOME_NAME, apps.first().name)
        assertEquals(ShellApps.EXTENDED, apps.first().component)
        assertEquals(listOf("YouTube", "Parsec"), apps.drop(1).map { it.name })
        assertTrue(apps.none { it.component == ShellApps.SCREEN })
    }
}
