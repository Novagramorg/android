package org.fenixuz.callrec

import android.app.Activity
import android.os.SystemClock
import android.widget.Toast
import org.fenixuz.utils.LanguageCode
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.UserObject
import org.telegram.messenger.Utilities
import org.telegram.messenger.voip.Instance
import org.telegram.messenger.voip.VoIPService
import org.telegram.ui.ActionBar.DarkAlertDialog

/**
 * Novagram: records 1:1 calls. The one place that knows when a recording may start, starts and stops it, tells the
 * other person, and reports the result. Main thread only; the call service reports its state from other threads and is
 * hopped onto the main thread here.
 *
 * Rules (the same as the iOS and macOS apps, so a Novagram user gets the same behaviour on every phone):
 *  - only private calls with a user (never group calls);
 *  - the Record button shows while the setting is on: from the start of an outgoing call, from the answer of an
 *    incoming one ([isAvailable]); a running recording keeps its REC mark whatever else happens;
 *  - the very first recording asks for consent once ([CallRecordingSettings.wasWarned]); Cancel on an automatic start
 *    also turns automatic recording off;
 *  - NEVER SECRET: the moment a recording really starts, "🔴 I'm recording this call (Novagram)" goes to the chat with
 *    the other person, once per call;
 *  - "Record every call automatically" starts once per call: an outgoing call when it rings, an incoming one when it is
 *    answered. A manual Stop is final for that call;
 *  - the recording ends with the call, with Stop, or when the service dies; the file is always finished.
 */
object CallRecording {

    fun interface Listener {
        fun onCallRecordingChanged()
    }

    private val listeners = ArrayList<Listener>()

    /** `SystemClock.elapsedRealtime()` when the running recording started; 0 while idle. */
    @JvmStatic
    @Volatile
    var startedAt = 0L
        private set

    private var recorder: CallAudioRecorder? = null
    private var videoRecorder: CallVideoRecorder? = null
    private var videoPartial: java.io.File? = null

    // What the call screen shows (which side is big, the corner of the small one), read a few times a second while a
    // video recording runs: the small window is dragged without any event the controller could listen to.
    private val layoutPoll = object : Runnable {
        override fun run() {
            val video = videoRecorder ?: return
            video.setLayout(currentLayout())
            AndroidUtilities.runOnUIThread(this, 250)
        }
    }

    // Per call.
    private var callId = 0L
    private var account = 0
    private var peerId = 0L
    private var peerName = ""
    private var didStartOnce = false
    private var noticeSentForCall = 0L
    private var asking = false
    private var stopping = false

    @JvmStatic
    fun isRecording(): Boolean = startedAt != 0L

    @JvmStatic
    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    @JvmStatic
    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun notifyChanged() {
        for (l in ArrayList(listeners)) l.onCallRecordingChanged()
    }

    // ---- what the call screen asks ----

    /** Whether the Record control may show for [service]'s call right now (a running recording always may). */
    @JvmStatic
    fun isAvailable(service: VoIPService?): Boolean {
        if (isRecording()) return true
        if (service == null || !CallRecordingSettings.isEnabled) return false
        if (service.user == null || service.chat != null) return false
        return isStateAllowed(service.callState, service.isOutgoing)
    }

    // Outgoing: from the first state. Incoming: only once answered (keys are exchanged).
    private fun isStateAllowed(state: Int, outgoing: Boolean): Boolean = when (state) {
        VoIPService.STATE_WAITING, VoIPService.STATE_REQUESTING, VoIPService.STATE_RINGING -> outgoing
        VoIPService.STATE_EXCHANGING_KEYS, VoIPService.STATE_WAIT_INIT, VoIPService.STATE_WAIT_INIT_ACK,
        VoIPService.STATE_ESTABLISHED, VoIPService.STATE_RECONNECTING -> true
        else -> false
    }

    private fun isTerminal(state: Int): Boolean = when (state) {
        VoIPService.STATE_ENDED, VoIPService.STATE_FAILED, VoIPService.STATE_HANGING_UP, VoIPService.STATE_BUSY -> true
        else -> false
    }

    // ---- the call service reports ----

