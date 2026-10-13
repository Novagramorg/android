package org.fenixuz.analytics

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.google.firebase.FirebaseApp
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Novagram analytics — anonymous growth counters on Firebase Firestore. NO Firebase Analytics / GA4
 * (intentionally: GA4's device-id + location + app-activity collection is far heavier than we need and
 * complicates Play Data Safety). The ONLY datum that ever leaves the device is the Android ID, used
 * purely to de-duplicate installs so one device is counted once. No phone, no name, no location.
 *
 * Firestore layout (the owner creates/edits these in the console):
 *   users/usersCount                 { allUsers }   — total account logins (each login = +1)
 *   uniqueInstallUsers/{androidId}   { created_at } — one doc per device, for de-dup
 *   uniqueInstallUsersCount/usersCount { allUsers } — unique-install total
 *   config/display                   { installBaseOffset } — an OPTIONAL base added to the shown install
 *                                                            number; 0 (no base) when the field is absent.
 *                                                            (same for accountBaseOffset) — NOTHING is hard-coded.
 *
 * Talks to Firestore's REST API over plain HTTPS, not through the Firestore SDK. The SDK's gRPC channel
 * (firebase-firestore 24.0.0 → gRPC 1.41) never connects on a network whose IPv6 route to
 * firestore.googleapis.com is dead: it starts on the IPv6 address and the SDK resets the channel every 15 s,
 * before gRPC ever falls back to IPv4. Seen on the office Wi-Fi in 2026-10: the screen showed "0 users" and no
 * install or login was counted from that network. HttpURLConnection tries the resolved addresses one after
 * another, so with a short connect timeout a dead IPv6 route costs a few seconds and IPv4 answers. Newer SDKs
 * need minSdk 23/24, so upgrading was not an option. Same database, same security rules, no server of our own.
 *
 * Kept from the rewrite of pro's AnalyticsRemote:
 *  1. A local SharedPreferences flag short-circuits after the first successful install count, so normal
 *     launches do ZERO network work.
 *  2. Install de-dup is atomic: one commit creates uniqueInstallUsers/{androidId} with an exists=false
 *     precondition AND increments the counter. If the device doc already exists the whole commit is rejected
 *     (409), so a device is counted exactly once, even after a cache wipe.
 *  3. Increments are upserts (empty update mask + increment transform), which auto-create a missing doc.
 */
object AnalyticsRemote {

    private const val PREF = "db"
    private const val KEY_INSTALL_COUNTED = "fenix_install_counted"

    /** Last successfully shown numbers — re-displayed INSTANTLY on the next open (no network wait). */
    private const val KEY_CACHE_INSTALLS = "fenix_cache_installs"
    private const val KEY_CACHE_ACCOUNTS = "fenix_cache_accounts"

    private const val INSTALLS = "uniqueInstallUsersCount/usersCount"
    private const val ACCOUNTS = "users/usersCount"
    private const val CONFIG = "config/display"

    // Per address: on a dead IPv6 route the connect times out after this long and the next address (IPv4) is
    // tried. Long enough for a slow mobile network, short enough that the fallback lands in seconds.
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 10_000

