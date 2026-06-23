package org.fenixuz.utils

import android.content.Context
import org.telegram.messenger.ApplicationLoader

object GhostStory {

    var ghostMode = false

    private var sharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences("db", Context.MODE_PRIVATE)
    private var editor = sharedPreferences.edit()

    init {
        ghostMode = sharedPreferences.getBoolean("ghost_story", false)
    }

    fun getGhostModeFromCache(): Boolean {
        return sharedPreferences.getBoolean("ghost_story", false)
    }

    fun changeGhostMode(mode: Boolean) {
        ghostMode = mode
        editor.putBoolean("ghost_story", ghostMode)
        editor.commit()
    }

}