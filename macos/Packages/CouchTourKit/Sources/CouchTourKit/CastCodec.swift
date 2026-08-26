import Foundation

/// Encodes and decodes Google Cast V2 channel messages.
///
/// Google Cast V2 uses Protocol Buffers framing over TLS (port 8009).
/// The wire packet format is:
/// - 4 bytes Big-Endian UInt32: Length of the protobuf payload
/// - Protobuf payload: `CastMessage` containing:
///   - field 1 (varint): protocol_version = 0
///   - field 2 (string): source_id
///   - field 3 (string): destination_id
///   - field 4 (string): namespace
///   - field 5 (varint): payload_type = 0 (STRING)
///   - field 6 (string): payload_utf8 (JSON text)
public enum CastCodec {

    /// Represents a Cast wire message envelope.
    public struct Packet: Equatable, Sendable {
        public let sourceId: String
        public let destinationId: String
        public let namespace: String
        public let payloadUtf8: String

        public init(sourceId: String, destinationId: String, namespace: String, payloadUtf8: String) {
            self.sourceId = sourceId
            self.destinationId = destinationId
            self.namespace = namespace
            self.payloadUtf8 = payloadUtf8
        }
    }

    /// Encodes a `Packet` into framed network data (4-byte length prefix + Protobuf binary).
    public static func encodePacket(_ packet: Packet) -> Data {
        let protoData = encodeProtobuf(packet)
        let count = UInt32(protoData.count)
        var data = Data([
            UInt8((count >> 24) & 0xFF),
            UInt8((count >> 16) & 0xFF),
            UInt8((count >> 8) & 0xFF),
            UInt8(count & 0xFF)
        ])
        data.append(protoData)
        return data
    }

    /// Decodes a protobuf payload into a `Packet`.
    public static func decodePacket(from rawData: Data) -> Packet? {
        let protoData = Data(rawData) // Ensure 0-based indexing
        var index = 0
        var sourceId = ""
        var destinationId = ""
        var namespace = ""
        var payloadUtf8 = ""

        while index < protoData.count {
            guard let (tagVarint, tagIndex) = readVarint(from: protoData, offset: index) else { break }
            index = tagIndex
            let fieldNumber = Int(tagVarint >> 3)
            let wireType = Int(tagVarint & 0x07)

            switch wireType {
            case 0: // Varint
                guard let (_, nextIndex) = readVarint(from: protoData, offset: index) else { return nil }
                index = nextIndex
            case 2: // Length-delimited (string / bytes)
                guard let (length, strStart) = readVarint(from: protoData, offset: index) else { return nil }
                let strEnd = strStart + Int(length)
                guard strEnd <= protoData.count else { return nil }
                let fieldData = protoData.subdata(in: strStart..<strEnd)
                let str = String(data: fieldData, encoding: .utf8) ?? ""
                index = strEnd

                switch fieldNumber {
                case 2: sourceId = str
                case 3: destinationId = str
                case 4: namespace = str
                case 6: payloadUtf8 = str
                default: break
                }
            default:
                return nil
            }
        }

        guard !namespace.isEmpty else { return nil }
        return Packet(
            sourceId: sourceId,
            destinationId: destinationId,
            namespace: namespace,
            payloadUtf8: payloadUtf8
        )
    }

    // MARK: - Protobuf Encoding Helpers

    private static func encodeProtobuf(_ packet: Packet) -> Data {
        var data = Data()

        // Field 1: protocol_version = 0 (varint, tag = 1 << 3 | 0 = 8)
        data.append(contentsOf: [0x08, 0x00])

        // Field 2: source_id (string, tag = 2 << 3 | 2 = 18)
        appendStringField(tag: 18, string: packet.sourceId, to: &data)

        // Field 3: destination_id (string, tag = 3 << 3 | 2 = 26)
        appendStringField(tag: 26, string: packet.destinationId, to: &data)

        // Field 4: namespace (string, tag = 4 << 3 | 2 = 34)
        appendStringField(tag: 34, string: packet.namespace, to: &data)

        // Field 5: payload_type = 0 (varint, tag = 5 << 3 | 0 = 40)
        data.append(contentsOf: [0x28, 0x00])

        // Field 6: payload_utf8 (string, tag = 6 << 3 | 2 = 50)
        appendStringField(tag: 50, string: packet.payloadUtf8, to: &data)

        return data
    }

    private static func appendStringField(tag: UInt8, string: String, to data: inout Data) {
        let utf8 = Data(string.utf8)
        data.append(tag)
        appendVarint(UInt64(utf8.count), to: &data)
        data.append(utf8)
    }

    private static func appendVarint(_ value: UInt64, to data: inout Data) {
        var v = value
        while v >= 0x80 {
            data.append(UInt8((v & 0x7F) | 0x80))
            v >>= 7
        }
        data.append(UInt8(v))
    }

    private static func readVarint(from data: Data, offset: Int) -> (UInt64, Int)? {
        var result: UInt64 = 0
        var shift: UInt64 = 0
        var index = offset

        while index < data.count {
            let byte = data[index]
            index += 1
            result |= UInt64(byte & 0x7F) << shift
            if (byte & 0x80) == 0 {
                return (result, index)
            }
            shift += 7
            if shift >= 64 { return nil }
        }
        return nil
    }

    // MARK: - JSON Command Builders

    public static func connectMessage() -> String {
        return #"{"type":"CONNECT","userAgent":"CouchTour-macOS"}"#
    }

    public static func closeMessage() -> String {
        return #"{"type":"CLOSE"}"#
    }

    public static func pingMessage() -> String {
        return #"{"type":"PING"}"#
    }

    public static func pongMessage() -> String {
        return #"{"type":"PONG"}"#
    }

