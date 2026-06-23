package org.fenixuz.ui.auto_text

import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.view.Gravity
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import org.fenixuz.utils.AutoTextAppender
import org.fenixuz.utils.LanguageCode
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.ChatActivityEnterView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.SizeNotifierFrameLayout
import org.telegram.ui.WrappedResourceProvider
import kotlin.math.min

/**
 * Novagram: the styled composer for a chat's [AutoTextAppender] signature. Hosts a real
 * [ChatActivityEnterView] so the user can enter text with styling + custom/premium emoji. On Done it
 * captures the field text + entities and persists them; the per-chat active flag is kept as-is (toggled
 * in [AutoTextSettings]). Ported from pro's AutoPostEditor, adapted to Production's 5-arg enter-view
 * constructor and [AutoTextAppender] storage.
 */
class AutoTextEditor(private val dialogId: Long) : BaseFragment() {

    private lateinit var doneButton: View
    private var chatActivityEnterView: ChatActivityEnterView? = null
    private lateinit var sizeNotifierFrameLayout: SizeNotifierFrameLayout
    private var oldInputStr: CharSequence = ""

    override fun createView(context: Context): View {
        setUpActionBar()
        setUpBaseUi()
        setUpInput()

        val fm = chatActivityEnterView?.editField?.paint?.fontMetricsInt
        oldInputStr = if (fm != null) AutoTextAppender.getEditPrefill(dialogId, fm) else ""
        chatActivityEnterView?.setOverrideHint(AutoTextAppender.chatLinkHint(dialogId))
        chatActivityEnterView?.setFieldText(oldInputStr)
        chatActivityEnterView?.setSelection(oldInputStr.length)

        return fragmentView
    }

