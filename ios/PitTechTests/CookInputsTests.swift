import XCTest
@testable import PitTech

/// Mirrors Android's CookInputsTest assertion for assertion.
final class CookInputsTests: XCTestCase {
    private func rowsOf(_ inputs: CookInputs, cookId: String = "c1", dishId: String? = "d1") -> [InsightResultInput] {
        CookInputVocabulary.rows(inputs).enumerated().map { i, row in
            InsightResultInput(id: "r\(i)", cookId: cookId, dishId: dishId, resultType: row.resultType,
                               numericValue: nil, textValue: row.textValue, recordedAtUtcMillis: Double(1_000 + i))
        }
    }

    func testRoundTripPreservesEveryField() {
        let inputs = CookInputs(wrapMaterial: .foil, rub: "Killer Hogs AP", spritz: .yes, spritzMedium: "Apple juice")
        XCTAssertEqual(CookInputVocabulary.fromResults(rowsOf(inputs), cookId: "c1", dishId: "d1"), inputs)
    }

    func testAbsenceIsNotRecordedNeverAValue() {
        XCTAssertEqual(CookInputVocabulary.fromResults([], cookId: "c1", dishId: "d1"), CookInputs.notRecorded)
        XCTAssertEqual(CookInputVocabulary.notRecordedLabel, "Not recorded")
    }

    func testDishScopedRowsWinOverCookScopedRows() {
        let rows = rowsOf(CookInputs(wrapMaterial: .foil), dishId: nil) +
            rowsOf(CookInputs(wrapMaterial: .butcherPaper), dishId: "d1")
        XCTAssertEqual(CookInputVocabulary.fromResults(rows, cookId: "c1", dishId: "d1").wrapMaterial, .butcherPaper)
    }

    func testALoggedSpritzActionMeansYesEvenWithoutAMedium() {
        let read = CookInputVocabulary.fromResults([], cookId: "c1", dishId: "d1", hasSpritzEvent: true)
        XCTAssertEqual(read.spritz, .yes)
        XCTAssertNil(read.spritzMedium)
    }

    func testAnExplicitNoOutranksTheEvent() {
        let rows = rowsOf(CookInputs(spritz: .no), dishId: "d1")
        XCTAssertEqual(CookInputVocabulary.fromResults(rows, cookId: "c1", dishId: "d1", hasSpritzEvent: true).spritz, .no)
    }

    func testWrapHintsComeFromWholeWordsOnly() {
        XCTAssertEqual(CookInputVocabulary.wrapMaterialHint(texts: ["Wrap in foil at the stall"]), .foil)
        XCTAssertEqual(CookInputVocabulary.wrapMaterialHint(texts: ["foil boat"]), .foil)
        XCTAssertEqual(CookInputVocabulary.wrapMaterialHint(texts: ["Butcher paper at 165"]), .butcherPaper)
        XCTAssertEqual(CookInputVocabulary.wrapMaterialHint(texts: ["No wrap, hot and fast"]), .none)
        XCTAssertNil(CookInputVocabulary.wrapMaterialHint(texts: ["Rest in a cooler"]))
        XCTAssertNil(CookInputVocabulary.wrapMaterialHint(texts: ["Rest tented on the counter"]))
    }

    func testRubComesFromTheRubStagedIngredient() {
        let ingredients = [NoteIngredient(name: "Salt", stage: "seasoning"), NoteIngredient(name: "Killer Hogs AP", stage: "rub")]
        XCTAssertEqual(CookInputVocabulary.rubFromIngredients(ingredients), "Killer Hogs AP")
        XCTAssertNil(CookInputVocabulary.rubFromIngredients([NoteIngredient(name: "Salt", stage: "seasoning")]))
    }
}
