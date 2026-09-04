package dev.mike.couchtour

/**
 * Artist display names for narrow columns (e.g. Home date-first rows, Library rows).
 * Sourced from etree.org BandAbbreviations wiki (the codes tapers already use in filenames).
 * Acronyms are uppercase (GD, DMB, SCI, UM); nicknames are lowercase (mule, pgroove, goose).
 */
object ArtistAbbreviations {

    val ABBREVIATIONS: Map<String, String> = mapOf(
        "Allman Brothers Band" to "ABB",
        "Bela Fleck & the Flecktones" to "BFFT",
        "Ben Harper and the Innocent Criminals" to "BHIC",
        "Blues Traveler" to "BT",
        "Brothers Past" to "BP",
        "Dark Star Orchestra" to "DSO",
        "Dave Matthews Band" to "DMB",
        "David Nelson Band" to "DNB",
        "Deep Banana Blackout" to "DBB",
        "Derek Trucks Band" to "DTB",
        "Dirty Dozen Brass Band" to "DDBB",
        "Disco Biscuits" to "DB",
        "Donna the Buffalo" to "DTB",
        "Drive-By Truckers" to "DBT",
        "Ekoostik Hookah" to "EH",
        "God Street Wine" to "GSW",
        "Gov't Mule" to "mule",
        "Grateful Dead" to "GD",
        "GreyBoy AllStars" to "GBA",
        "Jazz Mandolin Project" to "JMP",
        "Jerry Garcia Band" to "JGB",
        "Jerry Joseph & The Jackmormons" to "JJJ",
        "Jimi Hendrix Experience" to "JHE",
        "John Mayer Trio" to "JM3",
        "Karl Denson's Tiny Universe" to "KDTU",
        "Keller Williams" to "KW",
        "Leftover Salmon" to "LS",
        "Les Claypool's Fearless Flying Frog Brigade" to "FFFB",
        "Little Feat" to "LF",
        "Medeski Martin & Wood" to "MMW",
        "Michael Franti & Spearhead" to "MFS",
        "North Mississippi Allstars" to "NMAS",
        "Pat Metheny Group" to "PMG",
        "Pat McGee Band" to "PMB",
        "Pearl Jam" to "PJ",
        "Phil Lesh & Friends" to "phil",
        "Phish" to "PH",
        "Railroad Earth" to "RRE",
        "Ratdog" to "ratdog",
        "Robert Randolph & the Family Band" to "RRFB",
        "Rusted Root" to "RR",
        "Sound Tribe Sector 9" to "STS9",
        "Steve Kimock Band" to "SKB",
        "String Cheese Incident" to "SCI",
        "Tea Leaf Green" to "TLG",
        "The Big Wu" to "wu",
        "The Dead" to "dead",
        "The New Deal" to "TND",
        "The Slip" to "slip",
        "Trey Anastasio Band" to "TAB",
        "Umphrey's McGee" to "UM",
        "Widespread Panic" to "WSP",
        "Yonder Mountain String Band" to "YMSB",
        "Perpetual Groove" to "pgroove",
        "Goose" to "goose",
    )

    /**
     * Formats the artist name, substituting known abbreviations when it does not fit
     * or when abbreviations are preferred for compact column layouts.
     * Also respects uat-005: 'moe.' is always rendered in lowercase with a trailing period.
     */
    fun artistLabel(name: String, fits: Boolean = true): String {
        if (name.equals("moe.", ignoreCase = true) || name.equals("moe", ignoreCase = true)) {
            return "moe."
        }
        if (fits) return name
        return ABBREVIATIONS[name] ?: name
    }
}
