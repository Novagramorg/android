package org.fenixuz.utils

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.widget.TextView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme

object DeletedMsg {
    private const val TAG = "DeletedMsg"

    const val SIMPLE = 0
    const val SECOND = 1
    const val FIRST = 2//----
    const val ALL = 3
    const val DELETE_MARK = "<-------------->"

    private val sharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences("db", Context.MODE_PRIVATE)
    private val editor = sharedPreferences.edit()
    private val gson = Gson()
    var myDelete = false

    var list = ArrayList<MessageObject>()

    fun clearCacheDialog(parentActivity: Activity, allCache: Boolean, callback: (Unit?) -> Unit) {
        val subTitle: String = if (allCache) {
            LanguageCode.getMyTitles(106)
        } else {
            LanguageCode.getMyTitles(107)
        }
        val alertDialog =
            AlertDialog.Builder(parentActivity)
                .setTitle(LanguageCode.getMyTitles(105))
                .setMessage(subTitle)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LanguageCode.getMyTitles(105)) { dialog: DialogInterface?, which: Int ->
                    callback(null)
                }.create()
        alertDialog.show()
        (alertDialog.getButton(Dialog.BUTTON_POSITIVE) as TextView).setTextColor(
            Theme.getColor(Theme.key_text_RedBold)
        )
    }

    fun clearChatCache(currentAccount: Int, dialogId: Long, isChannel: Boolean) {
        val msgids = getDeletedMessagesByDialogId(dialogId)
        clearCache(currentAccount, dialogId, msgids, isChannel)
    }

    fun clearAllCache(currentAccount: Int) {
        val allIds = getAllIds()
        val resultMap = mutableMapOf<Long?, ArrayList<Int>>()
        try {
            for (allId in allIds) {
                if (allId.dialogId != null && allId.id != null) {
                    val idList = resultMap.getOrPut(allId.dialogId) { ArrayList() }
                    idList.add(allId.id)
                }
            }

            resultMap.forEach { (k, v) ->
                clearCache(currentAccount, k, v, false)
            }

        } catch (_: Exception) {

        }
    }

    private fun clearCache(
        currentAccount: Int,
        dialogId: Long?,
        messageIds: ArrayList<Int>,
        isChannel: Boolean
    ) {
        try {
            if (dialogId != null) {
                val messagesStorage = MessagesStorage.getInstance(currentAccount)
                messagesStorage.markMessagesAsDeleted(
                    dialogId,
                    messageIds,
                    true,
                    true,
                    0,
                    0,
                    true,
                    By.Me
                )
                messagesStorage.updateDialogsWithDeletedMessages(
                    dialogId,
                    if (isChannel) dialogId else 0,
                    messageIds,
                    null,
                    true
                )
                deleteFromSharedPrefByDialogId(dialogId)
            }
        } catch (e: Exception) {

        }
    }

    private fun deleteFromSharedPrefByDialogId(dialogId: Long) {
        val allIds = getAllIds()
        val iterator = allIds.iterator()

        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.dialogId == dialogId) {
                iterator.remove()
            }
        }

        saveDeletedMessagesId(allIds)
    }

    private fun getDeletedMessagesByDialogId(dialogId: Long): ArrayList<Int> {
        var msgs = ArrayList<Int>()

        var allIds = getAllIds()
        for (i in 0 until allIds.size) {
            if (allIds[i].dialogId == dialogId) {
                msgs.add(allIds[i].id ?: -1)
            }
        }

        return msgs
    }

    fun sortDeletedIds(dialogId: Long, msgIds: ArrayList<Int>): ArrayList<Int> {
        val haveList = ArrayList<Int>()
        val oldMsgsIds = getAllIds()

        msgIds.forEach { m ->
            if (oldMsgsIds.any { it.id == m && it.dialogId == dialogId }) {
                haveList.add(m)
            }
        }

        return haveList
    }

    fun whoDelete(dialogId: Long, msgId: Int): String {
        val whoDeletedMsgs = getAllIds()
        var resultStr = ""
        for (i in 0 until whoDeletedMsgs.size) {
            val whoDeletedMsg = whoDeletedMsgs[i]
            if (whoDeletedMsg.dialogId == dialogId && whoDeletedMsg.id == msgId) {
                resultStr =
                    when (whoDeletedMsg.who) {
                        By.Me -> {
                            LanguageCode.getMyTitles(109)
                        }

                        By.You -> {
                            LanguageCode.getMyTitles(110)
                        }

                        By.Channel -> {
                            LanguageCode.getMyTitles(111)
                        }

                        null -> ""
                    }
                break
            }
        }

        return resultStr
    }

    fun whoDeleteStr(by: By?): String{
        return when (by) {
            By.Me -> {
                LanguageCode.getMyTitles(109)
            }

            By.You -> {
                LanguageCode.getMyTitles(110)
            }

            By.Channel -> {
                LanguageCode.getMyTitles(111)
            }

            null -> ""
        }
    }

    fun getAllIds(): ArrayList<WhoDeletedMsg> {
        val markMessages = ArrayList<WhoDeletedMsg>()

        val gson = Gson()
        val str = sharedPreferences.getString("mark_delete", "")
        if (str !== "") {
            val type: TypeToken<*> = object : TypeToken<List<WhoDeletedMsg?>?>() {
            }
            val fromJson = gson.fromJson<java.util.ArrayList<WhoDeletedMsg>>(str, type.type)
            for (markId in fromJson) {
                markMessages.add(markId)
            }
        }

        return markMessages
    }

    fun saveDeletedMessagesId(messageIds: ArrayList<WhoDeletedMsg>) {
        val str = gson.toJson(messageIds)
        editor.putString("mark_delete", str)
        editor.commit()
    }

    fun saveCheckType(type: Int) {
        editor.putInt("delete_check_key", type)
        editor.commit()
    }

    fun getCheckType(): Int {
        return sharedPreferences.getInt("delete_check_key", SIMPLE)
    }

    fun notify(ids: ArrayList<Int>, messages: ArrayList<MessageObject>, dialogId: Long?): ArrayList<MessageObject> {
        val notifyMessages = ArrayList<MessageObject>()
        for (i in 0 until messages.size) {
            for (j in 0 until ids.size) {
                if (messages[i].messageOwner.id == ids[j] && messages[i].messageOwner.dialog_id == dialogId) {
                    notifyMessages.add(messages[i])
                }
            }
        }
        return notifyMessages
    }
}

data class WhoDeletedMsg(val dialogId: Long?, val id: Int?, val who: By?)

enum class By {
    Me,
    You,
    Channel
}