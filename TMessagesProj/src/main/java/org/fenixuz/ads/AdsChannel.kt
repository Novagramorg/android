package org.fenixuz.ads

import androidx.collection.LongSparseArray
import org.fenixuz.utils.LanguageCode
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.ChatObject
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.Utilities
import org.telegram.messenger.WebFile
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Locale

/**
 * Novagram: our sponsored message in channels (ads API v2, slot CHANNEL_MESSAGE).
 *
 * It rides on Telegram's own sponsored-message machinery: when MessagesController.getSponsoredMessages finds no
 * Telegram ad for a channel, [loadFallback] builds our ad as the same kind of MessageObject, so ChatActivity
 * places and draws it like Telegram's (bubble after the last post, title, text, button). The MessageObject is
 * tagged with [MessageObject.novagramAd]; ChatActivity sends that one's view, click and report here instead of
 * to Telegram, and ChatMessageCell draws its https images through the no-proxy web-document path.
 *
 * UI thread only, like the rest of the package.
 */
object AdsChannel {

    // Drawn where Telegram's own sponsored message says "Ad", so ours is never mistaken for Telegram's.
    const val AD_LABEL = "ad by Novagram"

    // The backend's rule (same as Telegram's): only public broadcast channels with 1000+ subscribers get an ad.
    private const val MIN_SUBSCRIBERS = 1000

    // The backend's test channel (@testChannel4Insta2, from its integration guide) has a handful of subscribers;
    // debug builds let it through so the slot can be tested end to end. Release builds never skip the rule.
    private const val TEST_CHANNEL_ID = 3658719860L

    // Telegram numbers its sponsored messages downward from here; ours is only used when Telegram has none.
    private const val MESSAGE_ID = -10000000

    private val REPORT_REASONS = arrayOf("spam", "violence", "pornography", "illegal", "misleading", "other")

    // Ads the user reported or closed: hidden until the app restarts (the backend keeps serving them and has no
    // per-user hide yet), matched by url + text because every serve brings a new token.
    private val hidden = HashSet<String>()

    @JvmStatic
    fun isOurs(messageObject: MessageObject?): Boolean = messageObject?.novagramAd != null

    /**
     * Telegram had no ad for [dialogId] (or a Premium user switched Telegram's ads off): ask our backend. [callback]
     * gets the ad as a one-item list ready for SponsoredMessagesInfo, or null — synchronously when the channel does
     * not qualify or the answer is cached, otherwise after the request.
     */
    @JvmStatic
    fun loadFallback(account: Int, dialogId: Long, callback: Utilities.Callback<ArrayList<MessageObject>?>) {
        val chat = eligibleChannel(account, dialogId)
        if (chat == null) {
            callback.run(null)
            return
        }
        val botApiId = -(1_000_000_000_000L + chat.id)
        AdsServe.serve(account, AdsServe.CHANNEL_MESSAGE, mapOf<String, Any>("channel_id" to botApiId), botApiId.toString()) { served ->
            val ad = served?.ad
            if (served == null || ad == null || ad.url.isNullOrEmpty() || hidden.contains(hideKey(ad))) {
                callback.run(null)
            } else {
                callback.run(arrayListOf(buildMessageObject(account, dialogId, served)))
            }
        }
    }

    /** The bubble is on screen with its whole text: the (billed) view. Repeats for the same token are ignored. */
    @JvmStatic
    fun onViewed(messageObject: MessageObject) {
        messageObject.novagramAd?.let { AdsEvents.add(it, AdsEvents.VIEW) }
    }

    /** Any tap that opens the ad (button, title, image, menu link). The caller opens the URL itself. */
    @JvmStatic
    fun onClicked(account: Int, messageObject: MessageObject) {
        val served = messageObject.novagramAd ?: return
        AdsEvents.add(served, AdsEvents.CLICK, urgent = true)
        val channelId = served.ad.promoted?.telegramId?.let { AdsManager.botApiToChannelId(it) } ?: 0L
        AdsManager.rememberClick(account, served, if (channelId != 0L) -channelId else 0L)
    }

    /** The user closed the ad. Nothing goes to the backend (it has no "hide"); it just stays hidden locally. */
    @JvmStatic
    fun hide(messageObject: MessageObject) {
        messageObject.novagramAd?.let { hidden.add(hideKey(it.ad)) }
    }

    /**
     * Asks for a reason and reports the ad. Whatever the backend answers, the user is thanked and the ad is hidden
     * ([onReported] removes it from the chat) — the backend's own guidance, since a report can't be fixed by the user.
     */
    @JvmStatic
    fun showReport(fragment: BaseFragment, messageObject: MessageObject, onReported: Runnable) {
        val served = messageObject.novagramAd ?: return
        val context = fragment.parentActivity ?: return
        val titles = arrayOf<CharSequence>(
            LanguageCode.getMyTitles(419), LanguageCode.getMyTitles(420), LanguageCode.getMyTitles(421),
            LanguageCode.getMyTitles(422), LanguageCode.getMyTitles(423), LanguageCode.getMyTitles(424)
        )
        val builder = AlertDialog.Builder(context, fragment.resourceProvider)
        builder.setTitle(LanguageCode.getMyTitles(418))
        builder.setItems(titles) { _, which ->
            sendReport(served, REPORT_REASONS[which], retry = true)
            hidden.add(hideKey(served.ad))
            onReported.run()
            BulletinFactory.of(fragment).createAdReportedBulletin(LanguageCode.getMyTitles(425)).show()
        }
        fragment.showDialog(builder.create())
    }

