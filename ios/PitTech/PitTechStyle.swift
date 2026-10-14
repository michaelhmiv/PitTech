import SwiftUI
import UIKit

enum PitTechStyle {
    static let ink = adaptive(0x242622, 0xF0EDE4)
    static let canvas = adaptive(0xF5F0E7, 0x1B1E1B)
    static let surface = adaptive(0xFFFCF6, 0x242923)
    static let raised = adaptive(0xFFFFFF, 0x2C322B)
    static let supporting = adaptive(0x514C45, 0xC9C5B9)
    static let border = adaptive(0xE2D9CD, 0x444B40)
    static let accent = adaptive(0xB04D2E, 0xD77954)
    static let accentFill = adaptive(0xF2D9CC, 0x65341F)
    static let sage = adaptive(0x58665A, 0x9BAC98)
    static let sageFill = adaptive(0xDCE3DA, 0x37443A)

    private static func adaptive(_ light: UInt, _ dark: UInt) -> Color {
        Color(uiColor: UIColor { traits in
            let hex = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: Double((hex >> 16) & 255) / 255, green: Double((hex >> 8) & 255) / 255, blue: Double(hex & 255) / 255, alpha: 1)
        })
    }
}

struct SectionTitle: View {
    let title: String
    var subtitle: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.title2.weight(.bold))
            if let subtitle {
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
