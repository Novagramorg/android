package org.fenixuz.ui.secret_chat

import android.content.Context
import android.view.View
import org.fenixuz.ui.lock.codeOnly
import org.fenixuz.utils.LanguageCode
import org.fenixuz.utils.Password
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalFragment

/**
 * Novagram: "Secret Chat" in the Novagram settings, laid out like Hidden accounts and Telegram's passcode settings.
 *
 * Off: what the folder is and "Turn on", which sets the code. On (opening the screen then asks for the code alone, see
 * [open]): change the code, fingerprint unlock, vibration, and "Turn off", which moves every secret chat back to the
 * chat list. Chats go in and out of the folder from the chat list, not here.
 */
class SecretChatScreen : UniversalFragment() {

    private val TURN_ON = 1
    private val CHANGE_CODE = 2
    private val FINGERPRINT = 3
    private val VIBRATE = 4
    private val TURN_OFF = 5

    override fun getTitle(): CharSequence = LanguageCode.getMyTitles(213)

    override fun createView(context: Context): View {
        val view = super.createView(context)
        listView.setSections()
        listView.adapter.setApplyBackground(false)
        actionBar.setAdaptiveBackground(listView)
        return view
    }

    override fun onResume() {
        super.onResume()
        // Back from the code screen: the code may have been set.
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asTopView(LanguageCode.getMyTitles(217), R.raw.utyan_passcode))
        if (!SecretPassword.hasPassword()) {
            items.add(UItem.asButton(TURN_ON, LanguageCode.getMyTitles(438)).accent())
            items.add(UItem.asShadow(null))
            return
        }
        items.add(UItem.asButton(CHANGE_CODE, LanguageCode.getMyTitles(431)))
        val fingerprint = Password.isFingerprintHardwareAvailable()
        if (fingerprint) {
            items.add(UItem.asCheck(FINGERPRINT, LanguageCode.getMyTitles(439)).setChecked(SecretPassword.fingerprintUnlock))
        }
        items.add(UItem.asCheck(VIBRATE, LanguageCode.getMyTitles(446)).setChecked(SecretPassword.vibrate))
        // Whose finger opens it: the reason someone sharing the phone turns fingerprint unlock off.
        items.add(UItem.asShadow(if (fingerprint) LanguageCode.getMyTitles(506) else null))
        items.add(UItem.asButton(TURN_OFF, LanguageCode.getMyTitles(507)).red())
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            // Both save only after the new code is entered twice; Back before that leaves the old code as it was.
            TURN_ON, CHANGE_CODE -> presentFragment(SecretPasscodeScreen(SecretPassword.editor(), SecretPasswordType.SET_NEW))
            FINGERPRINT -> {
                SecretPassword.fingerprintUnlock = !SecretPassword.fingerprintUnlock
                (view as? TextCheckCell)?.isChecked = SecretPassword.fingerprintUnlock
            }
            VIBRATE -> {
                SecretPassword.vibrate = !SecretPassword.vibrate
                (view as? TextCheckCell)?.isChecked = SecretPassword.vibrate
            }
            TURN_OFF -> confirmTurnOff()
        }
    }

    private fun confirmTurnOff() {
        val context = parentActivity ?: return
        val dialog = AlertDialog.Builder(context, resourceProvider)
            .setTitle(LanguageCode.getMyTitles(507))
            .setMessage(LanguageCode.getMyTitles(508))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LanguageCode.getMyTitles(445)) { _, _ ->
                SecretPassword.removePassword()
                listView?.adapter?.update(true)
            }
            .create()
        showDialog(dialog)
        (dialog.getButton(AlertDialog.BUTTON_POSITIVE) as? android.widget.TextView)
            ?.setTextColor(Theme.getColor(Theme.key_text_RedBold, resourceProvider))
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean = false

    companion object {
        /**
         * Opens the screen from [fragment]; once the folder has a code, only after that code. The code alone, not the
         * fingerprint: the screen changes the code and turns the folder off.
         */
        @JvmStatic
        fun open(fragment: BaseFragment) {
            if (SecretPassword.hasPassword()) {
                SecretLockScreenDialog.askCode(fragment, SecretPassword.credential().codeOnly(), SecretLockScreenDialog.UNLOCK_BEHIND) {
                    fragment.presentFragment(SecretChatScreen(), false, true)
                }
            } else {
                fragment.presentFragment(SecretChatScreen())
            }
        }
    }
}
