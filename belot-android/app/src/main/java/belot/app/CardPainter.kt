package belot.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import belot.core.Card
import belot.core.Rank
import belot.core.Suit

/** Draws cards and suit symbols with vector paths (no emoji fonts involved). */
object CardPainter {
    const val RED = 0xFFC62828.toInt()
    const val BLACK = 0xFF1B1B1B.toInt()
    const val ASPECT = 1.45f

    private val unitPaths: Map<Suit, Path> = mapOf(
        Suit.HEARTS to Path().apply {
            moveTo(0.5f, 0.28f)
            cubicTo(0.5f, 0.02f, 0.0f, 0.0f, 0.02f, 0.32f)
            cubicTo(0.04f, 0.58f, 0.32f, 0.74f, 0.5f, 0.97f)
            cubicTo(0.68f, 0.74f, 0.96f, 0.58f, 0.98f, 0.32f)
            cubicTo(1.0f, 0.0f, 0.5f, 0.02f, 0.5f, 0.28f)
            close()
        },
        Suit.DIAMONDS to Path().apply {
            moveTo(0.5f, 0.0f)
            quadTo(0.7f, 0.28f, 0.9f, 0.5f)
            quadTo(0.7f, 0.72f, 0.5f, 1.0f)
            quadTo(0.3f, 0.72f, 0.1f, 0.5f)
            quadTo(0.3f, 0.28f, 0.5f, 0.0f)
            close()
        },
        Suit.SPADES to Path().apply {
            moveTo(0.5f, 0.0f)
            cubicTo(0.32f, 0.24f, 0.0f, 0.38f, 0.02f, 0.62f)
            cubicTo(0.04f, 0.86f, 0.32f, 0.88f, 0.46f, 0.72f)
            lineTo(0.36f, 1.0f)
            lineTo(0.64f, 1.0f)
            lineTo(0.54f, 0.72f)
            cubicTo(0.68f, 0.88f, 0.96f, 0.86f, 0.98f, 0.62f)
            cubicTo(1.0f, 0.38f, 0.68f, 0.24f, 0.5f, 0.0f)
            close()
        },
        Suit.CLUBS to Path().apply {
            addCircle(0.5f, 0.26f, 0.22f, Path.Direction.CW)
            addCircle(0.25f, 0.58f, 0.22f, Path.Direction.CW)
            addCircle(0.75f, 0.58f, 0.22f, Path.Direction.CW)
            addCircle(0.5f, 0.52f, 0.12f, Path.Direction.CW)
            moveTo(0.46f, 0.55f)
            lineTo(0.36f, 1.0f)
            lineTo(0.64f, 1.0f)
            lineTo(0.54f, 0.55f)
            close()
        },
    )

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
    private val rect = RectF()

    fun suitColor(suit: Suit) = if (suit.red) RED else BLACK

    /** Draws a suit symbol inside a square of [size] whose top-left corner is (x, y). */
    fun drawSuit(canvas: Canvas, suit: Suit, x: Float, y: Float, size: Float, color: Int = suitColor(suit)) {
        fill.color = color
        fill.style = Paint.Style.FILL
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(size, size)
        canvas.drawPath(unitPaths.getValue(suit), fill)
        canvas.restore()
    }

