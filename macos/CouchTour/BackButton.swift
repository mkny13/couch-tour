import SwiftUI

/// Toolbar back button for drilled-down views in the macOS desktop client.
/// Uses `@Environment(\.dismiss)` to pop back to the previous view in the NavigationStack,
/// and attaches standard macOS `⌘[` keyboard shortcut.
struct BackButtonToolbarItem: ToolbarContent {
    @Environment(\.dismiss) private var dismiss

    var body: some ToolbarContent {
        ToolbarItem(placement: .navigation) {
            Button {
                dismiss()
            } label: {
                Label("Back", systemImage: "chevron.left")
            }
            .help("Back (⌘[)")
            .keyboardShortcut("[", modifiers: .command)
        }
    }
}

extension View {
    /// Attaches the standard navigation back button with ⌘[ shortcut to the toolbar.
    func navigationBackButton() -> some View {
        toolbar {
            BackButtonToolbarItem()
        }
    }
}
