package org.fenixuz.ui.auto_answer

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.Emoji
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import kotlin.math.min

/**
 * Auto-answer: replies once to the first incoming message of each chat with a saved plain text.
 * The reply text is stored as plain text (no rich editor); see [AutoAnswerMenu].
 */
object AutoAnswer {

    private var sharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences("db", Context.MODE_PRIVATE)
    private var editor = sharedPreferences.edit()
    private val gson = Gson()

    fun processSendingText(dialog_id: Long, replyingMessageObject: MessageObject?) {
        if (!autoAnswerIsActive() || checkAnsweredDialogs(dialog_id)) return
        val msg = getAutoAnswerText()
        if (msg.isEmpty()) return
        saveAnsweredDialogIds(dialog_id)

        val notify = true
        val scheduleDate = 0
        val scheduleRepeatPeriod = 0
        val payStars = 0L

        val accountInstance = AccountInstance.getInstance(UserConfig.selectedAccount)
        var text: CharSequence = msg

        val emojiOnly = IntArray(1)
        Emoji.parseEmojis(text.toString(), emojiOnly)
        val hasOnlyEmoji = emojiOnly[0] > 0
        if (!hasOnlyEmoji) {
            text = AndroidUtilities.getTrimmedString(text)
        }
        val supportsNewEntities = true
        val maxLength: Int = accountInstance.messagesController.maxMessageLength
        var end: Int
        var start = 0
        do {
            var whitespaceIndex = -1
            var dotIndex = -1
            var tabIndex = -1
            var enterIndex = -1
            if (text.length > start + maxLength) {
                var i = start + maxLength - 1
                var k = 0
                while (i > start && k < 300) {
                    val c = text[i]
                    val c2 = if (i > 0) text[i - 1] else ' '
                    if (c == '\n' && c2 == '\n') {
                        tabIndex = i
                        break
                    } else if (c == '\n') {
                        enterIndex = i
                    } else if (dotIndex < 0 && Character.isWhitespace(c) && c2 == '.') {
                        dotIndex = i
                    } else if (whitespaceIndex < 0 && Character.isWhitespace(c)) {
                        whitespaceIndex = i
                    }
                    i--
                    k++
                }
            }
            end = min(start + maxLength, text.length)
            if (tabIndex > 0) {
                end = tabIndex
            } else if (enterIndex > 0) {
                end = enterIndex
            } else if (dotIndex > 0) {
                end = dotIndex
            } else if (whitespaceIndex > 0) {
                end = whitespaceIndex
            }

            var part = text.subSequence(start, end)
            if (!hasOnlyEmoji) {
                part = AndroidUtilities.getTrimmedString(part)
            }
            val message = arrayOf<CharSequence>(part)
            val entities: ArrayList<TLRPC.MessageEntity> =
                MediaDataController.getInstance(UserConfig.selectedAccount)
                    .getEntities(message, supportsNewEntities)

            val sendAnimationData = MessageObject.SendAnimationData()
            sendAnimationData.height = AndroidUtilities.dp(22f).toFloat()
            sendAnimationData.width = sendAnimationData.height

            val updateStickersOrder = SendMessagesHelper.checkUpdateStickersOrder(text)

            val params = SendMessagesHelper.SendMessageParams.of(
                message[0].toString(),
                dialog_id,
                replyingMessageObject,
                null,
                null,
                true,
                entities,
                null,
                null,
                notify,
                scheduleDate,
                scheduleRepeatPeriod,
                sendAnimationData,
                updateStickersOrder
            )
            SendMessagesHelper.getInstance(UserConfig.selectedAccount).sendMessage(params)
            start = end + 1
        } while (end != text.length)
    }

    fun saveAutoAnswerActive(active: Boolean) {
        if (!active) {
            clearAnsweredDialogIds()
        }
        editor.putBoolean("auto_answer_active", active)
        editor.commit()
    }

    fun autoAnswerIsActive(): Boolean {
        return sharedPreferences.getBoolean("auto_answer_active", false)
    }

    fun saveAutoAnswerText(text: String) {
        editor.putString("auto_answer_text", text)
        editor.commit()
    }

    fun getAutoAnswerText(): String {
        return sharedPreferences.getString("auto_answer_text", "") ?: ""
    }

    private fun saveAnsweredDialogIds(dialogId: Long) {
        val list = getAnsweredDialogIds()
        list.add(dialogId)
        editor.putString("answered_dialogs", gson.toJson(list))
        editor.commit()
    }

    private fun checkAnsweredDialogs(dialogId: Long): Boolean {
        return getAnsweredDialogIds().contains(dialogId)
    }

    fun removeAnsweredDialogId(dialogId: Long) {
        val list = getAnsweredDialogIds()
        if (list.contains(dialogId)) {
            list.remove(dialogId)
            editor.putString("answered_dialogs", gson.toJson(list))
            editor.commit()
        }
    }

    private fun clearAnsweredDialogIds() {
        editor.putString("answered_dialogs", "")
        editor.commit()
    }

    private fun getAnsweredDialogIds(): ArrayList<Long> {
        val list = ArrayList<Long>()
        try {
            val str = sharedPreferences.getString("answered_dialogs", "")
            if (str != "") {
                val type: TypeToken<*> = object : TypeToken<ArrayList<Long>>() {}
                val fromJson = gson.fromJson<ArrayList<Long>>(str, type.type)
                fromJson.forEach { list.add(it) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }
}
