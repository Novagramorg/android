package org.fenixuz.ui.join_requests

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.fenixuz.utils.LanguageCode
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RadialProgressView
import org.telegram.ui.Components.StickerImageView

/**
 * Novagram: a polished per-chat sheet to clear a backlog of pending join requests.
 *
 * UX: opens in a LOADING state, fetches the live pending count, then offers a clear choice —
 * Approve vs Decline (colour-coded), and how many (All / presets). The primary button reflects the exact
 * action ("Approve 234"). While running it shows live progress (spinner + done/total) and can be cancelled;
 * on completion it shows a short result. The heavy lifting is delegated to [JoinRequestsProcessor].
 */
class JoinRequestsSheet(
    fragment: BaseFragment,
    private val chatId: Long
) : BottomSheet(fragment.parentActivity, false) {

    private val ctx: Context = fragment.parentActivity
    private val account = UserConfig.selectedAccount

    // --- model ---
    private var pendingCount = 0
    private var approve = true
    private var selectedAmount = 0
    private var processor: JoinRequestsProcessor? = null
    private var processing = false

    // --- views ---
    private val subtitle: TextView
    private val modeRow: LinearLayout
    private val amountLabel: TextView
    private val amountRow: LinearLayout
    private val actionButton: TextView
    private val progressBox: LinearLayout
    private val progressSpinner: RadialProgressView
    private val progressText: TextView

    // --- colours ---
    private val blue get() = getThemedColor(Theme.key_featuredStickers_addButton)
    private val red get() = getThemedColor(Theme.key_text_RedRegular)
    private val onAccent get() = getThemedColor(Theme.key_featuredStickers_buttonText)
    private val textColor get() = getThemedColor(Theme.key_dialogTextBlack)
    private val grayColor get() = getThemedColor(Theme.key_dialogTextGray3)
    private val accent get() = if (approve) blue else red

    init {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val sticker = StickerImageView(ctx, account).apply {
            setStickerNum(2)
            imageReceiver.setAutoRepeat(1)
        }
        root.addView(sticker, LayoutHelper.createLinear(120, 120, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 4))

        val title = TextView(ctx).apply {
            text = LanguageCode.getMyTitles(279)
            gravity = Gravity.CENTER
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            typeface = AndroidUtilities.bold()
        }
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 22, 8, 22, 0))

        subtitle = TextView(ctx).apply {
            gravity = Gravity.CENTER
            setTextColor(grayColor)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            text = "…"
        }
        root.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 22, 6, 22, 18))

        modeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        root.addView(modeRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 16, 18))

        amountLabel = TextView(ctx).apply {
            text = LanguageCode.getMyTitles(284)
            setTextColor(grayColor)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            visibility = View.GONE
        }
        root.addView(amountLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 18, 0, 18, 8))

        amountRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        root.addView(amountRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 16, 20))

        // progress (hidden until processing)
        progressBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        progressSpinner = RadialProgressView(ctx).apply {
            setSize(dp(26f))
            setStrokeWidth(2.5f)
            setProgressColor(blue)
        }
        progressBox.addView(progressSpinner, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL, 0, 0, 12, 0))
        progressText = TextView(ctx).apply {
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            typeface = AndroidUtilities.bold()
        }
        progressBox.addView(progressText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        root.addView(progressBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 0, 16, 0, 16, 16))

        actionButton = TextView(ctx).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            typeface = AndroidUtilities.bold()
            isEnabled = false
            setOnClickListener { onActionClick() }
        }
        root.addView(actionButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 16, 4, 16, 16))

        val scroll = ScrollView(ctx).apply { addView(root) }
        setCustomView(scroll)

        renderActionButton()
        loadPendingCount()
    }

    // ---------------------------------------------------------------- loading

    private fun loadPendingCount() {
        val req = TLRPC.TL_messages_getChatInviteImporters()
        req.peer = MessagesController.getInstance(account).getInputPeer(-chatId)
        req.requested = true
        req.limit = 1
        req.offset_user = TLRPC.TL_inputUserEmpty()
        ConnectionsManager.getInstance(account).sendRequest(req) { response, _ ->
            AndroidUtilities.runOnUIThread {
                val count = (response as? TLRPC.TL_messages_chatInviteImporters)?.count ?: 0
                pendingCount = count
                onCountLoaded()
            }
        }
    }

    private fun onCountLoaded() {
        if (pendingCount <= 0) {
            subtitle.text = LanguageCode.getMyTitles(281) // No pending requests
            modeRow.visibility = View.GONE
            amountLabel.visibility = View.GONE
            amountRow.visibility = View.GONE
            actionButton.text = LanguageCode.getMyTitles(293) // Close
            actionButton.isEnabled = true
            styleButtonFilled(grayColor)
            actionButton.setOnClickListener { dismiss() }
            return
        }
        subtitle.text = "$pendingCount ${LanguageCode.getMyTitles(280)}" // N people are waiting to join
        selectedAmount = pendingCount
        modeRow.visibility = View.VISIBLE
        renderModeRow()
        renderAmountRow() // shows/hides the amount label+row depending on how many presets fit
        actionButton.isEnabled = true
        renderActionButton()
    }

    // ---------------------------------------------------------------- mode row

    private fun renderModeRow() {
        modeRow.removeAllViews()
        modeRow.addView(
            pill(LanguageCode.getMyTitles(282), approve, blue) { setApprove(true) },
            LayoutHelper.createLinear(0, 44, 1f, Gravity.CENTER, 0, 0, 5, 0)
        )
        modeRow.addView(
            pill(LanguageCode.getMyTitles(283), !approve, red) { setApprove(false) },
            LayoutHelper.createLinear(0, 44, 1f, Gravity.CENTER, 5, 0, 0, 0)
        )
    }

    private fun setApprove(value: Boolean) {
        if (approve == value) return
        approve = value
        renderModeRow()
        renderAmountRow() // selected-chip colour follows the mode (blue/red)
        renderActionButton()
    }

    // ---------------------------------------------------------------- amount row

    private fun amountOptions(): List<Int> {
        val presets = intArrayOf(100, 500, 1000).filter { it < pendingCount }
        // "All" first (= pendingCount), then the presets that are smaller than the backlog.
        return listOf(pendingCount) + presets
    }

    private fun renderAmountRow() {
        amountRow.removeAllViews()
        val options = amountOptions()
        if (options.size <= 1) {
            // Only "All" makes sense — hide the chooser entirely, keep the UI calm.
            amountLabel.visibility = View.GONE
            amountRow.visibility = View.GONE
            return
        }
        amountLabel.visibility = View.VISIBLE
        amountRow.visibility = View.VISIBLE
        options.forEachIndexed { index, value ->
            val label = if (value == pendingCount) LanguageCode.getMyTitles(285) else value.toString()
            val lp = LayoutHelper.createLinear(0, 40, 1f, Gravity.CENTER,
                if (index == 0) 0 else 4, 0, if (index == options.lastIndex) 0 else 4, 0)
            amountRow.addView(pill(label, selectedAmount == value, accent) { setAmount(value) }, lp)
        }
    }

    private fun setAmount(value: Int) {
        if (selectedAmount == value) return
        selectedAmount = value
        renderAmountRow()
        renderActionButton()
    }

    // ---------------------------------------------------------------- action button

    private fun renderActionButton() {
        if (processing) return
        if (pendingCount > 0) {
            val word = if (approve) LanguageCode.getMyTitles(282) else LanguageCode.getMyTitles(283)
            actionButton.text = "$word $selectedAmount"
            styleButtonFilled(accent)
        }
    }

    private fun onActionClick() {
        if (processing || pendingCount <= 0) return
        startProcessing()
    }

    private fun startProcessing() {
        processing = true
        modeRow.visibility = View.GONE
        amountLabel.visibility = View.GONE
        amountRow.visibility = View.GONE
        subtitle.visibility = View.GONE
        progressBox.visibility = View.VISIBLE
        progressSpinner.setProgressColor(accent)
        progressText.setTextColor(accent)
        progressText.text = "0 / $selectedAmount"
        actionButton.text = LanguageCode.getMyTitles(291) // Cancel
        styleButtonOutline(grayColor)
        actionButton.setOnClickListener { dismiss() }

        processor = JoinRequestsProcessor(
            account, chatId, approve, selectedAmount, pendingCount,
            object : JoinRequestsProcessor.Listener {
                override fun onProgress(done: Int, total: Int) {
                    progressText.text = "$done / $total"
                }

                override fun onFinished(done: Int) {
                    showDone(done)
                }

                override fun onError(text: String) {
                    showError()
                }
            }
        ).also { it.start() }
    }

    private fun showDone(done: Int) {
        progressBox.visibility = View.GONE
        subtitle.visibility = View.VISIBLE
        val verb = if (approve) LanguageCode.getMyTitles(289) else LanguageCode.getMyTitles(290)
        subtitle.setTextColor(grayColor)
        subtitle.text = "${LanguageCode.getMyTitles(288)} — $done $verb" // Done — N approved/declined
        finishButton()
    }

    private fun showError() {
        progressBox.visibility = View.GONE
        subtitle.visibility = View.VISIBLE
        subtitle.setTextColor(red)
        subtitle.text = LanguageCode.getMyTitles(292) // Something went wrong
        finishButton()
    }

    private fun finishButton() {
        processing = false
        actionButton.text = LanguageCode.getMyTitles(293) // Close
        styleButtonFilled(blue)
        actionButton.setOnClickListener { dismiss() }
    }

    // ---------------------------------------------------------------- styling helpers

    /** A rounded pill: filled accent when selected, outlined otherwise. */
    private fun pill(label: String?, selected: Boolean, color: Int, onClick: () -> Unit): TextView {
        return TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            typeface = AndroidUtilities.bold()
            isSingleLine = true
            val bg = GradientDrawable()
            bg.cornerRadius = dp(10f).toFloat()
            if (selected) {
                bg.setColor(color)
                setTextColor(onAccent)
            } else {
                bg.setColor(0)
                bg.setStroke(dp(1.5f), Theme.multAlpha(grayColor, 0.4f))
                setTextColor(grayColor)
            }
            background = bg
            setOnClickListener { onClick() }
        }
    }

    private fun styleButtonFilled(color: Int) {
        val bg = GradientDrawable()
        bg.cornerRadius = dp(8f).toFloat()
        bg.setColor(color)
        actionButton.background = bg
        actionButton.setTextColor(onAccent)
    }

    private fun styleButtonOutline(color: Int) {
        val bg = GradientDrawable()
        bg.cornerRadius = dp(8f).toFloat()
        bg.setColor(0)
        bg.setStroke(dp(1.5f), Theme.multAlpha(color, 0.5f))
        actionButton.background = bg
        actionButton.setTextColor(color)
    }

    override fun canDismissWithSwipe(): Boolean = !processing

    override fun dismiss() {
        processor?.cancel()
        processor = null
        super.dismiss()
    }
}
