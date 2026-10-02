import XCTest

/// Asserts a cache-backed `load` treats a failed fetch as an error, not as "empty" (#445, the
/// class of bug behind #346/#352): the server answers 500 first, then 200 with `successBody`.
///
/// 1. The first call must throw. Returning an empty/default value would hide the failure.
/// 2. The second call must hit the network again and return the data, which proves the
///    failure wasn't written to the cache.
///
/// Returns the second call's result so the caller can assert on the data itself.
/// Port of `CacheErrorAssertions.kt`.
@discardableResult
func assertErrorNotCachedAsEmpty<T>(
    server: MockServer,
    successBody: String,
    file: StaticString = #filePath,
    line: UInt = #line,
    load: () async throws -> T
) async -> T? {
    let before = server.requestCount
    server.enqueue("", code: 500)
    server.enqueue(successBody)

    do {
        let result = try await load()
        XCTFail("a failed load must surface an error, but returned \(result)", file: file, line: line)
        return nil
    } catch {
        // expected: the 500 propagated
    }
    XCTAssertEqual(before + 1, server.requestCount, "the failing call should have made one request", file: file, line: line)

    do {
        let second = try await load()
        XCTAssertEqual(before + 2, server.requestCount, "the retry must re-fetch, not replay a cached failure", file: file, line: line)
        return second
    } catch {
        XCTFail("the retry must return the data, but threw \(error)", file: file, line: line)
        return nil
    }
}
