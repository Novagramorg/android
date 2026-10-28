package org.fenixuz.voice

import java.nio.ShortBuffer
import org.fenixuz.utils.LanguageCode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Novagram voice changer: one preset of the voice changer (the same 15 as Graph Messenger's, in its order). A preset is
 * a pitch shift or a tape-speed change, plus a few simple stages applied after it; every value is fixed, so a preset
 * always sounds the same. [gain] brings each preset back to about the loudness of the voice it was given.
 */
class VoicePreset(
    /** Stored in the prefs, so never renumber. */
    val id: Int,
    /** [org.fenixuz.utils.LanguageCode] id of the name. */
    val titleId: Int,
    /** Shown before the name in the picker, as in Graph. */
    val emoji: String,
    val semitones: Float = 0f,
    /**
     * Where the formants (the resonances that make a voice sound like a man's, a woman's or a child's) end up, in
     * semitones, apart from the pitch ([FormantWarp]); null leaves them moving with the pitch, the cartoon voice.
     */
    val formant: Float? = null,
    /** Tape speed: above 1 the voice is faster and thinner, below 1 slower and thicker; the message's length changes. */
    val rate: Float = 1f,
    /** Robot: the voice rebuilt as a monotone buzz at [robotHz], keeping its words (spectral, phases dropped). */
    val robotHz: Float = 0f,
    /** Ring modulation: the voice times a sine of [ringHz]; [ringMix] 1 = fully modulated. */
    val ringHz: Float = 0f,
    val ringMix: Float = 0f,
    /** Feedback echo; every repeat is a little duller, as in a real space. */
    val echoMs: Float = 0f,
    val echoFeedback: Float = 0f,
    val echoMix: Float = 0f,
    /** Freeverb reverberation: [reverbMix] wet level, [reverbRoom] 0..1 size, [reverbDamp] 0..1 high-frequency loss. */
    val reverbMix: Float = 0f,
    val reverbRoom: Float = 0.5f,
    val reverbDamp: Float = 0.5f,
    val reverbPreDelayMs: Float = 0f,
    /** Vibrato: a delay line swept by a sine. */
    val vibratoHz: Float = 0f,
    val vibratoDepthMs: Float = 0f,
    /** Soft clipping, 0 = none. */
    val drive: Float = 0f,
    /** Hoarseness: [whisper] mixes in the voice turned to breath (spectral, phases randomized), [roughness] a rough flutter. */
    val whisper: Float = 0f,
    val roughness: Float = 0f,
    /** Constant soft background noise. */
    val noise: Float = 0f,
    /** Output level, measured per preset so that switching presets doesn't make the voice jump in loudness. */
    val gain: Float = 1f,
) {
    companion object {
        const val OFF = 0

        /** The picker's "Record speed" row: its four speeds are presets of their own (ids 16..19, names 484..487). */
        const val SPEED_ROW = -1

        private const val SPEED_EMOJI = "\u23E9"

        /** The four tape speeds, thickest first. */
        @JvmField
        val SPEEDS = listOf(
            VoicePreset(16, 484, SPEED_EMOJI, rate = 0.7f),
            VoicePreset(17, 485, SPEED_EMOJI, rate = 0.85f),
            VoicePreset(18, 486, SPEED_EMOJI, rate = 1.25f),
            VoicePreset(19, 487, SPEED_EMOJI, rate = 1.5f),
        )

        /** The picker's rows in Graph's order; [SPEED_ROW] stands for [SPEEDS]. Ids are stable; names are LanguageCode. */
        @JvmField
        val ROWS = listOf(
            VoicePreset(OFF, 468, "\u274C"),
            VoicePreset(SPEED_ROW, 479, SPEED_EMOJI),
            // robotic
            VoicePreset(8, 476, "\uD83E\uDD16", robotHz = 120f, gain = 1.15f),
            // alien
            VoicePreset(9, 477, "\uD83D\uDC7D", semitones = 4f, ringHz = 140f, ringMix = 0.6f, gain = 1.6f),
            // hoarseness
            VoicePreset(13, 481, "\uD83D\uDDE3\uFE0F", whisper = 0.5f, roughness = 0.15f, drive = 1.3f),
            // modulation
            VoicePreset(14, 482, "\uD83C\uDF9B\uFE0F", vibratoHz = 6f, vibratoDepthMs = 2.5f),
            // child
            VoicePreset(1, 469, "\uD83E\uDDD2", semitones = 12f, formant = 5.5f, gain = 1.1f),
            // mouse
            VoicePreset(7, 475, "\uD83D\uDC01", semitones = 14f),
            // man
            VoicePreset(3, 471, "\uD83D\uDC68\u200D\uD83E\uDDB0", semitones = -6f, formant = -3f),
            // woman
            VoicePreset(2, 470, "\uD83D\uDC69\u200D\uD83E\uDDB0", semitones = 9f, formant = 3.5f, gain = 1.1f),
            // monster
            VoicePreset(6, 474, "\uD83D\uDC7A", semitones = -8f, formant = -5f, drive = 2.5f, ringHz = 30f, ringMix = 0.25f, gain = 0.54f),
            // echo
            VoicePreset(10, 478, "\uD83C\uDF99\uFE0F", echoMs = 300f, echoFeedback = 0.45f, echoMix = 0.5f, gain = 0.85f),
            // noise
            VoicePreset(15, 483, "\uD83D\uDCA5", noise = 0.02f),
            // helium: as in the real gas, mostly the formants go up (sound is faster in it), the pitch only a little
            VoicePreset(4, 472, "\uD83C\uDF88", semitones = 3f, formant = 9f, gain = 0.95f),
            // hexafluoride: the other way, a heavy gas
            VoicePreset(5, 473, "\uD83C\uDF88", semitones = -3f, formant = -7f, gain = 1.08f),
            // cave
            VoicePreset(12, 480, "\uD83D\uDD73\uFE0F", reverbMix = 0.3f, reverbRoom = 0.7f, reverbDamp = 0.3f, reverbPreDelayMs = 35f),
        )

        @JvmStatic
        fun byId(id: Int): VoicePreset =
            ROWS.firstOrNull { it.id == id && it.id != SPEED_ROW } ?: SPEEDS.firstOrNull { it.id == id } ?: ROWS[0]

        @JvmStatic
        fun isSpeed(id: Int): Boolean = SPEEDS.any { it.id == id }
    }
}

/**
 * Applies a [VoicePreset] to a stream of 16-bit mono PCM, chunk by chunk, as the voice is recorded. [process] may return
 * more or fewer samples than it was given, because the pitch shifter holds back about two pitch periods; [drain] returns
 * what is still held once the recording stops, with the echo or reverb dying away. Not thread-safe: one instance per
 * recording, used from one thread.
 */
class VoiceEffect(val preset: VoicePreset, private val sampleRate: Int) {

    // Sonic moves the formants along with the pitch, so the warp moves them only by what is left over.
    private val warp = preset.formant?.let { FormantWarp(2f.pow((it - preset.semitones) / 12f), sampleRate) }

    // The warp can raise peaks; it runs at half level and applyStages brings the level back.
    private val inputScale = if (warp != null) 1f / WARP_HEADROOM else 1f

    // What the warp gives for its first [FormantWarp.size] samples is the silence before the voice reached it.
    private var warpLagLeft = warp?.size ?: 0

    // Pitch keeps the length; tape speed resamples to sampleRate / rate, so the same samples play faster or slower.
    private val sonic: Sonic? = if (preset.semitones != 0f || preset.rate != 1f) {
        Sonic(sampleRate, 1, 1f, 2f.pow(preset.semitones / 12f), (sampleRate / preset.rate).roundToInt())
    } else {
        null
    }

    private val ringStep = 2.0 * PI * preset.ringHz / sampleRate
    private var ringPhase = 0.0

    private val echo = if (preset.echoMs > 0f) FloatArray(msToSamples(preset.echoMs)) else null
    private var echoPos = 0
    private var echoDamp = 0f

    private val reverb = if (preset.reverbMix > 0f) Freeverb(sampleRate, preset) else null

    private val spectral = when {
        preset.robotHz > 0f -> Spectral(robot = true, size = 1024, hop = (sampleRate / preset.robotHz).roundToInt())
        preset.whisper > 0f -> Spectral(robot = false, size = 512, hop = 128)
        else -> null
    }

    /** What [drain] adds after the voice: the spectral stage's lag, then a second for an echo or a reverb to fade. */
    private val spectralLag = spectral?.size ?: 0
    private val fadeLength = if (echo != null || reverb != null) sampleRate else 0

    private val vibrato = if (preset.vibratoHz > 0f) FloatArray(msToSamples(preset.vibratoDepthMs * 2f + 10f)) else null
    private var vibratoPos = 0
    private val vibratoStep = 2.0 * PI * preset.vibratoHz / sampleRate
    private var vibratoPhase = 0.0

    private var roughPhase = 0.0
    private var noiseLow = 0f
    private var noiseSeed = 0x2545F491

    private fun msToSamples(ms: Float): Int = max(1, (sampleRate * ms / 1000f).toInt())

    fun process(input: ShortArray, count: Int): ShortArray {
        val source = if (warp != null) warped(input, count) else input.copyOf(count)
        val pitched = if (sonic != null) {
            sonic.queueInput(ShortBuffer.wrap(source))
            takeSonicOutput(sonic)
        } else {
            source
        }
        applyStages(pitched)
        return pitched
    }

    fun drain(): ShortArray {
        // Silence through the warp first: it still holds the last of the voice.
        val warpRest = if (warp != null) warped(ShortArray(warp.size), warp.size) else ShortArray(0)
        val rest = if (sonic != null) {
            sonic.queueInput(ShortBuffer.wrap(warpRest))
            sonic.queueEndOfStream()
            takeSonicOutput(sonic)
        } else {
            warpRest
        }
        // Silence pushed through the stages brings out what they still hold; the echo or reverb part fades to nothing.
        val out = rest.copyOf(rest.size + spectralLag + fadeLength)
        applyStages(out)
        val fadeStart = rest.size + spectralLag
        for (i in 0 until fadeLength) {
            out[fadeStart + i] = (out[fadeStart + i] * (1f - i.toFloat() / fadeLength)).toInt().toShort()
        }
        return out
    }

    private fun takeSonicOutput(sonic: Sonic): ShortArray {
        val out = ShortArray(sonic.outputSize / 2)
        if (out.isNotEmpty()) {
            sonic.getOutput(ShortBuffer.wrap(out))
        }
        return out
    }

    private fun applyStages(samples: ShortArray) {
        for (i in samples.indices) {
            var x = samples[i] / 32768f * inputScale

            if (spectral != null) {
                val wet = spectral.process(x)
                x = if (preset.robotHz > 0f) wet else spectral.dryDelayed * (1f - preset.whisper) + wet * preset.whisper
            }
            if (preset.ringMix > 0f) {
                val ring = x * sin(ringPhase).toFloat()
                x = x * (1f - preset.ringMix) + ring * preset.ringMix
                ringPhase += ringStep
                if (ringPhase > 2.0 * PI) ringPhase -= 2.0 * PI
            }
            if (vibrato != null) {
                vibrato[vibratoPos] = x
                val center = msToSamples(preset.vibratoDepthMs + 5f).toFloat()
                val offset = center + sin(vibratoPhase).toFloat() * msToSamples(preset.vibratoDepthMs)
                x = readFractional(vibrato, vibratoPos, offset)
                vibratoPos = (vibratoPos + 1) % vibrato.size
                vibratoPhase += vibratoStep
                if (vibratoPhase > 2.0 * PI) vibratoPhase -= 2.0 * PI
            }
            if (preset.roughness > 0f) {
                // Roughness: an irregular 30..50 Hz flutter of the loudness, like a strained throat.
                roughPhase += 2.0 * PI * (40.0 + 10.0 * nextNoise()) / sampleRate
                if (roughPhase > 2.0 * PI) roughPhase -= 2.0 * PI
                x *= 1f - preset.roughness * (0.5f + 0.5f * sin(roughPhase).toFloat())
            }
            if (preset.drive > 0f) {
                x = (tanh((x * preset.drive).toDouble()) / tanh(preset.drive.toDouble())).toFloat()
            }
            if (echo != null) {
                // A one-pole low-pass in the loop: each repeat loses some treble, like a wall far away.
                val past = echo[echoPos]
                echoDamp += (past - echoDamp) * 0.6f
                echo[echoPos] = x + echoDamp * preset.echoFeedback
                echoPos = (echoPos + 1) % echo.size
                x += echoDamp * preset.echoMix
            }
            if (reverb != null) {
                x = x * (1f - preset.reverbMix * 0.5f) + reverb.process(x) * preset.reverbMix
            }
            if (preset.noise > 0f) {
                // Softened (low-passed) noise, a hiss rather than a hard white noise.
                noiseLow += (nextNoise() - noiseLow) * 0.35f
                x += noiseLow * preset.noise * 2f
            }

            x *= preset.gain
            samples[i] = (softLimit(x) * 32767f).toInt().toShort()
        }
    }

    /** Linear up to 0.9, then bends smoothly toward 1, so loud peaks never clip hard. */
    private fun softLimit(x: Float): Float {
        val a = abs(x)
        if (a <= 0.9f) return x
        val bent = 0.9f + 0.1f * tanh(((a - 0.9f) / 0.1f).toDouble()).toFloat()
        return if (x < 0) -bent else bent
    }

    /** Linear interpolation [offset] samples back from [writePos] in a circular buffer. */
    private fun readFractional(line: FloatArray, writePos: Int, offset: Float): Float {
        val back = offset.coerceIn(1f, line.size - 2f)
        val whole = back.toInt()
        val frac = back - whole
        val a = line[(writePos - whole + line.size) % line.size]
        val b = line[(writePos - whole - 1 + line.size) % line.size]
        return a + (b - a) * frac
    }

    private fun nextNoise(): Float {
        noiseSeed = xorshift(noiseSeed)
        return noiseSeed / 2147483648f
    }

    /** [count] samples of [input] through the warp, without its lag at the start. */
    private fun warped(input: ShortArray, count: Int): ShortArray {
        val warp = warp!!
        val skip = min(warpLagLeft, count)
        val out = ShortArray(count - skip)
        for (i in 0 until count) {
            val y = warp.process(input[i] / 32768f) * WARP_HEADROOM
            if (i >= skip) {
                out[i - skip] = (y * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
            }
        }
        warpLagLeft -= skip
        return out
    }

    private companion object {
        const val WARP_HEADROOM = 0.5f
    }
}

/**
 * Moves the formants (the resonances of the throat and mouth) by [factor] along the frequency axis and leaves the pitch
 * where it is. In each STFT frame the spectral envelope is the log spectrum smoothed through the cepstrum: only the
 * quefrencies below [LIFTER_SECONDS] stay, shorter than the pitch period of any speaking voice, so the harmonics don't
 * count and no pitch detection is needed. The frame is then filtered by the warped envelope over its own envelope.
 * 75 % overlapping Hann windows; the output lags the input by [size] samples.
 */
private class FormantWarp(private val factor: Float, sampleRate: Int) {
    val size = if (sampleRate > 32000) 2048 else 1024
    private val hop = size / 4
    private val half = size / 2
    private val lifter = max(8, (sampleRate * LIFTER_SECONDS).roundToInt())
    private val window = FloatArray(size) { (0.5 - 0.5 * cos(2.0 * PI * it / size)).toFloat() }
    private val input = FloatArray(size)
    private val output = FloatArray(size)
    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private val cepstrumRe = FloatArray(size)
    private val cepstrumIm = FloatArray(size)
    private val envelope = FloatArray(half + 1)
    private val fft = Fft(size)
    private var pos = 0
    private var sinceFrame = 0

    // Hann analysis and synthesis at 75 % overlap add up to 1.5.
    private val scale = hop / (0.375f * size)

    fun process(x: Float): Float {
        input[pos] = x
        val y = output[pos]
        output[pos] = 0f
        pos = (pos + 1) % size
        if (++sinceFrame == hop) {
            sinceFrame = 0
            frame()
        }
        return y
    }

    private fun frame() {
        for (i in 0 until size) {
            re[i] = input[(pos + i) % size] * window[i]
            im[i] = 0f
        }
        fft.transform(re, im, false)
        // The log magnitude, an even real sequence, through the inverse transform: the cepstrum.
        for (k in 0..half) {
            val level = ln(hypot(re[k], im[k]) + 1e-9f)
            cepstrumRe[k] = level
            cepstrumIm[k] = 0f
            if (k in 1 until half) {
                cepstrumRe[size - k] = level
                cepstrumIm[size - k] = 0f
            }
        }
        fft.transform(cepstrumRe, cepstrumIm, true)
        for (n in 0 until size) {
            val quefrency = if (n <= half) n else size - n
            cepstrumRe[n] *= when {
                quefrency < lifter -> 1f
                quefrency < lifter + LIFTER_TAPER -> (0.5 + 0.5 * cos(PI * (quefrency - lifter) / LIFTER_TAPER)).toFloat()
                else -> 0f
            }
            cepstrumIm[n] = 0f
        }
        fft.transform(cepstrumRe, cepstrumIm, false)
        for (k in 0..half) {
            envelope[k] = cepstrumRe[k]
        }
        for (k in 0..half) {
            // The envelope [factor] times lower down lands here: above 1 the formants go up.
            val from = k / factor
            val below = from.toInt()
            val target = if (below >= half) envelope[half] else envelope[below] + (envelope[below + 1] - envelope[below]) * (from - below)
            // Limited, so that a quiet valley or the noise between words isn't blown up.
            val gain = exp((target - envelope[k]).coerceIn(-MAX_LOG_GAIN, MAX_LOG_GAIN))
            re[k] *= gain
            im[k] *= gain
            if (k in 1 until half) {
                re[size - k] = re[k]
                im[size - k] = -im[k]
            }
        }
        fft.transform(re, im, true)
        for (i in 0 until size) {
            output[(pos + i) % size] += re[i] * window[i] * scale
        }
    }

    private companion object {
        /** 1.5 ms: half the pitch period of a 330 Hz voice, so even a high voice's harmonics stay out of the envelope. */
        const val LIFTER_SECONDS = 0.0015f
        const val LIFTER_TAPER = 16

        /** ln(8): at most 18 dB up or down. */
        const val MAX_LOG_GAIN = 2.0794415f
    }
}

/** The next xorshift state; as a signed Int over 2^31 it is white noise in -1..1 (quality is irrelevant, speed is not). */
private fun xorshift(seed: Int): Int {
    var s = seed
    s = s xor (s shl 13)
    s = s xor (s ushr 17)
    s = s xor (s shl 5)
    return s
}

/**
 * Short-time Fourier processing for the effects that rebuild the voice from its spectrum (DAFX, Zolzer, ch. 7.4): a
 * robot keeps each frame's spectrum but drops its phases, so every frame becomes one pulse and the voice takes the
 * monotone pitch of the frame rate; a whisper gives the phases random values, which turns the voice into breath that
 * keeps its vowels. Hann windows on both sides; the output lags the input by [size] samples, and [dryDelayed] is the
 * input with the same lag, for mixing.
 */
private class Spectral(private val robot: Boolean, val size: Int, private val hop: Int) {
    private val window = FloatArray(size) { (0.5 - 0.5 * cos(2.0 * PI * it / size)).toFloat() }
    private val input = FloatArray(size)
    private val output = FloatArray(size)
    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private val fft = Fft(size)
    private var pos = 0
    private var sinceFrame = 0
    private var seed = 0x6C8E9CF5

    // Keeps the level: overlapping frames add up in phase for the robot's pulses, at random for the whisper.
    private val scale = if (robot) hop / (0.375f * size) else sqrt(hop / (0.140625f * size))

    var dryDelayed = 0f
        private set

    fun process(x: Float): Float {
        dryDelayed = input[pos]
        input[pos] = x
        val y = output[pos]
        output[pos] = 0f
        pos = (pos + 1) % size
        if (++sinceFrame == hop) {
            sinceFrame = 0
            frame()
        }
        return y
    }

    /** Analyses the last [size] samples and adds the rebuilt frame to the output, starting with the next sample. */
    private fun frame() {
        for (i in 0 until size) {
            re[i] = input[(pos + i) % size] * window[i]
            im[i] = 0f
        }
        fft.transform(re, im, false)
        val half = size / 2
        re[0] = 0f
        im[0] = 0f
        for (k in 1..half) {
            val magnitude = hypot(re[k], im[k])
            if (robot) {
                // Zero phase, moved to the middle of the frame (the sign flip of odd bins) where the window is open.
                re[k] = if (k % 2 == 0) magnitude else -magnitude
                im[k] = 0f
            } else {
                seed = xorshift(seed)
                val angle = seed / 2147483648.0 * PI
                re[k] = magnitude * cos(angle).toFloat()
                im[k] = if (k == half) 0f else magnitude * sin(angle).toFloat()
            }
            if (k < half) {
                re[size - k] = re[k]
                im[size - k] = -im[k]
            }
        }
        fft.transform(re, im, true)
        for (i in 0 until size) {
            output[(pos + i) % size] += re[i] * window[i] * scale
        }
    }
}

/** In-place radix-2 complex FFT of a fixed power-of-two size; the inverse is scaled by 1/size. */
private class Fft(private val size: Int) {
    private val cosTable = FloatArray(size / 2) { cos(2.0 * PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { sin(2.0 * PI * it / size).toFloat() }
    private val reversed = IntArray(size).also {
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            it[i] = j
        }
    }

    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until size) {
            val j = reversed[i]
            if (i < j) {
                var t = re[i]
                re[i] = re[j]
                re[j] = t
                t = im[i]
                im[i] = im[j]
                im[j] = t
            }
        }
        var length = 2
        while (length <= size) {
            val half = length / 2
            val step = size / length
            var start = 0
            while (start < size) {
                for (k in 0 until half) {
                    val wr = cosTable[k * step]
                    val wi = if (inverse) sinTable[k * step] else -sinTable[k * step]
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                start += length
            }
            length = length shl 1
        }
        if (inverse) {
            val s = 1f / size
            for (i in 0 until size) {
                re[i] *= s
                im[i] *= s
            }
        }
    }
}

/**
 * Freeverb (Jezar at Dreampoint, public domain): eight damped feedback combs in parallel, then four allpasses in series.
 * The usual tunings, given for 44.1 kHz, are scaled to [sampleRate]. Mono, wet signal only.
 */
private class Freeverb(sampleRate: Int, preset: VoicePreset) {
    private val scale = sampleRate / 44100f
    private val combs = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617).map { FloatArray((it * scale).toInt()) }
    private val combPos = IntArray(combs.size)
    private val combStore = FloatArray(combs.size)
    private val allpasses = intArrayOf(556, 441, 341, 225).map { FloatArray((it * scale).toInt()) }
    private val allpassPos = IntArray(allpasses.size)
    private val feedback = preset.reverbRoom * 0.28f + 0.7f
    private val damp = preset.reverbDamp * 0.4f
    private val preDelay = if (preset.reverbPreDelayMs > 0f) FloatArray(max(1, (sampleRate * preset.reverbPreDelayMs / 1000f).toInt())) else null
    private var preDelayPos = 0

    // A one-pole high-pass at 200 Hz on what goes into the reverb, so low notes don't pile up into a boom.
    private val highPassCoef = exp(-2.0 * PI * 200.0 / sampleRate).toFloat()
    private var highPassOut = 0f
    private var highPassIn = 0f

    fun process(dry: Float): Float {
        highPassOut = highPassCoef * (highPassOut + dry - highPassIn)
        highPassIn = dry
        val x = highPassOut
        var input = x
        if (preDelay != null) {
            val delayed = preDelay[preDelayPos]
            preDelay[preDelayPos] = x
            preDelayPos = (preDelayPos + 1) % preDelay.size
            input = delayed
        }
        input *= 0.015f * 2f
        var out = 0f
        for (c in combs.indices) {
            val line = combs[c]
            val pos = combPos[c]
            val y = line[pos]
            combStore[c] = y * (1f - damp) + combStore[c] * damp
            line[pos] = input + combStore[c] * feedback
            combPos[c] = (pos + 1) % line.size
            out += y
        }
        for (a in allpasses.indices) {
            val line = allpasses[a]
            val pos = allpassPos[a]
            val buffered = line[pos]
            line[pos] = out + buffered * 0.5f
            allpassPos[a] = (pos + 1) % line.size
            out = buffered - out
        }
        return out * 3f
    }
}

/** The voice changer's state: the chosen preset (kept across recordings, like in Graph). */
object VoiceChanger {

    private const val PREFS = "novagram_voice_changer"
    private const val KEY_PRESET = "preset"
    private const val KEY_CALL_PRESET = "call_preset"

    private val prefs
        get() = org.telegram.messenger.ApplicationLoader.applicationContext
            .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    // Read from the prefs once: the recording button asks on every frame it draws.
    @Volatile
    private var current: VoicePreset? = null

    private fun current(): VoicePreset =
        current ?: VoicePreset.byId(prefs.getInt(KEY_PRESET, VoicePreset.OFF)).also { current = it }

    // Calls keep a choice of their own, as in Graph. A tape speed can't change a live voice, so it never is one.
    @Volatile
    private var currentCall: VoicePreset? = null

    private fun currentCall(): VoicePreset =
        currentCall ?: forCall(prefs.getInt(KEY_CALL_PRESET, VoicePreset.OFF)).also { currentCall = it }

    private fun forCall(id: Int): VoicePreset = if (VoicePreset.isSpeed(id)) VoicePreset.byId(VoicePreset.OFF) else VoicePreset.byId(id)

    /** The chosen preset's id; [VoicePreset.OFF] records the voice as it is, and so does an id no preset has any more. */
    @JvmStatic
    var presetId: Int
        get() = current().id
        set(value) {
            current = VoicePreset.byId(value)
            prefs.edit().putInt(KEY_PRESET, value).apply()
        }

    @JvmStatic
    val isOn: Boolean
        get() = current().id != VoicePreset.OFF

    /** The chosen effect's name, as the settings row shows it: a speed by its own name ("Thick"), none as "Off". */
    @JvmStatic
    fun currentTitle(): String = LanguageCode.getMyTitles(current().titleId)

    /** A new effect for one recording, or null when the voice changer is off. */
    @JvmStatic
    fun newEffect(sampleRate: Int): VoiceEffect? {
        val preset = current()
        return if (preset.id == VoicePreset.OFF) null else VoiceEffect(preset, sampleRate)
    }

    /** The preset for calls; read by the call's audio thread every 10 ms, so it is cached. */
    @JvmStatic
    var callPresetId: Int
        get() = currentCall().id
        set(value) {
            currentCall = forCall(value)
            prefs.edit().putInt(KEY_CALL_PRESET, value).apply()
        }

    @JvmStatic
    val isCallOn: Boolean
        get() = currentCall().id != VoicePreset.OFF

    @JvmStatic
    fun callTitle(): String = LanguageCode.getMyTitles(currentCall().titleId)

    /** A new effect for a call, or null when the voice changer is off for calls. */
    @JvmStatic
    fun newCallEffect(sampleRate: Int): VoiceEffect? {
        val preset = currentCall()
        return if (preset.id == VoicePreset.OFF) null else VoiceEffect(preset, sampleRate)
    }
}
