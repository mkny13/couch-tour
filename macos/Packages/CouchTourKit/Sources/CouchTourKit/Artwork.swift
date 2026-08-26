import Foundation

/// RGBA color representation independent of platform UI frameworks.
public struct ArtworkRGB: Codable, Hashable, Sendable {
    public let red: Double
    public let green: Double
    public let blue: Double
    public let opacity: Double

    public init(red: Double, green: Double, blue: Double, opacity: Double = 1.0) {
        self.red = max(0.0, min(1.0, red))
        self.green = max(0.0, min(1.0, green))
        self.blue = max(0.0, min(1.0, blue))
        self.opacity = max(0.0, min(1.0, opacity))
    }
}

/// Multi-color palette for procedural show artwork rendering.
public struct ArtworkPalette: Codable, Hashable, Sendable {
    public let primaryColor: ArtworkRGB
    public let secondaryColor: ArtworkRGB
    public let accentColor: ArtworkRGB
    public let backgroundColor: ArtworkRGB

    public init(
        primaryColor: ArtworkRGB,
        secondaryColor: ArtworkRGB,
        accentColor: ArtworkRGB,
        backgroundColor: ArtworkRGB
    ) {
        self.primaryColor = primaryColor
        self.secondaryColor = secondaryColor
        self.accentColor = accentColor
        self.backgroundColor = backgroundColor
    }
}

/// Pure deterministic generator for show artwork palettes, typography, and procedural aesthetics.
public enum ShowArtworkGenerator {

