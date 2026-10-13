package org.fenixuz.ui.message_history

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.ChatAvatarContainer
import org.telegram.ui.Components.CustomPhoneKeyboardView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.SizeNotifierFrameLayout
import org.fenixuz.utils.EditMessage
import org.fenixuz.utils.LanguageCode

class MessageHistory(val msg: MessageObject, var dialogId: Long) : BaseFragment() {


    private val keyboardView: CustomPhoneKeyboardView? = null
    private var avatarContainer: ChatAvatarContainer? = null
    var user: TLRPC.User? = null
    var chat: TLRPC.Chat? = null
    lateinit var msgHistoryAdapter: MsgHistoryAdapter

    init {
        user = ChatActivity.instance.getCurrentUser()
        chat = ChatActivity.instance.getCurrentChat()
    }

    override fun createView(context: Context?): View {
        setUpActionBar()

        var fragmentContentView: View
        val frameLayout = FrameLayout(context!!)
        fragmentContentView = frameLayout

        val contentView: SizeNotifierFrameLayout = object : SizeNotifierFrameLayout(context) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                var frameBottom: Int
                fragmentContentView.layout(0, 0, measuredWidth, measuredHeight.also {
                    frameBottom = it
                })

                keyboardView?.layout(
                    0,
                    frameBottom,
                    measuredWidth,
                    frameBottom + AndroidUtilities.dp(CustomPhoneKeyboardView.KEYBOARD_HEIGHT_DP.toFloat())
                )
                notifyHeightChanged()
            }

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = MeasureSpec.getSize(widthMeasureSpec)
                val height = MeasureSpec.getSize(heightMeasureSpec)
                setMeasuredDimension(width, height)

                var frameHeight = height
                fragmentContentView.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(frameHeight, MeasureSpec.EXACTLY)
                )
                keyboardView?.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(
                        AndroidUtilities.dp(CustomPhoneKeyboardView.KEYBOARD_HEIGHT_DP.toFloat()),
                        MeasureSpec.EXACTLY
                    )
                )
            }
        }

        contentView.setDelegate { _: Int, _: Boolean -> }

        fragmentView = contentView
        contentView.addView(
            fragmentContentView,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f)
        )

//        actionBar.setTitle(msg.messageText)
        frameLayout.tag = Theme.key_windowBackgroundGray
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))

        val recyclerView = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply {
                setMargins(0, 0, 0, 0)
            }
        }
        recyclerView.layoutManager = LinearLayoutManager(context).apply {
            reverseLayout = true // Teskari tartibda ko'rsatadi
            stackFromEnd = false  // Elementlarni pastdan (yoki o'ngdan) joylashtirish
        }

        msgHistoryAdapter = MsgHistoryAdapter(EditMessage.getHistory(dialogId, msg.id))
        recyclerView.adapter = msgHistoryAdapter

        frameLayout.addView(
            recyclerView,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat())
        )
        // The menu item shows on every edited message, but only edits seen while edit-save was on are stored,
        // so an empty list is normal. Say so instead of leaving a blank screen.
        if (msgHistoryAdapter.itemCount == 0) {
            frameLayout.addView(
                emptyView(context),
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.CENTER, 32f, 0f, 32f, 0f)
            )
        }

        return fragmentView
    }

    private fun emptyView(context: Context): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(TextView(context).apply {
            text = LanguageCode.getMyTitles(414)              // No edit history yet
            textSize = 17f
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
        })
        addView(TextView(context).apply {
            text = LanguageCode.getMyTitles(415)              // …why: only edits made after edit-save was turned on are kept
            textSize = 14f
            gravity = Gravity.CENTER
            setLineSpacing(AndroidUtilities.dp(2f).toFloat(), 1f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = AndroidUtilities.dp(8f)
        })
    }

    private fun setUpActionBar() {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setAllowOverlayTitle(false)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) {
                    finishFragment()
                }
            }
        })

        avatarContainer = object : ChatAvatarContainer(
            context,
            ChatActivity.instance, false, ChatActivity.instance.themeDelegate
        ) {
            override fun useAnimatedSubtitle(): Boolean {
                return false
            }

            override fun canSearch(): Boolean {
                return false
            }

            override fun openSearch() {

            }
        }
        avatarContainer?.allowShorterStatus = true
        avatarContainer?.premiumIconHiddable = true
        avatarContainer?.allowDrawStories = false
        avatarContainer?.setClipChildren(false)
        AndroidUtilities.updateViewVisibilityAnimated(avatarContainer, true, 1f, false)

        avatarContainer?.updateSubtitle()

        if (chat != null) {
            avatarContainer?.setChatAvatar(chat)
            // The chat's own name; this used to be a hard-coded "Channel", even for groups.
            avatarContainer?.setTitle(AndroidUtilities.removeDiacritics(chat?.title ?: ""))
        } else if (user != null) {
            avatarContainer?.setUserAvatar(user)
            avatarContainer?.setTitle(AndroidUtilities.removeDiacritics(UserObject.getUserName(user)))
        }

        actionBar.addView(
            avatarContainer,
            0,
            LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT,
                LayoutHelper.MATCH_PARENT.toFloat(),
                Gravity.TOP or Gravity.LEFT,
                (if (!inPreviewMode) 56 else 0).toFloat(),
                0f,
                40f,
                0f
            )
        )
    }


    override fun hasForceLightStatusBar(): Boolean {
        return true
    }

    override fun onFragmentDestroy() {
        if (avatarContainer != null) {
            avatarContainer!!.onDestroy()
        }
        super.onFragmentDestroy()
    }
}