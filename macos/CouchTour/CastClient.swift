import CouchTourKit
import Foundation
import Network
import Security

/// Manages a live TLS connection to a Google Cast device, driving the Cast V2 protocol,
/// heartbeat ticks, media transport, and state machine updates.
@MainActor
public final class CastClient: ObservableObject {
    @Published public private(set) var connectedDevice: CastDevice?
    @Published public private(set) var isConnected = false
    @Published public private(set) var isPlaying = false
    @Published public private(set) var positionMs: Int64 = 0
    @Published public private(set) var durationMs: Int64 = 0
    @Published public private(set) var volumeLevel: Double = 1.0
    @Published public private(set) var isMuted = false

    public var onTrackFinished: (() -> Void)?
    public var onPlaybackStateChanged: ((Bool) -> Void)?
    public var onPositionTick: ((Int64) -> Void)?

    private var connection: NWConnection?
    private let stateMachine = CastPlaybackStateMachine()
    private var heartbeatTimer: Task<Void, Never>?
    private var pendingLoadTrack: (track: PlayableTrack, show: ShowSummary?, queueKey: String?, resumeMs: Int64)?

    public init() {}

    deinit {
        heartbeatTimer?.cancel()
        connection?.cancel()
    }

    public func connect(to device: CastDevice) {
        disconnect()

        connectedDevice = device
        let endpoint = NWEndpoint.hostPort(
            host: NWEndpoint.Host(device.host),
            port: NWEndpoint.Port(rawValue: UInt16(device.port)) ?? 8009
        )

        let tlsOptions = NWProtocolTLS.Options()
        // Cast receivers use self-signed local device certificates; allow local verification.
        sec_protocol_options_set_verify_block(tlsOptions.securityProtocolOptions, { (_, _, completionHandler) in
            completionHandler(true)
        }, DispatchQueue.global())

        let parameters = NWParameters(tls: tlsOptions, tcp: NWProtocolTCP.Options())
        parameters.includePeerToPeer = true

        let conn = NWConnection(to: endpoint, using: parameters)
        self.connection = conn

        conn.stateUpdateHandler = { [weak self] state in
            Task { @MainActor [weak self] in
                self?.handleConnectionState(state)
            }
        }

        conn.start(queue: .main)
    }

    public func disconnect() {
        heartbeatTimer?.cancel()
        heartbeatTimer = nil

        if isConnected {
            let disconnectPacket = stateMachine.createDisconnectPacket()
            sendPacket(disconnectPacket)
        }

        connection?.cancel()
        connection = nil
        connectedDevice = nil
        isConnected = false
        isPlaying = false
        positionMs = 0
        durationMs = 0
        stateMachine.reset()
    }

    private func handleConnectionState(_ state: NWConnection.State) {
        switch state {
        case .ready:
            isConnected = true
            startHeartbeat()
            // 1. Send device CONNECT packet
            let connectPacket = stateMachine.createConnectDevicePacket()
            sendPacket(connectPacket)
            // 2. Launch Default Media Receiver app
            let launchPacket = stateMachine.createLaunchAppPacket()
            sendPacket(launchPacket)
            // 3. Start packet read loop
            receiveNextPacket()

        case .failed(let error):
            print("[CastClient] Connection failed: \(error)")
            disconnect()

        case .cancelled:
            isConnected = false

        default:
            break
        }
    }

    // MARK: - Packet I/O

    private func sendPacket(_ packet: CastCodec.Packet) {
        guard let connection, isConnected else { return }
        let data = CastCodec.encodePacket(packet)
        connection.send(content: data, completion: .contentProcessed { error in
            if let error {
                print("[CastClient] Error sending packet: \(error)")
            }
        })
    }

