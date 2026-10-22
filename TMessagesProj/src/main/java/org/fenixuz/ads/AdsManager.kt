package org.fenixuz.ads

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import retrofit2.Call
import java.util.Locale

/**
 * Orchestrates the sponsored search result (ads API v2, slot SEARCH): debounced lookup, stale-response
 * guarding, resolving the promoted channel/bot, and its events — view when the row is shown, click when it
 * is tapped, action when the user then joins the channel. Everything here runs on the UI thread (the search
 * adapter calls in on it; Retrofit and the resolveUsername callback hop back to it), so the mutable state
 * below needs no locking.
 *
 * No long-lived scope, no Context/View retained: the only state is the ad on screen plus a monotonic
 * [generation] used to drop results from a superseded query. Every new query cancels the previous debounce
 * + in-flight call, so callbacks are bounded by a single request's lifetime.
 */
object AdsManager {

    // Advertiser phrases are 4–32 characters and must match the whole query, so anything outside that
    // range can never get an ad — don't ask.
    private const val MIN_QUERY_LEN = 4
    private const val MAX_QUERY_LEN = 32
    private const val DEBOUNCE_MS = 500L

    // Tokens die 1 h after they are issued; an action later than this would be rejected anyway.
    private const val ACTION_WINDOW_MS = 55 * 60 * 1000L

    /** The search ad currently on screen and the dialog id it resolved to. */
    private var current: AdsServed? = null
    private var currentDialogId = 0L
    private var currentAccount = -1

    /** The ad last tapped (search or channel slot); joining that channel afterwards is reported as its action. */
    private var clicked: AdsServed? = null
    private var clickedDialogId = 0L
    private var clickedUsername: String? = null
    private var clickedAccount = -1

    /** Bumped on every new query / cancel; a callback whose captured value differs is stale and ignored. */
    private var generation = 0

    private var pendingCall: Call<AdsServeResponse>? = null
    private var debounceRunnable: Runnable? = null

    /**
     * Requests the search ad for [query] on [account]. [onResult] is invoked on the UI thread with the promoted
     * peer (TLRPC.Chat for a channel, TLRPC.User for a bot) when there is one to show, or null (no ad, query
     * out of range, error). It may be called synchronously (short query, cached answer) or after the debounce
     * + network round-trip.
     */
    fun requestSearchAd(account: Int, query: String?, onResult: (TLObject?) -> Unit) {
        cancel()
        current = null
        currentDialogId = 0L
        val normalized = normalizeQuery(query)
        if (!AdsRetrofitClient.enabled || normalized.length < MIN_QUERY_LEN || normalized.length > MAX_QUERY_LEN) {
            onResult(null)
            return
        }
        val gen = generation
        val runnable = Runnable {
            // We're executing now: release our reference to this runnable (and, through it, the caller's
            // callback) so the static manager never retains a stale adapter after the request is kicked off.
            debounceRunnable = null
            val context = mapOf<String, Any>("query" to query!!.trim())
            pendingCall = AdsServe.serve(account, AdsServe.SEARCH, context, normalized) { served ->
                if (gen != generation) return@serve
                pendingCall = null
                if (served == null) {
                    onResult(null)
                    return@serve
                }
                resolvePromoted(account, served.ad.promoted) { peer ->
                    if (gen != generation) return@resolvePromoted
                    if (peer != null) {
                        current = served
                        currentAccount = account
                        currentDialogId = if (peer is TLRPC.Chat) -peer.id else (peer as TLRPC.User).id
                    }
                    onResult(peer)
                }
            }
        }
        debounceRunnable = runnable
        AndroidUtilities.runOnUIThread(runnable, DEBOUNCE_MS)
    }

    /** Drops any pending lookup and invalidates in-flight callbacks. UI thread. */
    fun cancel() {
        generation++
        debounceRunnable?.let { AndroidUtilities.cancelRunOnUIThread(it) }
        debounceRunnable = null
        pendingCall?.cancel()
        pendingCall = null
    }

    /** The ad row was bound on screen. The first call per token bills the view; repeats are ignored. */
    fun onSearchAdShown() {
        current?.let { AdsEvents.add(it, AdsEvents.VIEW) }
    }

    /** The ad row was tapped (the caller opens the chat itself). */
    fun onSearchAdClicked() {
        val served = current ?: return
        AdsEvents.add(served, AdsEvents.CLICK, urgent = true)
        rememberClick(currentAccount, served, currentDialogId)
    }

