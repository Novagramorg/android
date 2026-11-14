package org.fenixuz.callrec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import org.telegram.messenger.FileLog
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections

/**
 * Novagram: mixes a call's two voices into one file while the call runs.
 *
 * Sound arrives on two real-time threads ([CallAudioTap]): the microphone (us) and the playout (the other person and
 * the call tones), each in its own sample rate and channel count. Each is turned into 48 kHz mono on arrival and put
 * in its own ring. A worker thread produces the recording's timeline against the wall clock, [PRIME_MS] behind real
 * time so a late buffer is not a dropout: every 10 ms it takes the frames due from both rings (silence where a ring is
 * empty), adds them, and feeds the AAC encoder. A ring that drifts ahead of the clock is trimmed to [LEAD_LOW_MS],
 * so a long call can never pile up delay or memory. Mute keeps the microphone in step with silence.
 *
 * The encoder's frames go to the file at once as a raw ADTS stream, so a crash or a killed process loses at most the
 * last fraction of a second and [CallRecordingStorage.recover] can still finish the file. On [stop] the stream is
 * wrapped into an `.m4a` (no re-encoding) and moved into the recordings folder.
 *
 * 48 kHz mono AAC-LC at 64 kbit/s: voice-clear, about 480 KB a minute.
 */
class CallAudioRecorder(
    private val partial: File,
    private val finalFileFor: (extension: String) -> File,
    private val isMicMuted: () -> Boolean,
) : CallAudioTap.Sink {

    /** Receives every encoded AAC frame as it is written, for the video recorder's muxer. */
    interface AacSink {
        fun onAacFormat(format: MediaFormat)
        fun onAacFrame(data: ByteBuffer, info: MediaCodec.BufferInfo)
    }

    @Volatile var aacSink: AacSink? = null

    /** `SystemClock.elapsedRealtimeNanos()` of the timeline's time zero; -1 until the first sound. */
    @Volatile var timelineStartNs = -1L
        private set

    sealed class Result {
        class Saved(val file: File, val durationMs: Long) : Result()
        object Nothing : Result()
        class Failed(val reason: String) : Result()
    }

    private val mic = PcmRing(RING_FRAMES)
    private val playout = PcmRing(RING_FRAMES)
    private val micConverter = ToMono48k()
    private val playoutConverter = ToMono48k()

    @Volatile private var running = false
    @Volatile private var stopRequested = false
    @Volatile private var failure: String? = null
    private var thread: Thread? = null

    /** Frames of the timeline produced so far (48 kHz). */
    @Volatile var producedFrames = 0L
        private set

    val durationMs: Long get() = producedFrames * 1000 / SAMPLE_RATE

    // ---- taps (real-time threads) ----

    override fun onMic(buffer: ByteBuffer, bytes: Int, sampleRate: Int, channels: Int) {
        if (!running) return
        micConverter.feed(buffer, bytes, channels, sampleRate, mic)
    }

    override fun onPlayout(buffer: ByteBuffer, bytes: Int, sampleRate: Int, channels: Int) {
        if (!running) return
        playoutConverter.feed(buffer, bytes, channels, sampleRate, playout)
    }

    // ---- lifecycle (UI thread) ----

    /**
     * Opens the file and starts listening; the encoder comes up on the worker (it takes a few hundred milliseconds,
     * too long for the UI thread). Throws when the file cannot be opened; an encoder that cannot be created ends the
     * recording as [Result.Failed].
     */
    fun start() {
        check(thread == null) { "started twice" }
        val out = BufferedOutputStream(FileOutputStream(partial), 64 * 1024)
        synchronized(writing) { writing.add(partial.absolutePath) }
        running = true
        CallAudioTap.attach(this)
        thread = Thread({
            val codec = try {
                createEncoder()
            } catch (e: Throwable) {
                FileLog.e(e)
                failure = e.javaClass.simpleName
                running = false
                try {
                    out.close()
                } catch (ignored: Exception) {
                }
                return@Thread
            }
            work(codec, out)
        }, "novagram-call-rec").also {
            it.priority = Thread.NORM_PRIORITY + 1
            it.start()
        }
    }

    private fun createEncoder(): MediaCodec {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_FRAMES * 2 * 4)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return codec
    }

    /**
     * Stops listening, finishes the file and reports on the UI thread. [videoFile] is the finished `.mp4` of the
     * video recorder when the call had video (it already holds this audio): it becomes the recording and the audio
     * stream is dropped; otherwise the stream becomes an `.m4a`. [onDone] runs exactly once. Safe to call twice.
     */
    fun stop(videoFile: File?, onDone: (Result) -> Unit) {
        if (stopRequested) return
        stopRequested = true
        CallAudioTap.detach(this)
        val worker = thread
        Thread({
            try {
                worker?.join(8_000)
            } catch (ignored: InterruptedException) {
            }
            val result = finish(videoFile)
            org.telegram.messenger.AndroidUtilities.runOnUIThread { onDone(result) }
        }, "novagram-call-rec-finish").start()
    }

    private fun finish(videoFile: File?): Result {
        synchronized(writing) { writing.remove(partial.absolutePath) }
        failure?.let {
            partial.delete()
            videoFile?.delete()
            return Result.Failed(it)
        }
        val duration = durationMs
        if (producedFrames < SAMPLE_RATE / 2 || partial.length() == 0L) {
            partial.delete()
            videoFile?.delete()
            return Result.Nothing
        }
        if (videoFile != null && videoFile.length() > 0) {
            val target = finalFileFor("mp4")
            if (videoFile.renameTo(target)) {
                partial.delete()
                CallRecordingStorage.rememberDuration(target, duration)
                return Result.Saved(target, duration)
            }
            // The move failed: fall through to the audio-only file rather than lose the call.
            videoFile.delete()
        }
        val target = finalFileFor(CallRecordingStorage.EXTENSION)
        return try {
            if (AacRemux.toM4a(partial, target)) {
                partial.delete()
                CallRecordingStorage.rememberDuration(target, duration)
                Result.Saved(target, duration)
            } else {
                target.delete()
                Result.Failed("remux")
            }
        } catch (e: Exception) {
            FileLog.e(e)
            target.delete()
            Result.Failed(e.javaClass.simpleName)
        }
    }

    // ---- worker ----

    private fun work(codec: MediaCodec, out: BufferedOutputStream) {
        val info = MediaCodec.BufferInfo()
        val pcm = ByteBuffer.allocateDirect(CHUNK_FRAMES * 2).order(ByteOrder.LITTLE_ENDIAN)
        val mixed = ShortArray(CHUNK_FRAMES)
        var startedAt = -1L
        var ended = false
        try {
            codec.start()
            while (!ended) {
                if (!stopRequested) {
                    try {
                        Thread.sleep(TICK_MS)
                    } catch (ignored: InterruptedException) {
                    }
                }
                val now = SystemClock.elapsedRealtimeNanos()
                if (startedAt < 0) {
                    // Time zero: the first sound of either side. Nothing is produced before that.
                    if (mic.available() == 0 && playout.available() == 0) {
                        if (stopRequested) break
                        continue
                    }
                    startedAt = now
                    timelineStartNs = now
                }
                val due = ((now - startedAt - PRIME_MS * 1_000_000L).coerceAtLeast(0) * SAMPLE_RATE / 1_000_000_000L)
                var need = (due - producedFrames).coerceAtMost(MAX_CATCH_UP_FRAMES.toLong()).toInt()
                if (stopRequested) {
                    // Flush what the rings still hold, then end the stream.
                    need = maxOf(need, maxOf(mic.available(), playout.available()))
                    ended = true
                }
                val muted = isMicMuted()
                while (need > 0) {
                    val n = minOf(need, CHUNK_FRAMES)
                    mix(mixed, n, muted)
                    encode(codec, info, out, pcm, mixed, n, false)
                    producedFrames += n
                    need -= n
                }
                trim(mic)
                trim(playout)
                if (ended) {
                    encode(codec, info, out, pcm, mixed, 0, true)
                }
            }
        } catch (e: Throwable) {
            FileLog.e(e)
            failure = e.javaClass.simpleName
        } finally {
            running = false
            try {
                out.flush()
                out.close()
            } catch (ignored: Exception) {
            }
            try {
                codec.stop()
            } catch (ignored: Exception) {
            }
            try {
                codec.release()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun mix(outBuf: ShortArray, n: Int, muted: Boolean) {
        for (i in 0 until n) {
            val far = if (playout.available() > 0) playout.pop().toInt() else 0
            val nearRaw = if (mic.available() > 0) mic.pop().toInt() else 0
            val near = if (muted) 0 else nearRaw
            var v = far + near
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toInt()
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toInt()
            outBuf[i] = v.toShort()
        }
    }

    /** A ring that ran ahead of the clock (its source's clock is faster than ours) is cut back, once in a long while. */
    private fun trim(ring: PcmRing) {
        if (ring.available() > LEAD_HIGH_FRAMES) {
            ring.drop(ring.available() - LEAD_LOW_FRAMES)
        }
    }

    private fun encode(codec: MediaCodec, info: MediaCodec.BufferInfo, out: BufferedOutputStream, pcm: ByteBuffer, samples: ShortArray, n: Int, end: Boolean) {
        // Input: one chunk, or the end-of-stream mark.
        var queued = false
        while (!queued) {
            val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (index >= 0) {
                val input = codec.getInputBuffer(index) ?: continue
                input.clear()
                if (end) {
                    codec.queueInputBuffer(index, 0, 0, presentationUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    pcm.clear()
                    for (i in 0 until n) pcm.putShort(samples[i])
                    pcm.flip()
                    input.put(pcm)
                    codec.queueInputBuffer(index, 0, n * 2, presentationUs(), 0)
                }
                queued = true
            } else {
                drain(codec, info, out, false)
            }
        }
        drain(codec, info, out, end)
    }

    private fun presentationUs(): Long = producedFrames * 1_000_000L / SAMPLE_RATE

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo, out: BufferedOutputStream, untilEnd: Boolean) {
        val header = ByteArray(ADTS_HEADER)
        // Ending: wait for the end-of-stream mark, but at most a few seconds; a stuck encoder must not hold the stop.
        var waits = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEnd) CODEC_TIMEOUT_US * 10 else 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (untilEnd && ++waits <= 30) continue else return
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                aacSink?.onAacFormat(codec.outputFormat)
                continue
            }
            if (index < 0) continue
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                adtsHeader(header, info.size + ADTS_HEADER)
                out.write(header)
                val bytes = ByteArray(info.size)
                buffer.get(bytes)
                out.write(bytes)
                aacSink?.let { sink ->
                    val copy = ByteBuffer.wrap(bytes)
                    val ci = MediaCodec.BufferInfo()
                    ci.set(0, bytes.size, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                    sink.onAacFrame(copy, ci)
                }
            }
            codec.releaseOutputBuffer(index, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
        }
    }

    /** MPEG-4 AAC-LC, 48 kHz (index 3), mono, no CRC. */
    private fun adtsHeader(h: ByteArray, frameLength: Int) {
        h[0] = 0xFF.toByte()
        h[1] = 0xF1.toByte()
        h[2] = (((AAC_LC - 1) shl 6) or (FREQ_INDEX_48K shl 2) or (CHANNELS shr 2)).toByte()
        h[3] = (((CHANNELS and 3) shl 6) or (frameLength shr 11)).toByte()
        h[4] = ((frameLength and 0x7FF) shr 3).toByte()
        h[5] = (((frameLength and 7) shl 5) or 0x1F).toByte()
        h[6] = 0xFC.toByte()
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        private const val BIT_RATE = 64_000
        private const val CHANNELS = 1
        private const val AAC_LC = 2
        private const val FREQ_INDEX_48K = 3
        private const val ADTS_HEADER = 7
        private const val TICK_MS = 10L
        private const val CHUNK_FRAMES = 1024
        private const val CODEC_TIMEOUT_US = 10_000L
        /** The timeline runs this far behind real time, so sound that is a little late is not a gap. */
        const val PRIME_MS = 120L
        private const val RING_FRAMES = SAMPLE_RATE * 2
        private const val LEAD_HIGH_FRAMES = SAMPLE_RATE * 400 / 1000
        private const val LEAD_LOW_FRAMES = SAMPLE_RATE * 150 / 1000
        /** After a stall (the device slept) the timeline catches up at most this much per tick: no runaway loop. */
        private const val MAX_CATCH_UP_FRAMES = SAMPLE_RATE

        private val writing: MutableSet<String> = Collections.synchronizedSet(HashSet())

        /** Whether [file] is a partial stream a running recorder writes right now (recovery must leave it alone). */
        fun isWriting(file: File): Boolean = synchronized(writing) { writing.contains(file.absolutePath) }
    }
}

