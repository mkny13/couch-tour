import CouchTourKit
import SwiftUI

/// Pairing and device management for progress sync (D119-D127, QR pairing D145). No scanner
/// here — Android is the side that gets a camera and a scanning library (ROADMAP.md); this
/// side only ever shows a code, typed or as a QR, for the other device to consume.
struct SyncView: View {
    @ObservedObject var syncSession: SyncSession
    /// Runs one sync cycle — `AppModel.syncNow` in practice. Used both right after a
    /// successful pair and by the "Sync now" button below.
    let sync: () -> Void

    @State private var pairingResult: PairStartResponse?
    @State private var claimCode = ""
    @State private var busy = false
    @State private var error: String?
    @State private var devices: [DeviceInfo] = []

    var body: some View {
        Form {
            Section {
                Text(
                    "Sync keeps listening history and resume position in step across your " +
                        "paired devices. Pairing is one-time; after that, devices sync on their own."
                )
                .foregroundStyle(.secondary)
            }

            if syncSession.paired {
                Section {
                    Button("Unlink this device") {
                        syncSession.unlink()
                        pairingResult = nil
                    }
                }
                Section {
                    HStack {
                        Text(syncSession.lastSyncedAt.map { "Last synced \(relativeTime($0))" } ?? "Never synced")
                            .foregroundStyle(.secondary)
                        Spacer()
                        Button(syncSession.isSyncing ? "Syncing…" : "Sync now") { sync() }
                            .disabled(syncSession.isSyncing)
                    }
                }
            }

            if let result = pairingResult {
                Section("Enter this code on the other device") {
                    Text(result.code)
                        .font(.system(.largeTitle, design: .monospaced))
                        .bold()
                    Text("Expires in 10 minutes").foregroundStyle(.secondary)
                    if let cgImage = qrCodeImage(result.code) {
                        Image(decorative: cgImage, scale: 1.0)
                            .interpolation(.none)
                            .resizable()
                            .frame(width: 160, height: 160)
                            // A scanner needs real light/dark contrast — the form's own
                            // background shouldn't show through on either side of the code.
                            .padding(12)
                            .background(.white)
                    }
                }
            }

            Section {
                Button(syncSession.paired ? "Add another device" : "Pair this device") {
                    startPairing()
                }
                .disabled(busy)
            }

            if !syncSession.paired {
                Section("Have a code from another device?") {
                    TextField("Code", text: $claimCode)
                        .textFieldStyle(.roundedBorder)
                        // Pairing codes are generated all-uppercase (sync/src/crypto.ts's
                        // randomPairingCode) and looked up by exact hash — a lowercase entry
                        // hashes to a different value and just 401s with no hint why. Android's
                        // TextField already forces this on input; this matches it.
                        .onChange(of: claimCode) { _, newValue in
                            claimCode = newValue.uppercased()
                        }
                        .onSubmit { claimPairing() }
                    Button("Join") { claimPairing() }
                        .disabled(busy || claimCode.isEmpty)
                }
            }

            if let error {
                Text(error).foregroundStyle(.red)
            }

            if syncSession.paired {
                Section("Devices") {
                    ForEach(devices) { device in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(device.name + (device.isSelf ? " (this device)" : ""))
                                Text(device.platform).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("Revoke") { revoke(device.deviceId) }
                        }
                    }
                }
            }

            // Diagnostic detail, not a feature — small, muted, at the bottom of the closest
            // thing to a settings screen this app has today (#43). MARKETING_VERSION in
            // project.yml is bumped by hand alongside each notable build; there's no
            // release-tag pipeline for macOS yet (no CI at all — see CLAUDE.md).
            Section {
                Text("Couch Tour \(appVersion)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Sync")
        .task { if syncSession.paired { await refreshDevices() } }
        .onChange(of: syncSession.paired) { _, paired in
            if paired { Task { await refreshDevices() } }
        }
    }

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "unknown"
    }

    private func startPairing() {
        busy = true
        error = nil
        Task {
            do {
                pairingResult = try await syncSession.startPairing(deviceName: Host.current().localizedName ?? "Mac", platform: "macos")
            } catch {
                self.error = "Couldn't start pairing: \(error.localizedDescription)"
            }
            busy = false
        }
    }

    private func claimPairing() {
        busy = true
        error = nil
        Task {
            do {
                try await syncSession.claimPairing(
                    code: claimCode.trimmingCharacters(in: .whitespaces),
                    deviceName: Host.current().localizedName ?? "Mac",
                    platform: "macos"
                )
                claimCode = ""
                // Sync straight away rather than leaving History empty until the 15-minute
                // timer or a refocus fires — pairing that appears to do nothing reads as
                // failure, which is exactly how this landed the first time.
                sync()
            } catch {
                self.error = "Couldn't join: \(error.localizedDescription)"
            }
            busy = false
        }
    }

    private func revoke(_ deviceId: String) {
        Task {
            try? await syncSession.revoke(deviceId: deviceId)
            await refreshDevices()
        }
    }

    private func refreshDevices() async {
        devices = (try? await syncSession.devices()) ?? []
    }
}
