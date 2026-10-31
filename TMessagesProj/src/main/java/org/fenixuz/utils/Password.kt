package org.fenixuz.utils

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.fenixuz.ui.lock.LockCredential
import org.fenixuz.ui.lock.LockEditor
import org.fenixuz.ui.lock.LockTexts
import org.telegram.messenger.ApplicationLoader

/**
 * Chat-lock store supporting TWO modes per chat:
 *  - COMMON: one shared passcode protects a set of chats (cheap to add more — no new passcode).
 *  - INDIVIDUAL: a chat carries its OWN passcode, independent of every other chat.
 *
 * A chat is locked if it is in either bucket. When a locked chat is opened,
 * [org.fenixuz.ui.secret_chat.SecretLockScreenDialog] is shown with [credentialFor], which resolves
 * to the chat's individual passcode when it has one, otherwise the common passcode.
 *
 * State lives on-device in the shared "db" preferences (survives logout, no network). A cached
 * [lockedIdsArray] (union of both buckets) keeps [isLocked] an O(1)-ish lookup on the ChatActivity
 * open path with no JSON parse; writes are @Synchronized and rebuild the cache.
 */
object Password {

    private const val PASSWORD_LOCATE = "chat_lock_password"      // the shared/common passcode
    private const val IDS_LOCATE = "chat_lock_ids"                // ids locked with the common passcode
    private const val INDIVIDUAL_LOCATE = "chat_lock_individual"  // id -> its own passcode
    // Not "chat_lock_fingerprint": the old opt-in switch stored false there, and that would turn it off again.
    private const val FINGERPRINT_UNLOCK_LOCATE = "chat_lock_biometric"
    private const val VIBRATE_LOCATE = "chat_lock_vibrate"

    private val sharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences("db", Context.MODE_PRIVATE)
    private val gson = Gson()

    /** Chats locked with the shared passcode. */
    private val commonIds: MutableSet<Long> = loadCommonIds()

    /** Chats that carry their own passcode. */
    private val individualPasswords: MutableMap<Long, ChatLockPassword> = loadIndividual()

    /** Union of both buckets, snapshotted for the lock-free hot path. */
    @Volatile
    private var lockedIdsArray: LongArray = computeUnion()

    private fun computeUnion(): LongArray {
        val all = HashSet<Long>(commonIds.size + individualPasswords.size)
        all.addAll(commonIds)
        all.addAll(individualPasswords.keys)
        return all.toLongArray()
    }

    // region common passcode

    fun changeCommonPassword(password: String, type: Int, fingerPrint: Boolean) {
        val json = gson.toJson(ChatLockPassword(password, type, fingerPrint))
        sharedPreferences.edit().putString(PASSWORD_LOCATE, json).apply()
    }

