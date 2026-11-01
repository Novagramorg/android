package org.fenixuz.utils

import androidx.collection.LongSparseArray
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessageObject
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.SerializedData
import org.telegram.tgnet.TLRPC

/**
 * Delete-save in the chat itself: a kept message shows in its place even when Telegram no longer has it, because its
 * cache was wiped or because this part of the chat came from the server (which leaves deleted messages out). As in
 * AyuGram, but the messages join the page as it is loaded, before the chat sees it, instead of arriving as "new"
 * messages: they get the page's date headers and grouping and none of the new-message side effects.
 *
 * Each page covers the ids from its oldest message to its newest, plus the gap up to the page loaded before it (the
 * request's max_id), plus everything newer when it is the newest page. So consecutive pages cover the chat without gaps
 * and without overlap; a message already in the page (Telegram still has it) is not added again.
 */
object KeptInChat {

    /** Kept messages added to one page at most (newest first). */
    private const val LIMIT = 300

    /**
     * Called from MessagesController.processLoadedMessages (background thread) for an ordinary chat, after Telegram
     * stored the page and built [objects] from [page] (newest first). Adds the kept messages in range to [objects].
     */
    @JvmStatic
    fun addTo(
        account: Int, dialogId: Long, page: List<TLRPC.Message>, objects: ArrayList<MessageObject>, loadType: Int,
        count: Int, maxId: Int, offsetDate: Int, lastMessageId: Int, isEnd: Boolean, isCache: Boolean,
        users: LongSparseArray<TLRPC.User>, chats: LongSparseArray<TLRPC.Chat>
    ) {
        if (DeletedMsg.getCheckType() == DeletedMsg.SIMPLE) return
        val uid = UserConfig.getInstance(account).getClientUserId()
        if (uid == 0L) return

        val present = HashSet<Int>()
        var oldest = Int.MAX_VALUE
        var newest = 0
        for (m in page) {
            if (m.id <= 0) continue
            present.add(m.id)
            if (m.id < oldest) oldest = m.id
            if (m.id > newest) newest = m.id
        }
        // Without a message of its own the page gives nothing to place kept messages against.
        if (present.isEmpty()) return

        var low = oldest.toLong()
        var high = newest.toLong()
        when (loadType) {
            // Older than the page already shown: up to where that page began.
            0 -> if (maxId > 0) high = maxId.toLong()
            // Newer than the page already shown: from where that page ended.
            1 -> if (maxId > 0) low = maxId.toLong()
        }
        // A server page shorter than asked for reached the end in its direction (the server passes no isEnd here); a
        // cache page can be short because of a gap in the cache, so only its own isEnd counts.
        val reachedEnd = if (isCache) isEnd else page.size < count
        // lastMessageId is the newest message Telegram has for the chat (cache loads), so a page holding it is the
        // newest page.
        val newestPage = (loadType == 0 && maxId == 0 && offsetDate == 0) || (loadType == 1 && reachedEnd) ||
            (lastMessageId in 1..newest)
        if (newestPage) high = Int.MAX_VALUE.toLong()
        // The very beginning of the chat, as the server says: also what was deleted before its first message.
        if (loadType == 0 && maxId > 0 && !isCache && reachedEnd) low = 0

        val kept = HistoryDb.deletedBetween(uid, dialogId, low, high, LIMIT)
        for (stored in kept) {
            if (stored.msgId in present) continue
            val messageObject = toMessageObject(account, uid, dialogId, stored, users, chats) ?: continue
            insertNewestFirst(objects, messageObject)
            present.add(stored.msgId)
        }
    }

    private fun toMessageObject(
        account: Int, uid: Long, dialogId: Long, stored: HistoryDb.Stored,
        users: LongSparseArray<TLRPC.User>, chats: LongSparseArray<TLRPC.Chat>
    ): MessageObject? {
        val bytes = stored.data ?: return null
        return try {
            val stream = SerializedData(bytes)
            val message = TLRPC.Message.TLdeserialize(stream, stream.readInt32(false), false) ?: return null
            message.readAttachPath(stream, uid)
            message.dialog_id = dialogId
            // Never counted as a new or unread message: it was read before it was deleted, or never will be.
            message.unread = false
            MessageObject(account, message, users, chats, true, false, false).also {
                it.deletedBy = DeletedMsg.whoDeleteStr(DeletedMsg.byOfCode(stored.who))
            }
        } catch (e: Exception) {
            FileLog.e(e)
            null
        }
    }

    /**
     * [objects] is newest first, with messages still being sent (negative ids) at the very start: [m] goes before the
     * first sent message older than it.
     */
    private fun insertNewestFirst(objects: ArrayList<MessageObject>, m: MessageObject) {
        val id = m.getId()
        for (i in objects.indices) {
            val other = objects[i].getId()
            if (other in 1 until id) {
                objects.add(i, m)
                return
            }
        }
        objects.add(m)
    }
}
