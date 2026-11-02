package org.fenixuz.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.fenixuz.ui.message_history.MessageHistoryModule
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.SerializedData
import org.telegram.tgnet.TLRPC
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Novagram's own store for kept deleted messages and edit history: `novagram_history.db`, apart from Telegram's
 * per-account cache, which Telegram wipes on a "difference too long", a channel reset or Clear cache.
 *
 * Every row carries its account (the account's Telegram user id, which stays when accounts change slots): in private
 * chats, basic groups and bots message ids are counted per account, so the old marks, which had no account, showed
 * "Deleted" on another account's message with the same id. A kept message is also saved whole (Telegram's own row
 * bytes), so it outlives Telegram's cache.
 *
 * Writes come from the accounts' storage queues; reads for the chat list never touch the disk on the UI thread: a
 * chat's marks load in the background on first need, then [NotificationCenter.novagramHistoryWarmedUp] is posted.
 */
object HistoryDb {

    const val WHO_ME = 0
    const val WHO_YOU = 1
    const val WHO_CHANNEL = 2

    private const val NAME = "novagram_history.db"
    private const val VERSION = 1

    /** How long the one-time migration waits for an account's storage queue or a server answer. */
    private const val WAIT_SECONDS = 60L

    /** A message to keep as deleted: [data] is Telegram's stored row for it (null when it couldn't be read). */
    class Kept(val dialogId: Long, val msgId: Int, val who: Int, val data: ByteArray?)

    /** A kept message of one chat with its saved copy: for the chat itself and its "Deleted messages" list. */
    class Stored(val msgId: Int, val who: Int, val data: ByteArray?)

    private class Helper(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE deleted_messages(account_uid INTEGER NOT NULL, dialog_id INTEGER NOT NULL, " +
                    "msg_id INTEGER NOT NULL, who INTEGER NOT NULL, deleted_at INTEGER NOT NULL, data BLOB, " +
                    "PRIMARY KEY(account_uid, dialog_id, msg_id))"
            )
            db.execSQL("CREATE INDEX deleted_messages_time ON deleted_messages(deleted_at)")
            db.execSQL(
                "CREATE TABLE edit_history(account_uid INTEGER NOT NULL, dialog_id INTEGER NOT NULL, " +
                    "msg_id INTEGER NOT NULL, text TEXT, edited_at INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX edit_history_msg ON edit_history(account_uid, dialog_id, msg_id)")
            // Old marks of private chats and basic groups (ids counted per account): which account really lost the
            // message is asked from the server.
            db.execSQL(
                "CREATE TABLE pending_marks(dialog_id INTEGER NOT NULL, msg_id INTEGER NOT NULL, who INTEGER NOT NULL, " +
                    "PRIMARY KEY(dialog_id, msg_id))"
            )
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private val helper by lazy { Helper(ApplicationLoader.applicationContext) }
    private val db: SQLiteDatabase get() = helper.writableDatabase

    /** Cache loads and cache updates, one at a time, so a load can never miss a mark written meanwhile. */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "novagram-history").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    private data class Key(val uid: Long, val dialogId: Long)

