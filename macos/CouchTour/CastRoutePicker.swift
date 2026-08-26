import CouchTourKit
import SwiftUI

/// Popover and button for selecting Google Cast receivers or AirPlay audio destinations.
struct CastRoutePickerButton: View {
    @EnvironmentObject private var player: Player
    @State private var showPicker = false

    var body: some View {
        Button {
            showPicker.toggle()
        } label: {
            HStack(spacing: 4) {
                Image(systemName: player.isCasting ? "tv.and.mediabox.fill" : "airplayaudio")
                    .foregroundStyle(player.isCasting ? Color.accentColor : Color.primary)
                if let name = player.castDeviceName {
                    Text(name)
                        .font(.caption2)
                        .fontWeight(.semibold)
                        .foregroundStyle(Color.accentColor)
                        .lineLimit(1)
                }
            }
        }
        .buttonStyle(.plain)
        .popover(isPresented: $showPicker, arrowEdge: .top) {
            CastRoutePickerMenu(isPresented: $showPicker)
                .frame(width: 280)
                .padding(12)
        }
    }
}

private struct CastRoutePickerMenu: View {
    @EnvironmentObject private var player: Player
    @Binding var isPresented: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Audio Output & Cast")
                    .font(.headline)
                Spacer()
                Button {
                    isPresented = false
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }

            if player.isCasting, let deviceName = player.castDeviceName {
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Image(systemName: "tv.and.mediabox.fill")
                            .foregroundStyle(Color.accentColor)
                        Text("Casting to \(deviceName)")
                            .font(.subheadline)
                            .fontWeight(.medium)
                        Spacer()
                    }
                    Button("Disconnect Cast") {
                        player.disconnectCast()
                        isPresented = false
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                }
                .padding(8)
                .background(Color.accentColor.opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 8))

                Divider()
            }

            // Google Cast Devices
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("Google Cast Devices")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fontWeight(.semibold)
                    Spacer()
                    ProgressView()
                        .controlSize(.mini)
                }

                if player.castDiscovery.devices.isEmpty {
                    Text("Searching for Cast devices on local network...")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.vertical, 4)
                } else {
                    ForEach(player.castDiscovery.devices) { device in
                        Button {
                            player.connectCast(to: device)
                            isPresented = false
                        } label: {
                            HStack {
                                Image(systemName: "tv")
                                    .foregroundStyle(player.castDeviceName == device.name ? Color.accentColor : Color.secondary)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(device.name)
                                        .font(.subheadline)
                                        .foregroundStyle(player.castDeviceName == device.name ? Color.accentColor : Color.primary)
                                    if let model = device.modelName {
                                        Text(model)
                                            .font(.caption2)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                Spacer()
                                if player.castDeviceName == device.name {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(Color.accentColor)
                                        .font(.caption)
                                }
                            }
                            .contentShape(Rectangle())
                            .padding(.vertical, 4)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            Divider()

            // AirPlay & System Output
            VStack(alignment: .leading, spacing: 6) {
                Text("AirPlay & External Speakers")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fontWeight(.semibold)

                HStack {
                    Image(systemName: "airplayaudio")
                        .foregroundStyle(.secondary)
                    Text("System AirPlay Picker")
                        .font(.subheadline)
                    Spacer()
                    AirRoutePickerView()
                        .frame(width: 32, height: 32)
                }
                .padding(.vertical, 2)
            }
        }
        .onAppear {
            player.castDiscovery.startDiscovery()
        }
        .onDisappear {
            if !player.isCasting {
                player.castDiscovery.stopDiscovery()
            }
        }
    }
}
