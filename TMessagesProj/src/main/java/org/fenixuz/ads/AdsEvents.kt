package org.fenixuz.ads

import android.content.Context
import android.os.SystemClock
import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Novagram: the ad event queue (view / click / action / video_open → POST /api/ads/events/).
 *
 * Events are batched (up to 50 per request, one user id per request, order kept so a click always precedes
 * its action) and persisted, so a view that bills the advertiser survives the app being killed or offline.
 * The backend counts every (random_id, type) once, so resending a batch after a timeout or 5xx is safe.
 * A token dies 1 h after it was issued; anything older than 55 minutes is dropped instead of sent.
 *
 * UI thread only, like [AdsServe]: callers are on it and Retrofit calls back on the main thread.
 */
object AdsEvents {

    const val VIEW = "view"
    const val CLICK = "click"
    const val ACTION = "action"

    private const val PREFS = "novagram_ads"
    private const val KEY_QUEUE = "event_queue"

    private const val MAX_AGE_MS = 55 * 60 * 1000L
    private const val MAX_BATCH = 50
    private const val FLUSH_AT_COUNT = 20
    private const val FLUSH_DELAY_MS = 5_000L
    private const val MAX_QUEUE = 500
    private const val MIN_BACKOFF_MS = 2_000L
    private const val MAX_BACKOFF_MS = 300_000L
    private const val MAX_RECORDED = 2_000

    @Keep
    data class Queued(
        @SerializedName("user_id") val userId: Long,
        @SerializedName("random_id") val randomId: String,
        @SerializedName("type") val type: String,
        // Wall-clock time the ad (its token) was received.
        @SerializedName("issued_at") val issuedAt: Long
    )

    private val gson = Gson()
    private var queue: ArrayList<Queued>? = null

    // "randomId|type" pairs already queued by this process: one view (and one click, ...) per token. The backend
    // would reject a repeat anyway; this keeps a re-bound row from producing traffic. Insertion-ordered so the
    // oldest can be trimmed.
    private val recorded = LinkedHashSet<String>()

    private var inFlight: Call<AdsEventsResponse>? = null
    private var backoffMs = 0L
    private var retryAt = 0L
    private var scheduledFor = 0L
    private val flushRunnable = Runnable {
        scheduledFor = 0L
        flush()
    }

    /** Queues [type] for [served]. [urgent] (a click) sends right away instead of waiting for the batch. */
    fun add(served: AdsServed, type: String, urgent: Boolean = false) {
        if (!AdsRetrofitClient.enabled) return
        val randomId = served.ad.randomId ?: return
        if (!recorded.add("$randomId|$type")) return
        if (recorded.size > MAX_RECORDED) {
            recorded.remove(recorded.first())
        }
        val q = load()
        q.add(Queued(served.userId, randomId, type, served.receivedAt))
        while (q.size > MAX_QUEUE) {
            q.removeAt(0)
        }
        save()
        schedule(if (urgent || q.size >= FLUSH_AT_COUNT) 0L else FLUSH_DELAY_MS)
    }

    /** App went to the background: send what is queued now (it stays on disk if this fails). */
    fun onAppPaused() {
        if (!AdsRetrofitClient.enabled) return
        if (load().isNotEmpty()) {
            schedule(0L)
        }
    }

    /** App came to the foreground: pick up whatever an earlier session or a failed send left on disk. */
    fun onAppResumed() {
        if (!AdsRetrofitClient.enabled) return
        if (load().isNotEmpty()) {
            schedule(FLUSH_DELAY_MS)
        }
    }

    private fun schedule(delayMs: Long) {
        val at = maxOf(SystemClock.elapsedRealtime() + delayMs, retryAt)
        if (scheduledFor != 0L && scheduledFor <= at) {
            return
        }
        AndroidUtilities.cancelRunOnUIThread(flushRunnable)
        scheduledFor = at
        AndroidUtilities.runOnUIThread(flushRunnable, at - SystemClock.elapsedRealtime())
    }

