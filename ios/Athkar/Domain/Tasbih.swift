import Foundation

/// A phrase the tasbih counts, and how many of it makes a round.
///
/// The targets are the ones the phrases are actually said in: the tasbih after every prayer is
/// thirty-three of each, and the hundredth is لا إله إلا الله. They are defaults, not rules — the
/// user can count to whatever they like.
enum Dhikr: String, CaseIterable, Codable, Identifiable, Sendable {
    case subhanAllah
    case alhamdulillah
    case allahuAkbar
    case laIlahaIllaAllah
    case astaghfirullah
    /// Naming the Prophet obliges the salutation with it, so the phrase carries both.
    case salawat
    case salawatAal

    var id: String { rawValue }

    var arabic: String {
        switch self {
        case .subhanAllah: return "سبحان الله"
        case .alhamdulillah: return "الحمد لله"
        case .allahuAkbar: return "الله أكبر"
        case .laIlahaIllaAllah: return "لا إله إلا الله"
        case .astaghfirullah: return "أستغفر الله"
        case .salawat: return "اللهم صلِّ وسلِّم على نبينا محمد"
        case .salawatAal: return "اللهم صلِّ على محمد وعلى آل محمد"
        }
    }

    var defaultTarget: Int {
        switch self {
        case .subhanAllah, .alhamdulillah, .allahuAkbar: return 33
        case .laIlahaIllaAllah, .astaghfirullah, .salawat, .salawatAal: return 100
        }
    }

    static let `default` = Dhikr.subhanAllah

    /// A phrase written by a later version of the app reads as absent rather than as a crash.
    static func from(name: String?) -> Dhikr {
        guard let name, let dhikr = Dhikr(rawValue: name) else { return .default }
        return dhikr
    }
}

/// The state of a tasbih in progress.
///
/// `count` is the position within the current round and `rounds` how many have been completed, so
/// the two together say "the third time through, twelve in" without either number having to be
/// reconstructed from the other.
struct TasbihState: Equatable, Sendable {

    var dhikr: Dhikr = .default
    var target: Int = Dhikr.default.defaultTarget
    var count: Int = 0
    var rounds: Int = 0

    /// Offered as targets; the list the counting traditions actually use.
    static let targets = [33, 99, 100, 500, 1000]

    var isRoundComplete: Bool { count == 0 && rounds > 0 }

    /// Fraction of the current round done, for the ring.
    var progress: Double { target <= 0 ? 0 : Double(count) / Double(target) }

    /// One more.
    ///
    /// Reaching the target rolls over to a new round rather than stopping: a tasbih of a hundred is
    /// counted in rounds, and someone who has said thirty-three wants the next thirty-three, not a
    /// counter that refuses to move until they find a reset button.
    func incremented() -> TasbihState {
        var next = self
        if count + 1 >= target {
            next.count = 0
            next.rounds = rounds + 1
        } else {
            next.count = count + 1
        }
        return next
    }

    /// Clears the count and the rounds both; starting over means starting over.
    func reset() -> TasbihState {
        var next = self
        next.count = 0
        next.rounds = 0
        return next
    }

    /// Switching phrase carries its own customary target and starts a fresh count.
    func selecting(_ next: Dhikr) -> TasbihState {
        TasbihState(dhikr: next, target: next.defaultTarget, count: 0, rounds: 0)
    }

    /// A target below one would make every tap complete a round.
    func retargeted(_ value: Int) -> TasbihState {
        TasbihState(dhikr: dhikr, target: max(1, value), count: 0, rounds: 0)
    }
}
