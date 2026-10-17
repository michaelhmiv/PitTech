import SwiftUI
import UIKit

private struct CardText: Identifiable {
    let id: Int; let value: String; let rect: CGRect; let size: CGFloat; let lines: Int; let color: Color; let bold: Bool
}
private struct CardLayout {
    var text: [CardText] = []
    var hero: CGRect?
    var divider: CGFloat = 1238
}
@MainActor enum NativeShareCardRenderer {
    static let width = 1080
    static let height = 1350
    static let ink = Color(red: 36/255, green: 38/255, blue: 34/255)
    static let supporting = Color(red: 81/255, green: 76/255, blue: 69/255)
    static let sage = Color(red: 88/255, green: 102/255, blue: 90/255)
    static let palette: [Color] = [Color(red:176/255,green:77/255,blue:46/255),sage,Color(red:65/255,green:108/255,blue:138/255),Color(red:139/255,green:93/255,blue:132/255),Color(red:161/255,green:122/255,blue:44/255),Color(red:53/255,green:125/255,blue:120/255)]
    static func render(_ input: PreparedNativeShareCard) throws -> Data {
        guard let root = input.content.objectValue, root["version"]?.numberValue == 1 else { throw PitTechArchiveError.invalidArchive }
        let layout = try layout(root), photo = try cover(input, rect: layout.hero)
        let view = ShareCardPoster(root: root, layout: layout, photo: photo).frame(width: CGFloat(width), height: CGFloat(height)).environment(\.dynamicTypeSize, .medium).environment(\.colorScheme,.light)
        let renderer = ImageRenderer(content: view); renderer.scale = 1; renderer.isOpaque = true
        guard let bytes = renderer.uiImage?.pngData() else { throw CookNoteError.missingPhoto }
        return bytes
    }
    private static func cover(_ input: PreparedNativeShareCard, rect: CGRect?) throws -> UIImage? {
        guard input.content.objectValue?["hero"]?.stringValue == "photo" else { return nil }
        guard let data = input.photo, let image = UIImage(data: data)?.cgImage, let rect else { throw CookNoteError.missingPhoto }
        let root = input.content.objectValue ?? [:], zoom = root["zoom"]?.numberValue ?? 1
        let scale = max(rect.width / CGFloat(image.width), rect.height / CGFloat(image.height)) * zoom
        let width = rect.width / scale, height = rect.height / scale
        let cropWidth = max(1,min(image.width,Int(width.rounded()))), cropHeight = max(1,min(image.height,Int(height.rounded())))
        let x = (Double(image.width-cropWidth) * (root["cropX"]?.numberValue ?? 0.5)).rounded(), y = (Double(image.height-cropHeight) * (root["cropY"]?.numberValue ?? 0.5)).rounded()
        let crop = CGRect(x:x,y:y,width:Double(cropWidth),height:Double(cropHeight))
        guard let cropped = image.cropping(to: crop) else { throw CookNoteError.missingPhoto }
        return UIImage(cgImage: cropped)
    }
    private static func layout(_ root: [String: JSONValue]) throws -> CardLayout {
        var result = CardLayout()
        let left = 72.0, width = 936.0
        @discardableResult func text(_ value: String, _ x: Double, _ y: Double, _ w: Double, _ size: Double, _ lines: Int = 1, _ color: Color = Color(red:36/255,green:38/255,blue:34/255), _ bold: Bool = false) -> Double {
            let display = shareCardLimited(value,600) + (value.unicodeScalars.count > 600 ? "…" : "")
            let font = UIFont.systemFont(ofSize: size, weight: bold ? .semibold : .regular)
            let measured = (display as NSString).boundingRect(with: CGSize(width: w, height: .greatestFiniteMagnitude), options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: [.font: font], context: nil)
            let height = min(ceil(measured.height) + 4, ceil(font.lineHeight * Double(lines)) + 4)
            result.text.append(CardText(id: result.text.count, value: display, rect: CGRect(x:x,y:y,width:w,height:height), size:size, lines:lines, color:color, bold:bold)); return height
        }
        text("PITTECH COOKBOOK",left,60,width,22,1,sage,true)
        var y = 108.0
        y += text(root["title"]?.stringValue ?? "",left,y,width,56,3,ink,true) + 18
        let dishes = (root["dishes"]?.arrayValue ?? []).compactMap(\.stringValue)
        guard (1...20).contains(dishes.count) else { throw PitTechArchiveError.invalidArchive }
        y += text(dishes.prefix(3).joined(separator:" · ") + (dishes.count > 3 ? " · +\(dishes.count-3) more" : ""),left,y,width,24,2,sage) + 24
        let milestones = (root["milestones"]?.arrayValue ?? []).compactMap(\.objectValue), stats = (root["stats"]?.arrayValue ?? []).compactMap(\.objectValue)
        let note = root["note"]?.stringValue ?? "", relay = root["relay"]?.objectValue
        guard milestones.count <= 5, stats.count <= 3 else { throw PitTechArchiveError.invalidArchive }
        let footer = relay == nil ? 1258.0 : 1160.0
        let remaining = (milestones.isEmpty ? 0 : 34 + 44 * Double(milestones.count) + 20) + (stats.isEmpty ? 0 : 100) + (note.isEmpty ? 0 : 88)
        if root["hero"]?.stringValue != "none" {
            let height = max(180, min(390,footer - 32 - y - remaining))
            result.hero = CGRect(x:left,y:y,width:width,height:height); y += height + 24
            if root["hero"]?.stringValue == "chart" {
                let points = (root["curves"]?.arrayValue ?? []).flatMap { $0.objectValue?["segments"]?.arrayValue ?? [] }.flatMap { $0.arrayValue ?? [] }.compactMap(\.objectValue)
                guard !points.isEmpty, points.allSatisfy({ $0["x"]?.numberValue?.isFinite == true && $0["y"]?.numberValue?.isFinite == true }) else { throw PitTechArchiveError.invalidArchive }
                let values = points.compactMap { $0["y"]?.numberValue }
                guard let low = values.min(), let high = values.max(), (high-low).isFinite else { throw PitTechArchiveError.invalidArchive }
            }
        }
        if !milestones.isEmpty {
            text("RECORDED MILESTONES",left,y,width,20,1,sage,true);y += 34
            for item in milestones {
                text(NativeShareCardContent.offset(item["offsetMillis"]?.numberValue),left,y,160,25,1,supporting)
                text("\(item["dish"]?.stringValue ?? "") · \(item["title"]?.stringValue ?? "")",left+170,y,width-170,27);y += 44
            };y += 20
        }
        if !stats.isEmpty {
            let cell = width / Double(stats.count)
            for (i,item) in stats.enumerated() {
                text(item["label"]?.stringValue ?? "",left+Double(i)*cell,y,cell-16,20,1,supporting)
                text(item["value"]?.stringValue ?? "",left+Double(i)*cell,y+32,cell-16,31,1,ink,true)
            };y += 100
        }
        if !note.isEmpty { text("“\(note)”",left,y,width,28,2) }
        result.divider = footer-20
        if let relay {
            text("GET THE COOK PACK · \(relay["code"]?.stringValue ?? "")",left,footer,width,28,1,ink,true)
            text((relay["url"]?.stringValue ?? "").replacingOccurrences(of:"https://",with:""),left,footer+42,width,24,1,supporting)
        }
        text("PitTech",left,1285,300,32,1,ink,true);text("Cook. Keep. Share.",left+450,1292,width-450,22,1,supporting)
        return result
    }
}
private struct ShareCardPoster: View {
    let root: [String: JSONValue]; let layout: CardLayout; let photo: UIImage?
    var body: some View {
        ZStack(alignment:.topLeading) {
            Color(red:245/255,green:240/255,blue:231/255)
            if let rect = layout.hero {
                if let photo { Image(uiImage:photo).resizable().scaledToFill().frame(width:rect.width,height:rect.height).clipped().clipShape(RoundedRectangle(cornerRadius:24)).position(x:rect.midX,y:rect.midY) }
                else { ShareCardChart(root:root).frame(width:rect.width,height:rect.height).position(x:rect.midX,y:rect.midY) }
            }
            ForEach(layout.text) { item in
                Text(item.value).font(.system(size:item.size,weight:item.bold ? .semibold : .regular)).foregroundStyle(item.color).lineLimit(item.lines).multilineTextAlignment(.leading)
                    .frame(width:item.rect.width,height:item.rect.height,alignment:.topLeading).clipped().position(x:item.rect.midX,y:item.rect.midY)
            }
            Rectangle().fill(Color(red:226/255,green:217/255,blue:205/255)).frame(width:936,height:1).position(x:540,y:layout.divider)
        }
    }
}
private struct ShareCardChart: View {
    let root: [String: JSONValue]
    var body: some View {
        Canvas { context,size in
            let curves = (root["curves"]?.arrayValue ?? []).compactMap(\.objectValue)
            let all = curves.flatMap { $0["segments"]?.arrayValue ?? [] }.flatMap { $0.arrayValue ?? [] }.compactMap(\.objectValue)
            let xs = all.compactMap { $0["x"]?.numberValue }, ys = all.compactMap { $0["y"]?.numberValue }
            guard let x0 = xs.min(), let last = xs.max(), let low = ys.min(), let high = ys.max() else { return }
            let x1 = max(x0+1,last), pad = max(5,max(high-low,abs(low)*0.01)*0.1), y0 = low-pad, y1 = high+pad
            guard (y1-y0).isFinite, y1 > y0 else { return }
            let p = CGRect(x:68,y:18,width:size.width-88,height:size.height-124)
            func point(_ value: [String:JSONValue]) -> CGPoint { CGPoint(x:p.minX+((value["x"]?.numberValue ?? 0)-x0)/(x1-x0)*p.width,y:p.maxY-((value["y"]?.numberValue ?? 0)-y0)/(y1-y0)*p.height) }
            func text(_ value:String,_ x:Double,_ y:Double,_ size:Double,_ color:Color = NativeShareCardRenderer.supporting) { context.draw(Text(value).font(.system(size:size)).foregroundColor(color),at:CGPoint(x:x,y:y),anchor:.topLeading) }
            for i in 0..<3 {
                let v = y0+(y1-y0)*Double(i)/2, at = point(["x":.number(x0),"y":.number(v)]).y
                var path = Path();path.move(to:CGPoint(x:p.minX,y:at));path.addLine(to:CGPoint(x:p.maxX,y:at));context.stroke(path,with:.color(Color(red:226/255,green:217/255,blue:205/255)),lineWidth:2)
                text(String(format:"%.0f",v),0,at-12,18)
            }
            text(root["unit"]?.stringValue ?? "",0,-8,18)
            text("Simplified trace · \(curves.count) of \(Int(root["channelCount"]?.numberValue ?? Double(curves.count))) channels",size.width-370,-8,18)
            text(String(format:"%.0f → %.0f min since each dish's Food on",x0,x1),p.minX,p.maxY+8,19)
            for (i,curve) in curves.enumerated() {
                let color = NativeShareCardRenderer.palette[i % NativeShareCardRenderer.palette.count]
                for segment in curve["segments"]?.arrayValue ?? [] {
                    let points = (segment.arrayValue ?? []).compactMap(\.objectValue)
                    var line = Path()
                    for (j,row) in points.enumerated() { let at = point(row);if j == 0 { line.move(to:at) } else { line.addLine(to:at) };context.fill(Path(ellipseIn:CGRect(x:at.x-4,y:at.y-4,width:8,height:8)),with:.color(color)) }
                    context.stroke(line,with:.color(color),lineWidth:4)
                }
                var label = "\(i+1). \(shareCardLimited(curve["label"]?.stringValue ?? "",100))"
                let font = UIFont.systemFont(ofSize:19), available = size.width/2-8
                if (label as NSString).size(withAttributes:[.font:font]).width > available {
                    while !label.isEmpty && ((label + "…") as NSString).size(withAttributes:[.font:font]).width > available { label.removeLast() }
                    label += "…"
                }
                context.draw(Text(label).font(.system(size:19)).foregroundColor(color),
                    in:CGRect(x:Double(i%2)*size.width/2,y:size.height-70+Double(i/2)*24,width:size.width/2-8,height:24))
            }
        }
    }
}
