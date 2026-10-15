package belot.app

import android.os.Handler
import android.os.Looper
import belot.core.Codec
import belot.core.DiscoveryBeacon
import belot.core.GameClient
import belot.core.GameServer
import belot.core.PlayerView
import belot.core.Rules
import org.json.JSONObject
import java.util.UUID

data class SeatInfo(val name: String, val bot: Boolean, val online: Boolean, val ready: Boolean)

data class TableState(
    val you: Int,
    val hostSeat: Int,
    val players: List<SeatInfo?>,
    val rules: Rules,
    val game: PlayerView?,
) {
    val isHost: Boolean get() = you == hostSeat

    fun name(seat: Int): String = players.getOrNull(seat)?.name ?: "—"

    companion object {
        fun parse(msg: JSONObject): TableState {
            val arr = msg.getJSONArray("players")
            val players = (0 until arr.length()).map { i ->
                if (arr.isNull(i)) null else arr.getJSONObject(i).let {
                    SeatInfo(it.getString("name"), it.getBoolean("bot"), it.getBoolean("online"), it.optBoolean("ready"))
                }
            }
            return TableState(
                you = msg.getInt("you"),
                hostSeat = msg.getInt("hostSeat"),
                players = players,
                rules = Codec.decodeRules(msg.getJSONObject("rules")),
                game = if (msg.has("game")) Codec.decodeView(msg.getJSONObject("game")) else null,
            )
        }
    }
}

/**
 * Process-wide connection state: survives the activity being recreated.
 * All callbacks are delivered on the main thread.
 */
object Session {
    interface Listener {
        fun onState()
        fun onError(msg: String)
        fun onDisconnected(reason: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var server: GameServer? = null
    private var beacon: DiscoveryBeacon? = null
    private var client: GameClient? = null
    private var generation = 0
    private var token: String? = null
    /** Number of players for a practice game against bots, or 0 for a normal table. */
    private var autoStartWithBots = 0

    var listener: Listener? = null
    var state: TableState? = null
        private set
    var connected = false
        private set

    private var lastHost = ""
    private var lastPort = 0
    private var lastName = ""

    val isHosting: Boolean get() = server != null
    val isActive: Boolean get() = client != null || server != null
    val hostPort: Int get() = server?.port ?: lastPort

    fun host(name: String, botsGame: Int) {
        val withBots = botsGame > 0
        leave()
        val tok = UUID.randomUUID().toString()
        token = tok
        autoStartWithBots = botsGame
        val srv = GameServer(tok)
        server = srv
        val gen = generation
        Thread {
            srv.start()
            val b = DiscoveryBeacon(srv)
            if (!withBots) b.start()
            main.post {
                if (gen != generation) {
                    b.stop()
                    return@post
                }
                beacon = b
                connect("127.0.0.1", srv.port, name)
            }
        }.start()
    }

    fun join(host: String, port: Int, name: String) {
        leave()
        token = null
        connect(host, port, name)
    }

    fun reconnect() {
        if (lastHost.isNotEmpty()) connect(lastHost, lastPort, lastName)
    }

    private fun connect(host: String, port: Int, name: String) {
        client?.close()
        lastHost = host
        lastPort = port
        lastName = name
        val gen = ++generation
        val c = GameClient(host, port, name, token, object : GameClient.Listener {
            override fun onMessage(msg: JSONObject) {
                main.post { if (gen == generation) handle(msg) }
            }

            override fun onDisconnected(reason: String) {
                main.post {
                    if (gen != generation) return@post
                    connected = false
                    client = null
                    listener?.onDisconnected(reason)
                }
            }
        })
        client = c
        connected = false
        c.connect()
    }

    private fun handle(msg: JSONObject) {
        when (msg.optString("t")) {
            "state" -> {
                connected = true
                val st = TableState.parse(msg)
                state = st
                val bots = autoStartWithBots
                if (bots > 0 && st.isHost && st.game == null) {
                    autoStartWithBots = 0
                    send(JSONObject().put("t", "rules").put("rules", Codec.encodeRules(st.rules.copy(players = bots))))
                    repeat(bots - 1) { send(JSONObject().put("t", "addbot")) }
                    send(JSONObject().put("t", "start"))
                }
                listener?.onState()
            }
            "error" -> listener?.onError(msg.optString("msg"))
        }
    }

    fun send(msg: JSONObject) {
        client?.send(msg)
    }

    fun leave() {
        generation++
        val c = client
        val s = server
        val b = beacon
        client = null
        server = null
        beacon = null
        state = null
        connected = false
        autoStartWithBots = 0
        Thread {
            c?.close()
            b?.stop()
            s?.stop()
        }.start()
    }
}
