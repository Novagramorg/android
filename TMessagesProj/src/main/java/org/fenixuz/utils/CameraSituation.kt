package org.fenixuz.utils

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.MenuItem
import android.widget.FrameLayout
import android.widget.PopupMenu
import org.telegram.ui.ActionBar.Theme

object CameraSituation {

    var isFront = true

    fun showPopupMenu(context: Context, audioVideoButtonContainer: FrameLayout, callback: (Int) -> Unit){
        val popupMenu = PopupMenu(context, audioVideoButtonContainer)

        popupMenu.menu.add(0, 1, 0, LanguageCode.getMyTitles(113))
        popupMenu.menu.add(0, 2, 1, LanguageCode.getMyTitles(114))

        popupMenu.setOnMenuItemClickListener { item: MenuItem ->
            if (item.itemId == 1) {
                isFront = true
            } else if (item.itemId == 2) {
                isFront = false
            }

            callback(item.itemId)

            true
        }

        popupMenu.show()

        try {
            val fieldPopup = PopupMenu::class.java.getDeclaredField("mPopup")
            fieldPopup.isAccessible = true
            val menuPopupHelper = fieldPopup[popupMenu]

            val classPopupHelper = Class.forName(menuPopupHelper.javaClass.name)
            val setBackgroundMethod = classPopupHelper.getMethod(
                "setBackgroundDrawable",
                Drawable::class.java
            )

            setBackgroundMethod.invoke(
                menuPopupHelper,
                ColorDrawable(Theme.ACTION_BAR_MEDIA_PICKER_COLOR)
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}