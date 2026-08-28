import CouchTourKit
import SwiftUI

/// The window's one Back button, in the root toolbar beside the breadcrumb.
///
/// It used to be re-attached per drilled-down view via `@Environment(\.dismiss)`, which is what
/// six independent `NavigationStack`s required. With a single stack (D203) there is one path to
/// pop, so there is one button — and ⌘[ now works from every screen rather than only from views
/// that remembered to ask for it. On Home there's nothing to pop and it disables itself, which
/// is also the "you are at the root" signal the sidebar's selected row used to give.
struct BackButtonToolbarItem: ToolbarContent {
    @EnvironmentObject private var appModel: AppModel

    var body: some ToolbarContent {
        ToolbarItem(placement: .navigation) {
            Button {
                appModel.path.removeLast()
            } label: {
                Label("Back", systemImage: "chevron.left")
            }
            .help("Back (⌘[)")
            .keyboardShortcut("[", modifiers: .command)
            .disabled(appModel.path.isEmpty)
        }
    }
}
