// Native accessibility walk for the mac smoke runner (#730).
//
// The JXA walks through System Events cost ~100-200 ms per element, so on a busy screen (104
// search results) a 15 s budget ran out after ~80 elements and the journey reported tabs that
// were plainly on screen as missing. Reading AXIdentifier in-process covers the whole tree in
// well under a second. Output formats match the JXA versions they replace in run-mac.sh.
//
// Usage:
//   ax-walk query    <bundle-id> <target-id|*> <max-depth> <timeout-ms> <all|first>
//   ax-walk children <bundle-id> <target-id> <timeout-ms>
//   ax-walk press    <bundle-id> <target-id> <max-depth> <timeout-ms>
import AppKit
import ApplicationServices

func attr(_ e: AXUIElement, _ name: String) -> AnyObject? {
    var v: AnyObject?
    return AXUIElementCopyAttributeValue(e, name as CFString, &v) == .success ? v : nil
}

func children(_ e: AXUIElement) -> [AXUIElement] { (attr(e, "AXChildren") as? [AXUIElement]) ?? [] }
func identifier(_ e: AXUIElement) -> String? { attr(e, "AXIdentifier") as? String }

// Same "title, else value" text the JXA `element.title() || element.value()` produced.
func line(_ e: AXUIElement) -> String {
    let role = (attr(e, "AXRole") as? String) ?? ""
    var text = (attr(e, "AXTitle") as? String) ?? ""
    if text.isEmpty, let v = attr(e, "AXValue") { text = "\(v)" }
    text = text.components(separatedBy: CharacterSet(charactersIn: "\r\n\t"))
        .filter { !$0.isEmpty }.joined(separator: " ").trimmingCharacters(in: .whitespaces)
    return "\(identifier(e) ?? "")\t\(role)\t\(text)"
}

func matches(_ id: String, _ target: String) -> Bool {
    target.isEmpty || target == "*" || id == target || id.hasPrefix(target + ".")
}

struct Walk {
    let deadline: Date
    var visited = 0
    var timedOut = false

    // Depth 0 is each window, as in the JXA walk over proc.windows().
    mutating func find(in roots: [AXUIElement], maxDepth: Int, firstOnly: Bool,
                       where pred: (String) -> Bool) -> [AXUIElement] {
        var found: [AXUIElement] = []
        func walk(_ e: AXUIElement, _ depth: Int) {
            if firstOnly && !found.isEmpty { return }
            if Date() > deadline { timedOut = true; return }
            visited += 1
            if let id = identifier(e), pred(id) { found.append(e) }
            if depth >= maxDepth { return }
            for c in children(e) { walk(c, depth + 1) }
        }
        for w in roots { walk(w, 0) }
        return found
    }
}

let args = CommandLine.arguments
guard args.count >= 4 else {
    FileHandle.standardError.write("usage: ax-walk query|children|press <bundle-id> <target-id> ...\n".data(using: .utf8)!)
    exit(1)
}
let (cmd, bundleId, target) = (args[1], args[2], args[3])
guard let app = NSRunningApplication.runningApplications(withBundleIdentifier: bundleId).first else {
    print(cmd == "press" ? "NOT_FOUND:proc_null" : "")
    exit(0)
}
let windows = (attr(AXUIElementCreateApplication(app.processIdentifier), "AXWindows") as? [AXUIElement]) ?? []
func deadline(_ ms: String) -> Date { Date().addingTimeInterval((Double(ms) ?? 5000) / 1000) }

switch cmd {
case "query":
    let maxDepth = Int(args[4]) ?? 6
    let firstOnly = args.count > 6 && args[6] == "first"
    var w = Walk(deadline: deadline(args[5]))
    let found = w.find(in: windows, maxDepth: maxDepth, firstOnly: firstOnly) { matches($0, target) }
    if !found.isEmpty { print(found.map(line).joined(separator: "\n")) }
    if w.timedOut && !(firstOnly && !found.isEmpty) {
        // run-mac.sh greps stderr for this exact phrase (MAC_AX_TIMED_OUT).
        FileHandle.standardError.write("ax_query: walk timed out after \(args[5])ms at \(w.visited) elements (target \(target))\n".data(using: .utf8)!)
    }

case "children":
    var w = Walk(deadline: deadline(args[4]))
    if let parent = w.find(in: windows, maxDepth: 6, firstOnly: true, where: { $0 == target }).first {
        let lines = children(parent).map(line)
        if !lines.isEmpty { print(lines.joined(separator: "\n")) }
    }

case "press":
    let maxDepth = Int(args[4]) ?? 6
    var w = Walk(deadline: deadline(args[5]))
    guard let el = w.find(in: windows, maxDepth: maxDepth, firstOnly: true, where: { matches($0, target) }).first else {
        print("NOT_FOUND:wins=\(windows.count):visited=\(w.visited):maxDepth=\(maxDepth):timeoutMs=\(args[5]):timedOut=\(w.timedOut)")
        exit(0)
    }
    // Text fields need keyboard focus rather than a press.
    if (attr(el, "AXRole") as? String) == "AXTextField",
       AXUIElementSetAttributeValue(el, "AXFocused" as CFString, kCFBooleanTrue) == .success {
        print("FOCUSED"); exit(0)
    }
    if AXUIElementPerformAction(el, "AXPress" as CFString) == .success { print("PRESSED"); exit(0) }
    var pos = CGPoint.zero, size = CGSize.zero
    if let p = attr(el, "AXPosition"), let s = attr(el, "AXSize"),
       AXValueGetValue(p as! AXValue, .cgPoint, &pos), AXValueGetValue(s as! AXValue, .cgSize, &size) {
        print("COORDS:\(Int((pos.x + size.width / 2).rounded())):\(Int((pos.y + size.height / 2).rounded()))")
        exit(0)
    }
    print("ACTION_FAILED")

default:
    FileHandle.standardError.write("ax-walk: unknown command \(cmd)\n".data(using: .utf8)!)
    exit(1)
}
