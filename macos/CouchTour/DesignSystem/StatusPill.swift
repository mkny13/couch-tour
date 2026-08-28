import SwiftUI

/// A small glyph-plus-label badge for state that used to be carried by colour alone.
///
/// #102 flagged two of these: the sync icon's green-vs-secondary tint was the only thing
/// distinguishing paired from unpaired, and the codec badge was green FLAC vs grey MP3. Colour
/// is still there — it's a real signal for anyone who can see it — but it is no longer the
/// *only* one, which is what makes the state perceptible under Increase Contrast, in
/// greyscale, and to VoiceOver.
struct StatusPill: View {
    let text: String
    let systemImage: String
    var tone: Tone = .neutral

    enum Tone {
        case affirmative
        case neutral

        var color: Color {
            switch self {
            case .affirmative: return .green
            case .neutral: return .secondary
            }
        }
    }

    var body: some View {
        Label(text, systemImage: systemImage)
            // A semantic style, not the `.system(size: 9)` this replaced — 9pt was already
            // below the smallest comfortable reading size and ignored the text-size setting
            // outright.
            .font(.caption2.weight(.semibold))
            .labelStyle(.titleAndIcon)
            .padding(.horizontal, 5)
            .padding(.vertical, 2)
            .background(tone.color.opacity(0.18), in: RoundedRectangle(cornerRadius: 4))
            .foregroundStyle(tone.color)
    }
}

extension StatusPill {
    /// FLAC or MP3 for the track currently playing. Both the player bar and the Now Playing
    /// inspector drew their own version of this; they now draw the same one.
    static func codec(isFlac: Bool) -> StatusPill {
        StatusPill(
            text: isFlac ? "FLAC" : "MP3",
            systemImage: isFlac ? "waveform.badge.plus" : "waveform",
            tone: isFlac ? .affirmative : .neutral
        )
    }

    static func paired(_ paired: Bool) -> StatusPill {
        StatusPill(
            text: paired ? "Paired" : "Not paired",
            systemImage: paired ? "checkmark.circle.fill" : "circle.dashed",
            tone: paired ? .affirmative : .neutral
        )
    }
}
