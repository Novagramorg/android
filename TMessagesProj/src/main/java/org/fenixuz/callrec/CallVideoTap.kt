package org.fenixuz.callrec

import org.webrtc.VideoFrame

/**
 * Novagram: the call's two video streams, told to the running video recorder. `VoIPService`'s local and remote proxy
 * sinks call [onFrame] for every frame they pass on (camera or shared screen on our side, the decoded picture on the
 * other), on the capturer's and decoder's threads. The sink must retain the frame if it keeps it and return at once.
 * With no recording running the call costs one volatile read.
 */
object CallVideoTap {

    const val SIDE_LOCAL = 1
    const val SIDE_REMOTE = 2

    interface Sink {
        fun onVideoFrame(frame: VideoFrame, side: Int)
    }

    @Volatile
    private var sink: Sink? = null

    fun attach(sink: Sink) {
        this.sink = sink
    }

    fun detach(sink: Sink) {
        if (this.sink === sink) this.sink = null
    }

    @JvmStatic
    fun onFrame(frame: VideoFrame, side: Int) {
        val s = sink ?: return
        try {
            s.onVideoFrame(frame, side)
        } catch (ignored: Throwable) {
        }
    }
}
