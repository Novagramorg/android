package org.fenixuz.callrec

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import org.fenixuz.utils.LanguageCode
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.Components.CubicBezierInterpolator

/**
 * Novagram: the "● Record" / "● REC 00:12" capsule at the top of the call screen.
 *
 * Idle it is a translucent black pill with a red dot that starts a recording; recording it is a red pill with a
 * white dot and the elapsed time, and a tap stops. A recording is never secret, so while one runs the capsule stays
 * at full alpha even when the screen hides its other controls ([followControls]); idle it fades with them. It shows
 * only while [CallRecording.isAvailable] says so ([setAvailable]) and keeps itself current through
 * [CallRecording.Listener] while attached.
 */
class CallRecordCapsule(context: Context) : View(context), CallRecording.Listener {

    var onToggle: Runnable? = null

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = AndroidUtilities.bold()
        textSize = dp(14f).toFloat()
        color = Color.WHITE
    }
    private val rect = RectF()
    private var text = ""
    private var available = false
    private var controlsShown = true
    private var pressed = false

    private val tick = object : Runnable {
        override fun run() {
            if (CallRecording.isRecording()) {
                refreshText()
                postDelayed(this, 500)
            }
        }
    }

    init {
        isClickable = true
        contentDescription = LanguageCode.getMyTitles(528)
        refreshText()
    }

    /** Whether the control may show at all right now (the call's state and the setting). */
    fun setAvailable(value: Boolean) {
        if (available == value) return
        available = value
        applyVisibility(true)
    }

    /** The screen's other controls are shown or hidden: the idle capsule follows, a running recording stays. */
    fun followControls(shown: Boolean, animated: Boolean) {
        controlsShown = shown
        applyVisibility(animated)
    }

    private fun applyVisibility(animated: Boolean) {
        val recording = CallRecording.isRecording()
        val show = (available || recording) && (controlsShown || recording)
        val target = if (show) 1f else 0f
        if (!show && alpha == 0f && visibility == GONE) return
        if (show && visibility != VISIBLE) {
            visibility = VISIBLE
            if (animated) alpha = 0f
        }
        animate().cancel()
        if (animated) {
            animate().alpha(target).setDuration(150).setInterpolator(CubicBezierInterpolator.DEFAULT)
                .withEndAction { if (target == 0f) visibility = GONE }.start()
        } else {
            alpha = target
            visibility = if (show) VISIBLE else GONE
        }
        isClickable = show
    }

    private fun refreshText() {
        val startedAt = CallRecording.startedAt
        val newText = if (startedAt != 0L) {
            "REC " + format((SystemClock.elapsedRealtime() - startedAt) / 1000)
        } else {
            LanguageCode.getMyTitles(528)
        }
        if (newText != text) {
            text = newText
            contentDescription = if (startedAt != 0L) LanguageCode.getMyTitles(553) else LanguageCode.getMyTitles(528)
            requestLayout()
            invalidate()
        }
    }

    private fun format(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    override fun onCallRecordingChanged() {
        refreshText()
        applyVisibility(true)
        removeCallbacks(tick)
        if (CallRecording.isRecording()) postDelayed(tick, 500)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        CallRecording.addListener(this)
        onCallRecordingChanged()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        CallRecording.removeListener(this)
        removeCallbacks(tick)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = SIDE * 2 + DOT + GAP + textPaint.measureText(text)
        setMeasuredDimension(Math.ceil(width.toDouble()).toInt(), dp(HEIGHT))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (pressed) {
                    pressed = false
                    invalidate()
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onToggle?.run()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val recording = CallRecording.isRecording()
        pillPaint.color = if (recording) RED else Color.argb(if (pressed) 150 else 115, 0, 0, 0)
        dotPaint.color = if (recording) Color.WHITE else RED
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val r = height / 2f
        canvas.drawRoundRect(rect, r, r, pillPaint)
        canvas.drawCircle(SIDE + DOT / 2f, r, DOT / 2f, dotPaint)
        val baseline = r - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, SIDE + DOT + GAP, baseline, textPaint)
    }

    companion object {
        private const val HEIGHT = 28f
        private val SIDE = dp(11f).toFloat()
        private val DOT = dp(8f).toFloat()
        private val GAP = dp(6f).toFloat()
        private const val RED = 0xFFFF3B30.toInt()
    }
}
