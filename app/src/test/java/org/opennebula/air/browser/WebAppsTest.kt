package org.opennebula.air.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAppsTest {
    @Test
    fun catalog_has_the_curated_web_apps() {
        val names = WebApps.catalog.map { it.name }
        assertTrue(names.size >= 40)
        assertTrue(
            names.containsAll(
                listOf(
                    "YouTube", "Parsec", "Twitch", "Discord", "Spotify",
                    "Reddit", "Xbox Cloud Gaming", "ChatGPT", "Plex", "Google",
                    "Netflix", "WhatsApp", "Gmail", "Claude", "GitHub",
                ),
            ),
        )
        assertEquals(names.size, names.toSet().size)
        assertEquals(WebApps.catalog.size, WebApps.catalog.map { it.id }.toSet().size)
    }

    @Test
    fun urls_match_the_requested_sites() {
        fun url(id: String) = WebApps.find(id)!!.url
        assertEquals("https://www.youtube.com", url("youtube"))
        assertEquals("https://web.parsec.app", url("parsec"))
        assertEquals("https://www.twitch.tv", url("twitch"))
        assertEquals("https://discord.com/app", url("discord"))
        assertEquals("https://open.spotify.com", url("spotify"))
        assertEquals("https://www.reddit.com", url("reddit"))
        assertEquals("https://www.xbox.com/play", url("xbox"))
        assertEquals("https://chatgpt.com", url("chatgpt"))
        assertEquals("https://app.plex.tv", url("plex"))
        assertEquals("https://www.google.com", url("google"))
        assertEquals("https://www.netflix.com", url("netflix"))
        assertEquals("https://web.whatsapp.com", url("whatsapp"))
        assertEquals("https://claude.ai", url("claude"))
    }

    @Test
    fun parsec_asks_for_desktop_chrome() {
        assertTrue(WebApps.find("parsec")!!.desktopChrome)
        assertTrue(WebApps.wantsDesktopChrome("https://web.parsec.app/login"))
        assertTrue(WebApps.find("youtube")!!.desktopChrome)
        assertTrue(WebApps.wantsDesktopChrome("https://www.youtube.com/watch"))
    }

    @Test
    fun web_components_do_not_need_shizuku() {
        assertTrue(WebApps.isComponent("web:youtube"))
        assertTrue(WebApps.isComponent(WebApps.component("parsec")))
        assertFalse(WebApps.isComponent("com.android.settings/.Settings"))
        assertEquals("https://www.youtube.com", WebApps.find("web:youtube")?.url)
    }
}