    /** (account, chat) → message id → who deleted it; the chats opened most recently. Guarded by itself. */
    private val marks = object : LinkedHashMap<Key, HashMap<Int, Int>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, HashMap<Int, Int>>?) = size > 64
    }
    private val loading = HashSet<Key>()

    /** False until the old stores are migrated: marks read before that would miss the migrated ones. */
    @Volatile
    private var ready = false

    // region deleted messages

    /**
     * Who deleted [msgId] in the account [uid] (a WHO_ code), or -1 when it isn't kept or the chat's marks aren't
     * loaded yet. Never touches the disk, so it is safe on the UI thread; a miss starts the background load.
     */
    fun whoDeleted(uid: Long, dialogId: Long, msgId: Int): Int {
        if (uid == 0L) return -1
        val key = Key(uid, dialogId)
        synchronized(marks) {
            val chat = marks[key]
            if (chat != null) return chat[msgId] ?: -1
            if (!ready || !loading.add(key)) return -1
        }
        io.execute { load(key) }
        return -1
    }

    /** Of [msgIds], those kept as deleted in the account [uid], from the loaded marks only (UI thread). */
    fun keptInCache(uid: Long, dialogId: Long, msgIds: List<Int>): ArrayList<Int> {
        val out = ArrayList<Int>()
        synchronized(marks) {
            val chat = marks[Key(uid, dialogId)] ?: return out
            msgIds.filterTo(out) { chat.containsKey(it) }
        }
        return out
    }

    private fun load(key: Key) {
        val chat = HashMap<Int, Int>()
        try {
            db.rawQuery(
                "SELECT msg_id, who FROM deleted_messages WHERE account_uid = ? AND dialog_id = ?",
                arrayOf(key.uid.toString(), key.dialogId.toString())
            ).use { c ->
                while (c.moveToNext()) chat[c.getInt(0)] = c.getInt(1)
            }
        } catch (e: Exception) {
            FileLog.e(e)
        }
        synchronized(marks) {
            marks[key] = chat
            loading.remove(key)
        }
        if (chat.isNotEmpty()) {
            AndroidUtilities.runOnUIThread {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.novagramHistoryWarmedUp)
            }
        }
    }

    /** Keeps [rows] as deleted in the account [uid]; a newer mark replaces an older one. Storage-queue thread. */
    fun addDeleted(uid: Long, rows: List<Kept>) {
        if (uid == 0L || rows.isEmpty()) return
        if (!insertKept(uid, rows, replace = true)) return
        io.execute {
            synchronized(marks) {
                for (r in rows) marks[Key(uid, r.dialogId)]?.put(r.msgId, r.who)
            }
        }
    }

    /** Of [rows], those kept as deleted in the account [uid]. Storage-queue thread. */
    fun keptAmong(uid: Long, rows: List<Kept>): ArrayList<Kept> {
        val out = ArrayList<Kept>()
        if (uid == 0L) return out
        try {
            for ((dialogId, inChat) in rows.groupBy { it.dialogId }) {
                for (chunk in inChat.chunked(500)) {
                    val kept = HashSet<Int>()
                    db.rawQuery(
                        "SELECT msg_id FROM deleted_messages WHERE account_uid = ? AND dialog_id = ? AND msg_id IN (" +
                            chunk.joinToString(",") { it.msgId.toString() } + ")",
                        arrayOf(uid.toString(), dialogId.toString())
                    ).use { c ->
                        while (c.moveToNext()) kept.add(c.getInt(0))
                    }
                    chunk.filterTo(out) { it.msgId in kept }
                }
            }
        } catch (e: Exception) {
            FileLog.e(e)
        }
        return out
    }

    /**
     * The kept messages of [dialogId] in the account [uid], newest first, with their saved copies: also those Telegram
     * has since dropped from its cache. Off the UI thread.
     */
    fun deletedIn(uid: Long, dialogId: Long): ArrayList<Stored> {
        val out = ArrayList<Stored>()
        if (uid == 0L) return out
        try {
            db.rawQuery(
                "SELECT msg_id, who, data FROM deleted_messages WHERE account_uid = ? AND dialog_id = ? ORDER BY msg_id DESC",
                arrayOf(uid.toString(), dialogId.toString())
            ).use { c ->
                while (c.moveToNext()) out.add(Stored(c.getInt(0), c.getInt(1), if (c.isNull(2)) null else c.getBlob(2)))
            }
        } catch (e: Exception) {
            FileLog.e(e)
        }
        return out
    }

    /**
     * Kept messages of [dialogId] in the account [uid] with ids strictly between [low] and [high], newest first, at most
     * [limit]: those a loaded page of the chat covers. Off the UI thread.
     */
    fun deletedBetween(uid: Long, dialogId: Long, low: Long, high: Long, limit: Int): ArrayList<Stored> {
        val out = ArrayList<Stored>()
        if (uid == 0L) return out
        try {
            db.rawQuery(
                "SELECT msg_id, who, data FROM deleted_messages WHERE account_uid = ? AND dialog_id = ? AND msg_id > ? " +
                    "AND msg_id < ? AND data IS NOT NULL ORDER BY msg_id DESC LIMIT $limit",
                arrayOf(uid.toString(), dialogId.toString(), low.toString(), high.toString())
            ).use { c ->
                while (c.moveToNext()) out.add(Stored(c.getInt(0), c.getInt(1), c.getBlob(2)))
            }
        } catch (e: Exception) {
            FileLog.e(e)
        }
        return out
    }

    /** How many messages of [dialogId] the account [uid] keeps as deleted. Off the UI thread. */
    fun countIn(uid: Long, dialogId: Long): Int {
        if (uid == 0L) return 0
        return try {
            db.rawQuery(
                "SELECT COUNT(*) FROM deleted_messages WHERE account_uid = ? AND dialog_id = ?",
                arrayOf(uid.toString(), dialogId.toString())
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        } catch (e: Exception) {
            FileLog.e(e)
            0
        }
    }

    /**
     * Removes kept [msgIds] of [dialogId] for good, in account slot [slot]: from the chat (this phone only, nothing is
     * sent) and from the saved history, also when Telegram's cache no longer has them. UI thread; [onDone] runs on it.
     */
    fun forget(slot: Int, dialogId: Long, msgIds: List<Int>, onDone: Runnable) {
        val uid = UserConfig.getInstance(slot).getClientUserId()
        dropFromChat(slot, dialogId, msgIds)
        afterStorage(listOf(slot)) {
            removeDeleted(uid, msgIds.map { Kept(dialogId, it, WHO_YOU, null) })
            AndroidUtilities.runOnUIThread(onDone)
        }
    }

    /** Deletes [msgIds] of [dialogId] from [slot]'s cache and open chats, as the user's own local delete. UI thread. */
    private fun dropFromChat(slot: Int, dialogId: Long, msgIds: List<Int>) {
        val controller = MessagesController.getInstance(slot)
        for (chunk in msgIds.chunked(100)) {
            controller.deleteMessages(ArrayList(chunk), null, null, dialogId, 0, false, 0, true)
        }
    }

    /**
     * Runs [block] on [io] once the storage queues of [slots] are through with what was posted to them so far: the
     * deletes [dropFromChat] queued there. In the "all" mode storage keeps a message the user deletes unless it finds it
     * kept already, so the saved copy may only go after storage looked; gone earlier, the message was kept again.
     */
    private fun afterStorage(slots: Collection<Int>, block: () -> Unit) {
        if (slots.isEmpty()) {
            io.execute(block)
            return
        }
        val left = AtomicInteger(slots.size)
        for (slot in slots) {
            MessagesStorage.getInstance(slot).storageQueue.postRunnable {
                if (left.decrementAndGet() == 0) io.execute(block)
            }
        }
    }

    /**
     * The user deleted the messages of [dialogId] dated [minDate]..[maxDate] (seconds, both included) in the account
     * [uid]: forgets the kept ones Telegram's cache no longer has (those in [cached] go through storage's own delete,
     * which must find them still kept). Returns their ids, for the open chat. Storage-queue thread.
     */
    fun forgetInDates(uid: Long, dialogId: Long, minDate: Int, maxDate: Int, cached: Collection<Int>): ArrayList<Int> {
        val gone = ArrayList<Int>()
        if (uid == 0L) return gone
        val skip = HashSet(cached)
        try {
            db.rawQuery(
                "SELECT msg_id, data FROM deleted_messages WHERE account_uid = ? AND dialog_id = ?",
                arrayOf(uid.toString(), dialogId.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val msgId = c.getInt(0)
                    if (msgId in skip) continue
                    val stream = SerializedData(c.getBlob(1) ?: continue)
                    val date = try {
                        TLRPC.Message.TLdeserialize(stream, stream.readInt32(false), false)?.date
                    } finally {
                        stream.cleanup()
                    } ?: continue
                    if (date in minDate..maxDate) gone.add(msgId)
                }
            }
        } catch (e: Exception) {
            FileLog.e(e)
            return ArrayList()
        }
        removeDeleted(uid, gone.map { Kept(dialogId, it, WHO_YOU, null) })
        return gone
    }

    /**
     * Whether [data] (a stored message) is the "History cleared" placeholder Telegram puts in place of a chat's last
     * message when the user clears the chat (MessagesController.deleteDialog). Storage thread.
     */
    @JvmStatic
    fun isClearedPlaceholder(data: ByteArray?): Boolean {
        if (data == null || data.size < 4) return false
        val stream = SerializedData(data)
        return try {
            val message = TLRPC.Message.TLdeserialize(stream, stream.readInt32(false), false)
            message is TLRPC.TL_messageService && message.action is TLRPC.TL_messageActionHistoryClear
        } catch (e: Exception) {
            false
        } finally {
            stream.cleanup()
        }
    }

    /** Forgets [rows] in the account [uid]: the user deleted them themselves, which removes them for good. */
    fun removeDeleted(uid: Long, rows: List<Kept>) {
        if (uid == 0L || rows.isEmpty()) return
        try {
            val d = db
            d.beginTransaction()
            try {
                for ((dialogId, inChat) in rows.groupBy { it.dialogId }) {
                    for (chunk in inChat.chunked(500)) {
                        d.execSQL(
                            "DELETE FROM deleted_messages WHERE account_uid = ? AND dialog_id = ? AND msg_id IN (" +
                                chunk.joinToString(",") { it.msgId.toString() } + ")",
                            arrayOf<Any>(uid, dialogId)
                        )
                    }
                }
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        } catch (e: Exception) {
            FileLog.e(e)
            return
        }
        io.execute {
            synchronized(marks) {
                for (r in rows) marks[Key(uid, r.dialogId)]?.remove(r.msgId)
            }
        }
    }

    private fun insertKept(uid: Long, rows: List<Kept>, replace: Boolean): Boolean {
        val now = System.currentTimeMillis()
        try {
            val d = db
            d.beginTransaction()
            try {
                val st = d.compileStatement(
                    "INSERT OR " + (if (replace) "REPLACE" else "IGNORE") +
                        " INTO deleted_messages(account_uid, dialog_id, msg_id, who, deleted_at, data) VALUES(?, ?, ?, ?, ?, ?)"
                )
                for (r in rows) {
                    st.clearBindings()
                    st.bindLong(1, uid)
                    st.bindLong(2, r.dialogId)
                    st.bindLong(3, r.msgId.toLong())
                    st.bindLong(4, r.who.toLong())
                    st.bindLong(5, now)
                    if (r.data != null) st.bindBlob(6, r.data) else st.bindNull(6)
                    st.executeInsert()
                }
                st.close()
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
            return true
        } catch (e: Exception) {
            FileLog.e(e)
            return false
        }
    }

    // endregion

    // region edit history

    /** Stores the text [msgId] had before an edit. Storage-queue thread. */
    fun addEdit(uid: Long, dialogId: Long, msgId: Int, text: String, editedAtMs: Long) {
        try {
            db.execSQL(
                "INSERT INTO edit_history(account_uid, dialog_id, msg_id, text, edited_at) VALUES(?, ?, ?, ?, ?)",
                arrayOf<Any>(uid, dialogId, msgId, text, editedAtMs)
            )
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    /** The earlier texts of [msgId] in the account [uid], newest first. */
    fun edits(uid: Long, dialogId: Long, msgId: Int): ArrayList<MessageHistoryModule> {
        val out = ArrayList<MessageHistoryModule>()
        if (uid == 0L) return out
        try {
            db.rawQuery(
                "SELECT text, edited_at FROM edit_history WHERE account_uid = ? AND dialog_id = ? AND msg_id = ? " +
                    "ORDER BY edited_at DESC",
                arrayOf(uid.toString(), dialogId.toString(), msgId.toString())
            ).use { c ->
                while (c.moveToNext()) out.add(MessageHistoryModule(dialogId, msgId, c.getString(0), c.getLong(1)))
            }
        } catch (e: Exception) {
            FileLog.e(e)
        }
        return out
    }

    // endregion

    /** The account [uid] logged out: its kept messages and edit history must not stay on the phone after it. */
    fun deleteAccount(uid: Long) {
        if (uid == 0L) return
        io.execute {
            try {
                val args = arrayOf<Any>(uid)
                db.execSQL("DELETE FROM deleted_messages WHERE account_uid = ?", args)
                db.execSQL("DELETE FROM edit_history WHERE account_uid = ?", args)
            } catch (e: Exception) {
                FileLog.e(e)
            }
            synchronized(marks) {
                marks.keys.removeAll { it.uid == uid }
            }
        }
    }

    // region size and "Clear saved history"

    /** Per row, roughly what SQLite keeps besides the row's own bytes (keys, indexes, page space). */
    private const val ROW_OVERHEAD = 48

    /**
     * About how much space the saved history of the account [uid] takes on the phone: its rows' bytes plus a rough
     * per-row overhead (the file is shared by all accounts, so its size would show the others' history too, a hidden
     * account's included). There is no automatic limit, as in AyuGram: dropping old copies silently would break the
     * promise that a deleted message stays; the user sees the size and clears it. Background thread.
     */
    fun sizeOf(uid: Long): Long {
        if (uid == 0L) return 0L
        return try {
            val args = arrayOf(uid.toString())
            val kept = db.rawQuery(
                "SELECT COALESCE(SUM(LENGTH(data)), 0) + COUNT(*) * $ROW_OVERHEAD FROM deleted_messages WHERE account_uid = ?", args
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            val edits = db.rawQuery(
                "SELECT COALESCE(SUM(LENGTH(CAST(text AS BLOB))), 0) + COUNT(*) * $ROW_OVERHEAD FROM edit_history WHERE account_uid = ?", args
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            kept + edits
        } catch (e: Exception) {
            FileLog.e(e)
            0L
        }
    }

    /**
     * "Clear saved history" of the account in slot [slot]: removes its kept messages from its chats (on this phone
     * only; nothing is sent) and empties its saved history, edits included. Other accounts keep theirs: a hidden
     * account's history can't be wiped from the settings of the account on show. The kept messages live in Telegram's
     * cache too, so emptying only this store would leave them in the chats looking like ordinary messages. UI thread;
     * [onDone] runs on it.
     */
    fun clearAccount(slot: Int, onDone: Runnable) {
        val uid = UserConfig.getInstance(slot).getClientUserId()
        if (uid == 0L) {
            onDone.run()
            return
        }
        io.execute {
            val kept = HashMap<Long, ArrayList<Int>>()
            try {
                db.rawQuery("SELECT dialog_id, msg_id FROM deleted_messages WHERE account_uid = ?", arrayOf(uid.toString())).use { c ->
                    while (c.moveToNext()) kept.getOrPut(c.getLong(0)) { ArrayList() }.add(c.getInt(1))
                }
            } catch (e: Exception) {
                FileLog.e(e)
            }
            AndroidUtilities.runOnUIThread {
                for ((dialogId, ids) in kept) dropFromChat(slot, dialogId, ids)
                afterStorage(listOf(slot)) {
                    try {
                        val args = arrayOf<Any>(uid)
                        db.execSQL("DELETE FROM deleted_messages WHERE account_uid = ?", args)
                        db.execSQL("DELETE FROM edit_history WHERE account_uid = ?", args)
                        // Old edits the migration could give to no account: never shown, so nobody's to keep.
                        db.execSQL("DELETE FROM edit_history WHERE account_uid = 0")
                        // An old mark still waiting for the server must not come back to this account afterwards.
                        db.execSQL("INSERT OR REPLACE INTO meta(key, value) VALUES(?, '1')", arrayOf<Any>("cleared_$uid"))
                        db.execSQL("VACUUM")
                        // In WAL mode VACUUM writes the shrunk database into the WAL, which keeps its size: without a
                        // truncating checkpoint the space doesn't come back.
                        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                    } catch (e: Exception) {
                        FileLog.e(e)
                    }
                    // A chat's marks still loading are queued behind this on io and read the emptied table.
                    synchronized(marks) {
                        marks.keys.removeAll { it.uid == uid }
                    }
                    AndroidUtilities.runOnUIThread(onDone)
                }
            }
        }
    }

    // endregion

    // region one-time migration of the old stores

    /**
     * Startup, on the history warm-up thread: moves the old stores ("mark_delete" and "edited_messages" in the "db"
     * preferences) here once, then asks the server about the old marks it couldn't give to an account by itself. Until
     * the move is done, chats show no marks (they come with the refresh that follows).
     */
    fun warmUp() {
        try {
            migrateIfNeeded()
        } catch (e: Throwable) {
            FileLog.e(e)
        }
        ready = true
        try {
            resolvePending()
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    private class Account(val slot: Int, val uid: Long)

    /**
     * The logged-in accounts. The warm-up starts in ApplicationLoader.onCreate, before postInitApplication loads the
     * accounts' configs, so it loads them itself (the same cheap prefs read; loadConfig runs once per account). Without
     * that the list came out empty at startup, and the migration would have dropped every old mark as held by nobody.
     */
    private fun accounts(): List<Account> = (0 until UserConfig.MAX_ACCOUNT_COUNT)
        .filter { UserConfig.getInstance(it).also { config -> config.loadConfig() }.isClientActivated }
        .map { Account(it, UserConfig.getInstance(it).getClientUserId()) }
        .filter { it.uid != 0L }

    private fun meta(key: String): String? = db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key))
        .use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun migrateIfNeeded() {
        val prefs = ApplicationLoader.applicationContext.getSharedPreferences("db", Context.MODE_PRIVATE)
        if (meta("migrated") == "1") {
            dropLegacy(prefs)
            return
        }
        // Old marks: the newest per message wins, as it did in the old lookup.
        val oldMarks = LinkedHashMap<Pair<Long, Int>, Int>()
        for (m in DeletedMsg.parseLegacy(prefs)) {
            val key = Pair(m.dialogId ?: continue, m.id ?: continue)
            val who = DeletedMsg.whoCode(m.who ?: continue)
            oldMarks.remove(key)
            oldMarks[key] = who
        }
        val oldEdits = EditMessage.parseLegacy(prefs)
        if (oldMarks.isEmpty() && oldEdits.isEmpty()) {
            markMigrated()
            dropLegacy(prefs)
            return
        }
        val accounts = accounts()
        // No account to give the old history to (none logged in, or not readable yet): keep it for a later start
        // rather than drop it all as held by nobody.
        if (accounts.isEmpty()) return

        // Which accounts hold each old message in their cache (and whether it is a channel's message).
        val wanted = HashSet<Pair<Long, Int>>(oldMarks.keys)
        for (e in oldEdits) wanted.add(Pair(e.dialogId ?: continue, e.msgId ?: continue))
        val holders = HashMap<Pair<Long, Int>, ArrayList<Account>>()
        val channel = HashSet<Pair<Long, Int>>()
        for (a in accounts) {
            val found = existing(a.slot, wanted) ?: return // an account's storage didn't answer: next start
            for ((key, isChannel) in found) {
                holders.getOrPut(key) { ArrayList() }.add(a)
                if (isChannel) channel.add(key)
            }
        }

        // A channel's message is the same message in every account, so its mark belongs to every account holding it.
        // Any other mark (private chats, basic groups, bots: ids counted per account) goes to the server, even when one
        // account holds it: the account that saw it deleted may have lost it from its cache while another account
        // merely has an unrelated message with the same id in a chat with the same id (its Saved Messages, say), and
        // only the server tells a deleted message from a living one. Held by none: the message is gone from every cache
        // and the mark is dropped (kept without an account it would mark the next message with that id in another
        // account).
        val pending = ArrayList<Pair<Pair<Long, Int>, Int>>()
        val toCopy = HashMap<Account, ArrayList<Kept>>()
        for ((key, who) in oldMarks) {
            val inAccounts = holders[key] ?: continue
            if (key in channel) {
                for (a in inAccounts) toCopy.getOrPut(a) { ArrayList() }.add(Kept(key.first, key.second, who, null))
            } else {
                pending.add(Pair(key, who))
            }
        }
        for ((a, rows) in toCopy) {
            if (!copyKept(a, rows)) return
        }

        // Old edits belong to the accounts whose copy of the message is edited (a channel's: every account holding
        // it); none edited or held: unknown (0), kept but never shown. Holding an unrelated message with the same id
        // is not enough, for the same reason as above.
        val editKeys = oldEdits.mapNotNull { e ->
            val key = Pair(e.dialogId ?: return@mapNotNull null, e.msgId ?: return@mapNotNull null)
            if (holders.containsKey(key) && key !in channel) key else null
        }.toHashSet()
        val editedIn = HashMap<Pair<Long, Int>, ArrayList<Account>>()
        if (editKeys.isNotEmpty()) {
            for (a in accounts) {
                val edited = edited(a.slot, editKeys) ?: return
                for (key in edited) editedIn.getOrPut(key) { ArrayList() }.add(a)
            }
        }

        val d = db
        d.beginTransaction()
        try {
            val editSt = d.compileStatement(
                "INSERT INTO edit_history(account_uid, dialog_id, msg_id, text, edited_at) VALUES(?, ?, ?, ?, ?)"
            )
            for (e in oldEdits) {
                val dialogId = e.dialogId ?: continue
                val msgId = e.msgId ?: continue
                val key = Pair(dialogId, msgId)
                val inAccounts = holders[key]
                val owners = when {
                    inAccounts == null -> listOf(0L)
                    key in channel -> inAccounts.map { it.uid }
                    else -> editedIn[key]?.map { it.uid } ?: listOf(0L)
                }
                for (uid in owners) {
                    editSt.clearBindings()
                    editSt.bindLong(1, uid)
                    editSt.bindLong(2, dialogId)
                    editSt.bindLong(3, msgId.toLong())
                    editSt.bindString(4, e.messageText ?: "")
                    editSt.bindLong(5, e.editedTime ?: 0L)
                    editSt.executeInsert()
                }
            }
            editSt.close()
            for ((key, who) in pending) {
                d.execSQL(
                    "INSERT OR IGNORE INTO pending_marks(dialog_id, msg_id, who) VALUES(?, ?, ?)",
                    arrayOf<Any>(key.first, key.second, who)
                )
            }
            d.execSQL("INSERT OR REPLACE INTO meta(key, value) VALUES('migrated', '1')")
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        FileLog.d("HistoryDb: migrated ${oldMarks.size} marks (${pending.size} to ask the server) and ${oldEdits.size} edits")
        dropLegacy(prefs)
    }

    private fun markMigrated() {
        db.execSQL("INSERT OR REPLACE INTO meta(key, value) VALUES('migrated', '1')")
    }

    private fun dropLegacy(prefs: android.content.SharedPreferences) {
        if (prefs.contains("mark_delete") || prefs.contains("edited_messages")) {
            prefs.edit().remove("mark_delete").remove("edited_messages").commit()
        }
    }

    /** Runs [block] on [slot]'s storage queue and waits for it; null when it failed or didn't answer in time. */
    private fun <T> onStorage(slot: Int, block: (MessagesStorage) -> T): T? {
        val storage = MessagesStorage.getInstance(slot)
        val latch = CountDownLatch(1)
        var result: T? = null
        storage.storageQueue.postRunnable {
            try {
                result = block(storage)
            } catch (e: Exception) {
                FileLog.e(e)
            } finally {
                latch.countDown()
            }
        }
        return if (latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) result else null
    }

    /** Of [keys], those in [slot]'s cache, each with whether it is a channel's message. */
    private fun existing(slot: Int, keys: Collection<Pair<Long, Int>>): Map<Pair<Long, Int>, Boolean>? = onStorage(slot) { storage ->
        val out = HashMap<Pair<Long, Int>, Boolean>()
        for ((dialogId, inChat) in keys.groupBy { it.first }) {
            for (chunk in inChat.chunked(500)) {
                val c = storage.database.queryFinalized(
                    "SELECT mid, is_channel FROM messages_v2 WHERE uid = $dialogId AND mid IN (" +
                        chunk.joinToString(",") { it.second.toString() } + ")"
                )
                try {
                    while (c.next()) out[Pair(dialogId, c.intValue(0))] = c.intValue(1) != 0
                } finally {
                    c.dispose()
                }
            }
        }
        out
    }

    /** Of [keys], those [slot] holds as edited messages. */
    private fun edited(slot: Int, keys: Collection<Pair<Long, Int>>): Set<Pair<Long, Int>>? = onStorage(slot) { storage ->
        val out = HashSet<Pair<Long, Int>>()
        for ((dialogId, inChat) in keys.groupBy { it.first }) {
            for (chunk in inChat.chunked(200)) {
                val c = storage.database.queryFinalized(
                    "SELECT mid, data FROM messages_v2 WHERE uid = $dialogId AND mid IN (" +
                        chunk.joinToString(",") { it.second.toString() } + ")"
                )
                try {
                    while (c.next()) {
                        val data = c.byteBufferValue(1) ?: continue
                        val message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false)
                        data.reuse()
                        if (message != null && message.edit_date != 0) out.add(Pair(dialogId, c.intValue(0)))
                    }
                } finally {
                    c.dispose()
                }
            }
        }
        out
    }

    /** Saves [rows] for [a] with Telegram's row bytes, read in chunks so other storage work isn't held up long. */
    private fun copyKept(a: Account, rows: List<Kept>): Boolean {
        for ((dialogId, inChat) in rows.groupBy { it.dialogId }) {
            for (chunk in inChat.chunked(200)) {
                val who = chunk.associate { it.msgId to it.who }
                val withData = onStorage(a.slot) { storage ->
                    val out = ArrayList<Kept>()
                    val c = storage.database.queryFinalized(
                        "SELECT mid, data FROM messages_v2 WHERE uid = $dialogId AND mid IN (" +
                            chunk.joinToString(",") { it.msgId.toString() } + ")"
                    )
                    try {
                        while (c.next()) {
                            val mid = c.intValue(0)
                            out.add(Kept(dialogId, mid, who[mid] ?: WHO_YOU, c.byteArrayValue(1)))
                        }
                    } finally {
                        c.dispose()
                    }
                    out
                } ?: return false
                // OR IGNORE: a deletion seen since the update started is newer than the old mark.
                if (!insertKept(a.uid, withData, replace = false)) return false
            }
        }
        return true
    }

    /**
     * Old marks the migration could not give to an account by itself: asks the server of each account holding the
     * message whether it still has it, and keeps the mark where it doesn't. A channel's message is the same message in
     * every account (and its id isn't in an account's own message box, which the server is asked about), so a pending
     * one goes to every account holding it without asking. Stays pending while an account can't be asked (offline),
     * until a start where every holder answered.
     */
    private fun resolvePending() {
        val pending = HashMap<Pair<Long, Int>, Int>()
        db.rawQuery("SELECT dialog_id, msg_id, who FROM pending_marks", null).use { c ->
            while (c.moveToNext()) pending[Pair(c.getLong(0), c.getInt(1))] = c.getInt(2)
        }
        if (pending.isEmpty()) return
        val accounts = accounts()
        // Same as the migration: with no account to ask, the pending marks wait for a later start.
        if (accounts.isEmpty()) return
        val unanswered = HashSet<Pair<Long, Int>>()
        for (a in accounts) {
            // The account cleared its saved history since: its old marks go with it.
            if (meta("cleared_${a.uid}") != null) continue
            val found = existing(a.slot, pending.keys) ?: return
            if (found.isEmpty()) continue
            val keep = ArrayList<Kept>()
            for ((key, isChannel) in found) {
                if (isChannel) keep.add(Kept(key.first, key.second, pending.getValue(key), null))
            }
            val ask = found.filterValues { !it }.keys
            if (ask.isNotEmpty()) {
                val gone = askServer(a.slot, ask.map { it.second }.distinct())
                if (gone == null) {
                    unanswered.addAll(ask)
                } else {
                    for (key in ask) if (key.second in gone) keep.add(Kept(key.first, key.second, pending.getValue(key), null))
                }
            }
            if (keep.isEmpty()) continue
            if (!copyKept(a, keep)) {
                unanswered.addAll(found.keys)
                continue
            }
            io.execute {
                synchronized(marks) {
                    for (r in keep) marks[Key(a.uid, r.dialogId)]?.put(r.msgId, r.who)
                }
            }
        }
        val d = db
        d.beginTransaction()
        try {
            for (key in pending.keys) {
                if (key in unanswered) continue
                d.execSQL("DELETE FROM pending_marks WHERE dialog_id = ? AND msg_id = ?", arrayOf<Any>(key.first, key.second))
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    /** Of [msgIds] (ids of the account's own message box), those the server no longer has; null without an answer. */
    private fun askServer(slot: Int, msgIds: List<Int>): Set<Int>? {
        val gone = HashSet<Int>()
        for (chunk in msgIds.chunked(100)) {
            val req = TLRPC.TL_messages_getMessages()
            req.id.addAll(chunk)
            val latch = CountDownLatch(1)
            var answered = false
            ConnectionsManager.getInstance(slot).sendRequest(req) { response, error ->
                if (error == null && response is TLRPC.messages_Messages) {
                    for (m in response.messages) {
                        if (m is TLRPC.TL_messageEmpty) gone.add(m.id)
                    }
                    answered = true
                }
                latch.countDown()
            }
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS) || !answered) return null
        }
        return gone
    }

    // endregion
}
