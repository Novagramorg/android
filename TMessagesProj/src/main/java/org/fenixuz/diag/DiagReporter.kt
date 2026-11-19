package org.fenixuz.diag

/**
 * Where [FreezeWatchdog] sends what it finds. TMessagesProj itself has no Crashlytics: the app modules that ship it
 * (TMessagesProj_App, TMessagesProj_AppRustore) hand an implementation in through
 * ApplicationLoader.onCreateDiagReporter(). A build without one passes null and the watchdog never starts.
 */
interface DiagReporter {
    /** Records [error] as a non-fatal report; it is sent with the next batch, it never stops the app. */
    fun recordNonFatal(error: Throwable)

    /** A key=value attached to every later report of this process (crashes included), e.g. heap size. */
    fun setKey(key: String, value: String)
}
