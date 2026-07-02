package org.fenixuz.utils

import android.content.Context
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.R

object GhostVariable {
    var ghostMode = false
    var ghostMenuVisibilityOnActionBar = false

    private var sharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences("db", Context.MODE_PRIVATE)
    private var editor = sharedPreferences.edit()

    init {
        ghostMode = sharedPreferences.getBoolean("ghost", false)
        ghostMenuVisibilityOnActionBar =
            sharedPreferences.getBoolean("ghost_btn_visibility_on_action_bar", false)
    }

    fun getGhostBtnIcon(): Int {
        return if (ghostMode) {
            R.drawable.ghost_on_for_action_bar
        } else {
            R.drawable.ghost_off_for_action_bar
        }
    }

    fun changeGhostMode() {
        ghostMode = !ghostMode
        editor.putBoolean("ghost", ghostMode)
        editor.commit()
        MyStatus.setMyStatus()
    }

    fun changeGhostModeVisibilityOnActionBar() {
        ghostMenuVisibilityOnActionBar = !ghostMenuVisibilityOnActionBar
        editor.putBoolean("ghost_btn_visibility_on_action_bar", ghostMenuVisibilityOnActionBar)
        editor.commit()
    }
}