package org.fenixuz.ui.create_folder_dialog

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig

/**
 * Custom folder-tab icons.
 *
 * Performance-clean by design: [iconMode] and the folder→icon map are cached IN MEMORY (loaded once),
 * so the render path ([isIconMode], [getTabIcon]) does O(1) reads — there are NO SharedPreferences reads
 * or JSON parsing per frame (unlike the original pro implementation).
 *
 * Storage is INDEX-based (the index into [ICONS]) rather than raw R.drawable ids, so saved choices stay
 * correct across app updates (resource ids are not stable across builds).
 */
object FolderIcons {

    /**
     * Fixed icon palette — Telegram's own thin/consistent icons (24dp, uniform style).
     * The stored value per folder is the index into this array.
     */
    val ICONS = intArrayOf(
        R.drawable.msg_folders, R.drawable.msg_folders_private, R.drawable.msg_contacts,
        R.drawable.msg_folders_groups, R.drawable.msg_groups, R.drawable.msg_folders_channels,
        R.drawable.msg_channel, R.drawable.msg_folders_bots, R.drawable.msg_bots,
        R.drawable.msg_folders_read, R.drawable.msg_folders_muted, R.drawable.msg_mute,
        R.drawable.msg_folders_archive, R.drawable.msg_folders_requests, R.drawable.msg_fave,
        R.drawable.msg_saved, R.drawable.msg_home, R.drawable.msg_calls,
        R.drawable.msg_video, R.drawable.msg_photos, R.drawable.msg_location,
        R.drawable.msg_link2, R.drawable.msg_secret, R.drawable.msg_edit
    )

    val DEFAULT_ICON = R.drawable.msg_folders

    private val ctx: Context = ApplicationLoader.applicationContext
    private val prefs = ctx.getSharedPreferences("db", Context.MODE_PRIVATE)

    @Volatile
    private var iconMode: Boolean = prefs.getBoolean("folder_icon_mode", false)

    /** filterId -> icon res id. Loaded once, kept in memory. Persisted by resource NAME (stable across builds & palette changes). */
    private val folderToIcon = HashMap<Int, Int>()

    /** Resource name -> res id for the current palette (so stored names survive any palette reorder). */
    private val nameToRes: Map<String, Int> by lazy {
        ICONS.associateBy { ctx.resources.getResourceEntryName(it) }
    }

    init {
        val saved = prefs.getString("folder_icons", "").orEmpty()
        if (saved.isNotEmpty()) {
            for (pair in saved.split(";")) {
                val kv = pair.split(":")
                if (kv.size == 2) {
                    val id = kv[0].toIntOrNull()
                    val res = nameToRes[kv[1]]
                    if (id != null && res != null) {
                        folderToIcon[id] = res
                    }
                }
            }
        }
    }

    /** O(1), safe to call from the render loop. */
    fun isIconMode(): Boolean = iconMode

    fun setIconMode(enabled: Boolean) {
        iconMode = enabled
        prefs.edit().putBoolean("folder_icon_mode", enabled).apply()
    }

    /** The custom icon res chosen for this folder, or 0 if none (uses a type default then). */
    fun getSelectedIconRes(filterId: Int): Int = folderToIcon[filterId] ?: 0

    fun setIconRes(filterId: Int, iconRes: Int) {
        if (ICONS.contains(iconRes)) {
            folderToIcon[filterId] = iconRes
        } else {
            folderToIcon.remove(filterId)
        }
        save()
    }

    private fun save() {
        val sb = StringBuilder()
        for ((id, res) in folderToIcon) {
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(id).append(':').append(ctx.resources.getResourceEntryName(res))
        }
        prefs.edit().putString("folder_icons", sb.toString()).apply()
    }

    /**
     * Drawable for a folder TAB. The tab id passed by DialogsActivity is the POSITION in the dialog-filters
     * list (not the filter's real id), so we resolve the filter by position, then use filter.id for the custom
     * icon and filter.flags for the type default. Built once when a tab is (re)added, NOT per frame.
     */
    fun getTabIcon(tabPosition: Int): Drawable? {
        val filter = filterAtPosition(tabPosition)
        val res = filter?.id?.let { folderToIcon[it] } ?: iconByFilter(filter)
        return ContextCompat.getDrawable(ctx, res)
    }

    private fun filterAtPosition(position: Int): MessagesController.DialogFilter? {
        // Same list (and order) DialogsActivity uses when adding tabs by position.
        val filters = MessagesController.getInstance(UserConfig.selectedAccount).getDialogFilters()
        return if (position in 0 until filters.size) filters[position] else null
    }

    private fun iconByFilter(filter: MessagesController.DialogFilter?): Int {
        if (filter == null || filter.isDefault) return DEFAULT_ICON
        val f = filter.flags
        val all = MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS
        return when {
            (f and all) == (MessagesController.DIALOG_FILTER_FLAG_CONTACTS or MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS) -> R.drawable.msg_contacts
            (f and all) == MessagesController.DIALOG_FILTER_FLAG_CHANNELS -> R.drawable.msg_folders_channels
            (f and all) == MessagesController.DIALOG_FILTER_FLAG_GROUPS -> R.drawable.msg_folders_groups
            (f and all) == MessagesController.DIALOG_FILTER_FLAG_CONTACTS -> R.drawable.msg_contacts
            (f and all) == MessagesController.DIALOG_FILTER_FLAG_BOTS -> R.drawable.msg_folders_bots
            else -> DEFAULT_ICON
        }
    }
}
