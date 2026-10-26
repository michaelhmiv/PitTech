import SwiftUI
import UIKit
import XCTest
@testable import PitTech

/// TURN-IN Slice 0 (BUILD-SPEC §3): token contrast floors, appearance
/// resolution, and bundled-font registration.
final class TurnInThemeTests: XCTestCase {

    // MARK: Contrast (spec §3.1: body text >= 4.5:1 in both modes)

    private func luminance(_ hex: UInt) -> Double {
        func channel(_ value: UInt) -> Double {
            let c = Double(value) / 255.0
            return c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel((hex >> 16) & 255) + 0.7152 * channel((hex >> 8) & 255) + 0.0722 * channel(hex & 255)
    }

    private func contrast(_ a: UInt, _ b: UInt) -> Double {
        let (lighter, darker) = (max(luminance(a), luminance(b)), min(luminance(a), luminance(b)))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private func assertPair(_ name: String, _ foreground: UInt, _ background: UInt, floor: Double = 4.5) {
        let ratio = contrast(foreground, background)
        XCTAssertGreaterThanOrEqual(ratio, floor, "\(name) contrast \(ratio) is below the \(floor) floor")
    }

    private func assertMode(_ mode: String, ground: UInt, surface: UInt, raised: UInt, ink: UInt, muted: UInt, emberText: UInt, attentionText: UInt, cobalt: UInt, flag: UInt, marigold: UInt, onAccent: UInt) {
        for (textName, text) in [("ink", ink), ("inkMuted", muted), ("emberText", emberText), ("attentionText", attentionText)] {
            for (groundName, bg) in [("ground", ground), ("surface", surface), ("surfaceRaised", raised)] {
                assertPair("\(mode) \(textName) on \(groundName)", text, bg)
            }
        }
        assertPair("\(mode) cobalt on ground", cobalt, ground)
        assertPair("\(mode) cobalt on surface", cobalt, surface)
        assertPair("\(mode) flag on ground", flag, ground)
        assertPair("\(mode) onAccent on marigold", onAccent, marigold)
    }

    func testDarkTokensMeetContrastFloors() {
        assertMode("dark",
                   ground: PitTechPalette.ground.dark, surface: PitTechPalette.surface.dark,
                   raised: PitTechPalette.surfaceRaised.dark, ink: PitTechPalette.ink.dark,
                   muted: PitTechPalette.inkMuted.dark, emberText: PitTechPalette.emberText.dark,
                   attentionText: PitTechPalette.attentionText.dark, cobalt: PitTechPalette.cobalt.dark,
                   flag: PitTechPalette.flag.dark, marigold: PitTechPalette.marigold.dark,
                   onAccent: PitTechPalette.onAccent.dark)
        // Dark ember is a text-bearing fill with navy onAccent text (§3.1).
        assertPair("dark onAccent on ember", PitTechPalette.onAccent.dark, PitTechPalette.ember.dark)
    }

    func testLightTokensMeetContrastFloors() {
        assertMode("light",
                   ground: PitTechPalette.ground.light, surface: PitTechPalette.surface.light,
                   raised: PitTechPalette.surfaceRaised.light, ink: PitTechPalette.ink.light,
                   muted: PitTechPalette.inkMuted.light, emberText: PitTechPalette.emberText.light,
                   attentionText: PitTechPalette.attentionText.light, cobalt: PitTechPalette.cobalt.light,
                   flag: PitTechPalette.flag.light, marigold: PitTechPalette.marigold.light,
                   onAccent: PitTechPalette.onAccent.light)
    }

    func testSpecFixValuesAreBakedIn() {
        // The two build-time fixes from §3.1, verbatim.
        XCTAssertEqual(PitTechPalette.emberText.light, 0xA84A0C)
        XCTAssertEqual(PitTechPalette.attentionText.light, 0x8A6400)
        XCTAssertEqual(PitTechPalette.ground.dark, 0x10151C)
        XCTAssertEqual(PitTechPalette.marigold.light, 0xDFA408)
    }

    // MARK: Appearance resolution (spec §3.4)

    func testAppearanceResolutionDefaultsToDark() {
        XCTAssertEqual(PitTechAppearance.resolve(nil), .dark)
        XCTAssertEqual(PitTechAppearance.resolve("bogus"), .dark)
        // The #172 factory seed (lowercase) means unset, not a System choice.
        XCTAssertEqual(PitTechAppearance.resolve("system"), .dark)
    }

    func testAppearanceResolutionHonorsStoredChoices() {
        XCTAssertEqual(PitTechAppearance.resolve("dark"), .dark)
        XCTAssertEqual(PitTechAppearance.resolve("DARK"), .dark)
        XCTAssertEqual(PitTechAppearance.resolve("light"), .light)
        XCTAssertEqual(PitTechAppearance.resolve("LIGHT"), .light)
        XCTAssertNil(PitTechAppearance.resolve("SYSTEM"))
        XCTAssertEqual(PitTechAppearance.selectionTag(nil), "DARK")
        XCTAssertEqual(PitTechAppearance.selectionTag("light"), "LIGHT")
        XCTAssertEqual(PitTechAppearance.selectionTag("SYSTEM"), "SYSTEM")
    }

    // MARK: Bundled fonts (spec §3.3)

    func testBundledFontsAreRegistered() {
        // PostScript names from the shipped files (UIAppFonts in Info.plist).
        for name in [
            "NotoSansDisplayCondensed-Bold", "NotoSansDisplayCondensed-ExtraBold", "NotoSansDisplayCondensed-Black",
            "IBMPlexMono-Regular", "IBMPlexMono-Medium", "IBMPlexMono-SemiBold", "IBMPlexMono-Bold",
            "Inter-Regular", "Inter-Medium", "Inter-Bold",
        ] {
            XCTAssertNotNil(UIFont(name: name, size: 12), "Font \(name) is not registered — check UIAppFonts and the Fonts group")
        }
    }
}
