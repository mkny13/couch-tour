import Foundation

/// A minimal stand-in for OkHttp's MockWebServer, built on `URLProtocol` since Foundation has
/// no bundled equivalent. Queues canned responses and records every request that was made
/// against them, in order — enough to pin the request-shape traps the Android tests pin
/// (query params, path segments, headers), without a real network call.
final class MockServer {
    private final class Interceptor: URLProtocol {
        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

        override func startLoading() {
            MockServer.active?.handle(self)
        }

        override func stopLoading() {}
    }

    private static var active: MockServer?

    private var responses: [(code: Int, body: String, headers: [String: String])] = []
    private(set) var requests: [URLRequest] = []
    private let lock = NSLock()
    /// Total requests ever received, unlike `requests.count` which shrinks as `takeRequest`
    /// drains the queue — mirrors OkHttp MockWebServer's `requestCount`.
    private(set) var requestCount = 0

    func start() {
        MockServer.active = self
        URLProtocol.registerClass(Interceptor.self)
    }

    func shutdown() {
        URLProtocol.unregisterClass(Interceptor.self)
        MockServer.active = nil
    }

    func enqueue(_ body: String, code: Int = 200, headers: [String: String] = [:]) {
        lock.lock(); defer { lock.unlock() }
        responses.append((code, body, headers))
    }

    func takeRequest() -> URLRequest? {
        lock.lock(); defer { lock.unlock() }
        guard !requests.isEmpty else { return nil }
        return requests.removeFirst()
    }

    fileprivate func handle(_ protocolInstance: URLProtocol) {
        lock.lock()
        requests.append(protocolInstance.request)
        requestCount += 1
        let next = responses.isEmpty ? nil : responses.removeFirst()
        lock.unlock()

        guard let next else {
            protocolInstance.client?.urlProtocol(protocolInstance, didFailWithError: URLError(.unknown))
            return
        }
        let url = protocolInstance.request.url!
        let response = HTTPURLResponse(url: url, statusCode: next.code, httpVersion: "HTTP/1.1", headerFields: next.headers)!
        protocolInstance.client?.urlProtocol(protocolInstance, didReceive: response, cacheStoragePolicy: .notAllowed)
        protocolInstance.client?.urlProtocol(protocolInstance, didLoad: Data(next.body.utf8))
        protocolInstance.client?.urlProtocolDidFinishLoading(protocolInstance)
    }
}

extension URLRequest {
    func queryValue(_ name: String) -> String? {
        guard let url, let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        return components.queryItems?.first { $0.name == name }?.value
    }

    var pathSegments: [String] {
        (url?.path ?? "").split(separator: "/").map(String.init)
    }

    /// A POST/DELETE body's text, from wherever URLSession actually put it. `httpBody` is
    /// nil for requests that have passed through a custom `URLProtocol` — Foundation
    /// converts it to `httpBodyStream` first, which `URLRequest.httpBody` never reflects
    /// back, only the original request object before interception did.
    var bodyString: String? {
        if let httpBody { return String(data: httpBody, encoding: .utf8) }
        guard let stream = httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        var data = Data()
        let bufferSize = 4096
        var buffer = [UInt8](repeating: 0, count: bufferSize)
        while stream.hasBytesAvailable {
            let read = stream.read(&buffer, maxLength: bufferSize)
            guard read > 0 else { break }
            data.append(buffer, count: read)
        }
        return String(data: data, encoding: .utf8)
    }
}
