package org.fenixuz.utils

import android.content.Context
import android.os.Bundle
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Cells.DialogCell
import org.telegram.ui.ProfileActivity

/**
 * Novagram: tapping the round avatar in the chat list opens that chat's PROFILE instead of the chat.
 *
 * Getting to a profile otherwise costs three taps -- open the chat, tap the header, land on the profile.
 * This makes it one. The hit area and the touch plumbing already existed; what this adds is a branch and,
 * mostly, the rules about when NOT to take it.
 *
 * **The avatar was already an overloaded control**, which is why the order matters. `DialogCell.onTouchEvent`
 * hands the event to `StoriesUtilities.AvatarStoryParams.checkOnTouchEvent` first, and that consumes the tap
 * for a story ring or a linked-community badge -- in those cases `onItemClick` never runs and we are never
 * asked. So the precedence falls out naturally:
 *
 *     selection mode -> checkbox      (handled by DialogsActivity before us)
 *     story ring     -> story         (consumed in DialogCell)
 *     community      -> sheet         (consumed in DialogCell)
 *     us             -> PROFILE
 *     anything else  -> chat, unchanged
 *
 * One consequence worth stating rather than discovering later: somebody who has posted a story cannot be
 * reached this way while the ring is up. That is the correct trade -- the story is the more time-sensitive
 * thing and it is what upstream already promises -- but it does mean the gesture is not universal.
 *
 * Default OFF, and deliberately so. This changes what a tap on roughly a sixth of the row does, and users
 * have years of "tap anywhere on the row, get the chat" behind them. A toggle lets the people who want it
 * have it without re-training everyone else; if it proves popular it can become the default later, which is
 * a far nicer direction to move in than walking back a default that annoyed people.
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
     * Open the profile for the cell that was tapped, and report whether we did.
     *
     * Returns false for everything it is not sure about, and the caller then carries on and opens the chat
     * exactly as before -- so every case not enumerated here keeps its old behaviour rather than breaking in
     * a new way.
     *
     * [x] and [y] are cell-local, the same coordinates `onItemLongClick` already feeds to
     * [DialogCell.isPointInsideAvatar] for the preview sheet, so the tap and long-press agree on where the
     * avatar is.
     */
    @JvmStatic
    fun handleTap(fragment: BaseFragment?, view: android.view.View?, x: Float, y: Float): Boolean {
        if (!isEnabled()) return false
        if (fragment == null || view !is DialogCell) return false
        if (!view.isPointInsideAvatar(x, y)) return false
        // A cell standing in for a message hit (search results) or a topic is not a peer we can profile.
        if (view.messageId != 0) return false
        if (view.currentDialogFolderId != 0) return false   // the Archive row is not a peer

        val did = view.dialogId
        if (did == 0L) return false
        if (DialogObject.isEncryptedDialog(did)) return false   // secret chats have no profile of their own

        val account = fragment.currentAccount
        val args = Bundle()
        if (DialogObject.isUserDialog(did)) {
            // Saved Messages and the Replies pseudo-chat look like user dialogs but are neither: the first
            // should stay the user's own archive, the second has no profile at all.
            if (did == UserConfig.getInstance(account).clientUserId) return false
            if (UserObject.isReplyUser(did)) return false
            if (MessagesController.getInstance(account).getUser(did) == null) return false
            args.putLong("user_id", did)
        } else {
            if (MessagesController.getInstance(account).getChat(-did) == null) return false
            args.putLong("chat_id", -did)
        }
        fragment.presentFragment(ProfileActivity(args))
        return true
    }
}
