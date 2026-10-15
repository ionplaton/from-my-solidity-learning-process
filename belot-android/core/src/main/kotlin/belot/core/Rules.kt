package belot.core

data class Rules(
    /** The game ends when someone reaches this many points. */
    val target: Int = 501,
    /**
     * true: the player who chose trump must score more than EACH opponent.
     * false: the player who chose trump must score more than half of all points of the deal.
     */
    val takerMustBeatEach: Boolean = true,
    /** true: a player who cannot follow suit must play a trump even if it cannot beat a trump already on the table. */
    val mustUnderTrump: Boolean = true,
) {
    companion object {
        val TARGETS = listOf(301, 501, 1001)
        const val LAST_TRICK_BONUS = 10
        const val CAPOT_BONUS = 90
        const val BELOTE_POINTS = 20
        const val HAND_SIZE = 8
    }
}
