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
        for (trump in Suit.entries) assertEquals(152, Card.deck32().sumOf { it.points(trump) })
        assertEquals(32, Card.deck32().toSet().size)
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
        for (n in 2..4) playBotGames(n)
    }

    private fun playBotGames(n: Int) {
        var handsPlayed = 0
        var failedContracts = 0
        repeat(150) { seed ->
            val rules = if (seed % 2 == 0) Rules(players = n)
            else Rules(players = n, target = 301, takerMustBeatEach = false, mustUnderTrump = false)
            val g = BelotGame(listOf("A", "B", "C", "D").take(n), rules, Random(seed))
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
        val ts = g.sideOf(r.taker)
        assertEquals(162, r.cardPoints.sum() + r.lastTrick.sum())
        assertEquals(r.raw.sum(), 162 + r.comboPoints.sum() + r.belote.sum() + r.capot.sum())
        assertTrue(g.hands.all { it.isEmpty() })
        val opp = (0 until g.sideCount).filter { it != ts }
        val litige = !r.takerMade && g.sideCount == 2 && r.raw[0] == r.raw[1]
        if (litige) {
            assertEquals(r.raw.sum() - (r.raw[ts] - r.belote[ts]), r.awarded.sum())
        } else {
            assertEquals(r.raw.sum(), r.awarded.sum())
        }
        if (g.sideCount == 2 || g.rules.takerMustBeatEach) {
            assertEquals(opp.all { r.raw[ts] > r.raw[it] }, r.takerMade)
        }
        if (!r.takerMade) assertEquals(r.belote[ts], r.awarded[ts])
        if (r.capot.sum() > 0) assertEquals(g.tricksPerHand, (0 until g.playerCount).filter { g.sideOf(it) == r.capot.indexOf(90) }.sumOf { g.tricksWon[it] })
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
        val r = Rules(players = 4, target = 301, takerMustBeatEach = false, mustUnderTrump = false)
        assertEquals(r, Codec.decodeRules(Codec.encodeRules(r)))
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

    @Test
    fun dealSizesForTwoAndFourPlayers() {
        for ((n, size) in listOf(2 to 12, 4 to 8)) {
            repeat(30) { seed ->
                val g = BelotGame(listOf("A", "B", "C", "D").take(n), Rules(players = n), Random(seed))
                assertTrue(g.hands.all { it.size == if (n == 2) 6 else 5 })
                g.bid(g.currentPlayer, true)
                assertTrue(g.hands.all { it.size == size })
                assertEquals(if (n == 4) 32 else 24, g.hands.flatten().toSet().size)
                assertEquals(size, g.tricksPerHand)
            }
        }
    }

    @Test
    fun fourPlayersNeedNotTrumpWhenPartnerWins() {
        var checked = 0
        repeat(400) { seed ->
            val g = BelotGame(listOf("A", "B", "C", "D"), Rules(players = 4), Random(seed))
            g.bid(g.currentPlayer, true)
            val tr = g.trump!!
            while (g.phase == Phase.PLAYING) {
                val p = g.currentPlayer
                val hand = g.hands[p]
                val legal = g.legalCards(p)
                if (g.trick.isNotEmpty()) {
                    val led = g.trick[0].card.suit
                    val partnerWins = g.trick[g.trickWinnerIndex()].player == g.partnerOf(p)
                    if (hand.none { it.suit == led }) {
                        if (partnerWins) {
                            assertEquals(hand.toSet(), legal.toSet())
                            if (hand.any { it.suit == tr } && hand.any { it.suit != tr }) checked++
                        } else if (hand.any { it.suit == tr }) {
                            assertTrue(legal.all { it.suit == tr })
                        }
                    }
                }
                g.play(p, legal.random(Random(seed + checked)))
            }
        }
        assertTrue(checked > 0)
    }

    @Test
    fun teamCombosAndSevens() {
        // Sevens/eights carrés do not count; 7-8-9 is a tierce.
        assertTrue(Combos.find(listOf("7S", "7H", "7C", "7D").map(::c)).isEmpty())
        assertEquals(20, Combos.find(listOf("7S", "8S", "9S").map(::c)).single().points)
        // Team play: partner's quarte beats an opponent's tierce, and the partner's own tierce then counts too.
        val p0 = Combos.find(listOf("9H", "TH", "JH").map(::c))
        val p1 = Combos.find(listOf("QS", "KS", "AS").map(::c))
        val p2 = Combos.find(listOf("7D", "8D", "9D", "TD").map(::c))
        val w = Combos.winners(listOf(p0, p1, p2, emptyList()), Suit.CLUBS) { it % 2 }
        assertEquals(setOf(0, 2), w.map { it.first }.toSet())
        // Same best sequence in both teams: nobody scores.
        val a = Combos.find(listOf("9H", "TH", "JH").map(::c))
        val b = Combos.find(listOf("9S", "TS", "JS").map(::c))
        assertTrue(Combos.winners(listOf(a, b, emptyList(), emptyList()), Suit.CLUBS) { it % 2 }.isEmpty())
    }
}
