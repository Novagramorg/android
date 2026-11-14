package org.fenixuz.callrec

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.fenixuz.utils.LanguageCode
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaController
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCell
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.PhotoViewer
import java.io.File
import java.io.FileInputStream

/**
 * Novagram: a recording opened from the list. Plays it right here (a plain MediaPlayer on the file: no message object,
 * no upload), and offers what Android can do with a file that iOS cannot: besides Share, "Save to device" puts a copy
 * in the phone's Music folder where every player and file manager sees it, and "Send to Saved Messages" keeps it in
 * the user's own cloud chat. Delete asks first.
 */
object CallRecordingPlayerSheet {

    private const val MIME = "audio/mp4"
    private const val PUBLIC_FOLDER = "Novagram Call Recordings"

    fun show(fragment: BaseFragment, rec: CallRecordingStorage.Recording, onChanged: Runnable) {
        val context = fragment.context ?: return
        val rp = fragment.resourceProvider
        val content = LinearLayout(context)
        content.orientation = LinearLayout.VERTICAL

        val title = TextView(context)
        title.text = rec.peerName
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp))
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
        title.typeface = AndroidUtilities.bold()
        title.maxLines = 1
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22f, 12f, 22f, 2f))

        val subtitle = TextView(context)
        // "November 14, 15:09 · 68.8 KB": the day as the list's section header spells it, the time as the rows do.
        subtitle.text = LocaleController.formatDateChat(rec.date / 1000) + ", " + android.text.format.DateFormat.getTimeFormat(context).format(rec.date) + " · " + AndroidUtilities.formatFileSize(rec.size)
        subtitle.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, rp))
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        content.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22f, 0f, 22f, 12f))

        val player = PlayerView(context, rp, rec.file, rec.isVideo) {
            // Full screen: Telegram's own viewer (the one chat videos open in), with its player, seek bar and rotation.
            val entries = ArrayList<Any>()
            entries.add(MediaController.PhotoEntry(0, 0, 0, rec.file.absolutePath, 0, true, 0, 0, rec.size))
            PhotoViewer.getInstance().setParentActivity(fragment, rp)
            PhotoViewer.getInstance().openPhotoForSelect(entries, 0, PhotoViewer.SELECT_TYPE_NO_SELECT, false, PhotoViewer.EmptyPhotoViewerProvider(), null)
        }
        content.addView(player, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 10f, 0f, 10f, 8f))

        val builder = BottomSheet.Builder(context, false, rp)
        builder.setApplyTopPadding(false)
        builder.setApplyBottomPadding(false)
        builder.setCustomView(content)
        val sheet = builder.create()

        fun row(text: String, icon: Int, red: Boolean = false, onClick: () -> Unit) {
            val cell = TextCell(context, rp)
            cell.setTextAndIcon(text, icon, false)
            if (red) cell.setColors(Theme.key_text_RedRegular, Theme.key_text_RedRegular)
            cell.background = Theme.getSelectorDrawable(false)
            cell.setOnClickListener { onClick() }
            content.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48))
        }
        row(LanguageCode.getMyTitles(545), R.drawable.msg_share) { share(context, rec) }
        row(LanguageCode.getMyTitles(546), R.drawable.msg_download) { saveToDevice(context, rec) }
        row(LanguageCode.getMyTitles(547), R.drawable.msg_saved) {
            sendToSavedMessages(rec)
            sheet.dismiss()
        }
        row(LanguageCode.getMyTitles(548), R.drawable.msg_delete, red = true) {
            sheet.dismiss()
            confirmDelete(fragment, rec, onChanged)
        }
        content.addView(View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8))

        // Not setOnDismissListener: BaseFragment.showDialog installs its own and would replace ours, leaving the
        // MediaPlayer playing after the sheet is gone. The hide listener is the sheet's own hook.
        sheet.setOnHideListener { player.release() }
        fragment.showDialog(sheet)
    }

    fun confirmDelete(fragment: BaseFragment, rec: CallRecordingStorage.Recording, onChanged: Runnable) {
        val context = fragment.context ?: return
        val builder = AlertDialog.Builder(context, fragment.resourceProvider)
        builder.setTitle(LanguageCode.getMyTitles(548))
        builder.setMessage(LanguageCode.getMyTitles(550))
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null)
        builder.setPositiveButton(LanguageCode.getMyTitles(548)) { _, _ ->
            CallRecordingStorage.delete(rec)
            onChanged.run()
        }
        val dialog = builder.create()
        fragment.showDialog(dialog)
        dialog.redPositive()
    }

    // ---- actions ----

    private fun share(context: Context, rec: CallRecordingStorage.Recording) {
        val file = rec.file
        try {
            val uri = FileProvider.getUriForFile(context, ApplicationLoader.getApplicationId() + ".provider", file)
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = rec.mimeType
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, file.name))
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    /**
     * A copy into Music/Novagram Call Recordings (voice) or Movies/Novagram Call Recordings (video), through MediaStore
     * on Android 10+, so every player, gallery and file manager sees it at once.
     */
    private fun saveToDevice(context: Context, rec: CallRecordingStorage.Recording) {
        val file = rec.file
        val video = rec.isVideo
        Utilities.globalQueue.postRunnable {
            val ok = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues()
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    values.put(MediaStore.MediaColumns.MIME_TYPE, rec.mimeType)
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC) + "/" + PUBLIC_FOLDER)
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1)
                    val resolver = context.contentResolver
                    val uri = resolver.insert(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri == null) {
                        false
                    } else {
                        resolver.openOutputStream(uri).use { out ->
                            FileInputStream(file).use { it.copyTo(out!!) }
                        }
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                        true
                    }
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC), PUBLIC_FOLDER)
                    dir.mkdirs()
                    val target = File(dir, file.name)
                    file.copyTo(target, overwrite = true)
                    AndroidUtilities.addMediaToGallery(target.absolutePath)
                    true
                }
            } catch (e: Exception) {
                FileLog.e(e)
                false
            }
            AndroidUtilities.runOnUIThread {
                Toast.makeText(context, LanguageCode.getMyTitles(if (!ok) 573 else if (video) 566 else 552), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Telegram refuses to upload anything that lives under the app's private data directory (its guard against another
     * app's share intent smuggling the database out), and the recordings live there. What gets sent is a copy in the
     * sharing cache, the same place Telegram stages files it receives from other apps; the cache cleaner removes it later.
     */
    private fun sendToSavedMessages(rec: CallRecordingStorage.Recording) {
        val source = rec.file
        val account = UserConfig.selectedAccount
        val self = UserConfig.getInstance(account).getClientUserId()
        Utilities.globalQueue.postRunnable {
            val copy = try {
                val dir = AndroidUtilities.getSharingDirectory()
                dir.mkdirs()
                source.copyTo(File(dir, source.name), overwrite = true)
            } catch (e: Exception) {
                FileLog.e(e)
                null
            }
            AndroidUtilities.runOnUIThread {
                if (copy == null) {
                    Toast.makeText(ApplicationLoader.applicationContext, LanguageCode.getMyTitles(573), Toast.LENGTH_SHORT).show()
                    return@runOnUIThread
                }
                SendMessagesHelper.prepareSendingDocument(
                    AccountInstance.getInstance(account), copy.absolutePath, copy.absolutePath, null, null, rec.mimeType, self,
                    null, null, null, null, null, true, 0, null, null, false
                )
                Toast.makeText(ApplicationLoader.applicationContext, LanguageCode.getMyTitles(559), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---- the player ----

    /**
     * Play/pause, a seek bar and the two times; a video shows its picture above them and opens full screen on a tap.
     * The MediaPlayer is prepared asynchronously as soon as the sheet opens (the UI thread never waits on the decoder),
     * so the first picture of a video is already there and the seek bar works before Play is pressed; a seek made
     * before preparation finishes is applied when it does. Seeking in a video lands on the exact frame, not the
     * previous key frame several seconds back. Playback takes audio focus (Telegram's own voice player and other apps
     * pause) and gives it back; losing it pauses. Released with the sheet.
     */
    private class PlayerView(
        context: Context,
        rp: Theme.ResourcesProvider?,
        private val file: File,
        private val isVideo: Boolean,
        private val onFullScreen: () -> Unit,
    ) : FrameLayout(context) {
        private val button = PlayPauseButton(context, rp)
        private var textureView: android.view.TextureView? = null
        private var surface: android.view.Surface? = null
        private val controlsTop: Int get() = if (isVideo) dp(VIDEO_HEIGHT) else 0
        private val seek = SeekBar(context)
        private val elapsed = TextView(context)
        private val total = TextView(context)
        private var player: MediaPlayer? = null
        private var prepared = false
        private var playing = false
        private var playWhenPrepared = false
        private var pendingSeekMs = -1
        private var seeking = false
        private var released = false
        private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        private var focusRequest: AudioFocusRequest? = null
        private var hasFocus = false
        private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                hasFocus = false
                if (playing) pause()
            }
        }
        private val ticker = object : Runnable {
            override fun run() {
                val p = player ?: return
                if (prepared && !seeking) {
                    val pos = try { p.currentPosition } catch (e: Exception) { 0 }
                    seek.progress = pos
                    elapsed.text = AndroidUtilities.formatShortDuration(pos / 1000)
                }
                if (playing) AndroidUtilities.runOnUIThread(this, 250)
            }
        }

        init {
            val accent = Theme.getColor(Theme.key_featuredStickers_addButton, rp)
            val top = if (isVideo) VIDEO_HEIGHT else 0f
            if (isVideo) {
                // The portrait picture, centred on a black strip; the MediaPlayer draws into it once it exists.
                val tv = android.view.TextureView(context)
                tv.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                        surface = android.view.Surface(st)
                        player?.setSurface(surface)
                        // A prepared, paused player shows nothing until asked for a frame.
                        if (prepared && !playing) seekTo(seek.progress)
                    }
                    override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                        try {
                            player?.setSurface(null)
                        } catch (ignored: Exception) {
                        }
                        surface?.release()
                        surface = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                }
                val strip = FrameLayout(context)
                strip.setBackgroundColor(android.graphics.Color.BLACK)
                strip.addView(tv, LayoutHelper.createFrame(VIDEO_HEIGHT * 9f / 16f, VIDEO_HEIGHT, Gravity.CENTER))
                val expand = ExpandButton(context)
                strip.addView(expand, LayoutHelper.createFrame(36, 36f, Gravity.RIGHT or Gravity.TOP, 0f, 8f, 8f, 0f))
                strip.isClickable = true
                strip.setOnClickListener { openFullScreen() }
                expand.setOnClickListener { openFullScreen() }
                addView(strip, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT.toFloat(), VIDEO_HEIGHT, Gravity.TOP))
                textureView = tv
            }
            addView(button, LayoutHelper.createFrame(48, 48f, Gravity.LEFT or Gravity.TOP, 8f, top + 6f, 0f, 0f))
            seek.progressDrawable.colorFilter = PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN)
            seek.thumb.colorFilter = PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN)
            addView(seek, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP, 60f, top + 6f, 12f, 0f))
            for (t in listOf(elapsed, total)) {
                t.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, rp))
                t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            }
            addView(elapsed, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.LEFT or Gravity.TOP, 72f, top + 36f, 0f, 0f))
            addView(total, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.RIGHT or Gravity.TOP, 0f, top + 36f, 24f, 0f))
            // The cached duration gives the bar its range at once; the player corrects it when prepared.
            val knownMs = CallRecordingStorage.durationOf(file).toInt()
            seek.max = knownMs.coerceAtLeast(1)
            elapsed.text = AndroidUtilities.formatShortDuration(0)
            total.text = AndroidUtilities.formatShortDuration(knownMs / 1000)

            button.setOnClickListener { toggle() }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) elapsed.text = AndroidUtilities.formatShortDuration(progress / 1000)
                }
                override fun onStartTrackingTouch(bar: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    seeking = false
                    seekTo(bar.progress)
                }
            })
            preparePlayer()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(60f) + controlsTop, MeasureSpec.EXACTLY))
        }

        /** Whatever closed the sheet (back, swipe, the fragment going away), a view off screen plays nothing. */
        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            release()
        }

        private fun preparePlayer() {
            try {
                val p = MediaPlayer()
                p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                p.setDataSource(file.absolutePath)
                surface?.let { p.setSurface(it) }
                p.setOnPreparedListener {
                    if (released) return@setOnPreparedListener
                    prepared = true
                    val d = p.duration
                    if (d > 0) {
                        seek.max = d
                        total.text = AndroidUtilities.formatShortDuration(d / 1000)
                    }
                    if (pendingSeekMs >= 0) {
                        seekTo(pendingSeekMs)
                        pendingSeekMs = -1
                    } else if (isVideo) {
                        seekTo(0) // shows the first picture
                    }
                    if (playWhenPrepared) {
                        playWhenPrepared = false
                        play()
                    }
                }
                p.setOnCompletionListener {
                    playing = false
                    button.setPlaying(false)
                    AndroidUtilities.cancelRunOnUIThread(ticker)
                    seek.progress = 0
                    elapsed.text = AndroidUtilities.formatShortDuration(0)
                    abandonFocus()
                }
                p.setOnErrorListener { _, what, extra ->
                    FileLog.d("CallRecording: player error $what/$extra for ${file.name}")
                    playing = false
                    playWhenPrepared = false
                    button.setPlaying(false)
                    AndroidUtilities.cancelRunOnUIThread(ticker)
                    abandonFocus()
                    Toast.makeText(context, LanguageCode.getMyTitles(541), Toast.LENGTH_SHORT).show()
                    true
                }
                player = p
                p.prepareAsync()
            } catch (e: Exception) {
                FileLog.e(e)
                player = null
            }
        }

        private fun seekTo(ms: Int) {
            val p = player
            if (p == null || !prepared) {
                pendingSeekMs = ms
                return
            }
            try {
                if (isVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    p.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST)
                } else {
                    p.seekTo(ms)
                }
            } catch (e: Exception) {
                FileLog.e(e)
            }
        }

        private fun toggle() {
            if (playing) pause() else play()
        }

        private fun play() {
            val p = player ?: return
            if (!prepared) {
                playWhenPrepared = true
                button.setPlaying(true)
                return
            }
            if (!requestAudioFocus()) return
            try {
                p.start()
            } catch (e: Exception) {
                FileLog.e(e)
                return
            }
            playing = true
            button.setPlaying(true)
            AndroidUtilities.cancelRunOnUIThread(ticker)
            AndroidUtilities.runOnUIThread(ticker)
        }

        private fun pause() {
            playing = false
            playWhenPrepared = false
            button.setPlaying(false)
            AndroidUtilities.cancelRunOnUIThread(ticker)
            try {
                if (prepared) player?.pause()
            } catch (e: Exception) {
                FileLog.e(e)
            }
            abandonFocus()
        }

        private fun openFullScreen() {
            if (playing) pause()
            onFullScreen()
        }

        private fun requestAudioFocus(): Boolean {
            if (hasFocus) return true
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener(focusListener)
                    .build()
                focusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
            hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            return hasFocus
        }

        private fun abandonFocus() {
            if (!hasFocus) return
            hasFocus = false
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.abandonAudioFocus(focusListener)
                }
            } catch (ignored: Exception) {
            }
        }

        fun release() {
            if (released) return
            released = true
            playing = false
            playWhenPrepared = false
            AndroidUtilities.cancelRunOnUIThread(ticker)
            abandonFocus()
            val p = player
            player = null
            // The decoder's teardown can take a moment; it must not hold the sheet's closing animation.
            Utilities.globalQueue.postRunnable {
                try {
                    p?.release()
                } catch (ignored: Exception) {
                }
            }
            surface?.release()
            surface = null
        }

        companion object {
            private const val VIDEO_HEIGHT = 220f
        }
    }

    /** A round translucent button with a "full screen" mark: four corner brackets. */
    private class ExpandButton(context: Context) : View(context) {
        private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dp(1.6f).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val path = Path()

        init {
            isClickable = true
            contentDescription = LanguageCode.getMyTitles(565)
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, dp(18f).toFloat(), back)
            val r = dp(6f).toFloat()
            val l = dp(3.5f).toFloat()
            path.reset()
            // top-left, top-right, bottom-right, bottom-left brackets
            path.moveTo(cx - r, cy - r + l); path.lineTo(cx - r, cy - r); path.lineTo(cx - r + l, cy - r)
            path.moveTo(cx + r - l, cy - r); path.lineTo(cx + r, cy - r); path.lineTo(cx + r, cy - r + l)
            path.moveTo(cx + r, cy + r - l); path.lineTo(cx + r, cy + r); path.lineTo(cx + r - l, cy + r)
            path.moveTo(cx - r + l, cy + r); path.lineTo(cx - r, cy + r); path.lineTo(cx - r, cy + r - l)
            canvas.drawPath(path, stroke)
        }
    }

    /** A filled circle with a play triangle or two pause bars: no drawable to depend on. */
    private class PlayPauseButton(context: Context, rp: Theme.ResourcesProvider?) : View(context) {
        private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.getColor(Theme.key_featuredStickers_addButton, rp) }
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.getColor(Theme.key_featuredStickers_buttonText, rp) }
        private val path = Path()
        private val bar = RectF()
        private var playing = false

        init {
            isClickable = true
        }

        fun setPlaying(value: Boolean) {
            playing = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, dp(20f).toFloat(), circle)
            if (playing) {
                val w = dp(4f).toFloat()
                val h = dp(14f).toFloat()
                bar.set(cx - dp(6f), cy - h / 2, cx - dp(6f) + w, cy + h / 2)
                canvas.drawRoundRect(bar, 2f, 2f, glyph)
                bar.set(cx + dp(2f), cy - h / 2, cx + dp(2f) + w, cy + h / 2)
                canvas.drawRoundRect(bar, 2f, 2f, glyph)
            } else {
                path.reset()
                path.moveTo(cx - dp(5f), cy - dp(8f))
                path.lineTo(cx + dp(9f), cy)
                path.lineTo(cx - dp(5f), cy + dp(8f))
                path.close()
                canvas.drawPath(path, glyph)
            }
        }
    }
}
