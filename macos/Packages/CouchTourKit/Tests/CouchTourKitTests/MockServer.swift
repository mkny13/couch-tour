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

    private var responses: [(code: Int, body: String)] = []
    private(set) var requests: [URLRequest] = []
    private let lock = NSLock()

    func start() {
        MockServer.active = self
        URLProtocol.registerClass(Interceptor.self)
    }

    func shutdown() {
        URLProtocol.unregisterClass(Interceptor.self)
        MockServer.active = nil
    }

    func enqueue(_ body: String, code: Int = 200) {
        lock.lock(); defer { lock.unlock() }
        responses.append((code, body))
    }

    func takeRequest() -> URLRequest? {
        lock.lock(); defer { lock.unlock() }
        guard !requests.isEmpty else { return nil }
        return requests.removeFirst()
    }

    fileprivate func handle(_ protocolInstance: URLProtocol) {
        lock.lock()
        requests.append(protocolInstance.request)
        let next = responses.isEmpty ? nil : responses.removeFirst()
        lock.unlock()

        guard let next else {
            protocolInstance.client?.urlProtocol(protocolInstance, didFailWithError: URLError(.unknown))
            return
        }
        let url = protocolInstance.request.url!
        let response = HTTPURLResponse(url: url, statusCode: next.code, httpVersion: "HTTP/1.1", headerFields: nil)!
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
}