    /** From `VoIPService.dispatchStateChanged`, any thread. */
    @JvmStatic
    fun onCallState(service: VoIPService, state: Int) {
        AndroidUtilities.runOnUIThread {
            val live = VoIPService.getSharedInstance()
            if (live !== service) return@runOnUIThread
            val id = service.callID
            if (id != callId) {
                // A new call: nothing of the previous one carries over.
                callId = id
                didStartOnce = false
                asking = false
            }
            if (isTerminal(state)) {
                if (isRecording()) stop()
            } else {
                autoStartIfNeeded(service, state)
            }
            notifyChanged()
        }
    }

    /** From `VoIPService.onDestroy`: the call is gone whatever state it reported last. */
    @JvmStatic
    fun onServiceDestroyed() {
        AndroidUtilities.runOnUIThread {
            if (isRecording()) stop()
            notifyChanged()
        }
    }

    private fun autoStartIfNeeded(service: VoIPService, state: Int) {
        if (!CallRecordingSettings.isEnabled || !CallRecordingSettings.isAutoEnabled) return
        if (didStartOnce || isRecording() || service.user == null || service.chat != null) return
        // The consent alert needs a screen; an automatic start cannot show one, so it waits for the first manual one.
        if (!CallRecordingSettings.wasWarned) return
        val shouldStart = if (service.isOutgoing) {
            state == VoIPService.STATE_RINGING || (state != VoIPService.STATE_WAITING && state != VoIPService.STATE_REQUESTING && isStateAllowed(state, true))
        } else {
            isStateAllowed(state, false)
        }
        if (shouldStart) {
            didStartOnce = true
            start(service)
        }
    }

    // ---- the Record control ----

