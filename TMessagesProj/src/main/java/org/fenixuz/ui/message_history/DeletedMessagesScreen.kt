package org.fenixuz.ui.message_history

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fenixuz.utils.EditMessage
import org.fenixuz.utils.HistoryDb
import org.fenixuz.utils.LanguageCode
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.messenger.Utilities
import org.telegram.messenger.databinding.MsgItemBinding
import org.telegram.tgnet.SerializedData
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper

/**
 * Novagram: a chat's "Deleted messages", read from the saved history ([HistoryDb]): what others deleted while
 * delete-save was on, also the messages Telegram has since dropped from its cache, which no longer show in the chat.
 * Laid out like the edit history ([MessageHistory]). A tap offers copy and delete for good; the toolbar clears them all.
 */
class DeletedMessagesScreen(private val dialogId: Long) : BaseFragment() {

    /** [text] as shown (with the sender in groups), [plain] for copying, [date] in ms (0 when unknown). */
    private class Item(val msgId: Int, val text: CharSequence, val plain: String, val date: Long)

    private class Holder(val binding: MsgItemBinding) : RecyclerView.ViewHolder(binding.root)

    private val CLEAR = 1

    private val items = ArrayList<Item>()
    private var adapter: RecyclerView.Adapter<Holder>? = null
    private var emptyView: View? = null

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setAllowOverlayTitle(true)
        actionBar.setTitle(LanguageCode.getMyTitles(516))
        actionBar.setSubtitle(chatName())
        actionBar.createMenu().addItem(CLEAR, R.drawable.msg_delete)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) {
                    finishFragment()
                } else if (id == CLEAR) {
                    confirmClear()
                }
            }
        })

        val frame = FrameLayout(context)
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
        val list = RecyclerView(context)
        // Newest at the bottom, as in a chat.
        list.layoutManager = LinearLayoutManager(context).apply { reverseLayout = true }
        val listAdapter = object : RecyclerView.Adapter<Holder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                Holder(MsgItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

            override fun onBindViewHolder(holder: Holder, position: Int) {
                val item = items[position]
                holder.binding.msgText.text = item.text
                holder.binding.editedTime.text = if (item.date > 0) EditMessage.formatDateTime(item.date) else ""
                holder.binding.msg.setOnClickListener { showOptions(item) }
            }

            override fun getItemCount() = items.size
        }
        adapter = listAdapter
        list.adapter = listAdapter
        frame.addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        emptyView = emptyView(context).also {
            it.visibility = View.GONE
            frame.addView(it, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.CENTER, 32f, 0f, 32f, 0f))
        }
        fragmentView = frame
        load()
        return frame
    }

    private fun chatName(): String {
        val controller = MessagesController.getInstance(currentAccount)
        return when {
            DialogObject.isUserDialog(dialogId) -> controller.getUser(dialogId)?.let { UserObject.getUserName(it) } ?: ""
            DialogObject.isChatDialog(dialogId) -> controller.getChat(-dialogId)?.title ?: ""
            else -> ""
        }
    }

    /** Reads the chat's saved copies off the UI thread: deserializing them is the slow part. */
    private fun load() {
        val account = currentAccount
        val uid = UserConfig.getInstance(account).getClientUserId()
        Utilities.globalQueue.postRunnable {
            val loaded = HistoryDb.deletedIn(uid, dialogId).map { toItem(account, uid, it) }
            AndroidUtilities.runOnUIThread {
                items.clear()
                items.addAll(loaded)
                adapter?.notifyDataSetChanged()
                updateEmpty()
            }
        }
    }

    private fun toItem(account: Int, uid: Long, stored: HistoryDb.Stored): Item {
        val message = stored.data?.let { bytes ->
            try {
                val stream = SerializedData(bytes)
                TLRPC.Message.TLdeserialize(stream, stream.readInt32(false), false)?.also { it.readAttachPath(stream, uid) }
            } catch (e: Exception) {
                FileLog.e(e)
                null
            }
        } ?: return Item(stored.msgId, "—", "", 0)

        // A media message shows its kind ("Photo", "Voice message"…, as Telegram names it in the chat list) and its
        // caption; the file itself is usually gone with the message.
        val messageObject = MessageObject(account, message, false, false)
        val caption = message.message ?: ""
        val media = message.media
        val hasMedia = media != null && media !is TLRPC.TL_messageMediaEmpty && media !is TLRPC.TL_messageMediaWebPage
        val plain = when {
            message is TLRPC.TL_messageService -> messageObject.messageText?.toString() ?: ""
            hasMedia -> listOf(messageObject.messageText?.toString() ?: "", caption).filter { it.isNotEmpty() }.joinToString("\n")
            else -> caption
        }

        val text = SpannableStringBuilder()
        // In groups and channels, who wrote it.
        val sender = if (DialogObject.isChatDialog(dialogId)) senderName(account, message) else null
        if (!sender.isNullOrEmpty()) {
            text.append(sender).append('\n')
            text.setSpan(StyleSpan(Typeface.BOLD), 0, sender.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text.append(plain)
        return Item(stored.msgId, text, plain, message.date * 1000L)
    }

    private fun senderName(account: Int, message: TLRPC.Message): String? {
        val from = message.from_id ?: return null
        val controller = MessagesController.getInstance(account)
        return when {
            from.user_id != 0L -> controller.getUser(from.user_id)?.let { UserObject.getUserName(it) }
            from.channel_id != 0L -> controller.getChat(from.channel_id)?.title
            from.chat_id != 0L -> controller.getChat(from.chat_id)?.title
            else -> null
        }
    }

    private fun updateEmpty() {
        emptyView?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showOptions(item: Item) {
        val context = parentActivity ?: return
        val dialog = AlertDialog.Builder(context, resourceProvider)
            .setItems(arrayOf<CharSequence>(LocaleController.getString(R.string.Copy), LocaleController.getString(R.string.Delete))) { _, which ->
                if (which == 0) {
                    AndroidUtilities.addToClipboard(item.plain)
                    BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show()
                } else {
                    confirmDelete(item)
                }
            }
            .create()
        showDialog(dialog)
    }

    private fun confirmDelete(item: Item) {
        confirm(LocaleController.getString(R.string.Delete), LanguageCode.getMyTitles(522), LocaleController.getString(R.string.Delete)) {
            HistoryDb.forget(currentAccount, dialogId, listOf(item.msgId)) {
                items.remove(item)
                adapter?.notifyDataSetChanged()
                updateEmpty()
            }
        }
    }

    private fun confirmClear() {
        if (items.isEmpty()) return
        confirm(LanguageCode.getMyTitles(520), LanguageCode.getMyTitles(521), LanguageCode.getMyTitles(514)) {
            HistoryDb.forget(currentAccount, dialogId, items.map { it.msgId }) {
                items.clear()
                adapter?.notifyDataSetChanged()
                updateEmpty()
            }
        }
    }

    /** These can't be undone: a red button after a question. */
    private fun confirm(title: String, message: String, button: String, onYes: () -> Unit) {
        val context = parentActivity ?: return
        val dialog = AlertDialog.Builder(context, resourceProvider)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(button) { _, _ -> onYes() }
            .create()
        showDialog(dialog)
        (dialog.getButton(AlertDialog.BUTTON_POSITIVE) as? TextView)
            ?.setTextColor(Theme.getColor(Theme.key_text_RedBold, resourceProvider))
    }

    private fun emptyView(context: Context): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(TextView(context).apply {
            text = LanguageCode.getMyTitles(518)
            textSize = 17f
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
        })
        addView(TextView(context).apply {
            text = LanguageCode.getMyTitles(519)
            textSize = 14f
            gravity = Gravity.CENTER
            setLineSpacing(AndroidUtilities.dp(2f).toFloat(), 1f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = AndroidUtilities.dp(8f)
        })
    }
}
