package org.fenixuz.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MessageObject
import org.telegram.messenger.UserConfig

/**
 * Delete-save: the mode switch and the "deleted by" marks. The kept messages themselves are stored per account in
 * [HistoryDb]; this object maps between [By] and the stored codes and builds the marks' text.
 */
object DeletedMsg {

    const val SIMPLE = 0
    const val SECOND = 1
    const val FIRST = 2//----
    const val ALL = 3

    private val sharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences("db", Context.MODE_PRIVATE)
    private val editor = sharedPreferences.edit()

    /**
     * The mark of [msgId] in [dialogId] of the account in slot [account] ("" when it isn't kept). Never touches the
     * disk, so it is safe on the chat-scroll bind path: a chat's marks load in the background on first need and an
     * open chat refreshes its rows when they arrive (NotificationCenter.novagramHistoryWarmedUp).
     */
    fun whoDelete(account: Int, dialogId: Long, msgId: Int): String {
        val uid = UserConfig.getInstance(account).getClientUserId()
        return whoDeleteStr(byOf(HistoryDb.whoDeleted(uid, dialogId, msgId)))
    }

    /** Of [msgIds], those kept as deleted in [dialogId] of the account in slot [account], from the loaded marks. */
    fun sortDeletedIds(account: Int, dialogId: Long, msgIds: ArrayList<Int>): ArrayList<Int> =
        HistoryDb.keptInCache(UserConfig.getInstance(account).getClientUserId(), dialogId, msgIds)

    fun whoDeleteStr(by: By?): String {
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

    /** The stored code for [by]: fixed numbers, not the enum's ordinal, so reordering the enum can't change them. */
    @JvmStatic
    fun whoCode(by: By?): Int = when (by) {
        By.Me -> HistoryDb.WHO_ME
        By.Channel -> HistoryDb.WHO_CHANNEL
        else -> HistoryDb.WHO_YOU
    }

    fun byOfCode(code: Int): By? = byOf(code)

    private fun byOf(code: Int): By? = when (code) {
        HistoryDb.WHO_ME -> By.Me
        HistoryDb.WHO_YOU -> By.You
        HistoryDb.WHO_CHANNEL -> By.Channel
        else -> null
    }

    fun saveCheckType(type: Int) {
        editor.putInt("delete_check_key", type)
        editor.apply()
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

    /** The marks of the old store ("mark_delete" in the "db" preferences, no account), for [HistoryDb]'s migration. */
    fun parseLegacy(prefs: SharedPreferences): ArrayList<WhoDeletedMsg> {
        val out = ArrayList<WhoDeletedMsg>()
        val str = prefs.getString("mark_delete", "")
        if (!str.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<List<WhoDeletedMsg?>?>() {}.type
                Gson().fromJson<ArrayList<WhoDeletedMsg?>>(str, type)?.filterNotNullTo(out)
            } catch (_: Exception) {
            }
        }
        return out
    }
}

/** An old mark, as the old store kept it (Gson; the field names must stay, see proguard-rules.pro). */
data class WhoDeletedMsg(val dialogId: Long?, val id: Int?, val who: By?)

enum class By {
    Me,
    You,
    Channel
}
