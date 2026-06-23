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
}

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
}
