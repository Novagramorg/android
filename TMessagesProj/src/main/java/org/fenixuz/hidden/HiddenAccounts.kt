package org.fenixuz.hidden

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.text.SpannableStringBuilder
import android.util.Base64
import org.fenixuz.ui.lock.LockCredential
import org.fenixuz.ui.lock.LockEditor
import org.fenixuz.ui.lock.LockTexts
import org.fenixuz.ui.secret_chat.SecretLockScreenDialog
import org.fenixuz.utils.LanguageCode
import org.fenixuz.utils.Password
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.ChatsWidgetProvider
import org.telegram.messenger.ContactsController
import org.telegram.messenger.ContactsWidgetProvider
import org.telegram.messenger.FeedWidgetProvider
import org.telegram.messenger.FileLog
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.NotificationsController
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ColoredImageSpan
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.LaunchActivity
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Novagram: hidden accounts. The feature is turned on in Novagram settings by setting a code; then a long press on an
 * account in the account lists hides it. A hidden account is never listed with the others (only as the current account
 * while the user is in it): hidden accounts have a list of their own, [HiddenAccountsListScreen], which a long press on
 * "Add account" opens after the code. Entering the code unlocks hidden accounts until the app has been in the
 * background for [RELOCK_DELAY_MS], the screen turns off, or the process restarts; then the app leaves a hidden account
 * it is showing for a visible one, and the list needs the code again. With [hideOnBackground] off they stay unlocked
 * through all of that, until the lock button of the list.
 *
 * Accounts are remembered by Telegram user id, not by slot, so a reshuffle of slots never hides the wrong account,
 * and logging out of a hidden account forgets it: logging in again brings it back visible.
 *
 * There is one code for all hidden accounts. It is stored as a salted PBKDF2 hash, never as text.
 *
 * Outside the account lists a hidden account leaves no trace either, locked or not: it sends no notifications and rings
 * no calls, counts in no launcher badge, gets no launcher shortcuts, has no system account (Settings > Accounts, the
 * contacts app), shows nothing in widgets, and account pickers ([isListed]) leave it out.
 *
 * Main thread only, except [isHidden] / [isConcealed] / [isListed] / [listedCount], which background code
 * (notifications, contacts) may call: the set of ids is an immutable snapshot swapped on every change.
 */
object HiddenAccounts {

    private const val PREFS = "novagram_hidden_accounts"
    private const val KEY_IDS = "ids"
    private const val KEY_HASH = "code_hash"
    private const val KEY_SALT = "code_salt"
    private const val KEY_TYPE = "code_type"
    private const val KEY_LAST_VISIBLE = "last_visible"
    private const val KEY_FINGERPRINT = "fingerprint"
    private const val KEY_VIBRATE = "vibrate"
    private const val KEY_HIDE_ON_BACKGROUND = "hide_on_background"
    private const val KEY_KEPT_UNLOCKED = "kept_unlocked"

    private const val ITERATIONS = 10000
    private const val KEY_BITS = 256

    /** How long the app may stay in the background (a picker, a browser tab) before hidden accounts lock again. */
    const val RELOCK_DELAY_MS = 30_000L

    private val prefs: SharedPreferences
        get() = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var ids: Set<Long> = prefs.getStringSet(KEY_IDS, null)?.mapNotNull { it.toLongOrNull() }?.toHashSet() ?: emptySet()

    /**
     * True while hidden accounts are unlocked: they then show everywhere like visible ones. Every start begins locked,
     * unless [hideOnBackground] is off and they were unlocked when the app was left.
     */
    @JvmStatic
    @Volatile
    var unlocked = !prefs.getBoolean(KEY_HIDE_ON_BACKGROUND, true) && prefs.getBoolean(KEY_KEPT_UNLOCKED, false)
        private set

    private val relockRunnable = Runnable { lock() }

    // ---- state ----