    private fun setUpActionBar() {
        actionBar.setTitle(LanguageCode.getMyTitles(300))
        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setAllowOverlayTitle(false)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == 1) {
                    save()
                    finishFragment()
                } else if (id == -1) {
                    if (checkUnchanged()) finishFragment() else discardDialog()
                }
            }
        })
        val menu = actionBar.createMenu()
        doneButton = menu.addItemWithWidth(1, R.drawable.ic_ab_done, AndroidUtilities.dp(56f), LocaleController.getString(R.string.Done))
        doneButton.alpha = 0f
        doneButton.scaleX = 0f
        doneButton.scaleY = 0f
        doneButton.isEnabled = false
    }

    private fun checkUnchanged(): Boolean {
        val cur = chatActivityEnterView?.editField?.textToUse?.toString() ?: ""
        return oldInputStr.toString() == cur
    }

    private fun updateDoneButton(force: Boolean = false) {
        val enabled = force || !checkUnchanged()
        doneButton.isEnabled = enabled
        doneButton.animate().alpha(if (enabled) 1f else 0f)
            .scaleX(if (enabled) 1f else 0f).scaleY(if (enabled) 1f else 0f)
            .setDuration(180).start()
    }

    private fun setUpInput() {
        chatActivityEnterView?.onDestroy()
        val emojiResourceProvider: Theme.ResourcesProvider = object : WrappedResourceProvider(resourceProvider) {
            override fun appendColors() {
                sparseIntArray.put(Theme.key_chat_emojiPanelBackground, ColorUtils.setAlphaComponent(Color.WHITE, 30))
            }
        }
        chatActivityEnterView = object : ChatActivityEnterView(
            AndroidUtilities.findActivity(context), sizeNotifierFrameLayout, null, false, emojiResourceProvider
        ) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = if (alpha != 1.0f) false else super.onInterceptTouchEvent(ev)
            override fun onTouchEvent(event: MotionEvent): Boolean = if (alpha != 1.0f) false else super.onTouchEvent(event)
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean = if (alpha != 1.0f) false else super.dispatchTouchEvent(ev)
            override fun pannelAnimationEnabled(): Boolean = true
            override fun checkAnimation() {}
            override fun onLineCountChanged(oldLineCount: Int, newLineCount: Int) {}
            // Composer: this is a text editor, not a chat — keep the send/record buttons hidden so the only
            // action is the action-bar Done (✓). checkSendButton is what normally toggles send vs. mic, so we
            // override it to instead force both off (self-healing on every text change).
            override fun checkSendButton(animated: Boolean) {
                getSendButtonInternal()?.setVisibility(View.GONE)
                getAudioVideoButtonContainer()?.setVisibility(View.GONE)
            }
            override fun extendActionMode(menu: Menu?) {
                ChatActivity.fillActionModeMenu(menu, null, false, true)
            }
        }
        chatActivityEnterView?.setDelegate(object : ChatActivityEnterView.ChatActivityEnterViewDelegate {
            override fun onMessageSend(message: CharSequence?, notify: Boolean, scheduleDate: Int, scheduleRepeatPeriod: Int, payStars: Long) {}
            override fun needSendTyping() {}
            override fun onTextChanged(text: CharSequence, bigChange: Boolean, fromDraft: Boolean) { updateDoneButton() }
            override fun onTextSelectionChanged(start: Int, end: Int) {}
            override fun onTextSpansChanged(text: CharSequence) { updateDoneButton(true) }
            override fun onAttachButtonHidden() {}
            override fun onAttachButtonShow() {}
            override fun onWindowSizeChanged(size: Int) {}
            override fun onStickersTab(opened: Boolean) {}
            override fun onMessageEditEnd(loading: Boolean) {}
            override fun didPressAttachButton() {}
            override fun needStartRecordVideo(state: Int, notify: Boolean, scheduleDate: Int, scheduleRepeatPeriod: Int, ttl: Int, effectId: Long, stars: Long) {}
            override fun toggleVideoRecordingPause() {}
            override fun isVideoRecordingPaused(): Boolean = false
            override fun needChangeVideoPreviewState(state: Int, seekProgress: Float) {}
            override fun onSwitchRecordMode(video: Boolean) {}
            override fun onPreAudioVideoRecord() {}
            override fun needStartRecordAudio(state: Int) {}
            override fun needShowMediaBanHint() {}
            override fun onStickersExpandedChange() {}
            override fun onUpdateSlowModeButton(button: View?, show: Boolean, time: CharSequence?) {}
            override fun onSendLongClick() {}
            override fun onAudioVideoInterfaceUpdated() {}
        })
        chatActivityEnterView?.setAllowStickersAndGifs(true, true, true)
        // No send / mic button in a text editor — only the action-bar Done saves the signature.
        chatActivityEnterView?.getSendButtonInternal()?.visibility = View.GONE
        chatActivityEnterView?.getAudioVideoButtonContainer()?.visibility = View.GONE
        sizeNotifierFrameLayout.addView(
            chatActivityEnterView,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.LEFT or Gravity.BOTTOM, 0f, 0f, 0f, 0f)
        )
    }

    private fun setUpBaseUi() {
        sizeNotifierFrameLayout = object : SizeNotifierFrameLayout(context) {
            private var ignoreLayout = false
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val widthSize = MeasureSpec.getSize(widthMeasureSpec)
                var heightSize = MeasureSpec.getSize(heightMeasureSpec)
                setMeasuredDimension(widthSize, heightSize)
                heightSize -= paddingTop
                measureChildWithMargins(actionBar, widthMeasureSpec, 0, heightMeasureSpec, 0)
                val keyboardSize = measureKeyboardHeight()
                if (keyboardSize > AndroidUtilities.dp(20f)) {
                    ignoreLayout = true
                    chatActivityEnterView?.hideEmojiView()
                    ignoreLayout = false
                }
                val count = childCount
                for (i in 0 until count) {
                    val child = getChildAt(i) ?: continue
                    if (child.visibility == GONE || child === actionBar) continue
                    if (chatActivityEnterView != null && chatActivityEnterView!!.isPopupView(child)) {
                        if (AndroidUtilities.isInMultiwindow || AndroidUtilities.isTablet()) {
                            if (AndroidUtilities.isTablet()) {
                                child.measure(
                                    MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY),
                                    MeasureSpec.makeMeasureSpec(min(AndroidUtilities.dp(if (AndroidUtilities.isTablet()) 200f else 320f).toDouble(), (heightSize - AndroidUtilities.statusBarHeight + paddingTop).toDouble()).toInt(), MeasureSpec.EXACTLY)
                                )
                            } else {
                                child.measure(
                                    MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY),
                                    MeasureSpec.makeMeasureSpec(heightSize - AndroidUtilities.statusBarHeight + paddingTop, MeasureSpec.EXACTLY)
                                )
                            }
                        } else {
                            child.measure(
                                MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY),
                                MeasureSpec.makeMeasureSpec(child.layoutParams.height, MeasureSpec.EXACTLY)
                            )
                        }
                    } else {
                        measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
                    }
                }
            }

            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                val count = childCount
                val keyboardSize = measureKeyboardHeight()
                val paddingBottom = if (keyboardSize <= AndroidUtilities.dp(20f) && !AndroidUtilities.isInMultiwindow && !AndroidUtilities.isTablet()) chatActivityEnterView!!.emojiPadding else 0
                setBottomClip(paddingBottom)
                for (i in 0 until count) {
                    val child = getChildAt(i)
                    if (child.visibility == GONE) continue
                    val lp = child.layoutParams as LayoutParams
                    val width = child.measuredWidth
                    val height = child.measuredHeight
                    var gravity = lp.gravity
                    if (gravity == -1) gravity = Gravity.TOP or Gravity.LEFT
                    val absoluteGravity = gravity and Gravity.HORIZONTAL_GRAVITY_MASK
                    val verticalGravity = gravity and Gravity.VERTICAL_GRAVITY_MASK
                    val childLeft = when (absoluteGravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
                        Gravity.CENTER_HORIZONTAL -> (r - l - width) / 2 + lp.leftMargin - lp.rightMargin
                        Gravity.RIGHT -> r - width - lp.rightMargin
                        else -> lp.leftMargin
                    }
                    var childTop = when (verticalGravity) {
                        Gravity.TOP -> lp.topMargin + paddingTop
                        Gravity.CENTER_VERTICAL -> ((b - paddingBottom) - t - height) / 2 + lp.topMargin - lp.bottomMargin
                        Gravity.BOTTOM -> (b - paddingBottom) - t - height - lp.bottomMargin
                        else -> lp.topMargin
                    }
                    if (chatActivityEnterView != null && chatActivityEnterView!!.isPopupView(child)) {
                        childTop = if (AndroidUtilities.isTablet()) measuredHeight - child.measuredHeight else measuredHeight + keyboardSize - child.measuredHeight
                    }
                    child.layout(childLeft, childTop, childLeft + width, childTop + height)
                }
                notifyHeightChanged()
            }

            override fun requestLayout() {
                if (ignoreLayout) return
                super.requestLayout()
            }
        }
        sizeNotifierFrameLayout.setOnTouchListener { _, _ -> true }
        fragmentView = sizeNotifierFrameLayout
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
    }

    override fun onBackPressed(invoked: Boolean): Boolean {
        if (chatActivityEnterView != null && chatActivityEnterView!!.isPopupShowing) {
            chatActivityEnterView!!.hidePopup(true)
            return false
        }
        if (checkUnchanged()) return true
        discardDialog()
        return false
    }

    override fun onPause() {
        super.onPause()
        chatActivityEnterView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        chatActivityEnterView?.onResume()
        chatActivityEnterView?.editField?.requestFocus()
    }

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
        chatActivityEnterView?.onDestroy()
    }

    private fun discardDialog() {
        val builder = AlertDialog.Builder(parentActivity)
        builder.setTitle(LocaleController.getString(R.string.AppName))
        builder.setMessage(LanguageCode.getMyTitles(304))
        builder.setPositiveButton(LocaleController.getString(R.string.Save)) { _: DialogInterface?, _: Int ->
            save()
            finishFragment()
        }
        builder.setNegativeButton(LocaleController.getString(R.string.PassportDiscard)) { _: DialogInterface?, _: Int -> finishFragment() }
        showDialog(builder.create())
    }

    private fun save() {
        val message = (chatActivityEnterView?.editField?.textToUse ?: "").trim()
        val messages = arrayOf<CharSequence>(message)
        val entities: ArrayList<TLRPC.MessageEntity> = MediaDataController.getInstance(currentAccount).getEntities(messages, true)
        AutoTextAppender.save(dialogId, AutoTextAppender.isActive(dialogId), messages[0].toString(), entities)
    }
}
