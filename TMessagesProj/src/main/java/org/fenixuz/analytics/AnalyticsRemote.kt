package org.fenixuz.analytics

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import org.telegram.messenger.AndroidUtilities

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
 * Clean rewrite of pro's AnalyticsRemote, fixing its three real flaws:
 *  1. Pro hit Firestore.get() on EVERY first-screen show. Here a local SharedPreferences flag short-circuits
 *     after the first successful count, so normal launches do ZERO network work.
 *  2. Pro's install de-dup was a non-atomic get-then-set — two fast launches could both see "absent" and
 *     double-count. Here it is a single Firestore transaction: the doc is read and the counter incremented
 *     atomically, so a device is counted exactly once.
 *  3. Pro incremented with .update(), which THROWS if the counter doc doesn't exist yet (so the very first
 *     increment silently failed unless the doc was pre-created by hand). Here every increment is a
 *     set(increment, merge), which auto-creates the doc.
 */
object AnalyticsRemote {

    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private const val PREF = "db"
    private const val KEY_INSTALL_COUNTED = "fenix_install_counted"

    /** Last successfully shown numbers — re-displayed INSTANTLY on the next open (no network wait). */
    private const val KEY_CACHE_INSTALLS = "fenix_cache_installs"
    private const val KEY_CACHE_ACCOUNTS = "fenix_cache_accounts"

    /**
     * Count this device once, ever. Cheap: a local flag means after the first success we never touch the
     * network again — even across app updates. If the device's cache is wiped, the flag is lost but the
     * Android-ID transaction still recognises the device, so it is NOT counted twice. Safe to call from a
     * background thread (Firestore manages its own threads).
     */
    @JvmStatic
    fun addInstallIfFirstTime(context: Context) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_INSTALL_COUNTED, false)) return  // already counted — no network

        val deviceId = deviceId(context)
        if (deviceId.isNullOrBlank()) return

        val installRef = db.collection("uniqueInstallUsers").document(deviceId)
        val counterRef = db.collection("uniqueInstallUsersCount").document("usersCount")

        db.runTransaction<Boolean> { tx ->
            if (!tx.get(installRef).exists()) {
                tx.set(installRef, mapOf("created_at" to FieldValue.serverTimestamp()))
                tx.set(counterRef, mapOf("allUsers" to FieldValue.increment(1)), SetOptions.merge())
            }
            true
        }.addOnSuccessListener {
            prefs.edit().putBoolean(KEY_INSTALL_COUNTED, true).apply()
        }
        // On failure we leave the flag unset so the next launch retries — no double-count risk thanks to the
        // Android-ID transaction.
    }

    /** Every successful account login = +1 to the total-accounts counter (not de-duplicated, by design). */
    @JvmStatic
    fun addAccount() {
        db.collection("users").document("usersCount")
            .set(mapOf("allUsers" to FieldValue.increment(1)), SetOptions.merge())
    }

    // All four reads return null when the doc/field is missing OR the network read fails — so callers can
    // tell "no data yet" apart from a real zero. Nothing is hard-coded; every number comes from Firestore.
    fun getInstallCount(cb: (Long?) -> Unit) = read("uniqueInstallUsersCount", "usersCount", "allUsers", cb)

    fun getAccountCount(cb: (Long?) -> Unit) = read("users", "usersCount", "allUsers", cb)

    /** Optional base for the install count; comes ONLY from config/display.installBaseOffset (treated as 0 if absent). */
    fun getInstallBaseOffset(cb: (Long?) -> Unit) = read("config", "display", "installBaseOffset", cb)

    /** Optional base for the account count; comes ONLY from config/display.accountBaseOffset (treated as 0 if absent). */
    fun getAccountBaseOffset(cb: (Long?) -> Unit) = read("config", "display", "accountBaseOffset", cb)

    // ---- Instant-display layer ---------------------------------------------------------------------
    // The screen shows these cached numbers the moment it opens (zero network), then quietly corrects
    // them once the live values arrive — so the user never stares at an empty/placeholder figure.

    /** Last shown install total, or 0 before the first successful Firebase read. No fake base. */
    @JvmStatic
    fun cachedInstallsOrBase(context: Context): Long =
        prefs(context).getLong(KEY_CACHE_INSTALLS, 0L)

    /** Last shown account total, or 0 before the first successful Firebase read. No fake base. */
    @JvmStatic
    fun cachedAccountsOrBase(context: Context): Long =
        prefs(context).getLong(KEY_CACHE_ACCOUNTS, 0L)

    /**
     * Live install figure = base offset + real unique installs. The two reads fire in PARALLEL (not
     * chained) to halve latency, and the combined result is cached for an instant next open.
     */
    @JvmStatic
    fun getInstallsDisplay(context: Context, cb: (Long) -> Unit) {
        var base: Long? = null; var baseDone = false
        var real: Long? = null; var realDone = false
        fun emit() {
            if (!baseDone || !realDone) return          // wait for BOTH reads to come back
            if (real != null) {                         // live count arrived → base(or 0) + real, then cache it
                val total = (base ?: 0L) + real!!
                prefs(context).edit().putLong(KEY_CACHE_INSTALLS, total).apply()
                cb(total)
            } else {
                cb(cachedInstallsOrBase(context))       // read failed → keep last known, never wipe to 0
            }
        }
        getInstallBaseOffset { base = it; baseDone = true; emit() }
        getInstallCount { real = it; realDone = true; emit() }
    }

    /**
     * Live account figure = base offset + real account logins. Same parallel-read + cache approach as
     * [getInstallsDisplay], so it shows instantly next time and never stalls on the network.
     */
    @JvmStatic
    fun getAccountsDisplay(context: Context, cb: (Long) -> Unit) {
        var base: Long? = null; var baseDone = false
        var real: Long? = null; var realDone = false
        fun emit() {
            if (!baseDone || !realDone) return
            if (real != null) {
                val total = (base ?: 0L) + real!!
                prefs(context).edit().putLong(KEY_CACHE_ACCOUNTS, total).apply()
                cb(total)
            } else {
                cb(cachedAccountsOrBase(context))
            }
        }
        getAccountBaseOffset { base = it; baseDone = true; emit() }
        getAccountCount { real = it; realDone = true; emit() }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // Returns the Long field, or null when the doc/field is missing or the network read fails.
    private fun read(col: String, doc: String, field: String, cb: (Long?) -> Unit) {
        db.collection(col).document(doc).get()
            .addOnSuccessListener { d ->
                val v = if (d != null && d.exists()) d.getLong(field) else null
                AndroidUtilities.runOnUIThread { cb(v) }
            }
            .addOnFailureListener { AndroidUtilities.runOnUIThread { cb(null) } }
    }

    /** Permission-free, stable per app-signing-key (survives reinstall/cache-clear). @SuppressLint only mutes lint. */
    @SuppressLint("HardwareIds")
    private fun deviceId(context: Context): String? = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    } catch (e: Throwable) {
        null
    }
}
