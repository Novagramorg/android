package org.fenixuz.hidden

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.fenixuz.utils.LanguageCode
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RLottieDrawable
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalFragment
import org.telegram.ui.LaunchActivity
import org.telegram.ui.SettingsActivity

/**
 * Novagram: the list of hidden accounts, opened by a long press on "Add account" and the code. Tapping an account opens
 * it; a long press shows it in the account lists again. The lock button locks hidden accounts at once (with
 * [HiddenAccounts.hideOnBackground] off it is the only way). Hidden accounts are listed nowhere else, so this screen
 * closes as soon as they lock again (see [HiddenAccounts.lock]).
 */
class HiddenAccountsListScreen : UniversalFragment(), NotificationCenter.NotificationCenterDelegate {

    private val ACCOUNT_BASE = 1000
    private val LOCK = 1

    // Telegram's empty-list duck with a line under it, centred on the plain background (a shadow-type row, so the list's
    // sections don't wrap it in a card); built once, reused by every fillItems.
    private var emptyView: View? = null

    override fun getTitle(): CharSequence = LanguageCode.getMyTitles(428)

    override fun onFragmentCreate(): Boolean {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.novagramHiddenAccountsChanged)
        return super.onFragmentCreate()
    }

    override fun onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.novagramHiddenAccountsChanged)
        super.onFragmentDestroy()
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id == NotificationCenter.novagramHiddenAccountsChanged) {
            if (!HiddenAccounts.unlocked) {
                finishFragment(false)
            } else {
                listView?.adapter?.update(true)
            }
        }
    }

    override fun createView(context: Context): View {
        val view = super.createView(context)
        listView.setSections()
        listView.adapter.setApplyBackground(false)
        actionBar.setAdaptiveBackground(listView)
        actionBar.createMenu().addItem(LOCK, R.drawable.outline_header_lock_24).contentDescription = LanguageCode.getMyTitles(456)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) {
                    -1 -> finishFragment()
                    // Leaves a hidden account on screen for a visible one; this screen then closes itself.
                    LOCK -> HiddenAccounts.lock()
                }
            }
        })
        return view
    }

    override fun onResume() {
        super.onResume()
        if (!HiddenAccounts.unlocked) {
            finishFragment(false)
            return
        }
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val hidden = (0 until UserConfig.MAX_ACCOUNT_COUNT)
            .filter { HiddenAccounts.isHidden(it) }
            .sortedBy { UserConfig.getInstance(it).loginTime }
        if (hidden.isEmpty()) {
            items.add(UItem.asCustomShadow(emptyView()))
            return
        }
        for (account in hidden) {
            items.add(SettingsActivity.AccountCell.Factory.of(ACCOUNT_BASE + account, account))
        }
        items.add(UItem.asShadow(null))
    }

    private fun emptyView(): View {
        emptyView?.let { return it }
        val context = context
        // As tall as the list's visible area, so that the duck sits in the middle of the screen.
        val layout = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val list = listView
                val height = if (list != null && list.measuredHeight > 0) list.measuredHeight - list.paddingTop - list.paddingBottom else 0
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxOf(height, AndroidUtilities.dp(240f)), MeasureSpec.EXACTLY))
            }
        }
        layout.orientation = LinearLayout.VERTICAL
        layout.gravity = Gravity.CENTER

        val imageView = BackupImageView(context)
        imageView.setImageDrawable(RLottieDrawable(R.raw.utyan_empty, AndroidUtilities.dp(120f), AndroidUtilities.dp(120f)))
        layout.addView(imageView, LayoutHelper.createLinear(120, 120, Gravity.CENTER_HORIZONTAL))

        val textView = TextView(context)
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f)
        textView.typeface = AndroidUtilities.bold()
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider))
        textView.gravity = Gravity.CENTER
        textView.text = LanguageCode.getMyTitles(447)
        layout.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 16, 32, 0))

        emptyView = layout
        return layout
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (!item.instanceOf(SettingsActivity.AccountCell.Factory::class.java)) return
        val account = item.intValue
        if (account == UserConfig.selectedAccount) {
            finishFragment()
        } else {
            LaunchActivity.instance?.switchToAccount(account, true)
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        if (!item.instanceOf(SettingsActivity.AccountCell.Factory::class.java)) return false
        val o = ItemOptions.makeOptions(this, view)
        HiddenAccounts.addHideOption(o, this, item.intValue)
        o.setScrimViewBackground(listView.getClipBackground(view)).show()
        return true
    }
}
