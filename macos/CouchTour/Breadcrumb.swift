import CouchTourKit
import SwiftUI

/// Where you are, as a path rather than a single label.
///
/// This is the orientation job the sidebar's selected row used to do (D203). It does it better:
/// a highlighted sidebar row said "Artists" no matter how many levels deep into an artist's
/// years and shows you'd gone, whereas this says *Couch Tour › Grateful Dead › 1977*. Every
/// crumb but the leaf is a button that truncates the path to that level, so it's a way back up
/// as well as a place indicator.
struct Breadcrumb: View {
    @EnvironmentObject private var appModel: AppModel

    var body: some View {
        let trail = breadcrumbTrail(path: appModel.path)

        HStack(spacing: 4) {
            ForEach(Array(trail.enumerated()), id: \.offset) { index, title in
                if index > 0 {
                    Text("›")
                        .foregroundStyle(.tertiary)
                        .accessibilityHidden(true)
                }

                if index == trail.count - 1 {
                    Text(title)
                        .fontWeight(.semibold)
                        .lineLimit(1)
                } else {
                    Button(title) {
                        // The trail is the app name followed by one entry per path element, so
                        // crumb `index` is a path of exactly `index` levels.
                        appModel.popTo(depth: index)
                    }
                    .buttonStyle(.link)
                    .lineLimit(1)
                }
            }
        }
        .font(.callout)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Breadcrumb: \(trail.joined(separator: ", "))")
    }
}
