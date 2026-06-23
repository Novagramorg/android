package org.fenixuz.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import org.fenixuz.ui.onboarding.FenixTour
import org.fenixuz.utils.DeletedMsg
import org.fenixuz.utils.EditMessage
import org.fenixuz.ui.secret_chat.SecretPasscodeScreen
import org.fenixuz.ui.secret_chat.SecretPassword
import org.fenixuz.ui.secret_chat.SecretPasswordType
import org.fenixuz.utils.GhostVariable
import org.fenixuz.utils.LanguageCode
import org.fenixuz.utils.Password
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalFragment

/**
 * Advanced (hidden) tools — reached only via the secret 5-tap gesture on the
 * [FenixSettings] title. Holds the "grey-area" toggles kept out of the main
 * settings screen on purpose (edit-history save, deleted-message save, ghost mode),
 * so they are not surfaced during a casual store review.
 *
 * Pure UI shell over [EditMessage] / [DeletedMsg] / [GhostVariable]; no extra state of its own.
 */
class AdvancedTools : UniversalFragment() {

    private val EDIT_SAVE = 1
    private val DELETE_SAVE = 2
    private val GHOST_MODE = 3
    private val SECRET_CHAT = 4
    private val CHANGE_COMMON_PASSWORD = 5
    private val FINGERPRINT_UNLOCK = 6

    private val TOUR_SHOWN_KEY = "fenix_advanced_tour_shown"
    private val HELP_BUTTON = 1001

    override fun getTitle(): CharSequence = LanguageCode.getMyTitles(243)

