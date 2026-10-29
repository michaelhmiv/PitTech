import XCTest
@testable import PitTech

final class PrivateSyncProtocolTests: XCTestCase {
    private let deviceA = "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa"
    private let deviceB = "bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb"
    private func row(_ type: String, _ id: String, _ counter: Int64, _ client: String, _ payload: [String: JSONValue]?, _ assets: [String] = [], deleted: Bool = false) -> NativeSyncRecord {
        NativeSyncRecord(recordType: type, recordId: id, version: NativeSyncStamp(counter: counter, clientId: client), deleted: deleted, payload: payload, assetIds: assets)
    }
    private func projected(_ type: String, _ id: String, _ payload: [String: JSONValue], _ assets: [String] = []) -> NativePrivateProjectedRecord {
        NativePrivateProjectedRecord(recordType: type, recordId: id, payload: payload, assetIds: assets)
    }
    private func note(_ title: String, _ id: String = "note-1") -> [String: JSONValue] { ["id": .string(id), "cookId": .string("cook-1"), "title": .string(title)] }

    func testStampingKeepsBaseVersionsAdvancesChangesAndTombstonesDeletions() throws {
        let base = [row("events", "note-1", 1, deviceA, note("Same")), row("events", "note-2", 1, deviceA, note("Gone", "note-2"))]
        let projected = [projected("events", "note-1", note("Same")), projected("events", "note-3", note("New", "note-3"))]
        let stamped = try NativePrivateSyncProtocol.stampLocal(projected, base: base, deviceId: deviceA, counter: 2)
        XCTAssertEqual(stamped.count, 3)
        XCTAssertEqual(stamped.first { $0.recordId == "note-1" }?.version.counter, 1)
        let added = try XCTUnwrap(stamped.first { $0.recordId == "note-3" })
        XCTAssertEqual(added.version.counter, 2); XCTAssertEqual(added.version.clientId, deviceA); XCTAssertFalse(added.deleted)
        let tombstone = try XCTUnwrap(stamped.first { $0.recordId == "note-2" })
        XCTAssertTrue(tombstone.deleted); XCTAssertNil(tombstone.payload); XCTAssertTrue(tombstone.assetIds.isEmpty)
        XCTAssertEqual(tombstone.version.counter, 2); XCTAssertEqual(tombstone.version.clientId, deviceA)
        // Re-stamping an unchanged projection is stable: no version churn.
        XCTAssertEqual(stamped, try NativePrivateSyncProtocol.stampLocal(projected, base: stamped, deviceId: deviceA, counter: 3))
    }

    func testPutBodyMergesConflictsAndSendsOnlyServerKnownResolutions() throws {
        let records = [row("events", "note-1", 2, deviceA, note("Mine"))]
        let loser = row("events", "note-2", 2, deviceA, note("Mine", "note-2"))
        let winner = row("events", "note-2", 2, deviceB, note("Theirs", "note-2"))
        let merged = try NativePrivateSyncMerge.merge(base: [row("events", "note-2", 1, deviceA, note("Base", "note-2"))], local: [loser], remote: [winner])
        let conflict = try XCTUnwrap(merged.conflicts.first)
        XCTAssertEqual(conflict.reason, "edit_edit")
        let body = try NativePrivateSyncProtocol.putBody(baseVersion: 4, mutationId: UUID().uuidString.lowercased(), deviceId: deviceA, records: records, retained: [conflict], fresh: [], resolved: [conflict.id, String(repeating: "0", count: 64)], serverConflictIds: [conflict.id])
        XCTAssertEqual(body.conflicts.count, 0)
        XCTAssertEqual(body.resolvedSent, [conflict.id])
        let decoded = try XCTUnwrap(JSONDecoder().decode(JSONValue.self, from: body.data).objectValue)
        XCTAssertEqual(decoded["contractVersion"], .number(1)); XCTAssertEqual(decoded["baseVersion"], .number(4))
        XCTAssertEqual(decoded["resolvedConflictIds"]?.arrayValue, [.string(conflict.id)])
        // A locally generated conflict the server never saw drops without an unknown resolution ID.
        let fresh = try NativePrivateSyncProtocol.putBody(baseVersion: 4, mutationId: UUID().uuidString.lowercased(), deviceId: deviceA, records: records, retained: [], fresh: [conflict], resolved: [conflict.id], serverConflictIds: [])
        XCTAssertEqual(fresh.conflicts.count, 0); XCTAssertTrue(fresh.resolvedSent.isEmpty)
        XCTAssertNil(try JSONDecoder().decode(JSONValue.self, from: fresh.data).objectValue?["resolvedConflictIds"])
    }

