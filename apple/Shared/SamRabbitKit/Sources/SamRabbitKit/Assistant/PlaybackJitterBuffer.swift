import Foundation

/// A small buffer in front of the reply's player. The realtime voice reaches the watch at the pace it is spoken
/// (WebRTC on the Mac, 100 ms frames), so any delay on the way (Wi-Fi power saving, the iPhone relay's round trips)
/// would be a gap in the voice if each frame played the moment it arrived. Playback starts once `target` of audio is
/// waiting, `maxWait` after the first frame, or when the stream ends, whichever comes first; after that frames go
/// straight to the player. Running dry in the middle of a reply (`underrun`) buffers again.
///
/// Pure (the caller passes the time), so the rules are unit-tested; `AudioGraph` on the watch owns one.
public struct PlaybackJitterBuffer: Sendable, Equatable {
    /// Audio waiting before playback starts (seconds).
    public var target: TimeInterval
    /// The longest the first frame waits for the rest.
    public var maxWait: TimeInterval
    public let bytesPerSecond: Int

    private var held: [Data] = []
    private var heldBytes = 0
    private var firstAt: Date?
    /// Playback started: frames pass straight through.
    public private(set) var primed = false

    public init(target: TimeInterval = 0.2, maxWait: TimeInterval = 0.35,
                bytesPerSecond: Int = AssistantAudioFormat.bytesPerSecond) {
        self.target = target
        self.maxWait = maxWait
        self.bytesPerSecond = bytesPerSecond
    }

    /// Frames are waiting.
    public var isHolding: Bool { !held.isEmpty }

    /// Seconds of audio waiting.
    public var heldSeconds: Double { Double(heldBytes) / Double(bytesPerSecond) }

    /// When what waits must start playing even if nothing more comes (nil while nothing waits).
    public var deadline: Date? { held.isEmpty ? nil : firstAt?.addingTimeInterval(maxWait) }

    /// A frame (PCM16LE, mono, 16 kHz) arrived: returns what to play now, in order (nothing while it fills).
    public mutating func add(_ pcm: Data, at now: Date) -> [Data] {
        guard !pcm.isEmpty else { return [] }
        if primed { return [pcm] }
        if firstAt == nil { firstAt = now }
        held.append(pcm)
        heldBytes += pcm.count
        let enough = Double(heldBytes) >= target * Double(bytesPerSecond)
        let waited = firstAt.map { now.timeIntervalSince($0) >= maxWait } ?? false
        return enough || waited ? release() : []
    }

    /// The first frame waited `maxWait`: what waits plays now (nothing before the deadline).
    public mutating func tick(at now: Date) -> [Data] {
        guard let deadline, now >= deadline else { return [] }
        return release()
    }

    /// The stream ended: what waits plays now, and the next reply buffers from scratch.
    public mutating func finish() -> [Data] {
        let rest = held
        reset()
        return rest
    }

    /// Playback ran dry with nothing waiting while the reply goes on: the next frames buffer again.
    public mutating func underrun() {
        guard held.isEmpty else { return }
        primed = false
        firstAt = nil
    }

    /// Drops what waits (barge-in, a failed turn, another sound).
    public mutating func reset() {
        held = []
        heldBytes = 0
        firstAt = nil
        primed = false
    }

    private mutating func release() -> [Data] {
        let out = held
        held = []
        heldBytes = 0
        firstAt = nil
        primed = true
        return out
    }
}
