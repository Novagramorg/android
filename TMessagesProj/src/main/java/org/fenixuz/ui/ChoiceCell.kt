package org.fenixuz.ui

import android.content.Context
import android.graphics.Canvas
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedTextView
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalRecyclerView

/**
 * Novagram: a FenixSettings row for a multiple-choice setting. It is drawn like its switch neighbours
 * (NotificationsCheckCell(padding = 21, height = 60): 16sp title, one grey 13sp description line under it,
 * same offsets), but where they have a switch it shows the current choice in the value colour. The whole
 * row is one tap target, so the short divider bar NotificationsCheckCell draws before its switch is left out.
 * The fragment handles the tap, normally with a single-choice dialog.
 */
class ChoiceCell(context: Context, resourcesProvider: Theme.ResourcesProvider?) : FrameLayout(context) {

    private val titleView = TextView(context)
    private val descriptionView = AnimatedTextView(context)
    private val valueView = TextView(context)
    private var needDivider = false

    init {
        setWillNotDraw(false)
        val start = (if (LocaleController.isRTL) Gravity.RIGHT else Gravity.LEFT)

        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider))
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
        titleView.setLines(1)
        titleView.maxLines = 1
        titleView.isSingleLine = true
        titleView.gravity = start or Gravity.CENTER_VERTICAL
        titleView.ellipsize = TextUtils.TruncateAt.END
        addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), start or Gravity.TOP, 21f, 8f, 21f, 0f))

        descriptionView.setAnimationProperties(.55f, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT)
        descriptionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider))
        descriptionView.setTextSize(dp(13f).toFloat())
        descriptionView.setGravity(start)
        descriptionView.setPadding(0, 0, 0, 0)
        descriptionView.setEllipsizeByGradient(true)
        addView(descriptionView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), start or Gravity.TOP, 21f, 24f, 21f, 0f))

        valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText, resourcesProvider))
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
        valueView.setLines(1)
        valueView.maxLines = 1
        valueView.isSingleLine = true
        valueView.ellipsize = TextUtils.TruncateAt.END
        valueView.gravity = Gravity.CENTER_VERTICAL
        // A cap so a long translation of the value can never squeeze the title out of the row.
        valueView.maxWidth = dp(140f)
        val end = if (LocaleController.isRTL) Gravity.LEFT else Gravity.RIGHT
        addView(valueView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), end or Gravity.CENTER_VERTICAL, 21f, 0f, 21f, 0f))
    }

    fun set(title: CharSequence?, description: CharSequence?, value: CharSequence?, divider: Boolean) {
        titleView.text = title
        descriptionView.setText(description, false)
        valueView.text = value
        needDivider = divider
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The title and description end where the value starts, not at a fixed 80dp like the switch rows,
        // because the value is as wide as its text. Margins are written straight into the existing params
        // so this does not request another layout pass.
        valueView.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val reserved = dp(21f) + valueView.measuredWidth + dp(16f)
        for (view in arrayOf<View>(titleView, descriptionView)) {
            val lp = view.layoutParams as LayoutParams
            if (LocaleController.isRTL) lp.leftMargin = reserved else lp.rightMargin = reserved
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dp(60f), MeasureSpec.EXACTLY)
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (needDivider) {
            canvas.drawLine(
                (if (LocaleController.isRTL) 0 else dp(20f)).toFloat(),
                (measuredHeight - 1).toFloat(),
                (measuredWidth - if (LocaleController.isRTL) dp(20f) else 0).toFloat(),
                (measuredHeight - 1).toFloat(),
                Theme.dividerPaint
            )
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        info.contentDescription = "${titleView.text}, ${valueView.text}\n${descriptionView.text}"
    }

    class Factory : UItem.UItemFactory<ChoiceCell>() {

        companion object {
            init {
                UItem.UItemFactory.setup(Factory())
            }

            /** [title] on top, [description] in grey under it, the current choice [value] on the right. */
            fun of(id: Int, title: CharSequence, description: CharSequence, value: CharSequence): UItem {
                val item = UItem.ofFactory(Factory::class.java)
                item.id = id
                item.text = title
                item.subtext = description
                item.textValue = value
                return item
            }
        }

        override fun createView(context: Context, listView: RecyclerListView?, currentAccount: Int, classGuid: Int, resourcesProvider: Theme.ResourcesProvider?): ChoiceCell =
            ChoiceCell(context, resourcesProvider)

        override fun bindView(view: View, item: UItem, divider: Boolean, adapter: UniversalAdapter?, listView: UniversalRecyclerView?) {
            (view as ChoiceCell).set(item.text, item.subtext, item.textValue, divider)
        }

        // Same row identity across a value change, so picking another option rebinds the row in place
        // instead of DiffUtil animating it out and back in.
        override fun equals(a: UItem, b: UItem): Boolean = a.id == b.id

        override fun contentsEquals(a: UItem, b: UItem): Boolean =
            TextUtils.equals(a.text, b.text) && TextUtils.equals(a.subtext, b.subtext) && TextUtils.equals(a.textValue, b.textValue)
    }
}
