import Foundation
import Observation
import UIKit

// MARK: - Slice 18 customization: curated Pro looks, previewed, entitlement-resolved

/// Curated customization inventory (mirrors `contracts/fixtures/pro-personal-v1.json`
/// `customization`): share-card themes, accessible chart palettes, restrained
/// public-profile presentation options, and app-icon variants. Selections
/// persist in the existing preference store and never modify measured data.
/// Unsupported or expired entitlement resolves to the readable free fallback
/// (stored Pro selections are kept, just not applied) and prior exports are
/// never destroyed. Anonymous content never carries Pro identity: the public
/// projection helpers below return the unstyled classic treatment for
/// anonymous items. Android `domain/ProCustomization.kt` pins the same
/// inventory, contrast math, and resolution rules.
enum ProCustomization {
    // MARK: - Inventory (ids and colors pinned by pro-personal-v1.json)

    struct CardTheme: Equatable, Sendable {
        let id: String
        let name: String
        let isPro: Bool
        let background: String
        let ink: String
        let supporting: String
        let accent: String
    }

    static let cardThemes: [CardTheme] = [
        CardTheme(id: "classic", name: "Classic", isPro: false, background: "#FBF9F4", ink: "#1D2733", supporting: "#5C6B7A", accent: "#A84A0C"),
        CardTheme(id: "ember", name: "Ember", isPro: true, background: "#FBF3E8", ink: "#2B2118", supporting: "#6B5B4C", accent: "#B04D2E"),
        CardTheme(id: "sage-night", name: "Sage night", isPro: true, background: "#23261F", ink: "#F2EFE6", supporting: "#B9B3A4", accent: "#9DB29A"),
        CardTheme(id: "parchment", name: "Parchment", isPro: true, background: "#FDFBF5", ink: "#33302A", supporting: "#6E675C", accent: "#416C8A"),
    ]

    /// Share-card renderer theme ids: the free default plus the curated Pro set.
    static let shareCardThemeIds = Set(cardThemes.map(\.id))

    struct ChartPalette: Equatable, Sendable {
        let id: String
        let name: String
        let isPro: Bool
        let intendedBackground: String
        let colors: [String]
    }

    static let chartPalettes: [ChartPalette] = [
        ChartPalette(id: "classic", name: "Classic", isPro: false, intendedBackground: "#FBF9F4", colors: ["#B04D2E", "#58665A", "#416C8A", "#8B5D84", "#A17A2C", "#357D78"]),
        ChartPalette(id: "color-vision-safe", name: "Color-vision safe", isPro: true, intendedBackground: "#FBF9F4", colors: ["#0072B2", "#D55E00", "#00855F", "#A34D7E", "#2A7F9E", "#444444"]),
        ChartPalette(id: "high-contrast", name: "High contrast", isPro: true, intendedBackground: "#FBF9F4", colors: ["#000000", "#005A9C", "#8B0000", "#006400", "#4B0082", "#8B4513"]),
        ChartPalette(id: "sage-night-chart", name: "Sage night chart", isPro: true, intendedBackground: "#23261F", colors: ["#F0C987", "#A8D5BA", "#A8C8E8", "#D8B8E0", "#E8E18A", "#9AD5D0"]),
    ]

    struct ProfileAccent: Equatable, Sendable {
        let id: String
        let name: String
        let color: String
    }

    static let profileAccents: [ProfileAccent] = [
        ProfileAccent(id: "ember", name: "Ember accent", color: "#B04D2E"),
        ProfileAccent(id: "sage", name: "Sage accent", color: "#58665A"),
        ProfileAccent(id: "slate", name: "Slate accent", color: "#416C8A"),
    ]

    static let profileLayouts = ["standard", "compact"]

    struct IconVariant: Equatable, Sendable {
        let id: String
        let name: String
        let isPro: Bool
        /// The platform icon name: nil resets to the primary icon.
        var alternateName: String? {
            switch id {
            case "ember": return "ProEmber"
            case "midnight": return "ProMidnight"
            default: return nil
            }
        }
    }

    static let iconVariants: [IconVariant] = [
        IconVariant(id: "classic", name: "Classic", isPro: false),
        IconVariant(id: "ember", name: "Ember", isPro: true),
        IconVariant(id: "midnight", name: "Midnight", isPro: true),
    ]

    // MARK: - WCAG contrast (verified by test against the fixture hex)

    /// Relative luminance per WCAG 2.1. Returns nil for malformed hex.
    static func luminance(_ hex: String) -> Double? {
        let clean = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        guard clean.count == 6, let rgb = Int(clean, radix: 16) else { return nil }
        func linear(_ channel: Double) -> Double {
            channel <= 0.04045 ? channel / 12.92 : pow((channel + 0.055) / 1.055, 2.4)
        }
        let red = linear(Double((rgb >> 16) & 0xFF) / 255)
        let green = linear(Double((rgb >> 8) & 0xFF) / 255)
        let blue = linear(Double(rgb & 0xFF) / 255)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }

