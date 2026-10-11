import SwiftUI

enum PitTechStyle {
    static let ink = Color(red: 0.12, green: 0.13, blue: 0.13)
    static let canvas = Color(red: 0.96, green: 0.95, blue: 0.92)
    static let accent = Color(red: 0.70, green: 0.30, blue: 0.13)
    static let sage = Color(red: 0.31, green: 0.39, blue: 0.34)
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
