package org.fenixuz.callrec

import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import org.telegram.messenger.FileLog
import org.telegram.messenger.voip.VideoCapturerDevice
import org.webrtc.EglBase
import org.webrtc.GlGenericDrawer
import org.webrtc.GlShader
import org.webrtc.GlTextureFrameBuffer
import org.webrtc.RendererCommon
import org.webrtc.VideoFrame
import org.webrtc.VideoFrameDrawer
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * Novagram: the video of a recorded call, composed into one picture and written with the mixed voices into an `.mp4`.
 *
 * Both sides' frames arrive through [CallVideoTap] on the call's own threads. Each one is handed to our GL thread,
 * copied at once into that side's own RGB texture (a framebuffer the size of the upright frame) and released. The
 * copy is not optional: the camera and the hardware decoder deliver one shared texture frame at a time and wait for
 * its release before producing the next, so a recorder that kept the newest frame until the next arrived froze the
 * camera. From the two side textures the composite is drawn at the configured rate: the big side aspect-fit on a
 * 720x1280 portrait canvas (black bars where it does not fill), the small side as a rounded picture-in-picture in the
 * corner the call screen shows it in ([setLayout]). Our camera is mirrored like the preview; a shared screen is not.
 * Drawing uses WebRTC's own `VideoFrameDrawer` on an EGL context shared with the call's capturer and decoder, so a
 * texture frame is copied on the GPU, never read back. Until the first real frame the canvas is black at one frame a
 * second, so the video track starts at time zero with the audio; a recording that never saw a real frame ([hadVideo]
 * false) is thrown away by the caller in favour of the audio-only file.
 *
 * The audio comes from [CallAudioRecorder] through [CallAudioRecorder.AacSink]: the same encoded frames it writes to
 * its crash-safe stream also go into this muxer, so the `.mp4` holds both and nothing is encoded twice. The muxer can
 * only start once both tracks are known, so audio frames before the first video frame wait in a bounded queue.
 */
