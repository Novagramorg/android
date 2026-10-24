package org.fenixuz.ads

import android.content.Context
import android.os.SystemClock
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.time.OffsetDateTime

/**
 * Novagram: the system banners above the chat list (ads API v2, its banner endpoints) — announcements the backend's
 * superadmin publishes ("our channel", "new version", maintenance). They are not ads: there is no auction and no
 * view event, only a click count and a per-viewer dismiss that the server remembers.
 *
 * DialogsActivity shows [current] in Telegram's own hint cell, after Telegram's account notices and before its
 * promotional hints, and redraws it on [NotificationCenter.novagramBannersUpdated].
 *
 * UI thread only, like the rest of the package.
 */
object AdsBanners {

    // The backend asks for a refresh on start and on return to the foreground, but at most every 15–30 minutes.
    private const val REFRESH_MS = 20 * 60 * 1000L
    // After a failed fetch (5xx, network) the next start or resume may try again sooner.
    private const val RETRY_MS = 60 * 1000L
    private const val CLICK_RETRY_MS = 30 * 1000L
    // A far-off ends_at is re-checked at least daily (Handler delays don't count deep sleep anyway).
    private const val MAX_EXPIRY_DELAY_MS = 24 * 60 * 60 * 1000L

    private const val PREFS = "novagram_ads"
    // Per user id: banners closed on this device, and those whose dismiss has not reached the server yet.
    private const val KEY_DISMISSED = "banner_dismissed_"
    private const val KEY_PENDING = "banner_pending_"

    /** One banner as DialogsActivity draws it. [link] is null when the banner is not clickable. */
    class Banner internal constructor(
        val id: String,
        val title: String,
        val text: String,
        val link: String?,
        val dismissible: Boolean,
        internal val endsAtMs: Long // 0 = no end
    )

    private class State {
        var banners: List<Banner> = emptyList()
        var nextFetchAt = 0L // elapsedRealtime
        var call: Call<Array<SystemBanner>>? = null
    }

    private val states = HashMap<Long, State>()
    // 401/403: the key is refused. The backend says not to retry, so banners stay off for this process.
    private var keyRejected = false
    private val dismissInFlight = HashSet<String>()
    private var expiryRunnable: Runnable? = null

    /** The banner to show to [account] now, or null. Also starts a refresh when the list is due one. */
    @JvmStatic
    fun current(account: Int): Banner? {
        val userId = UserConfig.getInstance(account).clientUserId
        if (!AdsRetrofitClient.enabled || keyRejected || userId == 0L) return null
        val state = states.getOrPut(userId) { State() }
        refresh(account, userId, state)
        val now = System.currentTimeMillis()
        val dismissed = stringSet(KEY_DISMISSED + userId)
        // The list comes most important first; when the user closes one, the next shows.
        val banner = state.banners.firstOrNull { (it.endsAtMs == 0L || it.endsAtMs > now) && it.id !in dismissed }
        scheduleExpiry(account, banner, now)
        return banner
    }

    /** App start and return to the foreground (LaunchActivity.onResume). */
    @JvmStatic
    fun onAppResumed() {
        val account = UserConfig.selectedAccount
        val userId = UserConfig.getInstance(account).clientUserId
        if (!AdsRetrofitClient.enabled || keyRejected || userId == 0L) return
        refresh(account, userId, states.getOrPut(userId) { State() })
    }

    /** The banner was tapped. The click is counted fire-and-forget; the caller opens [Banner.link] right away. */
    @JvmStatic
    fun click(banner: Banner) {
        if (banner.link == null || !AdsRetrofitClient.enabled || keyRejected) return
        sendClick(banner.id, retry = true)
    }

    /** X: hidden at once and for good on this device; the server is told now, and again later if that fails. */
    @JvmStatic
    fun dismiss(account: Int, banner: Banner) {
        if (!banner.dismissible) return
        val userId = UserConfig.getInstance(account).clientUserId
        if (userId == 0L) return
        editSet(KEY_DISMISSED + userId) { it.add(banner.id) }
        editSet(KEY_PENDING + userId) { it.add(banner.id) }
        sendDismiss(userId, banner.id)
    }

