package org.fenixuz.ui

import android.content.Context
import android.media.RingtoneManager
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import org.fenixuz.ui.auto_answer.AutoAnswer
import org.fenixuz.ui.auto_answer.AutoAnswerMenu
import org.fenixuz.ui.create_folder_dialog.FolderIcons
import org.fenixuz.ui.onboarding.FenixTour
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.fenixuz.utils.AutoAcceptJoin
import org.fenixuz.utils.ConfirmDialogsPref
import org.fenixuz.utils.GhostStory
import org.fenixuz.utils.HideTabs
import org.fenixuz.utils.LanguageCode
import org.fenixuz.utils.MessageReminder
import org.fenixuz.utils.StoryDownload
import org.fenixuz.utils.StoryUtil
import org.fenixuz.utils.StrangerShield
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DownloadController
import org.telegram.messenger.MessagesController
import android.os.Bundle
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.DialogsActivity
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.NumberPicker
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalFragment

/**
 * Fenix Settings — Novagram feature hub, rendered in native Telegram style
 * (rounded card sections via [UniversalFragment]).
 *
 * Stories section — two independent toggles:
 *  - view stories anonymously ([GhostStory], hooked in PeerStoriesView)
 *  - hide the stories tray in the chat list ([StoryUtil], hooked in StoriesController)
 * Add one row per ported feature.
 */
class FenixSettings : UniversalFragment() {

    private val STORY_GHOST = 1
    private val STORY_HIDE = 2
    private val DOWNLOAD_STOP = 5
    private val AUTO_ANSWER_ACTIVE = 6
    private val AUTO_ANSWER_MSG = 7
    private val CONFIRM_STICKER = 8
    private val CONFIRM_VOICE = 9
    private val CONFIRM_GIF = 10
    private val FOLDER_ICONS = 11
    private val HIDE_TABS = 12
    private val AUTO_ACCEPT_JOIN = 13
    private val REMINDER_ENABLED = 14
    private val REMINDER_DELAY = 15
    private val REMINDER_SOUND = 16
    private val STORY_DOWNLOAD = 17
    private val STRANGER_SHIELD = 18
    private val STRANGER_INBOX = 19

    // Secret gesture: SECRET_TAP_TARGET taps on the title within SECRET_TAP_WINDOW_MS
    // (measured from the first tap) open the hidden [AdvancedTools] screen.
    private val SECRET_TAP_TARGET = 5
    private val SECRET_TAP_WINDOW_MS = 3000L
    private var secretTapCount = 0
    private var secretFirstTapTime = 0L

    // Onboarding: a toolbar "?" replays the full feature tour; the short tour auto-runs once on first open.
    private val HELP_BUTTON = 1001
    private val TOUR_SHOWN_KEY = "fenix_tour_shown"

    /** True when auto-download is fully stopped (all network presets disabled). */
    private fun isAutoDownloadStopped(): Boolean {
        val dc = DownloadController.getInstance(currentAccount)
        return !dc.mobilePreset.enabled && !dc.wifiPreset.enabled && !dc.roamingPreset.enabled
    }

    private fun setAutoDownloadStopped(stopped: Boolean) {
        val dc = DownloadController.getInstance(currentAccount)
        val enabled = !stopped
        dc.mobilePreset.enabled = enabled
        dc.wifiPreset.enabled = enabled
        dc.roamingPreset.enabled = enabled
        val editor = MessagesController.getMainSettings(currentAccount).edit()
        editor.putString("mobilePreset", dc.mobilePreset.toString())
        editor.putString("wifiPreset", dc.wifiPreset.toString())
        editor.putString("roamingPreset", dc.roamingPreset.toString())
        editor.putInt("currentMobilePreset", 3)
        editor.putInt("currentWifiPreset", 3)
        editor.putInt("currentRoamingPreset", 3)
        editor.commit()
        dc.checkAutodownloadSettings()
        dc.savePresetToServer(0)
        dc.savePresetToServer(1)
        dc.savePresetToServer(2)
    }

    override fun getTitle(): CharSequence = LanguageCode.getMyTitles(236)

