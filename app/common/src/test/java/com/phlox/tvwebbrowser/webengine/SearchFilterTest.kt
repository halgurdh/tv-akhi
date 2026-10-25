package com.phlox.tvwebbrowser.webengine

import com.phlox.tvwebbrowser.webengine.SearchFilter.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchFilterTest {
    private val all = Kind.entries.toSet()

    private fun kind(query: String) = SearchFilter.kindOf(query, all)

    @Test
    fun blocksGettingIt() {
        for (q in listOf("buy alcohol", "beer", "cheap Vodka near me", "why buy alcohol", "online casino bonus",
                "Bière pas cher", "bira fiyatı", "شراء الخمور", "الكحول", "خرید آبجو", "كازينو", "kaufen Wein",
                "comprar cerveza", "beli alkohol", "how to play poker")) {
            assertEquals(q, Kind.HARAM, kind(q))
        }
        for (q in listOf("porn", "free porn videos", "nude pics", "سكس", "افلام اباحية", "sexo", "çıplak")) {
            assertEquals(q, Kind.ADULT, kind(q))
        }
    }

    @Test
    fun allowsLearningAboutIt() {
        for (q in listOf("why is alcohol haram", "Is gambling haram?", "dangers of alcohol", "how to quit drinking beer",
                "porn addiction help", "لماذا الخمر حرام", "حكم القمار", "چرا شراب حرام است", "alkol neden haram",
                "pourquoi l'alcool est interdit", "warum ist Alkohol haram", "hukum judi", "baby sex")) {
            assertEquals(q, null, kind(q))
        }
    }

    @Test
    fun allowsEverydaySearches() {
        for (q in listOf("weather tomorrow", "bir film izle", "time slots", "Ford Escort", "cocktail dress",
                "pizza margherita", "نیک", "weed killer", "football results", "")) {
            assertEquals(q, null, kind(q))
        }
    }

    @Test
    fun onlyEnabledKinds() {
        assertEquals(null, SearchFilter.kindOf("buy beer", setOf(Kind.ADULT)))
        assertEquals(null, SearchFilter.kindOf("porn", setOf(Kind.HARAM)))
        assertEquals(Kind.HARAM, SearchFilter.kindOf("buy beer", setOf(Kind.HARAM)))
    }
}
