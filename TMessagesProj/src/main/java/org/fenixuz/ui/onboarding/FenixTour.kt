package org.fenixuz.ui.onboarding

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import org.fenixuz.utils.LanguageCode
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.UniversalRecyclerView
import org.telegram.ui.LaunchActivity
import org.telegram.ui.Stories.recorder.HintView2

/**
 * Novagram onboarding "coach-mark" tour: walks the user through the Novagram feature rows one at a time,
 * dimming the rest of the screen and popping a [HintView2] arrow bubble that points straight at the row
 * being explained. Tap anywhere to advance, "Skip" to bail out; a small "2 / 5" counter shows progress.
 *
 * The dim overlay is attached to the app's top-level [LaunchActivity.drawerLayoutContainer], NOT to the
 * fragment's own content view. That matters on edge-to-edge screens (targetSdk 35 forces edge-to-edge):
 * the fragment content is laid out ABOVE the transparent system navigation bar, so an in-fragment scrim
 * would leave the nav-bar strip bright. The top-level container spans the whole window — including behind
 * the transparent nav bar — so the whole screen darkens uniformly. Its lifecycle is tied to the host
 * [container]: when that detaches (back press / navigation), the tour tears itself down.
 *
 * Give it the host [container] (the fragment's content view — used for lifecycle), the
 * [UniversalRecyclerView] whose rows it targets, and a list of [Step]s (each = a row item-id + title +
 * one-line explanation, reusing the existing per-feature subtitle strings). Device-only, no network.
 */