    /**
     * Remembers a tapped ad so that joining its channel next counts as the ad's action. [dialogId] is the promoted
     * peer when already known (0 otherwise); the promoted username is matched as well, since a channel ad may name
     * a channel this client has never loaded.
     */
    fun rememberClick(account: Int, served: AdsServed, dialogId: Long) {
        clicked = served
        clickedDialogId = dialogId
        clickedUsername = served.ad.promoted?.username?.trim()?.removePrefix("@")?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        clickedAccount = account
    }

    /**
     * The current user joined channel [chatId] on [account] (MessagesController.addUserToChat). If that is the
     * channel of the ad just tapped, report the action — once, and only within the token's lifetime.
     */
    fun onChannelJoined(account: Int, chatId: Long) {
        val served = clicked ?: return
        if (account != clickedAccount) return
        val sameId = clickedDialogId != 0L && clickedDialogId == -chatId
        val sameUsername = clickedUsername != null &&
            MessagesController.getInstance(account).getChat(chatId)?.let { chat ->
                ChatObject.getPublicUsername(chat)?.lowercase(Locale.ROOT) == clickedUsername
            } == true
        if (!sameId && !sameUsername) return
        clicked = null
        if (System.currentTimeMillis() - served.receivedAt > ACTION_WINDOW_MS) return
        AdsEvents.add(served, AdsEvents.ACTION, urgent = true)
    }

    // Mirrors the backend's own normalisation closely enough for the length check and the cache key:
    // trim, lower case, one space between words, every apostrophe look-alike → '.
    private fun normalizeQuery(query: String?): String {
        if (query == null) return ""
        val sb = StringBuilder(query.length)
        var space = false
        for (c in query.trim().lowercase(Locale.ROOT)) {
            if (c.isWhitespace()) {
                space = true
                continue
            }
            if (space && sb.isNotEmpty()) sb.append(' ')
            space = false
            sb.append(if (c == 'ʻ' || c == 'ʼ' || c == '’' || c == '‘' || c == '`' || c == '´') '\'' else c)
        }
        return sb.toString()
    }

    /**
     * Finds the promoted channel/bot and returns it via [cb] (null = could not resolve). A public peer can
     * only be opened by its @username (that is where its access hash comes from), so the username is the
     * source of truth. telegram_id is the backend's Bot-API channel id, used only as a fast path when that
     * channel is already loaded.
     */
    private fun resolvePromoted(account: Int, promoted: AdPromoted?, cb: (TLObject?) -> Unit) {
        val username = promoted?.username?.trim()?.removePrefix("@")?.lowercase(Locale.ROOT)
        val mc = MessagesController.getInstance(account)
        val hintId = promoted?.telegramId?.let { botApiToChannelId(it) } ?: 0L
        if (hintId != 0L) {
            val chat = mc.getChat(hintId)
            if (chat != null) {
                cb(chat)
                return
            }
        }
        if (username.isNullOrEmpty()) {
            cb(null)
            return
        }
        val known = mc.getUserOrChat(username)
        if (known is TLRPC.Chat || known is TLRPC.User && known.bot) {
            cb(known)
            return
        }
        val req = TLRPC.TL_contacts_resolveUsername()
        req.username = username
        ConnectionsManager.getInstance(account).sendRequest(req) { response, error ->
            AndroidUtilities.runOnUIThread {
                if (error != null || response !is TLRPC.TL_contacts_resolvedPeer) {
                    cb(null)
                    return@runOnUIThread
                }
                mc.putUsers(response.users, false)
                mc.putChats(response.chats, false)
                val peer = response.peer
                val result: TLObject? = when {
                    peer != null && peer.channel_id != 0L -> mc.getChat(peer.channel_id)
                    // Only a bot can be advertised as a user; a plain user's username gets no ad row.
                    peer != null && peer.user_id != 0L -> mc.getUser(peer.user_id)?.takeIf { it.bot }
                    else -> null
                }
                cb(result)
            }
        }
    }

    // -1001234567890 (Bot API) → 1234567890. Other forms are not channel ids from this backend.
    internal fun botApiToChannelId(id: Long): Long {
        val abs = -id
        return if (abs in 1_000_000_000_000L..1_999_999_999_999L) abs - 1_000_000_000_000L else 0L
    }
}
