package org.fenixuz.utils

import org.fenixuz.hidden.HiddenAccounts
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import java.util.Locale
import kotlin.random.Random

/**
 * Novagram: join the current PUBLIC channel/supergroup on every other logged-in account.
 *
 * Why per-account resolve: a channel's `access_hash` is account-specific, so each account must
 * independently resolve the username ([TLRPC.TL_contacts_resolveUsername]) to get a valid
 * [TLRPC.InputChannel] before [TLRPC.TL_channels_joinChannel]. We build the InputChannel straight from
 * each account's resolved [TLRPC.Chat] (id + access_hash) — no MessagesController-cache dependency.
 *
 * ONE ACCOUNT AT A TIME, with a random 2–5 s pause between accounts (2026-11-07). The first version
 * fired all accounts concurrently: N accounts from one device, one IP and one api_id joining the same
 * channel in the same second is the textbook mass-join pattern Telegram's anti-spam scores, and users
 * reported frozen accounts. The pause is random so the joins don't form a fixed-period pattern either.
 * If the server answers FLOOD_WAIT for any account, the run stops there: piling the remaining accounts
 * on top of an active rate limit would only deepen it. The run continues in the background if the user
 * leaves the chat (the delays ride the main looper, the requests their account's network thread).
 *
 *  - includes the current account only when it is not a member yet (first in line); `USER_ALREADY_PARTICIPANT`
 *    counts as joined;
 *  - an account logged out while the run is in progress is skipped;
 *  - exactly one result callback, delivered on the UI thread.
 *
 * Device-only, no storage, no Firebase. Channels/supergroups only (basic groups can't be self-joined).
 */
object JoinAllAccounts {

    fun interface Listener {
        fun onComplete(joined: Int, total: Int)
    }

    private const val MIN_GAP_MS = 2000L
    private const val MAX_GAP_MS = 5000L

    private enum class Result { JOINED, FAILED, FLOOD }

    /** Rough cost of one account's resolve + join round trips, for the duration estimate. */
    private const val REQUESTS_MS = 1500L

    @Volatile private var running = false

    /**
     * The accounts a run joins on. The current account goes FIRST when [includeCurrent] (the user is looking at a
     * channel they have not joined: "all accounts" must include their own, and they see that one happen at once);
     * when the user is already a member it is left out. Hidden accounts are left out: they'd join without the
     * user seeing it, and the result would count them.
     */
    private fun targets(currentAccount: Int, includeCurrent: Boolean): ArrayList<Int> {
        val targets = ArrayList<Int>()
        if (includeCurrent && HiddenAccounts.isListed(currentAccount)) targets.add(currentAccount)
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (a == currentAccount) continue
            if (HiddenAccounts.isListed(a)) targets.add(a)
        }
        return targets
    }

    /** How many accounts a run from [currentAccount] would join on; [includeCurrent] = the user is not a member yet. */
    @JvmStatic
    fun targetCount(currentAccount: Int, includeCurrent: Boolean): Int = targets(currentAccount, includeCurrent).size

    /**
     * Expected duration of a run over [count] accounts, in whole seconds: the requests of every account plus the
     * average pause between accounts. Shown to the user before the run, since 30 accounts take about two minutes.
     */
    @JvmStatic
    fun estimateSeconds(count: Int): Int {
        if (count <= 0) return 0
        val ms = count * REQUESTS_MS + (count - 1) * (MIN_GAP_MS + MAX_GAP_MS) / 2
        return ((ms + 999) / 1000).toInt()
    }

    /**
     * @param includeCurrent the current account is not a member yet, so it joins too (first).
     * @return false if a run is already in progress, the username is blank, or there is no account to join on.
     */
    fun joinOnAllAccounts(currentAccount: Int, username: String, includeCurrent: Boolean, listener: Listener): Boolean {
        if (running) return false
        val uname = username.lowercase(Locale.US).trim()
        if (uname.isEmpty()) return false

        val targets = targets(currentAccount, includeCurrent)
        if (targets.isEmpty()) return false

        running = true
        joinNext(targets, 0, 0, uname, listener)
        return true
    }

    private fun finish(joined: Int, total: Int, listener: Listener) {
        running = false
        AndroidUtilities.runOnUIThread { listener.onComplete(joined, total) }
    }

    private fun joinNext(targets: List<Int>, index: Int, joined: Int, username: String, listener: Listener) {
        if (index >= targets.size) {
            finish(joined, targets.size, listener)
            return
        }
        val account = targets[index]
        if (!HiddenAccounts.isListed(account)) {
            // Logged out (or hidden) since the run started: nothing to send from it.
            joinNext(targets, index + 1, joined, username, listener)
            return
        }
        resolveAndJoin(account, username) { result ->
            val count = joined + if (result == Result.JOINED) 1 else 0
            when {
                result == Result.FLOOD -> finish(count, targets.size, listener)
                index + 1 >= targets.size -> finish(count, targets.size, listener)
                else -> {
                    val gap = MIN_GAP_MS + Random.nextLong(MAX_GAP_MS - MIN_GAP_MS + 1)
                    AndroidUtilities.runOnUIThread({ joinNext(targets, index + 1, count, username, listener) }, gap)
                }
            }
        }
    }

    private fun isFlood(error: TLRPC.TL_error?): Boolean = error?.text?.startsWith("FLOOD") == true

    private fun resolveAndJoin(account: Int, username: String, done: (Result) -> Unit) {
        val req = TLRPC.TL_contacts_resolveUsername()
        req.username = username
        ConnectionsManager.getInstance(account).sendRequest(req) { response, error ->
            if (isFlood(error)) {
                done(Result.FLOOD)
                return@sendRequest
            }
            if (error != null || response !is TLRPC.TL_contacts_resolvedPeer || response.chats.isEmpty()) {
                done(Result.FAILED)
                return@sendRequest
            }
            val channelId = (response.peer as? TLRPC.TL_peerChannel)?.channel_id
            val chat = (if (channelId != null) response.chats.firstOrNull { it.id == channelId } else null)
                ?: response.chats[0]
            if (!ChatObject.isChannel(chat)) {
                done(Result.FAILED)
                return@sendRequest
            }
            val input = TLRPC.TL_inputChannel()
            input.channel_id = chat.id
            input.access_hash = chat.access_hash
            val joinReq = TLRPC.TL_channels_joinChannel()
            joinReq.channel = input
            ConnectionsManager.getInstance(account).sendRequest(joinReq) { resp, err ->
                when {
                    err == null && resp is TLRPC.Updates -> {
                        MessagesController.getInstance(account).processUpdates(resp, false)
                        done(Result.JOINED)
                    }
                    isFlood(err) -> done(Result.FLOOD)
                    // already in the channel = nothing to do, count it as joined
                    err?.text == "USER_ALREADY_PARTICIPANT" -> done(Result.JOINED)
                    else -> done(Result.FAILED)
                }
            }
        }
    }
}
