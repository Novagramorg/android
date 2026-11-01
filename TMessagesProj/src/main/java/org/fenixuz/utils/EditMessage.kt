package org.fenixuz.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.fenixuz.ui.message_history.MessageHistoryModule
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import java.text.SimpleDateFormat
import java.util.*

/** Edit-save: the switch and the earlier texts of edited messages, stored per account in [HistoryDb]. */
object EditMessage {

    var editMode = false

    private var sharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences("db", Context.MODE_PRIVATE)
    private var editor = sharedPreferences.edit()

    init {
        editMode = sharedPreferences.getBoolean("edit", false)
    }

    fun changeEditMode(mode: Boolean) {
        editMode = mode
        editor.putBoolean("edit", editMode)
        // editMode is served from the in-memory field, so the async write is safe.
        editor.apply()
    }

    fun formatDateTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val date = Date(timestamp)
        return sdf.format(date)
    }

    /** The earlier texts of [messageId] in [dialogId] of the account in slot [account], newest first. */
    fun getHistory(account: Int, dialogId: Long, messageId: Int): ArrayList<MessageHistoryModule> =
        HistoryDb.edits(UserConfig.getInstance(account).getClientUserId(), dialogId, messageId)

    /**
     * Called when Telegram stores [newMessage] over the [oldMessage] it had (storage-queue thread). That happens for
     * many reasons besides an edit: a link preview loaded, reactions, a bot's keyboard. Only a changed text is an
     * edit worth keeping; saving every re-store filled the history with copies of the same text.
     */
    fun saveEditedMsg(accountUid: Long, oldMessage: TLRPC.Message?, newMessage: TLRPC.Message?, dialogId: Long) {
        if (oldMessage == null || accountUid == 0L) {
            return
        }
        val oldText = oldMessage.message ?: ""
        if (newMessage != null && oldText == (newMessage.message ?: "")) {
            return
        }
        val editedTime = when {
            oldMessage.edit_date != 0 -> oldMessage.edit_date.toLong()
            oldMessage.date != 0 -> oldMessage.date.toLong()
            else -> Date().time / 1000
        }
        HistoryDb.addEdit(accountUid, dialogId, oldMessage.id, oldText, editedTime * 1000)
    }

    /** The old store ("edited_messages" in the "db" preferences, no account), for [HistoryDb]'s migration. */
    fun parseLegacy(prefs: SharedPreferences): ArrayList<MessageHistoryModule> {
        val out = ArrayList<MessageHistoryModule>()
        val str = prefs.getString("edited_messages", "")
        if (!str.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<List<MessageHistoryModule?>?>() {}.type
                Gson().fromJson<ArrayList<MessageHistoryModule?>>(str, type)?.filterNotNullTo(out)
            } catch (_: Exception) {
            }
        }
        return out
    }
}
