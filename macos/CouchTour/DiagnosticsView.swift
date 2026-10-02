import AppKit
import CouchTourKit
import SwiftUI

/// Sheet showing the on-device diagnostics log with Copy and Clear (#435, D316).
struct DiagnosticsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var summary = ""
    @State private var lines: [String] = []
    @State private var loaded = false
    @State private var copied = false
    @State private var confirmClear = false

    // DiagnosticsLog.init creates the directory and prunes/reads the files, and inspection calls block
    // on its serial queue, so construction and every use below happen off the main actor.
    @State private var logTask: Task<DiagnosticsLog, Never>?

    private func resolveLog() -> Task<DiagnosticsLog, Never> {
        if let logTask { return logTask }
        let t = Task.detached(priority: .userInitiated) { Diagnostics.log ?? DiagnosticsLog() }
        logTask = t
        return t
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Diagnostics").font(.headline)
            Text(summary.isEmpty ? " " : summary)
                .font(.caption)
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
                .accessibilityIdentifier(AXIdentifiers.settingsDiagnosticsSummary)
            ScrollView {
                Text(logText)
                    .font(.system(.caption, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
                    .padding(8)
            }
            .background(Color(nsColor: .textBackgroundColor))
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .accessibilityIdentifier(AXIdentifiers.settingsDiagnosticsLog)
            HStack {
                Button("Copy Diagnostics") { copy() }
                    .accessibilityIdentifier(AXIdentifiers.settingsDiagnosticsCopy)
                if copied {
                    Text("Copied").font(.caption).foregroundStyle(.secondary)
                }
                Button("Clear Diagnostics", role: .destructive) { confirmClear = true }
                    .accessibilityIdentifier(AXIdentifiers.settingsDiagnosticsClear)
                Spacer()
                Button("Done") { dismiss() }
                    .keyboardShortcut(.defaultAction)
                    .accessibilityIdentifier(AXIdentifiers.settingsDiagnosticsDone)
            }
        }
        .padding(16)
        .frame(width: 640, height: 460)
        .alert("Clear diagnostics?", isPresented: $confirmClear) {
            Button("Clear", role: .destructive) { clear() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This deletes the on-device diagnostics log files.")
        }
        .task { await reload() }
    }

    private var logText: String {
        if lines.isEmpty { return loaded ? "No diagnostics recorded." : "Loading…" }
        return lines.joined(separator: "\n")
    }

    private func reload() async {
        let log = await resolveLog().value
        let (s, l) = await Task.detached(priority: .userInitiated) {
            (log.summaryLines(), log.tailLines(200))
        }.value
        summary = s
        lines = l
        loaded = true
    }

    private func copy() {
        let logTask = resolveLog()
        Task {
            let log = await logTask.value
            let text = await Task.detached(priority: .userInitiated) { log.exportText() }.value
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(text, forType: .string)
            copied = true
        }
    }

    private func clear() {
        let logTask = resolveLog()
        Task {
            let log = await logTask.value
            await Task.detached(priority: .userInitiated) { log.clear() }.value
            copied = false
            await reload()
        }
    }
}
