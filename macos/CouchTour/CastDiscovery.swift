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

            // Parse TXT record metadata
            if case let .bonjour(txtRecord) = result.metadata {
                friendlyName = txtRecord.dictionary["fn"]
                modelName = txtRecord.dictionary["md"]
                deviceId = txtRecord.dictionary["id"]
            }

            var host = ""
            var port = 8009

            switch result.endpoint {
            case let .service(name, _, _, _):
                if friendlyName == nil || friendlyName?.isEmpty == true {
                    friendlyName = name
                }
                host = "\(name)._googlecast._tcp.local."
            case let .hostPort(hostEndpoint, portEndpoint):
                host = hostEndpoint.debugDescription
                port = Int(portEndpoint.rawValue)
            default:
                break
            }

            let name = friendlyName ?? "Cast Device"
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
