package org.fenixuz.folders

import android.content.Context
import android.content.SharedPreferences
import org.fenixuz.ui.create_folder_dialog.FolderIcons
import org.fenixuz.utils.LanguageCode
import org.fenixuz.utils.LanguageCodeAr
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.support.LongSparseIntArray
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.FilterCreateActivity

/**
 * Four folders that sort the user's groups and channels by the rights they hold in them:
 * groups they own, groups they administer, channels they own, channels they administer.
 *
 * Two things about this are worth stating plainly, because they shape every decision below.
 *
 * FIRST: Telegram folders live on the SERVER, not on this device. Creating them writes to the user's
 * account and the folders show up in official Telegram on their phone, desktop and web. That is why
 * nothing here happens without an explicit confirmation, and why turning the feature off offers to
 * remove exactly the four folders we made and nothing else.
 *
 * SECOND: Telegram has no "I am an admin here" filter flag — the auto-include flags are only
 * contacts / non-contacts / groups / broadcasts / bots. So membership has to be an explicit peer list,
 * which makes these folders a SNAPSHOT: a group the user is promoted in tomorrow will not appear on its
 * own, and one they are demoted from will linger. Hence [refresh], and hence the wording of string 397.
 */
object AdminFolders {

    private const val PREF = "db"
    private const val KEY_IDS_PREFIX = "admin_folder_ids_"
    // What each folder held last time we synced, so we can tell a real rights change from a chat the user
    // added or removed by hand. Without it an auto-sync would have to rewrite the whole list and would
    // silently undo their edits.
    private const val KEY_SNAP_PREFIX = "admin_folder_snap_"
    /**
     * Telegram refuses a folder whose title is longer than this with 400 MESSAGE_TOO_LONG, and the folder
     * is then dropped again the next time filters are fetched from the server -- which looks exactly like
     * "it saved and then vanished". FilterCreateActivity.MAX_NAME_LENGTH is the same number; we clamp
     * rather than trust the translations, so a longer wording later cannot break creation.
     */
    private const val MAX_FOLDER_NAME = 12

    /**
     * Prefix stamped on every folder this feature creates, and the thing that makes them recognisably OURS.
     *
     * Identity used to rest on the title text alone, which meant a folder the user happened to name
     * "Kanallarim" could be adopted -- or swept away by a disable. A marker removes that: a title has to
     * carry it AND match one of our texts. It is language-independent, it survives a rename of the wording,
     * and unlike the folder's `emoticon` field it actually reaches the server, because saveFilterToServer
     * sends the title and never sends emoticon.
     *
     * U+2605 is a BMP character, so it costs ONE UTF-16 unit of the 12-character budget, not two like an
     * emoji outside the BMP would. Every localized title is sized to leave room for it.
     */
    private const val MARKER = "★"

    /** At most one auto-sync per this interval; a rights change does not need a faster reaction. */
    private const val SYNC_MIN_INTERVAL_MS = 10_000L
    private val lastSyncAt = LongArray(UserConfig.MAX_ACCOUNT_COUNT)

    /**
     * The interval above drops a call rather than delaying it, so a rights change that happens to land
     * inside the window would simply be lost until some later notification came along -- "usually works"
     * rather than a guarantee. One trailing re-check per window closes that: at most one is outstanding per
     * account, it is scheduled only when a call was actually dropped, and it carries nothing but the account
     * index, so there is no Activity or Fragment for it to keep alive.
     */
    private val pendingCheck = BooleanArray(UserConfig.MAX_ACCOUNT_COUNT)
    private val trailingCheck = Array(UserConfig.MAX_ACCOUNT_COUNT) { a ->
        Runnable {
            pendingCheck[a] = false
            syncIfNeeded(a)
        }
    }

    /**
     * One folder operation at a time per account. Every one of these is a CHAIN of server round-trips, and
     * they all read the account's current folder list to decide what to adopt, create or delete. Two chains
     * overlapping — the obvious way being a quick off-then-on — means the second one reads a list the first
     * is halfway through changing, and duplicates fall straight out of that.
     */
    private val busy = BooleanArray(UserConfig.MAX_ACCOUNT_COUNT)
    private val busyAt = LongArray(UserConfig.MAX_ACCOUNT_COUNT)

    /**
     * A chain releases the flag when it ends, but it ends on a network callback -- and a callback that
     * never arrives would wedge the feature for the rest of the process. Treating an old claim as stale
     * removes that whole class of failure for the price of one comparison.
     */
    private const val BUSY_STALE_MS = 60_000L

    private fun claimed(account: Int): Boolean =
        busy[account] && System.currentTimeMillis() - busyAt[account] < BUSY_STALE_MS

    private fun claim(account: Int) {
        busy[account] = true
        busyAt[account] = System.currentTimeMillis()
        notifyBusy()
    }

    private fun release(account: Int) {
        busy[account] = false
        notifyBusy()
    }

    @JvmStatic
    fun isBusy(account: Int): Boolean = account in busy.indices && claimed(account)

    /**
     * Lets an open settings screen grey its controls out for as long as an operation is running.
     *
     * Taps were not lost before this -- [claimed] refuses them, which is what stops a quick off-then-on from
     * racing two chains against each other -- but a control that silently ignores a tap reads as broken. One
     * listener is enough: only FenixSettings shows these rows, and it clears the reference when it pauses, so
     * nothing here can outlive the screen.
     */
    private var busyListener: Runnable? = null

