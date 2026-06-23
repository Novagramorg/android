package org.fenixuz.utils

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Novagram: join the current PUBLIC channel/supergroup on EVERY other logged-in account at once.
 *
 * Why per-account resolve: a channel's `access_hash` is account-specific, so each account must
 * independently resolve the username ([TLRPC.TL_contacts_resolveUsername]) to get a valid
 * [TLRPC.InputChannel] before [TLRPC.TL_channels_joinChannel]. We build the InputChannel straight from
 * each account's resolved [TLRPC.Chat] (id + access_hash) — no MessagesController-cache dependency
 * (pro read `getInputChannel` from an un-populated per-account cache, which was fragile).
 *
 * Optimised over pro's `JoinChannels`:
 *  - NO mutable shared recursion counter (`accountsNum`) — fully reentrancy-safe, state is per-call/local.
 *  - accounts run CONCURRENTLY (each has its own session → no shared flood pool) instead of one-at-a-time
 *    network-callback recursion → finishes in ~one round-trip instead of N.
 *  - skips the current account (you're already a member) and treats `USER_ALREADY_PARTICIPANT` as success.
 *  - exactly one result callback, delivered on the UI thread.
 *
 * Device-only, no storage, no Firebase. Channels/supergroups only (basic groups can't be self-joined).
 */
object JoinAllAccounts {

    fun interface Listener {
        fun onComplete(joined: Int, total: Int)
    }

    @Volatile private var running = false

    /** @return false if a run is already in progress, the username is blank, or no other account exists. */
    fun joinOnAllAccounts(currentAccount: Int, username: String, listener: Listener): Boolean {
        if (running) return false
        val uname = username.lowercase(Locale.US).trim()
        if (uname.isEmpty()) return false

        val targets = ArrayList<Int>()
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (a == currentAccount) continue
            if (UserConfig.getInstance(a).isClientActivated()) targets.add(a)
        }
        if (targets.isEmpty()) return false

        running = true
        val total = targets.size
        val remaining = AtomicInteger(total)
        val joined = AtomicInteger(0)
        for (account in targets) {
            resolveAndJoin(account, uname) { ok ->
                if (ok) joined.incrementAndGet()
                if (remaining.decrementAndGet() == 0) {
                    running = false
                    AndroidUtilities.runOnUIThread { listener.onComplete(joined.get(), total) }
                }
            }
        }
        return true
    }

    private fun resolveAndJoin(account: Int, username: String, done: (Boolean) -> Unit) {
        val req = TLRPC.TL_contacts_resolveUsername()
        req.username = username
        ConnectionsManager.getInstance(account).sendRequest(req) { response, error ->
            if (error != null || response !is TLRPC.TL_contacts_resolvedPeer || response.chats.isEmpty()) {
                done(false)
                return@sendRequest
            }
            val channelId = (response.peer as? TLRPC.TL_peerChannel)?.channel_id
            val chat = (if (channelId != null) response.chats.firstOrNull { it.id == channelId } else null)
                ?: response.chats[0]
            if (!ChatObject.isChannel(chat)) {
                done(false)
                return@sendRequest
            }
            val input = TLRPC.TL_inputChannel()
            input.channel_id = chat.id
            input.access_hash = chat.access_hash
            val joinReq = TLRPC.TL_channels_joinChannel()
            joinReq.channel = input
            ConnectionsManager.getInstance(account).sendRequest(joinReq) { resp, err ->
                if (err == null && resp is TLRPC.Updates) {
                    MessagesController.getInstance(account).processUpdates(resp, false)
                    done(true)
                } else {
                    // already in the channel = nothing to do, count it as joined
                    done(err?.text == "USER_ALREADY_PARTICIPANT")
                }
            }
        }
    }
}
