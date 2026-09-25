package org.fenixuz.utils

import android.content.Context
import android.os.Bundle
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.ui.Cells.DialogCell
import org.telegram.ui.DialogsActivity
import org.telegram.ui.ProfileActivity

/**
 * Novagram: tapping the round avatar in the chat list opens that chat's PROFILE instead of the chat.
 *
 * Getting to a profile otherwise costs three taps — open the chat, tap the header, land on the profile.
 * This makes it one.
 *
 * **It hangs off the avatar's own touch handling, not off the row click, and that placement is the feature.**
 * `StoriesUtilities.AvatarStoryParams.checkOnTouchEvent` already owns presses inside the avatar circle: once
 * [wantsAvatarTap] says yes, it sets up a `ButtonBounce`, calls `requestDisallowInterceptTouchEvent(true)` and
 * swallows the touch. So the avatar alone animates, the RecyclerView never draws its row selector, and
 * `onItemClick` is never reached. The first version of this feature hooked `onItemClick` instead and the whole
 * row lit up on every avatar tap — correct behaviour, wrong feedback, because the row was telling the user it
 * had been tapped when something else had happened.
 *
 * Precedence is decided by [openProfile] answering false and letting upstream continue:
 *
 *     selection mode  -> checkbox        (refused in wantsAvatarTap)
 *     linked community-> CommunitySheet  (DialogCell answers before us)
 *     story ring      -> story           (DialogCell answers after us, we decline when one is drawn)
 *     us              -> PROFILE
 *     anything else   -> chat, unchanged
 *
 * Default OFF, and deliberately so. This changes what a tap on the avatar does, against years of "tap anywhere
 * on the row, get the chat". Letting the people who want it opt in is the direction that can still be
 * reversed; shipping it as the default and walking it back is not.
 */
object AvatarOpensProfile {

    private const val PREF = "db"
    private const val KEY = "avatar_opens_profile"

    @Volatile private var loaded = false
    @Volatile private var enabled = false

    private fun prefs() =
        ApplicationLoader.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            enabled = prefs().getBoolean(KEY, false)
            loaded = true
        }
    }

    @JvmStatic
    fun isEnabled(): Boolean {
        ensureLoaded()
        return enabled
    }

    @JvmStatic
    @Synchronized
    fun toggle(): Boolean {
        ensureLoaded()
        enabled = !enabled
        prefs().edit().putBoolean(KEY, enabled).apply()
        return enabled
    }

    /**
     * Whether this cell's avatar should behave as its own button.
     *
     * Called from `isAvatarClickable` on every ACTION_DOWN inside the avatar, so it stays cheap: a volatile
     * read, then field reads off the cell. It must answer false for anything [openProfile] would refuse,
     * because saying yes here swallows the touch — claiming the avatar and then declining to act would eat
     * the tap and look like the app had frozen.
     */
    @JvmStatic
    fun wantsAvatarTap(fragment: DialogsActivity?, cell: DialogCell?): Boolean =
        target(fragment, cell) != 0L

    /**
     * Open the profile, and report whether we did. False leaves upstream's own handling to run.
     */
    @JvmStatic
    fun openProfile(fragment: DialogsActivity?, cell: DialogCell?): Boolean {
        val did = target(fragment, cell)
        if (did == 0L) return false
        val args = Bundle()
        if (DialogObject.isUserDialog(did)) {
            args.putLong("user_id", did)
        } else {
            args.putLong("chat_id", -did)
        }
        fragment!!.presentFragment(ProfileActivity(args))
        return true
    }

    /**
     * The peer whose profile this cell's avatar should open, or 0 for "leave it alone".
     *
     * Everything not enumerated here keeps its old behaviour rather than breaking in a new way.
     */
    private fun target(fragment: DialogsActivity?, cell: DialogCell?): Long {
        if (!isEnabled()) return 0L
        if (fragment == null || cell == null) return 0L
        // Selection mode: the avatar is a checkbox, and a tap there has to go on meaning "select".
        if (fragment.actionBar?.isActionModeShowed == true) return 0L
        // A cell standing in for a message hit (search results) is not a peer we can profile.
        if (cell.messageId != 0) return 0L
        if (cell.currentDialogFolderId != 0) return 0L      // the Archive row is not a peer

        val did = cell.dialogId
        if (did == 0L) return 0L
        if (DialogObject.isEncryptedDialog(did)) return 0L  // secret chats have no profile of their own

        val account = fragment.currentAccount
        return if (DialogObject.isUserDialog(did)) {
            // Saved Messages and the Replies pseudo-chat look like user dialogs but are neither: the first
            // should stay the user's own archive, the second has no profile at all.
            when {
                did == UserConfig.getInstance(account).clientUserId -> 0L
                UserObject.isReplyUser(did) -> 0L
                MessagesController.getInstance(account).getUser(did) == null -> 0L
                else -> did
            }
        } else {
            if (MessagesController.getInstance(account).getChat(-did) == null) 0L else did
        }
    }
}
