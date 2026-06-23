package org.fenixuz.ui.message_history

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
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

        return fragmentView
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
//            avatarContainer?.setTitle(AndroidUtilities.removeDiacritics(chat.title))
            avatarContainer?.setTitle("Channel")
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