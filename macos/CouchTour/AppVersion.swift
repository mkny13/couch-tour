import Foundation

extension Bundle {
    var appDisplayName: String {
        object(forInfoDictionaryKey: "CFBundleDisplayName") as? String
            ?? object(forInfoDictionaryKey: "CFBundleName") as? String
            ?? "Couch Tour"
    }

    var appMarketingVersion: String {
        object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown"
    }

    var appVersionString: String {
        "\(appDisplayName) \(appMarketingVersion)"
    }
}
