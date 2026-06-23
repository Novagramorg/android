package org.fenixuz.utils

/**
 * Tracks the dialog currently shown in the ghost [org.fenixuz.ui.chat_preview.ChatPreviewBottomSheet]
 * so that core read-receipt paths can be suppressed for it — the preview must leave ZERO footprint
 * (no "read", no "listened", no "watched" receipt to the other side).
 *
 * Scoped by dialogId on purpose: only the previewed chat is affected, the rest of the app behaves
 * exactly as before. The single hook reading this lives in
 * `MessagesController.markMessageContentAsRead(...)`, which is the one server-side content-read path.
 *
 * O(1), @Volatile field — safe to read from the markMessageContentAsRead path without locking.
 */
object ChatPreviewState {

    @Volatile
    private var previewDialogId: Long = 0L

    fun setActive(dialogId: Long) {
        previewDialogId = dialogId
    }

    fun clear() {
        previewDialogId = 0L
    }

    /** True while the given dialog is open in the ghost preview — callers should skip read receipts. */
    fun isGhost(dialogId: Long): Boolean = previewDialogId != 0L && previewDialogId == dialogId
}
