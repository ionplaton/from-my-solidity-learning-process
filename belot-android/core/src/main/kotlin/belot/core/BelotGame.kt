package belot.core

import kotlin.random.Random

enum class Phase { BIDDING, PLAYING, HAND_OVER, GAME_OVER }

data class Play(val player: Int, val card: Card)

data class Declaration(val player: Int, val combo: Combo, val counted: Boolean)

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
 * Belot for three players, every player for themselves.
 *
 * 24 cards (9..A). Each player gets 5 cards, the next card is turned up and proposed as trump.
 * Round 1: in turn, each player may take the turned suit or pass. Round 2: each player may name another
 * suit or pass. If everyone passes twice, the next dealer redeals. The taker receives the turned card plus 2,
 * the others 3 cards each, so everybody holds 8 cards.
 */
class BelotGame(
    names: List<String>,
    val rules: Rules = Rules(),
    private val random: Random = Random.Default,
    firstDealer: Int = random.nextInt(PLAYERS),
) {
    companion object {
        const val PLAYERS = 3
        const val TRICKS = 8
    }

    val names: MutableList<String> = names.toMutableList()
    val totals = IntArray(PLAYERS)

    var phase = Phase.BIDDING
        private set
    var handNumber = 0
        private set
    var dealer = firstDealer
        private set
    var currentPlayer = 0
        private set

    val hands: List<MutableList<Card>> = List(PLAYERS) { mutableListOf() }
    private var stock = mutableListOf<Card>()

    var turnedCard: Card? = null
        private set
    var bidRound = 1
        private set
    val passed = BooleanArray(PLAYERS)
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
    val tricksWon = IntArray(PLAYERS)
    private val cardPoints = IntArray(PLAYERS)
    val playedCards = mutableSetOf<Card>()

    var declarations: List<Declaration> = emptyList()
        private set
    var beloteHolder = -1
        private set
    private var beloteCardsPlayed = 0

    var lastResult: HandResult? = null
        private set
    var winner = -1
        private set

    private val logLines = ArrayDeque<String>()
    val log: List<String> get() = logLines.toList()

    init {
        deal()
    }

    private fun next(p: Int) = (p + 1) % PLAYERS

    private fun log(s: String) {
        logLines.addLast(s)
        while (logLines.size > 8) logLines.removeFirst()
    }

    private fun deal() {
        handNumber++
        phase = Phase.BIDDING
        hands.forEach { it.clear() }
        stock = Card.deck24().shuffled(random).toMutableList()
        for (i in 0 until PLAYERS) {
            val p = (dealer + 1 + i) % PLAYERS
            repeat(5) { hands[p] += stock.removeAt(stock.lastIndex) }
        }
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
        if (passesInRound < PLAYERS) {
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
        repeat(2) { hands[player] += stock.removeAt(stock.lastIndex) }
        for (p in 0 until PLAYERS) if (p != player) repeat(3) { hands[p] += stock.removeAt(stock.lastIndex) }
        check(stock.isEmpty())

        val combos = hands.map { Combos.find(it) }
        val counted = Combos.winners(combos, chosen)
        declarations = combos.flatMapIndexed { p, list -> list.map { Declaration(p, it, (p to it) in counted) } }
        beloteHolder = (0 until PLAYERS).firstOrNull { p ->
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
            if (led == tr && topTrump != null) {
                val higher = follow.filter { it.rank.trumpOrder > topTrump }
                return higher.ifEmpty { follow }
            }
            return follow
        }
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

        if (trick.size < PLAYERS) {
            currentPlayer = next(player)
            return
        }

        val winnerPlay = trick[trickWinnerIndex()]
        val w = winnerPlay.player
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

        if (tricksPlayed == TRICKS) endHand(w) else currentPlayer = w
    }

    private fun endHand(lastWinner: Int) {
        val tr = trump!!
        val combo = IntArray(PLAYERS)
        for (d in declarations) if (d.counted) combo[d.player] += d.combo.points
        val belote = IntArray(PLAYERS)
        if (beloteHolder >= 0) belote[beloteHolder] = Rules.BELOTE_POINTS
        val last = IntArray(PLAYERS)
        last[lastWinner] = Rules.LAST_TRICK_BONUS
        val capot = IntArray(PLAYERS)
        val capotPlayer = (0 until PLAYERS).firstOrNull { tricksWon[it] == TRICKS }
        if (capotPlayer != null) capot[capotPlayer] = Rules.CAPOT_BONUS

        val raw = IntArray(PLAYERS) { cardPoints[it] + combo[it] + belote[it] + last[it] + capot[it] }
        val total = raw.sum()
        val opponents = (0 until PLAYERS).filter { it != taker }
        val made = if (rules.takerMustBeatEach) {
            opponents.all { raw[taker] > raw[it] }
        } else {
            raw[taker] * 2 > total
        }

        val awarded = raw.copyOf()
        if (!made) {
            // The taker keeps only the belote; the rest of the taker's points is split between the opponents.
            val rest = raw[taker] - belote[taker]
            awarded[taker] = belote[taker]
            awarded[opponents[0]] += rest - rest / 2
            awarded[opponents[1]] += rest / 2
        }
        for (p in 0 until PLAYERS) totals[p] += awarded[p]

        lastResult = HandResult(
            trump = tr,
            taker = taker,
            cardPoints = cardPoints.toList(),
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
        val leaders = (0 until PLAYERS).filter { totals[it] == best }
        if (best >= rules.target && leaders.size == 1) {
            winner = leaders[0]
            phase = Phase.GAME_OVER
            log("Победа: ${names[winner]}!")
        } else {
            phase = Phase.HAND_OVER
        }
    }

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

/** Everything a single player is allowed to see. */
data class PlayerView(
    val seat: Int,
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
)
