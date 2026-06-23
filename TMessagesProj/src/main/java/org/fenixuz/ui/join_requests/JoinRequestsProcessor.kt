package org.fenixuz.ui.join_requests

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * Novagram: clears a backlog of pending join requests for ONE chat (channel or group).
 *
 * Two execution paths, picked automatically:
 *  - "All" (target >= pending) → a SINGLE [TLRPC.TL_messages_hideAllChatJoinRequests]; the server clears
 *    every pending request in one round-trip. No paging, no per-user loop — instant and cheap.
 *  - partial N → page through [TLRPC.TL_messages_getChatInviteImporters] (requested=true) and approve/decline
 *    each importer via [TLRPC.TL_messages_hideChatJoinRequest], one by one, until N are attempted.
 *
 * Flood-safe: honours `FLOOD_WAIT_x` (waits the requested seconds, then retries the same user, capped), and
 * gently paces individual approvals so a large backlog doesn't trip the server's spam guard. Skips users the
 * server rejects (e.g. `USER_CHANNELS_TOO_MUCH`) instead of aborting the whole run.
 *
 * Runs on [Dispatchers.IO] and is fully cancellable via [cancel]; progress/finish/error callbacks are always
 * posted back on the UI thread. Device-only, no Firebase.
 */
class JoinRequestsProcessor(
    private val account: Int,
    private val chatId: Long,
    private val approve: Boolean,
    private val target: Int,
    private val pendingCount: Int,
    private val listener: Listener
) {

    interface Listener {
        fun onProgress(done: Int, total: Int)
        fun onFinished(done: Int)
        fun onError(text: String)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val controller get() = MessagesController.getInstance(account)
    private val cm get() = ConnectionsManager.getInstance(account)

    @Volatile private var done = 0
    private var lastImporter: TLRPC.TL_chatInviteImporter? = null
    private var lastUser: TLRPC.User? = null

    fun start() {
        if (target >= pendingCount) {
            hideAll()
        } else {
            scope.launch { loop() }
        }
    }

    fun cancel() {
        scope.cancel()
    }

    /** Fast path: approve/decline every pending request in one server call. */
    private fun hideAll() {
        val req = TLRPC.TL_messages_hideAllChatJoinRequests()
        req.approved = approve
        req.peer = controller.getInputPeer(-chatId)
        cm.sendRequest(req) { response, error ->
            AndroidUtilities.runOnUIThread {
                if (error == null) {
                    if (response is TLRPC.Updates) controller.processUpdates(response, false)
                    done = pendingCount
                    listener.onProgress(done, pendingCount)
                    listener.onFinished(done)
                } else {
                    listener.onError(error.text ?: "")
                }
            }
        }
    }

    /** Partial path: page importers and process up to [target] of them. */
    private suspend fun loop() {
        var attempted = 0
        while (attempted < target) {
            coroutineContext.ensureActive()
            val batch = loadBatch() ?: break
            if (batch.importers.isEmpty()) break
            for (importer in batch.importers) {
                coroutineContext.ensureActive()
                if (attempted >= target) break
                attempted++
                val user = batch.users.firstOrNull { it.id == importer.user_id }
                if (user != null && processOne(user)) {
                    done++
                }
                postProgress()
                delay(BASE_DELAY_MS)
            }
            lastImporter = batch.importers.last()
            lastUser = batch.users.firstOrNull { it.id == lastImporter!!.user_id }
        }
        AndroidUtilities.runOnUIThread { listener.onFinished(done) }
    }

    private fun postProgress() {
        val snapshot = done
        AndroidUtilities.runOnUIThread { listener.onProgress(snapshot, target) }
    }

    private suspend fun loadBatch(): TLRPC.TL_messages_chatInviteImporters? =
        suspendCancellableCoroutine { cont ->
            val req = TLRPC.TL_messages_getChatInviteImporters()
            req.peer = controller.getInputPeer(-chatId)
            req.requested = true
            req.limit = PAGE_SIZE
            val li = lastImporter
            val lu = lastUser
            if (li == null || lu == null) {
                req.offset_user = TLRPC.TL_inputUserEmpty()
            } else {
                req.offset_user = controller.getInputUser(lu)
                req.offset_date = li.date
            }
            val token = cm.sendRequest(req) { response, _ ->
                cont.resume(response as? TLRPC.TL_messages_chatInviteImporters)
            }
            cont.invokeOnCancellation { cm.cancelRequest(token, false) }
        }

    /** Returns true on success. Retries on FLOOD_WAIT (capped), skips on any other error. */
    private suspend fun processOne(user: TLRPC.User): Boolean {
        var floodRetries = 0
        while (true) {
            coroutineContext.ensureActive()
            val wait = hideOne(user)
            when {
                wait == RESULT_OK -> return true
                wait == RESULT_SKIP -> return false
                else -> {
                    if (++floodRetries > MAX_FLOOD_RETRIES) return false
                    delay(wait * 1000L + 250L)
                }
            }
        }
    }

    /** @return [RESULT_OK], [RESULT_SKIP], or a positive flood-wait in seconds. */
    private suspend fun hideOne(user: TLRPC.User): Int =
        suspendCancellableCoroutine { cont ->
            val req = TLRPC.TL_messages_hideChatJoinRequest()
            req.approved = approve
            req.peer = controller.getInputPeer(-chatId)
            req.user_id = controller.getInputUser(user)
            val token = cm.sendRequest(req) { response, error ->
                if (error == null) {
                    if (response is TLRPC.Updates) {
                        AndroidUtilities.runOnUIThread { controller.processUpdates(response, false) }
                    }
                    cont.resume(RESULT_OK)
                } else {
                    val text = error.text ?: ""
                    if (text.startsWith("FLOOD_WAIT")) {
                        val secs = text.substringAfterLast('_').toIntOrNull() ?: 5
                        cont.resume(secs.coerceIn(1, MAX_FLOOD_WAIT))
                    } else {
                        cont.resume(RESULT_SKIP)
                    }
                }
            }
            cont.invokeOnCancellation { cm.cancelRequest(token, false) }
        }

    companion object {
        private const val PAGE_SIZE = 30
        private const val BASE_DELAY_MS = 120L
        private const val MAX_FLOOD_RETRIES = 3
        private const val MAX_FLOOD_WAIT = 30
        private const val RESULT_OK = 0
        private const val RESULT_SKIP = -1
    }
}
