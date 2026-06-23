package org.fenixuz.ui.create_folder_dialog

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

/** Grid adapter for the folder-icon picker. */
class FolderIconsAdapter(
    private val icons: List<Int>,
    private val selectedIcon: Int,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<FolderIconsAdapter.IconVH>() {

    inner class IconVH(val item: FolderIconItem) : RecyclerView.ViewHolder(item) {
        fun bind(iconRes: Int) {
            item.setImage(iconRes)
            item.isSelected = iconRes == selectedIcon
            item.setOnClickListener { onClick(iconRes) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = IconVH(FolderIconItem(parent.context))
    override fun onBindViewHolder(holder: IconVH, position: Int) = holder.bind(icons[position])
    override fun getItemCount() = icons.size
}

/** A single selectable icon cell. */
class FolderIconItem(context: Context) : FrameLayout(context) {

    private val imageView = ImageView(context).apply {
        colorFilter = PorterDuffColorFilter(
            Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY
        )
    }

    init {
        val size = AndroidUtilities.dp(56f)
        layoutParams = RecyclerView.LayoutParams(size, size).apply {
            val m = AndroidUtilities.dp(4f)
            setMargins(m, m, m, m)
        }
        addView(imageView, LayoutHelper.createFrame(28, 28, Gravity.CENTER))
        isSelected = false
    }

    fun setImage(iconRes: Int) {
        imageView.setImageResource(iconRes)
    }

    override fun setSelected(selected: Boolean) {
        setBackgroundResource(
            if (selected) R.drawable.pro_folder_icon_sellected_ic
            else R.drawable.pro_folder_icon_unsellected_ic
        )
    }
}
