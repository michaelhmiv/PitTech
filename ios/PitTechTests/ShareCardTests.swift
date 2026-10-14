import XCTest
import UIKit
@testable import PitTech

@MainActor final class ShareCardTests: XCTestCase {
    func testNativePixelsCoverChartPhotoMissingDataLongUnicodeAndRelay() throws {
        let url = try XCTUnwrap(Bundle(for:Self.self).url(forResource:"share-card-v1",withExtension:"json"))
        let fixture = try JSONDecoder().decode(JSONValue.self,from:Data(contentsOf:url)).objectValue!
        let format = UIGraphicsImageRendererFormat();format.scale = 1
        let photo = UIGraphicsImageRenderer(size:CGSize(width:80,height:120),format:format).image { UIColor.brown.setFill();$0.fill(CGRect(x:0,y:0,width:80,height:120)) }.jpegData(compressionQuality:0.9)!
        let before = photo
        for mode in ["chart","photo","none","relay"] {
            var content = fixture
            if mode == "photo" { content["hero"] = .string("photo");content["cropX"] = .number(0.2);content["cropY"] = .number(0.8);content["zoom"] = .number(1.5) }
            if mode == "none" { content["hero"] = .string("none");content["milestones"] = .array([]);content["stats"] = .array([]);content["note"] = .string("") }
            if mode == "relay" { content["relay"] = .object(["code":.string("EMBER-OAK-7"),"url":.string("https://pittech.app/s/EMBER-OAK-7")]);content["title"] = .string(String(repeating:"🌶",count:150));content["note"] = .string(String(repeating:"椒🌶 ",count:600)) }
            let bytes = try NativeShareCardRenderer.render(PreparedNativeShareCard(content:.object(content),photo:mode == "photo" ? photo : nil))
            let image = try XCTUnwrap(UIImage(data:bytes)?.cgImage)
            XCTAssertEqual(image.width,1080);XCTAssertEqual(image.height,1350)
            let attachment = XCTAttachment(data:bytes,uniformTypeIdentifier:"public.png");attachment.name = "Native share card " + mode;attachment.lifetime = .keepAlways;add(attachment)
        }
        XCTAssertEqual(photo,before)
        var broken = fixture;broken["hero"] = .string("photo")
        XCTAssertThrowsError(try NativeShareCardRenderer.render(PreparedNativeShareCard(content:.object(broken),photo:Data([1]))))
    }
    func testBuilderKeepsPrivateTextAndEditedRatingSeparateFromChosenRecordedVerdict() throws {
        let snapshot = RecipeSnapshot(version:1,name:"Pork",description:"Private source description",tags:[],draft:.object(["dishes":.array([.object(["name":.string("Pork")])])]),instructions:[],observations:[],targets:[],resultNotes:"",rating:1,smokingAnchorUtcMillis:1000000,servings:"")
        func sample(_ id:String) -> JSONValue { .object(["id":.string(id),"probeId":.string(id),"probeName":.string("Same name"),"measurementType":.string("food"),"value":.number(74.125),"unit":.string("°C"),"measuredAtUtcMillis":.number(1060000),"source":.string("manual"),"samplingIntervalMillis":.number(0),"qualityStatus":.string("valid")]) }
        let story:JSONValue = .object(["version":.number(1),"attemptId":.string("actual-source"),"anchorUtcMillis":.number(1000000),"observations":.array([]),"readings":.array([sample("one"),sample("two")]),"results":.array([.object(["type":.string("overall_rating"),"numericValue":.number(4.5)])])])
        var entry = NativeSharingEntry(snapshotJson:try recipeJSON(snapshot),story:story)
        var doc = NativeSharingDocument(title:"Pepper pork",entries:[entry],card:ShareCardOptions(note:"My explicit note 🌶",includeVerdict:true,unit:"°C"))
        let quiet = try NativeShareCardContent.build(doc,photo:nil)
        XCTAssertEqual(quiet.content.objectValue?["hero"]?.stringValue,"none");XCTAssertFalse(try recipeJSON(quiet.content).contains("Private source"))
        entry.includeStory = true;doc.entries = [entry]
        let shown = try NativeShareCardContent.build(doc,photo:nil)
        XCTAssertEqual(shown.content.objectValue?["curves"]?.arrayValue?.count,2)
        XCTAssertEqual(shown.content.objectValue?["stats"]?.arrayValue?.first?.objectValue?["value"]?.stringValue,"4.5 / 5")
        doc.card?.note = String(repeating:"🌶",count:2000)
        let roundTrip = try NativeSharingDocument.decode(JSONDecoder().decode(JSONValue.self,from:JSONEncoder().encode(doc)))
        XCTAssertEqual(roundTrip,doc)
        let relay = try NativeShareCardContent.build(doc,photo:nil,relay:ShareCardRelay(code:"EMBER-OAK-7",url:"https://pittech.app/s/EMBER-OAK-7"))
        XCTAssertEqual(relay.content.objectValue?["relay"]?.objectValue?["code"]?.stringValue,"EMBER-OAK-7")
    }
}
