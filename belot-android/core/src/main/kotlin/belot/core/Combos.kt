package belot.core

enum class ComboKind { SEQUENCE, CARRE }

data class Combo(val kind: ComboKind, val cards: List<Card>, val points: Int) {
    /** Highest card of a sequence, or the rank of a carré. */
    val top: Rank get() = cards.maxBy { it.rank.ordinal }.rank
    val suit: Suit? get() = if (kind == ComboKind.SEQUENCE) cards.first().suit else null

    val title: String
        get() = when (kind) {
            ComboKind.CARRE -> "Каре ${top.label}"
            ComboKind.SEQUENCE -> when (cards.size) {
                3 -> "Терц"
                4 -> "Пятьдесят"
                else -> "Сто"
            } + " до ${top.label}${suit!!.symbol}"
        }
}

object Combos {
    fun carrePoints(rank: Rank): Int = when (rank) {
        Rank.JACK -> 200
        Rank.NINE -> 150
        else -> 100
    }

    /** Carré strength for comparison: J > 9 > A > 10 > K > Q. */
    fun carreStrength(rank: Rank): Int = rank.trumpOrder

    fun sequencePoints(length: Int): Int = when {
        length >= 5 -> 100
        length == 4 -> 50
        else -> 20
    }

    /**
     * All combinations in a hand. A card used in a carré is not reused in a sequence.
     */
    fun find(hand: List<Card>): List<Combo> {
        val result = mutableListOf<Combo>()
        val used = mutableSetOf<Card>()
        for (rank in Rank.entries) {
            if (rank < Rank.NINE) continue // carrés of sevens and eights do not count
            val four = hand.filter { it.rank == rank }
            if (four.size == 4) {
                result += Combo(ComboKind.CARRE, four, carrePoints(rank))
                used += four
            }
        }
        for (suit in Suit.entries) {
            val ranks = hand.filter { it.suit == suit && it !in used }.map { it.rank.ordinal }.sorted()
            var i = 0
            while (i < ranks.size) {
                var j = i
                while (j + 1 < ranks.size && ranks[j + 1] == ranks[j] + 1) j++
                val len = j - i + 1
                if (len >= 3) {
                    val cards = (i..j).map { Card(suit, Rank.entries[ranks[it]]) }
                    result += Combo(ComboKind.SEQUENCE, cards, sequencePoints(len))
                }
                i = j + 1
            }
        }
        return result
    }

    /** Comparable key of the best sequence of a player (length, top card, is trump). */
    private fun sequenceKey(c: Combo, trump: Suit): Triple<Int, Int, Int> =
        Triple(c.cards.size, c.top.ordinal, if (c.suit == trump) 1 else 0)

    private val keyComparator = compareBy<Triple<Int, Int, Int>>({ it.first }, { it.second }, { it.third })

    /**
     * Decides which combinations count. Sequences and carrés are compared separately: only the side
     * (a player, or a team of two in the four-player game) holding the best sequence scores all of its
     * sequences, and only the side holding the best carré scores all of its carrés. Equal best sequences
     * (same length and top card, none in trump) held by different sides cancel out.
     * Returns the set of (player, combo) pairs that count.
     */
    fun winners(combos: List<List<Combo>>, trump: Suit, sideOf: (Int) -> Int = { it }): Set<Pair<Int, Combo>> {
        val counted = mutableSetOf<Pair<Int, Combo>>()

        val bestSeq = combos.mapIndexed { p, list ->
            p to list.filter { it.kind == ComboKind.SEQUENCE }.map { sequenceKey(it, trump) }.maxWithOrNull(keyComparator)
        }.filter { it.second != null }
        if (bestSeq.isNotEmpty()) {
            val top = bestSeq.maxOfWith(keyComparator) { it.second!! }
            val sides = bestSeq.filter { keyComparator.compare(it.second, top) == 0 }.map { sideOf(it.first) }.toSet()
            if (sides.size == 1) {
                val side = sides.first()
                combos.forEachIndexed { p, list ->
                    if (sideOf(p) == side) list.filter { it.kind == ComboKind.SEQUENCE }.forEach { counted += p to it }
                }
            }
        }

        val bestCarre = combos.mapIndexed { p, list ->
            p to list.filter { it.kind == ComboKind.CARRE }.maxOfOrNull { carreStrength(it.top) }
        }.filter { it.second != null }
        if (bestCarre.isNotEmpty()) {
            val side = sideOf(bestCarre.maxBy { it.second!! }.first)
            combos.forEachIndexed { p, list ->
                if (sideOf(p) == side) list.filter { it.kind == ComboKind.CARRE }.forEach { counted += p to it }
            }
        }
        return counted
    }
}
