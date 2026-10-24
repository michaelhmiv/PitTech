import SwiftUI
import UIKit

/// Night Service — the single PitTech appearance (#172, owner-approved
/// 2026-10-24; dark-only, DD-7 rejected). Near-black green-charcoal grounds,
/// ember-orange primary, amber secondary, cream ink. These tokens are fixed:
/// there is deliberately no light variant.
enum PitTechStyle {
    static let ink = fixed(0xF2EEE3)
    static let canvas = fixed(0x10140F)
    static let surface = fixed(0x1A201A)
    static let raised = fixed(0x242C24)
    static let supporting = fixed(0xA8B09E)
    static let border = fixed(0x333D31)
    static let accent = fixed(0xFF7A45)
    static let accentFill = fixed(0x4A2A18)
    static let sage = fixed(0xFFC24B)
    static let sageFill = fixed(0x3A2E12)

    private static func fixed(_ hex: UInt) -> Color {
        Color(uiColor: UIColor(red: Double((hex >> 16) & 255) / 255, green: Double((hex >> 8) & 255) / 255, blue: Double(hex & 255) / 255, alpha: 1))
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
