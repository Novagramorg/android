package org.fenixuz.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires when [MessageReminder]'s scheduled (inexact) alarm goes off. Kept intentionally tiny — all the
 * decision logic (still-unread check, notification) lives in [MessageReminder.onFired] so the receiver
 * has no state and does no heavy work on the main thread.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_FIRE) {
            // goAsync keeps this receiver's process alive while the ~8s ring plays; finished in onFired.
            MessageReminder.onFired(goAsync())
        }
    }

    companion object {
        const val ACTION_FIRE = "org.fenixuz.REMINDER_FIRE"
    }
}
