import SwiftUI

/// A section's title, optionally with a glyph and one trailing action.
///
/// Replaces five hand-rolled `HStack { Label; Spacer; Button }` blocks that had drifted apart on
/// font weight and whether the trailing item was a link, a caption, or nothing.
struct SectionHeader<Trailing: View>: View {
    let title: String
    var systemImage: String?
    var identifier: String?
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack {
            Group {
                if let systemImage {
                    Label(title, systemImage: systemImage)
                } else {
                    Text(title)
                }
            }
            .font(.title3)
            .fontWeight(.semibold)

            Spacer()
            trailing()
        }
        .modifier(OptionalAccessibilityIdentifier(identifier: identifier))
    }
}

private struct OptionalAccessibilityIdentifier: ViewModifier {
    let identifier: String?

    func body(content: Content) -> some View {
        if let identifier = identifier {
            content.accessibilityIdentifier(identifier)
        } else {
            content
        }
    }
}

extension SectionHeader where Trailing == EmptyView {
    init(_ title: String, systemImage: String? = nil, identifier: String? = nil) {
        self.init(title: title, systemImage: systemImage, identifier: identifier) { EmptyView() }
    }
}

extension SectionHeader {
    init(_ title: String, systemImage: String? = nil, identifier: String? = nil, @ViewBuilder trailing: @escaping () -> Trailing) {
        self.init(title: title, systemImage: systemImage, identifier: identifier, trailing: trailing)
    }
}
