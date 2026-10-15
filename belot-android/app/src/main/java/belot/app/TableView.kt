package belot.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import belot.core.Card
import belot.core.Phase
import belot.core.Play
import belot.core.PlayerView
import kotlin.math.min

/** The card table, drawn from the point of view of the local player (always at the bottom). */
@SuppressLint("ViewConstructor")
class TableView(context: Context, private val onPlay: (Card) -> Unit, private val onHint: (String) -> Unit) : View(context) {
    var state: TableState? = null
        set(value) {
            val oldHand = field?.game?.hand
            field = value
            val g = value?.game
            if (g == null || g.hand != oldHand || g.phase != Phase.PLAYING || g.currentPlayer != g.seat) selected = null
            invalidate()
        }

    /** Distance from the bottom of the view to the top of the player's hand, for placing overlays. */
    var handTopFromBottom = 0f
        private set

    private var selected: Card? = null
    private val handRects = mutableListOf<Pair<Card, RectF>>()
    private val density = resources.displayMetrics.density

    private val bg = Paint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        typeface = Typeface.DEFAULT_BOLD
    }
    private val tmp = RectF()

    // Layout, recomputed in onSizeChanged.
    private var cw = 0f      // hand card width
    private var tw = 0f      // trick card width
    private var sw = 0f      // small (opponent) card width
    private var topBar = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val W = w.toFloat()
        val H = h.toFloat()
        cw = min(W / 3.9f, H * 0.19f / CardPainter.ASPECT)
        tw = min(W / 4.6f, H * 0.15f / CardPainter.ASPECT)
        sw = min(W / 10f, H * 0.075f / CardPainter.ASPECT)
        topBar = 48 * density
        handTopFromBottom = cw * CardPainter.ASPECT + H * 0.02f + 30 * density
        bg.shader = RadialGradient(W / 2, H * 0.45f, maxOf(W, H) * 0.75f, 0xFF1F7A4A.toInt(), 0xFF0A3B22.toInt(), Shader.TileMode.CLAMP)
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    override fun onDraw(canvas: Canvas) {
        val W = width.toFloat()
        val H = height.toFloat()
        canvas.drawRect(0f, 0f, W, H, bg)
        val st = state ?: return
        val g = st.game ?: return
        val me = g.seat
        val n = g.playerCount

        drawTopBar(canvas, st, g, W)

        // Opponents along the top, in table order (the next player sits to the right).
        val oppY = topBar + 14 * density
        val columnWidth = W / (n - 1)
        for (r in n - 1 downTo 1) {
            val seat = (me + r) % n
            val column = n - 1 - r
            drawOpponent(canvas, st, g, seat, columnWidth * (column + 0.5f), oppY, columnWidth)
        }

        // Recent events.
        val oppBottom = oppY + sp(14f) + 30 * density + sw * CardPainter.ASPECT + sp(32f) + 18 * density
        text.textSize = sp(12f)
        text.textAlign = Paint.Align.CENTER
        text.alpha = 200
        g.log.takeLast(2).forEachIndexed { i, line ->
            canvas.drawText(ellipsize(line, text, W - 24 * density), W / 2, oppBottom + i * sp(16f), text)
        }
        text.alpha = 255

        val cx = W / 2
        val handTop = H - handTopFromBottom
        val cy = (oppBottom + sp(32f) + handTop - 34 * density) / 2
        val th = tw * CardPainter.ASPECT

        when (g.phase) {
            Phase.BIDDING -> {
                val turned = g.turnedCard
                bold.textSize = sp(14f)
                bold.textAlign = Paint.Align.CENTER
                canvas.drawText(if (g.bidRound == 1) "Открытая карта" else "Торговля: второй круг", cx, cy - th / 2 - 10 * density, bold)
                tmp.set(cx - tw * 1.05f, cy - th / 2, cx - tw * 0.05f, cy + th / 2)
                CardPainter.drawBack(canvas, tmp)
                if (turned != null) {
                    tmp.set(cx + tw * 0.05f, cy - th / 2, cx + tw * 1.05f, cy + th / 2)
                    CardPainter.drawFace(canvas, turned, tmp, dim = g.bidRound == 2)
                }
            }
            else -> {
                val showingLast = g.trick.isEmpty() && g.lastTrick.isNotEmpty()
                val plays = if (showingLast) g.lastTrick else g.trick
                for (p in plays) drawTrickCard(canvas, p, (p.player - me + n) % n, n, cx, cy, dim = showingLast)
                if (showingLast && g.lastTrickWinner >= 0) {
                    text.textSize = sp(13f)
                    text.textAlign = Paint.Align.CENTER
                    val label = if (g.lastTrickWinner == me) "Взятка ваша" else "Взятку взял(а) ${st.name(g.lastTrickWinner)}"
                    canvas.drawText(label, cx, cy + th * 0.8f + 18 * density, text)
                }
            }
        }

        // Status line and own info.
        val infoY = handTop - 12 * density
        bold.textAlign = Paint.Align.CENTER
        bold.textSize = sp(16f)
        val status = statusText(st, g)
        if (status.second) {
            fill.color = 0xFFF2B705.toInt()
            val tWidth = bold.measureText(status.first) + 28 * density
            tmp.set(cx - tWidth / 2, infoY - sp(16f) - 24 * density, cx + tWidth / 2, infoY - 12 * density)
            canvas.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, fill)
            bold.color = 0xFF1B1B1B.toInt()
            canvas.drawText(status.first, cx, tmp.bottom - (tmp.height() - sp(16f)) / 2 - 3 * density, bold)
            bold.color = 0xFFFFFFFF.toInt()
        } else if (status.first.isNotEmpty()) {
            canvas.drawText(status.first, cx, infoY - 20 * density, bold)
        }
        text.textSize = sp(13f)
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(ellipsize((listOf(myScoreLine(g)) + badges(g, me)).joinToString(" · "), text, W - 16 * density), cx, infoY + 4 * density, text)

        drawHand(canvas, g, W, H)
    }

    private fun statusText(st: TableState, g: PlayerView): Pair<String, Boolean> {
        val myTurn = g.currentPlayer == g.seat
        return when (g.phase) {
            Phase.BIDDING -> if (myTurn) "" to false else "Решает ${st.name(g.currentPlayer)}…" to false
            Phase.PLAYING -> when {
                myTurn && selected != null -> "Нажмите ещё раз, чтобы сыграть" to true
                myTurn -> "Ваш ход" to true
                st.players.getOrNull(g.currentPlayer)?.online == false -> "${st.name(g.currentPlayer)} не на связи…" to false
                else -> "Ходит ${st.name(g.currentPlayer)}…" to false
            }
            else -> "" to false
        }
    }

    private fun myScoreLine(g: PlayerView): String {
        val mySide = g.sideOf(g.seat)
        val parts = if (g.teams) {
            mutableListOf("Мы: ${g.totals[mySide]}", "Они: ${g.totals[1 - mySide]}")
        } else {
            mutableListOf("Вы: ${g.totals[mySide]}")
        }
        if (g.phase != Phase.BIDDING) parts += "взяток ${g.tricksWon[g.seat]}"
        return parts.joinToString(" · ")
    }

    /** Line under an opponent: their score (team score is shown in the player's own line) and tricks. */
    private fun opponentScoreLine(g: PlayerView, seat: Int): String {
        val parts = mutableListOf<String>()
        if (!g.teams) parts += "очки: ${g.totals[g.sideOf(seat)]}"
        if (g.phase != Phase.BIDDING) parts += "взяток ${g.tricksWon[seat]}"
        return parts.joinToString(" · ")
    }

    private fun badges(g: PlayerView, seat: Int): List<String> {
        val parts = mutableListOf<String>()
        if (g.teams && seat != g.seat && g.sideOf(seat) == g.sideOf(g.seat)) parts += "партнёр"
        if (g.dealer == seat) parts += "сдаёт"
        if (g.taker == seat) parts += "играет"
        if (g.beloteHolder == seat) parts += "бела"
        return parts
    }

    private fun drawTopBar(canvas: Canvas, st: TableState, g: PlayerView, W: Float) {
        fill.color = 0x66000000
        canvas.drawRect(0f, 0f, W, topBar, fill)
        val left = 56 * density
        text.textAlign = Paint.Align.LEFT
        text.textSize = sp(13f)
        canvas.drawText("Раздача №${g.handNumber}", left, topBar / 2 - 2 * density, text)
        text.alpha = 190
        text.textSize = sp(11f)
        canvas.drawText("игра до ${g.rules.target}", left, topBar / 2 + sp(12f), text)
        text.alpha = 255

        // Trump badge on the right.
        val trump = g.trump
        bold.textSize = sp(13f)
        bold.textAlign = Paint.Align.RIGHT
        val badge = topBar - 14 * density
        val bx = W - 12 * density - badge
        tmp.set(bx, 7 * density, bx + badge, 7 * density + badge)
        fill.color = 0xFFFFFDF6.toInt()
        canvas.drawRoundRect(tmp, 6 * density, 6 * density, fill)
        if (trump != null) {
            val s = badge * 0.7f
            CardPainter.drawSuit(canvas, trump, tmp.centerX() - s / 2, tmp.centerY() - s / 2, s)
            val who = if (g.taker == g.seat) "вы" else st.name(g.taker)
            canvas.drawText("Козырь", bx - 8 * density, topBar / 2 - 2 * density, bold)
            text.textAlign = Paint.Align.RIGHT
            text.textSize = sp(11f)
            canvas.drawText(ellipsize("играет: $who", text, W * 0.35f), bx - 8 * density, topBar / 2 + sp(12f), text)
        } else {
            bold.color = 0xFF1B1B1B.toInt()
            bold.textAlign = Paint.Align.CENTER
            bold.textSize = badge * 0.6f
            canvas.drawText("?", tmp.centerX(), tmp.centerY() + badge * 0.2f, bold)
            bold.color = 0xFFFFFFFF.toInt()
            bold.textAlign = Paint.Align.RIGHT
            bold.textSize = sp(13f)
            canvas.drawText("Козырь", bx - 8 * density, topBar / 2 + 4 * density, bold)
        }
    }

    private fun drawOpponent(canvas: Canvas, st: TableState, g: PlayerView, seat: Int, x: Float, y: Float, columnWidth: Float) {
        val info = st.players.getOrNull(seat)
        val active = g.currentPlayer == seat
        val maxText = columnWidth - 8 * density
        val name = ellipsize(st.name(seat), bold.apply { textSize = sp(14f) }, maxText - 24 * density)

        // Name pill.
        val pillW = bold.measureText(name) + 24 * density
        val pillH = sp(14f) + 14 * density
        tmp.set(x - pillW / 2, y, x + pillW / 2, y + pillH)
        fill.color = if (active) 0xFFF2B705.toInt() else 0x66000000
        canvas.drawRoundRect(tmp, pillH / 2, pillH / 2, fill)
        bold.textAlign = Paint.Align.CENTER
        bold.color = if (active) 0xFF1B1B1B.toInt() else 0xFFFFFFFF.toInt()
        canvas.drawText(name, x, tmp.bottom - (pillH - sp(14f)) / 2 - 2 * density, bold)
        bold.color = 0xFFFFFFFF.toInt()

        if (info != null && !info.online) {
            text.textSize = sp(11f)
            text.textAlign = Paint.Align.CENTER
            text.color = 0xFFFF8A80.toInt()
            canvas.drawText("нет связи", x, tmp.bottom + sp(12f), text)
            text.color = 0xFFFFFFFF.toInt()
        }

        // Card backs.
        val n = g.handCounts[seat]
        val partner = g.teams && g.sideOf(seat) == g.sideOf(g.seat)
        val sh = sw * CardPainter.ASPECT
        val step = min(sw * 0.32f, (maxText - sw) / (n - 1).coerceAtLeast(1))
        val total = sw + step * (n - 1).coerceAtLeast(0)
        val top = y + pillH + 16 * density
        for (i in 0 until n) {
            val l = x - total / 2 + i * step
            tmp.set(l, top, l + sw, top + sh)
            CardPainter.drawBack(canvas, tmp)
        }

        // Pass bubble during bidding.
        if (g.phase == Phase.BIDDING && g.passed[seat]) {
            bold.textSize = sp(13f)
            val bw = bold.measureText("Пас") + 20 * density
            tmp.set(x - bw / 2, top + sh / 2 - sp(13f), x + bw / 2, top + sh / 2 + sp(13f) * 0.6f)
            fill.color = 0xEEFFFFFF.toInt()
            canvas.drawRoundRect(tmp, 8 * density, 8 * density, fill)
            bold.color = 0xFF1B1B1B.toInt()
            canvas.drawText("Пас", x, tmp.bottom - 6 * density, bold)
            bold.color = 0xFFFFFFFF.toInt()
        }

        text.textSize = sp(12f)
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(ellipsize(opponentScoreLine(g, seat), text, maxText), x, top + sh + sp(16f), text)
        val b = badges(g, seat)
        if (b.isNotEmpty()) {
            text.color = if (partner) 0xFF90CAF9.toInt() else 0xFFF2B705.toInt()
            canvas.drawText(ellipsize(b.joinToString(" · "), text, maxText), x, top + sh + sp(32f), text)
            text.color = 0xFFFFFFFF.toInt()
        }
    }

    /** [rel] is the player's position relative to the viewer: 0 = me, 1 = next player (to the right), ... */
    private fun drawTrickCard(canvas: Canvas, p: Play, rel: Int, n: Int, cx: Float, cy: Float, dim: Boolean) {
        val th = tw * CardPainter.ASPECT
        val (dx, dy) = when (n) {
            2 -> if (rel == 0) 0f to th * 0.28f else 0f to -th * 0.28f
            3 -> when (rel) {
                0 -> 0f to th * 0.28f
                1 -> tw * 0.62f to -th * 0.16f
                else -> -tw * 0.62f to -th * 0.16f
            }
            else -> when (rel) {
                0 -> 0f to th * 0.36f
                1 -> tw * 0.95f to 0f
                2 -> 0f to -th * 0.36f
                else -> -tw * 0.95f to 0f
            }
        }
        tmp.set(cx + dx - tw / 2, cy + dy - th / 2, cx + dx + tw / 2, cy + dy + th / 2)
        CardPainter.drawFace(canvas, p.card, tmp, dim = dim)
    }

    private fun drawHand(canvas: Canvas, g: PlayerView, W: Float, H: Float) {
        handRects.clear()
        val cards = g.hand
        if (cards.isEmpty()) return
        val n = cards.size
        val cw = if (n > 8) min(this.cw, W / 4.4f) else this.cw
        val ch = cw * CardPainter.ASPECT
        val margin = 10 * density
        val step = if (n > 1) min(cw * 0.95f, (W - 2 * margin - cw) / (n - 1)) else 0f
        val total = cw + step * (n - 1)
        val startX = (W - total) / 2
        val baseTop = H - ch - H * 0.02f
        val myTurn = g.phase == Phase.PLAYING && g.currentPlayer == g.seat
        cards.forEachIndexed { i, card ->
            val l = startX + i * step
            val raised = card == selected
            val t = if (raised) baseTop - ch * 0.18f else baseTop
            val r = RectF(l, t, l + cw, t + ch)
            val legal = card in g.legal
            CardPainter.drawFace(canvas, card, r, dim = myTurn && !legal, highlight = raised)
            handRects += card to r
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action != MotionEvent.ACTION_UP) return true
        val g = state?.game ?: return true
        val hit = handRects.lastOrNull { it.second.contains(event.x, event.y) }?.first
        if (hit == null) {
            if (selected != null) {
                selected = null
                invalidate()
            }
            return true
        }
        if (g.phase != Phase.PLAYING) return true
        if (g.currentPlayer != g.seat) {
            onHint("Сейчас не ваш ход")
            return true
        }
        if (hit !in g.legal) {
            onHint("Этой картой ходить нельзя: нужно ходить в масть или козырем")
            return true
        }
        if (selected == hit) {
            selected = null
            onPlay(hit)
        } else {
            selected = hit
        }
        invalidate()
        return true
    }

    private fun ellipsize(s: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 1 && paint.measureText(s.substring(0, end) + "…") > maxWidth) end--
        return s.substring(0, end) + "…"
    }
}
