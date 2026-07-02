package org.fenixuz.utils

import android.content.Context
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.R

object StoryUtil {
    var hideStoryMode = false
    var hideStoryModeForVisibilityOnActionBar = true

    private var sharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences("db", Context.MODE_PRIVATE)
    private var editor = sharedPreferences.edit()

    init {
        hideStoryMode = sharedPreferences.getBoolean("hide_story", false)
        hideStoryModeForVisibilityOnActionBar =
            sharedPreferences.getBoolean("hide_story_for_visibility_on_action_bar", true)
    }

    fun getHideStoryBtnIcon(): Int {
        return if (hideStoryMode) {
            R.drawable.msg_stories_views
        } else {
            R.drawable.msg_stories_stealth
        }
    }

    fun getStoryTitle(): String{
        return if(hideStoryMode){
            LanguageCode.getMyTitles(230)
        }else {
            LanguageCode.getMyTitles(136)
        }
    }

    // Set when the tray-hide toggle is flipped from the settings screen. DialogsActivity consumes it
    // at the very TOP of onResume (before the permission-prompt branches that can early-return), so the
    // stories tray refreshes on every device — including Android 14 (full-screen-intent prompt) and
    // MIUI, where onResume returns early and never reaches the tail updateStoriesVisibility() call.
    private var storyVisibilityDirty = false

    fun markStoryVisibilityDirty() {
        storyVisibilityDirty = true
    }

    fun consumeStoryVisibilityDirty(): Boolean {
        val dirty = storyVisibilityDirty
        storyVisibilityDirty = false
        return dirty
    }

    fun changeHideStoryMode() {
        hideStoryMode = !hideStoryMode
        editor.putBoolean("hide_story", hideStoryMode)
        editor.commit()
    }

    fun changeHideStoryModeForVisibilityOnActionBar() {
        hideStoryModeForVisibilityOnActionBar = !hideStoryModeForVisibilityOnActionBar
        editor.putBoolean(
            "hide_story_for_visibility_on_action_bar",
            hideStoryModeForVisibilityOnActionBar
        )
        editor.commit()
    }
}