package org.fenixuz.review

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.google.android.play.core.review.ReviewManagerFactory
import org.fenixuz.analytics.AnalyticsRemote
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.browser.Browser
import org.telegram.messenger.voip.VoIPService
import org.telegram.ui.ChatActivity
import org.telegram.ui.LaunchActivity
import org.telegram.ui.PaymentFormActivity

/**
 * Novagram: asks for a Google Play rating with Play's own in-app review card, at the moment a user has just finished
 * using one of the Novagram features ([Feature]) for the fifth time. The rules live in [ReviewRules]; this object is
 * the Android side: where the state is kept, when the moment is right, and the call into Play.
 *
 * Two kinds of moments, decided per call site:
 *  - [used] with `askNow = true`: the feature's result is instant (ghost mode switched on, a story saved, a message
 *    sent), so the card may come up right there;
 *  - [used] with `askNow = false` + [askPending] when the screen closes: the feature's result is something the user
 *    reads (edit history, a locked chat, a translation), so the card waits until they are done with it.
 *
 * Google's rules, all followed here: no question before or around the card, no button that triggers it, the card is
 * shown as Play draws it and never dismissed by the app. Play decides whether the card actually appears (quota, user
 * already rated) and never tells the app, so [ReviewRules.markAsked] counts the request, not a rating.
 *
 * Only on a copy installed from Google Play: the card does not exist elsewhere, and the RuStore/Huawei builds have their
 * own stores. The feature IDs are shared with the iOS/macOS apps so the three platforms can be compared.
 */
object AppReview {

    enum class Feature(val id: String) {
        GHOST_MODE("ghost_mode"),
        STORY_DOWNLOAD("story_download"),
        EDIT_HISTORY("edit_history"),
        CHAT_LOCK("chat_lock"),
        SECURITY_VAULT("security_vault"),
        MESSAGE_TRANSLATE("message_translate"),
        VOICE_TO_TEXT("voice_to_text"),
        VOICE_TRANSLATE("voice_translate"),
        ROUND_VIDEO("round_video"),
        NO_NAME_FORWARD("no_name_forward"),
        FIRST_MESSAGE("first_message"),
    }

    private const val PREFS = "novagram_review"
    private const val PLAY_INSTALLER = "com.android.vending"

    /** Published store package IDs: fixed, so a store link never breaks on a suffixed debug build. */
    const val PLAY_PACKAGE = "uz.codingtech.messengerapp"
    const val RUSTORE_PACKAGE = "org.messenger_pro.messenger"
    /** The support admin "Send feedback" opens a chat with. */
    const val SUPPORT_ADMIN = "Avazbekedu"

    private val context: Context get() = ApplicationLoader.applicationContext

