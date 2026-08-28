import SwiftUI

/// The handful of numbers every card, shelf, and tile in the app agrees on.
///
/// #102 found corner radii of 8 and 10 and fill opacities of 0.06 and 0.08 sitting next to each
/// other inside a single screen. That wasn't carelessness so much as the absence of anywhere to
/// put the decision — each shelf re-implemented its own card inline, so each one picked again.
/// This is that somewhere.
enum CardMetrics {
    static let cornerRadius: CGFloat = 10
    /// Small enough to read as a surface rather than a control, high enough to survive
    /// Increase Contrast in both appearances.
    static let fillOpacity: CGFloat = 0.08
    static let padding: CGFloat = 12
    /// Between a section header and its content.
    static let headerSpacing: CGFloat = 12
    /// Between sections.
    static let sectionSpacing: CGFloat = 24
    /// Between cards on a shelf.
    static let shelfSpacing: CGFloat = 14
}

extension View {
    /// The standard card background. Takes its own padding so a call site can't pad one card
    /// differently from the next — the drift this exists to stop.
    func cardSurface(padding: CGFloat = CardMetrics.padding) -> some View {
        self
            .padding(padding)
            .background(
                Color.secondary.opacity(CardMetrics.fillOpacity),
                in: RoundedRectangle(cornerRadius: CardMetrics.cornerRadius)
            )
    }
}
