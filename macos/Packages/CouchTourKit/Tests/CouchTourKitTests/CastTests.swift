import CouchTourKit
import XCTest

final class CastTests: XCTestCase {

    func testCastDeviceCreationAndEquality() {
        let device1 = CastDevice(id: "dev-1", name: "Living Room TV", modelName: "Chromecast Ultra", host: "192.168.1.50", port: 8009)
        let device2 = CastDevice(id: "dev-1", name: "Living Room TV", modelName: "Chromecast Ultra", host: "192.168.1.50", port: 8009)
        let device3 = CastDevice(id: "dev-2", name: "Bedroom Speaker", host: "192.168.1.51")

        XCTAssertEqual(device1, device2)
        XCTAssertNotEqual(device1, device3)
        XCTAssertEqual(device1.port, 8009)
        XCTAssertEqual(device3.port, 8009)
    }

    func testCastItemConverterMP3Track() {
        let artist = ArtistRef(backend: .phishin, id: "phish", name: "Phish")
        let show = ShowSummary(
            artist: artist,
            date: "1997-11-17",
            venue: "McNichols Sports Arena",
            location: "Denver, CO",
            artURL: "https://phish.in/art.jpg",
            rating: 4.9
        )
        let track = PlayableTrack(
            id: "101",
            title: "Ghost",
            durationMs: 1260000,
            url: "https://phish.in/audio/ghost.mp3",
            showDate: "1997-11-17",
            venueName: "McNichols Sports Arena",
            likesCount: 42,
            likedByUser: true
        )

        let mediaInfo = CastItemConverter.toMediaInfo(track: track, show: show, queueKey: "show:1997-11-17")
        XCTAssertEqual(mediaInfo["contentId"] as? String, "https://phish.in/audio/ghost.mp3")
        XCTAssertEqual(mediaInfo["streamType"] as? String, "BUFFERED")
        XCTAssertEqual(mediaInfo["contentType"] as? String, "audio/mp3")
        XCTAssertEqual(mediaInfo["duration"] as? Double, 1260.0)

        guard let metadata = mediaInfo["metadata"] as? [String: Any],
              let customData = mediaInfo["customData"] as? [String: Any] else {
            XCTFail("Missing metadata or customData")
            return
        }

        XCTAssertEqual(metadata["title"] as? String, "Ghost")
        XCTAssertEqual(metadata["artist"] as? String, "Phish")
        XCTAssertEqual(metadata["albumName"] as? String, "1997-11-17 · McNichols Sports Arena")
        XCTAssertEqual(customData[CastKeys.queueKey] as? String, "show:1997-11-17")
        XCTAssertEqual(customData[CastKeys.mediaId] as? String, "101")
        XCTAssertEqual(customData[CastKeys.backend] as? String, "phishin")
        XCTAssertEqual(customData[CastKeys.liked] as? Bool, true)
        XCTAssertEqual(customData[CastKeys.likesCount] as? Int, 42)
    }

    func testCastItemConverterFlacFallbackToMp3() {
        let artist = ArtistRef(backend: .relisten, id: "grateful-dead", name: "Grateful Dead")
        let show = ShowSummary(
            artist: artist,
            date: "1977-05-08",
            venue: "Barton Hall",
            location: "Ithaca, NY",
            rating: 4.95
        )
        let track = PlayableTrack(
            id: "201",
            title: "Scarlet Begonias",
            durationMs: 580000,
            url: "https://archive.org/download/gd77-05-08/track.mp3",
            showDate: "1977-05-08",
            venueName: "Barton Hall",
            flacUrl: "https://archive.org/download/gd77-05-08/track.flac"
        )

        let mediaInfo = CastItemConverter.toMediaInfo(track: track, show: show, queueKey: "relisten:grateful-dead:1977-05-08:src-1")
        // Stream URL should be MP3 for Cast receiver compatibility (D187)
        XCTAssertEqual(mediaInfo["contentId"] as? String, "https://archive.org/download/gd77-05-08/track.mp3")
        XCTAssertEqual(mediaInfo["contentType"] as? String, "audio/mp3")

        guard let customData = mediaInfo["customData"] as? [String: Any] else {
            XCTFail("Missing customData")
            return
        }

        // Lossless FLAC url must be preserved in customData so local playback can restore it
        XCTAssertEqual(customData[CastKeys.flacUrl] as? String, "https://archive.org/download/gd77-05-08/track.flac")
        XCTAssertEqual(customData[CastKeys.mp3Url] as? String, "https://archive.org/download/gd77-05-08/track.mp3")
    }