    override fun createView(context: Context): View {
        val view = super.createView(context)
        // Native Telegram rounded "card" sections (like the main Settings screen).
        listView.setSections()
        listView.adapter.setApplyBackground(false)
        // Transparent/adaptive toolbar that blends with the list, like other settings menus.
        actionBar.setAdaptiveBackground(listView)

        // Secret entry point: 5 quick taps on the title open the hidden Advanced screen.
        actionBar.titleTextView?.setOnClickListener { onTitleTapped() }

        // Toolbar "?" → replay the guided tour on demand (for anyone who skipped or wants a refresher).
        actionBar.createMenu().addItem(HELP_BUTTON, R.drawable.outline_question_mark)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
                else if (id == HELP_BUTTON) startTour()
            }
        })

        // First-ever open: auto-play the full tour once, after the list has settled. It covers every feature;
        // the Skip button lets anyone bail out instantly, and the toolbar "?" lets them replay it later.
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

    /** Build a tour stop from a row id, its title string and the explanation (reusing each feature's subtitle). */
    private fun step(itemId: Int, titleCode: Int, bodyCode: Int): FenixTour.Step =
        FenixTour.Step(itemId, LanguageCode.getMyTitles(titleCode), LanguageCode.getMyTitles(bodyCode))

    /** The full walkthrough: every Novagram feature on this screen, in order. Used both on first open and replay. */
    private fun fullSteps(): List<FenixTour.Step> = listOf(
        step(STORY_GHOST, 232, 233),
        step(STORY_HIDE, 136, 137),
        step(STORY_DOWNLOAD, 297, 298),
        step(DOWNLOAD_STOP, 183, 196),
        step(AUTO_ANSWER_ACTIVE, 228, 239),
        step(CONFIRM_STICKER, 190, 191),
        step(FOLDER_ICONS, 240, 241),
        step(HIDE_TABS, 260, 261),
        step(AUTO_ACCEPT_JOIN, 263, 264),
        step(REMINDER_ENABLED, 270, 271),
        step(STRANGER_SHIELD, 319, 320)
    )

    private fun startTour() {
        val container = fragmentView as? FrameLayout ?: return
        FenixTour(container.context, container, listView, fullSteps()).start()
    }

    // Draw the grey page behind a TRANSPARENT system navigation bar (Telegram's own settings screens do this
    // via edge-to-edge) instead of painting a solid grey bar at the bottom. With edge-to-edge on, BaseFragment
    // skips forcing a nav-bar colour, so the bar goes transparent and the list background scrolls underneath.
    override fun isSupportEdgeToEdge(): Boolean = true

    // Keep the last row clear of the nav buttons (pad the bottom by the nav inset) while letting the background
    // and section cards bleed under the transparent bar (clipToPadding=false). Top/side padding is preserved so
    // the adaptive toolbar inset stays intact.
    override fun onInsets(left: Int, top: Int, right: Int, bottom: Int) {
        listView.setPadding(listView.paddingLeft, listView.paddingTop, listView.paddingRight, bottom)
        listView.clipToPadding = false
    }

    /** Counts title taps inside a rolling window; opens [AdvancedTools] once the target is hit. */
    private fun onTitleTapped() {
        val now = SystemClock.elapsedRealtime()
        if (secretTapCount == 0 || now - secretFirstTapTime > SECRET_TAP_WINDOW_MS) {
            // Start a fresh window with this tap as the first.
            secretFirstTapTime = now
            secretTapCount = 1
        } else {
            secretTapCount++
        }
        if (secretTapCount >= SECRET_TAP_TARGET) {
            secretTapCount = 0
            presentFragment(AdvancedTools())
        }
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LanguageCode.getMyTitles(135)))
        items.add(
            UItem.asButtonCheck(STORY_GHOST, LanguageCode.getMyTitles(232), LanguageCode.getMyTitles(233))
                .setChecked(GhostStory.ghostMode)
        )
        items.add(
            UItem.asButtonCheck(STORY_HIDE, LanguageCode.getMyTitles(136), LanguageCode.getMyTitles(137))
                .setChecked(StoryUtil.hideStoryMode)
        )
        items.add(
            UItem.asButtonCheck(STORY_DOWNLOAD, LanguageCode.getMyTitles(297), LanguageCode.getMyTitles(298))
                .setChecked(StoryDownload.isEnabled())
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LanguageCode.getMyTitles(237)))
        items.add(
            UItem.asButtonCheck(DOWNLOAD_STOP, LanguageCode.getMyTitles(183), LanguageCode.getMyTitles(196))
                .setChecked(isAutoDownloadStopped())
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LanguageCode.getMyTitles(228)))
        items.add(
            UItem.asButtonCheck(AUTO_ANSWER_ACTIVE, LanguageCode.getMyTitles(228), LanguageCode.getMyTitles(239))
                .setChecked(AutoAnswer.autoAnswerIsActive())
        )
        items.add(UItem.asButton(AUTO_ANSWER_MSG, LanguageCode.getMyTitles(238)))
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LanguageCode.getMyTitles(190)))
        items.add(
            UItem.asButtonCheck(CONFIRM_STICKER, LanguageCode.getMyTitles(210), LanguageCode.getMyTitles(191))
                .setChecked(ConfirmDialogsPref.confirmSticker)
        )
        items.add(
            UItem.asButtonCheck(CONFIRM_VOICE, LanguageCode.getMyTitles(211), LanguageCode.getMyTitles(192))
                .setChecked(ConfirmDialogsPref.confirmVoice)
        )
        items.add(
            UItem.asButtonCheck(CONFIRM_GIF, LanguageCode.getMyTitles(212), LanguageCode.getMyTitles(193))
                .setChecked(ConfirmDialogsPref.confirmGif)
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LanguageCode.getMyTitles(240)))
        items.add(
            UItem.asButtonCheck(FOLDER_ICONS, LanguageCode.getMyTitles(240), LanguageCode.getMyTitles(241))
                .setChecked(FolderIcons.isIconMode())
        )
        items.add(
            UItem.asButtonCheck(HIDE_TABS, LanguageCode.getMyTitles(260), LanguageCode.getMyTitles(261))
                .setChecked(HideTabs.isEnabled())
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LanguageCode.getMyTitles(262)))
        items.add(
            UItem.asButtonCheck(AUTO_ACCEPT_JOIN, LanguageCode.getMyTitles(263), LanguageCode.getMyTitles(264))
                .setChecked(AutoAcceptJoin.isEnabled())
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LanguageCode.getMyTitles(269)))
        items.add(
            UItem.asButtonCheck(REMINDER_ENABLED, LanguageCode.getMyTitles(270), LanguageCode.getMyTitles(271))
                .setChecked(MessageReminder.isEnabled())
        )
        items.add(UItem.asButton(REMINDER_DELAY, reminderDelayLabel()))
        items.add(UItem.asButton(REMINDER_SOUND, reminderSoundLabel()))
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LanguageCode.getMyTitles(318)))
        items.add(
            UItem.asButtonCheck(STRANGER_SHIELD, LanguageCode.getMyTitles(319), LanguageCode.getMyTitles(320))
                .setChecked(StrangerShield.isEnabled())
        )
        // A "Stranger chats" inbox — opens a Chats-like screen listing the hidden non-contact chats,
        // so nothing is lost: you can read and reply from there. Reachable even when the toggle is off.
        // Badge the entry with the unread count so an important message is never missed unnoticed.
        val inboxUnread = StrangerShield.countInboxUnread(currentAccount)
        if (inboxUnread > 0) {
            items.add(UItem.asButton(STRANGER_INBOX, LanguageCode.getMyTitles(321), inboxUnread.toString()))
        } else {
            items.add(UItem.asButton(STRANGER_INBOX, LanguageCode.getMyTitles(321)))
        }
        items.add(UItem.asShadow(null))
    }

    private fun reminderDelayLabel(): String =
        LanguageCode.getMyTitles(272) + ": " + MessageReminder.getDelayMin() + " " + LanguageCode.getMyTitles(273)

    private fun reminderSoundLabel(): String {
        val tone = if (MessageReminder.getSound() == 1) LanguageCode.getMyTitles(276) else LanguageCode.getMyTitles(275)
        return LanguageCode.getMyTitles(274) + ": " + tone
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            STORY_GHOST -> {
                GhostStory.changeGhostMode(!GhostStory.ghostMode)
                (view as NotificationsCheckCell).setChecked(GhostStory.ghostMode)
            }
            STORY_HIDE -> {
                StoryUtil.changeHideStoryMode()
                (view as NotificationsCheckCell).setChecked(StoryUtil.hideStoryMode)
            }
            STORY_DOWNLOAD -> {
                StoryDownload.toggle()
                (view as NotificationsCheckCell).setChecked(StoryDownload.isEnabled())
            }
            DOWNLOAD_STOP -> {
                setAutoDownloadStopped(!isAutoDownloadStopped())
                (view as NotificationsCheckCell).setChecked(isAutoDownloadStopped())
            }
            AUTO_ANSWER_ACTIVE -> {
                AutoAnswer.saveAutoAnswerActive(!AutoAnswer.autoAnswerIsActive())
                (view as NotificationsCheckCell).setChecked(AutoAnswer.autoAnswerIsActive())
            }
            AUTO_ANSWER_MSG -> {
                presentFragment(AutoAnswerMenu())
            }
            CONFIRM_STICKER -> {
                ConfirmDialogsPref.changeConfirmStickerMode()
                (view as NotificationsCheckCell).setChecked(ConfirmDialogsPref.confirmSticker)
            }
            CONFIRM_VOICE -> {
                ConfirmDialogsPref.changeConfirmVoiceMode()
                (view as NotificationsCheckCell).setChecked(ConfirmDialogsPref.confirmVoice)
            }
            CONFIRM_GIF -> {
                ConfirmDialogsPref.changeConfirmGifMode()
                (view as NotificationsCheckCell).setChecked(ConfirmDialogsPref.confirmGif)
            }
            FOLDER_ICONS -> {
                FolderIcons.setIconMode(!FolderIcons.isIconMode())
                (view as NotificationsCheckCell).setChecked(FolderIcons.isIconMode())
                // Rebuild folder tabs so the change applies immediately.
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.dialogFiltersUpdated)
            }
            HIDE_TABS -> {
                // Persist + mark dirty; DialogsActivity.onResume re-applies it when we navigate back.
                HideTabs.toggle()
                (view as NotificationsCheckCell).setChecked(HideTabs.isEnabled())
            }
            AUTO_ACCEPT_JOIN -> {
                AutoAcceptJoin.toggle()
                (view as NotificationsCheckCell).setChecked(AutoAcceptJoin.isEnabled())
            }
            REMINDER_ENABLED -> {
                MessageReminder.setEnabled(!MessageReminder.isEnabled())
                (view as NotificationsCheckCell).setChecked(MessageReminder.isEnabled())
            }
            REMINDER_DELAY -> showReminderDelayPicker()
            REMINDER_SOUND -> showReminderSoundPicker()
            STRANGER_SHIELD -> {
                if (!StrangerShield.isEnabled()) {
                    // Turning ON: explain exactly what happens, then ask for consent — so the user is
                    // never surprised that chats "disappeared" or that a message was silenced.
                    val context = parentActivity ?: return
                    AlertDialog.Builder(context)
                        .setTitle(LanguageCode.getMyTitles(319))
                        .setMessage(LanguageCode.getMyTitles(322))
                        .setPositiveButton(LanguageCode.getMyTitles(323)) { _, _ ->
                            StrangerShield.setEnabled(true)
                            (view as NotificationsCheckCell).setChecked(true)
                            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.dialogsNeedReload)
                        }
                        .setNegativeButton(LanguageCode.getMyTitles(80), null)
                        .show()
                } else {
                    StrangerShield.setEnabled(false)
                    (view as NotificationsCheckCell).setChecked(false)
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.dialogsNeedReload)
                }
            }
            STRANGER_INBOX -> {
                val args = Bundle()
                args.putBoolean("fenixStrangerInbox", true)
                presentFragment(DialogsActivity(args))
            }
        }
    }

    private fun showReminderDelayPicker() {
        val context = parentActivity ?: return
        val picker = NumberPicker(context)
        picker.setMinValue(MessageReminder.MIN_DELAY_MIN)
        picker.setMaxValue(MessageReminder.MAX_DELAY_MIN)
        picker.setValue(MessageReminder.getDelayMin())
        AlertDialog.Builder(context)
            .setTitle(LanguageCode.getMyTitles(272))
            .setView(picker)
            .setPositiveButton(LanguageCode.getMyTitles(4)) { _, _ ->
                MessageReminder.setDelayMin(picker.value)
                listView.adapter.update(true)
            }
            .setNegativeButton(LanguageCode.getMyTitles(80), null)
            .show()
    }

    private fun showReminderSoundPicker() {
        val context = parentActivity ?: return
        val items = arrayOf<CharSequence>(LanguageCode.getMyTitles(275), LanguageCode.getMyTitles(276))
        AlertDialog.Builder(context)
            .setTitle(LanguageCode.getMyTitles(274))
            .setItems(items) { _, which ->
                MessageReminder.setSound(which)
                previewReminderSound(context, which)
                listView.adapter.update(true)
            }
            .show()
    }

    private fun previewReminderSound(context: Context, index: Int) {
        try {
            val uri = MessageReminder.soundUri(index) ?: return
            val ringtone = RingtoneManager.getRingtone(context, uri) ?: return
            ringtone.play()
            // Bound the preview: alarm tones loop, so stop it after a short listen.
            AndroidUtilities.runOnUIThread({
                try {
                    ringtone.stop()
                } catch (ignore: Exception) {
                }
            }, 2500)
        } catch (ignore: Exception) {
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        return false
    }
}
