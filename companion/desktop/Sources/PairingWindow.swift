import AppKit
import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI

/// "Pair iPhone…": asks the bridge for a one-time pairing code (`POST /v1/mobile/pairing/start`, loopback + desktop
/// token) and shows it as a QR code of the `samrabbit://pair?…` link, as text, and with this Mac's address; below it
/// the paired iPhones and watches (`GET /v1/mobile/devices`) with Revoke (`DELETE /v1/mobile/devices/<id>`).
/// The code is shown here only, never logged.
enum PairingAPI {
    struct Start: Decodable, Equatable {
        let code: String
        let expiresAt: String
        let pairUrl: String
        let hosts: [String]
        let bridgeName: String?
    }

    struct Device: Decodable, Identifiable, Equatable {
        let deviceId: String
        let name: String?
        let platform: String?
        let createdAt: String?
        let lastSeenAt: String?
        let parentId: String?
        var id: String { deviceId }
    }

    struct Failure: Error { let status: Int }

    private struct DeviceList: Decodable { let devices: [Device] }

    static func start() async -> Result<Start, Failure> {
        await call("/v1/mobile/pairing/start", method: "POST")
    }

    static func devices() async -> Result<[Device], Failure> {
        let result: Result<DeviceList, Failure> = await call("/v1/mobile/devices", method: "GET")
        return result.map(\.devices)
    }

    static func revoke(_ id: String) async -> Bool {
        let escaped = id.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(CharacterSet(charactersIn: "_"))) ?? id
        guard var request = Config.request(Config.api("/v1/mobile/devices/\(escaped)"), timeout: 8) else { return false }
        request.httpMethod = "DELETE"
        guard let (_, response) = try? await URLSession.shared.data(for: request) else { return false }
        return (response as? HTTPURLResponse)?.statusCode == 200
    }

    private static func call<T: Decodable>(_ path: String, method: String) async -> Result<T, Failure> {
        guard var request = Config.request(Config.api(path), timeout: 8) else { return .failure(Failure(status: -1)) }
        request.httpMethod = method
        if method == "POST" {
            request.httpBody = Data("{}".utf8)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard status == 200 else { return .failure(Failure(status: status)) }
            return .success(try JSONDecoder().decode(T.self, from: data))
        } catch is DecodingError {
            return .failure(Failure(status: 502))
        } catch {
            return .failure(Failure(status: 0))
        }
    }
}

/// QR code (CoreImage `CIQRCodeGenerator`), black on white with sharp modules.
enum QRCode {
    static func image(for text: String, pixels: CGFloat = 640) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage, output.extent.width > 0 else { return nil }
        let scale = (pixels / output.extent.width).rounded(.down)
        let scaled = output.transformed(by: CGAffineTransform(scaleX: max(1, scale), y: max(1, scale)))
        let context = CIContext(options: [.useSoftwareRenderer: false])
        guard let cg = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return NSImage(cgImage: cg, size: NSSize(width: scaled.extent.width, height: scaled.extent.height))
    }
}

@MainActor
final class PairingModel: ObservableObject {
    enum Phase: Equatable {
        case loading
        case ready
        case failed(Int)
    }

    @Published private(set) var phase: Phase = .loading
    @Published private(set) var start: PairingAPI.Start?
    @Published private(set) var qr: NSImage?
    @Published private(set) var devices: [PairingAPI.Device] = []
    @Published private(set) var justPaired: String?
    @Published private(set) var now = Date()
    @Published var pendingRevoke: PairingAPI.Device?
    private var timer: Timer?
    private var known: Set<String>?
    private var ticks = 0

    var expiresAt: Date? { start.flatMap { PairingModel.date($0.expiresAt) } }
    var expired: Bool { expiresAt.map { $0 <= now } ?? false }
    var codeText: String {
        guard let code = start?.code, code.count == 8 else { return start?.code ?? "" }
        return "\(code.prefix(4)) \(code.suffix(4))"
    }

    func open() {
        justPaired = nil
        Task { await newCode() }
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
    }

    func close() {
        timer?.invalidate()
        timer = nil
    }

    /// While a code is on screen and nothing has paired with it yet, the device list is read every 2 s, so a phone
    /// that just paired shows up at once; otherwise every 30 s (for the "last seen" times), which keeps the bridge
    /// log quiet while the window stays open.
    var waitingForPairing: Bool { phase == .ready && start != nil && !expired && justPaired == nil }

    private func tick() {
        now = Date()
        ticks += 1
        if ticks % (waitingForPairing ? 2 : 30) == 0 { Task { await refreshDevices() } }
    }

    func newCode() async {
        phase = .loading
        justPaired = nil
        switch await PairingAPI.start() {
        case .success(let value):
            start = value
            qr = QRCode.image(for: value.pairUrl)
            phase = .ready
            Log.write("pairing code shown")
        case .failure(let failure):
            start = nil
            qr = nil
            phase = .failed(failure.status)
            Log.write("pairing start failed (HTTP \(failure.status))")
        }
        await refreshDevices()
    }

