package org.fenixuz.callrec

import android.content.Context
import android.media.MediaMetadataRetriever
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Novagram: where call recordings live. `files/call_recordings/` of the app (private: nothing indexes it, uninstall
 * removes it; "Save to device" copies a file out on request). A recording is written as a raw AAC stream into
 * `.partial/` and becomes "<Peer name> yyyy-MM-dd HH.mm[ N].m4a" in the folder once it is finished, so the folder only
 * ever holds playable files. A stream a killed app left in `.partial/` is finished on the next look ([recover]).
 */
object CallRecordingStorage {

    /** [peerAccount]/[peerId] are 0 when the recording predates them or was recovered without a call. */
    class Recording(val file: File, val peerName: String, val date: Long, val size: Long, val peerAccount: Int, val peerId: Long) {
        /** Milliseconds; measured once per file and cached. */
        val durationMs: Long get() = durationOf(file)
        val isVideo: Boolean get() = file.name.endsWith(".$VIDEO_EXTENSION")
        val mimeType: String get() = if (isVideo) "video/mp4" else "audio/mp4"
    }

    const val EXTENSION = "m4a"
    const val VIDEO_EXTENSION = "mp4"
    private const val FOLDER = "call_recordings"
    private const val PARTIAL = ".partial"
    private const val DATE_FORMAT = "yyyy-MM-dd HH.mm"
    private const val DURATIONS_PREF = "call_rec_durations"
    private const val PEERS_PREF = "call_rec_peers"
    private val stampPattern = Regex(" (\\d{4}-\\d{2}-\\d{2}) (\\d{2})\\.(\\d{2})( \\d+)?$")

    val directory: File
        get() = File(ApplicationLoader.getFilesDirFixed(), FOLDER).also { it.mkdirs() }

    val partialDirectory: File
        get() = File(directory, PARTIAL).also { it.mkdirs() }

    /** A fresh partial file for a recording in progress: the audio stream (`aac`) or the video container (`mp4`). */
    fun newPartialFile(extension: String): File = File(partialDirectory, "${System.currentTimeMillis()}-${(Math.random() * 1e6).toInt()}.$extension")

    /** A free final name for a recording of [peerName] made at [date], with [extension]. */
    fun finalFile(peerName: String, date: Long, extension: String = EXTENSION): File {
        val name = sanitize(peerName).ifEmpty { "Call" }
        val stamp = SimpleDateFormat(DATE_FORMAT, Locale.US).format(Date(date))
        var candidate = File(directory, "$name $stamp.$extension")
        var n = 2
        while (candidate.exists()) {
            candidate = File(directory, "$name $stamp $n.$extension")
            n++
        }
        return candidate
    }

    private fun isRecordingFile(f: File): Boolean =
        f.isFile && (f.name.endsWith(".$EXTENSION") || f.name.endsWith(".$VIDEO_EXTENSION"))

    /** Characters a file name cannot hold, and control characters. */
    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").trim().take(60)

    /** Every recording, newest first. Off the UI thread: it stats every file. */
    fun list(): List<Recording> {
        val files = directory.listFiles { f -> isRecordingFile(f) } ?: return emptyList()
        val peers = peerPrefs().all
        return files.map { f ->
            val base = f.name.substringBeforeLast('.')
            val m = stampPattern.find(base)
            val peer = if (m != null) base.substring(0, m.range.first).trim().ifEmpty { base } else base
            val date = if (m != null) parseStamp(m.groupValues[1], m.groupValues[2], m.groupValues[3]) ?: f.lastModified() else f.lastModified()
            // "account:userId", written when the recording was finished.
            val ids = (peers[f.name] as? String)?.split(':')
            val peerAccount = ids?.getOrNull(0)?.toIntOrNull() ?: 0
            val peerId = ids?.getOrNull(1)?.toLongOrNull() ?: 0L
            Recording(f, peer, date, f.length(), peerAccount, peerId)
        }.sortedByDescending { it.date }
    }