    private func receiveNextPacket() {
        guard let connection, isConnected else { return }

        // Read 4-byte Big-Endian length header
        connection.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if isComplete || error != nil {
                Task { @MainActor [weak self] in self?.disconnect() }
                return
            }

            guard let data, data.count == 4 else {
                Task { @MainActor [weak self] in self?.receiveNextPacket() }
                return
            }

            let length = (UInt32(data[data.startIndex]) << 24) |
                         (UInt32(data[data.startIndex + 1]) << 16) |
                         (UInt32(data[data.startIndex + 2]) << 8) |
                         UInt32(data[data.startIndex + 3])

            guard length > 0, length < 65536 else {
                Task { @MainActor [weak self] in self?.receiveNextPacket() }
                return
            }

            // Read the protobuf message body
            connection.receive(minimumIncompleteLength: Int(length), maximumLength: Int(length)) { [weak self] bodyData, _, isBodyComplete, bodyError in
                guard let self else { return }
                if isBodyComplete || bodyError != nil {
                    Task { @MainActor [weak self] in self?.disconnect() }
                    return
                }

                if let bodyData, let packet = CastCodec.decodePacket(from: bodyData) {
                    Task { @MainActor [weak self] in
                        self?.processIncomingPacket(packet)
                    }
                }

                Task { @MainActor [weak self] in
                    self?.receiveNextPacket()
                }
            }
        }
    }

    private func processIncomingPacket(_ packet: CastCodec.Packet) {
        if let event = stateMachine.handleIncomingPacket(packet) {
            switch event {
            case .needTransportConnection:
                if let transportPacket = stateMachine.createConnectTransportPacket() {
                    sendPacket(transportPacket)
                }
                // If we had a pending load request waiting for receiver launch, execute it now
                if let pending = pendingLoadTrack {
                    pendingLoadTrack = nil
                    load(
                        track: pending.track,
                        show: pending.show,
                        queueKey: pending.queueKey,
                        resumePositionMs: pending.resumeMs
                    )
                }

            case .mediaStatusUpdated:
                let wasPlaying = self.isPlaying
                self.isPlaying = stateMachine.isPlaying
                self.positionMs = stateMachine.positionMs
                if stateMachine.durationMs > 0 {
                    self.durationMs = stateMachine.durationMs
                }
                self.volumeLevel = stateMachine.volumeLevel
                self.isMuted = stateMachine.isMuted
                onPositionTick?(self.positionMs)
                if wasPlaying != self.isPlaying {
                    onPlaybackStateChanged?(self.isPlaying)
                }

            case .mediaFinished:
                self.isPlaying = false
                onPlaybackStateChanged?(false)
                onTrackFinished?()

            case .receiverDisconnected:
                disconnect()

            case .heartbeatPong:
                let pong = CastCodec.Packet(
                    sourceId: "sender-0",
                    destinationId: "receiver-0",
                    namespace: CastNamespace.heartbeat,
                    payloadUtf8: CastCodec.pongMessage()
                )
                sendPacket(pong)
            }
        }
    }

    private func startHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(5))
                guard let self, self.isConnected else { break }
                let ping = self.stateMachine.createPingPacket()
                self.sendPacket(ping)
            }
        }
    }

    // MARK: - Playback Transport Controls

    public func load(
        track: PlayableTrack,
        show: ShowSummary?,
        queueKey: String?,
        resumePositionMs: Int64 = 0
    ) {
        guard isConnected else {
            pendingLoadTrack = (track, show, queueKey, resumePositionMs)
            return
        }

        let seconds = Double(resumePositionMs) / 1000.0
        if let loadPacket = stateMachine.createLoadMediaPacket(
            track: track,
            show: show,
            queueKey: queueKey,
            currentTimeSeconds: seconds
        ) {
            sendPacket(loadPacket)
        } else {
            pendingLoadTrack = (track, show, queueKey, resumePositionMs)
        }
    }

    public func play() {
        if let playPacket = stateMachine.createPlayPacket() {
            sendPacket(playPacket)
        }
    }

    public func pause() {
        if let pausePacket = stateMachine.createPausePacket() {
            sendPacket(pausePacket)
        }
    }

    public func seek(toMs ms: Int64) {
        positionMs = ms
        if let seekPacket = stateMachine.createSeekPacket(positionMs: ms) {
            sendPacket(seekPacket)
        }
    }

    public func setVolume(_ volume: Double) {
        volumeLevel = volume
        if let volPacket = stateMachine.createSetVolumePacket(volume: volume) {
            sendPacket(volPacket)
        }
    }
}
