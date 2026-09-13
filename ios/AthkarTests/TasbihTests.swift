import XCTest
@testable import Athkar

/// The counting rules, which are the whole of the tasbih worth testing: the screen is a ring and a
/// number, and everything that can be wrong about it is in here. The cases are the Android suite's,
/// so a port that counts differently fails rather than shipping.
final class TasbihTests: XCTestCase {

    func testCountingAdvancesWithinTheRound() {
        let state = TasbihState(target: 33).incremented().incremented()
        XCTAssertEqual(state.count, 2)
        XCTAssertEqual(state.rounds, 0)
    }

    func testReachingTheTargetOpensANewRoundRatherThanStopping() {
        var state = TasbihState(target: 3)
        for _ in 0..<3 { state = state.incremented() }
        XCTAssertEqual(state.count, 0, "the count restarts")
        XCTAssertEqual(state.rounds, 1, "and the round is banked")
    }

    func testRoundsAccumulateAcrossManyPasses() {
        var state = TasbihState(target: 33)
        for _ in 0..<(33 * 3) { state = state.incremented() }
        XCTAssertEqual(state.rounds, 3)
        XCTAssertEqual(state.count, 0)
    }

    func testResetClearsTheCountAndTheRoundsTogether() {
        var state = TasbihState(target: 3)
        for _ in 0..<7 { state = state.incremented() }
        let cleared = state.reset()
        XCTAssertEqual(cleared.count, 0)
        XCTAssertEqual(cleared.rounds, 0)
        XCTAssertEqual(cleared.dhikr, state.dhikr, "the phrase is not a thing reset undoes")
        XCTAssertEqual(cleared.target, state.target, "nor the target")
    }

    func testChoosingAPhraseBringsItsOwnTargetAndStartsFresh() {
        let state = TasbihState(target: 33)
            .incremented()
            .incremented()
            .selecting(.laIlahaIllaAllah)
        XCTAssertEqual(state.dhikr, .laIlahaIllaAllah)
        XCTAssertEqual(state.target, 100)
        XCTAssertEqual(state.count, 0)
        XCTAssertEqual(state.rounds, 0)
    }

    func testATargetBelowOneIsRefusedOrEveryTapWouldCompleteARound() {
        XCTAssertEqual(TasbihState().retargeted(0).target, 1)
        XCTAssertEqual(TasbihState().retargeted(-40).target, 1)
    }

    func testProgressRunsFromEmptyToFullWithinARound() {
        let state = TasbihState(target: 4)
        XCTAssertEqual(state.progress, 0, accuracy: 0.0001)
        XCTAssertEqual(state.incremented().incremented().progress, 0.5, accuracy: 0.0001)

        // The fourth tap banks the round and the ring starts over rather than sitting full.
        var full = state
        for _ in 0..<4 { full = full.incremented() }
        XCTAssertEqual(full.progress, 0, accuracy: 0.0001)
        XCTAssertTrue(full.isRoundComplete)
    }

    func testAnUnknownStoredPhraseFallsBackInsteadOfCrashing() {
        XCTAssertEqual(Dhikr.from(name: nil), Dhikr.default)
        XCTAssertEqual(Dhikr.from(name: "aPhraseFromALaterVersion"), Dhikr.default)
        for dhikr in Dhikr.allCases {
            XCTAssertEqual(Dhikr.from(name: dhikr.rawValue), dhikr)
        }
    }

    func testEveryPhraseCarriesAUsableTarget() {
        for dhikr in Dhikr.allCases {
            XCTAssertGreaterThanOrEqual(dhikr.defaultTarget, 1, "\(dhikr) would complete on every tap")
            XCTAssertFalse(dhikr.arabic.isEmpty, "\(dhikr) has nothing to show")
        }
    }

    func testTheSalawatIsCountedInFull() {
        // Half a formula was what shipped first: naming the Prophet obliges the salutation with it.
        XCTAssertTrue(Dhikr.salawat.arabic.contains("وسلِّم"))
        XCTAssertTrue(Dhikr.salawatAal.arabic.contains("آل محمد"))
    }
}

/// The palette that changes with the hour, and the two ways it can be chosen: from the user's own
/// prayer times, or — before a place is known — from the clock.
final class SkyPhaseTests: XCTestCase {

    func testEachPrayerBringsItsOwnSky() {
        XCTAssertEqual(SkyPhase.forPrayer(.fajr), .dawn)
        XCTAssertEqual(SkyPhase.forPrayer(.sunrise), .sunrise)
        XCTAssertEqual(SkyPhase.forPrayer(.dhuhr), .noon)
        XCTAssertEqual(SkyPhase.forPrayer(.asr), .afternoon)
        XCTAssertEqual(SkyPhase.forPrayer(.maghrib), .sunset)
        XCTAssertEqual(SkyPhase.forPrayer(.isha), .night)
        XCTAssertEqual(SkyPhase.forPrayer(nil), .nightEnd)
    }

    func testEveryHourOfTheClockHasASky() {
        for hour in 0...23 {
            _ = SkyPhase.forHour(hour)
        }
        XCTAssertEqual(SkyPhase.forHour(1), .night)
        XCTAssertEqual(SkyPhase.forHour(12), .noon)
        XCTAssertEqual(SkyPhase.forHour(16), .afternoon)
        XCTAssertEqual(SkyPhase.forHour(18), .sunset)
        XCTAssertEqual(SkyPhase.forHour(23), .night)
    }

    func testTheDaylightHoursAreNotNight() {
        // This is what flips the whole app between a light page and a dark one, so a phase on the
        // wrong side of it is not a shade too warm — it is the reader's eyes at four in the morning.
        XCTAssertFalse(SkyPhase.sunrise.isNight)
        XCTAssertFalse(SkyPhase.noon.isNight)
        XCTAssertFalse(SkyPhase.afternoon.isNight)
        XCTAssertTrue(SkyPhase.sunset.isNight)
        XCTAssertTrue(SkyPhase.night.isNight)
        XCTAssertTrue(SkyPhase.nightEnd.isNight)
        XCTAssertTrue(SkyPhase.dawn.isNight)
    }

    func testEverySkyIsNamable() {
        for phase in SkyPhase.allCases {
            XCTAssertFalse(phase.arabicName.isEmpty, "\(phase) has nothing to call itself")
        }
    }
}
