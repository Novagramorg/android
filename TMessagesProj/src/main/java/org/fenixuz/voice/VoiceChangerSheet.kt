package org.fenixuz.voice

import android.content.Context
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.fenixuz.utils.LanguageCode
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.Emoji
import org.telegram.messenger.NotificationCenter
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.RadioColorCell
import org.telegram.ui.Components.LayoutHelper

/**
 * Novagram voice changer: the list of effects, opened from the button above the pause button while a voice message is
 * recorded (as in Graph Messenger), or from the Novagram settings. One radio row per effect, in Graph's order with its
 * emoji; "Record speed" asks for one of four speeds. The choice is kept for the next recordings and applies from when
 * the recording resumes.
 */
object VoiceChangerSheet {

    /** [onClosed] runs once as the sheet starts to close, however it is closed (a pick, Back, a tap outside, a swipe). */
    @JvmStatic
    fun show(context: Context, resourcesProvider: Theme.ResourcesProvider?, onClosed: Runnable?) {
        val list = LinearLayout(context)
        list.orientation = LinearLayout.VERTICAL
        // As in Graph the sheet takes part of the screen, with the chat still visible above it; the rows scroll inside.
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxHeight = (AndroidUtilities.displaySize.y * 0.55f).toInt()
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
            }
        }
        scroll.addView(list)

        val content = LinearLayout(context)
        content.orientation = LinearLayout.VERTICAL
        val handle = View(context)
        handle.background = Theme.createRoundRectDrawable(
            AndroidUtilities.dp(2f), Theme.getColor(Theme.key_sheet_scrollUp, resourcesProvider)
        )
        content.addView(handle, LayoutHelper.createLinear(36, 4, Gravity.CENTER_HORIZONTAL, 0, 2, 0, 12))
        val title = TextView(context)
        title.text = LanguageCode.getMyTitles(467)
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider))
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
        title.typeface = AndroidUtilities.bold()
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22f, 0f, 22f, 8f))
        content.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val builder = BottomSheet.Builder(context, false, resourcesProvider)
        builder.setCustomView(content)
        val sheet = builder.create()

        val cells = ArrayList<RadioColorCell>()
        for (row in VoicePreset.ROWS) {
            val cell = RadioColorCell(context, resourcesProvider)
            cell.setPadding(AndroidUtilities.dp(4f), 0, AndroidUtilities.dp(4f), 0)
            cell.tag = row.id
            cells.add(cell)
            list.addView(cell)
            cell.setOnClickListener {
                if (row.id == VoicePreset.SPEED_ROW) {
                    askSpeed(context, resourcesProvider) {
                        refresh(cells, true)
                        sheet.dismiss()
                    }
                } else {
                    VoiceChanger.presetId = row.id
                    refresh(cells, true)
                    // Long enough to see the radio button move.
                    AndroidUtilities.runOnUIThread({ sheet.dismiss() }, 150)
                }
            }
        }
        refresh(cells, false)

        // Telegram draws its emoji once they are loaded; until then a row shows a placeholder, so redraw on the signal.
        val emojiObserver = NotificationCenter.NotificationCenterDelegate { id, _, _ ->
            if (id == NotificationCenter.emojiLoaded) {
                for (cell in cells) {
                    for (i in 0 until cell.childCount) {
                        cell.getChildAt(i).invalidate()
                    }
                }
            }
        }
        NotificationCenter.getGlobalInstance().addObserver(emojiObserver, NotificationCenter.emojiLoaded)
        // Once only: onClosed may resume the recording, and a second run would pause it again.
        var closed = false
        sheet.setOnDismissListener(Runnable {
            if (closed) return@Runnable
            closed = true
            NotificationCenter.getGlobalInstance().removeObserver(emojiObserver, NotificationCenter.emojiLoaded)
            onClosed?.run()
        })
        sheet.show()
    }

    /**
     * Ticks the chosen effect's row; the "Record speed" row also names the chosen speed under it. [animated] only moves
     * the radio buttons (after a tap), since setting the text resets them without animation.
     */
    private fun refresh(cells: List<RadioColorCell>, animated: Boolean) {
        val selected = VoiceChanger.presetId
        for (cell in cells) {
            val id = cell.tag as Int
            val checked = if (id == VoicePreset.SPEED_ROW) VoicePreset.isSpeed(selected) else id == selected
            if (animated) {
                cell.setChecked(checked, true)
                continue
            }
            val title = withEmoji(VoicePreset.ROWS.first { it.id == id })
            if (id == VoicePreset.SPEED_ROW && checked) {
                cell.setTextAndText2AndValue(title, LanguageCode.getMyTitles(VoicePreset.byId(selected).titleId), true)
            } else {
                cell.setTextAndValue(title, checked)
            }
        }
    }

    /** The four tape speeds, as a small dialog over the list. */
    private fun askSpeed(context: Context, resourcesProvider: Theme.ResourcesProvider?, onChosen: Runnable) {
        val list = LinearLayout(context)
        list.orientation = LinearLayout.VERTICAL
        val dialog = AlertDialog.Builder(context, resourcesProvider)
            .setTitle(LanguageCode.getMyTitles(479))
            .setView(list)
            .create()
        val selected = VoiceChanger.presetId
        for (speed in VoicePreset.SPEEDS) {
            val cell = RadioColorCell(context, resourcesProvider)
            cell.setPadding(AndroidUtilities.dp(4f), 0, AndroidUtilities.dp(4f), 0)
            cell.setTextAndValue(LanguageCode.getMyTitles(speed.titleId), speed.id == selected)
            list.addView(cell)
            cell.setOnClickListener {
                VoiceChanger.presetId = speed.id
                for (i in 0 until list.childCount) {
                    (list.getChildAt(i) as RadioColorCell).setChecked(list.getChildAt(i) === cell, true)
                }
                AndroidUtilities.runOnUIThread({
                    dialog.dismiss()
                    onChosen.run()
                }, 150)
            }
        }
        dialog.show()
    }

    private val emojiPaint = TextPaint().apply { textSize = AndroidUtilities.dp(16f).toFloat() }

    private fun withEmoji(row: VoicePreset): CharSequence =
        Emoji.replaceEmoji(row.emoji + "  " + LanguageCode.getMyTitles(row.titleId), emojiPaint.fontMetricsInt, false)
}