    @JvmStatic
    fun setBusyListener(listener: Runnable?) {
        busyListener = listener
    }

    private fun notifyBusy() {
        busyListener?.let { org.telegram.messenger.AndroidUtilities.runOnUIThread(it) }
    }

    /** Owner of the folders we recorded, so state cannot survive a logout into a different account. */
    private const val KEY_UID_PREFIX = "admin_folder_uid_"

    /** The four buckets, in the order they are created (and therefore shown). */
    enum class Kind(val titleCode: Int, val iconRes: Int) {
        GROUP_OWNER(393, R.drawable.msg_groups),
        GROUP_ADMIN(394, R.drawable.msg_folders_groups),
        CHANNEL_OWNER(395, R.drawable.msg_channel),
        CHANNEL_ADMIN(396, R.drawable.msg_folders_channels);

        val title: String get() = (MARKER + LanguageCode.getMyTitles(titleCode)).take(MAX_FOLDER_NAME)
    }

    /** What a create/refresh actually managed to do, so the caller can tell the user the truth. */
    class Result(
        /** Folders written to the account. */
        val created: Int,
        /** Chats filed across all four. */
        val filed: Int,
        /** Folders whose list was cut to Telegram's per-folder limit, as "<folder title>" to count. */
        val truncated: LinkedHashMap<String, Int>,
        /** Set when nothing could be done at all; already localized, ready to show. */
        val blockedReason: String?
    )

    private fun prefs(): SharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun keyIds(account: Int) = KEY_IDS_PREFIX + account
    private fun keySnap(account: Int) = KEY_SNAP_PREFIX + account
    private fun keyUid(account: Int) = KEY_UID_PREFIX + account

    /**
     * Account slots are reused: log out of index 0 and the next account signs in as index 0 too. Filter ids
     * start at 2, so the ids we recorded would very likely collide with folders belonging to the NEW user --
     * and a sync would then rewrite a stranger's folder with our peer list. Tying the state to the user id
     * that produced it makes that impossible, and self-heals without needing a logout listener.
     */
    private fun stateBelongsToCurrentUser(account: Int): Boolean {
        val uid = UserConfig.getInstance(account).clientUserId
        if (uid == 0L) return false
        val stored = prefs().getLong(keyUid(account), 0L)
        if (stored == uid) return true
        if (stored == 0L) {
            // No stamp: either there is nothing recorded, or the folders were made before this check
            // existed. Adopt the latter for the signed-in user instead of discarding it -- rejecting
            // unstamped state is what made the feature read as off for anyone who had already enabled it.
            val hasState = !prefs().getString(keyIds(account), "").isNullOrEmpty()
            if (!hasState) return false
            prefs().edit().putLong(keyUid(account), uid).apply()
            return true
        }
        // Stamped by a different user: this slot was reused after a logout. Drop it rather than acting on
        // folders that belong to somebody else.
        prefs().edit().remove(keyIds(account)).remove(keySnap(account)).remove(keyUid(account)).apply()
        return false
    }

    /** kind ordinal -> filter id, for the folders we own. */
    private fun kindToFilter(account: Int): LinkedHashMap<Kind, Int> {
        val out = LinkedHashMap<Kind, Int>()
        if (!stateBelongsToCurrentUser(account)) return out
        val raw = prefs().getString(keyIds(account), "") ?: ""
        for (part in raw.split(',')) {
            val bits = part.split(':')
            if (bits.size != 2) continue
            val k = bits[0].toIntOrNull() ?: continue
            val id = bits[1].toIntOrNull() ?: continue
            if (k in Kind.entries.indices) out[Kind.entries[k]] = id
        }
        return out
    }

    private fun saveKindToFilter(account: Int, map: Map<Kind, Int>) {
        prefs().edit()
            .putString(keyIds(account), map.entries.joinToString(",") { it.key.ordinal.toString() + ":" + it.value })
            .putLong(keyUid(account), UserConfig.getInstance(account).clientUserId)
            .apply()
    }

    /** The ids we filed per kind at the last sync. */
    private fun snapshot(account: Int): LinkedHashMap<Kind, MutableSet<Long>> {
        val out = LinkedHashMap<Kind, MutableSet<Long>>()
        for (k in Kind.entries) out[k] = LinkedHashSet()
        val raw = prefs().getString(keySnap(account), "") ?: ""
        for ((i, chunk) in raw.split(';').withIndex()) {
            if (i !in Kind.entries.indices || chunk.isEmpty()) continue
            for (d in chunk.split(',')) d.toLongOrNull()?.let { out[Kind.entries[i]]!!.add(it) }
        }
        return out
    }

    private fun saveSnapshot(account: Int, snap: Map<Kind, out Collection<Long>>) {
        val raw = Kind.entries.joinToString(";") { k -> (snap[k] ?: emptyList()).joinToString(",") }
        prefs().edit().putString(keySnap(account), raw).apply()
    }

