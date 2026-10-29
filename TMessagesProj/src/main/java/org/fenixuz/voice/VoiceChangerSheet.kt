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
import org.telegram.ui.Components.NumberPicker

/**
 * Novagram voice changer: the list of effects, opened from the button above the pause button while a voice message is
 * recorded (as in Graph Messenger), from the call screens, or from the Novagram settings. One radio row per effect, in
 * Graph's order with its emoji; "Record speed" asks for one of four speeds, and Modulation, Echo and Noise for their
 * settings, which their rows show under the name, as in Graph. Voice messages and calls keep a choice each ([forCall]);
 * calls have no speed row, as a live voice can't be played faster or slower.
 */
object VoiceChangerSheet {

    /** [onClosed] runs once as the sheet starts to close, however it is closed (a pick, Back, a tap outside, a swipe). */
    @JvmStatic
    fun show(context: Context, resourcesProvider: Theme.ResourcesProvider?, forCall: Boolean, onClosed: Runnable?) {
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
            if (forCall && row.id == VoicePreset.SPEED_ROW) continue
            val cell = RadioColorCell(context, resourcesProvider)
            cell.setPadding(AndroidUtilities.dp(4f), 0, AndroidUtilities.dp(4f), 0)
            cell.tag = row.id
            cells.add(cell)
            list.addView(cell)
            cell.setOnClickListener {
                if (row.id == VoicePreset.SPEED_ROW) {
                    askSpeed(context, resourcesProvider) {
                        refresh(cells, true, forCall)
                        sheet.dismiss()
                    }
                } else if (VoicePreset.hasSettings(row.id)) {
                    askSettings(context, resourcesProvider, forCall, row) {
                        refresh(cells, true, forCall)
                        sheet.dismiss()
                    }
                } else {
                    if (forCall) VoiceChanger.callPresetId = row.id else VoiceChanger.presetId = row.id
                    refresh(cells, true, forCall)
                    // Long enough to see the radio button move.
                    AndroidUtilities.runOnUIThread({ sheet.dismiss() }, 150)
                }
            }
        }
        refresh(cells, false, forCall)

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
    private fun refresh(cells: List<RadioColorCell>, animated: Boolean, forCall: Boolean) {
        val selected = if (forCall) VoiceChanger.callPresetId else VoiceChanger.presetId
        val params = VoiceChanger.of(forCall).params
        for (cell in cells) {
            val id = cell.tag as Int
            val checked = if (id == VoicePreset.SPEED_ROW) VoicePreset.isSpeed(selected) else id == selected
            if (animated) {
                cell.setChecked(checked, true)
                continue
            }
            val title = withEmoji(VoicePreset.ROWS.first { it.id == id })
            val settings = VoicePreset.settingsText(id, params)
            if (id == VoicePreset.SPEED_ROW && checked) {
                cell.setTextAndText2AndValue(title, LanguageCode.getMyTitles(VoicePreset.byId(selected).titleId), true)
            } else if (settings != null) {
                cell.setTextAndText2AndValue(title, settings, checked)
            } else {
                cell.setTextAndValue(title, checked)
            }
        }
    }

    /**
     * The settings of Modulation, Echo or Noise, as number wheels in a small dialog over the list, in Graph's steps.
     * Done keeps them and picks the effect; Cancel leaves both as they were.
     */
    private fun askSettings(
        context: Context,
        resourcesProvider: Theme.ResourcesProvider?,
        forCall: Boolean,
        row: VoicePreset,
        onChosen: Runnable,
    ) {
        val choice = VoiceChanger.of(forCall)
        val params = choice.params
        val wheels = LinearLayout(context)
        wheels.orientation = LinearLayout.HORIZONTAL

        fun wheel(label: String?, min: Int, max: Int, value: Int, format: (Int) -> String): NumberPicker {
            val column = LinearLayout(context)
            column.orientation = LinearLayout.VERTICAL
            if (label != null) {
                val text = TextView(context)
                text.text = label
                text.gravity = Gravity.CENTER
                text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                text.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider))
                column.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 8f, 0f, 0f))
            }
            val picker = NumberPicker(context, resourcesProvider)
            picker.setMinValue(min)
            picker.setMaxValue(max)
            picker.setValue(value)
            picker.setWrapSelectorWheel(false)
            picker.setFormatter { format(it) }
            column.addView(picker, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            wheels.addView(column, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
            return picker
        }

        val content: android.view.View
        val read: () -> VoiceParams
        when (row.id) {
            VoicePreset.MODULATION -> {
                // NumberPicker takes no negative values, so the wheel runs 0..24 and shows -12..+12.
                val offset = VoiceParams.TONE_MIN
                val tone = wheel(null, 0, VoiceParams.TONE_MAX - offset, params.tone - offset) { VoicePreset.toneText(it + offset) }
                val layout = LinearLayout(context)
                layout.orientation = LinearLayout.VERTICAL
                layout.addView(wheels, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
                val hint = TextView(context)
                hint.text = LanguageCode.getMyTitles(494)
                hint.gravity = Gravity.CENTER
                hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                hint.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider))
                layout.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24f, 4f, 24f, 0f))
                content = layout
                read = { params.copy(tone = tone.value + offset) }
            }
            VoicePreset.ECHO -> {
                val delay = wheel(LanguageCode.getMyTitles(492), VoiceParams.STEP_MIN, VoiceParams.STEP_MAX, params.echoDelay) { VoicePreset.secondsText(it) }
                val volume = wheel(LanguageCode.getMyTitles(493), VoiceParams.STEP_MIN, VoiceParams.STEP_MAX, params.echoVolume) { VoicePreset.percentText(it) }
                content = wheels
                read = { params.copy(echoDelay = delay.value, echoVolume = volume.value) }
            }
            else -> {
                val volume = wheel(LanguageCode.getMyTitles(493), VoiceParams.STEP_MIN, VoiceParams.STEP_MAX, params.noiseVolume) { VoicePreset.percentText(it) }
                content = wheels
                read = { params.copy(noiseVolume = volume.value) }
            }
        }
        AlertDialog.Builder(context, resourcesProvider)
            .setTitle(LanguageCode.getMyTitles(row.titleId))
            .setView(content)
            .setPositiveButton(LanguageCode.getMyTitles(288)) { _, _ ->
                choice.params = read()
                choice.presetId = row.id
                onChosen.run()
            }
            .setNegativeButton(LanguageCode.getMyTitles(80), null)
            .show()
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