    fun getCommonPassword(): ChatLockPassword? {
        val json = sharedPreferences.getString(PASSWORD_LOCATE, null)
        if (json.isNullOrEmpty()) {
            return null
        }
        return try {
            gson.fromJson(json, ChatLockPassword::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun hasCommonPassword(): Boolean = getCommonPassword() != null

    private fun checkCommon(input: String): Boolean = getCommonPassword()?.password == input

    // endregion

    // region hot path / queries

    /** O(1)-ish check used on the chat-open path. */
    fun isLocked(dialogId: Long): Boolean = lockedIdsArray.contains(dialogId)

    /** True when the chat carries its own (individual) passcode rather than the shared one. */
    fun isIndividual(dialogId: Long): Boolean = individualPasswords.containsKey(dialogId)

    /** Whether any chat is locked at all (either bucket). */
    fun hasAnyLock(): Boolean = lockedIdsArray.isNotEmpty()

    // endregion

    // region fingerprint (offered on every locked chat when the device supports biometrics, unless turned off)

    /**
     * Whether locked chats also open with a fingerprint. On unless the user turns it off: any finger saved on the phone
     * opens them, so whoever shares the phone may want the code alone.
     */
    var fingerprintUnlock: Boolean
        get() = sharedPreferences.getBoolean(FINGERPRINT_UNLOCK_LOCATE, true)
        set(value) {
            sharedPreferences.edit().putBoolean(FINGERPRINT_UNLOCK_LOCATE, value).apply()
        }

    /** Whether the locked chats' code screen vibrates on key presses and on a wrong code. On unless turned off. */
    var vibrate: Boolean
        get() = sharedPreferences.getBoolean(VIBRATE_LOCATE, true)
        set(value) {
            sharedPreferences.edit().putBoolean(VIBRATE_LOCATE, value).apply()
        }

    /** Whether the device can actually do strong biometric auth — drives whether fingerprint is offered. */
    fun isFingerprintHardwareAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < 23) {
            return false
        }
        return try {
            BiometricManager.from(ApplicationLoader.applicationContext)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        } catch (e: Exception) {
            false
        }
    }

    // endregion

    // region lock / unlock

    @Synchronized
    fun addCommonLock(dialogId: Long) {
        if (commonIds.add(dialogId)) {
            persist()
        }
    }

    @Synchronized
    fun addIndividualLock(dialogId: Long, password: String, type: Int, fingerPrint: Boolean) {
        // An individual lock owns the chat exclusively — drop any stale common membership.
        commonIds.remove(dialogId)
        individualPasswords[dialogId] = ChatLockPassword(password, type, fingerPrint)
        persist()
    }

    /** Unlock a chat, whichever bucket it was in. */
    @Synchronized
    fun unlock(dialogId: Long) {
        var changed = commonIds.remove(dialogId)
        if (individualPasswords.remove(dialogId) != null) {
            changed = true
        }
        if (changed) {
            persist()
        }
    }

    /** Disable chat lock entirely: drop the shared passcode and unlock every chat (both buckets). */
    @Synchronized
    fun removeEverything() {
        commonIds.clear()
        individualPasswords.clear()
        sharedPreferences.edit().remove(PASSWORD_LOCATE).apply()
        persist()
    }

    private fun persist() {
        lockedIdsArray = computeUnion()
        sharedPreferences.edit()
            .putString(IDS_LOCATE, gson.toJson(commonIds.toLongArray()))
            .putString(INDIVIDUAL_LOCATE, gson.toJson(individualPasswords))
            .apply()
    }

    private fun loadCommonIds(): MutableSet<Long> {
        val json = sharedPreferences.getString(IDS_LOCATE, null)
        if (json.isNullOrEmpty()) {
            return mutableSetOf()
        }
        return try {
            (gson.fromJson(json, LongArray::class.java) ?: LongArray(0)).toMutableSet()
        } catch (e: Exception) {
            mutableSetOf()
        }
    }

    private fun loadIndividual(): MutableMap<Long, ChatLockPassword> {
        val json = sharedPreferences.getString(INDIVIDUAL_LOCATE, null)
        if (json.isNullOrEmpty()) {
            return mutableMapOf()
        }
        return try {
            val type = object : TypeToken<HashMap<Long, ChatLockPassword>>() {}.type
            gson.fromJson<HashMap<Long, ChatLockPassword>>(json, type) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    // endregion

    // region lock-core adapters

    /** Credential the lock screen verifies against: the chat's own passcode if any, else the common one. */
    fun credentialFor(dialogId: Long): LockCredential {
        val individual = individualPasswords[dialogId]
        return if (individual != null) {
            object : LockCredential {
                override val type: Int? get() = individual.type
                // Fingerprint is offered whenever the device supports strong biometrics, unless the user turned it
                // off for all locked chats ([fingerprintUnlock]). The passcode always remains as the fallback.
                override val fingerPrint: Boolean get() = fingerprintUnlock && isFingerprintHardwareAvailable()
                override fun check(input: String): Boolean = individual.password == input
                override val vibrate: Boolean get() = Password.vibrate
                override val texts: LockTexts get() = lockTexts(individual = true)
            }
        } else {
            object : LockCredential {
                override val type: Int? get() = getCommonPassword()?.type
                override val fingerPrint: Boolean get() = fingerprintUnlock && isFingerprintHardwareAvailable()
                override fun check(input: String): Boolean = checkCommon(input)
                override val vibrate: Boolean get() = Password.vibrate
                override val texts: LockTexts get() = lockTexts(individual = false)
            }
        }
    }

    /**
     * Asks for a chat-lock code and never offers the fingerprint: for opening the chat-lock settings, which change what
     * the locks accept, and a finger saved on the phone must not be enough for that. The common password when there is
     * one, else the code of any chat locked on its own. Null when no code is set.
     */
    fun codeCredential(): LockCredential? {
        val common = getCommonPassword()
        val individual = individualPasswords.values.toList()
        if (common == null && individual.isEmpty()) {
            return null
        }
        return object : LockCredential {
            override val type: Int? get() = common?.type ?: individual.first().type
            override val fingerPrint: Boolean get() = false
            override fun check(input: String): Boolean =
                if (common != null) common.password == input else individual.any { it.password == input }
            override val vibrate: Boolean get() = Password.vibrate
            override val texts: LockTexts get() = lockTexts(individual = false, title = 252)
        }
    }

    /**
     * Editor for locking [dialogId] with the SHARED passcode for the first time: [LockEditor.save] stores
     * the common passcode AND locks the chat atomically, so a cancelled creation never leaves a chat
     * "locked" with no passcode.
     */
    fun editorForCommonLock(dialogId: Long): LockEditor = object : LockEditor {
        override fun hasPassword(): Boolean = getCommonPassword() != null
        override fun currentType(): Int? = getCommonPassword()?.type
        override fun currentFingerPrint(): Boolean = getCommonPassword()?.fingerPrint ?: false
        override fun check(input: String): Boolean = checkCommon(input)
        override fun save(password: String, type: Int, fingerPrint: Boolean) {
            changeCommonPassword(password, type, fingerPrint)
            addCommonLock(dialogId)
        }
        override fun remove() = removeEverything()
        override val texts: LockTexts get() = lockTexts(individual = false)
    }

    /**
     * Editor for locking [dialogId] with its OWN passcode: [LockEditor.save] stores a per-chat passcode
     * (and locks the chat) without touching the common passcode or any other chat.
     */
    fun editorForIndividualLock(dialogId: Long): LockEditor = object : LockEditor {
        override fun hasPassword(): Boolean = individualPasswords.containsKey(dialogId)
        override fun currentType(): Int? = individualPasswords[dialogId]?.type
        override fun currentFingerPrint(): Boolean = individualPasswords[dialogId]?.fingerPrint ?: false
        override fun check(input: String): Boolean = individualPasswords[dialogId]?.password == input
        override fun save(password: String, type: Int, fingerPrint: Boolean) {
            addIndividualLock(dialogId, password, type, fingerPrint)
        }
        override fun remove() = unlock(dialogId)
        override val texts: LockTexts get() = lockTexts(individual = true)
    }

    /**
     * Editor for managing the shared/common passcode itself (not tied to locking a specific chat):
     * save() overwrites the common passcode — every chat on the common passcode immediately uses the
     * new one (they read it live, there are no per-chat copies to re-sync). remove() disables chat
     * lock entirely. Used by the "Change common password" row.
     */
    fun editorForCommonPassword(): LockEditor = object : LockEditor {
        override fun hasPassword(): Boolean = getCommonPassword() != null
        override fun currentType(): Int? = getCommonPassword()?.type
        override fun currentFingerPrint(): Boolean = getCommonPassword()?.fingerPrint ?: false
        override fun check(input: String): Boolean = checkCommon(input)
        override fun save(password: String, type: Int, fingerPrint: Boolean) {
            changeCommonPassword(password, type, fingerPrint)
        }
        override fun remove() = removeEverything()
        override val texts: LockTexts get() = lockTexts(individual = false)
    }

    /**
     * The lock screens' wording for locked chats: Telegram's speaks of unlocking the app and of reinstalling it. A chat
     * on its own code ([individual]) is "this chat" when the code is created; the shared code opens all the others.
     * [title] is "Chat is locked" over a locked chat, "Chat lock" over the settings that ask for the code.
     */
    private fun lockTexts(individual: Boolean, title: Int = 457) = LockTexts(
        title = LanguageCode.getMyTitles(title),
        enterCode = LanguageCode.getMyTitles(450),
        enterCodeOrFingerprint = LanguageCode.getMyTitles(451),
        createPin = LanguageCode.getMyTitles(if (individual) 460 else 458),
        createPassword = LanguageCode.getMyTitles(if (individual) 461 else 459),
        forgotCode = LanguageCode.getMyTitles(462),
    )

    // endregion
}

data class ChatLockPassword(val password: String, val type: Int, val fingerPrint: Boolean)
