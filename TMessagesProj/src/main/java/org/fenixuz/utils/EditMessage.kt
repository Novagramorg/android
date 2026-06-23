package org.fenixuz.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.telegram.tgnet.TLRPC
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MessageObject
import org.fenixuz.ui.message_history.MessageHistoryModule
import java.text.SimpleDateFormat
import java.util.*

object EditMessage {

    var editMode = false
    val gson = Gson()

    private var sharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences("db", Context.MODE_PRIVATE)
    private var editor = sharedPreferences.edit()

    var messages = ArrayList<MessageObject>()

    init {
        editMode = sharedPreferences.getBoolean("edit", false)
    }

    fun getEditModeFromCache(): Boolean {
        return sharedPreferences.getBoolean("edit", false)
    }

    fun changeEditMode(mode: Boolean) {
        editMode = mode
        editor.putBoolean("edit", editMode)
        editor.commit()
    }

    fun getAllEditedMessages(): ArrayList<MessageHistoryModule> {
        val msgs = ArrayList<MessageHistoryModule>()

        val str = sharedPreferences.getString("edited_messages", "")
        if (str !== "") {
            val type: TypeToken<*> = object : TypeToken<List<MessageHistoryModule?>?>() {
            }
            val fromJson = gson.fromJson<ArrayList<MessageHistoryModule>>(str, type.type)
            for (markId in fromJson) {
                msgs.add(markId)
            }
        }

        return msgs
    }

    fun formatDateTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val date = Date(timestamp)
        return sdf.format(date)
    }

    fun getHistory(dialogId: Long, messageId: Int): ArrayList<MessageHistoryModule> {
        val allEditedMessages = ArrayList<MessageHistoryModule>()
        allEditedMessages.addAll((getAllEditedMessages().filter { it.dialogId == dialogId && it.msgId == messageId }
            .sortedByDescending { it.editedTime ?: 0 }))
        return allEditedMessages
    }

    fun saveEditedMsg(oldMessage: TLRPC.Message?, dialogId: Long) {
        val allEditedMessages = getAllEditedMessages()
        var oldText = oldMessage?.message ?: ""
        for (message in messages) {
            val str = message.previousMessage
            if (message.id == oldMessage?.id ?: 0 && str != null) {
                oldText = str
                break
            }
        }
        val editedTime: Long
        val editDate = oldMessage?.edit_date ?: 0
        val firstDate = oldMessage?.date ?: 0
        editedTime = if (editDate != 0) {
            editDate.toLong()
        } else if (firstDate != 0) {
            firstDate.toLong()
        } else {
            Date().time / 1000
        }
        allEditedMessages.add(
            MessageHistoryModule(
                dialogId,
                oldMessage?.id,
                oldText,
                editedTime * 1000
            )
        )
        val str = gson.toJson(allEditedMessages)
        editor.putString("edited_messages", str)
        editor.commit()
    }
}