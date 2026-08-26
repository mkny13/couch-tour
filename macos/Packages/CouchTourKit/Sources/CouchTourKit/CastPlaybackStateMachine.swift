import Foundation

/// Pure state machine managing a Google Cast session lifecycle, request sequencing,
/// and remote playback state.
public final class CastPlaybackStateMachine {

    public enum ConnectionState: Equatable, Sendable {
        case disconnected
        case connecting
        case launchingReceiver
        case connectedToReceiver
        case ready
        case error(String)
    }

    public private(set) var connectionState: ConnectionState = .disconnected
    public private(set) var receiverSessionId: String?
    public private(set) var transportId: String?
    public private(set) var mediaSessionId: Int?

    public private(set) var isPlaying: Bool = false
    public private(set) var positionMs: Int64 = 0
    public private(set) var durationMs: Int64 = 0
    public private(set) var volumeLevel: Double = 1.0
    public private(set) var isMuted: Bool = false
    public private(set) var lastIdleReason: CastMediaStatus.IdleReason?

    private var nextRequestId: Int = 1
    private let senderId: String

    public init(senderId: String = "sender-0") {
        self.senderId = senderId
    }

    public func getNextRequestId() -> Int {
        let req = nextRequestId
        nextRequestId += 1
        return req
    }

    // MARK: - Outgoing Packet Generators

    /// Packet to initiate the connection to the Cast device.
    public func createConnectDevicePacket() -> CastCodec.Packet {
        connectionState = .connecting
        return CastCodec.Packet(
            sourceId: senderId,
            destinationId: "receiver-0",
            namespace: CastNamespace.connection,
            payloadUtf8: CastCodec.connectMessage()
        )
    }

    /// Packet to ping the receiver heartbeat.
    public func createPingPacket() -> CastCodec.Packet {
        return CastCodec.Packet(
            sourceId: senderId,
            destinationId: "receiver-0",
            namespace: CastNamespace.heartbeat,
            payloadUtf8: CastCodec.pingMessage()
        )
    }

    /// Packet to launch the default media receiver application.
    public func createLaunchAppPacket(appId: String = defaultCastReceiverAppId) -> CastCodec.Packet {
        connectionState = .launchingReceiver
        let reqId = getNextRequestId()
        return CastCodec.Packet(
            sourceId: senderId,
            destinationId: "receiver-0",
            namespace: CastNamespace.receiver,
            payloadUtf8: CastCodec.launchAppMessage(requestId: reqId, appId: appId)
        )
    }

    /// Packet to connect to the running application transport channel.
    public func createConnectTransportPacket() -> CastCodec.Packet? {
        guard let transportId else { return nil }
        return CastCodec.Packet(
            sourceId: senderId,
            destinationId: transportId,
            namespace: CastNamespace.connection,
            payloadUtf8: CastCodec.connectMessage()
        )
    }

    /// Packet to load a media track on the receiver.
    public func createLoadMediaPacket(
        track: PlayableTrack,
        show: ShowSummary?,
        queueKey: String?,
        currentTimeSeconds: Double = 0
    ) -> CastCodec.Packet? {
        guard let transportId else { return nil }
        let reqId = getNextRequestId()
        let mediaInfo = CastItemConverter.toMediaInfo(track: track, show: show, queueKey: queueKey)
        guard let loadPayload = CastCodec.loadMediaMessage(
            requestId: reqId,
            mediaInfo: mediaInfo,
            autoplay: true,
            currentTime: currentTimeSeconds
        ) else { return nil }

        return CastCodec.Packet(
            sourceId: senderId,
            destinationId: transportId,
            namespace: CastNamespace.media,
            payloadUtf8: loadPayload
        )
    }

    public func createPlayPacket() -> CastCodec.Packet? {
        guard let transportId, let mediaSessionId else { return nil }
        let reqId = getNextRequestId()
        return CastCodec.Packet(
            sourceId: senderId,
            destinationId: transportId,
            namespace: CastNamespace.media,
            payloadUtf8: CastCodec.playMessage(requestId: reqId, mediaSessionId: mediaSessionId)
        )
    }

    public func createPausePacket() -> CastCodec.Packet? {
        guard let transportId, let mediaSessionId else { return nil }
        let reqId = getNextRequestId()
        return CastCodec.Packet(
            sourceId: senderId,
            destinationId: transportId,
            namespace: CastNamespace.media,
            payloadUtf8: CastCodec.pauseMessage(requestId: reqId, mediaSessionId: mediaSessionId)
        )
    }