    func refreshDevices() async {
        guard case .success(let list) = await PairingAPI.devices() else { return }
        let ids = Set(list.map(\.deviceId))
        if let known, let added = list.first(where: { !known.contains($0.deviceId) && $0.parentId == nil }) {
            justPaired = added.name ?? "Your iPhone"
            Log.write("a phone paired")
        }
        known = ids
        devices = list.sorted { ($0.createdAt ?? "") > ($1.createdAt ?? "") }
    }

    func revoke(_ device: PairingAPI.Device) async {
        if await PairingAPI.revoke(device.deviceId) { Log.write("device revoked") }
        await refreshDevices()
    }

    var failureText: (title: String, detail: String, command: String?) {
        guard case .failed(let status) = phase else { return ("", "", nil) }
        switch status {
        case -1: return ("SamRabbit isn’t set up yet", "There is no desktop token. Run the installer once:", "companion/desktop/install.sh")
        case 0: return ("The bridge isn’t answering", "The SamRabbit bridge on this Mac isn’t running.", "companion/mac-bridge/install.sh")
        case 404: return ("Update the bridge", "This bridge is too old to pair an iPhone. Update it:", "companion/mac-bridge/install.sh")
        case 401, 403: return ("The bridge didn’t accept SamRabbit", "The desktop token was not accepted. Run:", "companion/desktop/install.sh")
        default: return ("Pairing isn’t available", "The bridge answered HTTP \(status).", nil)
        }
    }

    static func date(_ text: String?) -> Date? {
        guard let text else { return nil }
        let formatter = ISO8601DateFormatter()
        if let value = formatter.date(from: text) { return value }
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.date(from: text)
    }

    static func remaining(_ seconds: TimeInterval) -> String {
        let total = max(0, Int(seconds.rounded(.down)))
        return String(format: "%d:%02d", total / 60, total % 60)
    }

    static func ago(_ text: String?, now: Date) -> String {
        guard let date = date(text) else { return "never" }
        if now.timeIntervalSince(date) < 90 { return "just now" }
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return formatter.localizedString(for: date, relativeTo: now)
    }
}

struct PairingView: View {
    @ObservedObject var model: PairingModel

