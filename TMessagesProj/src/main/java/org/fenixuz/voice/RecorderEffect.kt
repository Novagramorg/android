package org.fenixuz.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Novagram voice changer in Telegram's audio recorder V2 (org.telegram.utils.recorder.audio.AudioCapture), one per
 * recorded segment, as MediaController's older recorder makes one per segment too.
 *
 * AudioCapture's encoder thread hands it each PCM frame after RNNoise (noise suppression would take a robot or whispered
 * voice, or the Noise preset's hiss, for noise to remove). The effect's output, sized as the effect likes, is cut back
 * into full frames for the Opus encoder; the one short frame, if any, is written last, after the effect's tail
 * ([finish]), since a short frame ends the stream. [outputSamples] is what the encoder got: the recording's real length,
 * which a tape speed changes, and which the recorder's duration and trimming must use instead of the microphone's count.
 *
 * Encoder thread only.
 */
class RecorderEffect private constructor(private val effect: VoiceEffect, frameSamples: Int) {

    /** Takes one frame: [bytes] bytes of [frame], from position 0 (the buffer is reused once this returns). */
    fun interface FrameSink {
        fun write(frame: ByteBuffer, bytes: Int)
    }

    private val out: ByteBuffer = ByteBuffer.allocateDirect(frameSamples * 2).order(ByteOrder.nativeOrder())
    private var input = ShortArray(frameSamples)

    /** Samples handed to [FrameSink]s so far. */
    var outputSamples = 0L
        private set

    /**
     * The message length [micSamples] of microphone input make, for the running timer: a tape speed plays them at its
     * rate. Any thread (the preset never changes).
     */
    fun lengthOf(micSamples: Long): Long =
        if (effect.preset.rate == 1f) micSamples else (micSamples / effect.preset.rate).toLong()

    /** Runs the first [samples] samples of [pcm] (native-order PCM16, read from position 0) through the effect. */
    fun push(pcm: ByteBuffer, samples: Int, sink: FrameSink) {
        if (input.size < samples) input = ShortArray(samples)
        pcm.position(0)
        pcm.asShortBuffer().get(input, 0, samples)
        put(effect.process(input, samples), sink)
    }

    /** The end of the segment: the effect's tail, then the short frame left over. */
    fun finish(sink: FrameSink) {
        put(effect.drain(), sink)
        if (out.position() > 0) emit(out.position(), sink)
    }

    private fun put(samples: ShortArray, sink: FrameSink) {
        for (s in samples) {
            out.putShort(s)
            if (!out.hasRemaining()) emit(out.capacity(), sink)
        }
    }

    private fun emit(bytes: Int, sink: FrameSink) {
        out.position(0)
        out.limit(bytes)
        sink.write(out, bytes)
        outputSamples += bytes / 2
        out.clear()
    }

    companion object {
        /** The effect chosen for voice messages now, or null when none is ([VoiceChanger.newEffect]). */
        @JvmStatic
        fun create(sampleRate: Int, frameSamples: Int): RecorderEffect? =
            VoiceChanger.newEffect(sampleRate)?.let { RecorderEffect(it, frameSamples) }
    }
}