    // One background thread: calls come from the main thread (login) and the requests are few. Sequential
    // requests also reuse the pooled connection, so only the first one pays for the IPv6 fallback.
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "novagram-analytics") }

    /** Runs [block] on [io]. An exception escaping an executor thread would crash the app; analytics never may. */
    private fun background(block: () -> Unit) = io.execute {
        try {
            block()
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    /**
     * The project's documents root, from google-services.json; null if Firebase isn't initialised. Not cached:
     * a lazy property would remember a null from a too-early call for the rest of the process.
     */
    private fun documents(): String? = try {
        FirebaseApp.getInstance().options.projectId?.let { "projects/$it/databases/(default)/documents" }
    } catch (e: Throwable) {
        null
    }

    /**
     * Count this device once, ever. Cheap: a local flag means after the first success we never touch the
     * network again — even across app updates. If the device's cache is wiped, the flag is lost but the
     * Android-ID precondition still recognises the device, so it is NOT counted twice.
     */
    @JvmStatic
    fun addInstallIfFirstTime(context: Context) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_INSTALL_COUNTED, false)) return  // already counted — no network

        val deviceId = deviceId(context)
        if (deviceId.isNullOrBlank()) return
        val root = documents() ?: return

        background {
            val createDevice = JSONObject()
                .put("update", JSONObject().put("name", "$root/uniqueInstallUsers/$deviceId"))
                .put("currentDocument", JSONObject().put("exists", false))
                .put("updateTransforms", JSONArray().put(
                    JSONObject().put("fieldPath", "created_at").put("setToServerValue", "REQUEST_TIME")))
            val code = commit(root, createDevice, increment("$root/$INSTALLS"))
            // 200 = counted now; 409 = this device was counted before. Anything else (offline, 5xx) leaves the
            // flag unset so the next launch retries — no double-count risk thanks to the precondition.
            if (code == 200 || code == 409) {
                prefs.edit().putBoolean(KEY_INSTALL_COUNTED, true).apply()
            }
        }
    }

    /** Every successful account login = +1 to the total-accounts counter (not de-duplicated, by design). */
    @JvmStatic
    fun addAccount() {
        val root = documents() ?: return
        background { commit(root, increment("$root/$ACCOUNTS")) }
    }

    // ---- Instant-display layer ---------------------------------------------------------------------
    // The screen shows these cached numbers the moment it opens (zero network), then quietly corrects
    // them once the live values arrive.
    //
    // Null means "never read successfully on this install". It must stay distinct from 0: the screen shows a
    // dash for null. Showing 0 instead told users "Novagram has 0 users" whenever the first read had not
    // landed yet.

    /** Last shown install total, or null before the first successful Firebase read. */
    @JvmStatic
    fun cachedInstalls(context: Context): Long? = cached(context, KEY_CACHE_INSTALLS)

    /** Last shown account total, or null before the first successful Firebase read. */
    @JvmStatic
    fun cachedAccounts(context: Context): Long? = cached(context, KEY_CACHE_ACCOUNTS)

    private fun cached(context: Context, key: String): Long? {
        val p = prefs(context)
        return if (p.contains(key)) p.getLong(key, 0L) else null
    }

    /**
     * Live install figure = base offset + real unique installs, cached for an instant next open. Calls back on
     * the UI thread, with the last cached number if the read fails, and with null only when nothing was ever
     * cached.
     */
    @JvmStatic
    fun getInstallsDisplay(context: Context, cb: (Long?) -> Unit) =
        display(context, INSTALLS, "installBaseOffset", KEY_CACHE_INSTALLS, cb)

    /** Live account figure = base offset + real account logins. Same cache approach as [getInstallsDisplay]. */
    @JvmStatic
    fun getAccountsDisplay(context: Context, cb: (Long?) -> Unit) =
        display(context, ACCOUNTS, "accountBaseOffset", KEY_CACHE_ACCOUNTS, cb)

    private fun display(context: Context, counter: String, baseField: String, cacheKey: String, cb: (Long?) -> Unit) {
        val root = documents()
        if (root == null) {
            cb(cached(context, cacheKey))
            return
        }
        background {
            val live = liveTotal(root, counter, baseField)
            if (live != null) prefs(context).edit().putLong(cacheKey, live).apply()
            val shown = live ?: cached(context, cacheKey)  // read failed → keep last known, never wipe to 0
            AndroidUtilities.runOnUIThread { cb(shown) }
        }
    }

    /**
     * Counter + optional base, or null when either read failed or the counter has no value yet. A failed base
     * read counts as a failure too: adding 0 for it would cache a number far below the real one.
     */
    private fun liveTotal(root: String, counter: String, baseField: String): Long? {
        val real = fields(root, counter)?.long("allUsers") ?: return null
        val config = fields(root, CONFIG) ?: return null
        return (config.long(baseField) ?: 0L) + real
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---- Firestore REST ----------------------------------------------------------------------------

    /** An upsert that adds 1 to the doc's allUsers, creating the doc if it is missing. */
    private fun increment(doc: String): JSONObject = JSONObject()
        .put("update", JSONObject().put("name", doc))
        .put("updateMask", JSONObject().put("fieldPaths", JSONArray()))
        .put("updateTransforms", JSONArray().put(
            JSONObject().put("fieldPath", "allUsers").put("increment", JSONObject().put("integerValue", "1"))))

    /** Applies the writes atomically; returns the HTTP status, or -1 when the request never completed. */
    private fun commit(root: String, vararg writes: JSONObject): Int {
        val body = JSONObject().put("writes", JSONArray().apply { writes.forEach { put(it) } })
        return request("POST", "https://firestore.googleapis.com/v1/$root:commit", body.toString())?.first ?: -1
    }

    /** The document's fields, an empty object if it doesn't exist, or null if the request failed. */
    private fun fields(root: String, doc: String): JSONObject? {
        val (code, text) = request("GET", "https://firestore.googleapis.com/v1/$root/$doc", null) ?: return null
        return when (code) {
            200 -> try {
                JSONObject(text).optJSONObject("fields") ?: JSONObject()
            } catch (e: Exception) {
                null
            }
            404 -> JSONObject()
            else -> null
        }
    }

    /** A Firestore number field: integerValue arrives as a string; doubleValue if someone typed 1.0 in the console. */
    private fun JSONObject.long(field: String): Long? {
        val v = optJSONObject(field) ?: return null
        return when {
            v.has("integerValue") -> v.optString("integerValue").toLongOrNull()
            v.has("doubleValue") -> v.optDouble("doubleValue").toLong()
            else -> null
        }
    }

    /** Status code and body, or null on a network error. Runs on [io] only (via [background]). */
    private fun request(method: String, url: String, body: String?): Pair<Int, String>? {
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = READ_TIMEOUT_MS
            c.useCaches = false
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            // Reading the body to the end and closing it hands the connection back to the pool for reuse.
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } catch (e: IOException) {
            c?.disconnect()
            null
        }
    }

    /** Permission-free, stable per app-signing-key (survives reinstall/cache-clear). @SuppressLint only mutes lint. */
    @SuppressLint("HardwareIds")
    private fun deviceId(context: Context): String? = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    } catch (e: Throwable) {
        null
    }
}