    /**
     * Every title we could have given a folder OF THIS KIND, in every language the app ships -- each one
     * carrying [MARKER].
     *
     * Identity is really the stored kind->id map; this is only the recovery net for when that map is
     * unavailable (a reinstall, cleared app data, a bug in our own bookkeeping). It has to be per-kind or a
     * sweep would file the channels folder under groups, and it has to cover all three languages because the
     * user can switch language at any time and a folder we made then no longer carries the title we would
     * generate now.
     *
     * **Every entry must carry the marker.** The list used to include the bare titles too, which quietly
     * turned the net into a trap: a folder the user had named `Kanallarim`, `Guruhlarim`, `My channels` or
     * `Мои каналы` -- ordinary names, and Telegram's 12-character cap pushes people towards exactly these --
     * matched as one of ours. Enabling the feature ADOPTED it and replaced its contents; disabling DELETED it
     * from the account, on every device the user is signed in on. The marker existed precisely to prevent
     * that and was doing nothing, because nothing required it.
     *
     * The bare titles were there for folders created before the marker was introduced. Those exist only on
     * the development phone -- this feature has never shipped -- so dropping them costs nobody anything.
     */
    private fun titlesFor(kind: Kind): Set<String> {
        val out = LinkedHashSet<String>()
        LanguageCode.getMyTitles(kind.titleCode)          // forces the table to initialize
        LanguageCode.titlesLanguages.getOrNull(kind.titleCode)?.let { t ->
            // Arabic too: a folder made while the app was in Arabic is titled in Arabic, and one we can't
            // recognise as ours is never synced or removed, and enabling again would create a second one.
            for (v in listOf(t.en, t.uz, t.ru, LanguageCodeAr.get(kind.titleCode).orEmpty())) {
                // A missing translation would collapse to the bare marker, which would then match any folder
                // the user happened to name "★". Skip it rather than widen the net.
                if (v.isBlank()) continue
                out.add((MARKER + v).take(MAX_FOLDER_NAME))
            }
        }
        return out
    }

    /** Ids of the folders WE created for [account]. Empty means the feature is off. */
    fun createdIds(account: Int): List<Int> = kindToFilter(account).values.toList()

    @JvmStatic
    fun isEnabled(account: Int): Boolean = createdIds(account).isNotEmpty()

    /**
     * Bucket every chat the user has rights in. Local only — `creator` and `admin_rights` ride along on
     * the TLRPC.Chat we already hold for each dialog, so this costs no network and no disk.
     *
     * Owners are deliberately kept OUT of the admin buckets: [ChatObject.hasAdminRights] answers true for
     * a creator too, and leaving that in would make "My groups" and "Admin groups" near-duplicates.
     */
    fun classify(account: Int): LinkedHashMap<Kind, MutableList<Long>> {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val buckets = LinkedHashMap<Kind, MutableList<Long>>()
        for (k in Kind.entries) buckets[k] = ArrayList()

        val controller = MessagesController.getInstance(account)
        val all = ArrayList(controller.getAllDialogs())
        val dialogCount = all.size
        for (dialog in all) {
            val did = dialog?.id ?: continue
            if (!DialogObject.isChatDialog(did)) continue
            val chat: TLRPC.Chat = controller.getChat(-did) ?: continue
            // Left, kicked, or deactivated: the chat is still in the list but holding rights there is
            // meaningless, and filing it would look like the folder is showing dead chats.
            if (ChatObject.isNotInChat(chat) || chat.left || chat.kicked) continue
            if (!ChatObject.hasAdminRights(chat)) continue

            val owner = ChatObject.isCreator(chat)
            // A broadcast channel is a channel that is NOT a megagroup; everything else (megagroup,
            // legacy chat) counts as a group, which is how users think about it.
            val broadcast = ChatObject.isChannel(chat) && !chat.megagroup
            val kind = when {
                broadcast && owner -> Kind.CHANNEL_OWNER
                broadcast -> Kind.CHANNEL_ADMIN
                owner -> Kind.GROUP_OWNER
                else -> Kind.GROUP_ADMIN
            }
            buckets[kind]!!.add(did)
        }
        // This is the only part of the feature that walks the whole chat list, and it runs on the UI thread.
        // Rather than assume it is cheap, say so out loud if it ever is not: one frame is ~16 ms, so anything
        // at or past that is worth knowing about on a big account.
        val tookMs = android.os.SystemClock.elapsedRealtime() - startedAt
        if (tookMs >= 16) {
            FileLog.d("Novagram folders: classify took " + tookMs + " ms over " + dialogCount + " dialogs -- move it off the UI thread if this is common")
        }
        return buckets
    }

    private fun folderLimit(account: Int): Int {
        val c = MessagesController.getInstance(account)
        return if (UserConfig.getInstance(account).isPremium) c.dialogFiltersLimitPremium else c.dialogFiltersLimitDefault
    }

    private fun chatsPerFolderLimit(account: Int): Int {
        val c = MessagesController.getInstance(account)
        return if (UserConfig.getInstance(account).isPremium) c.dialogFiltersChatsLimitPremium else c.dialogFiltersChatsLimitDefault
    }

    /**
     * Keep the two invariants Telegram's own filter editor keeps whenever the include list changes: no chat
     * may sit in both lists, and a pinned dialog must still be a member (FilterCreateActivity does exactly
     * this in its include-picker delegate).
     *
     * It matters here because we edit filters IN PLACE and therefore inherit whatever exclusions and pins the
     * user set inside our folders. A chat left in both lists, or pinned in a folder it is no longer part of,
     * is a folder the client and the server quietly disagree about.
     */
    private fun alignAuxLists(filter: MessagesController.DialogFilter, peers: List<Long>) {
        val members = HashSet(peers)
        filter.neverShow?.removeAll(members)
        val pinned = filter.pinnedDialogs ?: return
        val drop = ArrayList<Long>()
        for (i in 0 until pinned.size()) {
            val did = pinned.keyAt(i)
            if (DialogObject.isEncryptedDialog(did)) continue     // secret chats are pinned independently
            if (did !in members) drop.add(did)
        }
        for (did in drop) pinned.delete(did)
    }