class FenixTour(
    private val context: Context,
    private val container: FrameLayout,
    private val listView: UniversalRecyclerView,
    private val steps: List<Step>
) {

    /** One tour stop: the row to spotlight ([itemId] = its UItem id) plus the bold [title] and [body] text. */
    class Step(val itemId: Int, val title: String, val body: String)

    // Cover the WHOLE window (so the transparent nav-bar area dims too); fall back to the host view if the
    // top-level container isn't reachable.
    private val overlayParent: FrameLayout = LaunchActivity.instance?.drawerLayoutContainer ?: container

    private val root = TourRoot(context)
    private val scrim = ScrimView(context)
    private val progressLabel = TextView(context)
    private val skipButton = TextView(context)

    private var hint: HintView2? = null
    private var current = -1
    private var holeAnimator: ValueAnimator? = null
    private var finished = false

    // Tear the tour down when the host fragment view leaves the window (back press / navigated away), since the
    // overlay lives on the top-level container and wouldn't be removed with the fragment otherwise.
    private val hostAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) { finish() }
    }

    fun start() {
        if (steps.isEmpty() || active) return
        active = true

        root.setOnClickListener { next() }   // tap on the dimmed area → advance
        root.addView(scrim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        // The overlay covers the whole window (incl. status bar); float the controls just below the status bar.
        val topInset = AndroidUtilities.statusBarHeight + dp(8)

        progressLabel.setTextColor(Color.WHITE)
        progressLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        progressLabel.typeface = AndroidUtilities.bold()
        progressLabel.gravity = Gravity.CENTER
        progressLabel.setPadding(dp(13), dp(5), dp(13), dp(5))
        progressLabel.background = Theme.createRoundRectDrawable(dp(15), 0x33FFFFFF)
        val progressParams = LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        progressParams.topMargin = topInset
        root.addView(progressLabel, progressParams)

        skipButton.text = LanguageCode.getMyTitles(340)            // "Skip"
        skipButton.setTextColor(Color.WHITE)
        skipButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        skipButton.typeface = AndroidUtilities.bold()
        skipButton.setPadding(dp(14), dp(7), dp(14), dp(7))
        skipButton.background = Theme.createRoundRectDrawable(dp(16), 0x33FFFFFF)
        skipButton.setOnClickListener { finish() }
        val skipParams = LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP or Gravity.RIGHT)
        skipParams.topMargin = topInset
        skipParams.rightMargin = dp(12)
        root.addView(skipButton, skipParams)

        // A non-null tag makes DrawerLayoutContainer skip applying window-bar insets to us, so the overlay
        // truly covers edge-to-edge (incl. behind the transparent status & navigation bars).
        root.tag = "fenix_tour"
        overlayParent.addView(root, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        container.addOnAttachStateChangeListener(hostAttachListener)

        root.alpha = 0f
        root.animate().alpha(1f).setDuration(220).start()
        showStep(0)
    }

    private fun next() {
        if (finished) return
        if (current + 1 < steps.size) showStep(current + 1) else finish()
    }

    private fun showStep(index: Int) {
        current = index
        val step = steps[index]
        progressLabel.text = (index + 1).toString() + " / " + steps.size

        val position = listView.findPositionByItemId(step.itemId)
        if (position < 0) { next(); return }   // row not present (feature hidden) → skip this stop

        // Bring the row into the upper third so the bubble below/above it has room.
        listView.layoutManager.scrollToPositionWithOffset(position, Math.max(dp(40), root.height / 3))
        locateRow(step, attempt = 0)
    }

    /** The row view may not be laid out the instant after a scroll — retry a couple of frames before giving up. */
    private fun locateRow(step: Step, attempt: Int) {
        root.post {
            if (finished) return@post
            val rowView = listView.findViewByItemId(step.itemId)
            if (rowView == null || rowView.height == 0) {
                if (attempt < 4) locateRow(step, attempt + 1) else next()
                return@post
            }
            spotlight(step, rowView)
        }
    }

    private fun spotlight(step: Step, rowView: View) {
        // Row rect in the overlay's own coordinate space (overlay spans the whole window).
        val origin = IntArray(2); root.getLocationInWindow(origin)
        val loc = IntArray(2); rowView.getLocationInWindow(loc)
        val left = (loc[0] - origin[0]).toFloat()
        val top = (loc[1] - origin[1]).toFloat()
        val right = left + rowView.width
        val bottom = top + rowView.height
        val target = RectF(left + dp(6), top + dp(3), right - dp(6), bottom - dp(3))
        animateHole(target)

        hint?.let { root.removeView(it) }
        val centerX = (target.left + target.right) / 2f
        val below = bottom < root.height * 0.5f                   // row in top half → bubble sits below it
        val direction = if (below) HintView2.DIRECTION_TOP else HintView2.DIRECTION_BOTTOM
        val h = HintView2(context, direction)
            .setMultilineText(true)
            .setText(buildText(step))
            .setMaxWidth(260f)
            .setRounding(15f)
            .setInnerPadding(14f, 11f, 14f, 12f)                 // more breathing room around the text
            .setBgColor(BUBBLE_BG)
            .setTextColor(Color.WHITE)
            .setTextSize(15f)
            .setDuration(-1)                                     // we hide it manually on advance
            .setHideByTouch(false)
        h.isClickable = false                                    // taps fall through to the root → advance
        h.setJoint((centerX / Math.max(1, root.width)).coerceIn(0f, 1f), 0f)
        if (below) {
            h.setPadding(0, (bottom + dp(4)).toInt(), 0, 0)
        } else {
            h.setPadding(0, 0, 0, (root.height - top + dp(4)).toInt())
        }
        root.addView(h, 1, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        h.show()
        hint = h
    }

    private fun buildText(step: Step): CharSequence {
        val sb = SpannableStringBuilder()
        sb.append(step.title)
        sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, step.title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (step.body.isNotEmpty()) {
            val bodyStart = sb.length
            sb.append("\n")
            sb.append(step.body)
            // Quieter, slightly smaller body so the title leads the eye.
            sb.setSpan(RelativeSizeSpan(0.92f), bodyStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(0xDDFFFFFF.toInt()), bodyStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun animateHole(to: RectF) {
        holeAnimator?.cancel()
        val from = RectF(scrim.hole)
        if (from.isEmpty) { scrim.setHole(to); return }         // first step: no slide, just appear
        holeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = CubicBezierInterpolator.EASE_OUT_QUINT
            addUpdateListener {
                val f = it.animatedValue as Float
                scrim.setHole(
                    RectF(
                        lerp(from.left, to.left, f), lerp(from.top, to.top, f),
                        lerp(from.right, to.right, f), lerp(from.bottom, to.bottom, f)
                    )
                )
            }
            start()
        }
    }

    private fun finish() {
        if (finished) return
        finished = true
        active = false
        holeAnimator?.cancel()
        container.removeOnAttachStateChangeListener(hostAttachListener)
        hint?.hide()
        root.animate().alpha(0f).setDuration(180).setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                overlayParent.removeView(root)
            }
        }).start()
    }

    private fun lerp(a: Float, b: Float, f: Float): Float = a + (b - a) * f

    private fun dp(value: Int): Int = AndroidUtilities.dp(value.toFloat())

    /** Root overlay: swallows every touch so nothing leaks to the screen underneath while the tour is up. */
    private inner class TourRoot(context: Context) : FrameLayout(context) {
        init { isClickable = true }
        override fun onInterceptTouchEvent(ev: android.view.MotionEvent?): Boolean = false
        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            active = false           // never leave the guard stuck if we're torn down mid-tour
        }
    }

    /**
     * Dims the whole screen except a rounded "hole" punched over the row currently being explained, ringed
     * by a soft, gently pulsing green outline (a crisp inner ring + an outer glow that breathes in and out)
     * so the eye is drawn to the spotlight without anything looking harsh.
     */
    private inner class ScrimView(context: Context) : View(context) {
        val hole = RectF()
        private val glow = RectF()
        private var pulse = 0f
        private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = BUBBLE_BG
        }
        private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1150
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
        }

        init { setLayerType(LAYER_TYPE_HARDWARE, null) }

        fun setHole(r: RectF) {
            hole.set(r)
            if (!pulseAnimator.isStarted) pulseAnimator.start()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val saved = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            canvas.drawColor(SCRIM_COLOR)
            if (!hole.isEmpty) {
                val r = dp(12).toFloat()
                canvas.drawRoundRect(hole, r, r, clearPaint)

                // Outer glow: a wider, faint ring that expands and fades as the pulse swells.
                val grow = dp(7) * pulse
                glow.set(hole)
                glow.inset(-grow, -grow)
                ringPaint.strokeWidth = dp(2).toFloat()
                ringPaint.alpha = lerp(80f, 0f, pulse).toInt()
                canvas.drawRoundRect(glow, r + grow, r + grow, ringPaint)

                // Crisp inner ring, brightening slightly on the pulse peak.
                ringPaint.strokeWidth = dp(2).toFloat()
                ringPaint.alpha = lerp(170f, 255f, pulse).toInt()
                canvas.drawRoundRect(hole, r, r, ringPaint)
            }
            canvas.restoreToCount(saved)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            pulseAnimator.cancel()
        }
    }

    companion object {
        private val SCRIM_COLOR = 0xCC0E0E0E.toInt()            // ~80% black veil
        private val BUBBLE_BG = 0xFF0A93E8.toInt()              // Novagram brand blue (matches the app accent)

        // Guards against two tours (e.g. the auto tour + a toolbar "?" tap) stacking on screen at once.
        private var active = false
    }
}
