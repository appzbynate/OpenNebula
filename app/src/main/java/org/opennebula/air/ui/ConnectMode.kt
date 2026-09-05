package org.opennebula.air.ui

import org.opennebula.air.browser.WebApps
import org.opennebula.air.shell.ShellApps
import org.opennebula.air.usb.GlassesConnectionState

enum class ConnectChoice {
    MIRROR,
    EXTEND,
}

data class ExtendApp(
    val component: String,
    val name: String,
)

/**
 * The phone-OS dialog when a display plugs in: Mirror the phone, or Extend
 * an app and use the phone as a trackpad. OpenNebula offers the same split.
 */
object ConnectMode {
    const val TITLE = "Glasses connected"
    const val MIRROR_TITLE = "Mirror"
    const val MIRROR_BODY = "Your phone on the glasses. Use the phone as usual."
    const val EXTEND_TITLE = "Extend"
    const val EXTEND_BODY = "An app in space. Phone becomes the trackpad."
    const val APPS_LABEL = "Or open an app"
    const val HOME_NAME = "Home"
    const val MAX_SHORTCUTS = 8

    fun enteredStreaming(
        previous: GlassesConnectionState,
        next: GlassesConnectionState,
    ): Boolean {
        return next is GlassesConnectionState.Streaming &&
            previous !is GlassesConnectionState.Streaming
    }

    fun extendShortcuts(pinned: List<String>): List<ExtendApp> {
        val apps = ArrayList<ExtendApp>(MAX_SHORTCUTS)
        apps += ExtendApp(ShellApps.EXTENDED, HOME_NAME)
        for (id in pinned) {
            if (apps.size >= MAX_SHORTCUTS) break
            if (ShellApps.isScreen(id) || ShellApps.isExtended(id)) continue
            val web = WebApps.find(id) ?: continue
            apps += ExtendApp(web.component, web.name)
        }
        return apps
    }
}
