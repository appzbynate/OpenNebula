package org.opennebula.air.browser

data class WebAppItem(
    val id: String,
    val name: String,
    val url: String,
    val color: Int,
    val packages: List<String>,
    val desktopChrome: Boolean = true,
    val category: String = "Web",
) {
    val component: String get() = WebApps.component(id)
    val letter: String get() = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
}

object WebApps {
    const val PREFIX = "web:"
    const val STREAM = "Stream"
    const val PLAY = "Play"
    const val CHAT = "Chat"
    const val MUSIC = "Music"
    const val SOCIAL = "Social"
    const val GOOGLE = "Google"
    const val AI = "AI"
    const val WORK = "Work"
    const val NEWS = "News"

    val catalog: List<WebAppItem> = listOf(
        w("youtube", "YouTube", "https://www.youtube.com", 0xFFE11D48, STREAM,
            "com.google.android.youtube", "com.google.android.youtube.tv"),
        w("parsec", "Parsec", "https://web.parsec.app", 0xFF2563EB, PLAY,
            "tv.parsec.client", "tv.parsec.android"),
        w("twitch", "Twitch", "https://www.twitch.tv", 0xFF7C3AED, STREAM,
            "tv.twitch.android.app"),
        w("discord", "Discord", "https://discord.com/app", 0xFF5865F2, CHAT,
            "com.discord"),
        w("spotify", "Spotify", "https://open.spotify.com", 0xFF16A34A, MUSIC,
            "com.spotify.music"),
        w("reddit", "Reddit", "https://www.reddit.com", 0xFFEA580C, SOCIAL,
            "com.reddit.frontpage"),
        w("xbox", "Xbox Cloud Gaming", "https://www.xbox.com/play", 0xFF15803D, PLAY,
            "com.microsoft.xboxone.smartglass", "com.microsoft.xboxapp",
            "com.microsoft.xboxone.smartglass.beta"),
        w("chatgpt", "ChatGPT", "https://chatgpt.com", 0xFF0D9488, AI,
            "com.openai.chatgpt"),
        w("plex", "Plex", "https://app.plex.tv", 0xFFEAB308, STREAM,
            "com.plexapp.android"),
        w("google", "Google", "https://www.google.com", 0xFF2563EB, GOOGLE,
            "com.google.android.googlequicksearchbox", "com.android.chrome"),
        w("netflix", "Netflix", "https://www.netflix.com", 0xFFE50914, STREAM,
            "com.netflix.mediaclient"),
        w("primevideo", "Prime Video", "https://www.primevideo.com", 0xFF00A8E1, STREAM,
            "com.amazon.avod.thirdpartyclient"),
        w("disney", "Disney+", "https://www.disneyplus.com", 0xFF113CCF, STREAM,
            "com.disney.disneyplus"),
        w("max", "Max", "https://play.max.com", 0xFF002BE7, STREAM,
            "com.wbd.stream"),
        w("hulu", "Hulu", "https://www.hulu.com", 0xFF1CE783, STREAM,
            "com.hulu.plus"),
        w("crunchyroll", "Crunchyroll", "https://www.crunchyroll.com", 0xFFF47521, STREAM,
            "com.crunchyroll.crunchyroid"),
        w("kick", "Kick", "https://kick.com", 0xFF53FC18, STREAM,
            "com.kick.mobile"),
        w("geforcenow", "GeForce Now", "https://play.geforcenow.com", 0xFF76B900, PLAY,
            "com.nvidia.geforcenow"),
        w("steam", "Steam", "https://store.steampowered.com", 0xFF1B2838, PLAY,
            "com.valvesoftware.android.steam.community"),
        w("whatsapp", "WhatsApp", "https://web.whatsapp.com", 0xFF25D366, CHAT,
            "com.whatsapp"),
        w("telegram", "Telegram", "https://web.telegram.org", 0xFF2AABEE, CHAT,
            "org.telegram.messenger"),
        w("slack", "Slack", "https://app.slack.com", 0xFF4A154B, CHAT,
            "com.Slack"),
        w("messenger", "Messenger", "https://www.messenger.com", 0xFF006AFF, CHAT,
            "com.facebook.orca"),
        w("teams", "Teams", "https://teams.microsoft.com", 0xFF6264A7, CHAT,
            "com.microsoft.teams"),
        w("youtubemusic", "YouTube Music", "https://music.youtube.com", 0xFFFF0000, MUSIC,
            "com.google.android.apps.youtube.music"),
        w("applemusic", "Apple Music", "https://music.apple.com", 0xFFFA2D48, MUSIC,
            "com.apple.android.music"),
        w("soundcloud", "SoundCloud", "https://soundcloud.com", 0xFFFF5500, MUSIC,
            "com.soundcloud.android"),
        w("x", "X", "https://x.com", 0xFF0F1419, SOCIAL,
            "com.twitter.android"),
        w("instagram", "Instagram", "https://www.instagram.com", 0xFFE1306C, SOCIAL,
            "com.instagram.android"),
        w("facebook", "Facebook", "https://www.facebook.com", 0xFF1877F2, SOCIAL,
            "com.facebook.katana"),
        w("tiktok", "TikTok", "https://www.tiktok.com", 0xFF010101, SOCIAL,
            "com.zhiliaoapp.musically"),
        w("linkedin", "LinkedIn", "https://www.linkedin.com", 0xFF0A66C2, SOCIAL,
            "com.linkedin.android"),
        w("threads", "Threads", "https://www.threads.com", 0xFF000000, SOCIAL,
            "com.instagram.barcelona"),
        w("gmail", "Gmail", "https://mail.google.com", 0xFFEA4335, GOOGLE,
            "com.google.android.gm"),
        w("maps", "Maps", "https://www.google.com/maps", 0xFF34A853, GOOGLE,
            "com.google.android.apps.maps"),
        w("photos", "Photos", "https://photos.google.com", 0xFFFBBC04, GOOGLE,
            "com.google.android.apps.photos"),
        w("drive", "Drive", "https://drive.google.com", 0xFF1A73E8, GOOGLE,
            "com.google.android.apps.docs"),
        w("docs", "Docs", "https://docs.google.com", 0xFF4285F4, GOOGLE,
            "com.google.android.apps.docs.editors.docs"),
        w("meet", "Meet", "https://meet.google.com", 0xFF00897B, GOOGLE,
            "com.google.android.apps.tachyon"),
        w("calendar", "Calendar", "https://calendar.google.com", 0xFF1A73E8, GOOGLE,
            "com.google.android.calendar"),
        w("translate", "Translate", "https://translate.google.com", 0xFF4285F4, GOOGLE,
            "com.google.android.apps.translate"),
        w("gemini", "Gemini", "https://gemini.google.com", 0xFF8E83EF, AI,
            "com.google.android.apps.bard"),
        w("claude", "Claude", "https://claude.ai", 0xFFD97757, AI,
            "com.anthropic.claude"),
        w("perplexity", "Perplexity", "https://www.perplexity.ai", 0xFF20808D, AI,
            "ai.perplexity.app.android"),
        w("copilot", "Copilot", "https://copilot.microsoft.com", 0xFF0078D4, AI,
            "com.microsoft.copilot"),
        w("grok", "Grok", "https://grok.com", 0xFF1A1A1A, AI),
        w("notion", "Notion", "https://www.notion.so", 0xFF111111, WORK,
            "notion.id"),
        w("github", "GitHub", "https://github.com", 0xFF24292F, WORK,
            "com.github.android"),
        w("figma", "Figma", "https://www.figma.com", 0xFFA259FF, WORK,
            "com.figma.mirror"),
        w("photopea", "Photopea", "https://www.photopea.com", 0xFF18A497, WORK),
        w("wikipedia", "Wikipedia", "https://www.wikipedia.org", 0xFFEAECF0, NEWS,
            "org.wikipedia"),
        w("stackoverflow", "Stack Overflow", "https://stackoverflow.com", 0xFFF48024, WORK),
        w("hackernews", "Hacker News", "https://news.ycombinator.com", 0xFFFF6600, NEWS),
        w("bbc", "BBC", "https://www.bbc.com", 0xFFBB1919, NEWS),
        w("nytimes", "NYTimes", "https://www.nytimes.com", 0xFF121212, NEWS),
        w("nebula", "Nebula", "https://nebula.tv", 0xFF5B4BFF, STREAM),
        w("imdb", "IMDb", "https://www.imdb.com", 0xFFF5C518, NEWS),
        w("letterboxd", "Letterboxd", "https://letterboxd.com", 0xFFFF8000, NEWS),
        w("duckduckgo", "DuckDuckGo", "https://duckduckgo.com", 0xFFDE5833, WORK),
    )

    fun component(id: String): String = "$PREFIX$id"

    fun isComponent(component: String): Boolean = component.trim().startsWith(PREFIX)

    fun find(component: String): WebAppItem? {
        val t = component.trim()
        return catalog.firstOrNull { it.component == t || it.id == t.removePrefix(PREFIX) }
    }

    fun findByUrl(url: String): WebAppItem? {
        val t = url.trim()
        if (t.isBlank()) return null
        return catalog.firstOrNull { t.startsWith(it.url) }
    }

    fun wantsDesktopChrome(url: String): Boolean {
        val t = url.trim().lowercase()
        return t.startsWith("http://") || t.startsWith("https://") || t.contains('.')
    }

    fun categories(): List<String> = catalog.map { it.category }.distinct()

    private fun w(
        id: String,
        name: String,
        url: String,
        color: Long,
        category: String,
        vararg packages: String,
    ): WebAppItem = WebAppItem(
        id = id,
        name = name,
        url = url,
        color = color.toInt(),
        packages = packages.toList(),
        desktopChrome = true,
        category = category,
    )
}
