package org.opennebula.air.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandIconsTest {
    @Test
    fun youtube_maps_to_official_android_package() {
        val yt = WebApps.find("youtube")!!
        assertTrue(BrandIcons.knownPackage(yt, "com.google.android.youtube"))
        assertFalse(BrandIcons.knownPackage(yt, "com.android.chrome"))
    }

    @Test
    fun google_may_use_search_or_chrome_icon() {
        val google = WebApps.find("google")!!
        assertTrue(BrandIcons.knownPackage(google, "com.google.android.googlequicksearchbox"))
        assertTrue(BrandIcons.knownPackage(google, "com.android.chrome"))
    }
}
