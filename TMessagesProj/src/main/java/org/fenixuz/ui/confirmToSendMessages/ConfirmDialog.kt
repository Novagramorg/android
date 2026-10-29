package org.fenixuz.ui.confirmToSendMessages

import android.content.Context
import android.content.DialogInterface
import org.fenixuz.utils.LanguageCode
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme

object ConfirmDialog {

    /**
     * [baseFragment] is null where the emoji panel has no screen of its own (story replies, the quick-reply popup):
     * the dialog then shows by itself. It used to be non-null and crashed there (Play Vitals 1.3.0).
     */
    fun showDialog(
        baseFragment: BaseFragment?,
        context: Context,
        resourcesProvider: Theme.ResourcesProvider?,
        call: (Boolean) -> Unit
    ) {
        val builder = AlertDialog.Builder(
            context, resourcesProvider
        )
        builder.setMessage(LanguageCode.getMyTitles(195))
        builder.setTitle("Novagram")
        builder.setPositiveButton(
            LocaleController.getString("OK", R.string.OK)
        ) { dialogInterface: DialogInterface?, i: Int ->
            call(true)
        }
        builder.setNegativeButton(
            LocaleController.getString("Cancel", R.string.Cancel),
            null
        )
        val dialog = builder.create()
        if (baseFragment != null) {
            baseFragment.showDialog(dialog)
        } else {
            dialog.show()
        }
    }

}
