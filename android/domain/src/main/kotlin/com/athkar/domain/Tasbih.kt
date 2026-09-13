package com.athkar.domain

import kotlinx.coroutines.flow.Flow

/**
 * A phrase the tasbih counts, and how many of it makes a round.
 *
 * The targets are the ones the phrases are actually said in: the tasbih after every prayer is
 * thirty-three of each, and the hundredth is لا إله إلا الله. They are defaults, not rules — the
 * user can count to whatever they like.
 */
enum class Dhikr(val arabic: String, val defaultTarget: Int) {
    SUBHAN_ALLAH("سبحان الله", 33),
    ALHAMDULILLAH("الحمد لله", 33),
    ALLAHU_AKBAR("الله أكبر", 33),
    LA_ILAHA_ILLA_ALLAH("لا إله إلا الله", 100),
    ASTAGHFIRULLAH("أستغفر الله", 100),
    SALAWAT("اللهم صلِّ على محمد", 100),
    ;

    companion object {
        val DEFAULT = SUBHAN_ALLAH

        fun fromName(name: String?): Dhikr = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * The state of a tasbih in progress.
 *
 * [count] is the position within the current round and [rounds] how many have been completed, so
 * the two together say "the third time through, twelve in" without either number having to be
 * reconstructed from the other.
 */
data class TasbihState(
    val dhikr: Dhikr = Dhikr.DEFAULT,
    val target: Int = Dhikr.DEFAULT.defaultTarget,
    val count: Int = 0,
    val rounds: Int = 0,
) {
    val isRoundComplete: Boolean get() = count == 0 && rounds > 0

    /** Fraction of the current round done, for the ring. */
    val progress: Float get() = if (target <= 0) 0f else count.toFloat() / target

    /**
     * One more.
     *
     * Reaching the target rolls over to a new round rather than stopping: a tasbih of a hundred is
     * counted in rounds, and someone who has said thirty-three wants the next thirty-three, not a
     * counter that refuses to move until they find a reset button.
     */
    fun increment(): TasbihState =
        if (count + 1 >= target) copy(count = 0, rounds = rounds + 1)
        else copy(count = count + 1)

    /** Clears the count and the rounds both; starting over means starting over. */
    fun reset(): TasbihState = copy(count = 0, rounds = 0)

    /** Switching phrase carries its own customary target and starts a fresh count. */
    fun select(next: Dhikr): TasbihState =
        TasbihState(dhikr = next, target = next.defaultTarget)

    /** A target below one would make every tap complete a round. */
    fun retarget(next: Int): TasbihState =
        copy(target = next.coerceAtLeast(1), count = 0, rounds = 0)

    companion object {
        /** Offered as targets; the list the counting traditions actually use. */
        val TARGETS = listOf(33, 99, 100, 500, 1000)
    }
}

/**
 * Port for the tasbih that survives leaving the screen.
 *
 * Unlike the per-dhikr tallies inside a chapter — which belong to one sitting and die with it —
 * this one is a device the user picks up and puts down. Someone a hundred and forty into a thousand
 * expects to find a hundred and forty when they come back.
 */
interface TasbihRepository {
    fun observe(): Flow<TasbihState>
    suspend fun save(state: TasbihState)
}
