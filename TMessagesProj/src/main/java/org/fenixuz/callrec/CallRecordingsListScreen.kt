package org.fenixuz.callrec

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.fenixuz.utils.LanguageCode
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RLottieDrawable
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalFragment
import java.util.Calendar

/**
 * Novagram: the list of call recordings, newest first, grouped by day. A row shows who the call was with, when, how
 * long and how big; a tap opens the player sheet with Share / Save to device / Send to Saved Messages / Delete, and the
 * action bar deletes everything. The folder is read off the UI thread; the list shows the previous content until then.
 */
class CallRecordingsListScreen : UniversalFragment() {

    private val ROW_BASE = 1000
    private val DELETE_ALL = 1

    private var recordings: List<CallRecordingStorage.Recording> = emptyList()
    private var loaded = false
    private var emptyView: View? = null

    override fun getTitle(): CharSequence = LanguageCode.getMyTitles(532)

    override fun createView(context: Context): View {
        val view = super.createView(context)
        listView.setSections()
        listView.adapter.setApplyBackground(false)
        actionBar.setAdaptiveBackground(listView)
        actionBar.createMenu().addItem(DELETE_ALL, R.drawable.msg_delete).contentDescription = LanguageCode.getMyTitles(549)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) {
                    -1 -> finishFragment()
                    DELETE_ALL -> confirmDeleteAll()
                }
            }
        })
        return view
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    fun reload() {
        Utilities.globalQueue.postRunnable {
            CallRecordingStorage.recover()
            CallRecordingStorage.prune()
            val list = CallRecordingStorage.list()
            // Durations are measured here, off the UI thread, so the rows have them when they appear.
            list.forEach { it.durationMs }
            AndroidUtilities.runOnUIThread {
                recordings = list
                loaded = true
                listView?.adapter?.update(true)
            }
        }
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        if (!loaded) return
        if (recordings.isEmpty()) {
            items.add(UItem.asCustomShadow(emptyView()))
            return
        }
        val total = recordings.sumOf { it.size }
        items.add(UItem.asShadow(String.format(LanguageCode.getMyTitles(554), recordings.size, AndroidUtilities.formatFileSize(total))))
        var lastDay = -1L
        val cal = Calendar.getInstance()
        for ((index, rec) in recordings.withIndex()) {
            cal.timeInMillis = rec.date
            val day = cal.get(Calendar.YEAR) * 1000L + cal.get(Calendar.DAY_OF_YEAR)
            if (day != lastDay) {
                if (lastDay != -1L) items.add(UItem.asShadow(null))
                items.add(UItem.asHeader(LocaleController.formatDateChat(rec.date / 1000)))
                lastDay = day
            }
            val next = recordings.getOrNull(index + 1)
            val divider = next != null && run {
                cal.timeInMillis = next.date
                cal.get(Calendar.YEAR) * 1000L + cal.get(Calendar.DAY_OF_YEAR) == day
            }
            items.add(UItem.asCustom(ROW_BASE + index, RecordingCell(context, resourceProvider).also { it.set(rec, divider) }))
        }
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        val index = item.id - ROW_BASE
        val rec = recordings.getOrNull(index) ?: return
        CallRecordingPlayerSheet.show(this, rec) { reload() }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        val rec = recordings.getOrNull(item.id - ROW_BASE) ?: return false
        CallRecordingPlayerSheet.confirmDelete(this, rec) { reload() }
        return true
    }

    private fun confirmDeleteAll() {
        if (recordings.isEmpty()) return
        val builder = AlertDialog.Builder(context, resourceProvider)
        builder.setTitle(LanguageCode.getMyTitles(549))
        builder.setMessage(String.format(LanguageCode.getMyTitles(551), recordings.size))
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null)
        builder.setPositiveButton(LanguageCode.getMyTitles(548)) { _, _ ->
            Utilities.globalQueue.postRunnable {
                CallRecordingStorage.deleteAll()
                AndroidUtilities.runOnUIThread { reload() }
            }
        }
        val dialog = builder.create()
        showDialog(dialog)
        dialog.redPositive()
    }

    private fun emptyView(): View {
        emptyView?.let { return it }
        val context = context
        val layout = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val list = listView
                val height = if (list != null && list.measuredHeight > 0) list.measuredHeight - list.paddingTop - list.paddingBottom else 0
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxOf(height, dp(240f)), MeasureSpec.EXACTLY))
            }
        }
        layout.orientation = LinearLayout.VERTICAL
        layout.gravity = Gravity.CENTER
        val imageView = BackupImageView(context)
        imageView.setImageDrawable(RLottieDrawable(R.raw.utyan_empty, dp(120f), dp(120f)))
        layout.addView(imageView, LayoutHelper.createLinear(120, 120, Gravity.CENTER_HORIZONTAL))
        val textView = TextView(context)
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f)
        textView.typeface = AndroidUtilities.bold()
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider))
        textView.gravity = Gravity.CENTER
        textView.text = LanguageCode.getMyTitles(544)
        layout.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 16, 32, 0))
        emptyView = layout
        return layout
    }

    /**
     * One recording: the profile photo of the person called (initials coloured by the name when the user is not known
     * any more), the name, and "14:25 · 03:12 · 1.2 MB".
     */
    class RecordingCell(context: Context, resourcesProvider: Theme.ResourcesProvider?) : FrameLayout(context) {
        private val avatar = AvatarDrawable()
        private val photo = BackupImageView(context)
        private val title = TextView(context)
        private val subtitle = TextView(context)
        private var divider = false
        private val dividerPaint = Paint().apply { color = Theme.getColor(Theme.key_divider, resourcesProvider) }

        init {
            setWillNotDraw(false)
            val start = if (LocaleController.isRTL) Gravity.RIGHT else Gravity.LEFT
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider))
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            title.typeface = AndroidUtilities.bold()
            title.maxLines = 1
            title.ellipsize = TextUtils.TruncateAt.END
            title.gravity = start
            addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), start or Gravity.TOP, if (LocaleController.isRTL) 16f else 72f, 10f, if (LocaleController.isRTL) 72f else 16f, 0f))
            subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider))
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            subtitle.maxLines = 1
            subtitle.ellipsize = TextUtils.TruncateAt.END
            subtitle.gravity = start
            addView(subtitle, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), start or Gravity.TOP, if (LocaleController.isRTL) 16f else 72f, 33f, if (LocaleController.isRTL) 72f else 16f, 0f))
            avatar.setTextSize(dp(18f))
            photo.setRoundRadius(dp(20f))
            addView(photo, LayoutHelper.createFrame(40, 40f, Gravity.CENTER_VERTICAL or start, 16f, 0f, 16f, 0f))
        }

        fun set(rec: CallRecordingStorage.Recording, divider: Boolean) {
            // The user as this account knows them today; an account that is logged out, or a recording from before
            // the id was kept, falls back to initials. The same name always gets the same colour: a stable hash.
            val user = if (rec.peerId != 0L && UserConfig.getInstance(rec.peerAccount).isClientActivated) MessagesController.getInstance(rec.peerAccount).getUser(rec.peerId) else null
            if (user != null) {
                avatar.setInfo(rec.peerAccount, user)
                photo.setForUserOrChat(user, avatar)
            } else {
                var hash = 0L
                for (ch in rec.peerName) hash = (hash * 31 + ch.code) and 0x7fffffffL
                avatar.setInfo(hash, rec.peerName, null)
                photo.setImageDrawable(avatar)
            }
            title.text = rec.peerName
            // The system time format (12/24 h) without seconds: a recording's time is known to the minute only.
            val time = android.text.format.DateFormat.getTimeFormat(context).format(rec.date)
            val dur = AndroidUtilities.formatShortDuration((rec.durationMs / 1000).toInt())
            val kind = if (rec.isVideo) LanguageCode.getMyTitles(565) + " · " else ""
            subtitle.text = "$kind$time · $dur · ${AndroidUtilities.formatFileSize(rec.size)}"
            this.divider = divider
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(60f), MeasureSpec.EXACTLY))
        }

        override fun onDraw(canvas: Canvas) {
            if (divider) {
                val x = if (LocaleController.isRTL) 0f else dp(72f).toFloat()
                val x2 = if (LocaleController.isRTL) (width - dp(72f)).toFloat() else width.toFloat()
                canvas.drawRect(x, height - 1f, x2, height.toFloat(), dividerPaint)
            }
        }
    }
}