/**
 * A single-producer single-consumer ring of 16-bit frames. The producer is one audio thread, the consumer the worker;
 * the two indices are volatile, which is all a one-in one-out ring needs.
 */
internal class PcmRing(private val capacity: Int) {
    private val data = ShortArray(capacity)
    @Volatile private var head = 0L
    @Volatile private var tail = 0L

    fun available(): Int = (head - tail).toInt()

    fun push(value: Short) {
        if (head - tail >= capacity) return // full: the newest goes; the worker's trim keeps this rare
        data[(head % capacity).toInt()] = value
        head++
    }

    fun pop(): Short {
        val v = data[(tail % capacity).toInt()]
        tail++
        return v
    }

    fun drop(n: Int) {
        tail += n
    }
}

/**
 * Turns the PCM a Java audio thread hands over (any rate, 1 or 2 channels, native byte order) into 48 kHz mono frames
 * in a ring, with linear interpolation. Keeps the last input sample and the fractional position between calls, so the
 * output is continuous across buffers. No allocation per call.
 */
internal class ToMono48k {
    private var rate = 0
    private var last = 0f
    private var hasLast = false
    private var frac = 0.0

    fun feed(buffer: ByteBuffer, bytes: Int, channels: Int, sampleRate: Int, out: PcmRing) {
        if (bytes <= 0 || channels <= 0 || sampleRate <= 0) return
        if (sampleRate != rate) {
            rate = sampleRate
            frac = 0.0
            hasLast = false
        }
        val shorts = buffer.duplicate().order(ByteOrder.nativeOrder())
        shorts.position(0)
        val sb = shorts.asShortBuffer()
        val frames = bytes / 2 / channels
        val step = sampleRate.toDouble() / CallAudioRecorder.SAMPLE_RATE
        for (f in 0 until frames) {
            var sum = 0
            val base = f * channels
            for (c in 0 until channels) sum += sb.get(base + c).toInt()
            val next = (sum / channels).toFloat()
            if (!hasLast) {
                last = next
                hasLast = true
                out.push(next.toInt().toShort())
                frac = step
                continue
            }
            while (frac < 1.0) {
                val v = last + (next - last) * frac.toFloat()
                out.push(v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                frac += step
            }
            frac -= 1.0
            last = next
        }
    }
}
