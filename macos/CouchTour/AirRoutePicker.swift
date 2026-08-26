import AVKit
import SwiftUI

/// Wraps AppKit's `AVRoutePickerView` for AirPlay and system audio output device selection.
struct AirRoutePickerView: NSViewRepresentable {
    func makeNSView(context: Context) -> AVRoutePickerView {
        let picker = AVRoutePickerView()
        picker.setRoutePickerButtonColor(.controlAccentColor, for: .normal)
        picker.isRoutePickerButtonBordered = false
        return picker
    }

    func updateNSView(_ nsView: AVRoutePickerView, context: Context) {}
}
