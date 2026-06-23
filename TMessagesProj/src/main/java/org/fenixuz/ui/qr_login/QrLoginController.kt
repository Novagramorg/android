package org.fenixuz.ui.qr_login

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.fenixuz.utils.LanguageCode
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLog
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.LoginActivity
import java.lang.ref.WeakReference
import java.util.Arrays

/**
 * Novagram QR-login. Displays a QR on THIS device; when it is scanned by an already logged-in
 * Telegram (Settings > Devices > Link Device) this device gets logged in — no phone number / SMS.
 *
 * This is a clean rewrite of pro's `CreateQrLogin` with senior-grade correctness/perf fixes:
 *  - NO static `LoginActivity` reference: the fragment is held only weakly and released on teardown,
 *    so a dismissed dialog can never leak the login Activity.
 *  - QR is encoded OFF the main thread (pro encoded on the UI thread → frame jank); the bitmap is
 *    only re-encoded when the token actually changes (cheap byte compare), avoiding needless work.
 *  - The in-flight request is cancelled and the poll loop stopped the instant the dialog is dismissed
 *    (bounded lifecycle, no leaked Handler — scheduling goes through the cancellable app looper).
 *  - `loginTokenMigrateTo` (cross-DC) IS handled via `importLoginToken` on the target DC. Pro left this
 *    commented out, so its QR login silently failed for every user whose account lives on a DC other
 *    than the app default — this is the main correctness fix.
 *  - The token is Base64 URL_SAFE/NO_PADDING/NO_WRAP encoded to match exactly what a stock Telegram
 *    scanner decodes (`Base64.URL_SAFE`, see LaunchActivity). Pro used `Base64.DEFAULT`, which yields
 *    `+`/`/`/newlines and would not decode cleanly on the scanning device.
 *
 * Lifecycle: every callback re-checks `destroyed`; all UI work is posted to the main looper; the
 * controller owns exactly one dialog and one poll chain, both torn down together.
 */
