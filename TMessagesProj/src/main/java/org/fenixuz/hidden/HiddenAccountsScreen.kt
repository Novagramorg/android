package org.fenixuz.hidden

import android.content.Context
import android.view.View
import org.fenixuz.ui.secret_chat.SecretPasscodeScreen
import org.fenixuz.ui.secret_chat.SecretPasswordType
import org.fenixuz.utils.LanguageCode
import org.fenixuz.utils.Password
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalFragment

/**
 * Novagram: "Hidden accounts" in the Novagram settings, laid out like Telegram's passcode settings.
 *
 * Off: what the feature does and "Turn on", which sets the code. On (opening the screen then asks for the code, see
 * [open]): change the code, fingerprint unlock, and "Turn off", which shows every hidden account again. Accounts
 * themselves are hidden and shown from their long-press menu in the account lists ([HiddenAccounts.addHideOption]).
 *
 * [pendingHide] is an account the user asked to hide before the feature was on: it is hidden as soon as the code is set.
 */
class HiddenAccountsScreen @JvmOverloads constructor(private var pendingHide: Int = -1) : UniversalFragment(),
    NotificationCenter.NotificationCenterDelegate {

    private val TURN_ON = 1
    private val CHANGE_CODE = 2
    private val FINGERPRINT = 3
    private val TURN_OFF = 4
    private val VIBRATE = 5

    override fun getTitle(): CharSequence = LanguageCode.getMyTitles(428)

    override fun onFragmentCreate(): Boolean {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.novagramHiddenAccountsChanged)
        return super.onFragmentCreate()
    }

    override fun onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.novagramHiddenAccountsChanged)
        super.onFragmentDestroy()
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id == NotificationCenter.novagramHiddenAccountsChanged) {
            if (lockedAgain()) {
                finishFragment(false)
            } else {
                listView?.adapter?.update(true)
            }
        }
    }

    // Once on, these settings are only open after the code; when hidden accounts lock again the screen closes.
    private fun lockedAgain(): Boolean = HiddenAccounts.hasCode() && !HiddenAccounts.unlocked

    override fun createView(context: Context): View {
        val view = super.createView(context)
        listView.setSections()
        listView.adapter.setApplyBackground(false)
        actionBar.setAdaptiveBackground(listView)
        return view
    }

    override fun onResume() {
        super.onResume()
        if (lockedAgain()) {
            finishFragment(false)
            return
        }
        // Back from the code screen with a code set: hide the account the user asked for.
        if (pendingHide >= 0 && HiddenAccounts.hasCode()) {
            val account = pendingHide
            pendingHide = -1
            HiddenAccounts.requestHide(this, account)
        }
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        if (!HiddenAccounts.hasCode()) {
            items.add(UItem.asTopView(LanguageCode.getMyTitles(437), R.raw.utyan_passcode))
            items.add(UItem.asButton(TURN_ON, LanguageCode.getMyTitles(438)).accent())
            items.add(UItem.asShadow(null))
            return
        }
        items.add(UItem.asTopView(LanguageCode.getMyTitles(430), R.raw.utyan_passcode))
        items.add(UItem.asButton(CHANGE_CODE, LanguageCode.getMyTitles(431)))
        if (Password.isFingerprintHardwareAvailable()) {
            items.add(UItem.asCheck(FINGERPRINT, LanguageCode.getMyTitles(439)).setChecked(HiddenAccounts.fingerprint))
        }
        items.add(UItem.asCheck(VIBRATE, LanguageCode.getMyTitles(446)).setChecked(HiddenAccounts.vibrate))
        items.add(UItem.asShadow(LanguageCode.getMyTitles(435)))
        items.add(UItem.asButton(TURN_OFF, LanguageCode.getMyTitles(440)).red())
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TURN_ON, CHANGE_CODE -> presentFragment(SecretPasscodeScreen(HiddenAccounts.editor(null), SecretPasswordType.SET_NEW))
            FINGERPRINT -> {
                HiddenAccounts.fingerprint = !HiddenAccounts.fingerprint
                (view as? TextCheckCell)?.isChecked = HiddenAccounts.fingerprint
            }
            VIBRATE -> {
                HiddenAccounts.vibrate = !HiddenAccounts.vibrate
                (view as? TextCheckCell)?.isChecked = HiddenAccounts.vibrate
            }
            TURN_OFF -> confirmTurnOff()
        }
    }

    private fun confirmTurnOff() {
        val context = parentActivity ?: return
        val dialog = AlertDialog.Builder(context, resourceProvider)
            .setTitle(LanguageCode.getMyTitles(440))
            .setMessage(LanguageCode.getMyTitles(443))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LanguageCode.getMyTitles(445)) { _, _ ->
                HiddenAccounts.disable()
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
         * Opens the screen from [fragment]; once the feature is on, only after the hidden accounts' code, which also
         * unlocks them (the screen stays open only while they are unlocked).
         */
        @JvmStatic
        fun open(fragment: BaseFragment) {
            if (HiddenAccounts.hasCode()) {
                HiddenAccounts.askCode(fragment) {
                    HiddenAccounts.unlock()
                    fragment.presentFragment(HiddenAccountsScreen())
                }
            } else {
                fragment.presentFragment(HiddenAccountsScreen())
            }
        }
    }
}
