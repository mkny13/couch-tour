import Cocoa
import ApplicationServices

let apps = NSWorkspace.shared.runningApplications
var pid: pid_t = 0
for app in apps {
    if app.bundleIdentifier == "com.apple.finder" {
        pid = app.processIdentifier
        break
    }
}
if pid == 0 { exit(1) }

let appElement = AXUIElementCreateApplication(pid)
var value: CFTypeRef?
let result = AXUIElementCopyAttributeValue(appElement, kAXWindowsAttribute as CFString, &value)
if result == .success, let windows = value as? [AXUIElement] {
    print("Found \(windows.count) windows")
} else {
    print("Failed to get windows: \(result.rawValue)")
}
