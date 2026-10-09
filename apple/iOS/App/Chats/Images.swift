import SamRabbitKit
import SwiftUI
import UIKit

/// Images from the bridge (timeline blobs, generated-UI previews, screenshots), cached in memory.
@MainActor
final class ImageStore {
    static let shared = ImageStore()
    private let cache = NSCache<NSString, UIImage>()

    init() { cache.totalCostLimit = 60 * 1024 * 1024 }

    func cached(_ key: String) -> UIImage? { cache.object(forKey: key as NSString) }

    func image(_ key: String, load: () async throws -> Data) async throws -> UIImage {
        if let image = cached(key) { return image }
        let data = try await load()
        guard let image = UIImage(data: data) else { throw BridgeError.invalidResponse("image") }
        let prepared = await image.byPreparingForDisplay() ?? image
        cache.setObject(prepared, forKey: key as NSString, cost: data.count)
        return prepared
    }
}

/// A timeline image (`/v1/mobile/blobs/<sha256>`).
struct BlobImage: View {
    @Environment(AppModel.self) private var model
    let blobId: String
    var aspect: CGFloat?
    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        RemoteImageFrame(image: image, failed: failed, aspect: aspect)
            .task(id: blobId) {
                guard image == nil, let client = model.client else { return }
                do {
                    image = try await ImageStore.shared.image("blob:\(blobId)") { try await client.blob(blobId) }
                } catch {
                    failed = true
                }
            }
    }
}

/// A generated UI's preview (`/v1/mobile/ui/artifacts/<id>/image`).
struct ArtifactImage: View {
    @Environment(AppModel.self) private var model
    let artifactId: String
    var blobId: String?
    var aspect: CGFloat?
    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        RemoteImageFrame(image: image, failed: failed, aspect: aspect)
            .task(id: artifactId) {
                guard image == nil, let client = model.client else { return }
                do {
                    if let blobId {
                        image = try await ImageStore.shared.image("blob:\(blobId)") { try await client.blob(blobId) }
                    } else {
                        image = try await ImageStore.shared.image("ui:\(artifactId)") { try await client.artifactImage(artifactId) }
                    }
                } catch {
                    failed = true
                }
            }
    }
}

struct RemoteImageFrame: View {
    let image: UIImage?
    let failed: Bool
    var aspect: CGFloat?

    var body: some View {
        let ratio = aspect ?? image.map { $0.size.width / max($0.size.height, 1) } ?? 1.5
        ZStack {
            RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.black.opacity(0.3))
            if let image {
                Image(uiImage: image).resizable().aspectRatio(contentMode: .fill)
                    .transition(.opacity)
            } else if failed {
                Image(systemName: "photo.badge.exclamationmark").font(.title2).foregroundStyle(SamTheme.faint)
            } else {
                ProgressView().tint(SamTheme.orbPale)
            }
        }
        .aspectRatio(ratio, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(SamTheme.line2, lineWidth: 0.6))
        .animation(.smooth, value: image != nil)
    }
}

/// Full-screen image with pinch to zoom, double tap to zoom and drag to pan.
struct ZoomableImageViewer: View {
    let image: UIImage
    var title: String = ""
    @Environment(\.dismiss) private var dismiss
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    var body: some View {
        NavigationStack {
            GeometryReader { geometry in
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .scaleEffect(scale)
                    .offset(offset)
                    .frame(width: geometry.size.width, height: geometry.size.height)
                    .contentShape(.rect)
                    .gesture(MagnifyGesture()
                        .onChanged { value in scale = min(6, max(1, lastScale * value.magnification)) }
                        .onEnded { _ in
                            lastScale = scale
                            if scale <= 1.01 { withAnimation(.smooth) { offset = .zero; lastOffset = .zero } }
                        })
                    .simultaneousGesture(DragGesture()
                        .onChanged { value in
                            guard scale > 1 else { return }
                            offset = CGSize(width: lastOffset.width + value.translation.width,
                                            height: lastOffset.height + value.translation.height)
                        }
                        .onEnded { _ in lastOffset = offset })
                    .onTapGesture(count: 2) {
                        withAnimation(.spring(duration: 0.35)) {
                            if scale > 1 {
                                scale = 1
                                offset = .zero
                            } else {
                                scale = 2.5
                            }
                            lastScale = scale
                            lastOffset = offset
                        }
                    }
            }
            .background(Color.black)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }
                }
                ToolbarItem(placement: .primaryAction) {
                    ShareLink(item: Image(uiImage: image), preview: SharePreview(title.isEmpty ? "Image" : title,
                                                                                  image: Image(uiImage: image)))
                }
            }
        }
        .preferredColorScheme(.dark)
    }
}