    private fun flush() {
        if (inFlight != null) {
            // Its callback reschedules.
            return
        }
        val q = load()
        val now = System.currentTimeMillis()
        if (q.removeAll { now - it.issuedAt > MAX_AGE_MS }) {
            save()
        }
        if (q.isEmpty()) {
            return
        }
        if (SystemClock.elapsedRealtime() < retryAt) {
            schedule(0L)
            return
        }
        val userId = q[0].userId
        val batch = ArrayList<Queued>()
        for (e in q) {
            if (e.userId == userId) {
                batch.add(e)
                if (batch.size == MAX_BATCH) break
            }
        }
        val call = AdsRetrofitClient.eventsService.events(AdsEventsRequest(userId, batch.map { AdsEventItem(it.randomId, it.type) }))
        inFlight = call
        call.enqueue(object : Callback<AdsEventsResponse> {
            override fun onResponse(call: Call<AdsEventsResponse>, response: Response<AdsEventsResponse>) {
                inFlight = null
                val code = response.code()
                when {
                    // 200 removes the batch even when some events were rejected: those are duplicates or dead
                    // tokens, and resending them would never be accepted.
                    response.isSuccessful -> {
                        val body = response.body()
                        FileLog.d("Novagram ads: events ${batch.joinToString(",") { it.type }} -> accepted ${body?.accepted}, rejected ${body?.rejected}")
                        done(batch, true)
                    }
                    // 400 = malformed batch, 403 = key refused: resending the same thing cannot succeed.
                    code == 400 || code == 403 -> {
                        FileLog.d("Novagram ads: events dropped, HTTP $code")
                        done(batch, true)
                    }
                    // 60 requests per viewer per minute; the window resets at the start of the next minute.
                    code == 429 -> {
                        retryAt = SystemClock.elapsedRealtime() + (60_000L - System.currentTimeMillis() % 60_000L)
                        done(batch, false)
                    }
                    else -> {
                        backOff()
                        done(batch, false)
                    }
                }
            }

            override fun onFailure(call: Call<AdsEventsResponse>, t: Throwable) {
                inFlight = null
                backOff()
                done(batch, false)
            }
        })
    }

    private fun done(batch: List<Queued>, remove: Boolean) {
        val q = load()
        if (remove) {
            backoffMs = 0L
            q.removeAll(batch.toSet())
            save()
        }
        if (q.isNotEmpty()) {
            schedule(0L)
        }
    }

    // 2 s, 4 s, 8 s ... up to 5 minutes, ±20% jitter.
    private fun backOff() {
        backoffMs = if (backoffMs == 0L) MIN_BACKOFF_MS else minOf(backoffMs * 2, MAX_BACKOFF_MS)
        val jitter = 0.8 + Math.random() * 0.4
        retryAt = SystemClock.elapsedRealtime() + (backoffMs * jitter).toLong()
    }

    private fun prefs() = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(): ArrayList<Queued> {
        queue?.let { return it }
        val loaded = ArrayList<Queued>()
        val json = prefs().getString(KEY_QUEUE, null)
        if (!json.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<ArrayList<Queued?>>() {}.type
                val parsed: ArrayList<Queued?>? = gson.fromJson(json, type)
                // Gson fills fields reflectively, so a damaged entry can hold nulls its Kotlin types don't
                // admit. Reading through the nullable element keeps these checks from being compiled away.
                parsed?.forEach { e ->
                    val randomId: String? = e?.randomId
                    val eventType: String? = e?.type
                    if (e != null && randomId != null && eventType != null) {
                        loaded.add(e)
                    }
                }
            } catch (e: Exception) {
                FileLog.e(e)
            }
        }
        queue = loaded
        return loaded
    }

    private fun save() {
        val q = queue ?: return
        val editor = prefs().edit()
        if (q.isEmpty()) {
            editor.remove(KEY_QUEUE)
        } else {
            editor.putString(KEY_QUEUE, gson.toJson(q))
        }
        editor.apply()
    }
}
