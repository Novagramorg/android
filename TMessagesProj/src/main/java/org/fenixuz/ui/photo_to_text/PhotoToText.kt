package org.fenixuz.ui.photo_to_text

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.util.TypedValue
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.fenixuz.ui.auto_text.AutoTranslatePicker
import org.fenixuz.utils.AutoTranslate
import org.fenixuz.utils.LanguageCode
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import java.util.Locale

/**
 * Novagram "Scan text" (OCR): recognise the text inside a photo — on-device via ML Kit, NO network, NO
 * Firebase — and show it in a bottom sheet with Copy / Translate / Share. Opened from the PhotoViewer
 * overflow menu for image messages. The image is Telegram's ALREADY-downloaded cache file, so no storage
 * permission is needed (we never touch the gallery). Translation reuses [AutoTranslate] — the same fast
 * Google-first engine as chat auto-translate.
 *
 * Clean rewrite of pro's `PhotoToTextDialog`, dropping its flaws: pro opened the app-settings screen to
 * beg for READ_MEDIA_IMAGES (not needed for our own cache file), decoded the bitmap on the main thread,
 * appended each line on top of `visionText.text` (duplicating the whole text), and used a raw material
 * BottomSheetDialog. Here decoding is off the main thread, the text is taken once, and it uses Telegram's
 * own themed [BottomSheet].
 */
class PhotoToText(private val activity: Activity, private val account: Int, private val filePath: String?) {

    private val context: Context = activity
    private var textView: TextView? = null
    private var original: String = ""        // the OCR result — the translation SOURCE, never overwritten
    private var recognized: String = ""      // currently-shown text (original, or its translation)
    private var translating = false
    private var targetLang: String = loadTarget()  // remembered translate-to language (long-press to change)

    init {
        show()
        recognize()
    }

    private fun show() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }

        root.addView(TextView(context).apply {
            text = LanguageCode.getMyTitles(330)                 // "Scan text"
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            typeface = AndroidUtilities.bold()
            setPadding(dp(8), dp(8), dp(8), dp(12))
        })

        val tv = TextView(context).apply {
            text = LanguageCode.getMyTitles(332)                 // "Recognizing…"
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            setTextIsSelectable(true)
            setPadding(dp(8), dp(4), dp(8), dp(12))
        }
        textView = tv
        root.addView(
            NestedScrollView(context).apply { addView(tv) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(260))
        )

        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(iconButton(R.drawable.msg_copy, { copy() }))
        // Translate: tap = translate to the remembered language; long-press = pick a different language.
        buttons.addView(iconButton(R.drawable.msg_translate, { translate() }, { pickLanguage() }))
        buttons.addView(iconButton(R.drawable.msg_shareout, { share() }))
        root.addView(
            buttons,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) }
        )

        BottomSheet.Builder(context).setCustomView(root).show()
    }

    private fun iconButton(icon: Int, onClick: () -> Unit, onLongClick: (() -> Unit)? = null): ImageView =
        ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(icon)
            setColorFilter(Theme.getColor(Theme.key_dialogTextBlack))
            background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector))
            setOnClickListener { onClick() }
            if (onLongClick != null) {
                setOnLongClickListener { onLongClick(); true }
            }
        }

    private fun recognize() {
        val path = filePath
        if (path.isNullOrEmpty()) { setText("") ; return }
        // Decode off the main thread (a large photo is heavy to decode); ML Kit then runs async itself.
        Thread {
            val bitmap = decodeForOcr(path)
            val rotation = exifRotation(path)   // BitmapFactory ignores EXIF — feed ML Kit the real orientation
            AndroidUtilities.runOnUIThread {
                if (bitmap == null) { setText(""); return@runOnUIThread }
                try {
                    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                        .process(InputImage.fromBitmap(bitmap, rotation))
                        .addOnSuccessListener { setText(it.text) }
                        .addOnFailureListener { setText("") }
                } catch (e: Throwable) {
                    setText("")
                }
            }
        }.start()
    }

    /** Decode the photo, down-sampling huge images so they neither OOM nor blow past ML Kit's input limits. */
    private fun decodeForOcr(path: String): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_OCR_DIM) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Throwable) {
        null
    }

    /** The clockwise rotation (deg) the photo's EXIF says to apply — tilted shots otherwise read as garbage. */
    private fun exifRotation(path: String): Int = try {
        when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Throwable) {
        0
    }

    private fun setText(text: String) {
        original = text.trim()
        recognized = original
        textView?.text = if (recognized.isEmpty()) LanguageCode.getMyTitles(331) else recognized  // "No text found"
    }

    private fun copy() {
        if (recognized.isEmpty()) return
        AndroidUtilities.addToClipboard(recognized)
        Toast.makeText(context, LanguageCode.getMyTitles(41), Toast.LENGTH_SHORT).show()
    }

    /** Translate the ORIGINAL OCR text to [targetLang] (so re-picking a language never stacks). */
    private fun translate() {
        if (original.isEmpty() || translating) return
        translating = true
        textView?.text = LanguageCode.getMyTitles(332)            // "Recognizing…" reused as a working hint
        AutoTranslate.translate(original, targetLang, account) { translated, ok ->
            translating = false
            recognized = if (ok && !translated.isNullOrEmpty()) translated.toString() else original
            textView?.text = recognized
        }
    }

    /** Long-press Translate → choose the target language (remembered for next time), then translate. */
    private fun pickLanguage() {
        if (original.isEmpty()) return
        AutoTranslatePicker.pickLanguage(activity, targetLang, false) { code ->
            if (!code.isNullOrBlank()) {
                targetLang = code
                prefs().edit().putString(KEY_TARGET, code).apply()
            }
            translate()
        }
    }

    private fun loadTarget(): String {
        val saved = prefs().getString(KEY_TARGET, "") ?: ""
        return if (saved.isNotBlank()) saved else try { Locale.getDefault().language } catch (e: Throwable) { "en" }
    }

    private fun prefs() = context.getSharedPreferences("db", Context.MODE_PRIVATE)

    private fun share() {
        if (recognized.isEmpty()) return
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, recognized)
            }
            activity.startActivity(Intent.createChooser(intent, LanguageCode.getMyTitles(330)))
        } catch (e: Throwable) {
            // no share target — ignore
        }
    }

    private fun dp(value: Int): Int = AndroidUtilities.dp(value.toFloat())

    private companion object {
        private const val KEY_TARGET = "ocr_translate_to"   // device-only, shared "db" pref
        private const val MAX_OCR_DIM = 2560                 // cap the longest side: avoids OOM, keeps detail
    }
}
