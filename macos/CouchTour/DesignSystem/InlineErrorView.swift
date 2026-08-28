import SwiftUI

/// A failed load inside a screen that has other, working sections.
///
/// `ErrorView` (ArtistsView.swift) fills the window, which is right when the whole screen is the
/// failed thing and wrong on Home, where one shelf failing shouldn't blank out the other five.
/// The point of both is the same and is #102's actual complaint: "no data" and "the request
/// failed" must not render identically.
struct InlineErrorView: View {
    let message: String
    var retry: (() async -> Void)?

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
                .accessibilityHidden(true)

            Text(message)
                .font(.callout)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            Spacer(minLength: 8)

            if let retry {
                Button("Retry") { Task { await retry() } }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface()
    }
}
