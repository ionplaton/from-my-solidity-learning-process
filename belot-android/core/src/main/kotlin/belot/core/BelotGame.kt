package belot.core

import kotlin.random.Random

enum class Phase { BIDDING, PLAYING, HAND_OVER, GAME_OVER }

data class Play(val player: Int, val card: Card)

data class Declaration(val player: Int, val combo: Combo, val counted: Boolean)

/** Result of one deal. All lists are indexed by side (see [BelotGame.sideOf]). */
data class HandResult(
    val trump: Suit,
    val taker: Int,
    val cardPoints: List<Int>,
    val comboPoints: List<Int>,
    val belote: List<Int>,
    val lastTrick: List<Int>,
    val capot: List<Int>,
    val raw: List<Int>,
    val awarded: List<Int>,
    val takerMade: Boolean,
)

/**
 * Belot for two, three or four players.
 *
 * - 4 players: two teams (partners sit opposite each other: seats 0+2 and 1+3), 32 cards (7..A), 8 cards each.
 * - 3 players: everyone for themselves, 24 cards (9..A), 8 cards each.
 * - 2 players: one against one, 24 cards (9..A), 12 cards each.
 *
 * The first part of the deal (5 cards each, 6 with two players) is followed by turning up the next card,
 * whose suit is proposed as trump. Round 1: in turn, each player may take the turned suit or pass.
 * Round 2: each player may name another suit or pass. If everyone passes twice, the next dealer redeals.
 * The taker receives the turned card and the rest of the deal is completed.
 */
