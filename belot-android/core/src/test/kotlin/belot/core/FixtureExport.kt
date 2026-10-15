package belot.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random
import kotlin.test.Test

/**
 * Records bot-played games (deck orders, every action and the resulting state) so that the JavaScript port
 * in belot-web can replay them and check that it behaves exactly like this engine.
 * Runs only with `-PfixtureOut=<file>`.
 */
class FixtureExport {
    @Test
    fun export() {
        val out = System.getProperty("fixture.out") ?: return
        val games = JSONArray()
        var seed = 0
        for (n in 2..4) {
            repeat(System.getProperty("fixture.games")?.toInt() ?: 5) {
                seed++
                val rules = when (seed % 3) {
                    0 -> Rules(players = n, target = 301)
                    1 -> Rules(players = n, target = 301, takerMustBeatEach = false, mustUnderTrump = false)
                    else -> Rules(players = n, target = 301, mustUnderTrump = false)
                }
                games.put(record(n, rules, seed))
            }
        }
        File(out).apply { parentFile?.mkdirs() }.writeText(JSONObject().put("games", games).toString())
        println("Wrote ${games.length()} games to $out")
    }

    private fun record(n: Int, rules: Rules, seed: Int): JSONObject {
        val random = Random(seed)
        val decks = JSONArray()
        val names = listOf("Ион", "Мария", "Пётр", "Анна").take(n)
        val firstDealer = seed % n
        val g = BelotGame(names, rules, random, firstDealer) { deck ->
            deck.shuffled(random).also { d -> decks.put(JSONArray().apply { d.forEach { put(it.code) } }) }
        }
        val steps = JSONArray()
        fun snapshot(action: JSONObject) {
            val s = JSONObject()
                .put("action", action)
                .put("phase", g.phase.name)
                .put("actor", g.actor)
                .put("hand", g.handNumber)
                .put("dealer", g.dealer)
                .put("totals", JSONArray(g.totals.toList()))
                .put("legal", JSONArray(if (g.actor >= 0) g.legalCards(g.actor).map { it.code } else emptyList<String>()))
                .put("log", JSONArray(g.log))
            if (g.phase != Phase.BIDDING && g.phase != Phase.PLAYING || g.tricksPlayed == 0 && g.trick.isEmpty()) {
                s.put("views", JSONArray().apply { for (p in 0 until n) put(Codec.encodeView(g.viewFor(p))) })
            }
            steps.put(s)
        }
        snapshot(JSONObject().put("t", "start"))
        var guard = 0
        while (g.phase != Phase.GAME_OVER && guard++ < 5000) {
            when (g.phase) {
                Phase.BIDDING -> {
                    val p = g.currentPlayer
                    val b = Bot.chooseBid(g, p)
                    g.bid(p, b.take, b.suit)
                    val a = JSONObject().put("t", "bid").put("p", p).put("take", b.take)
                    if (b.suit != null) a.put("suit", b.suit.code.toString())
                    snapshot(a)
                }
                Phase.PLAYING -> {
                    val p = g.currentPlayer
                    val c = Bot.chooseCard(g, p)
                    g.play(p, c)
                    snapshot(JSONObject().put("t", "play").put("p", p).put("card", c.code))
                }
                Phase.HAND_OVER -> {
                    g.nextHand()
                    snapshot(JSONObject().put("t", "next"))
                }
                Phase.GAME_OVER -> {}
            }
        }
        return JSONObject()
            .put("players", n)
            .put("names", JSONArray(names))
            .put("rules", Codec.encodeRules(rules))
            .put("firstDealer", firstDealer)
            .put("decks", decks)
            .put("steps", steps)
    }
}