class QrLoginController(
    loginActivity: LoginActivity,
    private val context: Context,
    private val onClosed: Runnable? = null
) {

    // The account slot being logged into — for "add account" this is the NEW slot the LoginActivity was
    // created with (LoginActivity(int account) → currentAccount), NOT the currently-visible account.
    // exportLoginToken / onAuthSuccess must run on this slot, else the new account lands on the wrong
    // session and shows the existing account's dialogs.
    private val account = loginActivity.currentAccount
    private val fragmentRef = WeakReference(loginActivity)

    private lateinit var qrImage: ImageView
    private var dialog: AlertDialog? = null

    @Volatile private var destroyed = false
    private var currentReqId = 0
    private var lastToken: ByteArray? = null

    // Re-poll exportLoginToken; the same call both refreshes the QR and detects that the QR was
    // accepted (it then returns success/migrate instead of a new token). Kept as a field so it can
    // be cancelled precisely on dismiss.
    private val pollRunnable = Runnable { requestToken() }

    fun show() {
        val card = FrameLayout(context).apply {
            // Always white behind the QR so it scans reliably in both light and dark themes.
            setBackgroundColor(Color.WHITE)
        }
        qrImage = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        // Fill the card (FIT_CENTER scales the QR bitmap down) so the card can shrink on narrow / split-screen
        // windows without clipping the QR.
        card.addView(qrImage, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat(), Gravity.CENTER, 12f, 12f, 12f, 12f))

        val hint = TextView(context).apply {
            text = LanguageCode.getMyTitles(259)
            setTextColor(Theme.getColor(Theme.key_dialogTextGray2))
            textSize = 14f
            gravity = Gravity.START
            setLineSpacing(AndroidUtilities.dp(4f).toFloat(), 1f)
        }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(AndroidUtilities.dp(20f), AndroidUtilities.dp(8f), AndroidUtilities.dp(20f), AndroidUtilities.dp(8f))
            // Clamp the QR card to the current window width (handles narrow phones & split-screen) so it never
            // overflows; on normal phones it stays the full QR_DP + 24.
            val qrCardSize = Math.min(QR_DP + 24, (AndroidUtilities.displaySize.x / AndroidUtilities.density).toInt() - 80)
            addView(card, LayoutHelper.createLinear(qrCardSize, qrCardSize, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0))
            addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 20, 0, 0))
        }

        val builder = AlertDialog.Builder(context)
        builder.setTitle(LanguageCode.getMyTitles(258))
        builder.setView(layout)
        builder.setOnDismissListener { destroy() }
        dialog = builder.create()
        try {
            dialog?.show()
        } catch (e: Exception) {
            FileLog.e(e)
            destroy()
            return
        }

        requestToken()
    }

    private fun requestToken() {
        if (destroyed) return
        val req = TLRPC.TL_auth_exportLoginToken()
        req.api_id = BuildVars.APP_ID
        req.api_hash = BuildVars.APP_HASH
        // Don't offer to re-add an account that's already signed in on this device.
        for (i in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (UserConfig.isValidAccount(i)) {
                req.except_ids.add(UserConfig.getInstance(i).clientUserId)
            }
        }
        currentReqId = ConnectionsManager.getInstance(account).sendRequest(req, { response, error ->
            AndroidUtilities.runOnUIThread { onTokenResponse(response, error) }
        }, ConnectionsManager.RequestFlagWithoutLogin or ConnectionsManager.RequestFlagFailOnServerErrors)
    }

    // loginTokenMigrateTo: the authorizing account lives on another DC — finish the login there.
    private fun importToken(dcId: Int, token: ByteArray) {
        if (destroyed) return
        val req = TLRPC.TL_auth_importLoginToken()
        req.token = token
        currentReqId = ConnectionsManager.getInstance(account).sendRequest(
            req,
            { response, error -> AndroidUtilities.runOnUIThread { onTokenResponse(response, error) } },
            null,
            null,
            ConnectionsManager.RequestFlagWithoutLogin or ConnectionsManager.RequestFlagFailOnServerErrors,
            dcId,
            ConnectionsManager.ConnectionTypeGeneric,
            true
        )
    }

    private fun onTokenResponse(response: TLObject?, error: TLRPC.TL_error?) {
        if (destroyed) return
        currentReqId = 0
        if (error != null) {
            handleError(error)
            return
        }
        when (response) {
            is TLRPC.TL_auth_loginToken -> {
                generateQr(response.token)
                scheduleNextPoll()
            }
            is TLRPC.TL_auth_loginTokenMigrateTo -> importToken(response.dc_id, response.token)
            is TLRPC.TL_auth_loginTokenSuccess -> handleSuccess(response.authorization)
            else -> scheduleNextPoll()
        }
    }

    private fun handleSuccess(authorization: TLRPC.auth_Authorization?) {
        val frag = fragmentRef.get()
        dismissDialog()
        if (frag != null && authorization is TLRPC.TL_auth_authorization) {
            frag.onAuthSuccessForQr(authorization)
        }
        destroy()
    }

    private fun handleError(error: TLRPC.TL_error) {
        val text = error.text
        if (text != null && text.contains("SESSION_PASSWORD_NEEDED")) {
            // Account has 2FA — hand off to the login screen's native password page.
            val frag = fragmentRef.get()
            dismissDialog()
            frag?.onQrLoginNeedPassword()
            destroy()
        } else {
            // Transient (e.g. token expired) — the next export refreshes the QR. Bounded by the dialog.
            scheduleNextPoll()
        }
    }

    private fun generateQr(token: ByteArray) {
        if (lastToken != null && Arrays.equals(lastToken, token)) {
            return // unchanged token → no need to re-encode
        }
        lastToken = token
        val encoded = Base64.encodeToString(token, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val text = "$QR_SCHEME$encoded"
        Utilities.globalQueue.postRunnable {
            val bitmap = encode(text)
            if (bitmap != null) {
                AndroidUtilities.runOnUIThread {
                    if (!destroyed) qrImage.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun encode(text: String): Bitmap? {
        return try {
            val hints = HashMap<EncodeHintType, Any>()
            hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
            hints[EncodeHintType.MARGIN] = 0
            QRCodeWriter().encode(text, QR_PX, QR_PX, hints, null)
        } catch (e: Exception) {
            FileLog.e(e)
            null
        }
    }

    private fun scheduleNextPoll() {
        if (destroyed) return
        AndroidUtilities.cancelRunOnUIThread(pollRunnable)
        AndroidUtilities.runOnUIThread(pollRunnable, POLL_INTERVAL_MS)
    }

    // Public teardown for the host fragment (e.g. fragment destroyed while the dialog is open).
    fun close() {
        dismissDialog()
        destroy()
    }

    private fun dismissDialog() {
        try {
            dialog?.dismiss()
        } catch (ignore: Exception) {
        }
    }

    // Idempotent teardown: stop the poll chain, cancel any in-flight request, drop all references.
    private fun destroy() {
        if (destroyed) return
        destroyed = true
        AndroidUtilities.cancelRunOnUIThread(pollRunnable)
        if (currentReqId != 0) {
            ConnectionsManager.getInstance(account).cancelRequest(currentReqId, true)
            currentReqId = 0
        }
        fragmentRef.clear()
        dialog = null
        lastToken = null
        onClosed?.run()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2000L
        private const val QR_PX = 768           // encode resolution (down-scaled into the view)
        private const val QR_DP = 220           // on-screen QR size
        private const val QR_SCHEME = "tg://login?token="
    }
}