    /** Whether [account] is a logged-in account the user has hidden (locked or not). */
    @JvmStatic
    fun isHidden(account: Int): Boolean {
        val hidden = ids
        if (hidden.isEmpty() || !UserConfig.isValidAccount(account)) return false
        val config = UserConfig.getInstance(account)
        return config.isClientActivated && hidden.contains(config.clientUserId)
    }

    /** Whether [account] must not be shown right now: hidden, and hidden accounts are locked. */
    @JvmStatic
    fun isConcealed(account: Int): Boolean = !unlocked && isHidden(account)

    /**
     * Whether [account] is offered by an account picker (which account opens a link, logs in to a site, joins a
     * channel...): a logged-in visible account, or the hidden one the user is in. The same rule as the account lists.
     */
    @JvmStatic
    fun isListed(account: Int): Boolean =
        UserConfig.getInstance(account).isClientActivated && (!isHidden(account) || account == UserConfig.selectedAccount)

    /** How many accounts are [isListed]: what "more than one account" means for anything the user sees. */
    @JvmStatic
    fun listedCount(): Int {
        var count = 0
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (isListed(a)) count++
        }
        return count
    }

    @JvmStatic
    fun hasHidden(): Boolean {
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (isHidden(a)) return true
        }
        return false
    }

    /**
     * Whether [account] may be hidden: at least one other logged-in account must stay visible, so the app always has an
     * account to show while hidden accounts are locked.
     */
    @JvmStatic
    fun canHide(account: Int): Boolean {
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (a != account && UserConfig.getInstance(a).isClientActivated && !isHidden(a)) return true
        }
        return false
    }

    /** Hides or shows [account]. Hiding needs a code ([hasCode]) and [canHide]; the caller checks both. */
    @JvmStatic
    fun setHidden(account: Int, hidden: Boolean) {
        if (!UserConfig.isValidAccount(account) || !UserConfig.getInstance(account).isClientActivated) return
        val userId = UserConfig.getInstance(account).clientUserId
        val updated = HashSet(ids)
        if (hidden) updated.add(userId) else updated.remove(userId)
        saveIds(updated)
        onHiddenChanged(account, hidden)
        if (hidden && !unlocked) {
            leaveConcealedAccount()
        }
    }

    /**
     * Adds "Hide" or "Show" for [account] to a long-press menu of the account lists. Hiding before the feature is on
     * opens its screen, which asks for a code first and then hides the account.
     */
    @JvmStatic
    fun addHideOption(o: ItemOptions, fragment: BaseFragment, account: Int) {
        if (isHidden(account)) {
            o.add(R.drawable.msg_view_file, LanguageCode.getMyTitles(442)) {
                setHidden(account, false)
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, LanguageCode.getMyTitles(444)).show()
            }
        } else {
            o.add(R.drawable.msg_archive_hide, LanguageCode.getMyTitles(441)) { requestHide(fragment, account) }
        }
    }

    /** Hides [account], or first opens the feature's screen to set a code when it is still off. */
    @JvmStatic
    fun requestHide(fragment: BaseFragment, account: Int) {
        if (!canHide(account)) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, LanguageCode.getMyTitles(432)).show()
            return
        }
        if (!hasCode()) {
            fragment.presentFragment(HiddenAccountsScreen(account))
            return
        }
        // Hiding the account on screen while hidden accounts are locked moves the app to a visible account, and the
        // screens of the old one close: no bulletin then.
        val leaving = account == UserConfig.selectedAccount && !unlocked
        setHidden(account, true)
        if (!leaving) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, LanguageCode.getMyTitles(433)).show()
        }
    }

    /** [name] with a small "hidden" mark after it when [account] is hidden (it is then only listed while unlocked). */
    @JvmStatic
    fun withHiddenMark(account: Int, name: CharSequence?): CharSequence? {
        if (name == null || !isHidden(account)) return name
        val sb = SpannableStringBuilder(name).append("  h")
        val span = ColoredImageSpan(R.drawable.msg_archive_hide)
        span.setSize(AndroidUtilities.dp(16f))
        sb.setSpan(span, sb.length - 1, sb.length, 0)
        return sb
    }

    /** Turns the feature off: every hidden account is shown again and the code is removed. */
    @JvmStatic
    fun disable() {
        AndroidUtilities.cancelRunOnUIThread(relockRunnable)
        unlocked = false
        prefs.edit().remove(KEY_HASH).remove(KEY_SALT).remove(KEY_TYPE).remove(KEY_FINGERPRINT).remove(KEY_VIBRATE)
            .remove(KEY_HIDE_ON_BACKGROUND).remove(KEY_KEPT_UNLOCKED).apply()
        val wereHidden = (0 until UserConfig.MAX_ACCOUNT_COUNT).filter { isHidden(it) }
        saveIds(emptySet())
        for (a in wereHidden) {
            onHiddenChanged(a, false)
        }
    }

    /** The account with user id [userId] is being logged out: it is no longer hidden (logging in again shows it). */
    @JvmStatic
    fun onLoggedOut(userId: Long) {
        if (userId == 0L || !ids.contains(userId)) return
        saveIds(HashSet(ids).apply { remove(userId) })
    }

    private fun saveIds(updated: Set<Long>) {
        ids = updated
        prefs.edit().putStringSet(KEY_IDS, updated.mapTo(HashSet()) { it.toString() }).apply()
        notifyChanged()
    }

    /** Brings what lives outside the app's screens (see the class comment) in line after [account] was hidden or shown. */
    private fun onHiddenChanged(account: Int, hidden: Boolean) {
        NotificationsController.getInstance(account).onHiddenChanged(hidden)
        ContactsController.getInstance(account).checkAppAccount()
        MediaDataController.getInstance(UserConfig.selectedAccount).buildShortcuts()
        updateWidgets()
    }

    /** Widgets of a hidden account show nothing (their services check [isHidden]); this makes them reload. */
    private fun updateWidgets() {
        try {
            val context = ApplicationLoader.applicationContext
            val manager = AppWidgetManager.getInstance(context) ?: return
            for (provider in arrayOf(ChatsWidgetProvider::class.java, ContactsWidgetProvider::class.java, FeedWidgetProvider::class.java)) {
                val ids = manager.getAppWidgetIds(ComponentName(context, provider))
                if (ids.isNotEmpty()) {
                    manager.notifyAppWidgetViewDataChanged(ids, R.id.list_view)
                }
            }
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    private fun notifyChanged() {
        AndroidUtilities.runOnUIThread {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.novagramHiddenAccountsChanged)
        }
    }

    // ---- locking ----

    @JvmStatic
    fun unlock() {
        AndroidUtilities.cancelRunOnUIThread(relockRunnable)
        if (!hideOnBackground) {
            prefs.edit().putBoolean(KEY_KEPT_UNLOCKED, true).apply()
        }
        if (unlocked) return
        unlocked = true
        notifyChanged()
    }

    /** Locks hidden accounts again and leaves the one on screen, if any. */
    @JvmStatic
    fun lock() {
        AndroidUtilities.cancelRunOnUIThread(relockRunnable)
        prefs.edit().remove(KEY_KEPT_UNLOCKED).apply()
        if (!unlocked) return
        unlocked = false
        notifyChanged()
        leaveConcealedAccount()
    }

    /** The app went to the background: lock after [RELOCK_DELAY_MS] unless it comes back first. */
    @JvmStatic
    fun onAppPaused() {
        AndroidUtilities.cancelRunOnUIThread(relockRunnable)
        if (unlocked && hideOnBackground) {
            AndroidUtilities.runOnUIThread(relockRunnable, RELOCK_DELAY_MS)
        }
    }

    @JvmStatic
    fun onAppResumed() {
        AndroidUtilities.cancelRunOnUIThread(relockRunnable)
    }

    @JvmStatic
    fun onScreenOff() {
        if (hideOnBackground) {
            lock()
        }
    }

    /**
     * Makes sure the selected account is not a concealed one: at process start (hidden accounts start locked) and
     * whenever LaunchActivity is created. Without an activity this only fixes the selection; LaunchActivity reads it.
     */
    @JvmStatic
    fun ensureSelectedVisible() {
        if (!isConcealed(UserConfig.selectedAccount)) return
        val target = visibleAccountOrFreeSlot()
        if (target < 0) return
        UserConfig.selectedAccount = target
        UserConfig.getInstance(0).saveConfig(false)
    }

    private fun leaveConcealedAccount() {
        if (!isConcealed(UserConfig.selectedAccount)) return
        val launchActivity = LaunchActivity.instance
        if (launchActivity != null) {
            launchActivity.leaveConcealedAccount()
        } else {
            ensureSelectedVisible()
        }
    }

    /** Remembers the last visible account the user was on, to go back to it when hidden accounts lock. */
    @JvmStatic
    fun onAccountSelected(account: Int) {
        if (!isHidden(account)) {
            prefs.edit().putInt(KEY_LAST_VISIBLE, account).apply()
            // A hidden account gets no launcher shortcuts: coming back from one, they are this account's again.
            if (hasHidden()) {
                MediaDataController.getInstance(account).buildShortcuts()
            }
        }
    }

    /** The visible account to show instead of a hidden one, or -1 when every logged-in account is hidden. */
    @JvmStatic
    fun visibleAccount(): Int {
        val last = prefs.getInt(KEY_LAST_VISIBLE, -1)
        if (UserConfig.isValidAccount(last) && UserConfig.getInstance(last).isClientActivated && !isHidden(last)) {
            return last
        }
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (UserConfig.getInstance(a).isClientActivated && !isHidden(a)) return a
        }
        return -1
    }

    /** [visibleAccount], or else a free slot so that the app shows the login screen; -1 when there is neither. */
    @JvmStatic
    fun visibleAccountOrFreeSlot(): Int {
        val visible = visibleAccount()
        if (visible >= 0) return visible
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (!UserConfig.getInstance(a).isClientActivated) return a
        }
        return -1
    }

    // ---- the code ----

    @JvmStatic
    fun hasCode(): Boolean = prefs.contains(KEY_HASH)

    /** Whether hidden accounts may also be unlocked with a fingerprint. Off unless the user turns it on. */
    @JvmStatic
    var fingerprint: Boolean
        get() = prefs.getBoolean(KEY_FINGERPRINT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FINGERPRINT, value).apply()
        }

    /** Whether the code screen vibrates on key presses and on a wrong code. On unless the user turns it off. */
    @JvmStatic
    var vibrate: Boolean
        get() = prefs.getBoolean(KEY_VIBRATE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_VIBRATE, value).apply()
        }

    /**
     * Whether hidden accounts lock again once the app is left: [RELOCK_DELAY_MS] in the background, the screen turning
     * off, a restart. On unless the user turns it off, like Graph's "Hide on background". Off, they stay unlocked (the app
     * reopens on the hidden account the user was in) until the lock button of [HiddenAccountsListScreen].
     */
    @JvmStatic
    var hideOnBackground: Boolean
        get() = prefs.getBoolean(KEY_HIDE_ON_BACKGROUND, true)
        set(value) {
            val edit = prefs.edit().putBoolean(KEY_HIDE_ON_BACKGROUND, value)
            if (value) {
                edit.remove(KEY_KEPT_UNLOCKED)
            } else if (unlocked) {
                edit.putBoolean(KEY_KEPT_UNLOCKED, true)
            }
            edit.apply()
        }

    private fun codeType(): Int = prefs.getInt(KEY_TYPE, SharedConfig.PASSCODE_TYPE_PIN)

    private fun checkCode(input: String): Boolean {
        val p = prefs
        val hash = p.getString(KEY_HASH, null) ?: return false
        val salt = p.getString(KEY_SALT, null) ?: return false
        return try {
            MessageDigest.isEqual(Base64.decode(hash, Base64.NO_WRAP), derive(input, Base64.decode(salt, Base64.NO_WRAP)))
        } catch (e: Exception) {
            FileLog.e(e)
            false
        }
    }

    private fun saveCode(code: String, type: Int) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_HASH, Base64.encodeToString(derive(code, salt), Base64.NO_WRAP))
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putInt(KEY_TYPE, type)
            .apply()
    }

    private fun derive(code: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(code.toCharArray(), salt, ITERATIONS, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * For the lock screen. Fingerprint only when the user turned it on: anyone whose finger the phone knows could
     * otherwise open hidden accounts.
     */
    @JvmStatic
    fun credential(): LockCredential = object : LockCredential {
        override val type: Int? get() = if (hasCode()) codeType() else null
        override val fingerPrint: Boolean get() = fingerprint && Password.isFingerprintHardwareAvailable()
        override val vibrate: Boolean get() = HiddenAccounts.vibrate
        override fun check(input: String): Boolean = checkCode(input)
        override val texts: LockTexts get() = lockTexts()
    }

    /** For the code creation screen. [onSaved] runs once a new code is stored. */
    @JvmStatic
    fun editor(onSaved: Runnable?): LockEditor = object : LockEditor {
        override fun hasPassword(): Boolean = hasCode()
        override fun currentType(): Int? = if (hasCode()) codeType() else null
        override fun currentFingerPrint(): Boolean = fingerprint
        override fun check(input: String): Boolean = checkCode(input)
        override fun save(password: String, type: Int, fingerPrint: Boolean) {
            saveCode(password, type)
            // Whoever just set the code may use it: hidden accounts are unlocked, like after entering it.
            unlock()
            onSaved?.run()
        }

        // Turning the feature off goes through [disable] (the settings screen), not the code screen.
        override fun remove() = Unit

        override val texts: LockTexts get() = lockTexts()
    }

    /** The lock screens' wording for hidden accounts: Telegram's speaks of unlocking the app and reinstalling it. */
    private fun lockTexts() = LockTexts(
        title = LanguageCode.getMyTitles(428),
        enterCode = LanguageCode.getMyTitles(450),
        enterCodeOrFingerprint = LanguageCode.getMyTitles(451),
        createPin = LanguageCode.getMyTitles(452),
        createPassword = LanguageCode.getMyTitles(453),
        forgotCode = LanguageCode.getMyTitles(435),
    )

    /**
     * Asks for the code over [fragment] and runs [onAccepted] as soon as it is right, under the fading lock screen: a
     * screen it opens (without animation) is what the lock screen reveals. Back closes the lock screen and leaves
     * [fragment] open. Returns false (and shows nothing) when no code has been set.
     */
    @JvmStatic
    fun askCode(fragment: BaseFragment, onAccepted: Runnable): Boolean {
        if (!hasCode()) return false
        val activity = fragment.parentActivity ?: return false
        val dialog = SecretLockScreenDialog(fragment, activity, credential(), onAccepted, false, SecretLockScreenDialog.UNLOCK_BEHIND)
        dialog.show()
        dialog.secretLockScreen.onShow(fingerprint, true, -1, -1, null, null)
        return true
    }

    /**
     * Login found that [account] is logged in already. When it is a hidden account and hidden accounts are locked, asks
     * for the code and opens it instead of saying so. Returns false (and shows nothing) otherwise: the caller then goes
     * on as usual.
     */
    @JvmStatic
    fun openFromLogin(fragment: BaseFragment, account: Int): Boolean {
        if (!isConcealed(account)) return false
        return askCode(fragment) {
            unlock()
            (fragment.parentActivity as? LaunchActivity)?.switchToAccount(account, true)
            fragment.finishFragment()
        }
    }

    /**
     * The long press on "Add account": opens the list of hidden accounts, after the code unless they are unlocked
     * already. Returns false (and does nothing) when the feature is off.
     */
    @JvmStatic
    fun askUnlock(fragment: BaseFragment): Boolean {
        if (!hasCode()) return false
        if (unlocked) {
            fragment.presentFragment(HiddenAccountsListScreen())
            return true
        }
        return askCode(fragment) {
            unlock()
            fragment.presentFragment(HiddenAccountsListScreen(), false, true)
        }
    }
}