    func testParseSnapshotGetValidatesVersionAndEnvelope() throws {
        let (emptyVersion, emptyEnvelope) = try NativePrivateSyncProtocol.parseSnapshotGet(Data("{\"version\":0,\"envelope\":null}".utf8))
        XCTAssertEqual(emptyVersion, 0); XCTAssertNil(emptyEnvelope)
        let records = try JSONEncoder().encode([row("events", "note-1", 1, deviceA, note("Base"))])
        let reply = "{\"version\":7,\"envelope\":{\"contractVersion\":1,\"records\":\(String(data: records, encoding: .utf8)!),\"conflicts\":[]}}"
        let (version, envelope) = try NativePrivateSyncProtocol.parseSnapshotGet(Data(reply.utf8))
        XCTAssertEqual(version, 7); XCTAssertEqual(envelope?.records.count, 1)
        XCTAssertThrowsError(try NativePrivateSyncProtocol.parseSnapshotGet(Data("{\"version\":-1,\"envelope\":null}".utf8)))
        XCTAssertThrowsError(try NativePrivateSyncProtocol.parseSnapshotGet(Data("{\"version\":1,\"envelope\":{\"contractVersion\":2,\"records\":[],\"conflicts\":[]}}".utf8)))
    }

    func testCanonicalVectorsMatchServerSerializationByteForByte() throws {
        // Issue #262: vectors generated by the server's own stable()
        // (api-service/scripts/sync-canonical-fixtures.ts). iOS's canonical
        // encoder (the JSONEncoder configuration putBody uses) must
        // reproduce the server's bytes exactly, or the commit receipt check
        // can never pass a real commit on this platform either.
        struct Fixture: Decodable {
            struct Vector: Decodable { let name: String; let input: String; let canonical: String; let sha256: String }
            let contractVersion: Int; let cases: [Vector]
        }
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "sync-canonical-v1", withExtension: "json"))
        let fixture = try JSONDecoder().decode(Fixture.self, from: Data(contentsOf: url))
        XCTAssertEqual(fixture.contractVersion, 1); XCTAssertEqual(fixture.cases.count, 8)
        for vector in fixture.cases {
            let value = try JSONDecoder().decode(JSONValue.self, from: Data(vector.input.utf8))
            let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
            let bytes = try encoder.encode(value)
            XCTAssertEqual(String(decoding: bytes, as: UTF8.self), vector.canonical, vector.name)
            XCTAssertEqual(NativeRelayProtocol.hash(bytes), vector.sha256, vector.name)
        }
    }

    func testSharedMergeVectorsProduceUploadableEnvelopesWithValidConflictIds() throws {
        struct Vectors: Decodable { let contractVersion: Int; let cases: [Vector] }
        struct Vector: Decodable { let name: String; let base: [NativeSyncRecord]; let local: [NativeSyncRecord]; let remote: [NativeSyncRecord]; let rejected: Bool? }
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "sync-merge-v1", withExtension: "json"))
        let vectors = try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: url))
        XCTAssertEqual(vectors.contractVersion, 1); XCTAssertEqual(vectors.cases.count, 16)
        for vector in vectors.cases where vector.rejected != true {
            let merged = try NativePrivateSyncMerge.merge(base: vector.base, local: vector.local, remote: vector.remote)
            let body = try NativePrivateSyncProtocol.putBody(baseVersion: 0, mutationId: UUID().uuidString.lowercased(), deviceId: deviceA, records: merged.records, retained: [], fresh: merged.conflicts, resolved: [], serverConflictIds: [])
            XCTAssertEqual(body.conflicts.count, merged.conflicts.count, vector.name)
            for conflict in merged.conflicts {
                let view = NativePrivateSyncProtocol.conflictView(conflict, deviceId: deviceA)
                XCTAssertFalse(view.mineSummary.isEmpty || view.theirsSummary.isEmpty || view.reason.isEmpty, vector.name)
            }
        }
    }

    func testKeepMineTheirsAndBothMatchMergeSemantics() throws {
        let base = [row("events", "note-1", 1, deviceA, note("Base"))]
        let mine = row("events", "note-1", 2, deviceA, note("Mine"))
        let theirs = row("events", "note-1", 2, deviceB, note("Theirs"))
        let conflict = try XCTUnwrap(NativePrivateSyncMerge.merge(base: base, local: [mine], remote: [theirs]).conflicts.first)
        XCTAssertEqual(conflict.winner.version.clientId, deviceB)
        let keepTheirs = try NativePrivateSyncProtocol.resolve(conflict, choice: "theirs", deviceId: deviceA)
        XCTAssertEqual(keepTheirs.resolvedId, conflict.id); XCTAssertNil(keepTheirs.restoreType)
        let keepMine = try NativePrivateSyncProtocol.resolve(conflict, choice: "mine", deviceId: deviceA)
        XCTAssertEqual(keepMine.restoreId, "note-1"); XCTAssertFalse(keepMine.newIdentity)
        XCTAssertEqual(keepMine.restorePayload?["title"], .string("Mine"))
        let both = try NativePrivateSyncProtocol.resolve(conflict, choice: "both", deviceId: deviceA)
        let bothId = try XCTUnwrap(both.restoreId)
        XCTAssertTrue(both.newIdentity); XCTAssertNotEqual(bothId, "note-1")
        XCTAssertEqual(both.restorePayload?["id"], .string(bothId)); XCTAssertEqual(both.restorePayload?["title"], .string("Mine"))
        // Tombstones win concurrent edits; the losing edit restores only as a new identity.
        let deleted = row("events", "note-1", 2, deviceB, nil, deleted: true)
        let tombstone = try XCTUnwrap(NativePrivateSyncMerge.merge(base: base, local: [mine], remote: [deleted]).conflicts.first)
        XCTAssertEqual(tombstone.reason, "delete_edit")
        XCTAssertNil(try NativePrivateSyncProtocol.resolve(tombstone, choice: "theirs", deviceId: deviceA).restoreType)
        let resurrected = try NativePrivateSyncProtocol.resolve(tombstone, choice: "mine", deviceId: deviceA)
        XCTAssertTrue(resurrected.newIdentity); XCTAssertEqual(resurrected.restorePayload?["id"], .string(try XCTUnwrap(resurrected.restoreId)))
        XCTAssertEqual(resurrected.restorePayload?["title"], .string("Mine"))
        // Immutable rewrites never overwrite under the same identity.
        let reading = ["id": JSONValue.string("reading-1"), "value": JSONValue.number(74)]
        let immutable = try XCTUnwrap(NativePrivateSyncMerge.merge(base: [row("readings", "reading-1", 1, deviceA, reading)],
            local: [row("readings", "reading-1", 2, deviceA, ["id": .string("reading-1"), "value": .number(75)])],
            remote: [row("readings", "reading-1", 1, deviceA, reading)]).conflicts.first)
        XCTAssertEqual(immutable.reason, "immutable_identity")
        let kept = try NativePrivateSyncProtocol.resolve(immutable, choice: "theirs", deviceId: deviceA)
        XCTAssertTrue(kept.newIdentity); XCTAssertEqual(kept.restorePayload?["value"], .number(75))
        XCTAssertNil(try NativePrivateSyncProtocol.resolve(immutable, choice: "mine", deviceId: deviceA).restoreType)
    }

    func testConflictViewPreviewsActualContentSideBySide() throws {
        // PL-16: the review renders the conflicting content itself, not the
        // one-line summaries — the note's title, type and time are fields on
        // each side, so the two versions can be judged against each other.
        func fullNote(_ title: String) -> [String: JSONValue] {
            var payload = note(title); payload["eventType"] = .string("note"); payload["occurredAtUtcMillis"] = .number(1000); return payload
        }
        let base = [row("events", "note-1", 1, deviceA, fullNote("Base"))]
        let mine = row("events", "note-1", 2, deviceA, fullNote("Mine"))
        let theirs = row("events", "note-1", 2, deviceB, fullNote("Theirs"))
        let conflict = try XCTUnwrap(NativePrivateSyncMerge.merge(base: base, local: [mine], remote: [theirs]).conflicts.first)
        let view = NativePrivateSyncProtocol.conflictView(conflict, deviceId: deviceA)
        XCTAssertEqual(view.recordType, "events"); XCTAssertEqual(view.recordLabel, "Note"); XCTAssertTrue(view.canKeepBoth)
        func field(_ fields: [NativeConflictPreviewField], _ label: String) -> String? { fields.first { $0.label == label }?.value }
        XCTAssertEqual(field(view.mineFields, "Title"), "Mine"); XCTAssertEqual(field(view.theirsFields, "Title"), "Theirs")
        XCTAssertEqual(field(view.mineFields, "Type"), "note")
        XCTAssertEqual(field(view.mineFields, "Occurred"), "Jan 1, 1970 · 12:00 AM UTC")
        // Reference ids and bookkeeping timestamps carry no judging value.
        XCTAssertTrue(view.mineFields.allSatisfy { !["Id", "Cook Id", "Created", "Updated", "Recorded"].contains($0.label) })
        // A reading preview pairs the value with its unit as one field.
        let reading = NativePrivateSyncProtocol.previewFields(row("readings", "r-1", 1, deviceA, ["id": .string("r-1"), "value": .number(74.1), "unit": .string("°C"), "probeName": .string("Pit"), "measuredAtUtcMillis": .number(1000)]))
        XCTAssertEqual(field(reading, "Value"), "74.1 °C"); XCTAssertEqual(field(reading, "Probe"), "Pit")
        XCTAssertTrue(reading.allSatisfy { $0.label != "Unit" })
        // A tombstone side previews as deleted, with its entry named.
        let tombstone = NativePrivateSyncProtocol.previewFields(row("events", "note-9", 1, deviceA, nil, deleted: true))
        XCTAssertEqual(tombstone, [NativeConflictPreviewField(label: "Status", value: "Deleted"), NativeConflictPreviewField(label: "Entry", value: "Note note-9")])
    }

    func testKeepBothUnavailableKindsAreExactlyTheLinkAndSingletonKeys() throws {
        // PL-16, from contracts/private-sync-projection-v1.json: of the 21
        // projected kinds, only logReadingLinks (keyed by readingId) and
        // lifecycles (keyed by cookId) cannot hold two versions at once.
        XCTAssertEqual(NativePrivateSyncProtocol.keepBothUnavailableTypes(), ["logReadingLinks", "lifecycles"])
        let lifecycle = try XCTUnwrap(NativePrivateSyncMerge.merge(base: [row("lifecycles", "cook-1", 1, deviceA, ["cookId": .string("cook-1"), "timingMode": .string("legacy_preserved")])],
            local: [row("lifecycles", "cook-1", 2, deviceA, ["cookId": .string("cook-1"), "timingMode": .string("explicit_phases")])],
            remote: [row("lifecycles", "cook-1", 2, deviceB, ["cookId": .string("cook-1"), "timingMode": .string("legacy_preserved")])]).conflicts.first)
        let view = NativePrivateSyncProtocol.conflictView(lifecycle, deviceId: deviceA)
        XCTAssertFalse(view.canKeepBoth)
        XCTAssertEqual(view.mineFields.first { $0.label == "Timing mode" }?.value, "explicit_phases")
    }

    func testSummariesDescribeCompetingVersionsAndKeepBothLimits() throws {
        let base = [row("lifecycles", "cook-1", 1, deviceA, ["cookId": .string("cook-1"), "timingMode": .string("legacy_preserved")])]
        let mine = row("lifecycles", "cook-1", 2, deviceA, ["cookId": .string("cook-1"), "timingMode": .string("explicit_phases")])
        let theirs = row("lifecycles", "cook-1", 2, deviceB, ["cookId": .string("cook-1"), "timingMode": .string("legacy_preserved")])
        let conflict = try XCTUnwrap(NativePrivateSyncMerge.merge(base: base, local: [mine], remote: [theirs]).conflicts.first)
        let view = NativePrivateSyncProtocol.conflictView(conflict, deviceId: deviceA)
        XCTAssertEqual(view.reason, "Edited on two devices"); XCTAssertFalse(view.canKeepBoth)
        XCTAssertThrowsError(try NativePrivateSyncProtocol.resolve(conflict, choice: "both", deviceId: deviceA))
        XCTAssertEqual(NativePrivateSyncProtocol.summarize(row("cooks", "cook-9", 1, deviceA, ["title": .string("Weeknight cook")])), "Meal \"Weeknight cook\"")
        XCTAssertEqual(NativePrivateSyncProtocol.summarize(row("readings", "r-1", 1, deviceA, ["value": .number(74.1), "unit": .string("°C")])), "Reading 74.1 °C")
        XCTAssertTrue(NativePrivateSyncProtocol.summarize(row("events", "note-9", 1, deviceA, nil, deleted: true)).hasSuffix("deleted"))
    }
}
