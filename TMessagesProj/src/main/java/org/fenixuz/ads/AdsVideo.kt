package org.fenixuz.ads

import org.telegram.messenger.Utilities
import org.telegram.messenger.WebFile
import org.telegram.tgnet.TLRPC
import retrofit2.Call

/**
 * Novagram: our banner over a channel's video in the fullscreen player (ads API v2, slot VIDEO).
 *
 * It rides on Telegram's own video-ad machinery (org.telegram.messenger.video.VideoAds): when Telegram has no video
 * ad for a channel, VideoAds asks here once a video of that channel really plays, then draws our ad with Telegram's
 * banner, timers and pause rules. Ours travels through VideoAds as an [Ad] — a TL_sponsoredMessage that carries the
 * served ad — so VideoAds can tell it apart and send its view, click and report here instead of to Telegram.
 *
 * UI thread only, like the rest of the package.
 */
object AdsVideo {

    // The backend's current timers, for an answer that lacks them (it leaves the delays out when degraded).
    private const val DEFAULT_START_DELAY = 5
    private const val DEFAULT_BETWEEN_DELAY = 120
    private const val DEFAULT_MIN_DISPLAY = 9
    private const val DEFAULT_MAX_DISPLAY = 30

    /** Our ad, shaped like Telegram's so VideoAds can show it. */
    class Ad internal constructor(val served: AdsServed) : TLRPC.TL_sponsoredMessage() {
        val startDelay: Int = served.startDelay?.takeIf { it >= 0 } ?: DEFAULT_START_DELAY
        val betweenDelay: Int = served.betweenDelay?.takeIf { it > 0 } ?: DEFAULT_BETWEEN_DELAY
    }

    @JvmStatic
    fun isOurs(ad: TLRPC.TL_sponsoredMessage?): Boolean = ad is Ad

    /**
     * Whether our video ads may run in [dialogId] at all: the same channels as our channel ads (public broadcast,
     * 1000+ subscribers). Anything else — chats, groups, private or small channels — is never sent to the backend.
     */
    @JvmStatic
    fun canServe(account: Int, dialogId: Long): Boolean =
        AdsRetrofitClient.enabled && AdsSponsored.eligibleChannel(account, dialogId) != null

    /**
     * Asks for the banner of channel [dialogId] while its video [msgId] plays. [refresh] skips the cached answer (the
     * next banner, after between_delay). [callback] gets the ad or null — synchronously when cached or not eligible,
     * otherwise after the request; the returned call (if any) can be cancelled, and its callback then never fires.
     */
    @JvmStatic
    fun load(account: Int, dialogId: Long, msgId: Int, refresh: Boolean, callback: Utilities.Callback<Ad?>): Call<AdsServeResponse>? {
        val chat = AdsSponsored.eligibleChannel(account, dialogId)
        if (chat == null || msgId <= 0) {
            callback.run(null)
            return null
        }
        val botApiId = -(1_000_000_000_000L + chat.id)
        // Cached per channel, as the backend asks: msg_id is only validated there, so neighbouring videos share an answer.
        val context = mapOf<String, Any>("channel_id" to botApiId, "msg_id" to msgId)
        return AdsServe.serve(account, AdsServe.VIDEO, context, botApiId.toString(), refresh) { served ->
            val ad = served?.ad
            if (served == null || ad == null || ad.url.isNullOrEmpty() || AdsSponsored.isHidden(ad)) {
                callback.run(null)
            } else {
                callback.run(build(served))
            }
        }
    }

    /** The banner is on screen with its whole text: the (billed) view. Repeats for the same token are ignored. */
    @JvmStatic
    fun onShown(ad: TLRPC.TL_sponsoredMessage) {
        (ad as? Ad)?.let { AdsEvents.add(it.served, AdsEvents.VIEW) }
    }

    /** The banner was tapped; the caller opens the URL itself. */
    @JvmStatic
    fun onClicked(account: Int, ad: TLRPC.TL_sponsoredMessage) {
        (ad as? Ad)?.let { AdsSponsored.onClicked(account, it.served) }
    }

    /** The report reasons for the banner's menu, in the order [report] takes them. */
    @JvmStatic
    fun reportReasonTitles(): Array<CharSequence> = AdsSponsored.reportReasonTitles()

    /** Reports the banner; it stays hidden for the rest of the session. The caller thanks the user and hides it. */
    @JvmStatic
    fun report(ad: TLRPC.TL_sponsoredMessage, reasonIndex: Int) {
        (ad as? Ad)?.let { AdsSponsored.report(it.served, reasonIndex) }
    }

    /** The promoted channel's avatar for the banner's round image, or null when the advertiser did not choose one. */
    @JvmStatic
    fun avatarFile(ad: TLRPC.TL_sponsoredMessage): WebFile? {
        return (ad as? Ad)?.let { AdsSponsored.webFile(it.served.ad.promoted?.avatarUrl, 160, 160) }
    }

    private fun build(served: AdsServed): Ad {
        val ad = served.ad
        val promoted = ad.promoted
        // VideoAds reads these exactly as it reads Telegram's answer.
        return Ad(served).apply {
            random_id = ad.randomId!!.toByteArray(Charsets.UTF_8)
            title = promoted?.title?.takeIf { it.isNotBlank() } ?: promoted?.username?.let { "@$it" } ?: ""
            message = ad.text ?: ""
            url = ad.url
            // The X may come only after min_display_duration and the banner goes away at max_display_duration, so
            // max must stay above min or the banner would vanish before it could be closed.
            val min = ad.minDisplayDuration?.takeIf { it >= 0 } ?: DEFAULT_MIN_DISPLAY
            min_display_duration = min
            max_display_duration = (ad.maxDisplayDuration ?: DEFAULT_MAX_DISPLAY).coerceAtLeast(min + 1)
        }
    }
}
