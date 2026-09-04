package dev.mike.couchtour

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistAbbreviationsTest {

    @Test
    fun returnsFullNameWhenFitting() {
        assertEquals("Grateful Dead", ArtistAbbreviations.artistLabel("Grateful Dead", fits = true))
        assertEquals("Phish", ArtistAbbreviations.artistLabel("Phish", fits = true))
        assertEquals("Perpetual Groove", ArtistAbbreviations.artistLabel("Perpetual Groove", fits = true))
    }

    @Test
    fun returnsEtreeAbbreviationsWhenNotFitting() {
        assertEquals("GD", ArtistAbbreviations.artistLabel("Grateful Dead", fits = false))
        assertEquals("DMB", ArtistAbbreviations.artistLabel("Dave Matthews Band", fits = false))
        assertEquals("SCI", ArtistAbbreviations.artistLabel("String Cheese Incident", fits = false))
        assertEquals("mule", ArtistAbbreviations.artistLabel("Gov't Mule", fits = false))
        assertEquals("pgroove", ArtistAbbreviations.artistLabel("Perpetual Groove", fits = false))
        assertEquals("goose", ArtistAbbreviations.artistLabel("Goose", fits = false))
    }

    @Test
    fun preservesMoeConventionAlways() {
        // uat-005: "moe." must always be displayed in lowercase with a trailing period
        assertEquals("moe.", ArtistAbbreviations.artistLabel("moe.", fits = true))
        assertEquals("moe.", ArtistAbbreviations.artistLabel("moe.", fits = false))
        assertEquals("moe.", ArtistAbbreviations.artistLabel("Moe", fits = true))
        assertEquals("moe.", ArtistAbbreviations.artistLabel("MOE.", fits = false))
    }

    @Test
    fun fallsBackToOriginalNameWhenUnlisted() {
        assertEquals("Unknown Jam Band", ArtistAbbreviations.artistLabel("Unknown Jam Band", fits = false))
    }
}
