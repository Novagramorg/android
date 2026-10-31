package org.fenixuz.ui.lock

import android.content.Context
import android.view.View
import org.fenixuz.ui.secret_chat.SecretLockScreenDialog
import org.fenixuz.ui.secret_chat.SecretPasscodeScreen
import org.fenixuz.ui.secret_chat.SecretPasswordType
import org.fenixuz.utils.LanguageCode
import org.fenixuz.utils.Password
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalFragment

/**
 * Novagram: "Chat lock" in the Novagram settings, laid out like Hidden accounts. Settings shared by every locked chat:
 * the common password, fingerprint unlock and vibration. Chats themselves are locked, unlocked and given their own code
 * from the chat's Novagram settings, which the text at the top explains.
 *
 * Once any code exists, opening the screen asks for one ([open]).
 */
class ChatLockScreen : UniversalFragment() {

    private val CHANGE_COMMON = 1
    private val FINGERPRINT = 2
    private val VIBRATE = 3

    override fun getTitle(): CharSequence = LanguageCode.getMyTitles(252)

    override fun createView(context: Context): View {
        val view = super.createView(context)
        listView.setSections()
        listView.adapter.setApplyBackground(false)
        actionBar.setAdaptiveBackground(listView)
        return view
    }

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asTopView(LanguageCode.getMyTitles(510), R.raw.utyan_passcode))
        // Nothing to set before the first chat is locked: the text above says how to lock one.
        if (!Password.hasCommonPassword() && !Password.hasAnyLock()) {
            items.add(UItem.asShadow(null))
            return
        }
        if (Password.hasCommonPassword()) {
            items.add(UItem.asButton(CHANGE_COMMON, LanguageCode.getMyTitles(251)))
        }
        val fingerprint = Password.isFingerprintHardwareAvailable()
        if (fingerprint) {
            items.add(UItem.asCheck(FINGERPRINT, LanguageCode.getMyTitles(439)).setChecked(Password.fingerprintUnlock))
        }
        items.add(UItem.asCheck(VIBRATE, LanguageCode.getMyTitles(446)).setChecked(Password.vibrate))
        items.add(UItem.asShadow(if (fingerprint) LanguageCode.getMyTitles(506) else null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            // Saved only after the new password is entered twice; Back before that keeps the old one.
            CHANGE_COMMON -> presentFragment(SecretPasscodeScreen(Password.editorForCommonPassword(), SecretPasswordType.SET_NEW))
            FINGERPRINT -> {
                Password.fingerprintUnlock = !Password.fingerprintUnlock
                (view as? TextCheckCell)?.isChecked = Password.fingerprintUnlock
            }
            VIBRATE -> {
                Password.vibrate = !Password.vibrate
                (view as? TextCheckCell)?.isChecked = Password.vibrate
            }
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean = false

    companion object {
        /**
         * Opens the screen from [fragment]; once any chat-lock code exists, only after one ([Password.codeCredential]).
         * The code alone, not the fingerprint: the screen changes the common password.
         */
        @JvmStatic
        fun open(fragment: BaseFragment) {
            val credential = Password.codeCredential()
            if (credential != null) {
                SecretLockScreenDialog.askCode(fragment, credential, SecretLockScreenDialog.UNLOCK_BEHIND) {
                    fragment.presentFragment(ChatLockScreen(), false, true)
                }
            } else {
                fragment.presentFragment(ChatLockScreen())
            }
        }
    }
}
