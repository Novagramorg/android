package org.fenixuz.ui.bot_login

import android.content.Context
import android.content.DialogInterface
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.fenixuz.utils.LanguageCode
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLog
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.InputSerializedData
import org.telegram.tgnet.OutputSerializedData
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.LoginActivity
import java.lang.ref.WeakReference

/**
 * Novagram bot-token login. Lets the user sign in as a BOT (the token comes from @BotFather)
 * instead of a phone number, via the MTProto `auth.importBotAuthorization` method.
 *
 * Clean rewrite of pro's `LoginBotDialog` with senior-grade fixes:
 *  - `auth.importBotAuthorization` is NOT in this (newer) base's generated TLRPC, so the request is
 *    defined here as a self-contained [TLObject] — the giant generated file stays untouched.
 *  - The token is validated client-side ([isValidToken]) BEFORE any network round-trip, and the input
 *    dialog stays open on a bad token (positive button overridden) instead of closing and losing the text.
 *  - The host [LoginActivity] is held only weakly and every callback re-checks [destroyed], so a
 *    dismissed dialog can never leak the Activity or fire onto a torn-down screen.
 *  - The in-flight request is cancelled on teardown; success reuses the existing native auth flow
 *    ([LoginActivity.onAuthSuccessForQr] → onAuthSuccess), so bot accounts get the same clean init as
 *    a normal login with zero duplicated account-setup code.
 *  - Errors are mapped to a meaningful message (FLOOD_WAIT vs. invalid token) rather than a single
 *    hard-coded "token is invalid".
 */
