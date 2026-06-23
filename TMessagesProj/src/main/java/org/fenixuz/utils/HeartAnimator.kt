package org.fenixuz.utils

import android.os.Handler
import android.os.Looper
import org.telegram.messenger.MessageObject
import org.telegram.ui.ChatActivity

/**
 * Novagram "Heart" message effect.
 *
 * Type the exact text ".heart" and send it → the just-sent message cycles smoothly through the nine
 * coloured hearts and settles on the classic red ❤️. A self-contained gag on YOUR OWN single message.
 *
 * WHAT THE RECIPIENT SEES (the important part): the message is SENT as a plain red ❤️ from the start —
 * NOT as ".heart". The send path ([org.telegram.ui.Components.ChatActivityEnterView]) detects the
 * ".heart" trigger, swaps the outgoing text to [RED_HEART], and calls [armForSend]. So the recipient
 * receives a clean ❤️ that fires Telegram's own native single-emoji effect, with NO ".heart" text and
 * NO "edited" tag ever shown. (Earlier versions sent ".heart" then edited it to ❤️ — which is exactly
 * why the recipient saw ".heart" first and then an "edited" heart.)
 *
 * WHY THE CYCLE IS SENDER-ONLY: a real multi-colour animation on the recipient would need a server
 * `editMessage` per frame. Telegram rate-limits edits (FLOOD_WAIT after ~10-13 edits in ~5s) AND the
 * recipient's client coalesces rapid edits (only the last colour would draw) while stamping "edited"
 * on every one. So a synced animation is impossible without flooding. Instead the cycle is rendered
 * purely LOCALLY on the sender, frame by frame, via [ChatActivity.fenixRenderHeartFrame] (a direct
 * in-place cell rebind) — zero network, no flood, no "edited", free length/speed.
 *
 * Because the message was already sent as ❤️, the final frame settles on ❤️ with NO server edit at all.
 *
 * Optimised over pro's `MessageWriter` (which fired ~45 server edits → floods, and on this base only
 * ever shows one colour because the edit→replaceMessagesObjects repaint is `doOnIdle`-gated). 9 hearts
 * via modulo, one shared main-thread Handler, per-dialog lifecycle cancellation. Device-only, no
 * Firebase, no UI strings. (pro's bundled ".typer" mode is intentionally dropped — not requested.)
 */
object HeartAnimator {

    /** The classic red heart — also the text actually sent over the wire for a ".heart" message. */
    const val RED_HEART = "❤️"

    /** The nine standard heart colours (UTF-8 Kotlin source), ordered as a smooth spectrum sweep. */
    private val HEARTS = arrayOf(
        RED_HEART, // red
        "🧡",      // orange
        "💛",      // yellow
        "💚",      // green
        "💙",      // blue
        "💜",      // purple
        "🤎",      // brown
        "🖤",      // black
        "🤍"       // white
    )

    private const val CYCLES = 3              // full colour loops (local-only, so length/speed are free)
    private const val STEP_MS = 320L          // delay between frames
    private const val ARM_TIMEOUT_MS = 5000L  // drop a stale arm if the send never confirms

    private val handler = Handler(Looper.getMainLooper())
    private val active = HashSet<Anim>()         // main-thread only — no locking needed
    private val armedDialogs = HashSet<Long>()   // dialogs whose next ❤️ send should animate

    /**
     * Called from the send path when the user sends exactly ".heart". Marks [dialogId] so the next
     * outgoing ❤️ (the swapped text) starts the local cycle in [handleSentMessage]. Auto-expires so a
     * failed / never-confirmed send can't later turn a normal ❤️ into an animation.
     */
    fun armForSend(dialogId: Long) {
        armedDialogs.add(dialogId)
        handler.postDelayed({ armedDialogs.remove(dialogId) }, ARM_TIMEOUT_MS)
    }

    /**
     * If [msg] is the confirmed ❤️ from an armed ".heart" send, takes it over and starts the local
     * cycle. Returns true when it did. Call once, right after the message is confirmed sent
     * (messageReceivedByServer). [chat] is the host fragment, used to repaint the bubble each frame.
     */
    fun handleSentMessage(msg: MessageObject?, account: Int, chat: ChatActivity?): Boolean {
        if (msg == null || chat == null) return false
        val owner = msg.messageOwner ?: return false
        if (!msg.isOut) return false
        // Only the swapped ❤️ consumes the arm — if some other message confirms first, leave the arm
        // in place for the real heart.
        if ((owner.message ?: return false).trim() != RED_HEART) return false
        if (!armedDialogs.remove(msg.dialogId)) return false
        Anim(msg, msg.dialogId, chat).also { active.add(it) }.start()
        return true
    }

    /** Stops every running animation for [dialogId]. Call from ChatActivity.onFragmentDestroy. */
    fun cancel(dialogId: Long) {
        val it = active.iterator()
        while (it.hasNext()) {
            val a = it.next()
            if (a.dialogId == dialogId) {
                handler.removeCallbacks(a)
                it.remove()
            }
        }
        armedDialogs.remove(dialogId)
    }

    private class Anim(
        private val msg: MessageObject,
        val dialogId: Long,
        private val chat: ChatActivity
    ) : Runnable {

        private val total = CYCLES * HEARTS.size  // animated frames before the settle frame
        private var frame = 0

        fun start() = handler.post(this)

        override fun run() {
            try {
                if (frame < total) {
                    // Local-only frame: smooth, free, never touches the network.
                    chat.fenixRenderHeartFrame(msg, HEARTS[frame % HEARTS.size])
                    frame++
                    handler.postDelayed(this, STEP_MS)
                } else {
                    // Settle on red locally. The message was already SENT as ❤️, so there is NO server
                    // edit here — no "edited" tag, and the recipient already has the clean heart.
                    chat.fenixRenderHeartFrame(msg, HEARTS[0])
                    finish()
                }
            } catch (e: Exception) {
                finish()
            }
        }

        private fun finish() {
            handler.removeCallbacks(this)
            active.remove(this)
        }
    }
}