    /** A tap on the control. [activity] shows the consent alert the first time. */
    @JvmStatic
    fun toggle(activity: Activity?) {
        if (isRecording()) {
            stop()
            return
        }
        val service = VoIPService.getSharedInstance() ?: return
        if (asking || stopping || !isAvailable(service)) return
        if (CallRecordingSettings.wasWarned) {
            didStartOnce = true
            start(service)
            return
        }
        if (activity == null || activity.isFinishing) return
        asking = true
        val dialog = DarkAlertDialog.Builder(activity)
            .setTitle(LanguageCode.getMyTitles(537))
            .setMessage(LanguageCode.getMyTitles(538))
            .setNegativeButton(LocaleController.getString(org.telegram.messenger.R.string.Cancel)) { _, _ -> }
            .setPositiveButton(LanguageCode.getMyTitles(539)) { _, _ ->
                CallRecordingSettings.wasWarned = true
                val live = VoIPService.getSharedInstance()
                if (live != null && isAvailable(live)) {
                    didStartOnce = true
                    start(live)
                }
            }
            .create()
        dialog.setOnDismissListener { asking = false }
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    // ---- start / stop ----

    private fun start(service: VoIPService) {
        if (isRecording()) return
        val user = service.user ?: return
        // A stream a killed app left behind is finished now, before a new one begins (it skips files being written).
        recoverLeftovers()
        account = service.account
        peerId = user.id
        peerName = UserObject.getUserName(user)
        val name = peerName
        val startDate = System.currentTimeMillis()
        val rec = CallAudioRecorder(
            partial = CallRecordingStorage.newPartialFile("aac"),
            finalFileFor = { ext -> CallRecordingStorage.finalFile(name, startDate, ext) },
            isMicMuted = { VoIPService.getSharedInstance()?.isMicMute ?: false },
        )
        // Read at each start: a change in Settings applies from the next recording. Video is followed for the whole
        // recording, in a voice call too (the camera may come on later); without any video the file ends up audio.
        val quality = CallRecordingSettings.quality
        var video: CallVideoRecorder? = null
        if (quality.recordsVideo) {
            val partial = CallRecordingStorage.newPartialFile(CallRecordingStorage.VIDEO_EXTENSION)
            video = CallVideoRecorder(partial, quality.width, quality.height, quality.framesPerSecond, quality.bitRate) { rec.timelineStartNs }
            try {
                video.start()
                videoPartial = partial
                rec.aacSink = video
            } catch (e: Throwable) {
                // No video encoder on this device: the voices alone are still worth keeping.
                FileLog.e(e)
                partial.delete()
                video = null
            }
        }
        try {
            rec.start()
        } catch (e: Throwable) {
            FileLog.e(e)
            video?.stop {}
            videoPartial?.delete()
            videoPartial = null
            toast(LanguageCode.getMyTitles(541))
            return
        }
        recorder = rec
        videoRecorder = video
        if (video != null) {
            video.setLayout(currentLayout())
            AndroidUtilities.runOnUIThread(layoutPoll, 250)
        }
        startedAt = SystemClock.elapsedRealtime()
        FileLog.d("CallRecording: started for call $callId, quality=${quality.key}")
        notifyChanged()
        sendNoticeIfNeeded()
    }

    /** The call screen's picture-in-picture: from the screen when it is open, the defaults when it is not. */
    private fun currentLayout(): CallVideoRecorder.Layout {
        val service = VoIPService.getSharedInstance()
        val screen = org.telegram.ui.VoIPFragment.getInstance()?.novagramVideoLayout()
        val localIsScreen = service?.isScreencast ?: false
        // "Active" is the state the other person sees: a previewed or paused camera is not video of the call.
        val localVideo = service != null && (service.getVideoState(false) == Instance.VIDEO_STATE_ACTIVE || service.getVideoState(true) == Instance.VIDEO_STATE_ACTIVE)
        val remoteVideo = service != null && service.remoteVideoState == Instance.VIDEO_STATE_ACTIVE
        return CallVideoRecorder.Layout(
            localIsBig = localIsScreen || (screen != null && screen[2] == 1),
            pipTop = screen != null && screen[0] == 1,
            pipLeft = screen != null && screen[1] == 1,
            localIsScreen = localIsScreen,
            mirrorLocal = service?.isFrontFaceCamera ?: true,
            localVideo = localVideo,
            remoteVideo = remoteVideo,
        )
    }

    /** Stops the running recording; the result shows as a toast. */
    @JvmStatic
    fun stop() {
        val rec = recorder ?: return
        if (stopping) return
        stopping = true
        didStartOnce = true
        AndroidUtilities.cancelRunOnUIThread(layoutPoll)
        val video = videoRecorder
        videoRecorder = null
        val partial = videoPartial
        videoPartial = null
        // The video container closes first (it needs the audio's last frames, which keep coming until the audio
        // worker ends; the muxer takes what it has), then the audio decides which file the recording is.
        val finishAudio = { videoFile: java.io.File? -> rec.stop(videoFile) { result -> onStopped(result) } }
        if (video != null) {
            video.stop { finishAudio(if (video.hadVideo) partial else partial?.also { it.delete() }.let { null }) }
        } else {
            finishAudio(null)
        }
    }

    private fun onStopped(result: CallAudioRecorder.Result) {
        run {
            recorder = null
            startedAt = 0L
            stopping = false
            when (result) {
                is CallAudioRecorder.Result.Saved -> {
                    FileLog.d("CallRecording: saved ${result.file.name}, ${result.durationMs} ms")
                    toast(LanguageCode.getMyTitles(540) + " · " + AndroidUtilities.formatShortDuration((result.durationMs / 1000).toInt()))
                }
                is CallAudioRecorder.Result.Nothing -> toast(LanguageCode.getMyTitles(542))
                is CallAudioRecorder.Result.Failed -> {
                    FileLog.d("CallRecording: failed: ${result.reason}")
                    toast(LanguageCode.getMyTitles(541))
                }
            }
            notifyChanged()
        }
    }

    /** "🔴 I'm recording this call (Novagram)" to the chat with the other person, once per call. */
    private fun sendNoticeIfNeeded() {
        if (noticeSentForCall == callId || peerId == 0L) return
        noticeSentForCall = callId
        try {
            val params = SendMessagesHelper.SendMessageParams.of(
                LanguageCode.getMyTitles(536), peerId, null, null, null, false, null, null, null, true, 0, 0, null, false
            )
            SendMessagesHelper.getInstance(account).sendMessage(params)
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    private fun toast(text: String) {
        try {
            Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_LONG).show()
        } catch (ignored: Throwable) {
        }
    }

    /** Finishes any stream a killed app left behind. Off the UI thread, cheap when there is none. */
    @JvmStatic
    fun recoverLeftovers() {
        Utilities.globalQueue.postRunnable {
            CallRecordingStorage.recover()
            CallRecordingStorage.prune()
        }
    }
}