    /** Our ad's media as a no-proxy web file (plain https download), or null when it has none we can draw. */
    @JvmStatic
    fun mediaFile(messageObject: MessageObject): WebFile? {
        val media = messageObject.novagramAd?.ad?.media ?: return null
        // Only photos for now: a video would need the player, which only plays Telegram documents.
        if (!"PHOTO".equals(media.type, ignoreCase = true)) return null
        return webFile(media.url, media.width ?: 1280, media.height ?: 720)
    }

    /** The promoted channel's avatar, drawn as the small image beside the text when the ad has no media. */
    @JvmStatic
    fun avatarFile(messageObject: MessageObject): WebFile? {
        return webFile(messageObject.novagramAd?.ad?.promoted?.avatarUrl, 160, 160)
    }

    private fun webFile(url: String?, width: Int, height: Int): WebFile? {
        // https only: the image loader would happily fetch http, but the backend promises https and anything else
        // is not something we want the app to load.
        if (url.isNullOrEmpty() || !url.startsWith("https://")) return null
        val document = TLRPC.TL_webDocumentNoProxy()
        document.url = url
        document.mime_type = if (url.lowercase(Locale.ROOT).endsWith(".png")) "image/png" else "image/jpeg"
        val size = TLRPC.TL_documentAttributeImageSize()
        size.w = width
        size.h = height
        document.attributes.add(size)
        return WebFile.createWithWebDocument(document)
    }

    private fun eligibleChannel(account: Int, dialogId: Long): TLRPC.Chat? {
        if (dialogId >= 0) return null
        val controller = MessagesController.getInstance(account)
        val chat = controller.getChat(-dialogId) ?: return null
        if (!ChatObject.isChannelAndNotMegaGroup(chat) || !ChatObject.isPublic(chat)) return null
        if (BuildConfig.DEBUG_VERSION && chat.id == TEST_CHANNEL_ID) return chat
        val subscribers = maxOf(chat.participants_count, controller.getChatFull(chat.id)?.participants_count ?: 0)
        return if (subscribers >= MIN_SUBSCRIBERS) chat else null
    }

    private fun buildMessageObject(account: Int, dialogId: Long, served: AdsServed): MessageObject {
        val ad = served.ad
        val promoted = ad.promoted
        // Built like MessagesController.getSponsoredMessages builds Telegram's: a local message with no sender.
        val message = TLRPC.TL_message()
        message.peer_id = MessagesController.getInstance(account).getPeer(dialogId)
        message.flags = message.flags or 256
        message.date = ConnectionsManager.getInstance(account).currentTime
        message.id = MESSAGE_ID
        message.message = ad.text ?: ""
        val messageObject = MessageObject(account, message, LongSparseArray(), LongSparseArray(), true, true)
        messageObject.sponsoredId = ad.randomId!!.toByteArray(Charsets.UTF_8)
        messageObject.sponsoredTitle = promoted?.title?.takeIf { it.isNotBlank() } ?: promoted?.username?.let { "@$it" } ?: ""
        messageObject.sponsoredUrl = ad.url
        messageObject.sponsoredButtonText = LanguageCode.getMyTitles(if ("BOT".equals(ad.buttonType, ignoreCase = true)) 417 else 416)
        // Enables the "Ad · what's this?" header and the menu; ChatActivity routes that menu's report to us.
        messageObject.sponsoredCanReport = true
        messageObject.sponsoredInfo = ad.otherInfo?.takeIf { it.isNotBlank() }
        messageObject.novagramAd = served
        messageObject.setType()
        messageObject.textLayoutBlocks = ArrayList()
        messageObject.generateThumbs(true)
        return messageObject
    }

    private fun hideKey(ad: ServedAd) = "${ad.url}|${ad.text}"

    private fun sendReport(served: AdsServed, reason: String, retry: Boolean) {
        val randomId = served.ad.randomId ?: return
        if (!AdsRetrofitClient.enabled) return
        AdsRetrofitClient.eventsService.report(AdsReportRequest(served.userId, randomId, reason)).enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                val code = response.code()
                FileLog.d("Novagram ads: report $reason -> HTTP $code")
                // 429 / 5xx are temporary: one more try in half a minute (a token older than 1 h is refused anyway).
                if (retry && (code == 429 || code >= 500)) retryLater(served, reason)
            }

            override fun onFailure(call: Call<Void>, t: Throwable) {
                FileLog.d("Novagram ads: report failed: ${t.javaClass.simpleName}")
                if (retry) retryLater(served, reason)
            }
        })
    }

    private fun retryLater(served: AdsServed, reason: String) {
        AndroidUtilities.runOnUIThread({ sendReport(served, reason, retry = false) }, 30_000L)
    }
}
