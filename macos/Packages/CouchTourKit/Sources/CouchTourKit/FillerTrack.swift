import Foundation

/// Pure filler-track detection and playback queue filtering (#49).
///
/// Many shows on phish.in and Relisten include non-music tracks for intros, outros,
/// tuning, stage banter, crowd noise, and announcements. When the user enables the
/// "skip filler tracks" preference, these tracks are bypassed automatically during
/// playback advancement without altering how the setlist renders in the browse UI.

private let singleFillerTerms: Set<String> = [
    "intro",
    "introduction",
    "intro.",
    "band intro",
    "band intros",
    "band introduction",
    "band introductions",
    "crowd intro",
    "outro",
    "outroduction",
    "outro.",
    "band outro",
    "tuning",
    "stage tuning",
    "tuning / dead air",
    "tuning/dead air",
    "dead air",
    "banter",
    "stage banter",
    "chat",
    "chatter",
    "stage talk",
    "talk",
    "crowd",
    "crowd noise",
    "crowd / applause",
    "applause",
    "cheering",
    "take a step back",
    "take a step back / tuning",
    "take a step back/tuning",
    "announcement",
    "announcements",
    "stage announcement",
    "stage announcements",
    "mc",
    "encore break",
    "encore call",
    "encore break / tuning",
]

/// Returns `true` if the given track title matches a known non-music filler pattern.
public func isFillerTrack(_ title: String) -> Bool {
    var cleaned = title.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    if cleaned.isEmpty { return false }

    // Strip trailing transition symbols like "->", ">", "...", etc.
    while cleaned.hasSuffix("->") || cleaned.hasSuffix(">") || cleaned.hasSuffix("-") || cleaned.hasSuffix(".") {
        if cleaned.hasSuffix("->") {
            cleaned = String(cleaned.dropLast(2)).trimmingCharacters(in: .whitespacesAndNewlines)
        } else {
            cleaned = String(cleaned.dropLast()).trimmingCharacters(in: .whitespacesAndNewlines)
        }
    }

    if singleFillerTerms.contains(cleaned) {
        return true
    }

    // Check compound titles where every segment is a filler term (e.g. "Tuning / Dead Air", "Crowd & Tuning")
    let separators = CharacterSet(charactersIn: "/&+|,")
    let parts = cleaned.components(separatedBy: separators)
        .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        .filter { !$0.isEmpty }

    if parts.count > 1 && parts.allSatisfy({ singleFillerTerms.contains($0) }) {
        return true
    }

    // Prefixes like "tuning: ...", "stage banter - ...", "band introductions: ..."
    let prefixes = [
        "tuning -", "tuning:", "tuning /",
        "stage banter -", "stage banter:", "stage banter /",
        "band intros -", "band intros:", "band introductions -", "band introductions:",
        "stage announcement -", "stage announcement:", "stage announcements -", "stage announcements:",
        "crowd noise -", "crowd noise:",
        "encore break -", "encore break:",
    ]
    for prefix in prefixes {
        if cleaned.hasPrefix(prefix) {
            return true
        }
    }

    return false
}

/// Result of filtering tracks for playback queue insertion.
public struct FilteredPlaybackTracks {
    public let tracks: [PlayableTrack]
    public let startIndex: Int

    public init(tracks: [PlayableTrack], startIndex: Int) {
        self.tracks = tracks
        self.startIndex = startIndex
    }
}

/// Filters a list of tracks when building a playback queue with filler skipping enabled.
///
/// Rules:
/// - If `skipFiller` is `false`, returns the original track list and start index unchanged.
/// - If starting from index 0 and track 0 is filler, advances `startIndex` to the first non-filler track.
/// - If the user explicitly tapped a filler track (`startIndex > 0` and `tracks[startIndex]` is filler),
///   that track is kept so it plays, but subsequent filler tracks are skipped.
/// - All other filler tracks are omitted from the resulting playback queue.
public func filterPlaybackTracks(
    tracks: [PlayableTrack],
    startIndex: Int,
    skipFiller: Bool
) -> FilteredPlaybackTracks {
    guard skipFiller, !tracks.isEmpty, tracks.indices.contains(startIndex) else {
        return FilteredPlaybackTracks(tracks: tracks, startIndex: startIndex)
    }

    var effectiveStartIndex = startIndex
    // If started from top (0) and track 0 is filler, find the first non-filler track
    if effectiveStartIndex == 0 && isFillerTrack(tracks[0].title) {
        if let firstNonFiller = tracks.indices.first(where: { !isFillerTrack(tracks[$0].title) }) {
            effectiveStartIndex = firstNonFiller
        }
    }

    let tappedTrack = tracks[effectiveStartIndex]

    // Keep all non-filler tracks, plus the tapped track if it happens to be filler
    var filtered: [PlayableTrack] = []
    var newStartIndex = 0

    for (idx, track) in tracks.enumerated() {
        let isTapped = (idx == effectiveStartIndex)
        let isFiller = isFillerTrack(track.title)

        if isTapped || !isFiller {
            if isTapped {
                newStartIndex = filtered.count
            }
            filtered.append(track)
        }
    }

    if filtered.isEmpty {
        return FilteredPlaybackTracks(tracks: tracks, startIndex: startIndex)
    }

    return FilteredPlaybackTracks(tracks: filtered, startIndex: newStartIndex)
}