    public func createSeekPacket(positionMs: Int64) -> CastCodec.Packet? {
        guard let transportId, let mediaSessionId else { return nil }
        let reqId = getNextRequestId()
        let seconds = Double(positionMs) / 1000.0
        return CastCodec.Packet(
            sourceId: senderId,
            destinationId: transportId,
            namespace: CastNamespace.media,
            payloadUtf8: CastCodec.seekMessage(requestId: reqId, mediaSessionId: mediaSessionId, positionSeconds: seconds)
        )
    }

    public func createSetVolumePacket(volume: Double, muted: Bool = false) -> CastCodec.Packet? {
        let reqId = getNextRequestId()
        let dest = transportId ?? "receiver-0"
        return CastCodec.Packet(
            sourceId: senderId,
            destinationId: dest,
            namespace: dest == "receiver-0" ? CastNamespace.receiver : CastNamespace.media,
            payloadUtf8: CastCodec.setVolumeMessage(requestId: reqId, level: volume, muted: muted)
        )
    }

    public func createStopPacket() -> CastCodec.Packet? {
        guard let sessionId = receiverSessionId else { return nil }
        let reqId = getNextRequestId()
        return CastCodec.Packet(
            sourceId: senderId,
            destinationId: "receiver-0",
            namespace: CastNamespace.receiver,
            payloadUtf8: CastCodec.stopAppMessage(requestId: reqId, sessionId: sessionId)
        )
    }

    public func createDisconnectPacket() -> CastCodec.Packet {
        let packet = CastCodec.Packet(
            sourceId: senderId,
            destinationId: transportId ?? "receiver-0",
            namespace: CastNamespace.connection,
            payloadUtf8: CastCodec.closeMessage()
        )
        reset()
        return packet
    }

    // MARK: - Inbound Packet Handling

    public enum Event: Equatable, Sendable {
        case needTransportConnection
        case mediaStatusUpdated
        case mediaFinished
        case receiverDisconnected
        case heartbeatPong
    }

    public func handleIncomingPacket(_ packet: CastCodec.Packet) -> Event? {
        switch packet.namespace {
        case CastNamespace.heartbeat:
            if packet.payloadUtf8.contains("PING") {
                return .heartbeatPong
            }

        case CastNamespace.receiver:
            if let status = CastCodec.parseReceiverStatus(json: packet.payloadUtf8) {
                if let vol = status.volumeLevel { volumeLevel = vol }
                if let muted = status.isMuted { isMuted = muted }

                if status.appId == defaultCastReceiverAppId {
                    self.receiverSessionId = status.sessionId
                    let previousTransportId = self.transportId
                    self.transportId = status.transportId

                    if status.transportId != nil && (previousTransportId == nil || previousTransportId != status.transportId) {
                        connectionState = .connectedToReceiver
                        return .needTransportConnection
                    }
                } else if status.appId == nil && receiverSessionId != nil {
                    // Receiver app was closed
                    reset()
                    return .receiverDisconnected
                }
            }

        case CastNamespace.media:
            if let mediaStatus = CastCodec.parseMediaStatus(json: packet.payloadUtf8) {
                if let session = mediaStatus.mediaSessionId {
                    self.mediaSessionId = session
                }
                if let dur = mediaStatus.duration {
                    self.durationMs = Int64(dur * 1000)
                }
                if let vol = mediaStatus.volumeLevel {
                    self.volumeLevel = vol
                }
                if let muted = mediaStatus.isMuted {
                    self.isMuted = muted
                }
                self.positionMs = Int64(mediaStatus.currentTime * 1000)
                self.lastIdleReason = mediaStatus.idleReason

                switch mediaStatus.playerState {
                case .playing, .buffering:
                    self.isPlaying = true
                    self.connectionState = .ready
                case .paused:
                    self.isPlaying = false
                    self.connectionState = .ready
                case .idle:
                    self.isPlaying = false
                    if mediaStatus.idleReason == .finished {
                        return .mediaFinished
                    }
                case .unknown:
                    break
                }
                return .mediaStatusUpdated
            }

        default:
            break
        }
        return nil
    }

    public func reset() {
        connectionState = .disconnected
        receiverSessionId = nil
        transportId = nil
        mediaSessionId = nil
        isPlaying = false
        positionMs = 0
        durationMs = 0
        lastIdleReason = nil
    }
}
