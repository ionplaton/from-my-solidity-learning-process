package belot.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RulesTest {
    private fun c(code: String) = Card.parse(code)

    @Test
    fun deckPointsTotal152() {
        for (trump in Suit.entries) {
            assertEquals(152, Card.deck24().sumOf { it.points(trump) })
        }
        assertEquals(24, Card.deck24().toSet().size)
    }

    @Test
    fun findsSequencesAndCarres() {
        val hand = listOf("9H", "TH", "JH", "QH", "AS", "AC", "AD", "AH").map(::c)
        val combos = Combos.find(hand)
        assertEquals(1, combos.count { it.kind == ComboKind.CARRE && it.top == Rank.ACE && it.points == 100 })
        val seq = combos.single { it.kind == ComboKind.SEQUENCE }
        assertEquals(4, seq.cards.size)
        assertEquals(50, seq.points)

        val hundred = Combos.find(listOf("9S", "TS", "JS", "QS", "KS", "AS").map(::c)).single()
        assertEquals(100, hundred.points)
        val tierce = Combos.find(listOf("QD", "KD", "AD", "9C").map(::c)).single()
        assertEquals(20, tierce.points)
        assertTrue(Combos.find(listOf("9C", "TC", "QC", "KC").map(::c)).isEmpty())
        assertEquals(200, Combos.find(listOf("JS", "JH", "JC", "JD").map(::c)).single().points)
        assertEquals(150, Combos.find(listOf("9S", "9H", "9C", "9D").map(::c)).single().points)
    }

    @Test
    fun onlyBestSequenceHolderScores() {
        val p0 = Combos.find(listOf("9H", "TH", "JH").map(::c))          // tierce to J
        val p1 = Combos.find(listOf("QS", "KS", "AS").map(::c))          // tierce to A
        val p2 = Combos.find(listOf("9D", "TD", "JD", "QD").map(::c))    // quarte
        val w = Combos.winners(listOf(p0, p1, p2), Suit.CLUBS)
        assertEquals(setOf(2), w.map { it.first }.toSet())

        // Equal tierces: trump wins; otherwise nobody.
        val a = Combos.find(listOf("9H", "TH", "JH").map(::c))
        val b = Combos.find(listOf("9S", "TS", "JS").map(::c))
        assertEquals(setOf(1), Combos.winners(listOf(a, b, emptyList()), Suit.SPADES).map { it.first }.toSet())
        assertTrue(Combos.winners(listOf(a, b, emptyList()), Suit.CLUBS).isEmpty())
    }

    /** Builds a game in the playing phase with fixed hands by brute force over seeds. */
    private fun playingGame(seed: Int, rules: Rules = Rules()): BelotGame {
        val g = BelotGame(listOf("A", "B", "C"), rules, Random(seed), firstDealer = 0)
        g.bid(g.currentPlayer, take = true)
        return g
    }

    @Test
    fun mustFollowSuitAndTrumpRules() {
        repeat(300) { seed ->
            val g = playingGame(seed)
            val trump = g.trump!!
            // play whole hand with first legal card, checking constraints each time
            while (g.phase == Phase.PLAYING) {
                val p = g.currentPlayer
                val hand = g.hands[p]
                val legal = g.legalCards(p)
                assertTrue(legal.isNotEmpty())
                assertTrue(hand.containsAll(legal))
                if (g.trick.isNotEmpty()) {
                    val led = g.trick[0].card.suit
                    if (hand.any { it.suit == led }) {
                        assertTrue(legal.all { it.suit == led }, "must follow suit")
                    } else if (hand.any { it.suit == trump }) {
                        assertTrue(legal.all { it.suit == trump }, "must trump")
                    }
                    val top = g.trick.filter { it.card.suit == trump }.maxOfOrNull { it.card.rank.trumpOrder }
                    if (top != null && legal.first().suit == trump) {
                        val canBeat = hand.any { it.suit == trump && it.rank.trumpOrder > top }
                        if (canBeat) assertTrue(legal.all { it.rank.trumpOrder > top }, "must overtrump")
                    }
                }
                g.play(p, legal.random(Random(seed)))
            }
        }
    }

    @Test
    fun trickWinner() {
        val g = playingGame(1)
        val tr = g.trump!!
        val other = Suit.entries.first { it != tr }
        val third = Suit.entries.first { it != tr && it != other }
        fun w(vararg cards: Card) = g.trickWinnerIndex(cards.mapIndexed { i, card -> Play(i, card) })
        assertEquals(1, w(Card(other, Rank.KING), Card(other, Rank.TEN), Card(other, Rank.NINE)))
        assertEquals(2, w(Card(other, Rank.ACE), Card(third, Rank.ACE), Card(tr, Rank.QUEEN)))
        assertEquals(1, w(Card(tr, Rank.ACE), Card(tr, Rank.NINE), Card(tr, Rank.TEN)))
        assertEquals(0, w(Card(other, Rank.NINE), Card(third, Rank.ACE), Card(third, Rank.TEN)))
        assertEquals(2, w(Card(other, Rank.NINE), Card(tr, Rank.NINE), Card(tr, Rank.JACK)))
    }

    @Test
    fun noUndertrumpOption() {
        var checked = 0
        repeat(500) { seed ->
            val g = playingGame(seed, Rules(mustUnderTrump = false))
            val tr = g.trump!!
            while (g.phase == Phase.PLAYING) {
                val p = g.currentPlayer
                val hand = g.hands[p]
                val legal = g.legalCards(p)
                if (g.trick.isNotEmpty()) {
                    val led = g.trick[0].card.suit
                    val top = g.trick.filter { it.card.suit == tr }.maxOfOrNull { it.card.rank.trumpOrder }
                    if (led != tr && top != null && hand.none { it.suit == led } &&
                        hand.any { it.suit == tr } && hand.none { it.suit == tr && it.rank.trumpOrder > top }
                    ) {
                        assertEquals(hand.toSet(), legal.toSet())
                        checked++
                    }
                }
                g.play(p, legal.first())
            }
        }
        assertTrue(checked > 0)
    }

    @Test
    fun fullGamesWithBotsKeepInvariants() {
        var handsPlayed = 0
        var failedContracts = 0
        repeat(150) { seed ->
            val rules = if (seed % 2 == 0) Rules() else Rules(target = 301, takerMustBeatEach = false, mustUnderTrump = false)
            val g = BelotGame(listOf("A", "B", "C"), rules, Random(seed))
            var guard = 0
            while (g.phase != Phase.GAME_OVER) {
                check(guard++ < 10_000)
                when (g.phase) {
                    Phase.BIDDING -> {
                        val b = Bot.chooseBid(g, g.currentPlayer)
                        g.bid(g.currentPlayer, b.take, b.suit)
                    }
                    Phase.PLAYING -> {
                        val p = g.currentPlayer
                        val card = Bot.chooseCard(g, p)
                        assertTrue(card in g.legalCards(p))
                        g.play(p, card)
                    }
                    Phase.HAND_OVER, Phase.GAME_OVER -> {
                        handsPlayed++
                        checkResult(g)
                        if (!g.lastResult!!.takerMade) failedContracts++
                        if (g.phase == Phase.HAND_OVER) g.nextHand()
                    }
                }
            }
            handsPlayed++
            checkResult(g)
            assertTrue(g.totals[g.winner] >= rules.target)
            assertEquals(g.totals.max(), g.totals[g.winner])
            assertFalse(g.totals.count { it == g.totals[g.winner] } > 1)
        }
        assertTrue(handsPlayed > 300)
        assertTrue(failedContracts > 0)
    }

    private fun checkResult(g: BelotGame) {
        val r = g.lastResult!!
        assertEquals(162, r.cardPoints.sum() + r.lastTrick.sum())
        assertEquals(r.raw.sum(), r.awarded.sum())
        assertEquals(r.raw.sum(), 162 + r.comboPoints.sum() + r.belote.sum() + r.capot.sum())
        assertTrue(g.hands.all { it.isEmpty() })
        val opp = (0..2).filter { it != r.taker }
        if (g.rules.takerMustBeatEach) {
            assertEquals(opp.all { r.raw[r.taker] > r.raw[it] }, r.takerMade)
        }
        if (!r.takerMade) assertEquals(r.belote[r.taker], r.awarded[r.taker])
    }

    @Test
    fun viewRoundTripsThroughJson() {
        val g = playingGame(7)
        repeat(4) { g.play(g.currentPlayer, g.legalCards(g.currentPlayer).first()) }
        for (seat in 0..2) {
            val v = g.viewFor(seat)
            assertEquals(v, Codec.decodeView(Codec.encodeView(v)))
            // Other players' cards are never leaked.
            assertEquals(g.hands[seat].toSet(), v.hand.toSet())
        }
        assertEquals(Rules(301, false, false), Codec.decodeRules(Codec.encodeRules(Rules(301, false, false))))
    }

    @Test
    fun everyoneDealtEightCards() {
        repeat(50) { seed ->
            val g = BelotGame(listOf("A", "B", "C"), Rules(), Random(seed))
            assertTrue(g.hands.all { it.size == 5 })
            val turned = g.turnedCard!!
            val p = g.currentPlayer
            g.bid(p, false)
            g.bid(g.currentPlayer, false)
            g.bid(g.currentPlayer, false)
            assertEquals(2, g.bidRound)
            val suit = Suit.entries.first { it != turned.suit }
            g.bid(g.currentPlayer, true, suit)
            assertEquals(suit, g.trump)
            assertTrue(g.hands.all { it.size == 8 })
            assertTrue(g.hands[g.taker].contains(turned))
            assertEquals(24, g.hands.flatten().toSet().size)
        }
    }
}
