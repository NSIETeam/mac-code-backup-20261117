import AppKit
import QuartzCore

enum Pose: String, CaseIterable { case idle, happy, walk, sleep, eat, play }

final class PetModel {
    let defaults: UserDefaults
    var pose: Pose = .idle
    var deadline: TimeInterval = 0
    var message = "你好呀，我是黄丫丫。"
    var messageUntil: TimeInterval = 0
    var spontaneous = false
    var happiness: Double
    var fullness: Double
    var energy: Double
    var pets: Int
    var wandering: Bool { didSet { defaults.set(wandering, forKey: "wandering") } }
    var size: Double { didSet { defaults.set(size, forKey: "size") } }
    var isSleeping: Bool { pose == .sleep }
    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        happiness = defaults.object(forKey: "happiness") as? Double ?? 85
        fullness = defaults.object(forKey: "fullness") as? Double ?? 80
        energy = defaults.object(forKey: "energy") as? Double ?? 90
        pets = defaults.integer(forKey: "pets")
        wandering = defaults.object(forKey: "wandering") as? Bool ?? false
        size = defaults.object(forKey: "size") as? Double ?? 1
    }
    func say(_ text: String, now: TimeInterval, duration: Double = 4) {
        message = text; messageUntil = now + duration
    }
    func act(_ action: Pose, now: TimeInterval) {
        spontaneous = false
        pose = action
        switch action {
        case .happy:
            pets += 1; happiness = min(100, happiness + 6)
            deadline = now + 3
            say(["呼噜呼噜，喜欢你。", "再摸一下下嘛～", "今天也要陪着你。", "喵！收到你的喜欢啦。"].randomElement()!, now: now)
        case .eat:
            fullness = min(100, fullness + 24); happiness = min(100, happiness + 3)
            deadline = now + 5; say("开饭啦，啊呜啊呜～", now: now, duration: 5)
        case .play:
            energy = max(0, energy - 5); happiness = min(100, happiness + 10)
            deadline = now + 5; say("扑！抓到小鱼干了吗？", now: now, duration: 5)
        case .sleep:
            deadline = .infinity; say("眯一会儿，等你回来。", now: now, duration: 5)
        case .walk:
            deadline = now + 5
        case .idle:
            deadline = 0; say("睡醒啦，陪你继续。", now: now)
        }
        save()
    }
    func tick(now: TimeInterval) {
        if pose != .idle && pose != .sleep && now >= deadline { pose = .idle; spontaneous = false }
    }
    func careMinute() {
        fullness = max(0, fullness - 0.6)
        if isSleeping { energy = min(100, energy + 3) }
        else { energy = max(0, energy - 0.3) }
        save()
    }
    func save() {
        defaults.set(happiness, forKey: "happiness"); defaults.set(fullness, forKey: "fullness")
        defaults.set(energy, forKey: "energy"); defaults.set(pets, forKey: "pets")
    }
}

func constrainedOrigin(_ origin: NSPoint, size: NSSize, within bounds: NSRect) -> NSPoint {
    NSPoint(x: min(max(origin.x, bounds.minX), max(bounds.minX, bounds.maxX - size.width)),
            y: min(max(origin.y, bounds.minY), max(bounds.minY, bounds.maxY - size.height)))
}

final class Sprite {
    let image: NSImage
    let bitmap: NSBitmapImageRep
    init(url: URL) throws {
        let data = try Data(contentsOf: url)
        guard let image = NSImage(data: data), let bitmap = NSBitmapImageRep(data: data), bitmap.hasAlpha else {
            throw NSError(domain: "黄丫丫", code: 1, userInfo: [NSLocalizedDescriptionKey: "无法加载透明猫咪素材：\(url.lastPathComponent)"])
        }
        self.image = image; self.bitmap = bitmap
    }
    func opaque(at p: NSPoint, rect: NSRect) -> Bool {
        guard rect.contains(p) else { return false }
        let x = min(bitmap.pixelsWide - 1, max(0, Int((p.x - rect.minX) / rect.width * Double(bitmap.pixelsWide))))
        let y = min(bitmap.pixelsHigh - 1, max(0, Int((1 - (p.y - rect.minY) / rect.height) * Double(bitmap.pixelsHigh))))
        return (bitmap.colorAt(x: x, y: y)?.alphaComponent ?? 0) > 0.08
    }
}

class PetPanel: NSPanel {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
}

