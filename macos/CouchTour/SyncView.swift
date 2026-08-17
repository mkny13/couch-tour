import CouchTourKit
import SwiftUI

/// Pairing and device management for progress sync (D119-D127, QR pairing D145). No scanner
/// here — Android is the side that gets a camera and a scanning library (ROADMAP.md); this
/// side only ever shows a code, typed or as a QR, for the other device to consume.
struct SyncView: View {
    @ObservedObject var syncSession: SyncSession
    /// Runs one sync cycle right after a successful pair — `AppModel.syncNow` in practice.
    let onPaired: () -> Void

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
        }
        .formStyle(.grouped)
        .navigationTitle("Sync")
        .task { if syncSession.paired { await refreshDevices() } }
        .onChange(of: syncSession.paired) { _, paired in
            if paired { Task { await refreshDevices() } }
        }
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
                onPaired()
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
