import Foundation
import Testing
import MediCabinetCoreTestSupport
@testable import MediCabinetCore

/// The decoder meets untrusted bytes: whatever they are, it returns an archive or throws an
/// `ArchiveError`, never anything else and never a crash (V4). `decode` declares `throws(ArchiveError)`,
/// so reaching the end of these loops already proves the "nothing else"; a trap would stop the run.
@Suite struct ArchiveFuzzTests {
    private func outcome(_ bytes: [UInt8]) -> Bool {
        do {
            _ = try ArchiveDecoder.decode(bytes)
            return true
        } catch {
            return false
        }
    }

    @Test func everyTruncationOfTheGoldenFileIsRefusedExceptTheWholeFile() throws {
        let golden = try SharedFixtures.goldenArchive()
        // Without its final newline the file is still the same JSON document.
        for length in 0..<(golden.count - 1) {
            #expect(!outcome(Array(golden[0..<length])), "a \(length)-byte prefix was accepted")
        }
        #expect(outcome(Array(golden.dropLast())))
        #expect(outcome(golden))
    }

    @Test func everySingleByteMutationOfTheGoldenFileIsHandled() throws {
        let golden = try SharedFixtures.goldenArchive()
        let structural: [UInt8] = [0x00, 0x22, 0x5C, 0x7B, 0x7D, 0x2C, 0x3A, 0x30, 0x80, 0xFF]
        var random = SeededGenerator(seed: 99)
        var accepted = 0
        for position in 0..<golden.count {
            // One structural byte (rotating) and one random byte at every position.
            for replacement in [structural[position % structural.count], UInt8.random(in: 0...255, using: &random)] {
                var mutated = golden
                mutated[position] = replacement
                if outcome(mutated) { accepted += 1 }
            }
        }
        // Many mutations are harmless (a letter inside a name); the point is that none crashed.
        #expect(accepted > 0)
    }

    @Test(arguments: Array(1...300))
    func randomBytesAreRefused(_ seed: Int) {
        var random = SeededGenerator(seed: UInt64(seed))
        let bytes = (0..<Int.random(in: 0...400, using: &random)).map { _ in UInt8.random(in: 0...255, using: &random) }
        #expect(!outcome(bytes))
    }

    @Test(arguments: Array(1...60))
    func randomBytesAfterAValidHeaderAreHandled(_ seed: Int) {
        var random = SeededGenerator(seed: UInt64(seed))
        let tail = (0..<Int.random(in: 0...300, using: &random)).map { _ in UInt8.random(in: 0x20...0x7E, using: &random) }
        let bytes = Array("{\"archive\":\"medicabinet\",\"formatVersion\":1,".utf8) + tail
        #expect(!outcome(bytes))
    }

    @Test func deeplyNestedValuesAreNeverRead() {
        let nested = String(repeating: "[", count: 100_000) + String(repeating: "]", count: 100_000)
        let bytes = Array("{\"archive\":\"medicabinet\",\"formatVersion\":1,\"exportedAt\":\(nested)}".utf8)
        #expect(throws: ArchiveError(.invalidStructure, path: "exportedAt")) { try ArchiveDecoder.decode(bytes) }
    }
}
