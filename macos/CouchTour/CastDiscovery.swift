import CouchTourKit
import Foundation
import Network

/// Discovers Google Cast devices on the local network using Bonjour mDNS (`_googlecast._tcp`).
@MainActor
public final class CastDiscovery: ObservableObject {
    @Published public private(set) var devices: [CastDevice] = []

    private var browser: NWBrowser?
    private var isBrowsing = false

    public init() {}

    public func startDiscovery() {
        guard !isBrowsing else { return }
        isBrowsing = true

        let descriptor = NWBrowser.Descriptor.bonjour(type: "_googlecast._tcp", domain: "local.")
        let parameters = NWParameters()
        parameters.includePeerToPeer = true

        let newBrowser = NWBrowser(for: descriptor, using: parameters)
        newBrowser.browseResultsChangedHandler = { [weak self] results, _ in
            Task { @MainActor [weak self] in
                self?.handleBrowseResults(results)
            }
        }

        newBrowser.stateUpdateHandler = { [weak self] state in
            if case .failed = state {
                Task { @MainActor [weak self] in
                    self?.stopDiscovery()
                }
            }
        }

        newBrowser.start(queue: .main)
        self.browser = newBrowser
    }

    public func stopDiscovery() {
        guard isBrowsing else { return }
        isBrowsing = false
        browser?.cancel()
        browser = nil
    }

    private func handleBrowseResults(_ results: Set<NWBrowser.Result>) {
        var discovered: [CastDevice] = []

        for result in results {
            var friendlyName: String?
            var modelName: String?
            var deviceId: String?

            // NWTXTRecord exposes fields via getEntry(for:) which returns Entry?, not String?.
            // Entry conforms to CustomStringConvertible; string interpolation extracts the value.
            // Cast receivers advertise friendly name in "fn", model in "md", unique id in "id".
            if case let .bonjour(txtRecord) = result.metadata {
                if let e = txtRecord.getEntry(for: "fn") { friendlyName = "\(e)" }
                if let e = txtRecord.getEntry(for: "md") { modelName = "\(e)" }
                if let e = txtRecord.getEntry(for: "id") { deviceId = "\(e)" }
            }

            var host = ""
            var port = 8009
            var serviceName = ""

            switch result.endpoint {
            case let .service(name, _, _, _):
                serviceName = name
                // Use TXT "fn" as the display name; fall back to the raw service name only
                // when it's absent so users see "Desk" not "Chromecast-4581f47b...".
                if friendlyName?.isEmpty != false {
                    friendlyName = name
                }
                host = "\(name)._googlecast._tcp.local."
            case let .hostPort(hostEndpoint, portEndpoint):
                host = hostEndpoint.debugDescription
                port = Int(portEndpoint.rawValue)
            default:
                break
            }

            // Parentheses required: ?? has lower precedence than ?: so without them this
            // becomes (friendlyName ?? serviceName).isEmpty ? "Cast Device" : serviceName.
            let name = friendlyName ?? (serviceName.isEmpty ? "Cast Device" : serviceName)
            let id = deviceId ?? host

            if !host.isEmpty {
                let device = CastDevice(
                    id: id,
                    name: name,
                    modelName: modelName,
                    host: host,
                    port: port
                )
                discovered.append(device)
            }
        }

        self.devices = discovered.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }
}