    fun drawFace(canvas: Canvas, card: Card, r: RectF, dim: Boolean = false, highlight: Boolean = false, alpha: Int = 255) {
        val w = r.width()
        val radius = w * 0.08f
        if (alpha < 255) canvas.saveLayerAlpha(r.left - 4, r.top - 4, r.right + 4, r.bottom + 4, alpha)

        fill.style = Paint.Style.FILL
        fill.color = 0x55000000
        rect.set(r.left + w * 0.02f, r.top + w * 0.03f, r.right + w * 0.02f, r.bottom + w * 0.03f)
        canvas.drawRoundRect(rect, radius, radius, fill)
        fill.color = 0xFFFFFDF6.toInt()
        canvas.drawRoundRect(r, radius, radius, fill)
        stroke.color = if (highlight) 0xFFF2B705.toInt() else 0xFF9E9E9E.toInt()
        stroke.strokeWidth = if (highlight) w * 0.05f else maxOf(1f, w * 0.012f)
        canvas.drawRoundRect(r, radius, radius, stroke)

        val color = suitColor(card.suit)
        drawCorner(canvas, card, r, color)
        canvas.save()
        canvas.rotate(180f, r.centerX(), r.centerY())
        drawCorner(canvas, card, r, color)
        canvas.restore()

        val h = r.height()
        when (card.rank) {
            Rank.JACK, Rank.QUEEN, Rank.KING -> {
                rect.set(r.left + w * 0.22f, r.top + h * 0.17f, r.right - w * 0.22f, r.bottom - h * 0.17f)
                stroke.color = color
                stroke.strokeWidth = maxOf(1f, w * 0.015f)
                canvas.drawRoundRect(rect, w * 0.04f, w * 0.04f, stroke)
                text.color = color
                text.textSize = w * 0.42f
                text.textAlign = Paint.Align.CENTER
                text.textScaleX = 1f
                canvas.drawText(card.rank.label, r.centerX(), r.centerY() - h * 0.01f, text)
                val s = w * 0.24f
                drawSuit(canvas, card.suit, r.centerX() - s / 2, r.centerY() + h * 0.04f, s, color)
            }
            else -> {
                val s = if (card.rank == Rank.ACE) w * 0.5f else w * 0.4f
                drawSuit(canvas, card.suit, r.centerX() - s / 2, r.centerY() - s / 2, s, color)
            }
        }

        if (dim) {
            fill.color = 0x73000000
            canvas.drawRoundRect(r, radius, radius, fill)
        }
        if (alpha < 255) canvas.restore()
    }

    private fun drawCorner(canvas: Canvas, card: Card, r: RectF, color: Int) {
        val w = r.width()
        text.color = color
        text.textAlign = Paint.Align.CENTER
        text.textSize = w * 0.25f
        text.textScaleX = if (card.rank == Rank.TEN) 0.78f else 1f
        val cx = r.left + w * 0.15f
        canvas.drawText(card.rank.label, cx, r.top + w * 0.27f, text)
        text.textScaleX = 1f
        val s = w * 0.17f
        drawSuit(canvas, card.suit, cx - s / 2, r.top + w * 0.33f, s, color)
    }

    fun drawBack(canvas: Canvas, r: RectF) {
        val w = r.width()
        val radius = w * 0.08f
        fill.style = Paint.Style.FILL
        fill.color = 0x55000000
        rect.set(r.left + w * 0.02f, r.top + w * 0.03f, r.right + w * 0.02f, r.bottom + w * 0.03f)
        canvas.drawRoundRect(rect, radius, radius, fill)
        fill.color = Color.WHITE
        canvas.drawRoundRect(r, radius, radius, fill)
        val inset = w * 0.07f
        rect.set(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
        fill.color = 0xFF8E1B1B.toInt()
        canvas.drawRoundRect(rect, radius * 0.6f, radius * 0.6f, fill)
        canvas.save()
        canvas.clipRect(rect)
        stroke.color = 0x55FFFFFF
        stroke.strokeWidth = maxOf(1f, w * 0.02f)
        val step = w * 0.14f
        var x = rect.left - rect.height()
        while (x < rect.right) {
            canvas.drawLine(x, rect.top, x + rect.height(), rect.bottom, stroke)
            canvas.drawLine(x + rect.height(), rect.top, x, rect.bottom, stroke)
            x += step
        }
        canvas.restore()
        stroke.color = 0xFF9E9E9E.toInt()
        stroke.strokeWidth = maxOf(1f, w * 0.012f)
        canvas.drawRoundRect(r, radius, radius, stroke)
    }
}
