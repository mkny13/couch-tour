import Foundation

/// Artist abbreviations for narrow desktop columns and compact badges.
/// Source: etree.org BandAbbreviations wiki, upper case for true acronyms (DMB, SCI, GD),
/// lower case for word nicknames (mule, pgroove). Fall back to name when not listed.
public enum ArtistAbbreviations {
    public static let map: [String: String] = [
        "Allman Brothers Band": "ABB",
        "Bela Fleck & the Flecktones": "BFFT",
        "Ben Harper and the Innocent Criminals": "BHIC",
        "Blues Traveler": "BT",
        "Brothers Past": "BP",
        "Dark Star Orchestra": "DSO",
        "Dave Matthews Band": "DMB",
        "David Nelson Band": "DNB",
        "Deep Banana Blackout": "DBB",
        "Derek Trucks Band": "DTB",
        "Dirty Dozen Brass Band": "DDBB",
        "Disco Biscuits": "DB",
        "Donna the Buffalo": "DTB",
        "Drive-By Truckers": "DBT",
        "Ekoostik Hookah": "EH",
        "God Street Wine": "GSW",
        "Gov't Mule": "mule",
        "Grateful Dead": "GD",
        "GreyBoy AllStars": "GBA",
        "Jazz Mandolin Project": "JMP",
        "Jerry Garcia Band": "JGB",
        "Jerry Joseph & The Jackmormons": "JJJ",
        "Jimi Hendrix Experience": "JHE",
        "John Mayer Trio": "JM3",
        "Karl Denson's Tiny Universe": "KDTU",
        "Keller Williams": "KW",
        "Leftover Salmon": "LS",
        "Les Claypool's Fearless Flying Frog Brigade": "FFFB",
        "Little Feat": "LF",
        "Medeski Martin & Wood": "MMW",
        "Michael Franti & Spearhead": "MFS",
        "North Mississippi Allstars": "NMAS",
        "Pat Metheny Group": "PMG",
        "Pat McGee Band": "PMB",
        "Pearl Jam": "PJ",
        "Phil Lesh & Friends": "phil",
        "Phish": "PH",
        "Railroad Earth": "RRE",
        "Ratdog": "ratdog",
        "Robert Randolph & the Family Band": "RRFB",
        "Rusted Root": "RR",
        "Sound Tribe Sector 9": "STS9",
        "Sound Tribe Sector9": "STS9",
        "Steve Kimock Band": "SKB",
        "String Cheese Incident": "SCI",
        "Tea Leaf Green": "TLG",
        "The Big Wu": "wu",
        "The Dead": "dead",
        "The New Deal": "TND",
        "The Slip": "slip",
        "Trey Anastasio Band": "TAB",
        "Umphrey's McGee": "UM",
        "Widespread Panic": "WSP",
        "Yonder Mountain String Band": "YMSB",
        "Perpetual Groove": "pgroove",
        "Goose": "goose",
        "moe.": "moe.",
    ]

    /// Returns abbreviation if fits is false, full name otherwise.
    /// Preserves "moe." lowercase with period (uat-005).
    public static func label(for name: String, fits: Bool = true) -> String {
        if name.lowercased() == "moe." || name.lowercased() == "moe" {
            return "moe."
        }
        if fits { return name }
        return map[name] ?? name
    }
}
