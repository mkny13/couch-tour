// Artist display names for narrow columns.
// Source: etree.org BandAbbreviations wiki (the codes tapers already use in filenames),
// upper case for true acronyms (DMB, SCI, GD); lower case for word nicknames (mule, pgroove). Fall back to truncation when an artist isn't listed.
//
// Rule used in the designs:
//   1. Show the full name when it fits the column.
//   2. Otherwise show the etree code: acronyms upper case (WSP, TAB, GD, SCI, UM),
//      word-style nicknames lower case (mule, phil, ratdog, pgroove).
//   3. No code on file -> truncate with an ellipsis.

export const ARTIST_ABBREVIATIONS = {
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
  // not on the etree list, but in common use
  "Perpetual Groove": "pgroove",
  "Goose": "goose",
};

export function artistLabel(name, fits) {
  if (fits) return name;
  return ARTIST_ABBREVIATIONS[name] || name;
}
