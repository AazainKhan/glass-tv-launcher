package dev.glasslauncher.system

import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.glasslauncher.R

/**
 * P229: every banner is one glass card at the TOP-LEFT of the screen, sliding in from the left edge and back out (a PlayStation-style
 * card): the notice's glyph, its title and one line, about four seconds, one at a time (BannerQueue's P163 rules: queued, held back by
 * Do Not Disturb by the caller, never focusable or touchable). The top-left clears Control Center, which opens on the right, and the
 * status pill. Where the card is drawn is a [NoticeSurface]: Glass's accessibility overlay, an overlay window where the permission is
 * granted, or Home's own window while it is in front.
 */
interface NoticeSurface {
    /** Puts [card] on screen; false if it cannot be (no window token, no permission). */
    fun add(card: View): Boolean
    fun remove(card: View)
}

/** A system window of [type] (TYPE_ACCESSIBILITY_OVERLAY for the service, TYPE_APPLICATION_OVERLAY with the overlay permission). */
class WindowSurface(private val context: Context, private val type: Int) : NoticeSurface {
    override fun add(card: View): Boolean {
        val dp = context.resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (NoticeCard.MARGIN_DP * dp).toInt(); y = (NoticeCard.MARGIN_DP * dp).toInt() }
        return runCatching { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(card, params) }
            .onFailure { android.util.Log.w("NoticeHost", "card could not be added", it) }.isSuccess
    }

    override fun remove(card: View) { runCatching { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(card) } }
}

/** Home's own window while it is in front: a child of its decor view, which needs no permission. */
class DecorSurface(private val activity: () -> Activity?) : NoticeSurface {
    override fun add(card: View): Boolean {
        val a = activity() ?: return false
        val decor = a.window?.decorView as? ViewGroup ?: return false
        val dp = a.resources.displayMetrics.density
        card.isFocusable = false; card.isClickable = false
        decor.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
            .apply { leftMargin = (NoticeCard.MARGIN_DP * dp).toInt(); topMargin = (NoticeCard.MARGIN_DP * dp).toInt() })
        return true
    }

    override fun remove(card: View) { (card.parent as? ViewGroup)?.removeView(card) }
}

object NoticeCard {
    const val MARGIN_DP = 24

    /** The glass card: glyph, title, one line. A plain notice has the info glyph. */
    fun build(context: Context, notice: Notice): View {
        val dp = context.resources.displayMetrics.density
        val bg = GradientDrawable().apply {
            // Clear dark glass with a bright upper rim, the dock tray's look at overlay scale.
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = intArrayOf(0xEB26262B.toInt(), 0xEB18181C.toInt())
            cornerRadius = 22 * dp
            setStroke((1 * dp).toInt(), 0x33FFFFFF)
        }
        fun word(t: String, size: Float, color: Int) = TextView(context).apply { text = t; setTextColor(color); textSize = size }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (22 * dp).toInt(), (12 * dp).toInt())
            background = bg
            elevation = 0f
            addView(ImageView(context).apply {
                setImageResource(if (notice.iconRes != 0) notice.iconRes else R.drawable.ic_info)
                setColorFilter(0xFFFFFFFF.toInt())
            }, LinearLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt()).apply { marginEnd = (14 * dp).toInt() })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(word(notice.text, 16f, 0xFFFFFFFF.toInt()).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = (380 * dp).toInt() })
                notice.detail?.let { addView(word(it, 13f, 0xB3FFFFFF.toInt()).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = (380 * dp).toInt() }) }
            })
        }
    }
}

/** One card at a time on one [surface]; the queue is BannerQueue's. */
class NoticeHost(private val context: Context, private val surface: NoticeSurface, private val tag: String = "NoticeHost") {
    private val handler = Handler(Looper.getMainLooper())
    private val queue = BannerQueue()
    private var card: View? = null
    private val drop = Runnable { hide(animate = true); handler.postDelayed({ next() }, SLIDE_OUT_MS + 40) }

    fun show(notice: Notice) {
        handler.post {
            val step = queue.offer(notice)
            android.util.Log.i(tag, "notice ${step::class.simpleName}: ${notice.text}")
            when (step) {
                is BannerQueue.Step.Show -> present(step.notice)
                is BannerQueue.Step.Extend -> { handler.removeCallbacks(drop); handler.postDelayed(drop, step.ms) }
                BannerQueue.Step.Wait -> Unit
            }
        }
    }

    /** Replaces whatever is up and queued (the sleep warning is answered by a key and takes its place at once). */
    fun showNow(text: String, forMs: Long) {
        handler.post { queue.clear(); hide(animate = false); present(Notice(text, forMs)) }
    }

    /** Everything off at once (the service is going away). */
    fun clear() { queue.clear(); hide(animate = false) }

    private fun next() { (queue.finished() as? BannerQueue.Step.Show)?.let { present(it.notice) } }

    private fun present(notice: Notice) {
        hide(animate = false)
        val view = NoticeCard.build(context, notice)
        // Off to the left and invisible until it is measured, then in from the left edge: a spring-like ease with a little overshoot.
        view.alpha = 0f
        if (!surface.add(view)) { queue.finished(); return }
        card = view
        view.post {
            val from = -(view.width + NoticeCard.MARGIN_DP * context.resources.displayMetrics.density + 8f)
            view.translationX = from
            view.alpha = 1f
            view.animate().translationX(0f).setDuration(SLIDE_IN_MS).setInterpolator(android.view.animation.OvershootInterpolator(0.7f)).start()
        }
        handler.postDelayed(drop, notice.shownMs)
    }

    private fun hide(animate: Boolean) {
        handler.removeCallbacks(drop)
        val v = card ?: return
        card = null
        if (!animate) { surface.remove(v); return }
        val to = -(v.width + NoticeCard.MARGIN_DP * context.resources.displayMetrics.density + 8f)
        v.animate().translationX(to).alpha(0f).setDuration(SLIDE_OUT_MS).setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f)).withEndAction { surface.remove(v) }.start()
    }

    companion object {
        const val SLIDE_IN_MS = 380L
        const val SLIDE_OUT_MS = 260L
    }
}
