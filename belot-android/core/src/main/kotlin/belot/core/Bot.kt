package belot.core

/** A simple computer player. It only looks at its own cards and at what has been played. */
object Bot {
    data class BidChoice(val take: Boolean, val suit: Suit?)

    private fun strength(cards: List<Card>, trump: Suit): Double {
        var s = 0.0
        val trumps = cards.filter { it.suit == trump }
        for (c in trumps) {
            s += when (c.rank) {
                Rank.JACK -> 3.0
                Rank.NINE -> 2.2
                Rank.ACE -> 1.2
                Rank.TEN -> 1.0
                Rank.KING -> 0.6
                Rank.QUEEN -> 0.5
                Rank.EIGHT, Rank.SEVEN -> 0.3
            }
        }
        if (trumps.size > 2) s += 0.5 * (trumps.size - 2)
        for (suit in Suit.entries) {
            if (suit == trump) continue
            val side = cards.filter { it.suit == suit }
            if (side.any { it.rank == Rank.ACE }) {
                s += 1.0
                if (side.any { it.rank == Rank.TEN }) s += 0.5
            }
        }
        if (cards.contains(Card(trump, Rank.KING)) && cards.contains(Card(trump, Rank.QUEEN))) s += 0.7
        return s
    }

    private fun threshold(game: BelotGame) = when (game.playerCount) {
        2 -> 5.2   // more cards are still to come, the bot sees only half of its hand
        4 -> 4.2   // the partner helps
        else -> 4.6
    }

    fun chooseBid(game: BelotGame, seat: Int): BidChoice {
        val hand = game.hands[seat]
        val turned = game.turnedCard!!
        return if (game.bidRound == 1) {
            BidChoice(strength(hand + turned, turned.suit) >= threshold(game), null)
        } else {
            val best = Suit.entries.filter { it != turned.suit }.maxBy { strength(hand, it) }
            BidChoice(strength(hand, best) >= threshold(game), best)
        }
    }

    fun chooseCard(game: BelotGame, seat: Int): Card {
        val legal = game.legalCards(seat)
        if (legal.size == 1) return legal[0]
        val trump = game.trump!!
        val trick = game.trick

        if (trick.isEmpty()) return lead(game, seat, legal, trump)

        val winnerPlay = trick[game.trickWinnerIndex()]
        val winningNow = winnerPlay.card
        val last = trick.size == game.playerCount - 1
        val partnerWins = winnerPlay.player == game.partnerOf(seat)

        if (partnerWins && (last || isMaster(game, winningNow, trump))) {
            // The trick is ours: add points with a non-trump card, but keep aces that are still masters.
            val plain = legal.filter { it.suit != trump }
            if (plain.isNotEmpty()) return plain.maxBy { it.points(trump) * 2 - (if (isMaster(game, it, trump)) 15 else 0) }
            return cheapest(legal, trump)
        }

        val winners = legal.filter { c ->
            val t = trick + Play(seat, c)
            game.trickWinnerIndex(t) == t.lastIndex
        }
        val trickPoints = trick.sumOf { it.card.points(trump) }

        if (winners.isNotEmpty() && !partnerWins) {
            val plainWinners = winners.filter { it.suit != trump }
            if (last) {
                // Take it as cheaply as possible in trumps, or with the richest plain card.
                return plainWinners.maxByOrNull { it.points(trump) } ?: winners.minBy { it.rank.trumpOrder }
            }
            val sure = plainWinners.filter { isMaster(game, it, trump) }
            if (sure.isNotEmpty()) return sure.maxBy { it.points(trump) }
            if (winningNow.suit != trump && plainWinners.isEmpty() && trickPoints >= 10) {
                return winners.minBy { it.rank.trumpOrder }
            }
            if (winningNow.suit == trump) return winners.minBy { it.rank.trumpOrder }
        }
        return cheapest(legal, trump)
    }

    private fun lead(game: BelotGame, seat: Int, legal: List<Card>, trump: Suit): Card {
        val hand = game.hands[seat]
        val outstandingTrumps = game.deckCards.count { it.suit == trump && it !in game.playedCards && it !in hand }
        val myTrumps = legal.filter { it.suit == trump }
        val ourSideTook = game.sideOf(seat) == game.sideOf(game.taker)
        if (ourSideTook && outstandingTrumps > 0 && myTrumps.isNotEmpty()) {
            val master = myTrumps.firstOrNull { isMaster(game, it, trump) }
            if (master != null) return master
        }
        val masters = legal.filter { it.suit != trump && isMaster(game, it, trump) }
        if (masters.isNotEmpty()) return masters.maxBy { it.points(trump) }
        val plain = legal.filter { it.suit != trump }
        if (plain.isNotEmpty()) return cheapest(plain, trump)
        return legal.minBy { it.rank.trumpOrder }
    }

    /** Whether no unseen card of the same suit can beat [card]. */
    private fun isMaster(game: BelotGame, card: Card, trump: Suit): Boolean =
        game.deckCards.none {
            it.suit == card.suit && it.order(trump) > card.order(trump) && it !in game.playedCards
        }

    private fun cheapest(cards: List<Card>, trump: Suit): Card =
        cards.minWith(compareBy<Card>({ if (it.suit == trump) 1 else 0 }, { it.points(trump) }, { it.order(trump) }))
}
