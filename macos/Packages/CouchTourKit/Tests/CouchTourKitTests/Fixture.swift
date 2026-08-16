import Foundation
import XCTest

/// Loads a copied resource file (e.g. "show.json") out of the `Fixtures/` directory that
/// `Package.swift` copies into the test bundle. Shared by every parsing test file.
func fixtureData(_ name: String, file: StaticString = #filePath, line: UInt = #line) throws -> Data {
    let ext = (name as NSString).pathExtension
    let base = (name as NSString).deletingPathExtension
    guard let url = Bundle.module.url(forResource: base, withExtension: ext, subdirectory: "Fixtures") else {
        XCTFail("missing fixture \(name)", file: file, line: line)
        return Data()
    }
    return try Data(contentsOf: url)
}
