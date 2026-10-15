package belot.app

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import belot.core.Phase
import belot.core.PlayerView
import belot.core.Suit
import org.json.JSONObject

/** In-game screen: the table plus bidding / result / connection overlays. */
@SuppressLint("ViewConstructor")
class GameScreen(context: Context, private val onMenu: () -> Unit, private val onLeave: () -> Unit) : FrameLayout(context) {
    private val table = TableView(context, onPlay = { card ->
        Session.send(JSONObject().put("t", "play").put("card", card.code))
    }, onHint = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() })

    private val bidPanel = context.column(10).apply {
        background = rounded(Colors.PANEL, context.dp(18f).toFloat(), Colors.GOLD, context.dp(1))
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = GONE
    }
    private var bidKey = ""

    private val resultScroll = ScrollView(context).apply { visibility = GONE }
    private val resultPanel = context.column(18).apply {
        background = rounded(Colors.PANEL, context.dp(18f).toFloat(), Colors.GOLD, context.dp(1))
    }
    private var resultKey = ""

    private val banner = context.column(10).apply {
        background = rounded(0xEE5D1A1A.toInt(), context.dp(12f).toFloat())
        visibility = GONE
    }
    private var bannerKey = ""

    init {
        setBackgroundColor(Colors.FELT_DARK)
        addView(table, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val menu = TextView(context).apply {
            text = "☰"
            textSize = 22f
            setTextColor(Colors.TEXT)
            gravity = Gravity.CENTER
            setOnClickListener { onMenu() }
        }
        addView(menu, LayoutParams(context.dp(48), context.dp(48), Gravity.TOP or Gravity.START))

        addView(bidPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = context.dp(16)
            rightMargin = context.dp(16)
        })
        resultScroll.addView(resultPanel)
        addView(resultScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = context.dp(14)
            rightMargin = context.dp(14)
            topMargin = context.dp(56)
            bottomMargin = context.dp(16)
        })
        addView(banner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            topMargin = context.dp(52)
            leftMargin = context.dp(12)
            rightMargin = context.dp(12)
        })
        table.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeBidPanel() }
    }

    private fun placeBidPanel() {
        val lp = bidPanel.layoutParams as LayoutParams
        val m = table.handTopFromBottom.toInt() + context.dp(4)
        if (lp.bottomMargin != m) {
            lp.bottomMargin = m
            bidPanel.layoutParams = lp
        }
    }

    fun update(st: TableState) {
        val g = st.game ?: return
        table.state = st
        updateBid(st, g)
        updateResult(st, g)
        updateBanner(st, g)
    }

    private fun updateBid(st: TableState, g: PlayerView) {
        val show = g.phase == Phase.BIDDING && g.currentPlayer == g.seat
        val key = if (show) "${g.handNumber}/${g.bidRound}/${g.turnedCard}" else ""
        if (key == bidKey) return
        bidKey = key
        bidPanel.removeAllViews()
        bidPanel.visibility = if (show) VISIBLE else GONE
        if (!show) return
        val turned = g.turnedCard!!
        val ctx = context
        if (g.bidRound == 1) {
            bidPanel.add(ctx.label("Козырь «${turned.suit.title}» — берёте?", 18f, bold = true).apply {
                gravity = Gravity.CENTER
            }, top = 0)
            val r = ctx.row()
            r.add(ctx.button("Беру") { sendBid(true, null) }, top = 0, width = 0, weight = 1f)
            r.addView(View(ctx), LinearLayout.LayoutParams(ctx.dp(12), 1))
            r.add(ctx.button("Пас", ButtonStyle.SECONDARY) { sendBid(false, null) }, top = 0, width = 0, weight = 1f)
            bidPanel.add(r, top = 10)
        } else {
            bidPanel.add(ctx.label("Назовите другой козырь или пас", 18f, bold = true).apply { gravity = Gravity.CENTER }, top = 0)
            val r = ctx.row()
            r.gravity = Gravity.CENTER
            for (s in Suit.entries.filter { it != turned.suit }) {
                r.addView(SuitButton(ctx, s) { sendBid(true, s) }, LinearLayout.LayoutParams(ctx.dp(56), ctx.dp(56)).apply {
                    marginEnd = ctx.dp(10)
                })
            }
            r.addView(ctx.button("Пас", ButtonStyle.SECONDARY) { sendBid(false, null) }, LinearLayout.LayoutParams(ctx.dp(90), ctx.dp(56)))
            bidPanel.add(r, top = 10)
        }
    }

    private fun sendBid(take: Boolean, suit: Suit?) {
        val m = JSONObject().put("t", "bid").put("take", take)
        if (suit != null) m.put("suit", suit.code.toString())
        Session.send(m)
        bidPanel.visibility = GONE
    }

    private fun updateResult(st: TableState, g: PlayerView) {
        val show = (g.phase == Phase.HAND_OVER || g.phase == Phase.GAME_OVER) && g.result != null
        val key = if (show) "${g.handNumber}/${g.phase}/${st.players.map { it?.ready }}/${st.isHost}" else ""
        if (key == resultKey) return
        resultKey = key
        resultScroll.visibility = if (show) VISIBLE else GONE
        resultPanel.removeAllViews()
        if (!show) return
        val r = g.result!!
        val ctx = context
        val over = g.phase == Phase.GAME_OVER

        resultPanel.add(ctx.label(
            if (over) "Игра окончена! Победитель: ${st.name(g.winner)}" else "Раздача №${g.handNumber} окончена",
            20f, bold = true, color = if (over) Colors.GOLD else Colors.TEXT,
        ), top = 0)
        resultPanel.add(ctx.label("Козырь: ${r.trump.title}, играл(а) ${st.name(r.taker)}", 15f, color = Colors.TEXT_DIM), top = 4)

        for (p in 0 until 3) {
            val parts = mutableListOf("карты ${r.cardPoints[p]}")
            if (r.comboPoints[p] > 0) parts += "комбинации ${r.comboPoints[p]}"
            if (r.belote[p] > 0) parts += "бела ${r.belote[p]}"
            if (r.lastTrick[p] > 0) parts += "последняя ${r.lastTrick[p]}"
            if (r.capot[p] > 0) parts += "все взятки ${r.capot[p]}"
            val tag = if (p == r.taker) " (играл(а))" else ""
            val name = if (p == g.seat) "Вы" else st.name(p)
            val block = ctx.column(0)
            block.add(ctx.label("$name$tag: +${r.awarded[p]}", 17f, bold = true), top = 0)
            block.add(ctx.label("${parts.joinToString(" + ")} = ${r.raw[p]}", 14f, color = Colors.TEXT_DIM), top = 2)
            resultPanel.add(block, top = 12)
        }

        val counted = g.declarations.filter { it.counted }
        if (counted.isNotEmpty()) {
            resultPanel.add(ctx.label("Комбинации: " + counted.joinToString(", ") {
                "${st.name(it.player)} — ${it.combo.title} (${it.combo.points})"
            }, 14f, color = Colors.TEXT_DIM), top = 10)
        }

        val takerName = st.name(r.taker)
        val verdict = if (r.takerMade) {
            "$takerName выполнил(а) игру — каждый получает свои очки."
        } else if (g.rules.takerMustBeatEach) {
            "$takerName не набрал(а) больше каждого соперника — его очки (кроме белы) делятся между соперниками."
        } else {
            "$takerName не набрал(а) больше половины очков — его очки (кроме белы) делятся между соперниками."
        }
        resultPanel.add(ctx.label(verdict, 15f, color = if (r.takerMade) 0xFFA5D6A7.toInt() else 0xFFFFAB91.toInt()), top = 12)

        resultPanel.add(ctx.label("Общий счёт (до ${g.rules.target}):", 15f, bold = true), top = 14)
        val order = (0 until 3).sortedByDescending { g.totals[it] }
        for (p in order) {
            resultPanel.add(ctx.label("${if (p == g.seat) "Вы" else st.name(p)} — ${g.totals[p]}", 16f), top = 2)
        }

        if (over) {
            if (st.isHost) {
                resultPanel.add(ctx.button("Новая игра") { Session.send(JSONObject().put("t", "newgame")) }, top = 16)
            } else {
                resultPanel.add(ctx.label("Новую игру может начать создатель стола.", 14f, color = Colors.TEXT_DIM), top = 16)
            }
            resultPanel.add(ctx.button("Выйти в меню", ButtonStyle.SECONDARY) { onLeave() }, top = 10)
        } else {
            val iAmReady = st.players.getOrNull(g.seat)?.ready == true
            if (iAmReady) {
                val waiting = (0 until 3).filter { st.players[it]?.ready != true }.map { st.name(it) }
                resultPanel.add(ctx.label("Ждём: ${waiting.joinToString(", ")}", 15f, color = Colors.TEXT_DIM).apply {
                    gravity = Gravity.CENTER
                }, top = 16)
            } else {
                resultPanel.add(ctx.button("Дальше") { Session.send(JSONObject().put("t", "ready")) }, top = 16)
            }
        }
    }

    private fun updateBanner(st: TableState, g: PlayerView) {
        val offline = (0 until 3).filter { st.players.getOrNull(it)?.online == false }
        val key = offline.joinToString() + "/" + st.isHost
        if (key == bannerKey) return
        bannerKey = key
        banner.removeAllViews()
        banner.visibility = if (offline.isEmpty()) GONE else VISIBLE
        if (offline.isEmpty()) return
        val ctx = context
        banner.add(ctx.label(
            "Нет связи: ${offline.joinToString { st.name(it) }}. Игрок может вернуться, нажав «Присоединиться» с тем же именем.",
            14f,
        ), top = 0)
        if (st.isHost) {
            for (p in offline) {
                banner.add(ctx.button("Заменить ботом: ${st.name(p)}", ButtonStyle.SECONDARY) {
                    Session.send(JSONObject().put("t", "tobot").put("seat", p))
                }, top = 8)
            }
        }
    }
}
