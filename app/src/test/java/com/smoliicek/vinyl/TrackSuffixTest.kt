package com.smoliicek.vinyl

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackSuffixTest {

    private fun assertSplit(input: String, main: String, suffix: String?) =
        assertEquals(SplitTitle(main, suffix), splitTitle(input))

    @Test
    fun dashRemasterVariants() {
        assertSplit("Wonderwall - Remastered", "Wonderwall", "- Remastered")
        assertSplit("Here Comes The Sun - Remastered 2009", "Here Comes The Sun", "- Remastered 2009")
        assertSplit("Bohemian Rhapsody - 2011 Remaster", "Bohemian Rhapsody", "- 2011 Remaster")
    }

    @Test
    fun dashEditsAndLive() {
        assertSplit("Purple Rain - Single Version", "Purple Rain", "- Single Version")
        assertSplit("Mr. Brightside - Radio Edit", "Mr. Brightside", "- Radio Edit")
        assertSplit("Hotel California - Live", "Hotel California", "- Live")
        assertSplit("Let It Go - From \"Frozen\"/Soundtrack Version", "Let It Go", "- From \"Frozen\"/Soundtrack Version")
    }

    @Test
    fun featuringInBrackets() {
        assertSplit("Stay (with Justin Bieber)", "Stay", "(with Justin Bieber)")
        assertSplit("Empire State of Mind [feat. Alicia Keys]", "Empire State of Mind", "[feat. Alicia Keys]")
        assertSplit("Get Lucky (ft. Pharrell Williams)", "Get Lucky", "(ft. Pharrell Williams)")
    }

    @Test
    fun stackedSuffixes() {
        assertSplit(
            "Old Town Road (feat. Billy Ray Cyrus) - Remix",
            "Old Town Road",
            "(feat. Billy Ray Cyrus) - Remix",
        )
        assertSplit(
            "Under Pressure (feat. David Bowie) - Remastered 2011",
            "Under Pressure",
            "(feat. David Bowie) - Remastered 2011",
        )
    }

    @Test
    fun albumNames() {
        assertSplit("Rumours (Super Deluxe)", "Rumours", "(Super Deluxe)")
        assertSplit("Abbey Road (Remastered)", "Abbey Road", "(Remastered)")
        assertSplit("Nevermind (Deluxe Edition)", "Nevermind", "(Deluxe Edition)")
        assertSplit("Upside Down: The Story Of Creation OST", "Upside Down: The Story Of Creation OST", null)
    }

    @Test
    fun caseInsensitive() {
        assertSplit("SONG 2 - REMASTERED", "SONG 2", "- REMASTERED")
        assertSplit("Clocks (FEAT. Nobody)", "Clocks", "(FEAT. Nobody)")
    }

    @Test
    fun keepsTitlesThatAreNotSuffixes() {
        assertSplit("Smells Like Teen Spirit", "Smells Like Teen Spirit", null)
        assertSplit("Live Forever", "Live Forever", null)
        assertSplit("(I Can't Get No) Satisfaction", "(I Can't Get No) Satisfaction", null)
        assertSplit("Love Theme (Part II)", "Love Theme (Part II)", null)
        assertSplit("Track 1 - Intro", "Track 1 - Intro", null)
    }

    @Test
    fun onlySplitsTheRecognisedTail() {
        assertSplit("(I Can't Get No) Satisfaction - Mono Version", "(I Can't Get No) Satisfaction", "- Mono Version")
        assertSplit("Live Forever - Remastered", "Live Forever", "- Remastered")
        assertSplit("Theme (Part II) - Live", "Theme (Part II)", "- Live")
    }

    @Test
    fun neverLeavesAnEmptyMain() {
        assertSplit("(Remastered)", "(Remastered)", null)
        assertSplit("", "", null)
    }
}