    /// Deterministic 64-bit FNV-1a hash of input string.
    public static func deterministicHash(_ string: String) -> UInt64 {
        var hash: UInt64 = 0xcbf29ce484222325
        for byte in string.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x100000001b3
        }
        return hash
    }

    /// Derives an `ArtworkPalette` deterministically from artist name and show date.
    public static func palette(forArtist artist: String?, date: String?) -> ArtworkPalette {
        let normalizedArtist = (artist ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let normalizedDate = (date ?? "").trimmingCharacters(in: .whitespacesAndNewlines)

        // Check curated artist themes first
        if let curated = curatedPalette(for: normalizedArtist, seedString: normalizedDate) {
            return curated
        }

        // Procedural generation from deterministic seed
        let seedKey = "\(normalizedArtist):\(normalizedDate)"
        let seed = deterministicHash(seedKey)
        return proceduralPalette(fromSeed: seed)
    }

    /// Curated artist color palettes for major jam & archival bands.
    private static func curatedPalette(for artist: String, seedString: String) -> ArtworkPalette? {
        let subSeed = deterministicHash(seedString)
        let variant = Int(subSeed % 3)

        if artist.contains("grateful dead") || artist == "gd" {
            switch variant {
            case 0:
                // Psychedelic Amber & Indigo
                return ArtworkPalette(
                    primaryColor: ArtworkRGB(red: 0.88, green: 0.45, blue: 0.12),
                    secondaryColor: ArtworkRGB(red: 0.22, green: 0.15, blue: 0.45),
                    accentColor: ArtworkRGB(red: 0.96, green: 0.78, blue: 0.24),
                    backgroundColor: ArtworkRGB(red: 0.11, green: 0.07, blue: 0.22)
                )
            case 1:
                // Forest Green & Crimson Rose
                return ArtworkPalette(
                    primaryColor: ArtworkRGB(red: 0.16, green: 0.44, blue: 0.30),
                    secondaryColor: ArtworkRGB(red: 0.68, green: 0.18, blue: 0.24),
                    accentColor: ArtworkRGB(red: 0.94, green: 0.84, blue: 0.52),
                    backgroundColor: ArtworkRGB(red: 0.08, green: 0.18, blue: 0.12)
                )
            default:
                // Tape Blue & Rust Gold
                return ArtworkPalette(
                    primaryColor: ArtworkRGB(red: 0.15, green: 0.38, blue: 0.68),
                    secondaryColor: ArtworkRGB(red: 0.76, green: 0.32, blue: 0.14),
                    accentColor: ArtworkRGB(red: 0.98, green: 0.86, blue: 0.32),
                    backgroundColor: ArtworkRGB(red: 0.07, green: 0.14, blue: 0.26)
                )
            }
        } else if artist.contains("jerry garcia") || artist == "jgb" {
            // Goldenrod & Deep Burgundy
            return ArtworkPalette(
                primaryColor: ArtworkRGB(red: 0.86, green: 0.54, blue: 0.14),
                secondaryColor: ArtworkRGB(red: 0.48, green: 0.12, blue: 0.18),
                accentColor: ArtworkRGB(red: 0.96, green: 0.82, blue: 0.38),
                backgroundColor: ArtworkRGB(red: 0.20, green: 0.06, blue: 0.08)
            )
        } else if artist.contains("phish") {
            switch variant {
            case 0:
                // Ultramarine Blue & Magenta
                return ArtworkPalette(
                    primaryColor: ArtworkRGB(red: 0.10, green: 0.42, blue: 0.88),
                    secondaryColor: ArtworkRGB(red: 0.86, green: 0.16, blue: 0.54),
                    accentColor: ArtworkRGB(red: 0.15, green: 0.86, blue: 0.80),
                    backgroundColor: ArtworkRGB(red: 0.06, green: 0.14, blue: 0.34)
                )
            case 1:
                // Aquamarine & Donut Red
                return ArtworkPalette(
                    primaryColor: ArtworkRGB(red: 0.08, green: 0.62, blue: 0.68),
                    secondaryColor: ArtworkRGB(red: 0.84, green: 0.20, blue: 0.28),
                    accentColor: ArtworkRGB(red: 0.98, green: 0.88, blue: 0.30),
                    backgroundColor: ArtworkRGB(red: 0.05, green: 0.22, blue: 0.26)
                )
            default:
                // Deep Purple & Neon Cyan
                return ArtworkPalette(
                    primaryColor: ArtworkRGB(red: 0.42, green: 0.18, blue: 0.68),
                    secondaryColor: ArtworkRGB(red: 0.10, green: 0.72, blue: 0.82),
                    accentColor: ArtworkRGB(red: 0.98, green: 0.45, blue: 0.65),
                    backgroundColor: ArtworkRGB(red: 0.15, green: 0.06, blue: 0.25)
                )
            }
        } else if artist.contains("goose") {
            // Goose Teal & Solar Orange
            return ArtworkPalette(
                primaryColor: ArtworkRGB(red: 0.10, green: 0.60, blue: 0.66),
                secondaryColor: ArtworkRGB(red: 0.90, green: 0.46, blue: 0.14),
                accentColor: ArtworkRGB(red: 0.96, green: 0.86, blue: 0.28),
                backgroundColor: ArtworkRGB(red: 0.05, green: 0.22, blue: 0.25)
            )
        } else if artist.contains("billy strings") || artist == "bmfs" {
            // Rust Orange & Woodsmoke Amber
            return ArtworkPalette(
                primaryColor: ArtworkRGB(red: 0.82, green: 0.36, blue: 0.12),
                secondaryColor: ArtworkRGB(red: 0.36, green: 0.20, blue: 0.10),
                accentColor: ArtworkRGB(red: 0.94, green: 0.74, blue: 0.26),
                backgroundColor: ArtworkRGB(red: 0.16, green: 0.08, blue: 0.05)
            )
        } else if artist.contains("widespread panic") || artist == "wsp" {
            // Crimson & Ochre Gold
            return ArtworkPalette(
                primaryColor: ArtworkRGB(red: 0.74, green: 0.16, blue: 0.20),
                secondaryColor: ArtworkRGB(red: 0.82, green: 0.58, blue: 0.14),
                accentColor: ArtworkRGB(red: 0.96, green: 0.86, blue: 0.44),
                backgroundColor: ArtworkRGB(red: 0.20, green: 0.06, blue: 0.08)
            )
        } else if artist.contains("dead & company") || artist.contains("dead and company") {
            // Deep Purple & Electric Cyan
            return ArtworkPalette(
                primaryColor: ArtworkRGB(red: 0.36, green: 0.14, blue: 0.62),
                secondaryColor: ArtworkRGB(red: 0.12, green: 0.52, blue: 0.82),
                accentColor: ArtworkRGB(red: 0.96, green: 0.46, blue: 0.22),
                backgroundColor: ArtworkRGB(red: 0.12, green: 0.05, blue: 0.22)
            )
        }
        return nil
    }

    /// Generates a vibrant, high-contrast procedural palette using HSV color science.
    private static func proceduralPalette(fromSeed seed: UInt64) -> ArtworkPalette {
        let hue1 = Double(seed % 360)
        let hue2 = Double((seed / 360 + 45 + (seed / 1000) % 60) % 360)
        let hueAccent = Double((seed / 7 + 180) % 360)

        let rgb1 = hsvToRGB(h: hue1, s: 0.75, v: 0.58)
        let rgb2 = hsvToRGB(h: hue2, s: 0.80, v: 0.38)
        let rgbAccent = hsvToRGB(h: hueAccent, s: 0.70, v: 0.92)
        let rgbBg = hsvToRGB(h: hue1, s: 0.85, v: 0.18)

        return ArtworkPalette(
            primaryColor: rgb1,
            secondaryColor: rgb2,
            accentColor: rgbAccent,
            backgroundColor: rgbBg
        )
    }

    /// Converts HSV components (H: 0-360, S: 0-1, V: 0-1) to RGB.
    public static func hsvToRGB(h: Double, s: Double, v: Double) -> ArtworkRGB {
        let c = v * s
        let normalizedH = (h.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)
        let x = c * (1 - abs((normalizedH / 60.0).truncatingRemainder(dividingBy: 2) - 1))
        let m = v - c

        var r = 0.0, g = 0.0, b = 0.0
        switch Int(normalizedH / 60.0) % 6 {
        case 0: r = c; g = x; b = 0
        case 1: r = x; g = c; b = 0
        case 2: r = 0; g = c; b = x
        case 3: r = 0; g = x; b = c
        case 4: r = x; g = 0; b = c
        case 5: r = c; g = 0; b = x
        default: break
        }

        return ArtworkRGB(red: r + m, green: g + m, blue: b + m)
    }

    /// Extracts a 2-4 letter monogram for an artist.
    public static func monogram(for artist: String?) -> String {
        guard let artist = artist?.trimmingCharacters(in: .whitespacesAndNewlines), !artist.isEmpty else {
            return "CT"
        }

        let lower = artist.lowercased()
        if lower.contains("grateful dead") || lower == "gd" { return "GD" }
        if lower.contains("jerry garcia") || lower == "jgb" { return "JGB" }
        if lower.contains("phish") { return "PH" }
        if lower.contains("goose") { return "GOOSE" }
        if lower.contains("billy strings") || lower == "bmfs" { return "BMFS" }
        if lower.contains("widespread panic") || lower == "wsp" { return "WSP" }
        if lower.contains("dead & company") || lower.contains("dead and company") { return "D&C" }
        if lower.contains("tedeschi trucks") || lower == "ttb" { return "TTB" }
        if lower.contains("almost dead") || lower == "jrad" { return "JRAD" }
        if lower.contains("string cheese") || lower == "sci" { return "SCI" }
        if lower.contains("disco biscuits") || lower == "tdb" { return "tDB" }
        if lower.contains("umphrey") || lower == "um" { return "UM" }
        if lower.contains("trey anastasio") || lower == "tab" { return "TAB" }
        if lower.contains("king gizzard") || lower == "kglw" { return "KGLW" }

        let words = artist.components(separatedBy: CharacterSet.whitespacesAndNewlines.union(.punctuationCharacters)).filter { !$0.isEmpty }
        if words.count >= 2 {
            let initials = words.prefix(3).compactMap { $0.first }.map { String($0).uppercased() }
            return initials.joined()
        } else if let word = words.first {
            return String(word.prefix(3)).uppercased()
        }
        return "CT"
    }

    /// Extracts year from show date string (e.g. "1977-05-08" -> "1977").
    public static func year(from date: String?) -> String? {
        guard let date = date?.trimmingCharacters(in: .whitespacesAndNewlines), !date.isEmpty else { return nil }
        let parts = date.components(separatedBy: "-")
        if let first = parts.first, first.count == 4, Int(first) != nil {
            return first
        }
        return nil
    }

    /// Extracts formatted month/day (e.g. "1977-05-08" -> "05/08").
    public static func monthDay(from date: String?) -> String? {
        guard let date = date?.trimmingCharacters(in: .whitespacesAndNewlines), !date.isEmpty else { return nil }
        let parts = date.components(separatedBy: "-")
        if parts.count >= 3 {
            return "\(parts[1])/\(parts[2])"
        }
        return nil
    }

    /// Formatted date badge for display on show artwork (e.g. "1977 · 05/08" or "1977").
    public static func dateBadge(from date: String?) -> String {
        let yr = year(from: date)
        let md = monthDay(from: date)
        if let yr, let md {
            return "\(yr) · \(md)"
        } else if let yr {
            return yr
        } else if let date, !date.isEmpty {
            return date
        }
        return "LIVE"
    }
}
