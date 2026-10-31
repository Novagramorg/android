package org.fenixuz.ui.lock

/**
 * Tiny abstractions that let one lock UI serve several features (the secret folder, per-chat lock, …)
 * without duplicating the ~2k-line passcode view. A feature supplies a [LockCredential] for the lock
 * screen and a [LockEditor] for the passcode creator; nothing else about those screens is feature-aware.
 */

/** What the lock screen needs to render and verify a passcode. */
interface LockCredential {
    /** SharedConfig.PASSCODE_TYPE_PIN / PASSCODE_TYPE_PASSWORD, or null when none is set. */
    val type: Int?

    /** Whether fingerprint unlock is enabled for this credential. */
    val fingerPrint: Boolean

    /** True when [input] matches the stored passcode. */
    fun check(input: String): Boolean

    /** Whether the lock screen vibrates on key presses and on a wrong passcode. */
    val vibrate: Boolean get() = true

    /**
     * The feature's own wording for the lock screen, or null for Telegram's. No default on purpose, here and in
     * [LockEditor]: every implementor states it, so a change here always recompiles them (a default member once left
     * implementors stale after an incremental build, and they crashed with AbstractMethodError).
     */
    val texts: LockTexts?
}

/**
 * This credential without fingerprint unlock: for changing what a lock accepts (its code, fingerprint unlock, the lock
 * itself), which a finger saved on the phone must not be enough for.
 */
fun LockCredential.codeOnly(): LockCredential {
    val base = this
    return object : LockCredential {
        override val type: Int? get() = base.type
        override val fingerPrint: Boolean get() = false
        override fun check(input: String): Boolean = base.check(input)
        override val vibrate: Boolean get() = base.vibrate
        override val texts: LockTexts? get() = base.texts
    }
}

/**
 * A feature's own wording for the lock screens, instead of Telegram's app-lock texts ("Unlock to use Novagram",
 * "you'll need to reinstall the app"…), which are wrong for a lock inside the app.
 */
class LockTexts(
    /** Lock screen title, also the fingerprint prompt's. */
    val title: String,
    /** The line under the lock screen title, without and with fingerprint unlock. */
    val enterCode: String,
    val enterCodeOrFingerprint: String,
    /** Code creation: the line under the title, for a PIN and for a password. */
    val createPin: String,
    val createPassword: String,
    /** Code creation, second step: what to do if the code is forgotten. */
    val forgotCode: String,
    /** Turning the lock off, which asks for the current code first: the line under the title. Null for Telegram's. */
    val changeInfo: String? = null,
)

/** What the passcode-creation screen needs to read/write a passcode for one feature. */
interface LockEditor {
    fun hasPassword(): Boolean

    /** Current passcode type (PIN/password), or null when none is set — drives the confirm-step UI. */
    fun currentType(): Int?

    fun currentFingerPrint(): Boolean

    fun check(input: String): Boolean

    fun save(password: String, type: Int, fingerPrint: Boolean)

    /** Remove the passcode (used by the "turn off" / disable flow). */
    fun remove()

    /** The feature's own wording for the code creation screen, or null for Telegram's. See [LockCredential.texts]. */
    val texts: LockTexts?
}
