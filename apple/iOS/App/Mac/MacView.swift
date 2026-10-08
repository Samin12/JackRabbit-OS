import SamRabbitKit
import SwiftUI

/// Mac: what is on the Mac, open an app or a link, a screenshot (pinch to zoom) and Generate UI.
struct MacView: View {
    @Environment(AppModel.self) private var model
    @State private var state: MacState?
    @State private var stateError: BridgeError?
    @State private var screenshot: UIImage?
    @State private var shotAt: Date?
    @State private var shooting = false
    @State private var shotError: BridgeError?
    @State private var viewer: ViewerImage?
    @State private var openText = ""
    @State private var opening = false
    @State private var prompt = ""
    @State private var generating = false
    @State private var artifact: GeneratedArtifact?

    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                if !model.isPaired {
                    PairPromptCard()
                } else {
                    statusCard
                    screenshotCard
                    openCard
                    generateCard
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 30)
        }
        .scrollIndicators(.hidden)
        .scrollDismissesKeyboard(.interactively)
        .refreshable { await loadState() }
        .samScreen()
        .navigationTitle("Mac")
        .task { await loadState() }
        .task(id: model.pendingScreenshot) {
            if model.pendingScreenshot {
                model.pendingScreenshot = false
                await takeScreenshot()
            }
        }
        .fullScreenCover(item: $viewer) { ZoomableImageViewer(image: $0.image, title: $0.title) }
    }

    // MARK: Status

    var statusCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 14) {
                Image(systemName: "macbook")
                    .font(.system(size: 24, weight: .medium))
                    .foregroundStyle(SamTheme.orbPale)
                    .frame(width: 50, height: 50)
                    .background(RoundedRectangle(cornerRadius: 14).fill(SamTheme.orb.opacity(0.16)))
                VStack(alignment: .leading, spacing: 3) {
                    Text(state?.name ?? model.summary?.mac.name ?? model.pairing?.displayName ?? "Your Mac")
                        .font(.system(size: 18, weight: .semibold))
                    HStack(spacing: 6) {
                        if stateError == nil, state != nil {
                            PulseDot(color: state?.screenLocked == true ? SamTheme.amber : SamTheme.green, size: 6)
                            Text(state?.screenLocked == true ? "Online · screen locked" : "Online")
                        } else if let stateError {
                            Circle().fill(SamTheme.amber).frame(width: 6, height: 6)
                            Text(stateError.shortDescription)
                        } else {
                            ProgressView().controlSize(.mini)
                            Text("Checking…")
                        }
                    }
                    .font(.system(size: 13))
                    .foregroundStyle(SamTheme.muted)
                }
                Spacer()
            }
            if let front = state?.front, let app = front.app {
                VStack(alignment: .leading, spacing: 3) {
                    Text("IN FRONT").font(SamTheme.eyebrow).tracking(0.5).foregroundStyle(SamTheme.faint)
                    Text(app).font(.system(size: 15.5, weight: .semibold))
                    if let window = front.window { Text(window).font(.system(size: 13.5)).foregroundStyle(SamTheme.ink2) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 14).fill(Color.black.opacity(0.22)))
            }
            if let visible = state?.visible, !visible.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("ON SCREEN").font(SamTheme.eyebrow).tracking(0.5).foregroundStyle(SamTheme.faint)
                    ForEach(visible) { app in
                        HStack(alignment: .firstTextBaseline) {
                            Text(app.app).font(.system(size: 14, weight: .medium))
                            Spacer()
                            Text(app.windows.first ?? "").font(.system(size: 12.5)).foregroundStyle(SamTheme.muted).lineLimit(1)
                        }
                    }
                }
            }
            if let running = state?.running, !running.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("RUNNING · TAP TO BRING FORWARD").font(SamTheme.eyebrow).tracking(0.5).foregroundStyle(SamTheme.faint)
                    FlowLayout(spacing: 6) {
                        ForEach(running, id: \.self) { app in
                            Button(app) { Task { _ = await model.openOnMac(app: app) } }
                                .font(.system(size: 12.5, weight: .medium))
                                .buttonStyle(.glass)
                                .controlSize(.small)
                        }
                    }
                }
            }
        }
        .glassCard(tint: SamTheme.orb)
    }

    func loadState() async {
        guard let client = model.client else { return }
        do {
            let fresh = try await client.macState()
            withAnimation(.smooth) { state = fresh }
            stateError = nil
        } catch let error as BridgeError {
            if error != .cancelled { stateError = error }
        } catch {}
    }

    // MARK: Screenshot

    var screenshotCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                SectionHeader("Screen", symbol: "camera.viewfinder")
                Spacer()
                if let shotAt {
                    Text(Formatting.ago(shotAt)).font(.system(size: 12)).foregroundStyle(SamTheme.faint)
                }
            }
            if let screenshot {
                Button {
                    viewer = ViewerImage(image: screenshot, title: "Mac screen")
                } label: {
                    Image(uiImage: screenshot)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(SamTheme.line2, lineWidth: 0.6))
                        .overlay(alignment: .bottomTrailing) {
                            Image(systemName: "arrow.up.left.and.arrow.down.right")
                                .font(.system(size: 12, weight: .bold))
                                .padding(8)
                                .glassEffect(.regular, in: .circle)
                                .padding(8)
                        }
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Open the screenshot")
            } else if let shotError {
                Label(shotError.code == "screen_locked" ? "Your Mac's screen is locked." :
                        shotError.code == "screen_recording_required" ? "Allow Screen Recording for cua-driver on the Mac." :
                        shotError.errorDescription ?? "No screenshot.", systemImage: "lock.display")
                    .font(.system(size: 14)).foregroundStyle(SamTheme.amber)
            }
            Button {
                Task { await takeScreenshot() }
            } label: {
                Label(shooting ? "Taking screenshot…" : screenshot == nil ? "Take a screenshot" : "Take another",
                      systemImage: "camera.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.glassProminent)
            .controlSize(.large)
            .disabled(shooting)
        }
        .glassCard()
    }

    func takeScreenshot() async {
        guard let client = model.client, !shooting else { return }
        shooting = true
        defer { shooting = false }
        do {
            let data = try await client.screenshot()
            guard let image = UIImage(data: data) else { throw BridgeError.invalidResponse("jpeg") }
            withAnimation(.smooth) {
                screenshot = image
                shotAt = .now
                shotError = nil
            }
            Haptics.success()
        } catch let error as BridgeError {
            shotError = error
            Haptics.error()
        } catch {}
    }

    // MARK: Open

    var openCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader("Open on Mac", symbol: "arrow.up.forward.app")
            HStack(spacing: 8) {
                TextField("An app, or a link", text: $openText)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.webSearch)
                    .submitLabel(.go)
                    .onSubmit { open() }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 11)
                    .background(RoundedRectangle(cornerRadius: 16).fill(Color.black.opacity(0.25)))
                DictationButton(text: $openText)
                Button(action: open) {
                    if opening { ProgressView().controlSize(.small) } else { Image(systemName: "arrow.up.right") }
                }
                .buttonStyle(.glassProminent)
                .buttonBorderShape(.circle)
                .disabled(openText.trimmingCharacters(in: .whitespaces).isEmpty || opening)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(["Google Chrome", "T3 Code", "Notes", "Slack", "Calendar", "Messages", "Music"], id: \.self) { app in
                        Button(app) { Task { _ = await model.openOnMac(app: app) } }
                            .font(.system(size: 13, weight: .medium))
                            .buttonStyle(.glass)
                            .controlSize(.small)
                    }
                }
            }
        }
        .glassCard()
    }

    func open() {
        let value = openText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return }
        let lower = value.lowercased()
        let link = lower.hasPrefix("http://") || lower.hasPrefix("https://") || (value.contains(".") && !value.contains(" "))
        opening = true
        Task {
            let url = link && !lower.hasPrefix("http") ? "https://" + value : value
            if await model.openOnMac(app: link ? nil : value, url: link ? url : nil) { openText = "" }
            opening = false
        }
    }

    // MARK: Generate UI

    var generateCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader("Generate UI", symbol: "wand.and.sparkles")
            TextField("A chart, dashboard or diagram…", text: $prompt, axis: .vertical)
                .lineLimit(2...5)
                .padding(.horizontal, 14)
                .padding(.vertical, 11)
                .background(RoundedRectangle(cornerRadius: 16).fill(Color.black.opacity(0.25)))
            HStack {
                DictationButton(text: $prompt)
                Spacer()
                Button {
                    generate()
                } label: {
                    Label(generating ? "Designing…" : "Generate", systemImage: "sparkles")
                }
                .buttonStyle(.glassProminent)
                .disabled(prompt.trimmingCharacters(in: .whitespaces).isEmpty || generating)
            }
            if generating {
                HStack(spacing: 12) {
                    OrbView(mood: .working, halo: false).frame(width: 32, height: 32)
                    Text("Your Mac is designing it — usually under a minute.").font(.system(size: 13.5)).foregroundStyle(SamTheme.ink2)
                }
            }
            if let artifact { GeneratedResultCard(artifact: artifact) }
        }
        .glassCard()
    }

    func generate() {
        let text = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, let client = model.client else { return }
        generating = true
        artifact = nil
        Task {
            defer { generating = false }
            do {
                let id = try await client.generateUI(prompt: text)
                let result = try await client.waitForArtifact(id)
                withAnimation(.smooth) { artifact = result }
                if result.status == .ready { Haptics.success() }
            } catch {
                model.fail(error)
            }
        }
    }
}
