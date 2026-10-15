package belot.core

enum class Suit(val code: Char, val symbol: String, val red: Boolean, val title: String) {
    SPADES('S', "♠", false, "Пики"),
    HEARTS('H', "♥", true, "Черви"),
    CLUBS('C', "♣", false, "Трефы"),
    DIAMONDS('D', "♦", true, "Бубны");

    companion object {
        fun fromCode(c: Char): Suit = entries.first { it.code == c }
    }
}

/**
 * Ranks of the 24-card deck (9..A). Declaration order is the natural sequence order
 * used for tierce/quarte/quinte: 9, 10, J, Q, K, A.
 */
enum class Rank(
    val code: Char,
    val label: String,
    /** Strength in a non-trump suit: 9 < J < Q < K < 10 < A. */
    val plainOrder: Int,
    /** Strength in the trump suit: Q < K < 10 < A < 9 < J. */
    val trumpOrder: Int,
    val plainPoints: Int,
    val trumpPoints: Int,
) {
    NINE('9', "9", 0, 6, 0, 14),
    TEN('T', "10", 4, 4, 10, 10),
    JACK('J', "В", 1, 7, 2, 20),
    QUEEN('Q', "Д", 2, 2, 3, 3),
    KING('K', "К", 3, 3, 4, 4),
    ACE('A', "Т", 5, 5, 11, 11);

    companion object {
        fun fromCode(c: Char): Rank = entries.first { it.code == c }
    }
}

data class Card(val suit: Suit, val rank: Rank) {
    val code: String get() = "${rank.code}${suit.code}"

    fun points(trump: Suit?): Int = if (suit == trump) rank.trumpPoints else rank.plainPoints

    /** Strength inside its own suit given the trump suit. */
    fun order(trump: Suit?): Int = if (suit == trump) rank.trumpOrder else rank.plainOrder

    override fun toString(): String = "${rank.label}${suit.symbol}"

    companion object {
        fun parse(code: String): Card {
            require(code.length == 2) { "Bad card code: $code" }
            return Card(Suit.fromCode(code[1]), Rank.fromCode(code[0]))
        }

        fun deck24(): List<Card> = Suit.entries.flatMap { s -> Rank.entries.map { r -> Card(s, r) } }
    }
}

/** Sorts a hand for display: suits alternate in colour, trump first, strongest card first. */
fun sortHand(cards: List<Card>, trump: Suit?): List<Card> {
    val suitOrder = listOf(Suit.SPADES, Suit.HEARTS, Suit.CLUBS, Suit.DIAMONDS)
    return cards.sortedWith(
        compareBy<Card>({ if (it.suit == trump) -1 else suitOrder.indexOf(it.suit) }, { -it.order(trump) })
    )
}
