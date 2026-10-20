import XCTest
@testable import PitTech

final class PrivateSyncMergeTests: XCTestCase {
    private struct Vectors: Decodable { let contractVersion: Int; let cases: [Vector] }
    private struct Vector: Decodable { let name: String; let base: [NativeSyncRecord]; let local: [NativeSyncRecord]; let remote: [NativeSyncRecord]; let expected: NativeSyncMergeResult?; let rejected: Bool? }
    func testSharedMergeVectorsPreserveEditsTombstonesAndImmutableRecipeBranches() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "sync-merge-v1", withExtension: "json"))
        let vectors = try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: url)); XCTAssertEqual(vectors.contractVersion, 1); XCTAssertEqual(vectors.cases.count, 16)
        for v in vectors.cases {
            if v.rejected == true { XCTAssertThrowsError(try NativePrivateSyncMerge.merge(base: v.base, local: v.local, remote: v.remote), v.name) }
            else {
                let forward = try NativePrivateSyncMerge.merge(base: v.base, local: v.local, remote: v.remote)
                try NativePrivateSyncMerge.validateConflicts(forward.conflicts)
                XCTAssertEqual(forward, v.expected, v.name)
                XCTAssertEqual(forward, try NativePrivateSyncMerge.merge(base: v.base, local: v.remote, remote: v.local), v.name)
            }
        }
    }
}
