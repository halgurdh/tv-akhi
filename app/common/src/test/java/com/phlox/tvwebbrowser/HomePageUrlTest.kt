package com.phlox.tvwebbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePageUrlTest {
    private val home = Config.HOME_PAGE_URL
    private val legacy = Config.LEGACY_HOME_PAGE_URL

    @Test
    fun recognisesCurrentAndLegacyAddresses() {
        assertTrue(Config.isHomePageUrl(home))
        assertTrue(Config.isHomePageUrl(legacy + "style.css"))
        assertFalse(Config.isHomePageUrl("https://example.com/appcontent/home/"))
        assertFalse(Config.isHomePageUrl(null))
        assertEquals(legacy, Config.homePagePrefixOf(legacy + "img/logo.png"))
        assertNull(Config.homePagePrefixOf("https://example.com/"))
    }

    @Test
    fun rootMatchesOnlyTheHomePageItself() {
        assertTrue(Config.isHomePageRoot(home))
        assertTrue(Config.isHomePageRoot(legacy))
        assertFalse(Config.isHomePageRoot(home + "style.css"))
        assertFalse(Config.isHomePageRoot(null))
    }

    @Test
    fun migratesLegacyAddressAndKeepsOthers() {
        assertEquals(home, Config.migrateHomePageUrl(legacy))
        assertEquals(home + "index.html?x=1", Config.migrateHomePageUrl(legacy + "index.html?x=1"))
        assertEquals("https://example.com/", Config.migrateHomePageUrl("https://example.com/"))
    }
}
