import CoreImage
import SwiftUI

/// Renders `text` (a pairing code) as a black-on-white QR `CGImage` using CoreImage's
/// built-in generator — no extra dependency needed here, unlike Android's scanning half
/// (ROADMAP.md: generation only on macOS, no camera scanner).
func qrCodeImage(_ text: String, pixelSize: CGFloat = 400) -> CGImage? {
    guard let data = text.data(using: .ascii),
          let filter = CIFilter(name: "CIQRCodeGenerator")
    else { return nil }
    filter.setValue(data, forKey: "inputMessage")
    filter.setValue("H", forKey: "inputCorrectionLevel")
    guard let output = filter.outputImage else { return nil }

    // CIQRCodeGenerator emits one pixel per module — scale up before rasterizing, or the
    // result is a handful of pixels stretched blurry by whatever frame SwiftUI gives it.
    let scale = pixelSize / output.extent.width
    let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
    return CIContext().createCGImage(scaled, from: scaled.extent)
}