    public static func launchAppMessage(requestId: Int, appId: String = defaultCastReceiverAppId) -> String {
        return #"{"type":"LAUNCH","requestId":\#(requestId),"appId":"\#(appId)"}"#
    }

    public static func stopAppMessage(requestId: Int, sessionId: String) -> String {
        return #"{"type":"STOP","requestId":\#(requestId),"sessionId":"\#(sessionId)"}"#
    }

    public static func getStatusMessage(requestId: Int) -> String {
        return #"{"type":"GET_STATUS","requestId":\#(requestId)}"#
    }

    public static func playMessage(requestId: Int, mediaSessionId: Int) -> String {
        return #"{"type":"PLAY","requestId":\#(requestId),"mediaSessionId":\#(mediaSessionId)}"#
    }

    public static func pauseMessage(requestId: Int, mediaSessionId: Int) -> String {
        return #"{"type":"PAUSE","requestId":\#(requestId),"mediaSessionId":\#(mediaSessionId)}"#
    }

    public static func seekMessage(requestId: Int, mediaSessionId: Int, positionSeconds: Double) -> String {
        return #"{"type":"SEEK","requestId":\#(requestId),"mediaSessionId":\#(mediaSessionId),"currentTime":\#(positionSeconds)}"#
    }

    public static func setVolumeMessage(requestId: Int, level: Double, muted: Bool = false) -> String {
        let clamped = max(0.0, min(1.0, level))
        return #"{"type":"SET_VOLUME","requestId":\#(requestId),"volume":{"level":\#(clamped),"muted":\#(muted)}}"#
    }

    public static func loadMediaMessage(
        requestId: Int,
        mediaInfo: [String: Any],
        autoplay: Bool = true,
        currentTime: Double = 0
    ) -> String? {
        let payload: [String: Any] = [
            "type": "LOAD",
            "requestId": requestId,
            "autoplay": autoplay,
            "currentTime": currentTime,
            "media": mediaInfo
        ]
        guard let jsonData = try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]),
              let jsonString = String(data: jsonData, encoding: .utf8) else {
            return nil
        }
        return jsonString
    }

    // MARK: - JSON Response Parsers

    /// Parses receiver status responses, returning receiver appId, sessionId, transportId, and displayName.
    public struct ReceiverStatus: Equatable, Sendable {
        public let appId: String?
        public let sessionId: String?
        public let transportId: String?
        public let displayName: String?
        public let volumeLevel: Double?
        public let isMuted: Bool?

        public init(
            appId: String? = nil,
            sessionId: String? = nil,
            transportId: String? = nil,
            displayName: String? = nil,
            volumeLevel: Double? = nil,
            isMuted: Bool? = nil
        ) {
            self.appId = appId
            self.sessionId = sessionId
            self.transportId = transportId
            self.displayName = displayName
            self.volumeLevel = volumeLevel
            self.isMuted = isMuted
        }
    }

    public static func parseReceiverStatus(json: String) -> ReceiverStatus? {
        guard let data = json.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let status = dict["status"] as? [String: Any] else {
            return nil
        }

        var appId: String?
        var sessionId: String?
        var transportId: String?
        var displayName: String?

        if let applications = status["applications"] as? [[String: Any]],
           let app = applications.first {
            appId = app["appId"] as? String
            sessionId = app["sessionId"] as? String
            transportId = app["transportId"] as? String
            displayName = app["displayName"] as? String
        }

        var volumeLevel: Double?
        var isMuted: Bool?
        if let volume = status["volume"] as? [String: Any] {
            volumeLevel = volume["level"] as? Double
            isMuted = volume["muted"] as? Bool
        }

        return ReceiverStatus(
            appId: appId,
            sessionId: sessionId,
            transportId: transportId,
            displayName: displayName,
            volumeLevel: volumeLevel,
            isMuted: isMuted
        )
    }

    /// Parses media status messages (`MEDIA_STATUS`).
    public static func parseMediaStatus(json: String) -> CastMediaStatus? {
        guard let data = json.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let statusList = dict["status"] as? [[String: Any]],
              let item = statusList.first else {
            return nil
        }

        let mediaSessionId = item["mediaSessionId"] as? Int
        let playerStateStr = (item["playerState"] as? String) ?? ""
        let playerState = CastMediaStatus.PlayerState(rawValue: playerStateStr) ?? .unknown
        let idleReasonStr = item["idleReason"] as? String
        let idleReason = idleReasonStr.flatMap { CastMediaStatus.IdleReason(rawValue: $0) }
        let currentTime = item["currentTime"] as? Double ?? 0
        let currentItemId = item["currentItemId"] as? Int
        let loadingItemId = item["loadingItemId"] as? Int

        var duration: Double?
        var contentId: String?
        var customDataDict: [String: AnySendable]?

        if let media = item["media"] as? [String: Any] {
            duration = media["duration"] as? Double
            contentId = media["contentId"] as? String
            if let custom = media["customData"] as? [String: Any] {
                customDataDict = custom.mapValues { AnySendable($0) }
            }
        }

        var volumeLevel: Double?
        var isMuted: Bool?
        if let volume = item["volume"] as? [String: Any] {
            volumeLevel = volume["level"] as? Double
            isMuted = volume["muted"] as? Bool
        }

        return CastMediaStatus(
            mediaSessionId: mediaSessionId,
            playerState: playerState,
            idleReason: idleReason,
            currentTime: currentTime,
            duration: duration,
            volumeLevel: volumeLevel,
            isMuted: isMuted,
            currentItemId: currentItemId,
            loadingItemId: loadingItemId,
            contentId: contentId,
            customData: customDataDict
        )
    }
}
