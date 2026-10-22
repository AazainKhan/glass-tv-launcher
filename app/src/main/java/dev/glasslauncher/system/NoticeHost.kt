package dev.glasslauncher.system

import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
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
internal interface NoticeSurface {
    /** Puts [card] on screen; false if it cannot be (no window token, no permission). */
    fun add(card: View): Boolean
    fun remove(card: View)

    /** Gets the glass for the card into [ref] before it shows ([done] when ready, at once if there is nothing to wait for). */
    fun prepare(ref: dev.glasslauncher.home.CcSheetRef, done: () -> Unit) = done()
}

/** A system window of [type] (TYPE_ACCESSIBILITY_OVERLAY for the service, TYPE_APPLICATION_OVERLAY with the overlay permission). */
internal class WindowSurface(private val context: Context, private val type: Int) : NoticeSurface {
    /** Over an app the glass is baked from a capture of the screen behind the card, which only the accessibility service can take; waits at most [CAPTURE_WAIT_MS]. */
    override fun prepare(ref: dev.glasslauncher.home.CcSheetRef, done: () -> Unit) {
        val service = context as? android.accessibilityservice.AccessibilityService ?: return done()
        var finished = false
        val finish = { if (!finished) { finished = true; done() } }
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, CAPTURE_WAIT_MS)
        NoticeGlass.sheetFor(service, context.resources.displayMetrics.density) { sheet -> if (!finished) { ref.sheet = sheet; finish() } }
    }

    override fun add(card: View): Boolean {
        val dp = context.resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (NoticeCard.MARGIN_DP * dp).toInt(); y = (NoticeCard.MARGIN_DP * dp).toInt() }
        return runCatching { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(card, params) }
            .onFailure { android.util.Log.w("NoticeHost", "card could not be added", it) }.isSuccess
    }

    override fun remove(card: View) { runCatching { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(card) } }

    private companion object { const val CAPTURE_WAIT_MS = 150L }
}

/** Home's own window while it is in front: a child of its decor view, which needs no permission. */
internal class DecorSurface(private val activity: () -> Activity?) : NoticeSurface {
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

internal object NoticeCard {
    const val MARGIN_DP = 24
    /** The card's fixed corner (P268): tvOS's banner uses 30 pt, 15 dp here. */
    const val CORNER_DP = 15

    /** The glass card (P268): glyph, title, one line, in Control Center's material; [ref] carries the baked glass once the surface has it. */
    internal fun build(context: Context, notice: Notice, ref: dev.glasslauncher.home.CcSheetRef = dev.glasslauncher.home.CcSheetRef()): View = NoticeGlass.view(context, notice, ref)
}

/** One card at a time on one [surface]; the queue is BannerQueue's. */
internal class NoticeHost(private val context: Context, private val surface: NoticeSurface, private val tag: String = "NoticeHost") {
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
    fun clear() { presentations++; queue.clear(); hide(animate = false) }

    private fun next() { (queue.finished() as? BannerQueue.Step.Show)?.let { present(it.notice) } }

    /** Counts presentations, so a card whose glass was still being captured when another took over or the host was cleared never shows. */
    private var presentations = 0

    private fun present(notice: Notice) {
        hide(animate = false)
        val mine = ++presentations
        val ref = dev.glasslauncher.home.CcSheetRef()
        val view = NoticeCard.build(context, notice, ref)
        surface.prepare(ref) { if (mine == presentations) show(notice, view) }
    }

    private fun show(notice: Notice, view: View) {
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
