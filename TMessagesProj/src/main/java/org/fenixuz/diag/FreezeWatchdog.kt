package org.fenixuz.diag

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.FileLog
import org.telegram.messenger.UserConfig
import org.telegram.ui.LaunchActivity
import java.io.File
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs

/**
 * Reports UI freezes that never become a crash: while the app is in front, the main thread did not run a tiny posted
 * task for [FreezePolicy.freezeMs]. Users see that as "the screen froze / stopped reacting"; nothing else records it
 * (an ANR needs a 5 s input timeout, and a freeze that ends earlier leaves no trace anywhere).
 *
 * Built to cost nothing and to be unable to hurt the app:
 *  - one daemon thread. In the background it is parked (no CPU, no wake-ups); [onAppResumed] wakes it.
 *  - in front: one Runnable posted to the main thread every [CHECK_MS], never more than one in flight, no
 *    allocation per tick. (Not a Looper printer: that builds a string for every message. Not a Choreographer
 *    callback: that keeps the display pipeline awake.)
 *  - a report only on a real freeze, and at most a few per process ([FreezePolicy]); never during cold start or
 *    with a debugger attached.
 *  - every step runs inside try/catch(Throwable); after [MAX_ERRORS] unexpected errors it stops for good. It can
 *    stop working, it cannot crash or block the app.
 *
 * Each report is a non-fatal "UiFreezeException" whose stack trace is the main thread's at the moment the freeze was
 * caught, so Crashlytics groups freezes by where the main thread was stuck. The same pass keeps four custom keys
 * fresh for every report, crashes included: heap_used_mb, heap_max_mb, accounts, process_age_min (OOM triage).
 */
object FreezeWatchdog {
    private const val CHECK_MS = 500L
    private const val PARK_NANOS = 60_000L * 1_000_000L
    private const val KEYS_EVERY_MS = 60_000L
    private const val HEAP_STEP_MB = 16L
    private const val MAX_ERRORS = 5
    private const val MB = 1024L * 1024L
    private const val DEBUG_FREEZE_FILE = "nova_freeze_test"

    private val policy = FreezePolicy()

    @Volatile private var reporter: DiagReporter? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var installedAt = 0L

    /** Counted on the main thread on every resume (cheap there, Telegram calls it on that thread all the time). */
    @Volatile private var accounts = -1

    // Bumped only by the main thread (the posted task), read by the watchdog thread.
    @Volatile private var tick = 0
    private val tickTask = Runnable { tick++ }

    // Watchdog-thread-only state of the custom keys.
    private var keysAt = 0L
    private var keysHeapMb = -1L
    private var keysAccounts = -2

    /** Called once from ApplicationLoader.onCreate in the main process; null (no Crashlytics in this build) = off. */
    @JvmStatic
    fun install(reporter: DiagReporter?) {
        if (reporter == null || this.reporter != null) return
        try {
            this.reporter = reporter
            installedAt = SystemClock.uptimeMillis()
            val t = Thread({ loop() }, "NovaFreezeWatchdog")
            t.isDaemon = true
            thread = t
            t.start()
        } catch (e: Throwable) {
            this.reporter = null
            thread = null
            FileLog.e(e)
        }
    }

    /** LaunchActivity.onResume, main thread, right after ApplicationLoader.mainInterfacePaused became false. */
    @JvmStatic
    fun onAppResumed() {
        val t = thread ?: return
        try {
            accounts = UserConfig.getActivatedAccountsCount()
            LockSupport.unpark(t)
            if (BuildConfig.DEBUG_VERSION) {
                debugFreezeIfAsked()
            }
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    private fun loop() {
        val main = Handler(Looper.getMainLooper())
        var errors = 0
        while (reporter != null) {
            try {
                if (ApplicationLoader.mainInterfacePaused) {
                    // Spurious or timed-out wake-ups are fine: the loop simply checks again.
                    LockSupport.parkNanos(PARK_NANOS)
                    continue
                }
                maybeUpdateKeys(false)
                val seen = tick
                val postedAt = SystemClock.uptimeMillis()
                main.post(tickTask)
                var handled = false
                while (true) {
                    Thread.sleep(CHECK_MS)
                    if (tick != seen) {
                        break
                    }
                    val now = SystemClock.uptimeMillis()
                    if (!handled && policy.isFreeze(postedAt, now)) {
                        handled = true // one report per freeze, however long it lasts
                        val debugger = Debug.isDebuggerConnected() || Debug.waitingForDebugger()
                        if (!ApplicationLoader.mainInterfacePaused && policy.tryReport(now, postedAt, installedAt, debugger)) {
                            report(now - postedAt)
                        }
                    }
                }
            } catch (e: Throwable) {
                // Nothing interrupts this thread on purpose; anything else is unexpected. Never spin on it.
                if (++errors >= MAX_ERRORS) {
                    reporter = null
                    thread = null
                    return
                }
                try {
                    Thread.sleep(5_000L)
                } catch (ignore: Throwable) {
                }
            }
        }
    }

    private fun report(blockedMs: Long) {
        val r = reporter ?: return
        val stack = Looper.getMainLooper().thread.stackTrace
        if (stack.isEmpty()) {
            return
        }
        // Read while the main thread is stuck, so the stack cannot change under us; a race only costs the name.
        val screen = try {
            LaunchActivity.getLastFragmentIncludeMainTabs()?.javaClass?.name
        } catch (e: Throwable) {
            null
        }
        maybeUpdateKeys(true)
        val error = UiFreezeException("Main thread blocked for $blockedMs ms; screen=${screen ?: "?"}")
        error.stackTrace = stack
        r.recordNonFatal(error)
        FileLog.d("FreezeWatchdog: reported a $blockedMs ms freeze on $screen (report ${policy.reportsMade()} of this process)")
    }

    private fun maybeUpdateKeys(force: Boolean) {
        val r = reporter ?: return
        val now = SystemClock.uptimeMillis()
        if (!force && keysAt != 0L && now - keysAt < KEYS_EVERY_MS) {
            return
        }
        keysAt = now
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / MB
        val count = accounts
        if (!force && keysHeapMb >= 0 && abs(usedMb - keysHeapMb) < HEAP_STEP_MB && count == keysAccounts) {
            return
        }
        keysHeapMb = usedMb
        keysAccounts = count
        r.setKey("heap_used_mb", usedMb.toString())
        r.setKey("heap_max_mb", (rt.maxMemory() / MB).toString())
        r.setKey("accounts", count.toString())
        r.setKey("process_age_min", ((now - installedAt) / 60_000L).toString())
    }

    /**
     * Debug builds only (BuildConfig.DEBUG_VERSION is a compile-time false in release, so R8 drops this): if the file
     * files/nova_freeze_test exists, block the main thread for 3 s once, to check the watchdog end to end on a device:
     * `adb shell run-as <package> touch files/nova_freeze_test`, then send the app to the background and back.
     */
    private fun debugFreezeIfAsked() {
        val file = File(ApplicationLoader.getFilesDirFixed(), DEBUG_FREEZE_FILE)
        if (file.exists() && file.delete()) {
            FileLog.d("FreezeWatchdog: debug freeze of 3000 ms")
            SystemClock.sleep(3_000L)
        }
    }

    /** The non-fatal type of a freeze report; its stack trace is the main thread's when the freeze was caught. */
    class UiFreezeException(message: String) : Exception(message) {
        // The watchdog thread's own stack is meaningless here and is replaced right away: don't build it.
        override fun fillInStackTrace(): Throwable = this
    }
}
