package org.fenixuz.callrec

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import org.telegram.messenger.FileLog
import java.io.File
import java.nio.ByteBuffer

/**
 * Novagram: wraps a raw AAC (ADTS) stream into an `.m4a` without touching the audio. The recorder writes ADTS while
 * the call runs because it stays readable after a crash; players and "Share" want an MP4 container with a duration
 * and seeking, which this adds at the end in a fraction of a second.
 */
object AacRemux {

    /** True when [target] is a complete file; false (and [target] removed) otherwise. Off the UI thread. */
    fun toM4a(source: File, target: File): Boolean {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var ok = false
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(source.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) return false
            extractor.selectTrack(track)
            muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val out = muxer.addTrack(format)
            muxer.start()
            val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 64 * 1024
            val buffer = ByteBuffer.allocate(maxOf(maxSize, 16 * 1024))
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(out, buffer, info)
                extractor.advance()
            }
            ok = true
        } catch (e: Exception) {
            FileLog.e(e)
            ok = false
        } finally {
            try {
                muxer?.stop()
            } catch (e: Exception) {
                ok = false
            }
            try {
                muxer?.release()
            } catch (ignored: Exception) {
            }
            try {
                extractor?.release()
            } catch (ignored: Exception) {
            }
        }
        if (!ok) target.delete()
        return ok && target.length() > 0
    }
}