    override fun createView(context: Context): View {
        val view = super.createView(context)
        // Native Telegram rounded "card" sections, matching the main settings screen.
        listView.setSections()
        listView.adapter.setApplyBackground(false)
        actionBar.setAdaptiveBackground(listView)

        // Toolbar "?" → replay the tour on demand (for anyone who skipped or wants a refresher).
        actionBar.createMenu().addItem(HELP_BUTTON, R.drawable.outline_question_mark)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
                else if (id == HELP_BUTTON) startTour()
            }
        })

        // First-ever open of this hidden screen: walk the user through its tools with the coach-mark tour
        // (only once — the person reached here deliberately via the 5-tap gesture, so a one-time guide helps).
        if (!tourAlreadyShown()) {
            markTourShown()
            AndroidUtilities.runOnUIThread({ startTour() }, 350)
        }
        return view
    }

    private fun tourAlreadyShown(): Boolean =
        parentActivity?.getSharedPreferences("db", Context.MODE_PRIVATE)?.getBoolean(TOUR_SHOWN_KEY, false) ?: true

    private fun markTourShown() {
        parentActivity?.getSharedPreferences("db", Context.MODE_PRIVATE)?.edit()?.putBoolean(TOUR_SHOWN_KEY, true)?.apply()
    }

    private fun step(itemId: Int, titleCode: Int, bodyCode: Int): FenixTour.Step =
        FenixTour.Step(itemId, LanguageCode.getMyTitles(titleCode), LanguageCode.getMyTitles(bodyCode))

    /** The always-present tools (the password rows are conditional, self-explanatory, and skipped). */
    private fun tourSteps(): List<FenixTour.Step> = listOf(
        step(EDIT_SAVE, 23, 72),
        step(DELETE_SAVE, 31, 235),
        step(GHOST_MODE, 32, 245),
        step(SECRET_CHAT, 213, 217)
    )

    private fun startTour() {
        val container = fragmentView as? FrameLayout ?: return
        FenixTour(container.context, container, listView, tourSteps()).start()
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LanguageCode.getMyTitles(234)))
        items.add(
            UItem.asButtonCheck(EDIT_SAVE, LanguageCode.getMyTitles(23), LanguageCode.getMyTitles(72))
                .setChecked(EditMessage.editMode)
        )
        items.add(
            UItem.asButtonCheck(DELETE_SAVE, LanguageCode.getMyTitles(31), LanguageCode.getMyTitles(235))
                .setChecked(DeletedMsg.getCheckType() != DeletedMsg.SIMPLE)
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LanguageCode.getMyTitles(244)))
        items.add(
            UItem.asButtonCheck(GHOST_MODE, LanguageCode.getMyTitles(32), LanguageCode.getMyTitles(245))
                .setChecked(GhostVariable.ghostMode)
        )
        items.add(
            UItem.asButtonCheck(SECRET_CHAT, LanguageCode.getMyTitles(213), LanguageCode.getMyTitles(217))
                .setChecked(SecretPassword.hasPassword())
        )
        items.add(UItem.asShadow(null))

        // Chat-lock management. "Change common password" needs a common passcode; the fingerprint
        // toggle needs at least one locked chat AND biometric-capable hardware.
        val showChangeCommon = Password.hasCommonPassword()
        val showFingerprint = Password.hasAnyLock() && Password.isFingerprintHardwareAvailable()
        if (showChangeCommon || showFingerprint) {
            items.add(UItem.asHeader(LanguageCode.getMyTitles(252)))
            if (showChangeCommon) {
                items.add(UItem.asButton(CHANGE_COMMON_PASSWORD, LanguageCode.getMyTitles(251)))
            }
            if (showFingerprint) {
                items.add(
                    UItem.asButtonCheck(FINGERPRINT_UNLOCK, LanguageCode.getMyTitles(253), LanguageCode.getMyTitles(254))
                        .setChecked(Password.isFingerprintEnabled())
                )
            }
            items.add(UItem.asShadow(null))
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            EDIT_SAVE -> {
                EditMessage.changeEditMode(!EditMessage.editMode)
                (view as NotificationsCheckCell).setChecked(EditMessage.editMode)
            }
            DELETE_SAVE -> {
                val enabled = DeletedMsg.getCheckType() != DeletedMsg.SIMPLE
                DeletedMsg.saveCheckType(if (enabled) DeletedMsg.SIMPLE else DeletedMsg.SECOND)
                (view as NotificationsCheckCell).setChecked(DeletedMsg.getCheckType() != DeletedMsg.SIMPLE)
            }
            GHOST_MODE -> {
                // Toggles GhostVariable.ghostMode → re-asserts offline status via MyStatus.setMyStatus();
                // backend hooks (MessagesController offline/sendTyping/completeReadTask, PeerStoriesView) already wired.
                GhostVariable.changeGhostMode()
                (view as NotificationsCheckCell).setChecked(GhostVariable.ghostMode)
            }
            SECRET_CHAT -> {
                // Behaves like a secure switch: turning ON asks the user to create a passcode,
                // turning OFF asks them to confirm it before the lock is removed. The checked state
                // is NOT flipped here — it follows SecretPassword.hasPassword() and is refreshed in
                // onResume once the passcode flow finishes (or is cancelled, leaving it untouched).
                if (SecretPassword.hasPassword()) {
                    presentFragment(SecretPasscodeScreen(SecretPassword.editor(), SecretPasswordType.CHANGE))
                } else {
                    presentFragment(SecretPasscodeScreen(SecretPassword.editor(), SecretPasswordType.SET_NEW))
                }
            }
            CHANGE_COMMON_PASSWORD -> {
                // SET_NEW with an existing password reads as "Enter new passcode" + confirm, then
                // overwrites the common passcode (Telegram's own change-passcode pattern; you're
                // already inside the hidden tools screen so the old one isn't re-asked).
                presentFragment(SecretPasscodeScreen(Password.editorForCommonPassword(), SecretPasswordType.SET_NEW))
            }
            FINGERPRINT_UNLOCK -> {
                val enabled = !Password.isFingerprintEnabled()
                Password.setFingerprintEnabled(enabled)
                (view as NotificationsCheckCell).setChecked(enabled)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Reflect the real secret-chat state after returning from the passcode screen.
        if (listView != null) {
            listView.adapter.update(true)
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        return false
    }
}