class BotLoginController(
    loginActivity: LoginActivity,
    private val context: Context,
    private val onClosed: Runnable? = null
) {

    // The account slot being logged into — for "add account" this is the NEW slot the LoginActivity
    // was created with (LoginActivity(int account) → currentAccount), NOT the currently-visible account.
    // Using UserConfig.selectedAccount here authorized the wrong slot, so the bot session landed on the
    // existing account and the new account showed the old account's dialogs.
    private val account = loginActivity.currentAccount
    private val fragmentRef = WeakReference(loginActivity)

    private var inputDialog: AlertDialog? = null
    private var progressDialog: AlertDialog? = null
    private var input: EditText? = null

    @Volatile private var destroyed = false
    @Volatile private var loggingIn = false
    private var currentReqId = 0

    fun show() {
        val instructions = TextView(context).apply {
            text = LanguageCode.getMyTitles(267)
            setTextColor(Theme.getColor(Theme.key_dialogTextGray2))
            textSize = 14f
            gravity = Gravity.START
            setLineSpacing(AndroidUtilities.dp(3f).toFloat(), 1f)
        }

        val field = EditText(context).apply {
            hint = LanguageCode.getMyTitles(266)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
            textSize = 16f
            // A bot token is a single opaque line; no autocorrect/suggestions, no multiline newlines.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
        }
        input = field

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AndroidUtilities.dp(22f), AndroidUtilities.dp(4f), AndroidUtilities.dp(22f), AndroidUtilities.dp(4f))
            addView(instructions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 18f, 0f, 0f))
        }

        val builder = AlertDialog.Builder(context)
        builder.setTitle(LanguageCode.getMyTitles(265))
        builder.setView(layout)
        // Real validation is wired after show() so a bad token doesn't dismiss the dialog.
        builder.setPositiveButton(LanguageCode.getMyTitles(4)) { _, _ -> }
        builder.setNegativeButton(LanguageCode.getMyTitles(80)) { d, _ -> d.dismiss() }
        builder.setOnDismissListener { destroy() }

        val dialog = builder.create()
        inputDialog = dialog
        try {
            dialog.show()
        } catch (e: Exception) {
            FileLog.e(e)
            destroy()
            return
        }

        // Override the positive button: validate locally and keep the dialog open on failure.
        (dialog.getButton(DialogInterface.BUTTON_POSITIVE) as? TextView)?.setOnClickListener {
            val token = input?.text?.toString()?.trim().orEmpty()
            if (!isValidToken(token)) {
                AndroidUtilities.shakeView(input)
                return@setOnClickListener
            }
            startLogin(token)
        }

        field.requestFocus()
        AndroidUtilities.showKeyboard(field)
    }

    private fun startLogin(token: String) {
        if (destroyed || loggingIn) return
        loggingIn = true

        AndroidUtilities.hideKeyboard(input)
        val spinner = AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER)
        spinner.setOnCancelListener {
            if (currentReqId != 0) {
                ConnectionsManager.getInstance(account).cancelRequest(currentReqId, true)
                currentReqId = 0
            }
            loggingIn = false
        }
        progressDialog = spinner
        spinner.showDelayed(300L)

        // Start the auth session fresh, exactly like a phone login would before importing credentials.
        ConnectionsManager.getInstance(account).cleanup(false)

        val req = TLAuthImportBotAuthorization().apply {
            apiId = BuildVars.APP_ID
            apiHash = BuildVars.APP_HASH
            botAuthToken = token
        }
        currentReqId = ConnectionsManager.getInstance(account).sendRequest(req, { response, error ->
            AndroidUtilities.runOnUIThread { onLoginResponse(response, error) }
        }, ConnectionsManager.RequestFlagFailOnServerErrors
                or ConnectionsManager.RequestFlagWithoutLogin
                or ConnectionsManager.RequestFlagTryDifferentDc
                or ConnectionsManager.RequestFlagEnableUnauthorized)
    }

    private fun onLoginResponse(response: TLObject?, error: TLRPC.TL_error?) {
        if (destroyed) return
        currentReqId = 0
        loggingIn = false
        dismiss(progressDialog)
        progressDialog = null

        if (error == null && response is TLRPC.TL_auth_authorization) {
            val frag = fragmentRef.get()
            dismiss(inputDialog)
            // Reuse the native success path (account setup, DC settings, finish activity).
            frag?.onAuthSuccessForQr(response)
            destroy()
        } else {
            showError(error)
        }
    }

    private fun showError(error: TLRPC.TL_error?) {
        if (destroyed) return
        val text = error?.text
        val message = when {
            text != null && text.startsWith("FLOOD_WAIT") -> {
                val seconds = text.substringAfter("FLOOD_WAIT_", "").toIntOrNull() ?: 0
                floodMessage(seconds)
            }
            else -> LanguageCode.getMyTitles(268)
        }
        val builder = AlertDialog.Builder(context)
        builder.setTitle(LanguageCode.getMyTitles(266))
        builder.setMessage(message)
        builder.setPositiveButton(LanguageCode.getMyTitles(4)) { d, _ -> d.dismiss() }
        try {
            builder.show()
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    private fun floodMessage(seconds: Int): String {
        // Keep the invalid-token hint but prepend the wait, since the server is rate-limiting attempts.
        val base = LanguageCode.getMyTitles(268)
        return if (seconds > 0) "$base\n\nFLOOD_WAIT: ${seconds}s" else base
    }

    private fun isValidToken(token: String): Boolean {
        // BotFather tokens look like: <bot_user_id>:<35-char secret>, e.g. 123456789:AAExxx...
        return TOKEN_REGEX.matches(token)
    }

    // Public teardown for the host fragment (e.g. fragment destroyed while a dialog is open).
    fun close() {
        dismiss(inputDialog)
        dismiss(progressDialog)
        destroy()
    }

    private fun dismiss(dialog: AlertDialog?) {
        try {
            dialog?.dismiss()
        } catch (ignore: Exception) {
        }
    }

    // Idempotent teardown: cancel any in-flight request and drop all references.
    private fun destroy() {
        if (destroyed) return
        destroyed = true
        if (currentReqId != 0) {
            ConnectionsManager.getInstance(account).cancelRequest(currentReqId, true)
            currentReqId = 0
        }
        fragmentRef.clear()
        inputDialog = null
        progressDialog = null
        input = null
        onClosed?.run()
    }

    /**
     * `auth.importBotAuthorization#67a3ff2c flags:int api_id:int api_hash:string bot_auth_token:string = auth.Authorization`
     * Defined locally because this base's generated TLRPC omits it (the method predates the schema split
     * but was dropped from the generated set).
     */
    private class TLAuthImportBotAuthorization : TLObject() {
        var flags = 0
        var apiId = 0
        var apiHash: String? = null
        var botAuthToken: String? = null

        override fun serializeToStream(stream: OutputSerializedData) {
            stream.writeInt32(CONSTRUCTOR)
            stream.writeInt32(flags)
            stream.writeInt32(apiId)
            stream.writeString(apiHash)
            stream.writeString(botAuthToken)
        }

        override fun deserializeResponse(stream: InputSerializedData, constructor: Int, exception: Boolean): TLObject? {
            return TLRPC.auth_Authorization.TLdeserialize(stream, constructor, exception)
        }

        companion object {
            const val CONSTRUCTOR = 0x67a3ff2c
        }
    }

    companion object {
        private val TOKEN_REGEX = Regex("^\\d{5,}:[A-Za-z0-9_-]{30,}$")
    }
}
