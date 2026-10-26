import SwiftUI

/// TURN-IN shared components (BUILD-SPEC §4), built in Slice 1 — the first
/// slice that uses them — and reused by every later slice. Each component
/// transcribes its canonical Area-01 frame: the scoreboard numeral block
/// and milestone rail from `d12-live-board`, the action placards from
/// `d12`/`d16`, the boxed score cells from `d31-finish-results`, the lane
/// row from `d01-meals-home`, and the segmented control from
/// `d12`/`d17`/`d19`. All colors and type come from the Slice 0 tokens
/// (`PitTechStyle`, `PitTechFont`) so both modes stay correct.

/// Tracked-caps section label — the §3.2 floor lives in the token.
struct TurnInMetaLabel: View {
    let text: String
    var color: Color = PitTechStyle.inkMuted

    init(_ text: String, color: Color = PitTechStyle.inkMuted) {
        self.text = text
        self.color = color
    }

    var body: some View {
        // Literal text, tracked type: the frames' caps are a CSS
        // transform over literal text, and data-derived labels (dish
        // names, group headings) are never rewritten.
        Text(text)
            .font(PitTechFont.metaLabel)
            .tracking(PitTechFont.metaLabelTracking)
            .foregroundStyle(color)
    }
}

/// A surface name set in the banner face (cook names, card titles). The
/// app's copy stays literal — the condensed face carries the look (§1.1).
struct TurnInBannerText: View {
    let text: String
    var font: Font = PitTechFont.bannerCard
    var color: Color = PitTechStyle.ink

    init(_ text: String, font: Font = PitTechFont.bannerCard, color: Color = PitTechStyle.ink) {
        self.text = text
        self.font = font
        self.color = color
    }

    var body: some View {
        Text(text).font(font).foregroundStyle(color)
    }
}

/// The laminated TURN-IN card: token surface, one ruling border, 16pt.
struct TurnInCard<Content: View>: View {
    private let content: Content
    init(@ViewBuilder content: () -> Content) { self.content = content() }

    var body: some View {
        content
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(PitTechStyle.ruling, lineWidth: 1))
    }
}

/// Filled trend chip on the scoreboard (HOLD / CLIMB / DROP): accent fill,
/// on-accent mono text, never white on marigold (§3.1).
struct TurnInTrendChip: View {
    let text: String
    let fill: Color

    var body: some View {
        Text(text)
            .font(PitTechFont.monoMeta)
            .foregroundStyle(PitTechStyle.onAccent)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(fill, in: RoundedRectangle(cornerRadius: 8))
    }
}

enum TurnInRailState {
    case done, current, upcoming
}

struct TurnInRailStep: Identifiable {
    let id = UUID()
    let label: String
    let sublabel: String?
    let state: TurnInRailState
}

/// Milestone rail (§4): done segments in ember, the next segment loudest
/// in marigold, the rest quiet on the ruling.
struct TurnInMilestoneRail: View {
    let steps: [TurnInRailStep]

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            ForEach(Array(steps.enumerated()), id: \.offset) { index, step in
                if index > 0 {
                    Rectangle()
                        .fill(steps[index - 1].state == .done ? PitTechStyle.ember : PitTechStyle.ruling)
                        .frame(height: 2)
                        .padding(.top, 5)
                }
                VStack(spacing: 6) {
                    Circle()
                        .fill(step.state == .done ? PitTechStyle.ember : step.state == .current ? PitTechStyle.marigold : PitTechStyle.ruling)
                        .frame(width: 12, height: 12)
                    Text(step.label)
                        .font(PitTechFont.metaLabel)
                        .tracking(PitTechFont.metaLabelTracking)
                        .foregroundStyle(step.state == .current ? PitTechStyle.attentionText : step.state == .done ? PitTechStyle.ink : PitTechStyle.inkMuted)
                        .multilineTextAlignment(.center)
                    if let sublabel = step.sublabel, !sublabel.isEmpty {
                        Text(sublabel)
                            .font(PitTechFont.monoMeta)
                            .foregroundStyle(PitTechStyle.inkMuted)
                            .multilineTextAlignment(.center)
                    }
                }
                .frame(width: 64)
            }
        }
        .frame(maxWidth: .infinity)
    }
}

