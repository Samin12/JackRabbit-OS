import Foundation

/// 16-bit PCM helpers: little-endian bytes, floats, loudness.
public enum PCM16 {
    /// Little-endian 16-bit samples from bytes (an odd last byte is ignored).
    public static func samples(_ data: Data) -> [Int16] {
        let count = data.count / 2
        var samples = [Int16](repeating: 0, count: count)
        data.withUnsafeBytes { raw in
            for index in 0..<count {
                let low = UInt16(raw[index * 2])
                let high = UInt16(raw[index * 2 + 1])
                samples[index] = Int16(bitPattern: low | high << 8)
            }
        }
        return samples
    }

    /// Little-endian bytes of 16-bit samples.
    public static func data(_ samples: [Int16]) -> Data {
        var data = Data(count: samples.count * 2)
        data.withUnsafeMutableBytes { raw in
            for (index, sample) in samples.enumerated() {
                let bits = UInt16(bitPattern: sample)
                raw[index * 2] = UInt8(bits & 0xFF)
                raw[index * 2 + 1] = UInt8(bits >> 8)
            }
        }
        return data
    }

    /// Floats in -1...1 (for an `AVAudioPCMBuffer`).
    public static func floats(_ data: Data) -> [Float] {
        samples(data).map { Float($0) / 32768 }
    }

    /// 16-bit samples from floats in -1...1 (clipped).
    public static func samples(fromFloats floats: [Float]) -> [Int16] {
        floats.map { value in
            let scaled = (value.isFinite ? value : 0) * 32767
            return Int16(max(-32768, min(32767, scaled.rounded())))
        }
    }

    /// The RMS level in dBFS (-160 for silence).
    public static func dbfs<C: Collection>(_ samples: C) -> Double where C.Element == Int16 {
        guard !samples.isEmpty else { return -160 }
        var sum = 0.0
        for sample in samples {
            let value = Double(sample) / 32768
            sum += value * value
        }
        let rms = (sum / Double(samples.count)).squareRoot()
        return rms > 0 ? max(-160, 20 * log10(rms)) : -160
    }

    /// The RMS level in dBFS of floats in -1...1.
    public static func dbfs(floats samples: UnsafeBufferPointer<Float>) -> Double {
        guard !samples.isEmpty else { return -160 }
        var sum: Float = 0
        for sample in samples { sum += sample * sample }
        let rms = Double((sum / Float(samples.count)).squareRoot())
        return rms > 0 ? max(-160, 20 * log10(rms)) : -160
    }

    /// Seconds of 16 kHz mono PCM16 in `bytes`.
    public static func seconds(bytes: Int, sampleRate: Int = AssistantAudioFormat.sampleRate) -> Double {
        Double(bytes) / Double(2 * sampleRate)
    }
}

/// RIFF/WAVE with 16-bit PCM: how an utterance goes up, and how the realtime brain's buffered answer comes down.
public enum WAV {
    public struct Decoded: Sendable, Equatable {
        public var sampleRate: Int
        public var channels: Int
        /// Little-endian 16-bit samples, interleaved when there is more than one channel.
        public var pcm: Data
    }

    public enum Problem: Error, Equatable {
        case notWAV
        case unsupported(String)
        case truncated
    }

    public static let contentType = "audio/wav"

    /// A canonical 44-byte header and the samples: PCM, 16 bits.
    public static func encode(pcm: Data, sampleRate: Int = AssistantAudioFormat.sampleRate, channels: Int = 1) -> Data {
        var data = Data(capacity: 44 + pcm.count)
        func u32(_ value: UInt32) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
        func u16(_ value: UInt16) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
        let blockAlign = channels * 2
        data.append(contentsOf: Array("RIFF".utf8))
        u32(UInt32(36 + pcm.count))
        data.append(contentsOf: Array("WAVE".utf8))
        data.append(contentsOf: Array("fmt ".utf8))
        u32(16)
        u16(1) // PCM
        u16(UInt16(channels))
        u32(UInt32(sampleRate))
        u32(UInt32(sampleRate * blockAlign))
        u16(UInt16(blockAlign))
        u16(16)
        data.append(contentsOf: Array("data".utf8))
        u32(UInt32(pcm.count))
        data.append(pcm)
        return data
    }

    public static func encode(samples: [Int16], sampleRate: Int = AssistantAudioFormat.sampleRate) -> Data {
        encode(pcm: PCM16.data(samples), sampleRate: sampleRate)
    }

    public static func isWAV(_ data: Data) -> Bool {
        data.count >= 12 && data.prefix(4) == Data("RIFF".utf8) && data.dropFirst(8).prefix(4) == Data("WAVE".utf8)
    }

    /// Reads a 16-bit PCM WAV (plain or WAVE_FORMAT_EXTENSIBLE), skipping other chunks (`LIST`, `fact`, ...).
    public static func decode(_ data: Data) throws -> Decoded {
        guard isWAV(data) else { throw Problem.notWAV }
        let bytes = [UInt8](data)
        // 32-bit safe (Apple Watch is arm64_32): read as UInt32, clamp into Int.
        func raw32(_ at: Int) -> UInt32 {
            UInt32(bytes[at]) | UInt32(bytes[at + 1]) << 8 | UInt32(bytes[at + 2]) << 16 | UInt32(bytes[at + 3]) << 24
        }
        func u32(_ at: Int) -> Int { Int(clamping: raw32(at)) }
        func u16(_ at: Int) -> Int { Int(bytes[at]) | Int(bytes[at + 1]) << 8 }
        var offset = 12
        var format: (rate: Int, channels: Int, bits: Int, tag: Int)?
        while offset + 8 <= bytes.count {
            let id = String(decoding: bytes[offset..<(offset + 4)], as: UTF8.self)
            let rawSize = raw32(offset + 4)
            let size = u32(offset + 4)
            let body = offset + 8
            switch id {
            case "fmt ":
                guard size >= 16, body + 16 <= bytes.count else { throw Problem.truncated }
                var tag = u16(body)
                if tag == 0xFFFE, size >= 40, body + 26 <= bytes.count { tag = u16(body + 24) } // the sub-format's GUID
                format = (u32(body + 4), u16(body + 2), u16(body + 14), tag)
            case "data":
                guard let format else { throw Problem.unsupported("data before fmt") }
                guard format.tag == 1 else { throw Problem.unsupported("format \(format.tag)") }
                guard format.bits == 16 else { throw Problem.unsupported("\(format.bits)-bit") }
                guard format.channels >= 1, format.rate > 0 else { throw Problem.unsupported("format") }
                // A streamed WAV may say 0 or 0xFFFFFFFF: take what is there.
                let end = rawSize == 0 || rawSize == UInt32.max || size > bytes.count - body ? bytes.count : body + size
                let usable = (end - body) / (2 * format.channels) * (2 * format.channels)
                return Decoded(sampleRate: format.rate, channels: format.channels,
                               pcm: Data(bytes[body..<(body + max(0, usable))]))
            default:
                break
            }
            guard size <= bytes.count - body else { break }
            offset = body + size + (size & 1)
        }
        throw format == nil ? Problem.notWAV : Problem.truncated
    }
}
