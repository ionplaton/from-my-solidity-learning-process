package belot.core

import org.json.JSONArray
import org.json.JSONObject

/** JSON encoding of game views for the network protocol (org.json is built into Android). */
object Codec {
    private fun cards(list: List<Card>) = JSONArray().apply { list.forEach { put(it.code) } }
    private fun ints(list: List<Int>) = JSONArray().apply { list.forEach { put(it) } }
    private fun bools(list: List<Boolean>) = JSONArray().apply { list.forEach { put(it) } }
    private fun plays(list: List<Play>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("p", it.player).put("c", it.card.code)) }
    }

    private fun JSONArray.cards() = (0 until length()).map { Card.parse(getString(it)) }
    private fun JSONArray.ints() = (0 until length()).map { getInt(it) }
    private fun JSONArray.bools() = (0 until length()).map { getBoolean(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun JSONArray.plays() = (0 until length()).map {
        val o = getJSONObject(it)
        Play(o.getInt("p"), Card.parse(o.getString("c")))
    }

    fun encodeRules(r: Rules): JSONObject = JSONObject()
        .put("players", r.players)
        .put("target", r.target)
        .put("beatEach", r.takerMustBeatEach)
        .put("underTrump", r.mustUnderTrump)

    fun decodeRules(o: JSONObject): Rules = Rules(
        players = o.optInt("players", 3).coerceIn(2, 4),
        target = o.optInt("target", 501),
        takerMustBeatEach = o.optBoolean("beatEach", true),
        mustUnderTrump = o.optBoolean("underTrump", true),
    )

    private fun encodeCombo(c: Combo) = JSONObject().put("kind", c.kind.name).put("cards", cards(c.cards)).put("pts", c.points)
    private fun decodeCombo(o: JSONObject) =
        Combo(ComboKind.valueOf(o.getString("kind")), o.getJSONArray("cards").cards(), o.getInt("pts"))

    private fun encodeResult(r: HandResult) = JSONObject()
        .put("trump", r.trump.code.toString())
        .put("taker", r.taker)
        .put("cards", ints(r.cardPoints))
        .put("combo", ints(r.comboPoints))
        .put("belote", ints(r.belote))
        .put("last", ints(r.lastTrick))
        .put("capot", ints(r.capot))
        .put("raw", ints(r.raw))
        .put("awarded", ints(r.awarded))
        .put("made", r.takerMade)

    private fun decodeResult(o: JSONObject) = HandResult(
        trump = Suit.fromCode(o.getString("trump")[0]),
        taker = o.getInt("taker"),
        cardPoints = o.getJSONArray("cards").ints(),
        comboPoints = o.getJSONArray("combo").ints(),
        belote = o.getJSONArray("belote").ints(),
        lastTrick = o.getJSONArray("last").ints(),
        capot = o.getJSONArray("capot").ints(),
        raw = o.getJSONArray("raw").ints(),
        awarded = o.getJSONArray("awarded").ints(),
        takerMade = o.getBoolean("made"),
    )

    fun encodeView(v: PlayerView): JSONObject {
        val o = JSONObject()
            .put("seat", v.seat)
            .put("n", v.playerCount)
            .put("phase", v.phase.name)
            .put("hand_no", v.handNumber)
            .put("dealer", v.dealer)
            .put("current", v.currentPlayer)
            .put("hand", cards(v.hand))
            .put("counts", ints(v.handCounts))
            .put("bid_round", v.bidRound)
            .put("passed", bools(v.passed))
            .put("taker", v.taker)
            .put("trick", plays(v.trick))
            .put("last_trick", plays(v.lastTrick))
            .put("last_winner", v.lastTrickWinner)
            .put("tricks", ints(v.tricksWon))
            .put("totals", ints(v.totals))
            .put("legal", cards(v.legal))
            .put("belote", v.beloteHolder)
            .put("winner", v.winner)
            .put("log", JSONArray().apply { v.log.forEach { put(it) } })
            .put("rules", encodeRules(v.rules))
            .put("decl", JSONArray().apply {
                v.declarations.forEach {
                    put(JSONObject().put("p", it.player).put("combo", encodeCombo(it.combo)).put("ok", it.counted))
                }
            })
        v.turnedCard?.let { o.put("turned", it.code) }
        v.trump?.let { o.put("trump", it.code.toString()) }
        v.result?.let { o.put("result", encodeResult(it)) }
        return o
    }

    fun decodeView(o: JSONObject): PlayerView = PlayerView(
        seat = o.getInt("seat"),
        playerCount = o.getInt("n"),
        phase = Phase.valueOf(o.getString("phase")),
        handNumber = o.getInt("hand_no"),
        dealer = o.getInt("dealer"),
        currentPlayer = o.getInt("current"),
        hand = o.getJSONArray("hand").cards(),
        handCounts = o.getJSONArray("counts").ints(),
        turnedCard = if (o.has("turned")) Card.parse(o.getString("turned")) else null,
        bidRound = o.getInt("bid_round"),
        passed = o.getJSONArray("passed").bools(),
        trump = if (o.has("trump")) Suit.fromCode(o.getString("trump")[0]) else null,
        taker = o.getInt("taker"),
        trick = o.getJSONArray("trick").plays(),
        lastTrick = o.getJSONArray("last_trick").plays(),
        lastTrickWinner = o.getInt("last_winner"),
        tricksWon = o.getJSONArray("tricks").ints(),
        totals = o.getJSONArray("totals").ints(),
        legal = o.getJSONArray("legal").cards(),
        declarations = o.getJSONArray("decl").let { a ->
            (0 until a.length()).map {
                val d = a.getJSONObject(it)
                Declaration(d.getInt("p"), decodeCombo(d.getJSONObject("combo")), d.getBoolean("ok"))
            }
        },
        beloteHolder = o.getInt("belote"),
        result = if (o.has("result")) decodeResult(o.getJSONObject("result")) else null,
        winner = o.getInt("winner"),
        log = o.getJSONArray("log").strings(),
        rules = decodeRules(o.getJSONObject("rules")),
    )
}
