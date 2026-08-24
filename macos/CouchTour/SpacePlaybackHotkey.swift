import AppKit

/// Bare Space as play/pause, matching Music/Podcasts — but not via SwiftUI
/// `.keyboardShortcut(.space)`, which never delivers the key when a `List` (the usual
/// browse state) has focus. A local `NSEvent` monitor sees the key first; we swallow it
/// unless a text field is editing so typing in Search/Settings still works. See D177.
enum SpacePlaybackHotkey {
    static func isBareSpace(_ event: NSEvent) -> Bool {
        guard event.charactersIgnoringModifiers == " " else { return false }
        let mods = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
            .subtracting([.capsLock, .numericPad, .function])
        return mods.isEmpty
    }

    static func isEditingText(_ firstResponder: NSResponder?) -> Bool {
        var responder = firstResponder
        while let current = responder {
            if current is NSTextView || current is NSTextField { return true }
            responder = current.nextResponder
        }
        return false
    }

    /// Consume (and on the first press, toggle) when this is a bare Space outside text input.
    static func shouldHandle(_ event: NSEvent, firstResponder: NSResponder?) -> Bool {
        // An AppKit modal (save panel, alert) still uses Space for the default button.
        guard NSApp.modalWindow == nil else { return false }
        return isBareSpace(event) && !isEditingText(firstResponder)
    }
}
