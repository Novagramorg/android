package org.fenixuz.ui.confirmToSendMessages

import android.content.Context
import android.content.DialogInterface
import org.fenixuz.utils.LanguageCode
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment

object ConfirmDialog {

    fun showDialog(baseFragment: BaseFragment, context: Context, call: (Boolean) -> Unit) {
        val builder = AlertDialog.Builder(
            context
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
        baseFragment.showDialog(builder.create())
    }

}