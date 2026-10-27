import SwiftUI
import UIKit

/// TURN-IN palette (design pass 2026-10-26, BUILD-SPEC §3.1): the raw
/// per-mode values behind [PitTechStyle]. Kept as plain hex pairs so unit
/// tests can assert the spec's contrast floors without a render pass. The
/// two build-time fixes are baked into the light values: `emberText` is
/// #A84A0C (not the #C75B12 fill) and `attentionText` is #8A6400 — marigold
/// is a fill, never a text color, and text on marigold/ember fills is
/// always `onAccent` navy ink, never white.
enum PitTechPalette {
    static let ground: (light: UInt, dark: UInt) = (0xFBF9F4, 0x10151C)
    static let surface: (light: UInt, dark: UInt) = (0xFFFFFF, 0x1D2733)
    static let surfaceRaised: (light: UInt, dark: UInt) = (0xF3EFE4, 0x27313F)
    static let ink: (light: UInt, dark: UInt) = (0x1D2733, 0xF5EFE3)
    static let inkMuted: (light: UInt, dark: UInt) = (0x5C6B7A, 0x9AA7B4)
    static let ruling: (light: UInt, dark: UInt) = (0xD8D2C4, 0x33404F)
    static let ember: (light: UInt, dark: UInt) = (0xC75B12, 0xF07D2E)
    static let emberText: (light: UInt, dark: UInt) = (0xA84A0C, 0xF07D2E)
    static let marigold: (light: UInt, dark: UInt) = (0xDFA408, 0xE8B41A)
    static let attentionText: (light: UInt, dark: UInt) = (0x8A6400, 0xE8B41A)
    static let cobalt: (light: UInt, dark: UInt) = (0x2B50C8, 0x6E8FE8)
    static let flag: (light: UInt, dark: UInt) = (0xC23327, 0xE5533D)
    static let onAccent: (light: UInt, dark: UInt) = (0x1D2733, 0x1D2733)
}

/// TURN-IN semantic tokens, resolved per appearance (BUILD-SPEC §3.1).
/// Supersedes the #172 Night Service fixed dark tokens: every token is now
/// a light/dark pair resolved from the active appearance (which
/// `ContentView` pins from the stored preference). The pre-TURN-IN names
/// (`canvas`, `raised`, `supporting`, `border`, `accent`, `accentFill`,
/// `sage`, `sageFill`) remain as aliases onto the tokens so existing
/// screens keep compiling; the translation slices migrate them onto the
/// semantic names. `accentFill`/`sageFill` are container tints interpolated
/// from the ember/marigold tokens — the only values not verbatim in §3.1.
enum PitTechStyle {
    static let ground = adaptive(PitTechPalette.ground)
    static let surface = adaptive(PitTechPalette.surface)
    static let surfaceRaised = adaptive(PitTechPalette.surfaceRaised)
    static let ink = adaptive(PitTechPalette.ink)
    static let inkMuted = adaptive(PitTechPalette.inkMuted)
    static let ruling = adaptive(PitTechPalette.ruling)
    static let ember = adaptive(PitTechPalette.ember)
    static let emberText = adaptive(PitTechPalette.emberText)
    static let marigold = adaptive(PitTechPalette.marigold)
    static let attentionText = adaptive(PitTechPalette.attentionText)
    static let cobalt = adaptive(PitTechPalette.cobalt)
    static let flag = adaptive(PitTechPalette.flag)
    static let onAccent = adaptive(PitTechPalette.onAccent)
    /// The primary-action fill (§3.1, the Slice 0 build-time fix): ember in
    /// dark, marigold in light, always with `onAccent` text — never white
    /// on either fill.
    static let primaryFill = adaptive((light: 0xDFA408, dark: 0xF07D2E))

    // Legacy aliases (pre-TURN-IN names) onto the tokens above.
    static let canvas = ground
    static let raised = surfaceRaised
    static let supporting = inkMuted
    static let border = ruling
    static let accent = ember
    static let accentFill = adaptive((light: 0xF9E9DA, dark: 0x3A2312))
    static let sage = attentionText
    static let sageFill = adaptive((light: 0xF7ECD0, dark: 0x38300F))

    private static func adaptive(_ pair: (light: UInt, dark: UInt)) -> Color {
        Color(uiColor: UIColor { traits in
            let hex = traits.userInterfaceStyle == .dark ? pair.dark : pair.light
            return UIColor(red: Double((hex >> 16) & 255) / 255, green: Double((hex >> 8) & 255) / 255, blue: Double(hex & 255) / 255, alpha: 1)
        })
    }
}

/// Resolves the stored `themeMode` preference to a color-scheme override
/// (BUILD-SPEC §3.4). Dark is the fresh-install default: a missing or
/// unrecognized value resolves to Dark. `nil` (follow the system) is
/// returned only for the exact picker-written value "SYSTEM" — the #172
/// factory seed wrote lowercase "system" into new libraries, and that seed
/// means *unset*, not a user's System choice. (The seed itself is now
/// "dark"; see LibraryModels.) Device-action writes decode
/// case-insensitively, like Android's preference parsing.
enum PitTechAppearance {
    static func resolve(_ stored: String?) -> ColorScheme? {
        guard let stored else { return .dark }
        if stored == "SYSTEM" { return nil }
        switch stored.uppercased() {
        case "LIGHT": return .light
        case "DARK": return .dark
        default: return .dark
        }
    }

    /// The Settings picker's tag for the currently resolved appearance.
    static func selectionTag(_ stored: String?) -> String {
        switch resolve(stored) {
        case .light: return "LIGHT"
        case .dark: return "DARK"
        default: return "SYSTEM"
        }
    }
}

/// TURN-IN type tokens (BUILD-SPEC §3.3), set in the bundled families
/// (working assumption pending Michael's pick from the Slice 0 comparison
/// sheet: banner = Noto Sans Display Condensed, mono = IBM Plex Mono,
/// text = Inter; all OFL, Latin-subset — see THIRD_PARTY_NOTICES.md).
/// `metaLabel` carries the §3.2 floor: 11 pt minimum at 1.0x text size,
/// with [metaLabelTracking] applied by the caller (tracking is a view
/// modifier, not a font property).
enum PitTechFont {
    static let scoreboard = Font.custom("NotoSansDisplayCondensed-Black", size: 52, relativeTo: .largeTitle)
    static let bannerTitle = Font.custom("NotoSansDisplayCondensed-ExtraBold", size: 21, relativeTo: .title3)
    static let bannerCard = Font.custom("NotoSansDisplayCondensed-Bold", size: 18, relativeTo: .headline)
    static let monoReadout = Font.custom("IBMPlexMono-SemiBold", size: 16, relativeTo: .body)
    static let monoMeta = Font.custom("IBMPlexMono-Regular", size: 12, relativeTo: .caption)
    static let body = Font.custom("Inter-Regular", size: 15, relativeTo: .body)
    static let bodyEmphasis = Font.custom("Inter-Medium", size: 15, relativeTo: .body)
    /// Small text for chips and dense rows (Slice 6, area-06 `c01` scope
    /// chips) — the Android kit's `bodySmall` counterpart.
    static let bodySmall = Font.custom("Inter-Regular", size: 13, relativeTo: .footnote)
    static let metaLabel = Font.custom("Inter-Medium", size: 11, relativeTo: .caption2)
    static let metaLabelTracking: CGFloat = 1.4
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
