package ae.livechat.sdk

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button

/**
 * A round chat button with the unread badge, ready to put on any screen. Tapping it opens the chat.
 *
 * ```kotlin
 * val button = LiveChatAEButton(context)    // 56×56 dp, uses the accent color of the theme (or set color)
 * layout.addView(button)
 * ```
 *
 * In XML: `<ae.livechat.sdk.LiveChatAEButton android:layout_width="wrap_content" android:layout_height="wrap_content" />`.
 */
class LiveChatAEButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Color of the button. Default: the accent color of the theme. */
    var color: Int = themeColor(context)
        set(value) {
            field = value
            invalidate()
        }

    /** Color of the unread badge. */
    var badgeColor: Int = Color.parseColor("#EF4444")
        set(value) {
            field = value
            invalidate()
        }

    private var count = LiveChatAE.unreadCount
    private val circle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        textSize = sp(11f)
    }
    private val bubble = Path()
    private val rect = RectF()

    private val listener = object : LiveChatAEListener {
        override fun onUnreadCountChanged(count: Int) = show(count)
    }

    init {
        isClickable = true
        isFocusable = true
        elevation = dp(6f)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val s = minOf(view.width, view.height)
                outline.setOval(0, 0, s, s)
            }
        }
        contentDescription = context.getString(R.string.livechatae_chat)
        setOnClickListener { LiveChatAE.present(activity()) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(56f).toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        LiveChatAE.addListener(listener)
        show(LiveChatAE.unreadCount)
    }

    override fun onDetachedFromWindow() {
        LiveChatAE.removeListener(listener)
        super.onDetachedFromWindow()
    }

    private fun show(n: Int) {
        count = n
        stateDescription(n)
        invalidate()
    }

    private fun stateDescription(n: Int) {
        val chat = context.getString(R.string.livechatae_chat)
        contentDescription = if (n > 0) chat + ", " + context.getString(R.string.livechatae_unread, n) else chat
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Button::class.java.name
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        val r = s / 2
        circle.color = if (isPressed) darker(color) else color
        canvas.drawCircle(r, r, r, circle)

        // a speech bubble, 44% of the button
        val w = s * 0.44f
        val h = w * 0.78f
        val left = r - w / 2
        val top = r - h / 2 - s * 0.02f
        rect.set(left, top, left + w, top + h)
        bubble.reset()
        bubble.addRoundRect(rect, h * 0.32f, h * 0.32f, Path.Direction.CW)
        bubble.moveTo(left + w * 0.22f, top + h - 1)
        bubble.lineTo(left + w * 0.18f, top + h + h * 0.32f)
        bubble.lineTo(left + w * 0.48f, top + h - 1)
        bubble.close()
        canvas.drawPath(bubble, icon)
        val dot = s * 0.035f
        circle.color = color
        for (i in -1..1) canvas.drawCircle(r + i * w * 0.25f, top + h / 2, dot, circle)

        if (count > 0) {
            val text = if (count > 99) "99+" else count.toString()
            val bh = dp(20f)
            val bw = maxOf(bh, badgeText.measureText(text) + dp(10f))
            rect.set(s - bw, 0f, s, bh)   // top right corner, over the edge of the circle
            badge.color = badgeColor
            canvas.drawRoundRect(rect, bh / 2, bh / 2, badge)
            val y = rect.centerY() - (badgeText.descent() + badgeText.ascent()) / 2
            canvas.drawText(text, rect.centerX(), y, badgeText)
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    private fun activity(): Activity? {
        var c = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private companion object {
        fun themeColor(context: Context): Int {
            val tv = TypedValue()
            return if (context.theme.resolveAttribute(android.R.attr.colorAccent, tv, true) && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT) {
                tv.data
            } else {
                Color.parseColor("#2563EB")
            }
        }

        fun darker(c: Int): Int {
            val f = 0.85f
            return Color.argb(Color.alpha(c), (Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
        }
    }
}
