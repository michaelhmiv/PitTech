import Foundation
import ImageIO
import UniformTypeIdentifiers

enum NativeCookPackPhoto {
    static func validatePublic(_ data: Data) throws {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let type = CGImageSourceGetType(source), type as String == UTType.jpeg.identifier,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any],
              (properties[kCGImagePropertyGPSDictionary as String] as? [String: Any])?.isEmpty != false else { throw PitTechArchiveError.invalidArchive }
        _ = try derivative(data)
    }
    static func derivative(_ original: Data, maximumPixels: Int = 2048) throws -> Data {
        guard !original.isEmpty, Int64(original.count) <= NativeArchiveLimits.backup.entry,
              let source = CGImageSourceCreateWithData(original as CFData, nil),
              let type = CGImageSourceGetType(source), UTType(type as String)?.conforms(to: .image) == true,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any],
              let width = properties[kCGImagePropertyPixelWidth as String] as? Int,
              let height = properties[kCGImagePropertyPixelHeight as String] as? Int,
              (1...32768).contains(width), (1...32768).contains(height), Int64(width) * Int64(height) <= 100_000_000,
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: maximumPixels] as CFDictionary) else { throw CookNoteError.missingPhoto }
        let result = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(result, UTType.jpeg.identifier as CFString, 1, nil) else { throw CookNoteError.missingPhoto }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.9] as CFDictionary)
        guard CGImageDestinationFinalize(destination), Int64(result.length) <= NativeArchiveLimits.cookPack.entry else { throw PitTechArchiveError.tooLarge }
        return result as Data
    }
}