    /** First id not already taken by one of the user's folders — the allocation Telegram itself uses. */
    private fun freeFilterId(account: Int, alsoTaken: Set<Int>): Int {
        val byId = MessagesController.getInstance(account).dialogFiltersById
        var id = 2
        while (byId.get(id) != null || alsoTaken.contains(id)) id++
        return id
    }

    /**
     * Create the folders that have anything in them. Empty buckets are skipped rather than created empty —
     * an empty "Admin channels" tab is noise for someone who administers none.
     *
     * [fragment] is required: [FilterCreateActivity.saveFilterToServer] needs a live parent activity, so
     * this runs from a screen and never from a background task.
     */
    fun create(fragment: BaseFragment, account: Int, onDone: (Result) -> Unit) {
        if (account !in busy.indices || claimed(account)) return
        claim(account)
        val done: (Result) -> Unit = { r -> release(account); onDone(r) }
        val buckets = classify(account).filterValues { it.isNotEmpty() }
        if (buckets.isEmpty()) {
            done(Result(0, 0, LinkedHashMap(), LanguageCode.getMyTitles(399)))
            return
        }

        // Count only the folders we would genuinely have to ADD. A bucket whose folder is already on the
        // account gets adopted and needs no slot, so charging for it would block the common repair case --
        // re-enabling when four of ours are already there -- with a limit message that is simply wrong.
        val filters = MessagesController.getInstance(account).dialogFilters
        val existing = filters.size
        val trackedNow = createdIds(account).toSet()
        val needSlots = buckets.keys.count { kind ->
            val known = titlesFor(kind)
            filters.none { it != null && !it.isDefault && (it.id in trackedNow || it.name in known) }
        }
        if (folderLimit(account) - existing < needSlots) {
            val msg = LanguageCode.getMyTitles(400)
                .replace("%1\$d", folderLimit(account).toString())
                .replace("%2\$d", existing.toString())
            done(Result(0, 0, LinkedHashMap(), msg))
            return
        }

        val perFolder = chatsPerFolderLimit(account)
        val truncated = LinkedHashMap<String, Int>()
        val taken = HashSet<Int>()
        val newIds = ArrayList<Int>()
        var filed = 0

        FileLog.d("Novagram folders: create -- buckets " + buckets.entries.joinToString { it.key.name + "=" + it.value.size }
                + ", tracked " + createdIds(account) + ", existing folders " + existing + ", need " + needSlots + " new slot(s)")
        val queue = ArrayDeque(buckets.entries.map { it.key to it.value })

        fun step() {
            val next = queue.removeFirstOrNull()
            if (next == null) {
                // MessagesController.addFilter() does NOT post this (removeFilter does), so without it the
                // tabs only appear after DialogsActivity is recreated -- which reads as "nothing happened".
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogFiltersUpdated)
                done(Result(newIds.size, filed, truncated, null))
                return
            }
            // saveFilterToServer returns WITHOUT calling onFinish when the fragment has lost its activity
            // (the user navigated away mid-chain), which would strand the queue forever. Stop here instead;
            // the ids of the folders already made are persisted as we go, so turning the feature off can
            // still clean them up.
            if (fragment.parentActivity == null) {
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogFiltersUpdated)
                done(Result(newIds.size, filed, truncated, null))
                return
            }
            val (kind, peersAll) = next
            // Cut to the server's limit rather than letting the request fail, and remember that we did so —
            // silently dropping chats is exactly the kind of thing a user reports as "the folder is wrong".
            val peers = if (peersAll.size > perFolder) {
                truncated[kind.title] = perFolder
                ArrayList(peersAll.subList(0, perFolder))
            } else {
                ArrayList(peersAll)
            }

            // Adopt a folder of ours that we have lost track of instead of making a second one. This
            // happens after a reinstall, after clearing app data, or if our own bookkeeping ever breaks --
            // and without it the user ends up with a duplicate set every time they toggle the feature.
            // Matched on the exact title we generate, and only among folders we are not already tracking.
            val tracked = createdIds(account).toSet()
            val known = titlesFor(kind)
            val adopted = MessagesController.getInstance(account).dialogFilters
                .firstOrNull { it != null && !it.isDefault && it.name in known && it.id !in tracked && it.id !in taken }

            val creating = adopted == null
            FileLog.d("Novagram folders: " + kind.name + " -> " + (if (creating) "CREATE new" else "ADOPT id=" + adopted!!.id + " name='" + adopted.name + "'")
                    + " (known titles: " + known.joinToString("|") + ")")
            val filter = adopted ?: MessagesController.DialogFilter()
            if (creating) {
                filter.id = freeFilterId(account, taken)
                filter.neverShow = ArrayList()
                filter.pinnedDialogs = LongSparseIntArray()
            }
            taken.add(filter.id)
            filter.name = kind.title
            filter.flags = 0                     // no auto-include: membership is exactly [alwaysShow]
            filter.color = kind.ordinal % 8
            filter.alwaysShow = peers

