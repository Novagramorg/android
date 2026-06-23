package org.fenixuz.utils

import android.content.Context
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.R

object ConfirmDialogsPref {
    var confirmSticker = false
    var confirmVoice = false
    var confirmGif = false

    private var sharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences("db", Context.MODE_PRIVATE)
    private var editor = sharedPreferences.edit()

    init {
        confirmSticker = sharedPreferences.getBoolean("confirm_sticker", false)
        confirmVoice = sharedPreferences.getBoolean("confirm_voice", false)
        confirmGif = sharedPreferences.getBoolean("confirm_gif", false)
    }

    fun changeConfirmStickerMode() {
        confirmSticker = !confirmSticker
        editor.putBoolean("confirm_sticker", confirmSticker)
        editor.commit()
    }

    fun changeConfirmVoiceMode() {
        confirmVoice = !confirmVoice
        editor.putBoolean("confirm_voice", confirmVoice)
        editor.commit()
    }

    fun changeConfirmGifMode() {
        confirmGif = !confirmGif
        editor.putBoolean("confirm_gif", confirmGif)
        editor.commit()
    }
}