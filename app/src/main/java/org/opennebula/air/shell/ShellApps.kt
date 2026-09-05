package org.opennebula.air.shell

import org.opennebula.air.browser.WebApps
import org.opennebula.air.mirror.MirrorPipeline

/**
 * Built-in launcher tiles that are not web apps. Screen opens the phone-mirror
 * window. Extended Display is the spatial desktop (this compositor).
 */
object ShellApps {
    const val PREFIX = "shell:"
    const val SCREEN = "shell:screen"
    const val EXTENDED = "shell:extended"
    const val STEAMVR = "shell:steamvr"
    const val DESKTOP = "shell:desktop"

    data class Item(
        val id: String,
        val name: String,
    ) {
        val component: String get() = "$PREFIX$id"
    }

    val catalog: List<Item> = listOf(
        Item("screen", "Screen"),
        Item("extended", "Extended Display"),
        Item("steamvr", "SteamVR"),
        Item("desktop", "Remote Desktop"),
    )

    fun isComponent(component: String): Boolean = component.trim().startsWith(PREFIX)

    fun isScreen(component: String): Boolean {
        val t = component.trim()
        return t == SCREEN || t == MirrorPipeline.ID || t == "screen"
    }

    fun isExtended(component: String): Boolean {
        val t = component.trim()
        return t == EXTENDED || t == "extended"
    }

    fun isSteamVr(component: String): Boolean {
        val t = component.trim()
        return t == STEAMVR || t == MirrorPipeline.STEAMVR_ID || t == "steamvr"
    }

    fun isDesktop(component: String): Boolean {
        val t = component.trim()
        return t == DESKTOP || t == MirrorPipeline.DESKTOP_ID || t == "desktop"
    }

    fun launcherIds(): List<String> {
        return catalog.map { it.component } + WebApps.catalog.map { it.component }
    }
}