class BelotGame(
    names: List<String>,
    val rules: Rules = Rules(players = names.size),
    private val random: Random = Random.Default,
    firstDealer: Int = random.nextInt(names.size),
    /** Returns the deck order for a new deal (the deal takes cards from the end). Tests can fix it. */
    private val shuffler: ((List<Card>) -> List<Card>)? = null,
) {
    val playerCount = names.size

    init {
        require(playerCount in 2..4) { "Нужно от 2 до 4 игроков" }
    }

    /** Scoring sides: two teams with four players, otherwise every player is a side. */
    val sideCount = if (playerCount == 4) 2 else playerCount
    fun sideOf(player: Int) = if (playerCount == 4) player % 2 else player
    fun partnerOf(player: Int) = if (playerCount == 4) (player + 2) % 4 else -1

    private val deck = Card.deck(playerCount)
    val deckCards: List<Card> get() = deck
    val tricksPerHand = deck.size / playerCount
    private val firstDeal = if (playerCount == 2) 6 else 5
    private val takerExtra = if (playerCount == 2) 5 else 2
    private val othersExtra = if (playerCount == 2) 6 else 3

    val names: MutableList<String> = names.toMutableList()
    val totals = IntArray(sideCount)

    var phase = Phase.BIDDING
        private set
    var handNumber = 0
        private set
    var dealer = firstDealer
        private set
    var currentPlayer = 0
        private set

    val hands: List<MutableList<Card>> = List(playerCount) { mutableListOf() }
    private var stock = mutableListOf<Card>()

    var turnedCard: Card? = null
        private set
    var bidRound = 1
        private set
    val passed = BooleanArray(playerCount)
    private var passesInRound = 0

    var trump: Suit? = null
        private set
    var taker = -1
        private set

    val trick = mutableListOf<Play>()
    var lastTrick: List<Play> = emptyList()
        private set
    var lastTrickWinner = -1
        private set
    var tricksPlayed = 0
        private set
    val tricksWon = IntArray(playerCount)
    private val cardPoints = IntArray(playerCount)
    val playedCards = mutableSetOf<Card>()

    var declarations: List<Declaration> = emptyList()
        private set
    var beloteHolder = -1
        private set
    private var beloteCardsPlayed = 0

    var lastResult: HandResult? = null
        private set
    /** Winning side, or -1. */
    var winner = -1
        private set

    private val logLines = ArrayDeque<String>()
    val log: List<String> get() = logLines.toList()

    init {
        deal()
    }

    private fun next(p: Int) = (p + 1) % playerCount

    private fun log(s: String) {
        logLines.addLast(s)
        while (logLines.size > 10) logLines.removeFirst()
    }

    private fun take(n: Int, to: MutableList<Card>) = repeat(n) { to += stock.removeAt(stock.lastIndex) }

    private fun deal() {
        handNumber++
        phase = Phase.BIDDING
        hands.forEach { it.clear() }
        stock = (shuffler?.invoke(deck) ?: deck.shuffled(random)).toMutableList()
        for (i in 0 until playerCount) take(firstDeal, hands[(dealer + 1 + i) % playerCount])
        turnedCard = stock.removeAt(stock.lastIndex)
        bidRound = 1
        passesInRound = 0
        passed.fill(false)
        trump = null
        taker = -1
        trick.clear()
        lastTrick = emptyList()
        lastTrickWinner = -1
        tricksPlayed = 0
        tricksWon.fill(0)
        cardPoints.fill(0)
        playedCards.clear()
        declarations = emptyList()
        beloteHolder = -1
        beloteCardsPlayed = 0
        lastResult = null
        currentPlayer = next(dealer)
        log("Раздача №$handNumber, сдаёт ${names[dealer]}. Открыта ${turnedCard}")
    }

    /** Round 1: [suit] is ignored (the turned suit is taken). Round 2: [suit] must differ from the turned suit. */
    fun bid(player: Int, take: Boolean, suit: Suit? = null) {
        check(phase == Phase.BIDDING) { "Сейчас не торговля" }
        check(player == currentPlayer) { "Сейчас не ваш ход" }
        val turned = turnedCard!!
        if (take) {
            val chosen = if (bidRound == 1) turned.suit else {
                require(suit != null && suit != turned.suit) { "Нужно выбрать другую масть" }
                suit
            }
            log("${names[player]} берёт, козырь ${chosen.symbol}")
            beginPlay(player, chosen)
            return
        }
        passed[player] = true
        passesInRound++
        log("${names[player]}: пас")
        if (passesInRound < playerCount) {
            currentPlayer = next(player)
        } else if (bidRound == 1) {
            bidRound = 2
            passesInRound = 0
            passed.fill(false)
            currentPlayer = next(dealer)
        } else {
            log("Все спасовали — пересдача")
            dealer = next(dealer)
            deal()
        }
    }

    private fun beginPlay(player: Int, chosen: Suit) {
        trump = chosen
        taker = player
        hands[player] += turnedCard!!
        take(takerExtra, hands[player])
        for (i in 1 until playerCount) take(othersExtra, hands[(player + i) % playerCount])
        check(stock.isEmpty())

        val combos = hands.map { Combos.find(it) }
        val counted = Combos.winners(combos, chosen, ::sideOf)
        declarations = combos.flatMapIndexed { p, list -> list.map { Declaration(p, it, (p to it) in counted) } }
        beloteHolder = (0 until playerCount).firstOrNull { p ->
            hands[p].contains(Card(chosen, Rank.KING)) && hands[p].contains(Card(chosen, Rank.QUEEN))
        } ?: -1

        phase = Phase.PLAYING
        currentPlayer = next(dealer)
    }

    fun legalCards(player: Int): List<Card> {
        if (phase != Phase.PLAYING || player != currentPlayer) return emptyList()
        val hand = hands[player]
        if (trick.isEmpty()) return hand.toList()
        val tr = trump!!
        val led = trick[0].card.suit
        val topTrump = trick.filter { it.card.suit == tr }.maxOfOrNull { it.card.rank.trumpOrder }

        val follow = hand.filter { it.suit == led }
        if (follow.isNotEmpty()) {
            // When trump is led, a higher trump must be played if possible (even over the partner).
            if (led == tr && topTrump != null) {
                val higher = follow.filter { it.rank.trumpOrder > topTrump }
                return higher.ifEmpty { follow }
            }
            return follow
        }
        // Cannot follow suit. With four players there is no obligation to trump while the partner wins the trick.
        val partnerWinning = playerCount == 4 && trick[trickWinnerIndex()].player == partnerOf(player)
        if (partnerWinning) return hand.toList()
        val trumps = hand.filter { it.suit == tr }
        if (trumps.isNotEmpty()) {
            if (topTrump == null) return trumps
            val higher = trumps.filter { it.rank.trumpOrder > topTrump }
            if (higher.isNotEmpty()) return higher
            return if (rules.mustUnderTrump) trumps else hand.toList()
        }
        return hand.toList()
    }

    /** Index into the trick of the card that currently wins it. */
    fun trickWinnerIndex(cards: List<Play> = trick): Int {
        val tr = trump
        val led = cards[0].card.suit
        var best = 0
        for (i in 1 until cards.size) {
            val c = cards[i].card
            val b = cards[best].card
            val beats = when {
                c.suit == tr && b.suit != tr -> true
                c.suit == b.suit -> c.order(tr) > b.order(tr)
                else -> false
            }
            if (beats && (c.suit == led || c.suit == tr)) best = i
        }
        return best
    }

    fun play(player: Int, card: Card) {
        check(phase == Phase.PLAYING) { "Сейчас нельзя ходить" }
        check(player == currentPlayer) { "Сейчас не ваш ход" }
        require(card in legalCards(player)) { "Этой картой ходить нельзя" }
        hands[player].remove(card)
        trick += Play(player, card)
        playedCards += card

        if (player == beloteHolder && card.suit == trump && (card.rank == Rank.KING || card.rank == Rank.QUEEN)) {
            beloteCardsPlayed++
            log("${names[player]}: ${if (beloteCardsPlayed == 1) "Бела!" else "Ребела!"}")
        }

        if (trick.size < playerCount) {
            currentPlayer = next(player)
            return
        }

        val w = trick[trickWinnerIndex()].player
        cardPoints[w] += trick.sumOf { it.card.points(trump) }
        tricksWon[w]++
        tricksPlayed++
        lastTrick = trick.toList()
        lastTrickWinner = w
        trick.clear()
        log("Взятку взял(а) ${names[w]}")

        if (tricksPlayed == 1) {
            for (d in declarations) {
                log("${names[d.player]}: ${d.combo.title} (${d.combo.points})" + if (d.counted) "" else " — не считается")
            }
        }

        if (tricksPlayed == tricksPerHand) endHand(w) else currentPlayer = w
    }

    private fun endHand(lastWinner: Int) {
        val tr = trump!!
        val sides = sideCount
        val cards = IntArray(sides)
        for (p in 0 until playerCount) cards[sideOf(p)] += cardPoints[p]
        val combo = IntArray(sides)
        for (d in declarations) if (d.counted) combo[sideOf(d.player)] += d.combo.points
        val belote = IntArray(sides)
        if (beloteHolder >= 0) belote[sideOf(beloteHolder)] = Rules.BELOTE_POINTS
        val last = IntArray(sides)
        last[sideOf(lastWinner)] = Rules.LAST_TRICK_BONUS
        val capot = IntArray(sides)
        val sideTricks = IntArray(sides)
        for (p in 0 until playerCount) sideTricks[sideOf(p)] += tricksWon[p]
        (0 until sides).firstOrNull { sideTricks[it] == tricksPerHand }?.let { capot[it] = Rules.CAPOT_BONUS }

        val raw = IntArray(sides) { cards[it] + combo[it] + belote[it] + last[it] + capot[it] }
        val total = raw.sum()
        val ts = sideOf(taker)
        val opponents = (0 until sides).filter { it != ts }
        val made = if (sides == 3 && !rules.takerMustBeatEach) {
            raw[ts] * 2 > total
        } else {
            opponents.all { raw[ts] > raw[it] }
        }

        val awarded = raw.copyOf()
        if (!made) {
            // The taking side keeps only its belote. The rest of its points goes to the opponents
            // (split equally between two opponents in the three-player game). On an exact tie with
            // one opponent side ("litige") the taker's points are simply lost.
            val rest = raw[ts] - belote[ts]
            awarded[ts] = belote[ts]
            if (sides == 2) {
                if (raw[ts] != raw[opponents[0]]) awarded[opponents[0]] += rest
            } else {
                awarded[opponents[0]] += rest - rest / 2
                awarded[opponents[1]] += rest / 2
            }
        }
        for (s in 0 until sides) totals[s] += awarded[s]

        lastResult = HandResult(
            trump = tr,
            taker = taker,
            cardPoints = cards.toList(),
            comboPoints = combo.toList(),
            belote = belote.toList(),
            lastTrick = last.toList(),
            capot = capot.toList(),
            raw = raw.toList(),
            awarded = awarded.toList(),
            takerMade = made,
        )
        log(if (made) "${names[taker]} сыграл(а) свою игру" else "${names[taker]} не выполнил(а) игру")

        val best = totals.max()
        val leaders = (0 until sides).filter { totals[it] == best }
        if (best >= rules.target && leaders.size == 1) {
            winner = leaders[0]
            phase = Phase.GAME_OVER
            log("Победа: ${sideName(winner)}!")
        } else {
            phase = Phase.HAND_OVER
        }
    }

    fun sideMembers(side: Int): List<Int> = (0 until playerCount).filter { sideOf(it) == side }

    fun sideName(side: Int): String = sideMembers(side).joinToString(" и ") { names[it] }

    fun nextHand() {
        check(phase == Phase.HAND_OVER) { "Раздача ещё не окончена" }
        dealer = next(dealer)
        deal()
    }

    fun newGame() {
        check(phase == Phase.GAME_OVER) { "Игра ещё не окончена" }
        totals.fill(0)
        winner = -1
        handNumber = 0
        dealer = next(dealer)
        deal()
    }

    /** Who has to act right now (bidding or playing), or -1. */
    val actor: Int get() = if (phase == Phase.BIDDING || phase == Phase.PLAYING) currentPlayer else -1

    fun viewFor(seat: Int): PlayerView {
        val revealAll = tricksPlayed >= 1 || phase == Phase.HAND_OVER || phase == Phase.GAME_OVER
        return PlayerView(
            seat = seat,
            playerCount = playerCount,
            phase = phase,
            handNumber = handNumber,
            dealer = dealer,
            currentPlayer = actor,
            hand = sortHand(hands[seat], trump),
            handCounts = hands.map { it.size },
            turnedCard = if (phase == Phase.BIDDING) turnedCard else null,
            bidRound = bidRound,
            passed = passed.toList(),
            trump = trump,
            taker = taker,
            trick = trick.toList(),
            lastTrick = lastTrick,
            lastTrickWinner = lastTrickWinner,
            tricksWon = tricksWon.toList(),
            totals = totals.toList(),
            legal = legalCards(seat),
            declarations = declarations.filter { revealAll || it.player == seat },
            beloteHolder = if (beloteCardsPlayed > 0) beloteHolder else -1,
            result = lastResult,
            winner = winner,
            log = log,
            rules = rules,
        )
    }
}

/** Everything a single player is allowed to see. [totals], [winner] and [result] are per side. */
data class PlayerView(
    val seat: Int,
    val playerCount: Int,
    val phase: Phase,
    val handNumber: Int,
    val dealer: Int,
    val currentPlayer: Int,
    val hand: List<Card>,
    val handCounts: List<Int>,
    val turnedCard: Card?,
    val bidRound: Int,
    val passed: List<Boolean>,
    val trump: Suit?,
    val taker: Int,
    val trick: List<Play>,
    val lastTrick: List<Play>,
    val lastTrickWinner: Int,
    val tricksWon: List<Int>,
    val totals: List<Int>,
    val legal: List<Card>,
    val declarations: List<Declaration>,
    val beloteHolder: Int,
    val result: HandResult?,
    val winner: Int,
    val log: List<String>,
    val rules: Rules,
) {
    val teams: Boolean get() = playerCount == 4
    val sideCount: Int get() = if (teams) 2 else playerCount
    fun sideOf(player: Int) = if (teams) player % 2 else player
    fun sideMembers(side: Int): List<Int> = (0 until playerCount).filter { sideOf(it) == side }
}