    func testCastCodecEncodeAndDecodePacket() {
        let originalPacket = CastCodec.Packet(
            sourceId: "sender-42",
            destinationId: "receiver-0",
            namespace: CastNamespace.heartbeat,
            payloadUtf8: #"{"type":"PING"}"#
        )

        let encoded = CastCodec.encodePacket(originalPacket)
        XCTAssertGreaterThan(encoded.count, 4)

        // First 4 bytes are Big-Endian length
        let protoLength = (UInt32(encoded[0]) << 24) | (UInt32(encoded[1]) << 16) | (UInt32(encoded[2]) << 8) | UInt32(encoded[3])
        XCTAssertEqual(Int(protoLength), encoded.count - 4)

        let protoData = Data(encoded.dropFirst(4))
        let decodedPacket = CastCodec.decodePacket(from: protoData)
        XCTAssertNotNil(decodedPacket)
        XCTAssertEqual(decodedPacket?.sourceId, "sender-42")
        XCTAssertEqual(decodedPacket?.destinationId, "receiver-0")
        XCTAssertEqual(decodedPacket?.namespace, CastNamespace.heartbeat)
        XCTAssertEqual(decodedPacket?.payloadUtf8, #"{"type":"PING"}"#)
    }

    func testCastCodecJSONBuilders() {
        XCTAssertTrue(CastCodec.connectMessage().contains(#""type":"CONNECT""#))
        XCTAssertTrue(CastCodec.closeMessage().contains(#""type":"CLOSE""#))
        XCTAssertTrue(CastCodec.pingMessage().contains(#""type":"PING""#))
        XCTAssertTrue(CastCodec.pongMessage().contains(#""type":"PONG""#))

        let launch = CastCodec.launchAppMessage(requestId: 1, appId: defaultCastReceiverAppId)
        XCTAssertTrue(launch.contains(#""type":"LAUNCH""#))
        XCTAssertTrue(launch.contains(#""appId":"CC1AD845""#))
        XCTAssertTrue(launch.contains(#""requestId":1"#))

        let play = CastCodec.playMessage(requestId: 2, mediaSessionId: 10)
        XCTAssertTrue(play.contains(#""type":"PLAY""#))
        XCTAssertTrue(play.contains(#""mediaSessionId":10"#))

        let pause = CastCodec.pauseMessage(requestId: 3, mediaSessionId: 10)
        XCTAssertTrue(pause.contains(#""type":"PAUSE""#))
        XCTAssertTrue(pause.contains(#""mediaSessionId":10"#))

        let seek = CastCodec.seekMessage(requestId: 4, mediaSessionId: 10, positionSeconds: 123.45)
        XCTAssertTrue(seek.contains(#""type":"SEEK""#))
        XCTAssertTrue(seek.contains(#""currentTime":123.45"#))

        let vol = CastCodec.setVolumeMessage(requestId: 5, level: 0.75, muted: false)
        XCTAssertTrue(vol.contains(#""type":"SET_VOLUME""#))
        XCTAssertTrue(vol.contains(#""level":0.75"#))
    }

    func testCastCodecParseReceiverStatus() {
        let json = """
        {
            "requestId": 1,
            "status": {
                "applications": [
                    {
                        "appId": "CC1AD845",
                        "displayName": "Default Media Receiver",
                        "sessionId": "session-1234",
                        "transportId": "transport-5678"
                    }
                ],
                "volume": {
                    "level": 0.85,
                    "muted": false
                }
            }
        }
        """

        let status = CastCodec.parseReceiverStatus(json: json)
        XCTAssertNotNil(status)
        XCTAssertEqual(status?.appId, "CC1AD845")
        XCTAssertEqual(status?.sessionId, "session-1234")
        XCTAssertEqual(status?.transportId, "transport-5678")
        XCTAssertEqual(status?.displayName, "Default Media Receiver")
        XCTAssertEqual(status?.volumeLevel, 0.85)
        XCTAssertEqual(status?.isMuted, false)
    }

    func testCastCodecParseMediaStatus() {
        let json = """
        {
            "requestId": 2,
            "status": [
                {
                    "mediaSessionId": 99,
                    "playerState": "PLAYING",
                    "currentTime": 45.5,
                    "media": {
                        "contentId": "https://phish.in/audio/tweezer.mp3",
                        "duration": 900.0,
                        "customData": {
                            "queue_key": "show:1995-12-02",
                            "media_id": "4001"
                        }
                    },
                    "volume": {
                        "level": 0.9,
                        "muted": false
                    }
                }
            ]
        }
        """

        let mediaStatus = CastCodec.parseMediaStatus(json: json)
        XCTAssertNotNil(mediaStatus)
        XCTAssertEqual(mediaStatus?.mediaSessionId, 99)
        XCTAssertEqual(mediaStatus?.playerState, .playing)
        XCTAssertEqual(mediaStatus?.currentTime, 45.5)
        XCTAssertEqual(mediaStatus?.duration, 900.0)
        XCTAssertEqual(mediaStatus?.volumeLevel, 0.9)
        XCTAssertEqual(mediaStatus?.contentId, "https://phish.in/audio/tweezer.mp3")
        XCTAssertEqual(mediaStatus?.customData?[CastKeys.queueKey]?.value as? String, "show:1995-12-02")
    }

    func testCastPlaybackStateMachineLifecycle() {
        let sm = CastPlaybackStateMachine(senderId: "test-sender")
        XCTAssertEqual(sm.connectionState, .disconnected)

        // 1. Connect Device
        let connectPacket = sm.createConnectDevicePacket()
        XCTAssertEqual(connectPacket.namespace, CastNamespace.connection)
        XCTAssertEqual(sm.connectionState, .connecting)

        // 2. Launch Receiver App
        let launchPacket = sm.createLaunchAppPacket()
        XCTAssertEqual(launchPacket.namespace, CastNamespace.receiver)
        XCTAssertEqual(sm.connectionState, .launchingReceiver)

        // 3. Receiver answers with status
        let receiverStatusJSON = """
        {
            "requestId": 1,
            "status": {
                "applications": [
                    {
                        "appId": "CC1AD845",
                        "displayName": "Default Media Receiver",
                        "sessionId": "session-abc",
                        "transportId": "transport-xyz"
                    }
                ]
            }
        }
        """
        let incomingReceiverPacket = CastCodec.Packet(
            sourceId: "receiver-0",
            destinationId: "test-sender",
            namespace: CastNamespace.receiver,
            payloadUtf8: receiverStatusJSON
        )
        let event = sm.handleIncomingPacket(incomingReceiverPacket)
        XCTAssertEqual(event, .needTransportConnection)
        XCTAssertEqual(sm.receiverSessionId, "session-abc")
        XCTAssertEqual(sm.transportId, "transport-xyz")
        XCTAssertEqual(sm.connectionState, .connectedToReceiver)

        // 4. Connect Transport Channel
        let transportConn = sm.createConnectTransportPacket()
        XCTAssertNotNil(transportConn)
        XCTAssertEqual(transportConn?.destinationId, "transport-xyz")

        // 5. Load Media
        let artist = ArtistRef(backend: .phishin, id: "phish", name: "Phish")
        let show = ShowSummary(
            artist: artist,
            date: "1997-12-06",
            venue: "The Palace of Auburn Hills",
            location: "Auburn Hills, MI",
            rating: 4.8
        )
        let track = PlayableTrack(id: "1", title: "Tweezer", durationMs: 900000, url: "https://phish.in/tweezer.mp3")

        let loadPacket = sm.createLoadMediaPacket(track: track, show: show, queueKey: "show:1997-12-06", currentTimeSeconds: 30)
        XCTAssertNotNil(loadPacket)
        XCTAssertEqual(loadPacket?.destinationId, "transport-xyz")
        XCTAssertEqual(loadPacket?.namespace, CastNamespace.media)

        // 6. Media status response arrives: Playing
        let mediaStatusJSON = """
        {
            "requestId": 2,
            "status": [
                {
                    "mediaSessionId": 55,
                    "playerState": "PLAYING",
                    "currentTime": 32.0,
                    "media": {
                        "duration": 900.0
                    }
                }
            ]
        }
        """
        let incomingMediaPacket = CastCodec.Packet(
            sourceId: "transport-xyz",
            destinationId: "test-sender",
            namespace: CastNamespace.media,
            payloadUtf8: mediaStatusJSON
        )
        let mediaEvent = sm.handleIncomingPacket(incomingMediaPacket)
        XCTAssertEqual(mediaEvent, .mediaStatusUpdated)
        XCTAssertEqual(sm.isPlaying, true)
        XCTAssertEqual(sm.positionMs, 32000)
        XCTAssertEqual(sm.durationMs, 900000)
        XCTAssertEqual(sm.mediaSessionId, 55)
        XCTAssertEqual(sm.connectionState, .ready)

        // 7. Pause, Seek, Set Volume
        let pausePacket = sm.createPausePacket()
        XCTAssertNotNil(pausePacket)
        XCTAssertEqual(pausePacket?.destinationId, "transport-xyz")

        let seekPacket = sm.createSeekPacket(positionMs: 60000)
        XCTAssertNotNil(seekPacket)
        XCTAssertTrue(seekPacket?.payloadUtf8.contains(#""currentTime":60"#) == true)

        let volPacket = sm.createSetVolumePacket(volume: 0.5)
        XCTAssertNotNil(volPacket)
        XCTAssertTrue(volPacket?.payloadUtf8.contains(#""level":0.5"#) == true)

        // 8. Finished track
        let finishedJSON = """
        {
            "requestId": 3,
            "status": [
                {
                    "mediaSessionId": 55,
                    "playerState": "IDLE",
                    "idleReason": "FINISHED",
                    "currentTime": 900.0
                }
            ]
        }
        """
        let finishPacket = CastCodec.Packet(
            sourceId: "transport-xyz",
            destinationId: "test-sender",
            namespace: CastNamespace.media,
            payloadUtf8: finishedJSON
        )
        let finishEvent = sm.handleIncomingPacket(finishPacket)
        XCTAssertEqual(finishEvent, .mediaFinished)
        XCTAssertEqual(sm.isPlaying, false)
        XCTAssertEqual(sm.lastIdleReason, .finished)

        // 9. Disconnect
        let disconnect = sm.createDisconnectPacket()
        XCTAssertEqual(disconnect.namespace, CastNamespace.connection)
        XCTAssertEqual(sm.connectionState, .disconnected)
        XCTAssertNil(sm.receiverSessionId)
        XCTAssertNil(sm.transportId)
        XCTAssertNil(sm.mediaSessionId)
    }

    func testHeartbeatPingGeneratesPongEvent() {
        let sm = CastPlaybackStateMachine(senderId: "test-sender")
        let pingPacket = CastCodec.Packet(
            sourceId: "receiver-0",
            destinationId: "test-sender",
            namespace: CastNamespace.heartbeat,
            payloadUtf8: #"{"type":"PING"}"#
        )
        let event = sm.handleIncomingPacket(pingPacket)
        XCTAssertEqual(event, .heartbeatPong)
    }
}
