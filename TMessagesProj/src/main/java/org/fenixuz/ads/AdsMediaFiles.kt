package org.fenixuz.ads

import android.media.MediaMetadataRetriever
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DownloadController
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.ImageLoader
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Novagram: our channel ad's video, made a Telegram video document so Telegram's own code shows it exactly as it shows
 * its own sponsored videos — muted autoplay in the bubble, the fullscreen viewer with sound, its caption, button and menu.
 *
 * Telegram fetches its documents over MTProto; ours is an https file on the ads backend. So the file is downloaded first
 * and stored where Telegram looks for a downloaded video (the video folder, under the document's own file name), and the
 * document is only handed over once that file is complete: Telegram then finds it present and never asks its servers for
 * it. The download follows the user's auto-download setting for videos on the current network, like any channel video;
 * when it is not allowed or fails, the ad shows without its media (the backend permits that).
 *
 * The document's id is negative and derived from the file's URL (the backend never changes a file at a URL), so it can't
 * meet a real Telegram file and the same video is downloaded once.
 */
object AdsMediaFiles {

    // The backend allows up to 20 MB; anything clearly above is not ours to download.
    private const val MAX_VIDEO_BYTES = 25L * 1024 * 1024
    // Only names the local file (dc_id + "_" + id); no request is ever made for it.
    private const val DC_ID = 2
    private const val FILE_NAME = "ad.mp4"

    private val downloadClient by lazy {
        AdsRetrofitClient.mediaClient.newBuilder()
            .callTimeout(90, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    // URL → the finished media, and URL → callers waiting for a download in progress. UI thread only.
    private val ready = HashMap<String, TLRPC.MessageMedia>()
    private val waiting = HashMap<String, ArrayList<(TLRPC.MessageMedia?) -> Unit>>()

    /**
     * [callback] (UI thread) gets the ad's video as a message media ready for MessageObject.sponsoredMedia, or null when
     * the ad has no video or it can't be shown. Synchronous for ads without a video and for videos already prepared.
     */
    fun prepare(account: Int, ad: ServedAd, callback: (TLRPC.MessageMedia?) -> Unit) {
        val media = ad.media
        val url = media?.url
        if (!"VIDEO".equals(media?.type, ignoreCase = true) || url == null || !url.startsWith("https://")) {
            callback(null)
            return
        }
        ready[url]?.let { prepared ->
            if (fileOf(prepared).exists()) {
                callback(prepared)
                return
            }
            ready.remove(url)
        }
        waiting[url]?.let {
            it.add(callback)
            return
        }
        waiting[url] = arrayListOf(callback)
        val document = document(url, media)
        val file = File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_VIDEO), FileLoader.getAttachFileName(document))
        if (file.exists() && file.length() > 0) {
            // Downloaded by an earlier run of the app: only the size and the preview frame are missing.
            Utilities.globalQueue.postRunnable { finish(url, document, file) }
            return
        }
        download(account, url, document, file)
    }

    private fun download(account: Int, url: String, document: TLRPC.Document, file: File) {
        downloadClient.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = response.body()
                    val size = body?.contentLength() ?: -1L
                    // The same rule as Telegram's own videos in a chat: the size must be known and allowed for this network.
                    val allowed = response.isSuccessful && body != null && size in 1..MAX_VIDEO_BYTES &&
                        DownloadController.getInstance(account).canDownloadMedia(DownloadController.AUTODOWNLOAD_TYPE_VIDEO, size)
                    if (!allowed) {
                        FileLog.d("Novagram ads: ad video not downloaded (HTTP ${response.code()}, $size bytes)")
                        deliver(url, null)
                        return
                    }
                    val temp = File(file.parentFile, file.name + ".part")
                    try {
                        file.parentFile?.mkdirs()
                        temp.outputStream().use { out -> body!!.byteStream().copyTo(out) }
                        if (temp.length() != size || !temp.renameTo(file)) throw IOException("incomplete")
                    } catch (e: Exception) {
                        temp.delete()
                        FileLog.d("Novagram ads: ad video download failed: ${e.javaClass.simpleName}")
                        deliver(url, null)
                        return
                    }
                }
                finish(url, document, file)
            }

            override fun onFailure(call: Call, e: IOException) {
                FileLog.d("Novagram ads: ad video download failed: ${e.javaClass.simpleName}")
                deliver(url, null)
            }
        })
    }

    // Background thread: completes the document with the file's size and a preview frame, then hands it over.
    private fun finish(url: String, document: TLRPC.Document, file: File) {
        document.size = file.length()
        thumb(file)?.let { document.thumbs.add(it) }
        val media = TLRPC.TL_messageMediaDocument()
        media.document = document
        media.flags = media.flags or 1
        AndroidUtilities.runOnUIThread {
            ready[url] = media
            deliver(url, media)
        }
    }

    private fun deliver(url: String, media: TLRPC.MessageMedia?) {
        AndroidUtilities.runOnUIThread {
            waiting.remove(url)?.forEach { it(media) }
        }
    }

    // The first frame, saved the way Telegram saves the preview of a video it sends (a local cached photo size).
    private fun thumb(file: File): TLRPC.PhotoSize? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.getFrameAtTime(0) ?: return null
            ImageLoader.scaleAndSaveImage(frame, 320f, 320f, 70, true).also { frame.recycle() }
        } catch (e: Exception) {
            FileLog.d("Novagram ads: ad video frame failed: ${e.javaClass.simpleName}")
            null
        } finally {
            try {
                retriever.release()
            } catch (ignore: Exception) {
            }
        }
    }

    private fun document(url: String, media: AdMedia): TLRPC.Document {
        val document = TLRPC.TL_document()
        document.id = idOf(url)
        document.access_hash = 0
        document.dc_id = DC_ID
        document.date = (System.currentTimeMillis() / 1000).toInt()
        document.mime_type = "video/mp4"
        document.file_reference = ByteArray(0)
        val video = TLRPC.TL_documentAttributeVideo()
        video.supports_streaming = true
        video.w = media.width ?: 1280
        video.h = media.height ?: 720
        video.duration = media.duration?.toDoubleOrNull() ?: 0.0
        document.attributes.add(video)
        val name = TLRPC.TL_documentAttributeFilename()
        name.file_name = FILE_NAME
        document.attributes.add(name)
        return document
    }

    /**
     * The local file of [document] when it is one of our ad videos, else null. FileLoader asks this before loading any
     * document: ours are served from this file and never requested from Telegram's servers (they are not Telegram files).
     * Telegram's own documents always carry an access hash, ours never do — no state is needed to tell them apart.
     */
    @JvmStatic
    fun localFile(document: TLRPC.Document?): File? {
        if (document == null || document.access_hash != 0L || document.dc_id != DC_ID || document.id >= 0) return null
        if (document.attributes.none { it is TLRPC.TL_documentAttributeFilename && it.file_name == FILE_NAME }) return null
        return File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_VIDEO), FileLoader.getAttachFileName(document))
    }

    private fun fileOf(media: TLRPC.MessageMedia): File =
        File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_VIDEO), FileLoader.getAttachFileName(media.document))

    // Negative, never 0, the same for the same URL.
    private fun idOf(url: String): Long {
        val digest = MessageDigest.getInstance("MD5").digest(url.toByteArray(Charsets.UTF_8))
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (digest[i].toLong() and 0xff)
        return -((value and Long.MAX_VALUE).coerceAtLeast(1L))
    }
}