    private fun parseStamp(day: String, hour: String, minute: String): Long? = try {
        SimpleDateFormat(DATE_FORMAT, Locale.US).parse("$day $hour.$minute")?.time
    } catch (e: Exception) {
        null
    }

    fun totalSize(): Long = list().sumOf { it.size }

    /**
     * Deletes the recordings older than the "keep recordings" setting. Off the UI thread; run wherever the list is about
     * to be read (before a recording starts, when the list or the settings open), so the limit holds without a job of
     * its own. Returns how many were removed.
     */
    fun prune(): Int {
        val days = CallRecordingSettings.keepDays
        if (days <= 0) return 0
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        val old = list().filter { it.date < cutoff }
        old.forEach { delete(it) }
        if (old.isNotEmpty()) FileLog.d("CallRecording: pruned ${old.size} recordings older than $days days")
        return old.size
    }

    fun count(): Int = directory.listFiles { f -> isRecordingFile(f) }?.size ?: 0

    fun delete(recording: Recording): Boolean {
        forgetDuration(recording.file)
        peerPrefs().edit().remove(recording.file.name).apply()
        return recording.file.delete()
    }

    // ---- who the call was with: the list shows their profile photo ----

    private fun peerPrefs() = ApplicationLoader.applicationContext.getSharedPreferences(PEERS_PREF, Context.MODE_PRIVATE)

    /** Ties [file] to the user it was recorded with, so the list can show the profile photo rather than initials. */
    fun rememberPeer(file: File, account: Int, userId: Long) {
        if (userId == 0L) return
        peerPrefs().edit().putString(file.name, "$account:$userId").apply()
    }

    fun deleteAll() {
        list().forEach { delete(it) }
    }

    /**
     * Finishes the streams a killed app left behind: each `.aac` in the partial folder becomes a recording named after
     * its time.
     * Off the UI thread. Never while a recording is being written (the controller calls it before a start and the
     * list calls it while nothing records).
     */
    fun recover() {
        // A video container a killed app left open is unreadable (MediaMuxer finalises only on stop); the audio
        // stream of the same call survives on its own and is kept below.
        partialDirectory.listFiles { f -> f.isFile && f.name.endsWith(".$VIDEO_EXTENSION") }?.forEach { mp4 ->
            if (!CallAudioRecorder.isWriting(mp4)) mp4.delete()
        }
        val leftovers = partialDirectory.listFiles { f -> f.isFile && f.name.endsWith(".aac") } ?: return
        for (aac in leftovers) {
            if (CallAudioRecorder.isWriting(aac)) continue
            try {
                val target = finalFile("Call", aac.lastModified())
                if (aac.length() > 0 && AacRemux.toM4a(aac, target)) {
                    FileLog.d("CallRecording: recovered ${target.name}")
                }
            } catch (e: Exception) {
                FileLog.e(e)
            }
            aac.delete()
        }
    }

    // ---- durations: measured once per file, kept in a small preferences map ----

    private val durationCache = HashMap<String, Long>()

    private fun durationPrefs() = ApplicationLoader.applicationContext.getSharedPreferences(DURATIONS_PREF, Context.MODE_PRIVATE)

    fun rememberDuration(file: File, ms: Long) {
        synchronized(durationCache) { durationCache[file.name] = ms }
        durationPrefs().edit().putLong(file.name, ms).apply()
    }

    private fun forgetDuration(file: File) {
        synchronized(durationCache) { durationCache.remove(file.name) }
        durationPrefs().edit().remove(file.name).apply()
    }

    /** Off the UI thread for a file seen for the first time (it is then read with MediaMetadataRetriever). */
    fun durationOf(file: File): Long {
        synchronized(durationCache) { durationCache[file.name]?.let { return it } }
        val stored = durationPrefs().getLong(file.name, -1L)
        if (stored >= 0) {
            synchronized(durationCache) { durationCache[file.name] = stored }
            return stored
        }
        val measured = try {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.absolutePath)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                r.release()
            }
        } catch (e: Exception) {
            0L
        }
        rememberDuration(file, measured)
        return measured
    }
}