class CallVideoRecorder(
    private val file: File,
    private val width: Int,
    private val height: Int,
    private val framesPerSecond: Int,
    private val bitRate: Int,
    private val timelineStartNs: () -> Long,
) : CallVideoTap.Sink, CallAudioRecorder.AacSink {

    // Picture-in-picture geometry scales with the canvas: 30% wide, 30% of the height at most, margin and radius alike.
    private val pipWidth = width * 3 / 10 and 1.inv()
    private val pipMaxHeight = height * 3 / 10 and 1.inv()
    private val pipMargin = width / 30
    private val pipRadiusPx = width * 28f / 720f

    /**
     * Which side is big and where the small picture sits, and which sides are actually sending video right now.
     * Frames reach the local sink while the camera is only previewed (before "Start video", or paused in the
     * background) without the other person seeing anything; the recording keeps to what the call really showed, so a
     * side whose video is not active is neither copied nor drawn, and a voice call with a peek at the camera stays audio.
     */
    class Layout(
        val localIsBig: Boolean,
        val pipTop: Boolean,
        val pipLeft: Boolean,
        val localIsScreen: Boolean,
        val mirrorLocal: Boolean,
        val localVideo: Boolean,
        val remoteVideo: Boolean,
    ) {
        fun shows(side: Int): Boolean = if (side == CallVideoTap.SIDE_LOCAL) localVideo else remoteVideo
    }

    @Volatile var hadVideo = false
        private set

    private val thread = HandlerThread("novagram-call-video")
    private lateinit var handler: Handler
    @Volatile private var running = false
    @Volatile private var layout = Layout(localIsBig = false, pipTop = false, pipLeft = false, localIsScreen = false, mirrorLocal = true, localVideo = false, remoteVideo = false)
    private var shownLocal = false
    private var shownRemote = false

    // Frames waiting for the GL thread, one slot per side (a newer frame replaces an unprocessed one). Held only for
    // the time it takes the GL thread to copy them; see the class comment for why they are never kept longer.
    private val pending = arrayOfNulls<VideoFrame>(3) // indexed by side
    private val pendingLock = Any()

    // GL thread only: each side's latest picture as an upright RGB texture, and its size (0 = nothing yet).
    private val sideTex = arrayOfNulls<GlTextureFrameBuffer>(3)
    private val sideWidth = IntArray(3)
    private val sideHeight = IntArray(3)
    private var dirty = false

    private var eglBase: EglBase? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var drawer: GlGenericDrawer? = null
    private var frameDrawer: VideoFrameDrawer? = null
    private val mirrorMatrix = RendererCommon.convertMatrixFromAndroidGraphicsMatrix(Matrix().apply {
        preTranslate(0.5f, 0.5f)
        preScale(-1f, 1f)
        preTranslate(-0.5f, -0.5f)
    })
    private val identityMatrix = RendererCommon.convertMatrixFromAndroidGraphicsMatrix(Matrix())

    // Muxer: written from the GL thread (video) and the audio worker (audio); guarded by muxerLock.
    private val muxerLock = Any()
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var videoTrack = -1
    private var audioTrack = -1
    private var audioFormat: MediaFormat? = null
    private var videoFormat: MediaFormat? = null
    private val pendingAudio = ArrayDeque<Pair<ByteArray, MediaCodec.BufferInfo>>()
    private var lastVideoPtsUs = -1L
    private var lastAudioPtsUs = -1L

    // Rounded picture-in-picture: the fragment shader discards what lies outside the rounded rect, in pixels.
    private var pipRectLocation = -1
    private var pipRadiusLocation = -1
    private val pipRect = FloatArray(4)
    private var pipRadius = 0f

    private val shaderCallbacks = object : GlGenericDrawer.ShaderCallbacks {
        override fun onNewShader(shader: GlShader) {
            pipRectLocation = shader.getUniformLocation("pipRect")
            pipRadiusLocation = shader.getUniformLocation("pipRadius")
        }

        override fun onPrepareShader(shader: GlShader, texMatrix: FloatArray?, frameWidth: Int, frameHeight: Int, viewportWidth: Int, viewportHeight: Int) {
            GLES20.glUniform4f(pipRectLocation, pipRect[0], pipRect[1], pipRect[2], pipRect[3])
            GLES20.glUniform1f(pipRadiusLocation, pipRadius)
        }
    }

    // ---- lifecycle ----

    /**
     * Opens the muxer here and the encoder and GL on the recorder's own thread: a hardware encoder takes a few hundred
     * milliseconds to come up, too long for the UI thread that pressed Record. A device that cannot encode is found
     * out there: the recording then simply never has video ([hadVideo] stays false) and the caller keeps the audio.
     */
    fun start() {
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        thread.start()
        handler = Handler(thread.looper)
        running = true
        handler.post {
            if (initEncoder()) initGl()
        }
        handler.postDelayed(tick, 1000L / framesPerSecond)
        CallVideoTap.attach(this)
    }

    private fun initEncoder(): Boolean {
        return try {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, framesPerSecond)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            encoder = codec
            true
        } catch (e: Throwable) {
            FileLog.e(e)
            running = false
            false
        }
    }

    /** Stops drawing, ends the video stream and closes the muxer. [onDone] runs on the calling thread's looper. */
    fun stop(onDone: () -> Unit) {
        if (!running) {
            onDone()
            return
        }
        running = false
        CallVideoTap.detach(this)
        val caller = Handler(android.os.Looper.myLooper() ?: android.os.Looper.getMainLooper())
        handler.post {
            try {
                encoder?.signalEndOfInputStream()
                drainVideo(true)
            } catch (e: Throwable) {
                FileLog.e(e)
            }
            releaseGl()
            try {
                encoder?.stop()
            } catch (ignored: Throwable) {
            }
            try {
                encoder?.release()
            } catch (ignored: Throwable) {
            }
            encoder = null
            inputSurface?.release()
            inputSurface = null
            synchronized(muxerLock) {
                try {
                    if (muxerStarted) muxer?.stop()
                } catch (e: Throwable) {
                    FileLog.e(e)
                    hadVideo = false
                }
                try {
                    muxer?.release()
                } catch (ignored: Throwable) {
                }
                muxer = null
                muxerStarted = false
            }
            releasePending()
            thread.quitSafely()
            caller.post(onDone)
        }
    }

    /** What the call screen shows right now; read by the controller a few times a second. */
    fun setLayout(layout: Layout) {
        this.layout = layout
    }

    // ---- frames in ----

    override fun onVideoFrame(frame: VideoFrame, side: Int) {
        if (!running || side !in 1..2) return
        frame.retain()
        val old: VideoFrame?
        synchronized(pendingLock) {
            old = pending[side]
            pending[side] = frame
        }
        old?.release()
        handler.post(copyPending)
    }

    /** GL thread: copies every waiting frame into its side's texture and gives the frame back. */
    private val copyPending = Runnable {
        for (side in 1..2) {
            val frame: VideoFrame?
            synchronized(pendingLock) {
                frame = pending[side]
                pending[side] = null
            }
            if (frame == null) continue
            try {
                if (running && eglBase != null && layout.shows(side)) copyToSide(side, frame)
            } catch (e: Throwable) {
                FileLog.e(e)
            } finally {
                frame.release()
            }
        }
    }

    private fun releasePending() {
        synchronized(pendingLock) {
            for (i in pending.indices) {
                pending[i]?.release()
                pending[i] = null
            }
        }
    }

    /** Draws [frame] upright into side [side]'s framebuffer texture, (re)sized to the frame. GL thread. */
    private fun copyToSide(side: Int, frame: VideoFrame) {
        val fd = frameDrawer ?: return
        val d = drawer ?: return
        val w = frame.rotatedWidth
        val h = frame.rotatedHeight
        if (w <= 0 || h <= 0) return
        val fbo = sideTex[side] ?: GlTextureFrameBuffer(GLES20.GL_RGBA).also { sideTex[side] = it }
        fbo.setSize(w, h)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo.frameBufferId)
        pipRadius = 0f
        fd.drawFrame(frame, d, null, 0, 0, w, h, false, false)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        sideWidth[side] = w
        sideHeight[side] = h
        dirty = true
    }

    override fun onAacFormat(format: MediaFormat) {
        synchronized(muxerLock) {
            audioFormat = format
            tryStartMuxer()
        }
    }

    override fun onAacFrame(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(muxerLock) {
            if (muxer == null) return
            if (muxerStarted) {
                writeAudio(data, info)
            } else {
                if (pendingAudio.size >= MAX_PENDING_AUDIO) pendingAudio.removeFirst()
                val copy = ByteArray(info.size)
                data.get(copy)
                val ci = MediaCodec.BufferInfo()
                ci.set(0, info.size, info.presentationTimeUs, info.flags)
                pendingAudio.addLast(copy to ci)
            }
        }
    }

    private fun writeAudio(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.presentationTimeUs <= lastAudioPtsUs) info.presentationTimeUs = lastAudioPtsUs + 1
        lastAudioPtsUs = info.presentationTimeUs
        try {
            muxer?.writeSampleData(audioTrack, data, info)
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    /** Both formats known: start, and let the waiting audio through. Call under muxerLock. */
    private fun tryStartMuxer() {
        val m = muxer ?: return
        if (muxerStarted) return
        val af = audioFormat ?: return
        val vf = videoFormat ?: return
        try {
            videoTrack = m.addTrack(vf)
            audioTrack = m.addTrack(af)
            m.start()
            muxerStarted = true
            while (pendingAudio.isNotEmpty()) {
                val (bytes, info) = pendingAudio.removeFirst()
                writeAudio(ByteBuffer.wrap(bytes), info)
            }
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    // ---- GL thread ----

    private fun initGl() {
        try {
            val shared = VideoCapturerDevice.getEglBase().eglBaseContext
            val egl = EglBase.create(shared, EglBase.CONFIG_RECORDABLE)
            egl.createSurface(inputSurface!!)
            egl.makeCurrent()
            eglBase = egl
            drawer = GlGenericDrawer(FRAGMENT_SHADER, shaderCallbacks)
            frameDrawer = VideoFrameDrawer()
        } catch (e: Throwable) {
            FileLog.e(e)
            running = false
        }
    }

    private fun releaseGl() {
        try {
            for (i in sideTex.indices) {
                sideTex[i]?.release()
                sideTex[i] = null
                sideWidth[i] = 0
                sideHeight[i] = 0
            }
            frameDrawer?.release()
            drawer?.release()
            eglBase?.let {
                it.detachCurrent()
                it.release()
            }
        } catch (ignored: Throwable) {
        }
        frameDrawer = null
        drawer = null
        eglBase = null
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                drawIfDue()
            } catch (e: Throwable) {
                FileLog.e(e)
            }
            handler.postDelayed(this, 1000L / framesPerSecond)
        }
    }

    private var lastDrawNs = 0L

    private fun drawIfDue() {
        val egl = eglBase ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        val l = layout
        val hasLocal = l.localVideo && sideWidth[CallVideoTap.SIDE_LOCAL] > 0
        val hasRemote = l.remoteVideo && sideWidth[CallVideoTap.SIDE_REMOTE] > 0
        // A side that stopped sending disappears from the picture at once, not at the next frame.
        val changed = dirty || hasLocal != shownLocal || hasRemote != shownRemote
        dirty = false
        shownLocal = hasLocal
        shownRemote = hasRemote
        // Without a new frame, black (or the last picture) is repeated once a second: the track must not stall.
        if (!changed && now - lastDrawNs < 1_000_000_000L) return
        lastDrawNs = now
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (hasLocal || hasRemote) {
            hadVideo = true
            compose(hasLocal, hasRemote)
        }
        egl.swapBuffers(nextVideoPtsNs(now), false)
        drainVideo(false)
    }

    /** The video clock is the audio's: elapsed since the mixed timeline's time zero. Always moves forward. */
    private fun nextVideoPtsNs(nowNs: Long): Long {
        val start = timelineStartNs()
        var ptsUs = if (start > 0) (nowNs - start) / 1000 - CallAudioRecorder.PRIME_MS * 1000 else 0L
        if (ptsUs <= lastVideoPtsUs) ptsUs = lastVideoPtsUs + 1000
        if (ptsUs < 0) ptsUs = 0
        lastVideoPtsUs = ptsUs
        return ptsUs * 1000
    }

    private fun compose(hasLocal: Boolean, hasRemote: Boolean) {
        val l = layout
        val bigIsLocal = if (hasLocal && hasRemote) l.localIsBig else hasLocal
        val big = if (bigIsLocal) CallVideoTap.SIDE_LOCAL else CallVideoTap.SIDE_REMOTE
        val small = if (hasLocal && hasRemote) (if (bigIsLocal) CallVideoTap.SIDE_REMOTE else CallVideoTap.SIDE_LOCAL) else 0
        val d = drawer ?: return
        pipRadius = 0f
        draw(d, big, fit(big, 0, 0, width, height), bigIsLocal && !l.localIsScreen && l.mirrorLocal)
        if (small != 0) {
            val w = sideWidth[small].coerceAtLeast(1)
            val h = sideHeight[small].coerceAtLeast(1)
            var pipW = pipWidth
            var pipH = pipWidth * h / w
            if (pipH > pipMaxHeight) {
                pipH = pipMaxHeight
                pipW = pipMaxHeight * w / h
            }
            val x = if (l.pipLeft) pipMargin else width - pipMargin - pipW
            // GL's y runs upwards: "top" of the picture is the high end.
            val y = if (l.pipTop) height - pipMargin - pipH else pipMargin
            pipRect[0] = x.toFloat(); pipRect[1] = y.toFloat(); pipRect[2] = pipW.toFloat(); pipRect[3] = pipH.toFloat()
            pipRadius = pipRadiusPx
            draw(d, small, intArrayOf(x, y, pipW, pipH), !bigIsLocal && !l.localIsScreen && l.mirrorLocal)
        }
    }

    /** Aspect-fit of side [side]'s picture inside the box, in GL pixels (origin bottom-left). */
    private fun fit(side: Int, x: Int, y: Int, w: Int, h: Int): IntArray {
        val fw = sideWidth[side].coerceAtLeast(1)
        val fh = sideHeight[side].coerceAtLeast(1)
        val scale = minOf(w.toFloat() / fw, h.toFloat() / fh)
        val dw = (fw * scale).toInt() and 1.inv()
        val dh = (fh * scale).toInt() and 1.inv()
        return intArrayOf(x + (w - dw) / 2, y + (h - dh) / 2, dw, dh)
    }

    /** Draws side [side]'s texture into [rect] of the canvas, mirrored when asked. */
    private fun draw(d: GlGenericDrawer, side: Int, rect: IntArray, mirror: Boolean) {
        val fbo = sideTex[side] ?: return
        val w = sideWidth[side]
        val h = sideHeight[side]
        d.drawRgb(fbo.textureId, w, h, w, h, if (mirror) mirrorMatrix else identityMatrix, w, h, rect[0], rect[1], rect[2], rect[3], false)
    }

    private fun drainVideo(untilEnd: Boolean) {
        val codec = encoder ?: return
        val info = MediaCodec.BufferInfo()
        // Ending: wait for the encoder's end-of-stream mark, but never for more than a few seconds (a stuck encoder
        // must not hold the call's teardown; the muxer then keeps what it has).
        var waits = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEnd) 100_000L else 0L)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(muxerLock) {
                    videoFormat = codec.outputFormat
                    tryStartMuxer()
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEnd || ++waits > 30) return
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        synchronized(muxerLock) {
                            if (muxerStarted) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                try {
                                    muxer?.writeSampleData(videoTrack, buffer, info)
                                } catch (e: Throwable) {
                                    FileLog.e(e)
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    companion object {
        private const val MAX_PENDING_AUDIO = 600

        /** Everything outside the rounded [pipRect] (pixels; a zero radius means "no rounding") is dropped. */
        private const val FRAGMENT_SHADER = """
uniform vec4 pipRect;
uniform float pipRadius;
void main() {
  if (pipRadius > 0.0) {
    vec2 p = gl_FragCoord.xy - pipRect.xy;
    vec2 c = clamp(p, vec2(pipRadius), pipRect.zw - vec2(pipRadius));
    if (distance(p, c) > pipRadius) discard;
  }
  gl_FragColor = sample(tc);
}
"""
    }
}
