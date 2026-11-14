package org.fenixuz.callrec

import android.content.Context
import android.view.View
import org.fenixuz.utils.LanguageCode
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalFragment

/**
 * Novagram: Settings → Novagram → "Call recording". Plain Telegram settings page: the switch, automatic recording, the
 * video quality and the recordings list with its count. The one-line top text says the other person is told about a
 * recording; the rest is explained by the first-time consent alert, not by footers.
 */
class CallRecordingScreen : UniversalFragment() {

    private val ENABLED = 1
    private val AUTO = 2
    private val RECORDINGS = 3
    private val QUALITY_AUDIO = 4
    private val QUALITY_SAVER = 5
    private val QUALITY_HIGH = 6

    private var count = -1

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
        refreshCount()
    }

    private fun refreshCount() {
        Utilities.globalQueue.postRunnable {
            val n = CallRecordingStorage.count()
            org.telegram.messenger.AndroidUtilities.runOnUIThread {
                if (n != count) {
                    count = n
                    listView?.adapter?.update(true)
                }
            }
        }
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
        items.add(UItem.asButton(RECORDINGS, R.drawable.msg_calls, LanguageCode.getMyTitles(532), if (count >= 0) count.toString() else ""))
        items.add(UItem.asShadow(null))
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
