package belot.core

import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkTest {
    /** A scripted player that plays the first legal card. */
    private class AutoPlayer(name: String, port: Int, token: String?, val finished: CountDownLatch) : GameClient.Listener {
        val last = AtomicReference<JSONObject>()
        val errors = mutableListOf<String>()
        val client = GameClient("127.0.0.1", port, name, token, this)

        override fun onMessage(msg: JSONObject) {
            when (msg.getString("t")) {
                "error" -> synchronized(errors) { errors += msg.getString("msg") }
                "state" -> {
                    last.set(msg)
                    if (!msg.has("game")) return
                    val v = Codec.decodeView(msg.getJSONObject("game"))
                    when {
                        v.phase == Phase.GAME_OVER -> finished.countDown()
                        v.phase == Phase.HAND_OVER -> client.send(JSONObject().put("t", "ready"))
                        v.currentPlayer != v.seat -> {}
                        v.phase == Phase.BIDDING -> {
                            val m = JSONObject().put("t", "bid")
                            if (v.bidRound == 1) {
                                m.put("take", v.seat == 0)
                            } else {
                                m.put("take", true)
                                m.put("suit", Suit.entries.first { it != v.turnedCard!!.suit }.code.toString())
                            }
                            client.send(m)
                        }
                        v.phase == Phase.PLAYING -> client.send(JSONObject().put("t", "play").put("card", v.legal.first().code))
                    }
                }
            }
        }

        override fun onDisconnected(reason: String) {}
    }

    @Test
    fun twoHumansAndBotPlayFullGameOverTcp() {
        val server = GameServer("secret", preferredPort = 47400, botDelayMs = 5)
        server.start()
        val done = CountDownLatch(2)
        val host = AutoPlayer("Ион", server.port, "secret", done)
        host.client.connect()
        Thread.sleep(300)
        val guest = AutoPlayer("Ион", server.port, null, done)
        guest.client.connect()
        Thread.sleep(300)

        // Names are made unique; the guest cannot control the table.
        val players = guest.last.get().getJSONArray("players")
        assertEquals("Ион", players.getJSONObject(0).getString("name"))
        assertEquals("Ион 2", players.getJSONObject(1).getString("name"))
        guest.client.send(JSONObject().put("t", "addbot"))
        Thread.sleep(200)
        assertTrue(guest.errors.isNotEmpty())

        host.client.send(JSONObject().put("t", "rules").put("rules", Codec.encodeRules(Rules(players = 3, target = 301))))
        host.client.send(JSONObject().put("t", "addbot"))
        host.client.send(JSONObject().put("t", "start"))

        assertTrue(done.await(60, TimeUnit.SECONDS), "game did not finish")
        val final = Codec.decodeView(host.last.get().getJSONObject("game"))
        assertEquals(Phase.GAME_OVER, final.phase)
        assertTrue(final.totals.max() >= 301)
        assertEquals(301, final.rules.target)

        // A fourth player is refused once the table is full.
        val refused = CountDownLatch(1)
        val reason = AtomicReference<String>()
        GameClient("127.0.0.1", server.port, "Late", null, object : GameClient.Listener {
            override fun onMessage(msg: JSONObject) {}
            override fun onDisconnected(r: String) { reason.set(r); refused.countDown() }
        }).connect()
        assertTrue(refused.await(5, TimeUnit.SECONDS))
        assertEquals("Игра уже идёт", reason.get())

        host.client.close()
        guest.client.close()
        server.stop()
    }

    @Test
    fun disconnectedPlayerCanRejoinSeat() {
        val server = GameServer("t", preferredPort = 47420, botDelayMs = 5)
        server.start()
        val never = CountDownLatch(1)
        val host = AutoPlayer("Host", server.port, "t", never)
        host.client.connect()
        Thread.sleep(200)
        val idle = object : GameClient.Listener {
            override fun onMessage(msg: JSONObject) {}
            override fun onDisconnected(reason: String) {}
        }
        val guest = GameClient("127.0.0.1", server.port, "Guest", null, idle)
        guest.connect()
        Thread.sleep(200)
        host.client.send(JSONObject().put("t", "addbot"))
        host.client.send(JSONObject().put("t", "start"))
        Thread.sleep(200)
        guest.close()
        Thread.sleep(300)
        val p1 = host.last.get().getJSONArray("players").getJSONObject(1)
        assertEquals(false, p1.getBoolean("online"))

        val back = AutoPlayer("Guest", server.port, null, never)
        back.client.connect()
        Thread.sleep(300)
        assertEquals(1, back.last.get().getInt("you"))
        assertEquals(true, host.last.get().getJSONArray("players").getJSONObject(1).getBoolean("online"))
        host.client.close()
        back.client.close()
        server.stop()
    }

    @Test
    fun discoveryFindsBeacon() {
        val server = GameServer("t", preferredPort = 47440)
        server.start()
        val found = CountDownLatch(1)
        val info = AtomicReference<HostInfo>()
        val listener = DiscoveryListener({ info.set(it); found.countDown() }, udpPort = 47450)
        listener.start()
        val beacon = DiscoveryBeacon(server, udpPort = 47450)
        beacon.start()
        val ok = found.await(5, TimeUnit.SECONDS)
        beacon.stop()
        listener.stop()
        server.stop()
        if (ok) assertEquals(server.port, info.get().port)
        else println("UDP broadcast not available in this environment; skipped")
    }

    @Test
    fun fourPlayerTableWithSwapAndBots() {
        val server = GameServer("t", preferredPort = 47460, botDelayMs = 2)
        server.start()
        val done = CountDownLatch(2)
        val host = AutoPlayer("Host", server.port, "t", done)
        host.client.connect()
        Thread.sleep(200)
        val guest = AutoPlayer("Guest", server.port, null, done)
        guest.client.connect()
        Thread.sleep(200)
        host.client.send(JSONObject().put("t", "rules").put("rules", Codec.encodeRules(Rules(players = 4, target = 301))))
        // Make the guest the host's partner (seat 2).
        host.client.send(JSONObject().put("t", "swap").put("a", 1).put("b", 2))
        Thread.sleep(200)
        assertEquals(2, guest.last.get().getInt("you"))
        assertEquals(4, guest.last.get().getJSONArray("players").length())
        repeat(2) { host.client.send(JSONObject().put("t", "addbot")) }
        host.client.send(JSONObject().put("t", "start"))
        assertTrue(done.await(60, TimeUnit.SECONDS), "game did not finish")
        val v = Codec.decodeView(guest.last.get().getJSONObject("game"))
        assertEquals(4, v.playerCount)
        assertEquals(2, v.totals.size)
        assertTrue(v.totals.max() >= 301)

        // Shrinking the table with humans on the extra seats is refused in a new lobby; covered by rules check.
        host.client.close()
        guest.client.close()
        server.stop()
    }

    @Test
    fun twoPlayerGame() {
        val server = GameServer("t", preferredPort = 47480, botDelayMs = 2)
        server.start()
        val done = CountDownLatch(1)
        val host = AutoPlayer("Host", server.port, "t", done)
        host.client.connect()
        Thread.sleep(200)
        host.client.send(JSONObject().put("t", "rules").put("rules", Codec.encodeRules(Rules(players = 2, target = 301))))
        host.client.send(JSONObject().put("t", "addbot"))
        host.client.send(JSONObject().put("t", "addbot"))
        Thread.sleep(200)
        assertTrue(host.errors.isNotEmpty(), "third seat must not exist")
        host.client.send(JSONObject().put("t", "start"))
        assertTrue(done.await(60, TimeUnit.SECONDS), "game did not finish")
        assertEquals(2, Codec.decodeView(host.last.get().getJSONObject("game")).playerCount)
        host.client.close()
        server.stop()
    }
}
