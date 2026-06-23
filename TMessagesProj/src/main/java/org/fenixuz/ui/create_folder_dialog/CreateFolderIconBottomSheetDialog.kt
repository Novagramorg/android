package org.fenixuz.ui.create_folder_dialog

import android.content.Context
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.BottomSheet

/**
 * Bottom sheet that lets the user pick a folder icon from [FolderIcons.ICONS].
 * Native Telegram BottomSheet (no material dependency).
 */
class CreateFolderIconBottomSheetDialog(
    context: Context,
    selectedIcon: Int,
    onPick: (Int) -> Unit
) {
    init {
        val recyclerView = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 5)
            val p = AndroidUtilities.dp(10f)
            setPadding(p, p, p, p)
            clipToPadding = false
        }

        val builder = BottomSheet.Builder(context)
        builder.setApplyTopPadding(true)
        builder.setApplyBottomPadding(true)
        builder.setCustomView(recyclerView)
        val sheet = builder.create()

        recyclerView.adapter = FolderIconsAdapter(FolderIcons.ICONS.toList(), selectedIcon) { icon ->
            onPick(icon)
            sheet.dismiss()
        }

        sheet.show()
    }
}
