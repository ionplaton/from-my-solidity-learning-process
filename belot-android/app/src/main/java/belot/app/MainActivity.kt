package belot.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import belot.core.Codec
import belot.core.DiscoveryListener
import belot.core.HostInfo
import belot.core.NetUtil
import belot.core.Protocol
import belot.core.Rules
import org.json.JSONObject

class MainActivity : Activity(), Session.Listener {
    private enum class Screen { MENU, JOIN, CONNECTING, LOBBY, GAME, RULES }

    private lateinit var root: FrameLayout
    private var screen = Screen.MENU
    private var gameScreen: GameScreen? = null
    private var lobbyKey = ""
    private var dialog: AlertDialog? = null

    private var discovery: DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val found = LinkedHashMap<String, HostInfo>()
    private var foundList: LinearLayout? = null
    private val main = Handler(Looper.getMainLooper())

    private val prefs by lazy { getSharedPreferences("belot", Context.MODE_PRIVATE) }
    private var playerName: String
        get() = prefs.getString("name", "") ?: ""
        set(v) = prefs.edit().putString("name", v).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Colors.FELT_DARK
        window.navigationBarColor = Colors.FELT_DARK
        root = FrameLayout(this).apply { setBackgroundColor(Colors.FELT) }
        setContentView(root)
        Session.listener = this
        if (Session.isActive && Session.state != null) onState() else if (Session.isActive) showConnecting() else showMenu()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
        if (Session.listener === this) Session.listener = null
        if (isFinishing) Session.leave()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            Screen.MENU -> super.onBackPressed()
            Screen.JOIN, Screen.RULES -> showMenu()
            Screen.CONNECTING -> { Session.leave(); showMenu() }
            Screen.LOBBY, Screen.GAME -> confirmLeave()
        }
    }

    private fun confirmLeave() {
        val text = if (Session.isHosting) "Вы создали этот стол. Если выйти, игра закончится для всех." else "Выйти из игры?"
        AlertDialog.Builder(this)
            .setTitle("Выход")
            .setMessage(text)
            .setPositiveButton("Выйти") { _, _ -> Session.leave(); showMenu() }
            .setNegativeButton("Остаться", null)
            .show()
    }

    private fun setScreen(s: Screen, content: View, scroll: Boolean = true) {
        if (screen == Screen.JOIN && s != Screen.JOIN) stopDiscovery()
        screen = s
        gameScreen = null
        lobbyKey = ""
        root.removeAllViews()
        if (scroll) {
            val sv = ScrollView(this).apply { isFillViewport = true }
            sv.addView(content)
            root.addView(sv)
        } else {
            root.addView(content)
        }
    }

    // ---------------------------------------------------------------- menu

    private fun showMenu() {
        val c = column(24).apply { gravity = Gravity.CENTER_HORIZONTAL }
        c.add(label("Белот", 44f, bold = true, color = Colors.GOLD).apply { gravity = Gravity.CENTER }, top = 24)
        c.add(label("на троих · по Wi‑Fi", 18f, color = Colors.TEXT_DIM).apply { gravity = Gravity.CENTER }, top = 0)
        c.add(label("Ваше имя", 15f, color = Colors.TEXT_DIM), top = 36)
        val name = input("Например, Ион", playerName)
        c.add(name, top = 6)

        fun takeName(): String? {
            val n = name.text.toString().trim()
            if (n.isEmpty()) {
                Toast.makeText(this, "Введите имя", Toast.LENGTH_SHORT).show()
                return null
            }
            playerName = n
            return n
        }

        c.add(button("Создать стол") {
            val n = takeName() ?: return@button
            Session.host(n, withBots = false)
            showConnecting()
        }, top = 24)
        c.add(button("Присоединиться к столу") {
            takeName() ?: return@button
            showJoin()
        }, top = 12)
        c.add(button("Играть с ботами", ButtonStyle.SECONDARY) {
            val n = takeName() ?: return@button
            Session.host(n, withBots = true)
            showConnecting()
        }, top = 12)
        c.add(button("Правила", ButtonStyle.SECONDARY) { showRules() }, top = 12)
        c.add(label(
            "Как играть втроём: все телефоны подключаются к одной Wi‑Fi сети (или к точке доступа одного из телефонов). " +
                "Один создаёт стол, двое других нажимают «Присоединиться».",
            14f, color = Colors.TEXT_DIM,
        ), top = 28)
        setScreen(Screen.MENU, c)
    }

    private fun showConnecting() {
        val c = column(24).apply { gravity = Gravity.CENTER }
        c.add(label("Подключение…", 22f, bold = true).apply { gravity = Gravity.CENTER }, top = 0)
        c.add(button("Отмена", ButtonStyle.SECONDARY) { Session.leave(); showMenu() }, top = 24)
        setScreen(Screen.CONNECTING, c)
    }

    // ---------------------------------------------------------------- join

    private fun showJoin() {
        val c = column(24)
        c.add(label("Присоединиться", 26f, bold = true), top = 0)
        c.add(label("Ищем столы в вашей сети…", 15f, color = Colors.TEXT_DIM), top = 8)
        val list = column(0)
        foundList = list
        c.add(list, top = 8)
        c.add(label("Не находится? Введите адрес, который показан на телефоне создателя стола:", 14f, color = Colors.TEXT_DIM), top = 24)
        val ip = input("например 192.168.1.5", prefs.getString("lastIp", "") ?: "", numeric = true)
        c.add(ip, top = 8)
        c.add(button("Подключиться по адресу") {
            val text = ip.text.toString().trim()
            if (text.isEmpty()) return@button
            prefs.edit().putString("lastIp", text).apply()
            val host = text.substringBefore(":")
            val port = text.substringAfter(":", "").toIntOrNull() ?: Protocol.TCP_PORT
            connectTo(host, port)
        }, top = 10)
        c.add(button("Назад", ButtonStyle.SECONDARY) { showMenu() }, top = 24)
        setScreen(Screen.JOIN, c)
        found.clear()
        renderFound()
        startDiscovery()
    }

    private fun connectTo(host: String, port: Int) {
        Session.join(host, port, playerName)
        showConnecting()
    }

    private fun renderFound() {
        val list = foundList ?: return
        list.removeAllViews()
        if (found.isEmpty()) {
            list.add(label("Пока ничего не найдено", 15f, color = Colors.TEXT_DIM), top = 4)
            return
        }
        for (h in found.values) {
            val status = if (h.started) "игра идёт" else "игроков ${h.players}/3"
            list.add(button("Стол «${h.hostName}» — $status") { connectTo(h.address, h.port) }, top = 8)
        }
    }

    private fun startDiscovery() {
        stopDiscovery()
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("belot").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
        discovery = DiscoveryListener({ info ->
            main.post {
                if (screen != Screen.JOIN) return@post
                val key = "${info.address}:${info.port}"
                if (found[key] != info) {
                    found[key] = info
                    renderFound()
                }
            }
        }).also { it.start() }
    }

    private fun stopDiscovery() {
        discovery?.stop()
        discovery = null
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
    }

    // ---------------------------------------------------------------- lobby

    private fun showLobby(st: TableState) {
        val key = "${st.players}/${st.rules}/${st.you}/${st.hostSeat}"
        if (screen == Screen.LOBBY && key == lobbyKey) return
        val c = column(24)
        c.add(label("Стол «${st.name(st.hostSeat)}»", 26f, bold = true), top = 0)
        if (st.isHost) {
            val ips = NetUtil.localAddresses()
            val port = Session.hostPort
            val addr = if (ips.isEmpty()) "нет сети Wi‑Fi" else ips.joinToString(", ") { if (port == Protocol.TCP_PORT) it else "$it:$port" }
            c.add(label("Друзья должны быть в той же Wi‑Fi сети (или подключены к точке доступа вашего телефона) и нажать «Присоединиться».", 14f, color = Colors.TEXT_DIM), top = 8)
            c.add(label("Адрес стола: $addr", 16f, bold = true, color = Colors.GOLD), top = 8)
        } else {
            c.add(label("Ждём, пока создатель стола начнёт игру.", 14f, color = Colors.TEXT_DIM), top = 8)
        }

        c.add(label("Игроки", 18f, bold = true), top = 20)
        for (i in 0 until 3) {
            val p = st.players.getOrNull(i)
            val r = row()
            val text = when {
                p == null -> "${i + 1}. свободно"
                else -> "${i + 1}. ${p.name}" + listOfNotNull(
                    if (i == st.you) "вы" else null,
                    if (i == st.hostSeat) "создатель" else null,
                    if (p.bot) "бот" else null,
                    if (!p.online) "нет связи" else null,
                ).let { if (it.isEmpty()) "" else " (${it.joinToString(", ")})" }
            }
            r.add(label(text, 17f, color = if (p == null) Colors.TEXT_DIM else Colors.TEXT), top = 0, width = 0, weight = 1f)
            if (st.isHost) {
                if (p == null) {
                    r.add(button("+ бот", ButtonStyle.SECONDARY) { Session.send(JSONObject().put("t", "addbot")) }, top = 0, width = dp(110))
                } else if (i != st.hostSeat) {
                    r.add(button("Убрать", ButtonStyle.SECONDARY) {
                        Session.send(JSONObject().put("t", "remove").put("seat", i))
                    }, top = 0, width = dp(110))
                }
            }
            c.add(r, top = 8)
        }

        c.add(label("Правила стола", 18f, bold = true), top = 24)
        val rules = st.rules
        if (st.isHost) {
            c.add(label("Игра до:", 15f, color = Colors.TEXT_DIM), top = 6)
            val targets = row()
            for (t in Rules.TARGETS) {
                targets.add(button("$t", if (t == rules.target) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY) {
                    sendRules(rules.copy(target = t))
                }, top = 0, width = 0, weight = 1f)
                if (t != Rules.TARGETS.last()) targets.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
            }
            c.add(targets, top = 6)
            c.add(checkbox("Взявший козырь должен набрать больше КАЖДОГО соперника (если выключено — больше половины всех очков)", rules.takerMustBeatEach) {
                sendRules(rules.copy(takerMustBeatEach = it))
            }, top = 10)
            c.add(checkbox("Нет масти и нельзя перебить козырь — всё равно класть козырь", rules.mustUnderTrump) {
                sendRules(rules.copy(mustUnderTrump = it))
            }, top = 6)
        } else {
            c.add(label(rulesSummary(rules), 15f, color = Colors.TEXT_DIM), top = 6)
        }

        if (st.isHost) {
            val start = button("Начать игру") { Session.send(JSONObject().put("t", "start")) }
            start.isEnabled = st.players.all { it != null }
            c.add(start, top = 24)
            if (!start.isEnabled) c.add(label("Нужно 3 игрока. Можно добавить бота.", 14f, color = Colors.TEXT_DIM), top = 6)
        }
        c.add(button("Выйти", ButtonStyle.SECONDARY) { confirmLeave() }, top = 12)
        setScreen(Screen.LOBBY, c)
        lobbyKey = key
    }

    private fun checkbox(text: String, checked: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        this.text = text
        isChecked = checked
        textSize = 15f
        setTextColor(Colors.TEXT)
        buttonTintList = android.content.res.ColorStateList.valueOf(Colors.GOLD)
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun sendRules(r: Rules) {
        Session.send(JSONObject().put("t", "rules").put("rules", Codec.encodeRules(r)))
    }

    private fun rulesSummary(r: Rules) = listOf(
        "Игра до ${r.target} очков.",
        if (r.takerMustBeatEach) "Взявший должен набрать больше каждого соперника." else "Взявший должен набрать больше половины очков.",
        if (r.mustUnderTrump) "Без масти козырь класть обязательно." else "Без масти, если нельзя перебить козырь, можно класть любую карту.",
    ).joinToString("\n")

    // ---------------------------------------------------------------- rules

    private fun showRules() {
        val c = column(24)
        c.add(label("Правила белота на троих", 24f, bold = true), top = 0)
        c.add(label(RulesText.TEXT, 15f), top = 12)
        c.add(button("Назад", ButtonStyle.SECONDARY) { showMenu() }, top = 20)
        setScreen(Screen.RULES, c)
    }

    // ---------------------------------------------------------------- session callbacks

    override fun onState() {
        dialog?.dismiss()
        dialog = null
        val st = Session.state ?: return
        if (st.game != null) {
            val gs = gameScreen ?: GameScreen(this, onMenu = { showGameMenu() }, onLeave = { confirmLeave() })
                .also {
                    setScreen(Screen.GAME, it, scroll = false)
                    gameScreen = it
                }
            gs.update(st)
        } else {
            showLobby(st)
        }
    }

    private fun showGameMenu() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Правила (кратко)", "Выйти из игры")) { _, which ->
                when (which) {
                    0 -> AlertDialog.Builder(this).setTitle("Правила").setMessage(RulesText.SHORT).setPositiveButton("OK", null).show()
                    1 -> confirmLeave()
                }
            }
            .show()
    }

    override fun onError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDisconnected(reason: String) {
        if (Session.isHosting) {
            Session.leave()
            showMenu()
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            return
        }
        val inGame = Session.state != null
        dialog?.dismiss()
        val b = AlertDialog.Builder(this)
            .setTitle("Нет связи со столом")
            .setMessage(reason)
            .setCancelable(false)
            .setNegativeButton("В меню") { _, _ -> Session.leave(); showMenu() }
        if (inGame) b.setPositiveButton("Переподключиться") { _, _ -> Session.reconnect() }
        dialog = b.show()
        if (!inGame) {
            Session.leave()
            showMenu()
        }
    }
}
