import SwiftUI

/// A horizontally scrolling row of cards, with its section header attached.
///
/// Continue Listening and On This Date each built this inline, which is how they ended up with
/// different card padding and the same scroll behaviour written twice.
struct Shelf<Content: View, Trailing: View>: View {
    let title: String
    var systemImage: String?
    @ViewBuilder var trailing: () -> Trailing
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: CardMetrics.headerSpacing) {
            SectionHeader(title: title, systemImage: systemImage, trailing: trailing)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: CardMetrics.shelfSpacing) {
                    content()
                }
                .padding(.vertical, 4)
            }
        }
    }
}

extension Shelf where Trailing == EmptyView {
    init(_ title: String, systemImage: String? = nil, @ViewBuilder content: @escaping () -> Content) {
        self.init(title: title, systemImage: systemImage, trailing: { EmptyView() }, content: content)
    }
}

extension Shelf {
    init(
        _ title: String,
        systemImage: String? = nil,
        @ViewBuilder trailing: @escaping () -> Trailing,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.init(title: title, systemImage: systemImage, trailing: trailing, content: content)
    }
}