final class PetView: NSView {
    let model: PetModel
    let sprites: [Pose: Sprite]
    weak var controller: AppDelegate?
    var elapsed: Double = 0
    var hover = false
    var controlsUntil = ProcessInfo.processInfo.systemUptime + 8
    var dragging = false
    var dragStart = NSPoint.zero
    var windowStart = NSPoint.zero
    var didMove = false
    var facingRight = true
    var buttons: [NSButton] = []
    var scale: CGFloat { bounds.width / 260 }
    var bodyRect: NSRect {
        let bounce = model.pose == .play ? abs(sin(elapsed * 6)) * 13 : (model.pose == .walk ? sin(elapsed * 11) * 2 : sin(elapsed * 1.7) * 1.6)
        return NSRect(x: 21 * scale, y: (46 + bounce) * scale, width: 218 * scale, height: 218 * scale)
    }
    var nameRect: NSRect { NSRect(x: 84 * scale, y: 30 * scale, width: 92 * scale, height: 23 * scale) }
    init(model: PetModel, sprites: [Pose: Sprite]) {
        self.model = model; self.sprites = sprites
        super.init(frame: NSRect(x: 0, y: 0, width: 260, height: 322))
        wantsLayer = true
        layer?.backgroundColor = NSColor.clear.cgColor
        let labels = ["♡ 摸摸", "♧ 喂食", "✦ 逗猫", "☾ 睡觉"]
        for (i, title) in labels.enumerated() {
            let button = NSButton(title: title, target: self, action: #selector(actionButton(_:)))
            button.tag = i; button.isBordered = false
            button.font = .systemFont(ofSize: 11, weight: .medium)
            button.contentTintColor = NSColor(calibratedRed: 0.25, green: 0.29, blue: 0.26, alpha: 1)
            button.setAccessibilityLabel(["摸摸黄丫丫", "喂食", "逗猫", "睡觉或叫醒"][i])
            addSubview(button); buttons.append(button)
        }
        setAccessibilityElement(true)
        setAccessibilityRole(.group)
        setAccessibilityLabel("黄丫丫桌面宠物，单击摸摸，双击逗猫，可拖动，右键打开菜单")
        setAccessibilityChildren(buttons)
        toolTip = "黄丫丫 · 单击摸摸 / 双击逗猫 / 拖动搬家 / 右键菜单"
    }
    required init?(coder: NSCoder) { fatalError() }
    override var isOpaque: Bool { false }
    override var acceptsFirstResponder: Bool { false }
    override func layout() {
        super.layout()
        for (i, button) in buttons.enumerated() {
            button.frame = NSRect(x: (12 + CGFloat(i) * 59) * scale, y: 3 * scale, width: 59 * scale, height: 24 * scale)
            button.font = .systemFont(ofSize: 11 * scale, weight: .medium)
        }
    }
    @objc func actionButton(_ sender: NSButton) {
        let pose: Pose = [.happy, .eat, .play, model.isSleeping ? .idle : .sleep][sender.tag]
        controller?.perform(pose)
    }
    func updateHover(_ value: Bool) {
        if hover != value { hover = value; needsDisplay = true }
        for button in buttons { button.isHidden = !value }
        buttons.last?.title = model.isSleeping ? "☀ 叫醒" : "☾ 睡觉"
    }
    func captures(_ point: NSPoint) -> Bool {
        if dragging || nameRect.contains(point) { return true }
        if hover && NSRect(x: 8 * scale, y: 0, width: 244 * scale, height: 29 * scale).contains(point) { return true }
        let imagePoint = model.pose == .walk && !facingRight ? NSPoint(x: bounds.width - point.x, y: point.y) : point
        return sprites[model.pose]?.opaque(at: imagePoint, rect: bodyRect) ?? false
    }
    override func mouseDown(with event: NSEvent) {
        dragging = true; didMove = false
        dragStart = NSEvent.mouseLocation; windowStart = window?.frame.origin ?? .zero
    }
    override func mouseDragged(with event: NSEvent) {
        let p = NSEvent.mouseLocation
        let dx = p.x - dragStart.x, dy = p.y - dragStart.y
        if hypot(dx, dy) > 4 { didMove = true }
        if didMove { window?.setFrameOrigin(NSPoint(x: windowStart.x + dx, y: windowStart.y + dy)) }
    }
    override func mouseUp(with event: NSEvent) {
        dragging = false
        if didMove { controller?.finishDrag() }
        else { controller?.perform(event.clickCount > 1 ? .play : (model.isSleeping ? .idle : .happy)) }
    }
    override func rightMouseDown(with event: NSEvent) {
        if let menu = controller?.makeMenu() { NSMenu.popUpContextMenu(menu, with: event, for: self) }
    }
    func pill(_ rect: NSRect, color: NSColor) {
        color.setFill(); NSBezierPath(roundedRect: rect, xRadius: rect.height / 2, yRadius: rect.height / 2).fill()
    }
    func text(_ text: String, rect: NSRect, font: NSFont, color: NSColor, center: Bool = true) {
        let paragraph = NSMutableParagraphStyle(); paragraph.alignment = center ? .center : .left
        (text as NSString).draw(in: rect, withAttributes: [.font: font, .foregroundColor: color, .paragraphStyle: paragraph])
    }
    override func draw(_ dirtyRect: NSRect) {
        NSColor.clear.setFill(); dirtyRect.fill(using: .copy)
        let now = ProcessInfo.processInfo.systemUptime
        if now < model.messageUntil {
            let r = NSRect(x: 14 * scale, y: 271 * scale, width: 232 * scale, height: 38 * scale)
            NSGraphicsContext.saveGraphicsState()
            let shadow = NSShadow(); shadow.shadowColor = NSColor.black.withAlphaComponent(0.10)
            shadow.shadowBlurRadius = 8; shadow.shadowOffset = NSSize(width: 0, height: -2); shadow.set()
            pill(r, color: NSColor(calibratedRed: 1, green: 0.99, blue: 0.95, alpha: 0.98))
            NSGraphicsContext.restoreGraphicsState()
            text(model.message, rect: r.insetBy(dx: 8 * scale, dy: 10 * scale), font: .systemFont(ofSize: 12 * scale, weight: .medium), color: NSColor(calibratedWhite: 0.24, alpha: 1))
        }
        if let sprite = sprites[model.pose] {
            NSGraphicsContext.saveGraphicsState()
            NSGraphicsContext.current?.imageInterpolation = .high
            let rect = bodyRect
            if model.pose == .walk && !facingRight {
                let transform = AffineTransform(translationByX: bounds.width, byY: 0)
                (transform as NSAffineTransform).concat()
                let flip = AffineTransform(scaleByX: -1, byY: 1)
                (flip as NSAffineTransform).concat()
            }
            sprite.image.draw(in: rect, from: .zero, operation: .sourceOver, fraction: 1)
            NSGraphicsContext.restoreGraphicsState()
        }
        if model.pose == .happy && !model.spontaneous {
            for i in 0..<3 {
                let phase = (elapsed * 0.6 + Double(i) * 0.29).truncatingRemainder(dividingBy: 1)
                let x = (70 + Double(i) * 56 + sin(elapsed + Double(i)) * 8) * scale
                text("♥", rect: NSRect(x: x, y: (180 + phase * 78) * scale, width: 22 * scale, height: 28 * scale), font: .systemFont(ofSize: (13 + phase * 5) * scale), color: NSColor.systemPink.withAlphaComponent(1 - phase))
            }
        }
        if model.pose == .sleep {
            text("z Z", rect: NSRect(x: 176 * scale, y: (201 + sin(elapsed) * 5) * scale, width: 52 * scale, height: 28 * scale), font: .systemFont(ofSize: 20 * scale, weight: .semibold), color: NSColor(calibratedRed: 0.55, green: 0.62, blue: 0.66, alpha: 0.9))
        }
        pill(nameRect, color: NSColor(calibratedRed: 1, green: 0.97, blue: 0.85, alpha: 0.97))
        text("黄丫丫", rect: nameRect.insetBy(dx: 3 * scale, dy: 4 * scale), font: .systemFont(ofSize: 12 * scale, weight: .semibold), color: NSColor(calibratedRed: 0.37, green: 0.31, blue: 0.20, alpha: 1))
        if hover { pill(NSRect(x: 8 * scale, y: 1 * scale, width: 244 * scale, height: 27 * scale), color: NSColor(calibratedRed: 0.98, green: 0.99, blue: 0.97, alpha: 0.96)) }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    let model = PetModel()
    var panel: PetPanel!
    var petView: PetView!
    var statusItem: NSStatusItem!
    var timer: Timer?
    var lastFrame = ProcessInfo.processInfo.systemUptime
    var nextCare = ProcessInfo.processInfo.systemUptime + 60
    var nextIdle = ProcessInfo.processInfo.systemUptime + 18
    var walkDirection: CGFloat = -1
    var alwaysOnTop = true

    func applicationDidFinishLaunching(_ notification: Notification) {
        do {
            let sprites = try loadSprites()
            if CommandLine.arguments.contains("--self-test") { try selfTest(sprites); NSApp.terminate(nil); return }
            petView = PetView(model: model, sprites: sprites); petView.controller = self
            panel = PetPanel(contentRect: NSRect(x: 0, y: 0, width: 260, height: 322), styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
            panel.title = "黄丫丫"; panel.isOpaque = false; panel.backgroundColor = .clear
            panel.hasShadow = false; panel.level = .floating; panel.isFloatingPanel = true
            panel.hidesOnDeactivate = false; panel.isReleasedWhenClosed = false
            panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle]
            panel.contentView = petView
            applySize(model.size, save: false)
            restorePosition()
            statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
            statusItem.button?.title = "🐾"; statusItem.button?.toolTip = "黄丫丫 · 你的桌面小猫"
            statusItem.button?.setAccessibilityLabel("黄丫丫")
            statusItem.menu = makeMenu()
            panel.orderFrontRegardless()
            model.say("你好呀，我是黄丫丫。", now: lastFrame, duration: 7)
            NotificationCenter.default.addObserver(self, selector: #selector(screenChanged), name: NSApplication.didChangeScreenParametersNotification, object: nil)
            timer = Timer.scheduledTimer(withTimeInterval: 1.0 / 30, repeats: true) { [weak self] _ in self?.tick() }
            RunLoop.main.add(timer!, forMode: .eventTracking)
        } catch {
            if CommandLine.arguments.contains("--self-test") { fputs("SELF-TEST FAILED: \(error)\n", stderr); exit(1) }
            let alert = NSAlert(); alert.messageText = "黄丫丫还没准备好"; alert.informativeText = error.localizedDescription
            alert.runModal(); NSApp.terminate(nil)
        }
    }
    func loadSprites() throws -> [Pose: Sprite] {
        var result: [Pose: Sprite] = [:]
        for pose in Pose.allCases {
            guard let url = Bundle.main.url(forResource: pose.rawValue, withExtension: "png") else {
                throw NSError(domain: "黄丫丫", code: 2, userInfo: [NSLocalizedDescriptionKey: "缺少素材 \(pose.rawValue).png，请重新运行 scripts/build.sh。"])
            }
            result[pose] = try Sprite(url: url)
        }
        return result
    }
    func tick() {
        let now = ProcessInfo.processInfo.systemUptime
        let dt = min(0.1, now - lastFrame); lastFrame = now
        guard panel.isVisible else { return }
        model.tick(now: now); petView.elapsed += dt
        let screenPoint = NSEvent.mouseLocation
        let point = petView.convert(panel.convertPoint(fromScreen: screenPoint), from: nil)
        let near = petView.bounds.insetBy(dx: -8, dy: -8).contains(point)
        petView.updateHover(near || now < petView.controlsUntil)
        panel.ignoresMouseEvents = !petView.captures(point)
        if now > nextCare { model.careMinute(); nextCare = now + 60 }
        if model.pose == .walk && !petView.dragging && !near {
            let screen = panel.screen ?? NSScreen.main
            if let frame = screen?.visibleFrame {
                var origin = panel.frame.origin; origin.x += walkDirection * 30 * dt
                if origin.x <= frame.minX || origin.x + panel.frame.width >= frame.maxX { walkDirection *= -1 }
                panel.setFrameOrigin(constrainedOrigin(origin, size: panel.frame.size, within: frame))
                petView.facingRight = walkDirection > 0
            }
        }
        if now > nextIdle && model.pose == .idle && !petView.dragging && !near {
            nextIdle = now + Double.random(in: 25...48)
            if model.wandering { model.act(.walk, now: now); walkDirection = Bool.random() ? 1 : -1 }
            else if Bool.random() { model.pose = .happy; model.spontaneous = true; model.deadline = now + 1.2 }
        }
        petView.needsDisplay = true
    }
    func perform(_ pose: Pose) {
        model.act(pose, now: ProcessInfo.processInfo.systemUptime)
        petView?.controlsUntil = ProcessInfo.processInfo.systemUptime + 8
        nextIdle = ProcessInfo.processInfo.systemUptime + 25
        statusItem?.menu = makeMenu(); petView?.needsDisplay = true
    }
    func restorePosition() {
        let frame = NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
        let d = model.defaults
        let proposed = d.object(forKey: "positionX") != nil ? NSPoint(x: d.double(forKey: "positionX"), y: d.double(forKey: "positionY")) : NSPoint(x: frame.maxX - panel.frame.width - 30, y: frame.minY + 20)
        let screen = NSScreen.screens.first { $0.visibleFrame.intersects(NSRect(origin: proposed, size: panel.frame.size)) } ?? NSScreen.main
        panel.setFrameOrigin(constrainedOrigin(proposed, size: panel.frame.size, within: screen?.visibleFrame ?? frame))
    }
    func finishDrag() {
        let cursor = NSEvent.mouseLocation
        let screen = NSScreen.screens.first { $0.frame.contains(cursor) } ?? panel.screen ?? NSScreen.main
        if let frame = screen?.visibleFrame { panel.setFrameOrigin(constrainedOrigin(panel.frame.origin, size: panel.frame.size, within: frame)) }
        savePosition()
    }
    func savePosition() {
        model.defaults.set(panel.frame.minX, forKey: "positionX"); model.defaults.set(panel.frame.minY, forKey: "positionY")
    }
    @objc func screenChanged() { restorePosition() }
    func applySize(_ size: Double, save: Bool = true) {
        let value = min(1.35, max(0.75, size))
        panel?.setContentSize(NSSize(width: 260 * value, height: 322 * value))
        petView?.needsLayout = true
        if save { model.size = value; finishDrag() }
    }
    func makeMenu() -> NSMenu {
        let menu = NSMenu(); menu.delegate = self
        func add(_ title: String, _ selector: Selector?, key: String = "") -> NSMenuItem {
            let item = NSMenuItem(title: title, action: selector, keyEquivalent: key); item.target = self; menu.addItem(item); return item
        }
        _ = add("黄丫丫 · 桌面小猫", nil)
        _ = add("开心 \(Int(model.happiness))   饱饱 \(Int(model.fullness))   精力 \(Int(model.energy))", nil)
        _ = add("已经摸摸 \(model.pets) 次", nil)
        menu.addItem(.separator())
        _ = add("♡  摸摸黄丫丫", #selector(pet))
        _ = add("♧  喂点好吃的", #selector(feed))
        _ = add("✦  陪它玩一会", #selector(play))
        _ = add(model.isSleeping ? "☀  叫醒黄丫丫" : "☾  让它睡一会", #selector(sleepWake))
        menu.addItem(.separator())
        let wander = add("在桌面散步", #selector(toggleWander)); wander.state = model.wandering ? .on : .off
        let top = add("陪在窗口上方", #selector(toggleTop)); top.state = alwaysOnTop ? .on : .off
        let sizes = add("调整大小", nil); let submenu = NSMenu()
        for (title, value) in [("小巧", 0.75), ("标准", 1.0), ("大一点", 1.35)] {
            let item = NSMenuItem(title: title, action: #selector(resize(_:)), keyEquivalent: "")
            item.target = self; item.representedObject = value; item.state = abs(model.size - value) < 0.01 ? .on : .off
            submenu.addItem(item)
        }
        sizes.submenu = submenu
        _ = add(panel?.isVisible == false ? "让黄丫丫回来" : "暂时藏起来", #selector(toggleVisible))
        _ = add("把黄丫丫叫到这块屏幕", #selector(recenter))
        _ = add("怎么玩", #selector(help))
        menu.addItem(.separator())
        _ = add("退出黄丫丫", #selector(quit), key: "q")
        return menu
    }
    func menuNeedsUpdate(_ menu: NSMenu) {
        let current = makeMenu()
        menu.removeAllItems()
        for item in current.items { current.removeItem(item); menu.addItem(item) }
    }
    @objc func pet() { perform(.happy) }
    @objc func feed() { perform(.eat) }
    @objc func play() { perform(.play) }
    @objc func sleepWake() { perform(model.isSleeping ? .idle : .sleep) }
    @objc func toggleWander() {
        model.wandering.toggle()
        if !model.wandering && model.pose == .walk { model.pose = .idle }
        model.say(model.wandering ? "我去附近溜达一下～" : "乖乖坐好，陪你。", now: ProcessInfo.processInfo.systemUptime)
        statusItem.menu = makeMenu()
    }
    @objc func toggleTop() { alwaysOnTop.toggle(); panel.level = alwaysOnTop ? .floating : .normal; statusItem.menu = makeMenu() }
    @objc func resize(_ sender: NSMenuItem) { if let value = sender.representedObject as? Double { applySize(value); statusItem.menu = makeMenu() } }
    @objc func toggleVisible() {
        if panel.isVisible { panel.orderOut(nil) } else { panel.orderFrontRegardless(); lastFrame = ProcessInfo.processInfo.systemUptime }
        statusItem.menu = makeMenu()
    }
    @objc func recenter() {
        let cursor = NSEvent.mouseLocation
        let screen = NSScreen.screens.first { $0.frame.contains(cursor) } ?? NSScreen.main
        if let frame = screen?.visibleFrame { panel.setFrameOrigin(NSPoint(x: frame.midX - panel.frame.width / 2, y: frame.minY + 20)); savePosition() }
        panel.orderFrontRegardless(); statusItem.menu = makeMenu()
    }
    @objc func help() {
        let alert = NSAlert(); alert.messageText = "和黄丫丫一起待一会儿"
        alert.informativeText = "单击猫咪：摸摸它\n双击猫咪：陪它玩\n按住拖动：搬到喜欢的位置\n鼠标靠近：显示喂食和睡觉按钮\n右键猫咪 / 菜单栏爪印：更多选项\n\n黄丫丫会记住大小、位置和互动次数。\n所有数据只保存在这台 Mac，不需要联网。"
        alert.addButton(withTitle: "知道啦"); alert.runModal()
    }
    @objc func quit() { savePosition(); model.save(); NSApp.terminate(nil) }
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply { model.save(); return .terminateNow }
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        panel?.orderFrontRegardless(); statusItem?.menu = makeMenu(); return false
    }
    func selfTest(_ sprites: [Pose: Sprite]) throws {
        func check(_ condition: @autoclosure () -> Bool, _ message: String) throws {
            if !condition() { throw NSError(domain: "SelfTest", code: 1, userInfo: [NSLocalizedDescriptionKey: message]) }
        }
        let suite = "local.huangyaya.selftest.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let pet = PetModel(defaults: defaults)
        pet.act(.happy, now: 10); try check(pet.pets == 1 && pet.pose == .happy, "Petting records interaction")
        pet.tick(now: 14); try check(pet.pose == .idle, "Finite action returns to idle")
        for _ in 0..<10 { pet.act(.eat, now: 20) }
        try check(pet.fullness == 100, "Feeding stays within bounds")
        pet.act(.sleep, now: 30); pet.tick(now: 900); try check(pet.isSleeping, "Sleep persists until woken")
        pet.energy = 50; pet.careMinute(); try check(pet.energy == 53, "Sleep restores energy")
        pet.act(.idle, now: 901); try check(!pet.isSleeping, "Wake resumes interaction")
        try check(PetModel(defaults: defaults).pets == 1, "Interaction count persists")
        let origin = constrainedOrigin(NSPoint(x: -100, y: 2000), size: NSSize(width: 260, height: 322), within: NSRect(x: 0, y: 30, width: 1000, height: 700))
        try check(origin == NSPoint(x: 0, y: 408), "Window remains within screen")
        for pose in Pose.allCases {
            let sprite = sprites[pose]!
            try check(sprite.bitmap.pixelsWide == 512 && sprite.bitmap.pixelsHigh == 512, "Normalized sprite dimensions")
            try check((sprite.bitmap.colorAt(x: 0, y: 0)?.alphaComponent ?? 1) == 0, "Transparent sprite corner")
            var solid = 0
            for y in stride(from: 0, to: 512, by: 8) { for x in stride(from: 0, to: 512, by: 8) {
                if (sprite.bitmap.colorAt(x: x, y: y)?.alphaComponent ?? 0) > 0.8 { solid += 1 }
            } }
            try check(solid > 400 && solid < 3500, "Sprite has visible content and transparent margins")
        }
        let view = PetView(model: pet, sprites: sprites)
        view.updateHover(true); view.layoutSubtreeIfNeeded()
        try check(view.buttons.count == 4 && view.buttons.allSatisfy { !$0.isHidden }, "Hover actions are available")
        let out = URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent("build")
        try FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
        for pose in Pose.allCases {
            pet.pose = pose; pet.say("你好呀，我是黄丫丫。", now: ProcessInfo.processInfo.systemUptime)
            view.needsDisplay = true
            guard let bitmap = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { throw NSError(domain: "SelfTest", code: 3) }
            view.cacheDisplay(in: view.bounds, to: bitmap)
            try bitmap.representation(using: .png, properties: [:])!.write(to: out.appendingPathComponent("preview-\(pose.rawValue).png"))
        }
        print("PASS: state transitions, feeding bounds, sleep recovery, persistence, screen bounds, six transparent sprites, toolbar, six rendered previews")
    }
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let delegate = AppDelegate()
app.delegate = delegate
app.run()
