package org.fenixuz.ads

import android.os.SystemClock
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.UserConfig
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * One served ad plus when it was received (wall clock), which bounds how long its events are still worth
 * sending: the token dies 1 h after it was issued.
 */
class AdsServed(val ad: ServedAd, val userId: Long, val receivedAt: Long)

/**
 * Novagram: the slot-independent half of ad serving — builds the request (user id + targeting hints), caches
 * every answer for the backend's `ttl_seconds` (empty answers too, so a slot without an ad is not asked
 * again for 5 minutes) and maps failures to the backend's retry rules. Slot code (search now; channel, bot,
 * video later) only supplies the placement + context and draws the result.
 *
 * UI thread only: callers are on it, and Retrofit delivers callbacks on the main thread on Android, so the
 * state below needs no locking.
 */
object AdsServe {

    const val SEARCH = "SEARCH"

    private const val DEFAULT_TTL_SECONDS = 300
    private const val FAILURE_TTL_SECONDS = 60
    private const val MAX_CACHE_ENTRIES = 64

    private class Entry(val served: AdsServed?, val expiresAt: Long)

    // Access-ordered LRU; keys are "userId|placement|contextKey" so accounts never share an ad (a token is
    // only valid for the user it was served to).
    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > MAX_CACHE_ENTRIES
    }

    // 403 = the key itself is refused. The backend says never retry that, so ads stay off for this process.
    private var keyRejected = false

    /**
     * Asks for the ad in [placement] for [account]. [cb] gets the ad, or null for "no ad" (none matched,
     * error, ads disabled). A cached answer is delivered synchronously and null is returned; otherwise the
     * returned call can be cancelled by the caller (its callback then never fires).
     */
    fun serve(account: Int, placement: String, context: Map<String, Any>, contextKey: String, cb: (AdsServed?) -> Unit): Call<AdsServeResponse>? {
        val userId = UserConfig.getInstance(account).clientUserId
        if (!AdsRetrofitClient.enabled || keyRejected || userId == 0L) {
            cb(null)
            return null
        }
        val key = "$userId|$placement|$contextKey"
        val now = SystemClock.elapsedRealtime()
        val cached = cache[key]
        if (cached != null) {
            if (cached.expiresAt > now) {
                cb(cached.served)
                return null
            }
            cache.remove(key)
        }
        val request = AdsServeRequest(
            placement = placement,
            userId = userId,
            context = context,
            phoneCountry = phoneCountry(account),
            lang = langCode(),
            platform = "android",
            appVersion = BuildVars.BUILD_VERSION_STRING
        )
        val call = AdsRetrofitClient.serveService.serve(request)
        call.enqueue(object : Callback<AdsServeResponse> {
            override fun onResponse(call: Call<AdsServeResponse>, response: Response<AdsServeResponse>) {
                if (call.isCanceled) return
                val at = SystemClock.elapsedRealtime()
                val code = response.code()
                if (response.isSuccessful) {
                    val body = response.body()
                    val ad = body?.ads?.firstOrNull { !it.randomId.isNullOrEmpty() }
                    // Wall clock, not elapsedRealtime: the event queue outlives reboots and drops by this age.
                    val served = if (ad != null) AdsServed(ad, userId, System.currentTimeMillis()) else null
                    val ttl = (body?.ttlSeconds ?: DEFAULT_TTL_SECONDS).coerceIn(10, 3600)
                    cache[key] = Entry(served, at + ttl * 1000L)
                    FileLog.d("Novagram ads: serve $placement -> ${if (served != null) "@" + served.ad.promoted?.username else "no ad"}, ttl $ttl")
                    cb(served)
                    return
                }
                when (code) {
                    // 400 is our bug, never retried as is; the 5-minute empty entry keeps us from repeating it.
                    400 -> cache[key] = Entry(null, at + DEFAULT_TTL_SECONDS * 1000L)
                    403 -> keyRejected = true
                    // 60 serves per viewer per minute; the window resets at the start of the next minute.
                    429 -> cache[key] = Entry(null, at + (60_000L - System.currentTimeMillis() % 60_000L))
                    // 503 / 5xx: treat as empty, ask again after at least a minute.
                    else -> cache[key] = Entry(null, at + FAILURE_TTL_SECONDS * 1000L)
                }
                FileLog.d("Novagram ads: serve $placement -> HTTP $code")
                cb(null)
            }

            override fun onFailure(call: Call<AdsServeResponse>, t: Throwable) {
                // Class name only: exception messages can carry the backend host.
                FileLog.d("Novagram ads: serve $placement failed: ${t.javaClass.simpleName}${if (call.isCanceled) " (cancelled)" else ""}")
                if (call.isCanceled) return
                // Network error / timeout: no retry now, the next open of this slot after a minute asks again.
                cache[key] = Entry(null, SystemClock.elapsedRealtime() + FAILURE_TTL_SECONDS * 1000L)
                cb(null)
            }
        })
        return call
    }

    // ---- targeting hints (all optional for the backend) ----

    private fun langCode(): String? {
        val info = LocaleController.getInstance().currentLocaleInfo ?: return null
        // pluralLangCode is the bare language ("uz", "ru", "en"). The pack's shortName can carry suffixes the
        // backend does not know (a cloud pack id), and an unknown language is ignored there anyway.
        val code = if (!info.pluralLangCode.isNullOrEmpty()) info.pluralLangCode else info.langCode
        return code?.takeIf { it.isNotEmpty() && it.length <= 64 }
    }

    // calling code -> ISO-2 codes sharing it, from Telegram's own countries.txt ("998;UZ;Uzbekistan;...").
    private var callingCodes: HashMap<String, ArrayList<String>>? = null
    private val countryByPhone = HashMap<String, String?>()

    /**
     * ISO-2 country of the account's phone number. Only the country leaves the device, never the number.
     * Null when unknown — the backend then falls back to the IP's country.
     */
    private fun phoneCountry(account: Int): String? {
        val phone = UserConfig.getInstance(account).currentUser?.phone
        if (phone.isNullOrEmpty()) return null
        if (countryByPhone.containsKey(phone)) return countryByPhone[phone]
        val iso = resolveCountry(phone.filter { it.isDigit() })
        countryByPhone[phone] = iso
        return iso
    }

    private fun resolveCountry(digits: String): String? {
        if (digits.isEmpty()) return null
        // +7 is shared: 7 6xx / 7 7xx is Kazakhstan, the rest Russia.
        if (digits[0] == '7') {
            return if (digits.length > 1 && (digits[1] == '6' || digits[1] == '7')) "KZ" else "RU"
        }
        val codes = callingCodes ?: loadCallingCodes().also { callingCodes = it }
        for (len in minOf(4, digits.length) downTo 1) {
            val isos = codes[digits.substring(0, len)] ?: continue
            // A code shared by several countries (+1) can't name one; leave it to the backend's IP lookup.
            return if (isos.size == 1) isos[0] else null
        }
        return null
    }

    private fun loadCallingCodes(): HashMap<String, ArrayList<String>> {
        val map = HashMap<String, ArrayList<String>>()
        try {
            ApplicationLoader.applicationContext.assets.open("countries.txt").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val parts = line.split(';')
                    if (parts.size < 2 || parts[1].length != 2) continue
                    map.getOrPut(parts[0]) { ArrayList() }.add(parts[1].uppercase())
                }
            }
        } catch (e: Exception) {
            FileLog.e(e)
        }
        return map
    }
}