    var body: some View {
        ZStack {
            LinearGradient(colors: [Palette.backgroundTop, Palette.background], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            ScrollView {
                VStack(spacing: 18) {
                    header
                    card
                    devicesCard
                }
                .padding(.horizontal, 26)
                .padding(.vertical, 22)
            }
        }
        .frame(minWidth: 420, idealWidth: 460, minHeight: 600, idealHeight: 720)
        .preferredColorScheme(.dark)
        .alert("Revoke \(model.pendingRevoke?.name ?? "this device")?", isPresented: Binding(
            get: { model.pendingRevoke != nil }, set: { if !$0 { model.pendingRevoke = nil } })) {
            Button("Revoke", role: .destructive) {
                if let device = model.pendingRevoke { Task { await model.revoke(device) } }
                model.pendingRevoke = nil
            }
            Button("Cancel", role: .cancel) { model.pendingRevoke = nil }
        } message: {
            Text("It stops working with this Mac at once (its watch too). Pair it again any time.")
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            OrbImage(size: 44)
            VStack(alignment: .leading, spacing: 3) {
                Text("Pair iPhone").font(.system(size: 20, weight: .semibold)).foregroundStyle(Palette.ink)
                Text("Control SamRabbit from your iPhone, its widgets and your Apple Watch.")
                    .font(.system(size: 12)).foregroundStyle(Palette.muted)
            }
            Spacer()
        }
    }

    @ViewBuilder private var card: some View {
        VStack(spacing: 14) {
            switch model.phase {
            case .loading:
                ProgressView().controlSize(.large).frame(height: 300)
            case .failed:
                failure
            case .ready:
                if let name = model.justPaired {
                    paired(name)
                } else {
                    code
                }
            }
        }
        .frame(maxWidth: .infinity)
        .padding(20)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Color.white.opacity(0.05)))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Color.white.opacity(0.08)))
    }

    @ViewBuilder private var code: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.white)
            if let qr = model.qr {
                Image(nsImage: qr).interpolation(.none).resizable().scaledToFit().padding(14)
                    .accessibilityLabel("QR code to pair your iPhone")
            }
            if model.expired {
                RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.black.opacity(0.82))
                VStack(spacing: 10) {
                    Text("This code expired").font(.system(size: 15, weight: .semibold)).foregroundStyle(Palette.ink)
                    Button("New Code") { Task { await model.newCode() } }.keyboardShortcut(.defaultAction)
                }
            }
        }
        .frame(width: 236, height: 236)
        Text(model.codeText)
            .font(.system(size: 30, weight: .semibold, design: .monospaced))
            .kerning(3)
            .foregroundStyle(model.expired ? Palette.muted : Palette.ink)
            .textSelection(.enabled)
            .accessibilityLabel("Pairing code " + (model.start?.code ?? "").map { String($0) }.joined(separator: " "))
        VStack(spacing: 4) {
            if let start = model.start {
                Text("\(start.bridgeName ?? "This Mac") · \(start.hosts.joined(separator: " · "))")
                    .font(.system(size: 12, design: .monospaced)).foregroundStyle(Palette.pale).textSelection(.enabled)
            }
            if let expires = model.expiresAt, !model.expired {
                Text("Single use · valid for \(PairingModel.remaining(expires.timeIntervalSince(model.now)))")
                    .font(.system(size: 11)).foregroundStyle(Palette.muted).monospacedDigit()
            }
        }
        Text("On your iPhone, open SamRabbit › Settings › Pair with Mac and scan this code, or point the Camera at it. "
             + "You can also type the code and the address.")
            .font(.system(size: 12)).foregroundStyle(Palette.muted).multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
        HStack {
            Button("New Code") { Task { await model.newCode() } }
            Button("Copy Code") {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(model.start?.code ?? "", forType: .string)
            }
            .disabled(model.start == nil || model.expired)
        }
        .controlSize(.regular)
    }

    private func paired(_ name: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "checkmark.circle.fill").font(.system(size: 54)).foregroundStyle(Palette.orb)
            Text("\(name) is paired").font(.system(size: 17, weight: .semibold)).foregroundStyle(Palette.ink)
            Text("It can now see your tasks, calendar and conversations, and control this Mac.")
                .font(.system(size: 12)).foregroundStyle(Palette.muted).multilineTextAlignment(.center)
            Button("Pair Another Device") { Task { await model.newCode() } }
        }
        .frame(height: 300)
    }

    private var failure: some View {
        let text = model.failureText
        return VStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle").font(.system(size: 34)).foregroundStyle(Palette.pale)
            Text(text.title).font(.system(size: 16, weight: .semibold)).foregroundStyle(Palette.ink)
            Text(text.detail).font(.system(size: 12)).foregroundStyle(Palette.muted).multilineTextAlignment(.center)
            if let command = text.command {
                Text(command).font(.system(size: 12, design: .monospaced)).foregroundStyle(Palette.pale)
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(RoundedRectangle(cornerRadius: 7).fill(Color.white.opacity(0.07)))
                    .textSelection(.enabled)
            }
            Button("Try Again") { Task { await model.newCode() } }.padding(.top, 4)
        }
        .frame(height: 300)
    }

    private var devicesCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Paired devices").font(.system(size: 13, weight: .semibold)).foregroundStyle(Palette.ink)
            if model.devices.isEmpty {
                Text("No iPhone or Apple Watch is paired yet.").font(.system(size: 12)).foregroundStyle(Palette.muted)
            }
            ForEach(model.devices) { device in
                HStack(spacing: 12) {
                    Image(systemName: device.platform == "watchos" ? "applewatch" : "iphone")
                        .font(.system(size: 18)).foregroundStyle(Palette.pale).frame(width: 24)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(device.name ?? "Device").font(.system(size: 13, weight: .medium)).foregroundStyle(Palette.ink)
                        Text("Last seen \(PairingModel.ago(device.lastSeenAt, now: model.now))"
                             + (device.parentId != nil ? " · through the iPhone" : ""))
                            .font(.system(size: 11)).foregroundStyle(Palette.muted)
                    }
                    Spacer()
                    Button("Revoke") { model.pendingRevoke = device }
                }
                .padding(.vertical, 4)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Color.white.opacity(0.04)))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Color.white.opacity(0.07)))
    }
}

@MainActor
final class PairingWindowController: NSWindowController, NSWindowDelegate {
    let model = PairingModel()

    init() {
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 460, height: 720),
                              styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                              backing: .buffered, defer: false)
        super.init(window: window)
        window.title = "Pair iPhone"
        window.titlebarAppearsTransparent = true
        window.appearance = NSAppearance(named: .darkAqua)
        window.backgroundColor = NSColor(srgbRed: 9 / 255, green: 11 / 255, blue: 16 / 255, alpha: 1)
        window.isReleasedWhenClosed = false
        window.tabbingMode = .disallowed
        window.delegate = self
        window.contentView = NSHostingView(rootView: PairingView(model: model))
        window.minSize = NSSize(width: 420, height: 600)
        if !window.setFrameUsingName("SamRabbitPairing") { window.center() }
        window.setFrameAutosaveName("SamRabbitPairing")
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("not used") }

    func show() {
        guard let window else { return }
        NSApp.activate(ignoringOtherApps: true)
        if !window.isVisible { model.open() }
        window.makeKeyAndOrderFront(nil)
    }

    func windowWillClose(_ notification: Notification) { model.close() }
}
