import AppKit

// Split the generated atlas without replacing its alpha, then align paws to a shared baseline.
guard CommandLine.arguments.count == 2 else { fatalError("Usage: swift scripts/prepare-assets.swift <atlas.png>") }
let source = URL(fileURLWithPath: CommandLine.arguments[1])
let data = try Data(contentsOf: source)
let bitmap = NSBitmapImageRep(data: data)!
let cg = bitmap.cgImage!
let output = URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent("Assets")
try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
try data.write(to: output.appendingPathComponent("sprites-source-v2.png"))
// Generated poses can cross nominal grid lines. Extract their separate alpha components
// instead of cutting a tail or including a fragment of the neighboring pose.
let w = bitmap.pixelsWide, h = bitmap.pixelsHigh
var labels = [Int32](repeating: 0, count: w * h)
for y in 0..<h { for x in 0..<w {
    if (bitmap.colorAt(x: x, y: y)?.alphaComponent ?? 0) > 0.04 { labels[y * w + x] = -1 }
} }
var components: [(Int32, Int, CGRect)] = []
var componentID: Int32 = 0
for i in labels.indices where labels[i] == -1 {
    componentID += 1; labels[i] = componentID
    var queue = [i], cursor = 0, minX = w, minY = h, maxX = 0, maxY = 0
    while cursor < queue.count {
        let j = queue[cursor]; cursor += 1
        let x = j % w, y = j / w
        minX = min(minX, x); minY = min(minY, y); maxX = max(maxX, x); maxY = max(maxY, y)
        for n in [x > 0 ? j - 1 : -1, x < w - 1 ? j + 1 : -1, y > 0 ? j - w : -1, y < h - 1 ? j + w : -1] {
            if n >= 0 && labels[n] == -1 { labels[n] = componentID; queue.append(n) }
        }
    }
    if queue.count > 10000 { components.append((componentID, queue.count, CGRect(x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1))) }
}
guard components.count == 6 else { fatalError("Expected six separate poses, found \(components.count)") }
for (id, _, componentBounds) in components {
    let column = min(2, Int(componentBounds.midX) / (w / 3))
    let name = (componentBounds.minY < CGFloat(h) * 0.3 ? ["idle", "happy", "walk"] : ["sleep", "eat", "play"])[column]
    let cropRect = componentBounds.insetBy(dx: -5, dy: -5).intersection(CGRect(x: 0, y: 0, width: w, height: h)).integral
    let cw = Int(cropRect.width), ch = Int(cropRect.height)
    precondition(bitmap.bitsPerSample == 8 && bitmap.samplesPerPixel == 4 && bitmap.bitmapFormat == .alphaNonpremultiplied)
    let sourcePixels = bitmap.bitmapData!
    var pixels = [UInt8](repeating: 0, count: cw * ch * 4)
    for y in 0..<ch { for x in 0..<cw {
        let sx = x + Int(cropRect.minX), sy = y + Int(cropRect.minY)
        var belongs = labels[sy * w + sx] == id
        if !belongs {
            for dy in -4...4 {
                let ny = sy + dy
                if ny < 0 || ny >= h { continue }
                for dx in -4...4 {
                    let nx = sx + dx
                    if nx >= 0 && nx < w && labels[ny * w + nx] == id { belongs = true; break }
                }
                if belongs { break }
            }
        }
        if belongs && (labels[sy * w + sx] == id || labels[sy * w + sx] == 0) {
            let src = sy * bitmap.bytesPerRow + sx * 4, dst = (y * cw + x) * 4
            for channel in 0..<4 { pixels[dst + channel] = sourcePixels[src + channel] }
        }
    } }
    let provider = CGDataProvider(data: Data(pixels) as CFData)!
    let crop = CGImage(width: cw, height: ch, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: cw * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.last.rawValue), provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)!
    let image = NSImage(cgImage: crop, size: cropRect.size)
    let target = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 512, pixelsHigh: 512, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: target)
    NSGraphicsContext.current?.imageInterpolation = .high
    let factor = 472 / max(cropRect.width, cropRect.height)
    let size = NSSize(width: cropRect.width * factor, height: cropRect.height * factor)
    image.draw(in: NSRect(x: (512 - size.width) / 2, y: 18, width: size.width, height: size.height))
    NSGraphicsContext.restoreGraphicsState()
    try target.representation(using: .png, properties: [:])!.write(to: output.appendingPathComponent("\(name).png"))
    print("\(name): \(cropRect)")
}

let iconset = URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent("build/AppIcon.iconset")
try FileManager.default.createDirectory(at: iconset, withIntermediateDirectories: true)
let cat = NSImage(contentsOf: output.appendingPathComponent("idle.png"))!
for points in [16, 32, 128, 256, 512] {
    for density in [1, 2] {
        let pixels = points * density
        let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: pixels, pixelsHigh: pixels, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
        NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
        let p = CGFloat(pixels)
        NSColor(calibratedRed: 0.99, green: 0.94, blue: 0.77, alpha: 1).setFill()
        NSBezierPath(roundedRect: NSRect(x: p * 0.04, y: p * 0.04, width: p * 0.92, height: p * 0.92), xRadius: p * 0.22, yRadius: p * 0.22).fill()
        cat.draw(in: NSRect(x: p * 0.04, y: p * 0.05, width: p * 0.92, height: p * 0.92))
        NSGraphicsContext.restoreGraphicsState()
        let filename = "icon_\(points)x\(points)\(density == 2 ? "@2x" : "").png"
        try rep.representation(using: .png, properties: [:])!.write(to: iconset.appendingPathComponent(filename))
    }
}