            newIds.add(filter.id)
            filed += peers.size
            val map = kindToFilter(account); map[kind] = filter.id; saveKindToFilter(account, map)
            val snap = snapshot(account); snap[kind] = LinkedHashSet(peers); saveSnapshot(account, snap)
            // Our folders carry flags = 0 by design, and FolderIcons' flag-based guess only knows the
            // built-in filter types -- so without an explicit icon all four land on the generic one.
            FolderIcons.setIconRes(filter.id, kind.iconRes)

            // A filter with no peers and no auto-include flags is rejected (FILTER_INCLUDE_EMPTY), and if it
            // ever were accepted it would empty a folder the user is relying on. Whatever produced an empty
            // list, sending it is never the right move -- leave the folder as it is.
            if (peers.isEmpty()) {
                FileLog.d("Novagram folders: " + kind.name + " computed an EMPTY peer list -- skipping")
                return step()
            }
            alignAuxLists(filter, peers)
            FilterCreateActivity.saveFilterToServer(
                filter, filter.flags, filter.name, filter.entities, filter.title_noanimate, filter.color,
                filter.alwaysShow, filter.neverShow, filter.pinnedDialogs,
                /* creatingNew */ creating, /* atBegin */ false, /* hasUserChanged */ true,
                /* resetUnreadCounter */ false, /* progress */ false, fragment
            ) { step() }   // strictly sequential: parallel saves race on the server's filter order
        }
        step()
    }

    /**
     * Put the folders back to exactly what the user's rights say they should be.
     *
     * This is the deliberate counterpart to [syncIfNeeded]: the sync applies a DELTA and therefore preserves
     * chats the user added or removed by hand, while Refresh is the way to say "forget my edits, rebuild from
     * my rights". Nothing else offers that, which is why the row is worth keeping.
     *
     * It rewrites each folder IN PLACE. The first version deleted all four and created them again, which
     * worked but was the wrong shape for a repair button: the folders came back at the end of the tab strip
     * with new ids, the user's own pinned chats and exclusions inside them were lost, and a chain that broke
     * after the deletes left the account with no folders at all. Editing the existing filter keeps its
     * position, its id, its pinned dialogs and its exclusions, and the worst a broken chain can now do is
     * leave one folder un-updated.
     *
     * Three cases per bucket:
     *  - folder exists and the bucket has chats -> overwrite its member list, title, colour and icon;
     *  - folder exists and the bucket is now EMPTY -> delete it, because [create] would not have made an
     *    empty one either and Telegram refuses a filter with no members anyway;
     *  - no folder and the bucket has chats -> adopt a stray of ours, or create one if there is room.
     */
    fun refresh(fragment: BaseFragment, account: Int, onDone: (Result) -> Unit) {
        if (account !in busy.indices || claimed(account)) return
        val map = kindToFilter(account)
        if (map.isEmpty()) {
            // Nothing of ours on record: this is a first build, not a repair.
            create(fragment, account, onDone)
            return
        }
        val controller = MessagesController.getInstance(account)
        // Refreshing off a half-loaded chat list would read as "you administer nothing" and delete all four.
        // The same guard syncIfNeeded relies on, restated here because this path is reachable from the UI at
        // any moment, including a few hundred milliseconds after a cold start.
        if (!controller.dialogFiltersLoaded || !controller.dialogsLoaded || controller.getAllDialogs().isEmpty()) {
            onDone(Result(0, 0, LinkedHashMap(), LanguageCode.getMyTitles(399)))
            return
        }
        claim(account)
        val done: (Result) -> Unit = { r -> release(account); onDone(r) }

        val current = classify(account)
        val perFolder = chatsPerFolderLimit(account)
        val truncated = LinkedHashMap<String, Int>()
        val taken = HashSet<Int>()
        var created = 0
        var filed = 0

        // UPDATE / DELETE / CREATE, all decided before a single request goes out, so the chain cannot change
        // its mind halfway through on a folder list that its own writes are mutating.
        val updates = ArrayList<Kind>()
        val deletes = ArrayList<Pair<Kind, Int>>()
        val creates = ArrayList<Kind>()
        for (kind in Kind.entries) {
            val has = (current[kind] ?: emptyList<Long>()).isNotEmpty()
            val existing = map[kind]?.let { controller.dialogFiltersById.get(it) }
            when {
                existing != null && has -> updates.add(kind)
                existing != null -> deletes.add(kind to existing.id)
                // Tracked but the filter is gone: deleted by hand, or on another device. Refresh is an
                // explicit "rebuild from my rights", so unlike syncIfNeeded this DOES bring it back.
                has -> creates.add(kind)
                else -> if (map.containsKey(kind)) {
                    val m = kindToFilter(account); m.remove(kind); saveKindToFilter(account, m)
                    val sp = snapshot(account); sp[kind] = LinkedHashSet(); saveSnapshot(account, sp)
                }
            }
        }

        // Charge the folder limit only for a bucket that would need a genuinely NEW slot; one we can adopt
        // costs nothing. Over the limit we skip the creates and still do the updates -- a partial repair
        // beats refusing to repair anything.
        var room = folderLimit(account) - controller.dialogFilters.size
        val creatable = ArrayList<Kind>()
        for (kind in creates) {
            val known = titlesFor(kind)
            val tracked = createdIds(account).toSet()
            val adoptable = controller.dialogFilters.any { it != null && !it.isDefault && it.name in known && it.id !in tracked }
            if (adoptable) { creatable.add(kind); continue }
            if (room > 0) { creatable.add(kind); room-- }
        }
        val skipped = creates.size - creatable.size

        FileLog.d("Novagram folders: refresh -- update " + updates.map { it.name }
                + ", delete " + deletes.map { it.first.name } + ", create " + creatable.map { it.name }
                + (if (skipped > 0) ", SKIPPED " + skipped + " (no folder slots left)" else ""))

        val queue = ArrayDeque<Pair<Kind, Boolean>>()          // kind, isNew
        for (k in updates) queue.add(k to false)
        for (k in creatable) queue.add(k to true)

        fun finish() {
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogFiltersUpdated)
            val blocked = if (skipped > 0) {
                LanguageCode.getMyTitles(400)
                    .replace("%1\$d", folderLimit(account).toString())
                    .replace("%2\$d", controller.dialogFilters.size.toString())
            } else null
            done(Result(created, filed, truncated, blocked))
        }

        // Deletions run LAST. If the chain dies partway the user is left with one stale folder, which another
        // press of Refresh fixes -- whereas dying after the deletes and before the writes would leave them
        // with nothing, which is the failure the old delete-then-recreate shape had built in.
        fun sweep() {
            val gone = ArrayDeque(deletes)
            fun next() {
                val d = gone.removeFirstOrNull()
                if (d == null) { finish(); return }
                deleteFilterOnServer(fragment, account, d.second) { ok ->
                    if (ok) {
                        val m = kindToFilter(account); m.remove(d.first); saveKindToFilter(account, m)
                        val sp = snapshot(account); sp[d.first] = LinkedHashSet(); saveSnapshot(account, sp)
                    }
                    next()
                }
            }
            next()
        }

        fun step() {
            val next = queue.removeFirstOrNull()
            if (next == null) { sweep(); return }
            // saveFilterToServer returns WITHOUT calling onFinish once the fragment has lost its activity,
            // which would strand the queue and hold the busy flag until it went stale. Stop cleanly instead;
            // everything written so far is already persisted.
            if (fragment.parentActivity == null) { finish(); return }
            val (kind, isNew) = next

            val all = current[kind] ?: emptyList<Long>()
            val peers = if (all.size > perFolder) {
                truncated[kind.title] = perFolder
                ArrayList(all.subList(0, perFolder))
            } else {
                ArrayList(all)
            }
            if (peers.isEmpty()) {
                // Unreachable -- an empty bucket became a delete above -- but a filter with no members and no
                // auto-include flags is rejected as FILTER_INCLUDE_EMPTY, so never send one.
                return step()
            }

            val filter: MessagesController.DialogFilter
            val creating: Boolean
            if (isNew) {
                val tracked = createdIds(account).toSet()
                val known = titlesFor(kind)
                val adopted = controller.dialogFilters
                    .firstOrNull { it != null && !it.isDefault && it.name in known && it.id !in tracked && it.id !in taken }
                creating = adopted == null
                filter = adopted ?: MessagesController.DialogFilter()
                if (creating) {
                    filter.id = freeFilterId(account, taken)
                    filter.neverShow = ArrayList()
                    filter.pinnedDialogs = LongSparseIntArray()
                }
                created++
            } else {
                creating = false
                // Non-null by construction: this kind is in `updates` only because the lookup succeeded, and
                // nothing between there and here removes a filter.
                filter = controller.dialogFiltersById.get(map[kind] ?: -1) ?: return step()
            }
            taken.add(filter.id)
            // Rewriting the title on an update is not cosmetic: it repairs a folder still carrying a
            // pre-marker or wrong-language name, so a later enable/disable still recognises it as ours.
            filter.name = kind.title
            filter.flags = 0                     // no auto-include: membership is exactly [alwaysShow]
            filter.color = kind.ordinal % 8
            filter.alwaysShow = peers
            filed += peers.size

            val m = kindToFilter(account); m[kind] = filter.id; saveKindToFilter(account, m)
            val sp = snapshot(account); sp[kind] = LinkedHashSet(all); saveSnapshot(account, sp)
            FolderIcons.setIconRes(filter.id, kind.iconRes)

            alignAuxLists(filter, peers)
            FilterCreateActivity.saveFilterToServer(
                filter, filter.flags, filter.name, filter.entities, filter.title_noanimate, filter.color,
                filter.alwaysShow, filter.neverShow, filter.pinnedDialogs,
                /* creatingNew */ creating, /* atBegin */ false, /* hasUserChanged */ true,
                /* resetUnreadCounter */ false, /* progress */ false, fragment
            ) { step() }   // strictly sequential: parallel saves race on the server's filter order
        }
        step()
    }

    /**
     * Keep the folders current without the user going to Settings and pressing Refresh.
     *
     * It applies a DELTA, never a rewrite: only chats whose rights actually changed since the last sync are
     * added or removed. That matters — these are ordinary Telegram folders and the user is free to drop a
     * chat from one or add their own; rewriting the whole list every time would quietly undo that.
     *
     * No server call happens unless something really changed, and it needs a visible fragment because
     * saveFilterToServer does; with the app in the background it simply waits for the next call.
     */
    @JvmStatic
    fun syncIfNeeded(account: Int) {
        // Order matters: this is called from updateInterfaces, which fires constantly. The time check is a
        // subtraction; isEnabled() reads a preference and parses it into a map. Cheapest guard first.
        if (account < 0 || account >= lastSyncAt.size) return
        // Never run alongside create/remove/refresh. Adding a folder makes the app post
        // updateInterfaces, which lands straight back here -- and mid-create the kind->id map is
        // filled in only as each folder lands, so a kind still in flight looks like "no folder yet"
        // and gets created a SECOND time. That is exactly how one enable produced two of three.
        if (claimed(account)) return
        val now = System.currentTimeMillis()
        if (now - lastSyncAt[account] < SYNC_MIN_INTERVAL_MS) {
            // Come back when the window closes instead of dropping this outright. Guarded by a plain array
            // read so the hot path stays cheap: updateInterfaces fires constantly and lands here most times.
            if (!pendingCheck[account]) {
                pendingCheck[account] = true
                org.telegram.messenger.AndroidUtilities.runOnUIThread(
                    trailingCheck[account], SYNC_MIN_INTERVAL_MS - (now - lastSyncAt[account]) + 50L)
            }
            return
        }
        if (!isEnabled(account)) return

        val controller = MessagesController.getInstance(account)
        // Wait for the folders to come off disk. dialogFiltersById is empty until then, and this can run at
        // cold start. Without this guard every folder looks deleted, the branch below "forgets" all four,
        // and the feature switches itself off on restart.
        if (!controller.dialogFiltersLoaded) return
        // And for the DIALOGS. Folders come off disk before the chat list does, and classify() reads the
        // chat list: in that window every bucket looks empty, the delta reads as 'every chat lost its
        // rights', and the update would strip all four folders bare. The server refused it with
        // FILTER_INCLUDE_EMPTY -- its validation is the only reason the folders were not emptied.
        if (!controller.dialogsLoaded || controller.getAllDialogs().isEmpty()) return
        // Claim the interval here, not once work is found. classify() walks the whole dialog list and this
        // is called from a hot notification, so the CHECK is what has to be rate-limited.
        lastSyncAt[account] = now

        val map = kindToFilter(account)
        if (map.isEmpty()) return
        val current = classify(account)
        val snap = snapshot(account)
        val perFolder = chatsPerFolderLimit(account)

        // An entry is either an update of a folder we already have, or the first folder of a kind the user
        // has only just acquired: the very first group they are made admin of should not need a manual
        // Refresh to get a folder, which is what happened before this branch existed.
        val jobs = ArrayList<Triple<Kind, List<Long>, List<Long>>>()   // kind, added, removed
        val fresh = ArrayList<Kind>()
        for (kind in Kind.entries) {
            val nowList = current[kind] ?: emptyList<Long>()
            if (map.containsKey(kind)) {
                val nowSet = LinkedHashSet(nowList)
                val wasSet = snap[kind] ?: LinkedHashSet()
                val added = nowSet.filter { it !in wasSet }
                val removed = wasSet.filter { it !in nowSet }
                if (added.isNotEmpty() || removed.isNotEmpty()) jobs.add(Triple(kind, added, removed))
            } else if (nowList.isNotEmpty()) {
                fresh.add(kind)
            }
        }
        // Only make new folders while there is room; an over-limit request would just be refused, and
        // nagging about it from a background sync the user did not ask for would be noise.
        val room = folderLimit(account) - controller.dialogFilters.size
        val freshAllowed = fresh.take(maxOf(0, room))
        if (jobs.isEmpty() && freshAllowed.isEmpty()) return

        val fragment = org.telegram.ui.LaunchActivity.getLastFragment() ?: return
        if (fragment.parentActivity == null) return
        // Claim only here: every early return above must leave the flag DOWN, or one skipped
        // sync would wedge create, remove and every later sync for the rest of the process.
        claim(account)

        val queue = ArrayDeque<Pair<Kind, Boolean>>()               // kind, isNew
        for (j in jobs) queue.add(j.first to false)
        for (k in freshAllowed) queue.add(k to true)
        val deltas = jobs.associate { it.first to (it.second to it.third) }
        val taken = HashSet<Int>()

        fun step() {
            val next = queue.removeFirstOrNull()
            if (next == null) {
                release(account)
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogFiltersUpdated)
                return
            }
            if (fragment.parentActivity == null) { release(account); return }
            val (kind, isNew) = next

            val filter: MessagesController.DialogFilter
            val creating: Boolean
            val peers: ArrayList<Long>

            if (isNew) {
                val tracked = createdIds(account).toSet()
                val known = titlesFor(kind)
                val adopted = controller.dialogFilters
                    .firstOrNull { it != null && !it.isDefault && it.name in known && it.id !in tracked && it.id !in taken }
                creating = adopted == null
                filter = adopted ?: MessagesController.DialogFilter()
                if (creating) {
                    filter.id = freeFilterId(account, taken)
                    filter.neverShow = ArrayList()
                    filter.pinnedDialogs = LongSparseIntArray()
                }
                filter.name = kind.title
                filter.flags = 0
                filter.color = kind.ordinal % 8
                peers = ArrayList((current[kind] ?: emptyList<Long>()).take(perFolder))
                filter.alwaysShow = peers
                val m = kindToFilter(account); m[kind] = filter.id; saveKindToFilter(account, m)
                FolderIcons.setIconRes(filter.id, kind.iconRes)
            } else {
                val existing = controller.dialogFiltersById.get(map[kind] ?: -1)
                if (existing == null) {
                    // Deleted by hand or on another device: forget it rather than recreating something the
                    // user got rid of on purpose. Safe to conclude that only because dialogFiltersLoaded
                    // was checked before we got here -- a missing filter would otherwise just mean "not
                    // loaded yet".
                    val m = kindToFilter(account); m.remove(kind); saveKindToFilter(account, m)
                    val sp = snapshot(account); sp[kind] = LinkedHashSet(); saveSnapshot(account, sp)
                    return step()
                }
                creating = false
                filter = existing
                val (added, removed) = deltas[kind] ?: (emptyList<Long>() to emptyList<Long>())
                peers = ArrayList(filter.alwaysShow)
                for (did in removed) peers.remove(did)
                for (did in added) if (!peers.contains(did) && peers.size < perFolder) peers.add(did)
            }
            taken.add(filter.id)

            val sp = snapshot(account)
            sp[kind] = LinkedHashSet(current[kind] ?: emptyList())
            saveSnapshot(account, sp)

            // A filter with no peers and no auto-include flags is rejected (FILTER_INCLUDE_EMPTY), and if it
            // ever were accepted it would empty a folder the user is relying on. Whatever produced an empty
            // list, sending it is never the right move -- leave the folder as it is.
            if (peers.isEmpty()) {
                FileLog.d("Novagram folders: " + kind.name + " computed an EMPTY peer list -- skipping")
                return step()
            }
            alignAuxLists(filter, peers)
            FilterCreateActivity.saveFilterToServer(
                filter, filter.flags, filter.name, filter.entities, filter.title_noanimate, filter.color,
                peers, filter.neverShow, filter.pinnedDialogs,
                /* creatingNew */ creating, /* atBegin */ false, /* hasUserChanged */ true,
                /* resetUnreadCounter */ false, /* progress */ false, fragment
            ) { step() }
        }
        step()
    }

    /**
     * Delete one folder from the account and from this device. Shared by [remove] and [refresh] so there is a
     * single place that knows a body-less TL_messages_updateDialogFilter means "delete" -- the same call
     * FilterCreateActivity makes -- and a single place that remembers to clear our icon override.
     *
     * [onResult] is always invoked, on the UI thread, with false only when the SERVER refused: a filter that
     * is already gone is a success, because the caller wanted it gone.
     */
    private fun deleteFilterOnServer(fragment: BaseFragment, account: Int, id: Int, onResult: (Boolean) -> Unit) {
        val controller = MessagesController.getInstance(account)
        val filter = controller.dialogFiltersById.get(id)
        if (filter == null) {
            onResult(true)   // already gone (deleted by hand, or on another device) -- nothing to undo
            return
        }
        val req = TLRPC.TL_messages_updateDialogFilter()
        req.id = id
        fragment.connectionsManager.sendRequest(req) { _, error ->
            org.telegram.messenger.AndroidUtilities.runOnUIThread {
                if (error == null) {
                    controller.removeFilter(filter)
                    org.telegram.messenger.MessagesStorage.getInstance(account).deleteDialogFilter(filter)
                    FolderIcons.setIconRes(id, 0)   // 0 is not in ICONS -> clears our override
                    onResult(true)
                } else {
                    FileLog.d("Novagram folders: delete of filter " + id + " failed: " + error.text)
                    onResult(false)
                }
            }
        }
    }

    /**
     * Delete the folders this feature made, on the server and locally.
     *
     * Two sources, because the tracked ids are not always the whole story. Anything our own bookkeeping
     * lost — a rename, a language switch, a reinstall, or one of my own bugs earlier in this feature's
     * life — is still on the account under a title only we generate, and leaving those behind means the
     * user has to go and delete them by hand. So the tracked ids come first and then untracked folders
     * whose title is one of ours are swept too. Titles are specific enough that a collision with a folder
     * the user named themselves is unlikely, and this only ever runs from an explicit, confirmed "remove".
     */
    fun remove(fragment: BaseFragment, account: Int, onDone: () -> Unit) {
        if (account !in busy.indices || claimed(account)) return
        claim(account)
        val controller = MessagesController.getInstance(account)
        val ids = LinkedHashSet(createdIds(account))
        val allKnown = Kind.entries.flatMap { titlesFor(it) }.toSet()
        for (f in ArrayList(controller.dialogFilters)) {
            if (f != null && !f.isDefault && f.name in allKnown) ids.add(f.id)
        }
        val queue = ArrayDeque(ids)
        val failed = LinkedHashSet<Int>()
        FileLog.d("Novagram folders: removing " + ids.size + " folder(s): " + ids)

        fun step() {
            val id = queue.removeFirstOrNull()
            if (id == null) {
                // Keep tracking anything the server refused to delete. Forgetting it would turn a folder we
                // made into an untracked stray that the next enable cannot adopt -- which is how a "remove"
                // that half-failed turns into two sets of folders.
                if (failed.isEmpty()) {
                    prefs().edit().remove(keyIds(account)).remove(keySnap(account)).remove(keyUid(account)).apply()
                } else {
                    FileLog.d("Novagram folders: remove kept " + failed.size + " id(s) the server refused: " + failed)
                    val keep = kindToFilter(account).filterValues { it in failed }
                    saveKindToFilter(account, keep)
                }
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogFiltersUpdated)
                release(account)
                onDone()
                return
            }
            deleteFilterOnServer(fragment, account, id) { ok ->
                // Dropping it locally on a failure was the bug: the folder is still on the account, comes
                // back with the next getDialogFilters, and by then we have forgotten it.
                if (!ok) failed.add(id)
                step()
            }
        }
        step()
    }
}
