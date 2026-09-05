package org.opennebula.air.shell

import android.content.Context
import org.opennebula.air.browser.WebApps

/**
 * Which launcher tiles sit on the glasses home grid. The phone Apps menu
 * pin/unpin list is the source of truth; the catalog keeps every web app.
 */
object LauncherPins {
    val DEFAULT: List<String> = listOf(
        ShellApps.SCREEN,
        ShellApps.EXTENDED,
        WebApps.component("youtube"),
        WebApps.component("parsec"),
        WebApps.component("twitch"),
        WebApps.component("discord"),
        WebApps.component("spotify"),
        WebApps.component("reddit"),
        WebApps.component("xbox"),
        WebApps.component("chatgpt"),
        WebApps.component("plex"),
        WebApps.component("google"),
    )

    fun parse(raw: String?, customized: Boolean): List<String> {
        if (!customized) return DEFAULT
        if (raw.isNullOrBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        for (part in raw.split(',')) {
            val id = part.trim()
            if (id.isNotEmpty()) seen += id
        }
        return seen.toList()
    }

    fun encode(ids: List<String>): String = ids.joinToString(",")

    fun apply(ids: List<String>, component: String, pinned: Boolean): List<String> {
        val id = component.trim()
        if (id.isEmpty()) return ids
        val next = LinkedHashSet(ids)
        if (pinned) next += id else next.remove(id)
        return next.toList()
    }

    fun gridOrder(pinned: Collection<String>): List<String> {
        val pin = pinned.toSet()
        val shell = ShellApps.catalog.map { it.component }.filter { it in pin }
        val web = WebApps.catalog.map { it.component }.filter { it in pin }
        return shell + web
    }

    fun known(component: String): Boolean {
        val t = component.trim()
        return ShellApps.isComponent(t) || WebApps.isComponent(t)
    }
}

class LauncherPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun pinned(): List<String> = LauncherPins.parse(prefs.getString(PINS, null), prefs.getBoolean(SET, false))

    fun setPinned(component: String, pinned: Boolean): List<String> {
        val next = LauncherPins.apply(pinned(), component, pinned)
        prefs.edit()
            .putBoolean(SET, true)
            .putString(PINS, LauncherPins.encode(next))
            .apply()
        return next
    }

    companion object {
        private const val NAME = "opennebula_launcher"
        private const val PINS = "pins"
        private const val SET = "pins_set"
    }
}
