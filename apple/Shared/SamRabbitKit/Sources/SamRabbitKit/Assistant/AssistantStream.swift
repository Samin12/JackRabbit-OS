import Foundation

/// The body of a streamed turn (`Content-Type: application/x-samrabbit-stream`, chunked): a run of frames, each
/// 1 byte of type, a 4-byte big-endian length and that many bytes of payload.
///
/// * `'J'`: one UTF-8 JSON event (`AssistantEvent`)
/// * `'A'`: PCM16LE mono 16 kHz audio, about 100–200 ms per frame
///
/// Frames of other types are skipped (a newer bridge may add some). The frames may arrive split anywhere.
public enum AssistantStream {
    public static let contentType = "application/x-samrabbit-stream"
    public static let event: UInt8 = 0x4A // 'J'
    public static let audio: UInt8 = 0x41 // 'A'
    public static let headerSize = 5
    /// A frame larger than this is refused (a broken stream, not a real frame).
    public static let maxPayload = 1 << 22

    /// One frame on the wire.
    /// A frame's 4-byte big-endian length.
    public static func length(_ b0: UInt8, _ b1: UInt8, _ b2: UInt8, _ b3: UInt8) -> UInt32 {
        UInt32(b0) << 24 | UInt32(b1) << 16 | UInt32(b2) << 8 | UInt32(b3)
    }

    public static func frame(type: UInt8, _ payload: Data) -> Data {
        var data = Data(capacity: headerSize + payload.count)
        data.append(type)
        let length = UInt32(clamping: payload.count).bigEndian
        withUnsafeBytes(of: length) { data.append(contentsOf: $0) }
        data.append(payload)
        return data
    }

    public static func frame(_ event: AssistantEvent) -> Data { frame(type: Self.event, event.json) }

    public static func audioFrame(_ pcm: Data) -> Data { frame(type: Self.audio, pcm) }
}

/// Reads frames as the bytes come in (any split, several frames per chunk).
public struct AssistantStreamParser: Sendable {
    public enum Frame: Sendable, Equatable {
        case event(Data)
        case audio(Data)
        case other(type: UInt8, Data)
    }

    private var buffer = Data()
    private var cursor = 0
    public private(set) var framesRead = 0

    public init() {}

    /// Bytes received but not yet a whole frame.
    public var pending: Int { buffer.count - cursor }

    /// Adds bytes and returns every frame they completed. Throws when a frame announces an impossible length.
    public mutating func feed(_ bytes: Data) throws -> [Frame] {
        if !bytes.isEmpty { buffer.append(bytes) }
        var frames: [Frame] = []
        while buffer.count - cursor >= AssistantStream.headerSize {
            let start = buffer.startIndex + cursor
            let type = buffer[start]
            // Read as UInt32 and checked before it becomes an `Int`: on the Apple Watch (arm64_32) `Int` is 32 bits,
            // so `Int(byte) << 24` of a first byte >= 0x80 is negative and a negative length would trap the slice.
            let declared = AssistantStream.length(buffer[start + 1], buffer[start + 2], buffer[start + 3], buffer[start + 4])
            guard declared <= UInt32(AssistantStream.maxPayload) else {
                throw AssistantStreamError.frameTooLarge(Int(clamping: declared))
            }
            let length = Int(declared)
            guard buffer.count - cursor - AssistantStream.headerSize >= length else { break }
            let payloadStart = start + AssistantStream.headerSize
            let payload = Data(buffer[payloadStart..<(payloadStart + length)])
            cursor += AssistantStream.headerSize + length
            framesRead += 1
            switch type {
            case AssistantStream.event: frames.append(.event(payload))
            case AssistantStream.audio: frames.append(.audio(payload))
            default: frames.append(.other(type: type, payload))
            }
        }
        // Drop what was read once it is a fair share of the buffer (keeps appends cheap).
        if cursor > 0, cursor >= 64 * 1024 || cursor == buffer.count {
            buffer.removeFirst(cursor)
            cursor = 0
        }
        return frames
    }

    /// The items of the frames `bytes` completed: events decoded, audio passed on, unknown types, unreadable events
    /// and the bridge's keep-alive (`{"type":"ping"}`, while a tool runs) skipped.
    public mutating func items(_ bytes: Data) throws -> [AssistantStreamItem] {
        try feed(bytes).compactMap { frame in
            switch frame {
            case .event(let json):
                switch try? AssistantEvent(json: json) {
                case nil, .unknown("ping"): nil
                case let event?: .event(event)
                }
            case .audio(let pcm): pcm.isEmpty ? nil : .audio(pcm)
            case .other: nil
            }
        }
    }
}
