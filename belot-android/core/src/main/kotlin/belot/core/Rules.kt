package belot.core

data class Rules(
    /** Number of players: 2, 3 or 4 (four players play two against two). */
    val players: Int = 3,
    /** The game ends when someone reaches this many points. */
    val target: Int = 501,
    /**
     * Three players only. true: the player who chose trump must score more than EACH opponent.
     * false: the player who chose trump must score more than half of all points of the deal.
     * With two or four players the taking side must always score more than the other side.
     */
    val takerMustBeatEach: Boolean = true,
    /** true: a player who cannot follow suit must play a trump even if it cannot beat a trump already on the table. */
    val mustUnderTrump: Boolean = true,
) {
    companion object {
        val TARGETS = listOf(301, 501, 1001)
        val PLAYER_COUNTS = listOf(2, 3, 4)
        const val LAST_TRICK_BONUS = 10
        const val CAPOT_BONUS = 90
        const val BELOTE_POINTS = 20
    }
}
