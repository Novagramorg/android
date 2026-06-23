package org.fenixuz.ui.ghost_menu

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fenixuz.utils.GhostVariable
import org.fenixuz.utils.LanguageCode
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.messenger.browser.Browser
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Cells.TextInfoPrivacyCell
import org.telegram.ui.Cells.TextSettingsCell
import org.telegram.ui.Components.CustomPhoneKeyboardView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RLottieImageView
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.SizeNotifierFrameLayout
import org.telegram.ui.LaunchActivity

class GhostMenu : BaseFragment() {

    private var listView: RecyclerListView? = null
    private var listAdapter: MyListAdapter? = null
    private var moreBtn = -1
    private var changeGhostMode = -1
    private var changeVisibilityGhostModeOnActionBar = -1
    private var rowCount = 0
    private var utyanRow = -1
    private var hintRow = -1
    private val keyboardView: CustomPhoneKeyboardView? = null
    lateinit var progressDialog: AlertDialog

    override fun createView(context: Context?): View {
        progressDialog = AlertDialog(getContext(), AlertDialog.ALERT_TYPE_SPINNER)

        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setAllowOverlayTitle(false)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) {
                    finishFragment()
                }
            }
        })

        var fragmentContentView: View
        val frameLayout = FrameLayout(context!!)
        fragmentContentView = frameLayout

        val contentView: SizeNotifierFrameLayout = object : SizeNotifierFrameLayout(context) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                var frameBottom: Int
                fragmentContentView.layout(0, 0, measuredWidth, measuredHeight.also {
                    frameBottom = it
                })

                keyboardView?.layout(
                    0,
                    frameBottom,
                    measuredWidth,
                    frameBottom + AndroidUtilities.dp(CustomPhoneKeyboardView.KEYBOARD_HEIGHT_DP.toFloat())
                )
                notifyHeightChanged()
            }

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = MeasureSpec.getSize(widthMeasureSpec)
                val height = MeasureSpec.getSize(heightMeasureSpec)
                setMeasuredDimension(width, height)

                var frameHeight = height
                fragmentContentView.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(frameHeight, MeasureSpec.EXACTLY)
                )
                keyboardView?.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(
                        AndroidUtilities.dp(CustomPhoneKeyboardView.KEYBOARD_HEIGHT_DP.toFloat()),
                        MeasureSpec.EXACTLY
                    )
                )
            }
        }

        contentView.setDelegate { _: Int, _: Boolean -> }

        fragmentView = contentView
        contentView.addView(
            fragmentContentView,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f)
        )

        actionBar.setTitle(LanguageCode.getMyTitles(27))
        frameLayout.tag = Theme.key_windowBackgroundGray
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
        listView = RecyclerListView(context)
        listView?.setLayoutManager(object : LinearLayoutManager(context, VERTICAL, false) {
            override fun supportsPredictiveItemAnimations(): Boolean {
                return false
            }
        })
        listView?.isVerticalScrollBarEnabled = false
        listView?.setItemAnimator(null)
        listView?.setLayoutAnimation(null)
        frameLayout.addView(
            listView,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat())
        )
        listView?.setAdapter(MyListAdapter(context).also {
            listAdapter = it
        })
        listView?.onItemClickListener =
            RecyclerListView.OnItemClickListener { view: View, position: Int ->
                if (!view.isEnabled) {
                    return@OnItemClickListener
                }
                if (position == moreBtn) {
                    val activity: Activity = getParentActivity()
                    val launchActivity = activity as LaunchActivity
                    Browser.openUrl(launchActivity, "https://t.me/PRO_Messenger/2")
                    progressDialog.show()
                } else if (position == changeGhostMode) {
                    GhostVariable.changeGhostMode()
                    (view as TextCheckCell).isChecked = GhostVariable.ghostMode
                } else if (position == changeVisibilityGhostModeOnActionBar) {
                    GhostVariable.changeGhostModeVisibilityOnActionBar()
                    (view as NotificationsCheckCell).isChecked = GhostVariable.ghostMenuVisibilityOnActionBar
                }
            }

        updateRows()
        if (listAdapter != null) {
            listAdapter?.notifyDataSetChanged()
        }

        return fragmentView
    }


    override fun hasForceLightStatusBar(): Boolean {
//        return type != PasscodeActivity.TYPE_MANAGE_CODE_SETTINGS
        return false
    }

    private fun updateRows() {
        rowCount = 0
        utyanRow = rowCount++
        hintRow = rowCount++
        changeGhostMode = rowCount++
//        changeVisibilityGhostModeOnActionBar = rowCount++
        moreBtn = rowCount++
    }

    private inner class MyListAdapter(private val mContext: Context) :
        RecyclerListView.SelectionAdapter() {

        private val VIEW_TYPE_CHECK = 0
        private val VIEW_TYPE_SETTING = 1
        private val VIEW_TYPE_INFO = 2
        private val VIEW_TYPE_HEADER = 3
        private val VIEW_TYPE_UTYAN = 4
        private val TYPE_NIGHT_THEME = 5



        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean {
            val position = holder.adapterPosition
            return position == changeGhostMode || position == changeVisibilityGhostModeOnActionBar || position == moreBtn
        }

        override fun getItemCount(): Int {
            return rowCount
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view: View
            when (viewType) {

                TYPE_NIGHT_THEME -> {
                    view = NotificationsCheckCell(mContext, 21, 60, true)
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
                }

                VIEW_TYPE_CHECK -> {
                    view = TextCheckCell(mContext)
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
                }

                VIEW_TYPE_SETTING -> {
                    view = TextSettingsCell(mContext)
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
                }

                VIEW_TYPE_HEADER -> {
                    view = View(mContext)
                }

                VIEW_TYPE_UTYAN -> view = RLottieImageHolderView.create(mContext)
                VIEW_TYPE_INFO -> view = TextInfoPrivacyCell(mContext)
                else -> view = TextInfoPrivacyCell(mContext)
            }
            return RecyclerListView.Holder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder.itemViewType) {

                TYPE_NIGHT_THEME -> {
                    var checkCell: NotificationsCheckCell? =
                        holder.itemView as NotificationsCheckCell
                    if (position == changeVisibilityGhostModeOnActionBar) {
                        checkCell?.setTextAndValueAndIconAndCheck(
                            LanguageCode.getMyTitles(157),
                            LanguageCode.getMyTitles(160),
                            R.drawable.ghost_on,
                            GhostVariable.ghostMenuVisibilityOnActionBar,
                            0,
                            true,
                            true
                        )
                    }
                }

                VIEW_TYPE_CHECK -> {
                    val textCell = holder.itemView as TextCheckCell
                    if (position == changeGhostMode) {
                        textCell.setTextAndCheck(
                            LanguageCode.getMyTitles(32),
                            GhostVariable.ghostMode,
                            true
                        )
                    }
                }

                VIEW_TYPE_SETTING -> {
                    val textCell = holder.itemView as TextSettingsCell
                    if (position == moreBtn) {
                        textCell.setText(
                            LanguageCode.getMyTitles(59),
                            false
                        )
                        textCell.tag = Theme.key_text_RedBold
                        textCell.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton))
                    }
                }

                VIEW_TYPE_UTYAN -> {
                    val holderView = holder.itemView as RLottieImageHolderView
                    holderView.imageView.setAnimation(R.raw.utyan_private, 100, 100)
                    holderView.imageView.playAnimation()
                }

                VIEW_TYPE_INFO -> {
                    val cell = holder.itemView as TextInfoPrivacyCell
                    if (position == hintRow) {
                        cell.text = LanguageCode.getMyTitles(71)
                        cell.background = null
                        cell.textView.gravity = Gravity.CENTER_HORIZONTAL
                    }
                }
            }
        }

        override fun getItemViewType(position: Int): Int {
            if (position == changeGhostMode) {
                return VIEW_TYPE_CHECK
            } else if (position == changeVisibilityGhostModeOnActionBar) {
                return TYPE_NIGHT_THEME
            } else if (position == moreBtn) {
                return VIEW_TYPE_SETTING
            } else if (position == hintRow) {
                return VIEW_TYPE_INFO
            } else if (position == utyanRow) {
                return VIEW_TYPE_UTYAN
            }
            return VIEW_TYPE_CHECK
        }
    }

    private class RLottieImageHolderView private constructor(context: Context) :
        FrameLayout(context) {
        val imageView = RLottieImageView(context)

        companion object {
            fun create(context: Context): RLottieImageHolderView {
                return RLottieImageHolderView(context)
            }
        }

        init {
            imageView.setOnClickListener { v: View? ->
                if (!imageView.animatedDrawable.isRunning) {
                    imageView.animatedDrawable.setCurrentFrame(0, false)
                    imageView.playAnimation()
                }
            }
            val size = AndroidUtilities.dp(120f)
            val params = LayoutParams(size, size)
            params.gravity = Gravity.CENTER_HORIZONTAL
            addView(imageView, params)

            setPadding(0, AndroidUtilities.dp(32f), 0, 0)
            layoutParams = RecyclerView.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
        }
    }

    override fun onPause() {
        progressDialog.dismiss()
        super.onPause()
    }

    override fun onFragmentDestroy() {
        progressDialog.dismiss()
        super.onFragmentDestroy()
    }

}