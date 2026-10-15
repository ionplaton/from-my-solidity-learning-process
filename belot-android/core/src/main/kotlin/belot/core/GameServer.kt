package belot.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Authoritative game host. Every player (including the one on the hosting phone) connects over TCP and
 * exchanges newline-delimited JSON messages. All game logic runs on a single thread.
 */
class GameServer(
    private val hostToken: String,
    private val preferredPort: Int = Protocol.TCP_PORT,
    private val botDelayMs: Long = 900,
) {
    private class Seat(var name: String, var bot: Boolean, var conn: Conn?) {
        var ready = false
    }

    private val loop: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "belot-server").apply { isDaemon = true }
    }
    private val seats = arrayOfNulls<Seat>(BelotGame.PLAYERS)
    private val conns = mutableListOf<Conn>()
    private var hostSeat = -1
    private var rules = Rules()
    private var game: BelotGame? = null
    private var botScheduled = false
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    var port: Int = preferredPort
        private set

    /** Number of occupied seats, for the discovery beacon. */
    @Volatile var playerCount: Int = 0
        private set
    @Volatile var hostName: String = ""
        private set
    @Volatile var started: Boolean = false
        private set

    fun start() {
        var ss: ServerSocket? = null
        for (p in preferredPort until preferredPort + 10) {
            try {
                ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(p)) }
                port = p
                break
            } catch (_: Exception) {
            }
        }
        val socket = ss ?: ServerSocket(0).also { port = it.localPort }
        serverSocket = socket
        running = true
        Thread({
            while (running) {
                try {
                    val s = socket.accept()
                    s.tcpNoDelay = true
                    s.soTimeout = Protocol.READ_TIMEOUT_MS
                    val c = Conn(s)
                    loop.execute { conns += c }
                    c.startReading()
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }, "belot-accept").apply { isDaemon = true }.start()
        loop.scheduleWithFixedDelay({ conns.forEach { it.send(JSONObject().put("t", "ping")) } }, 5, 5, TimeUnit.SECONDS)
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        loop.execute {
            conns.toList().forEach { it.close() }
            loop.shutdown()
        }
    }

    private inner class Conn(private val socket: Socket) {
        var seat = -1
        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        private val out = Executors.newSingleThreadExecutor { r -> Thread(r, "belot-out").apply { isDaemon = true } }
        @Volatile private var closed = false

        fun startReading() {
            Thread({
                try {
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val msg = try { JSONObject(line) } catch (_: Exception) { continue }
                        loop.execute { handle(this, msg) }
                    }
                } catch (_: Exception) {
                }
                loop.execute { onDisconnect(this) }
            }, "belot-in").apply { isDaemon = true }.start()
        }

        fun send(msg: JSONObject) {
            if (closed) return
            val line = msg.toString()
            try { out.execute {
                try {
                    writer.write(line)
                    writer.write("\n")
                    writer.flush()
                } catch (_: SocketException) {
                    close()
                } catch (_: Exception) {
                    close()
                }
            } } catch (_: java.util.concurrent.RejectedExecutionException) {
            }
        }

        fun close() {
            if (closed) return
            closed = true
            try {
                out.execute { try { socket.close() } catch (_: Exception) {} }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                try { socket.close() } catch (_: Exception) {}
            }
            out.shutdown()
        }
    }

    // ---- everything below runs on the loop thread ----

    private fun sendError(c: Conn, text: String) = c.send(JSONObject().put("t", "error").put("msg", text))

    private fun isHost(c: Conn) = c.seat >= 0 && c.seat == hostSeat

    private fun handle(c: Conn, msg: JSONObject) {
        try {
            when (msg.optString("t")) {
                "hello" -> hello(c, msg)
                "ping" -> {}
                "bye" -> { c.close(); onDisconnect(c) }
                else -> {
                    if (c.seat < 0) return
                    command(c, msg)
                }
            }
        } catch (e: IllegalStateException) {
            sendError(c, e.message ?: "Ошибка")
        } catch (e: IllegalArgumentException) {
            sendError(c, e.message ?: "Ошибка")
        }
    }

    private fun hello(c: Conn, msg: JSONObject) {
        if (c.seat >= 0) return
        val name = msg.optString("name").trim().take(16).ifEmpty { "Игрок" }
        val wantsHost = msg.optString("token") == hostToken
        val g = game
        val seatIndex: Int? = if (g == null) {
            seats.indices.firstOrNull { seats[it] == null }
        } else {
            // Rejoin: prefer a disconnected seat with the same name, then any disconnected human seat.
            seats.indices.firstOrNull { seats[it]?.let { s -> !s.bot && s.conn == null && s.name == name } == true }
                ?: seats.indices.firstOrNull { seats[it]?.let { s -> !s.bot && s.conn == null } == true }
        }
        if (seatIndex == null) {
            c.send(JSONObject().put("t", "closed").put("msg", if (g == null) "Стол уже заполнен" else "Игра уже идёт"))
            c.close()
            return
        }
        val existing = seats[seatIndex]
        if (existing == null) {
            seats[seatIndex] = Seat(uniqueName(name), false, c)
        } else {
            existing.conn = c
        }
        c.seat = seatIndex
        if (wantsHost) {
            hostSeat = seatIndex
            hostName = seats[seatIndex]!!.name
        }
        started = g != null
        changed()
    }

    private fun uniqueName(name: String): String {
        val taken = seats.mapNotNull { it?.name }.toSet()
        if (name !in taken) return name
        var i = 2
        while ("$name $i" in taken) i++
        return "$name $i"
    }

    private fun command(c: Conn, msg: JSONObject) {
        val g = game
        when (msg.optString("t")) {
            "addbot" -> {
                check(isHost(c) && g == null) { "Только создатель стола может добавить бота" }
                val free = seats.indices.firstOrNull { seats[it] == null } ?: error("Мест нет")
                var n = 1
                while (seats.any { it?.name == "Бот $n" }) n++
                seats[free] = Seat("Бот $n", true, null)
            }
            "remove" -> {
                check(isHost(c) && g == null) { "Только создатель стола может убрать игрока" }
                val i = msg.getInt("seat")
                require(i != hostSeat && i in seats.indices) { "Нельзя" }
                seats[i]?.conn?.let {
                    it.send(JSONObject().put("t", "closed").put("msg", "Вас убрали со стола"))
                    it.close()
                    conns -= it
                }
                seats[i] = null
            }
            "rules" -> {
                check(isHost(c) && g == null) { "Правила меняет создатель стола" }
                rules = Codec.decodeRules(msg.getJSONObject("rules"))
            }
            "start" -> {
                check(isHost(c) && g == null) { "Игру начинает создатель стола" }
                check(seats.all { it != null }) { "Нужно три игрока" }
                game = BelotGame(seats.map { it!!.name }, rules)
                started = true
            }
            "tobot" -> {
                check(isHost(c)) { "Только создатель стола" }
                val i = msg.getInt("seat")
                val s = seats.getOrNull(i) ?: error("Нет такого места")
                check(s.conn == null && !s.bot) { "Игрок на связи" }
                s.bot = true
                s.name = s.name + " (бот)"
                g?.names?.set(i, s.name)
            }
            "bid" -> {
                checkNotNull(g) { "Игра не начата" }
                val suit = msg.optString("suit").takeIf { it.isNotEmpty() }?.let { Suit.fromCode(it[0]) }
                g.bid(c.seat, msg.getBoolean("take"), suit)
            }
            "play" -> {
                checkNotNull(g) { "Игра не начата" }
                g.play(c.seat, Card.parse(msg.getString("card")))
            }
            "ready" -> {
                checkNotNull(g) { "Игра не начата" }
                if (g.phase == Phase.HAND_OVER) seats[c.seat]!!.ready = true
            }
            "newgame" -> {
                checkNotNull(g) { "Игра не начата" }
                check(g.phase == Phase.GAME_OVER) { "Игра ещё идёт" }
                g.newGame()
            }
            else -> return
        }
        changed()
    }

    private fun onDisconnect(c: Conn) {
        if (!conns.remove(c)) return
        c.close()
        val i = c.seat
        if (i < 0) return
        val s = seats[i] ?: return
        if (s.conn !== c) return
        s.conn = null
        if (game == null && i != hostSeat) seats[i] = null
        changed()
    }

    private fun changed() {
        val g = game
        if (g != null && g.phase == Phase.HAND_OVER) {
            seats.forEach { if (it!!.bot) it.ready = true }
            if (seats.all { it!!.ready }) {
                seats.forEach { it!!.ready = false }
                g.nextHand()
            }
        }
        playerCount = seats.count { it != null }
        broadcast()
        scheduleBot()
    }

    private fun scheduleBot() {
        val g = game ?: return
        val a = g.actor
        if (a < 0 || botScheduled || seats[a]?.bot != true) return
        botScheduled = true
        loop.schedule({
            botScheduled = false
            val actor = g.actor
            try {
                if (game === g && actor >= 0 && seats[actor]?.bot == true) {
                    if (g.phase == Phase.BIDDING) {
                        val b = Bot.chooseBid(g, actor)
                        g.bid(actor, b.take, b.suit)
                    } else {
                        g.play(actor, Bot.chooseCard(g, actor))
                    }
                }
            } catch (e: Exception) {
                System.err.println("Bot failed: $e")
            }
            changed()
        }, botDelayMs, TimeUnit.MILLISECONDS)
    }

    private fun broadcast() {
        val players = JSONArray()
        seats.forEach { s ->
            players.put(
                if (s == null) JSONObject.NULL
                else JSONObject().put("name", s.name).put("bot", s.bot).put("online", s.bot || s.conn != null)
                    .put("ready", s.ready)
            )
        }
        val g = game
        for (c in conns) {
            if (c.seat < 0) continue
            val msg = JSONObject()
                .put("t", "state")
                .put("you", c.seat)
                .put("hostSeat", hostSeat)
                .put("players", players)
                .put("rules", Codec.encodeRules(rules))
            if (g != null) msg.put("game", Codec.encodeView(g.viewFor(c.seat)))
            c.send(msg)
        }
    }
}

object Protocol {
    const val TCP_PORT = 47321
    const val UDP_PORT = 47322
    const val READ_TIMEOUT_MS = 20_000
    const val MAGIC = "BELOT3"
}