    private fun refresh(account: Int, userId: Long, state: State) {
        val now = SystemClock.elapsedRealtime()
        if (state.call != null || now < state.nextFetchAt) return
        state.nextFetchAt = now + RETRY_MS
        // Dismisses that failed earlier go first, so that the answer already leaves those banners out.
        for (id in stringSet(KEY_PENDING + userId)) sendDismiss(userId, id)
        val call = AdsRetrofitClient.eventsService.bannersActive(BannerViewerRequest(userId.toString()))
        state.call = call
        call.enqueue(object : Callback<Array<SystemBanner>> {
            override fun onResponse(call: Call<Array<SystemBanner>>, response: Response<Array<SystemBanner>>) {
                state.call = null
                val code = response.code()
                if (response.isSuccessful) {
                    val active: List<SystemBanner?> = response.body()?.toList() ?: emptyList()
                    state.banners = active.mapNotNull { toBanner(it) }
                    state.nextFetchAt = SystemClock.elapsedRealtime() + REFRESH_MS
                    pruneDismissed(userId, active)
                    FileLog.d("Novagram ads: banners -> ${state.banners.size}")
                } else {
                    FileLog.d("Novagram ads: banners -> HTTP $code")
                    when (code) {
                        401, 403 -> {
                            keyRejected = true
                            state.banners = emptyList()
                        }
                        // Our bug, and not retried as is. Anything else (5xx) keeps the last list until the next try.
                        400 -> state.nextFetchAt = SystemClock.elapsedRealtime() + REFRESH_MS
                    }
                }
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.novagramBannersUpdated)
            }

            override fun onFailure(call: Call<Array<SystemBanner>>, t: Throwable) {
                state.call = null
                // Class name only: exception messages can carry the backend host.
                FileLog.d("Novagram ads: banners failed: ${t.javaClass.simpleName}")
            }
        })
    }

    private fun toBanner(b: SystemBanner?): Banner? {
        val id = b?.id?.trim()
        if (b == null || id.isNullOrEmpty()) return null
        val title = b.title.orEmpty().trim()
        val text = b.text.orEmpty().trim()
        if (title.isEmpty() && text.isEmpty()) return null
        // An end we cannot read might already be past, so such a banner is left out.
        val endsAt = b.endsAt?.takeIf { it.isNotBlank() }?.let { parseTime(it) ?: return null } ?: 0L
        // Only https:// and tg:// open anything (the backend's rule); any other link, or none, is not clickable.
        val link = b.link?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("tg://", ignoreCase = true) }
        return Banner(id, title, text, link, b.dismissible == true, endsAt)
    }

    // ISO-8601 with an offset, with or without a fraction of a second (java.time comes from desugaring below API 26).
    private fun parseTime(s: String): Long? = try {
        OffsetDateTime.parse(s.trim()).toInstant().toEpochMilli()
    } catch (e: Exception) {
        FileLog.d("Novagram ads: banner ends_at unreadable")
        null
    }

    // Ids the server no longer lists (dismissed there, expired, deleted) are forgotten unless their dismiss has not
    // reached the server yet, which keeps the stored set small.
    private fun pruneDismissed(userId: Long, active: List<SystemBanner?>) {
        val keep = active.mapNotNullTo(HashSet()) { it?.id } + stringSet(KEY_PENDING + userId)
        editSet(KEY_DISMISSED + userId) { set -> set.retainAll(keep) }
    }

    private fun sendDismiss(userId: Long, id: String) {
        val inFlight = "$userId|$id"
        if (!AdsRetrofitClient.enabled || keyRejected || !dismissInFlight.add(inFlight)) return
        AdsRetrofitClient.eventsService.bannerDismiss(BannerDismissRequest(id, userId.toString())).enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                dismissInFlight.remove(inFlight)
                val code = response.code()
                FileLog.d("Novagram ads: banner dismiss -> HTTP $code")
                when {
                    // Done (again), not dismissible, or deleted from the backend: nothing to send again.
                    response.isSuccessful || code == 400 || code == 404 -> editSet(KEY_PENDING + userId) { it.remove(id) }
                    code == 401 || code == 403 -> keyRejected = true
                    // 5xx: stays pending and goes out again with the next refresh.
                }
            }

            override fun onFailure(call: Call<Void>, t: Throwable) {
                dismissInFlight.remove(inFlight)
                FileLog.d("Novagram ads: banner dismiss failed: ${t.javaClass.simpleName}")
            }
        })
    }

    private fun sendClick(id: String, retry: Boolean) {
        AdsRetrofitClient.eventsService.bannerClick(BannerClickRequest(id)).enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                val code = response.code()
                FileLog.d("Novagram ads: banner click -> HTTP $code")
                if (code == 401 || code == 403) {
                    keyRejected = true
                } else if (retry && code >= 500) {
                    retryClick(id)
                }
            }

            override fun onFailure(call: Call<Void>, t: Throwable) {
                FileLog.d("Novagram ads: banner click failed: ${t.javaClass.simpleName}")
                if (retry) retryClick(id)
            }
        })
    }

    // The backend: one more try is enough for a click.
    private fun retryClick(id: String) {
        AndroidUtilities.runOnUIThread({ sendClick(id, retry = false) }, CLICK_RETRY_MS)
    }

    // A banner whose ends_at passes while the chat list is open goes away then, not at the next redraw.
    private fun scheduleExpiry(account: Int, banner: Banner?, now: Long) {
        expiryRunnable?.let { AndroidUtilities.cancelRunOnUIThread(it) }
        expiryRunnable = null
        val endsAt = banner?.endsAtMs ?: 0L
        if (endsAt <= now) return
        val runnable = Runnable {
            expiryRunnable = null
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.novagramBannersUpdated)
        }
        expiryRunnable = runnable
        AndroidUtilities.runOnUIThread(runnable, minOf(endsAt - now + 500L, MAX_EXPIRY_DELAY_MS))
    }

    private fun prefs() = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun stringSet(key: String): Set<String> = prefs().getStringSet(key, null) ?: emptySet()

    private fun editSet(key: String, change: (MutableSet<String>) -> Unit) {
        // The set getStringSet returns must not be changed in place.
        val set = HashSet(stringSet(key))
        change(set)
        prefs().edit().putStringSet(key, set).apply()
    }
}