    private val rules: ReviewRules by lazy {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val store = object : ReviewRules.Store {
            override fun getInt(key: String, def: Int) = prefs.getInt(key, def)
            override fun getLong(key: String, def: Long) = prefs.getLong(key, def)
            override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
            override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
        }
        var installTime = System.currentTimeMillis()
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            installTime = info.firstInstallTime
            @Suppress("DEPRECATION")
            versionCode = info.versionCode
        } catch (e: Exception) {
            FileLog.e(e)
        }
        ReviewRules(store, { System.currentTimeMillis() }, installTime, versionCode)
    }

    /** This build's versionCode, read with the install time; 0 until [rules] has been touched. */
    private var versionCode = 0

    /** A feature over the threshold whose screen is still open; asked for when that screen closes. */
    @Volatile private var pending: Feature? = null

    /** A request is on its way to Play; no second one until it has returned. */
    @Volatile private var requesting = false

    /**
     * One successful use of [feature], from any thread. With [askNow] the card may come up at once; otherwise the use is
     * remembered and the card waits for [askPending].
     */
    @JvmStatic
    @JvmOverloads
    fun used(feature: Feature, askNow: Boolean = true) {
        AndroidUtilities.runOnUIThread {
            val count = rules.recordUse(feature.id)
            FileLog.d("AppReview: ${feature.id} used, count=$count, askNow=$askNow")
            if (askNow) {
                tryAsk(feature)
            } else if (count >= ReviewRules.USES_TO_ASK) {
                pending = feature
            }
        }
    }

    /**
     * The screen a `askNow = false` feature was used on has closed (or the user otherwise finished with its result):
     * ask now if one of [features] is waiting. Naming the features keeps a chat's close from asking for a feature whose
     * own screen is still open.
     */
    @JvmStatic
    fun askPending(vararg features: Feature) {
        val feature = pending ?: return
        if (feature !in features) return
        pending = null
        AndroidUtilities.runOnUIThread { tryAsk(feature) }
    }

    private fun tryAsk(feature: Feature) {
        if (requesting) return
        val block = rules.block(feature.id)
        if (block != ReviewRules.Block.NONE) {
            FileLog.d("AppReview: not asking for ${feature.id}: $block")
            return
        }
        val activity = LaunchActivity.instance
        val why = badMoment(activity)
        if (why != null) {
            FileLog.d("AppReview: not asking for ${feature.id}: $why")
            return
        }
        request(activity!!, feature)
    }

    /** Why the card must not come up right now, or null when it may. */
    private fun badMoment(activity: LaunchActivity?): String? {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return "no activity"
        if (ApplicationLoader.mainInterfacePaused) return "app in background"
        if (!isPlayInstall()) return "not installed from Google Play"
        if (SharedConfig.passcodeHash.isNotEmpty() && SharedConfig.appLocked) return "passcode screen"
        if (VoIPService.getSharedInstance() != null) return "call in progress"
        val fragment = LaunchActivity.getLastFragment()
        if (fragment is PaymentFormActivity) return "payment in progress"
        if (fragment is ChatActivity && fragment.isRecordingAudioVideo) return "recording"
        return null
    }

    private fun request(activity: Activity, feature: Feature) {
        requesting = true
        FileLog.d("AppReview: requesting the review card for ${feature.id}")
        try {
            val manager = ReviewManagerFactory.create(activity)
            manager.requestReviewFlow().addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    requesting = false
                    FileLog.e("AppReview: requestReviewFlow failed", task.exception)
                    return@addOnCompleteListener
                }
                val current = LaunchActivity.instance
                if (current == null || current.isFinishing || current.isDestroyed) {
                    requesting = false
                    return@addOnCompleteListener
                }
                // Counted only now that the card is really handed to Play: a failed request must not use up one of
                // the three lifetime asks or block this version.
                rules.markAsked()
                FileLog.d("AppReview: launching the review card, request #${rules.requestCount()}")
                // How often we ask, for the Play Console comparison: three anonymous counters, nothing about the user.
                AnalyticsRemote.addReviewRequest(feature.id, versionCode)
                manager.launchReviewFlow(current, task.result).addOnCompleteListener {
                    // Play says nothing about whether the card was shown or a rating left; this only means "done".
                    requesting = false
                    FileLog.d("AppReview: review flow finished")
                }
            }
        } catch (e: Exception) {
            requesting = false
            FileLog.e(e)
        }
    }

    /** Whether this copy came from Google Play, the only place the review card exists. */
    @JvmStatic
    fun isPlayInstall(): Boolean = try {
        val pm = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        }
        PLAY_INSTALLER == installer
    } catch (e: Exception) {
        false
    }

    // ---- Settings rows ("Rate Novagram", "Send feedback") ----

    /** Opens this build's store page: RuStore for the RuStore build, Google Play otherwise. A tap, not the card. */
    @JvmStatic
    fun openStorePage(context: Context) {
        if (context.packageName.startsWith(RUSTORE_PACKAGE)) {
            openStore(context, "rustore://apps.rustore.ru/app/$RUSTORE_PACKAGE", "ru.vk.store",
                "https://apps.rustore.ru/app/$RUSTORE_PACKAGE")
        } else {
            openStore(context, "market://details?id=$PLAY_PACKAGE", PLAY_INSTALLER,
                "https://play.google.com/store/apps/details?id=$PLAY_PACKAGE")
        }
    }

    private fun openStore(context: Context, storeUri: String, storePackage: String, webUrl: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(storeUri))
                .setPackage(storePackage)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        } catch (e: Exception) {
            // Store app absent: the web listing (the store intercepts it when present, else the browser).
            Browser.openUrl(context, webUrl)
        }
    }

    /** Opens a chat with the support admin. */
    @JvmStatic
    fun openFeedback(context: Context) {
        Browser.openUrl(context, "https://t.me/$SUPPORT_ADMIN")
    }
}
