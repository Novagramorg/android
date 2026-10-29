package org.fenixuz.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Novagram voice changer in calls: changes the microphone's 10 ms buffers in place on WebRtcAudioRecord's thread, before
 * WebRTC encodes them, for one-to-one and group calls alike. WebRTC needs every buffer back at its own size, but an
 * effect hands back what it has rather than what it was given (the pitch shifters hold some back), so the voice passes
 * through a small queue: it starts once one buffer more than needed is in it, and from then on a buffer out matches a
 * buffer in. The effect follows [VoiceChanger.calls], so a choice made during the call, of an effect or of its
 * settings, applies at once.
 * One per recording; used from one thread.
 */
class CallVoiceChanger(private val sampleRate: Int) {

    private var effect: VoiceEffect? = null
    private var effectPreset: VoicePreset? = null
    private var input = ShortArray(0)
    private var queue = ShortArray(sampleRate / 2)
    private var queued = 0
    private var started = false

    /** Changes the first [bytes] bytes of [buffer] (16-bit mono PCM, native byte order) in place. */
    fun process(buffer: ByteBuffer, bytes: Int) {
        // A new choice is a new object, so comparing the reference is enough (and cheap, every 10 ms).
        val preset = VoiceChanger.calls.preset
        if (preset !== effectPreset) {
            effectPreset = preset
            effect = if (preset.id == VoicePreset.OFF) null else VoiceEffect(preset, sampleRate)
            queued = 0
            started = false
        }
        val effect = effect ?: return
        val count = bytes / 2
        if (input.size != count) {
            input = ShortArray(count)
        }
        val samples = buffer.duplicate().order(ByteOrder.nativeOrder())
        samples.position(0)
        samples.asShortBuffer().get(input, 0, count)
        enqueue(effect.process(input, count))

        // Silence until the queue holds a buffer to spare; then a buffer out for every buffer in. Should the effect
        // ever fall behind, the gap is silent, and should it run ahead, the oldest samples go rather than the delay
        // growing.
        if (!started && queued >= count * 2) {
            started = true
        }
        if (queued > count * 6) {
            drop(queued - count * 2)
        }
        val out = samples.asShortBuffer()
        if (!started) {
            for (i in 0 until count) out.put(i, 0)
            return
        }
        val take = minOf(count, queued)
        for (i in 0 until take) out.put(i, queue[i])
        for (i in take until count) out.put(i, 0)
        drop(take)
    }

    private fun enqueue(samples: ShortArray) {
        if (queued + samples.size > queue.size) {
            queue = queue.copyOf(maxOf(queue.size * 2, queued + samples.size))
        }
        System.arraycopy(samples, 0, queue, queued, samples.size)
        queued += samples.size
    }

    private fun drop(count: Int) {
        System.arraycopy(queue, count, queue, 0, queued - count)
        queued -= count
    }
}
