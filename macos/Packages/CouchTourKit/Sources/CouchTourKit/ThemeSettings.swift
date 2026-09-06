import Foundation

#if canImport(SwiftUI)
import SwiftUI
#endif

/// App theme mode preference: auto (system default), light, or dark.
public enum ThemeMode: String, CaseIterable, Identifiable {
    case auto = "auto"
    case light = "light"
    case dark = "dark"

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .auto: return "Auto"
        case .light: return "Light"
        case .dark: return "Dark"
        }
    }

#if canImport(SwiftUI)
    /// Maps to SwiftUI's `ColorScheme?` for use in `.preferredColorScheme(...)`.
    /// `nil` allows SwiftUI to follow the macOS system appearance.
    public var colorScheme: ColorScheme? {
        switch self {
        case .auto: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
#endif
}

/// Persistent theme preferences.
///
/// Backed by `UserDefaults` with `@Published` properties so UI controls, `AppModel`,
/// and views can observe changes reactively.
@MainActor
public final class ThemeSettings: ObservableObject {
    private let defaults: UserDefaults
    private let themeModeKey = "app_theme_mode"

    @Published public var themeMode: ThemeMode {
        didSet {
            defaults.set(themeMode.rawValue, forKey: themeModeKey)
        }
    }

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let raw = defaults.string(forKey: themeModeKey), let mode = ThemeMode(rawValue: raw) {
            self.themeMode = mode
        } else {
            self.themeMode = .auto
        }
    }
}
