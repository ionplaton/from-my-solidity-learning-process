package belot.core

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Connection from a player's phone to the game host. Callbacks arrive on a background thread. */
class GameClient(
    private val host: String,
    private val port: Int,
    private val name: String,
    private val token: String?,
    private val listener: Listener,
) {
    interface Listener {
        fun onMessage(msg: JSONObject)
        fun onDisconnected(reason: String)
    }

    private val out = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "belot-client-out").apply { isDaemon = true } }
    @Volatile private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    @Volatile private var closed = false

    fun connect() {
        Thread({
            val s = Socket()
            var reason = "Соединение потеряно"
            try {
                s.connect(InetSocketAddress(host, port), 6000)
                s.tcpNoDelay = true
                s.soTimeout = Protocol.READ_TIMEOUT_MS
                socket = s
                writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                val hello = JSONObject().put("t", "hello").put("name", name).put("v", 1)
                if (token != null) hello.put("token", token)
                send(hello)
                out.scheduleWithFixedDelay({ send(JSONObject().put("t", "ping")) }, 5, 5, TimeUnit.SECONDS)
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                while (!closed) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val msg = try { JSONObject(line) } catch (_: Exception) { continue }
                    when (msg.optString("t")) {
                        "ping" -> {}
                        "closed" -> { reason = msg.optString("msg", reason); break }
                        else -> listener.onMessage(msg)
                    }
                }
            } catch (e: Exception) {
                if (socket == null) reason = "Не удалось подключиться к $host"
            }
            val wasClosed = closed
            close()
            if (!wasClosed) listener.onDisconnected(reason)
        }, "belot-client-in").apply { isDaemon = true }.start()
    }

    fun send(msg: JSONObject) {
        if (closed) return
        val line = msg.toString()
        try {
            out.execute {
                try {
                    writer?.apply {
                        write(line)
                        write("\n")
                        flush()
                    }
                } catch (_: Exception) {
                    try { socket?.close() } catch (_: Exception) {}
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    fun close() {
        if (closed) return
        send(JSONObject().put("t", "bye"))
        closed = true
        try {
            out.execute { try { socket?.close() } catch (_: Exception) {} }
        } catch (_: RejectedExecutionException) {
        }
        out.shutdown()
    }
}