/// Action placard (§4): a large tappable action card (icon + label). The
/// primary placard takes the mode's primary fill (`primaryFill`).
struct TurnInPlacard: View {
    let systemImage: String
    let label: String
    var primary: Bool = false
    var identifier: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: systemImage)
                    .font(.system(size: 20))
                    .foregroundStyle(primary ? PitTechStyle.onAccent : PitTechStyle.emberText)
                Text(label)
                    .font(PitTechFont.bodyEmphasis)
                    .foregroundStyle(primary ? PitTechStyle.onAccent : PitTechStyle.ink)
            }
            .frame(maxWidth: .infinity, minHeight: 64)
            .padding(.vertical, 6)
            .background(primary ? PitTechStyle.primaryFill : PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(primary ? Color.clear : PitTechStyle.ruling, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(identifier ?? label)
    }
}

/// Boxed score cells (§4, `d31`): five tappable 1–5 boxes; the selected
/// box is marigold fill with an on-accent banner numeral. Tapping the
/// selected box clears back to unrecorded — the same contract the verdict
/// chips carried, identifiers included (`\(tagPrefix)-\(n)`).
struct TurnInScoreCells: View {
    let value: Int?
    let tagPrefix: String
    let onSelect: (Int?) -> Void

    var body: some View {
        HStack(spacing: 8) {
            ForEach(1...5, id: \.self) { n in
                let selected = value == n
                Button { onSelect(selected ? nil : n) } label: {
                    Text("\(n)")
                        .font(PitTechFont.bannerCard)
                        .foregroundStyle(selected ? PitTechStyle.onAccent : PitTechStyle.ink)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(selected ? PitTechStyle.marigold : PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 10))
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(selected ? PitTechStyle.marigold : PitTechStyle.ruling, lineWidth: 1))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("\(tagPrefix)-\(n)")
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
    }
}

/// Verdict stars for lane rows (`d01`): filled stars in marigold, the
/// remainder quiet on the ruling.
struct TurnInStars: View {
    let rating: Double

    var body: some View {
        let filled = min(5, max(0, Int(rating)))
        HStack(spacing: 1) {
            Text(String(repeating: "★", count: filled)).foregroundStyle(PitTechStyle.marigold)
            if filled < 5 {
                Text(String(repeating: "★", count: 5 - filled)).foregroundStyle(PitTechStyle.ruling)
            }
        }
        .font(PitTechFont.body)
        .accessibilityLabel("Rated \(Int(rating)) of 5")
    }
}

/// Sheet chrome (§4): a small mono context kicker above a sheet's title —
/// the content name stays the title; the kicker names where you are.
/// Added in Slice 2, the first slice whose iOS sheets carry one (the
/// results editor and the reference surfaces); Android's kit has carried
/// it since Slice 1.
struct TurnInSheetKicker: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(PitTechFont.monoMeta)
            .foregroundStyle(PitTechStyle.inkMuted)
    }
}

/// A state strip (paused / stale / mismatch): raised surface with an
/// accent edge and a plain factual line (§4). Added in Slice 2 for the
/// reference surfaces' mismatch states; Android's kit has carried it
/// since Slice 1.
struct TurnInStatusStrip: View {
    let text: String
    let accent: Color

    var body: some View {
        HStack(spacing: 0) {
            Rectangle().fill(accent).frame(width: 4)
            Text(text)
                .font(PitTechFont.body)
                .foregroundStyle(PitTechStyle.ink)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
        }
        .background(PitTechStyle.surfaceRaised, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(PitTechStyle.ruling, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

/// A single raised fact cell: mono value under a meta label. Added in
/// Slice 3 for the recipe detail scoreboard (pit temperature, serves,
/// target) — the same cell Android carries as `TurnInScoreCell`.
struct TurnInScoreCell: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            TurnInMetaLabel(label)
            Text(value)
                .font(PitTechFont.monoReadout)
                .foregroundStyle(PitTechStyle.ink)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(PitTechStyle.surfaceRaised, in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(PitTechStyle.ruling, lineWidth: 1))
    }
}

/// The marigold PRO tag (§3.1): a fill, so its text is navy on-accent,
/// never white. Added in Slice 2 for the Pro entry rows and hub; Slice 7
/// reconciles the remaining Pro surfaces to it.
struct TurnInProTag: View {
    var body: some View {
        Text("PRO")
            .font(PitTechFont.monoMeta)
            .foregroundStyle(PitTechStyle.onAccent)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(PitTechStyle.marigold, in: RoundedRectangle(cornerRadius: 6))
    }
}
