package org.fenixuz.callrec

import android.content.Context
import android.view.View
import org.fenixuz.utils.LanguageCode
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.AlertsCreator
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalFragment

/**
 * Novagram: Settings → Novagram → "Call recording". Plain Telegram settings page: the switch, automatic recording, the
 * video quality and the recordings list with its total size. The one-line top text says the other person is told about a
 * recording; the rest is explained by the first-time consent alert, not by footers.
 */
class CallRecordingScreen : UniversalFragment() {

    private val ENABLED = 1
    private val AUTO = 2
    private val RECORDINGS = 3
    private val QUALITY_AUDIO = 4
    private val QUALITY_SAVER = 5
    private val QUALITY_HIGH = 6
    private val KEEP = 7

    /** "340 MB" for the recordings button: how much the recordings take; empty until measured or when there are none. */
    private var sizeValue = ""

    override fun getTitle(): CharSequence = LanguageCode.getMyTitles(527)

    override fun createView(context: Context): View {
        val view = super.createView(context)
        listView.setSections()
        listView.adapter.setApplyBackground(false)
        actionBar.setAdaptiveBackground(listView)
        return view
    }

    override fun onResume() {
        super.onResume()
        refreshSize()
    }

    private fun refreshSize() {
        Utilities.globalQueue.postRunnable {
            CallRecordingStorage.prune()
            val list = CallRecordingStorage.list()
            val value = if (list.isEmpty()) "" else AndroidUtilities.formatFileSize(list.sumOf { it.size })
            AndroidUtilities.runOnUIThread {
                if (value != sizeValue) {
                    sizeValue = value
                    listView?.adapter?.update(true)
                }
            }
        }
    }

    private fun keepLabel(days: Int): String = when (days) {
        7 -> LanguageCode.getMyTitles(569)
        30 -> LanguageCode.getMyTitles(570)
        90 -> LanguageCode.getMyTitles(571)
        else -> LanguageCode.getMyTitles(568)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asTopView(LanguageCode.getMyTitles(529), R.raw.utyan_call))
        items.add(UItem.asCheck(ENABLED, LanguageCode.getMyTitles(527)).setChecked(CallRecordingSettings.isEnabled))
        if (CallRecordingSettings.isEnabled) {
            items.add(UItem.asCheck(AUTO, LanguageCode.getMyTitles(530)).setChecked(CallRecordingSettings.isAutoEnabled))
            items.add(UItem.asShadow(null))

            val quality = CallRecordingSettings.quality
            items.add(UItem.asHeader(LanguageCode.getMyTitles(560)))
            items.add(UItem.asRadio(QUALITY_AUDIO, LanguageCode.getMyTitles(561)).setChecked(quality == CallRecordingSettings.Quality.AUDIO_ONLY))
            items.add(UItem.asRadio(QUALITY_SAVER, LanguageCode.getMyTitles(562)).setChecked(quality == CallRecordingSettings.Quality.BATTERY_SAVER))
            items.add(UItem.asRadio(QUALITY_HIGH, LanguageCode.getMyTitles(563)).setChecked(quality == CallRecordingSettings.Quality.HIGH))
        }
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LanguageCode.getMyTitles(556)))
        items.add(UItem.asButton(RECORDINGS, R.drawable.msg_calls, LanguageCode.getMyTitles(532), sizeValue))
        items.add(UItem.asButton(KEEP, R.drawable.msg_autodelete, LanguageCode.getMyTitles(567), keepLabel(CallRecordingSettings.keepDays)))
        items.add(UItem.asShadow(LanguageCode.getMyTitles(572)))
    }

    private fun chooseKeepDays() {
        val activity = parentActivity ?: return
        val options = CallRecordingSettings.KEEP_DAYS_OPTIONS
        val labels = Array(options.size) { keepLabel(options[it]) }
        val selected = options.indexOf(CallRecordingSettings.keepDays).coerceAtLeast(0)
        showDialog(AlertsCreator.createSingleChoiceDialog(activity, labels, LanguageCode.getMyTitles(567), selected) { _, which ->
            CallRecordingSettings.keepDays = options[which]
            listView?.adapter?.update(true)
            // A shorter limit applies at once: the size reflects it.
            refreshSize()
        })
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            ENABLED -> {
                CallRecordingSettings.isEnabled = !CallRecordingSettings.isEnabled
                listView?.adapter?.update(true)
            }
            AUTO -> {
                CallRecordingSettings.isAutoEnabled = !CallRecordingSettings.isAutoEnabled
                listView?.adapter?.update(true)
            }
            RECORDINGS -> presentFragment(CallRecordingsListScreen())
            KEEP -> chooseKeepDays()
            QUALITY_AUDIO, QUALITY_SAVER, QUALITY_HIGH -> {
                CallRecordingSettings.quality = when (item.id) {
                    QUALITY_AUDIO -> CallRecordingSettings.Quality.AUDIO_ONLY
                    QUALITY_SAVER -> CallRecordingSettings.Quality.BATTERY_SAVER
                    else -> CallRecordingSettings.Quality.HIGH
                }
                listView?.adapter?.update(true)
            }
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean = false

    companion object {
        @JvmStatic
        fun open(fragment: BaseFragment) {
            fragment.presentFragment(CallRecordingScreen())
        }
    }
}