    /// Contrast ratio, always >= 1. Chart colors need >= 3.0 against their
    /// intended background; text colors (ink, supporting) need >= 4.5.
    static func contrastRatio(_ first: String, _ second: String) -> Double? {
        guard let a = luminance(first), let b = luminance(second) else { return nil }
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    // MARK: - Entitlement resolution (free fallback, selections retained)

    static func theme(id: String?) -> CardTheme {
        cardThemes.first { $0.id == id } ?? cardThemes[0]
    }

    static func effectiveTheme(storedId: String?, entitled: Bool) -> CardTheme {
        let theme = theme(id: storedId)
        return theme.isPro && !entitled ? cardThemes[0] : theme
    }

    static func palette(id: String?) -> ChartPalette {
        chartPalettes.first { $0.id == id } ?? chartPalettes[0]
    }

    static func effectivePalette(storedId: String?, entitled: Bool) -> ChartPalette {
        let palette = palette(id: storedId)
        return palette.isPro && !entitled ? chartPalettes[0] : palette
    }

    static func icon(id: String?) -> IconVariant {
        iconVariants.first { $0.id == id } ?? iconVariants[0]
    }

    static func effectiveIcon(storedId: String?, entitled: Bool) -> IconVariant {
        let variant = icon(id: storedId)
        return variant.isPro && !entitled ? iconVariants[0] : variant
    }

    enum PublicIdentity: Sendable {
        case named
        case anonymous
    }

    /// Restrained profile presentation. Anonymous items always render the
    /// unstyled classic treatment: no accent, no Pro link, regardless of the
    /// stored selection or entitlement. Named items apply the stored style for
    /// entitled accounts and classic otherwise.
    static func effectiveProfileAccent(identity: PublicIdentity, storedId: String?, entitled: Bool) -> ProfileAccent? {
        guard identity == .named, entitled else { return nil }
        return profileAccents.first { $0.id == storedId }
    }

    static func effectiveProfileLayout(identity: PublicIdentity, stored: String?, entitled: Bool) -> String {
        guard identity == .named, entitled, stored == "compact" else { return "standard" }
        return "compact"
    }
}

// MARK: - Preference store (existing CookStore preference storage)

/// Backing for customization selections. Production wraps the existing
/// CookStore library preferences (`setPreference`, the same store as theme
/// mode and units); tests use the dictionary backing.
/// Main-actor confined like its owners; no cross-actor sharing.
@MainActor protocol ProCustomizationBacking: AnyObject {
    func value(_ key: String) -> String?
    func set(_ value: String, _ key: String)
}

@MainActor final class CookStoreCustomizationBacking: ProCustomizationBacking {
    private let store: CookStore

    init(store: CookStore) {
        self.store = store
    }

    func value(_ key: String) -> String? {
        store.library.preferences?[key]?.stringValue
    }

    func set(_ value: String, _ key: String) {
        store.setPreference(key, value: value)
    }
}

@MainActor final class DictionaryCustomizationBacking: ProCustomizationBacking {
    private var values: [String: String] = [:]

    func value(_ key: String) -> String? { values[key] }

    func set(_ value: String, _ key: String) { values[key] = value }
}

/// Preview-then-apply customization state. `preview*` ids drive the preview
/// tiles; applying copies one preview id into its stored selection. Nothing
/// here touches measured data — only preference keys.
@MainActor @Observable final class ProCustomizationStore {
    static let themeKey = "proCardTheme"
    static let paletteKey = "proChartPalette"
    static let profileAccentKey = "proProfileAccent"
    static let profileLayoutKey = "proProfileLayout"
    static let iconKey = "proAppIcon"

    private let backing: any ProCustomizationBacking
    private(set) var iconNotice: String?

    /// Preview ids, one per inventory. Nil previews the stored selection.
    var previewThemeId: String?
    var previewPaletteId: String?
    var previewProfileAccentId: String?
    var previewProfileLayout: String?
    var previewIconId: String?

    init(backing: any ProCustomizationBacking) {
        self.backing = backing
    }

    static func forApp(store: CookStore) -> ProCustomizationStore {
        ProCustomizationStore(backing: CookStoreCustomizationBacking(store: store))
    }

    var themeId: String {
        get { backing.value(Self.themeKey) ?? "classic" }
        set { backing.set(newValue, Self.themeKey) }
    }

    var paletteId: String {
        get { backing.value(Self.paletteKey) ?? "classic" }
        set { backing.set(newValue, Self.paletteKey) }
    }

    var profileAccentId: String? {
        get { backing.value(Self.profileAccentKey) }
        set {
            if let newValue { backing.set(newValue, Self.profileAccentKey) }
        }
    }

    var profileLayout: String {
        get { backing.value(Self.profileLayoutKey) ?? "standard" }
        set { backing.set(newValue, Self.profileLayoutKey) }
    }

    var iconId: String {
        get { backing.value(Self.iconKey) ?? "classic" }
        set { backing.set(newValue, Self.iconKey) }
    }

    func applyTheme(_ id: String) {
        themeId = id
        previewThemeId = nil
    }

    func applyPalette(_ id: String) {
        paletteId = id
        previewPaletteId = nil
    }

    func applyProfile(accentId: String?, layout: String) {
        if let accentId { backing.set(accentId, Self.profileAccentKey) }
        profileLayout = layout
        previewProfileAccentId = nil
        previewProfileLayout = nil
    }

    func clearProfileAccent() {
        // The classic treatment is the absence of a stored accent; clearing
        // restores it without touching any other selection.
        backing.set("none", Self.profileAccentKey)
        previewProfileAccentId = nil
    }

    /// Applies an icon variant through the platform alternate-icon mechanism.
    /// Unknown or art-less names degrade gracefully to the primary icon with
    /// an honest notice; the stored selection is retained.
    func applyIcon(_ id: String) {
        iconId = id
        previewIconId = nil
        iconNotice = nil
        let variant = ProCustomization.icon(id: id)
        guard UIApplication.shared.supportsAlternateIcons else {
            iconNotice = "Alternate icons aren't supported on this device. The Classic icon stays."
            return
        }
        UIApplication.shared.setAlternateIconName(variant.alternateName) { [weak self] error in
            Task { @MainActor [weak self] in
                if error != nil {
                    self?.iconNotice = "The \(variant.name) icon isn't available in this build yet. The Classic icon stays."
                }
            }
        }
    }
}
