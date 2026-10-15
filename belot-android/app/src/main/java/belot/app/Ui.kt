package belot.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import belot.core.Suit

object Colors {
    const val FELT = 0xFF0E4D2E.toInt()
    const val FELT_DARK = 0xFF07311C.toInt()
    const val GOLD = 0xFFF2B705.toInt()
    const val CREAM = 0xFFFFFDF6.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val TEXT_DIM = 0xB3FFFFFF.toInt()
    const val PANEL = 0xFF102A1C.toInt()
}

fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())

fun rounded(color: Int, radius: Float, strokeColor: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (strokeColor != null) setStroke(strokeWidth, strokeColor)
}

fun Context.label(text: String, sizeSp: Float = 16f, bold: Boolean = false, color: Int = Colors.TEXT): TextView =
    TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

enum class ButtonStyle { PRIMARY, SECONDARY, DANGER }

fun Context.button(text: String, style: ButtonStyle = ButtonStyle.PRIMARY, onClick: () -> Unit): Button =
    Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        val r = dp(14f).toFloat()
        val (bg, fg) = when (style) {
            ButtonStyle.PRIMARY -> Colors.GOLD to 0xFF1B1B1B.toInt()
            ButtonStyle.SECONDARY -> 0x33FFFFFF to Colors.TEXT
            ButtonStyle.DANGER -> 0xFFB3261E.toInt() to Colors.TEXT
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), rounded(0x22FFFFFF, r))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(bg and 0x00FFFFFF or 0xAA000000.toInt(), r))
            addState(intArrayOf(), rounded(bg, r))
        }
        setTextColor(fg)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        minHeight = dp(52)
        setOnClickListener { onClick() }
    }

fun Context.input(hint: String, value: String = "", numeric: Boolean = false): EditText = EditText(this).apply {
    this.hint = hint
    setText(value)
    setTextColor(0xFF1B1B1B.toInt())
    setHintTextColor(0xFF888888.toInt())
    textSize = 18f
    isSingleLine = true
    inputType = if (numeric) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
    background = rounded(Colors.CREAM, dp(12f).toFloat())
    setPadding(dp(14), dp(12), dp(14), dp(12))
}

fun Context.column(padding: Int = 20): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
}

fun Context.row(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun View.lp(width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, weight: Float = 0f): View {
    layoutParams = LinearLayout.LayoutParams(width, height, weight).apply { topMargin = top }
    return this
}

fun LinearLayout.add(v: View, top: Int = 12, width: Int = ViewGroup.LayoutParams.MATCH_PARENT, weight: Float = 0f): View {
    val m = context.dp(top)
    addView(v, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply { topMargin = m })
    return v
}

/** A square button showing a drawn suit symbol (avoids emoji rendering of ♥/♦ on some phones). */
@SuppressLint("ViewConstructor")
class SuitButton(context: Context, val suit: Suit, onClick: () -> Unit) : View(context) {
    init {
        background = StateListDrawable().apply {
            val r = context.dp(12f).toFloat()
            addState(intArrayOf(android.R.attr.state_pressed), rounded(0xFFE0D8C0.toInt(), r, Colors.GOLD, context.dp(3)))
            addState(intArrayOf(), rounded(Colors.CREAM, r, Colors.GOLD, context.dp(2)))
        }
        isClickable = true
        contentDescription = suit.title
        setOnClickListener { onClick() }
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height) * 0.6f
        CardPainter.drawSuit(canvas, suit, (width - s) / 2, (height - s) / 2, s)
    }
}
