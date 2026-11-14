package org.fenixuz.callrec

import java.nio.ByteBuffer

/**
 * Novagram: the two places a call's sound passes through Java, told to the running recorder.
 *
 * tgcalls on Android plays and records through the Java audio device module (`org.webrtc.voiceengine`): every 10 ms
 * `WebRtcAudioRecord` hands the microphone's PCM to native code and `WebRtcAudioTrack` writes the far end's PCM to the
 * speaker. Those two threads call [onMic] and [onPlayout] with the buffers as they are (16-bit PCM, native byte
 * order), on their real-time threads: the sink must only copy, never block or allocate per call. With no recording
 * running the calls cost one volatile read.
 */
object CallAudioTap {

    interface Sink {
        fun onMic(buffer: ByteBuffer, bytes: Int, sampleRate: Int, channels: Int)
        fun onPlayout(buffer: ByteBuffer, bytes: Int, sampleRate: Int, channels: Int)
    }

    @Volatile
    private var sink: Sink? = null

    fun attach(sink: Sink) {
        this.sink = sink
    }

    fun detach(sink: Sink) {
        if (this.sink === sink) this.sink = null
    }

    /** From WebRtcAudioRecord's thread: the microphone, after the mute switch (muted = zeros). */
    @JvmStatic
    fun onMic(buffer: ByteBuffer, bytes: Int, sampleRate: Int, channels: Int) {
        val s = sink ?: return
        try {
            s.onMic(buffer, bytes, sampleRate, channels)
        } catch (ignored: Throwable) {
        }
    }

    /** From WebRtcAudioTrack's thread: what the speaker plays, the other person and the call tones. */
    @JvmStatic
    fun onPlayout(buffer: ByteBuffer, bytes: Int, sampleRate: Int, channels: Int) {
        val s = sink ?: return
        try {
            s.onPlayout(buffer, bytes, sampleRate, channels)
        } catch (ignored: Throwable) {
        }
    }
}
