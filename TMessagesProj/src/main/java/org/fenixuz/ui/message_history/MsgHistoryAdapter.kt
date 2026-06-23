package org.fenixuz.ui.message_history

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.databinding.MsgItemBinding
import org.fenixuz.utils.EditMessage

class MsgHistoryAdapter(var messages: ArrayList<MessageHistoryModule>) :
    RecyclerView.Adapter<MsgHistoryAdapter.MessageHistoryVH>() {

    inner class MessageHistoryVH(var msgItemBinding: MsgItemBinding) :
        RecyclerView.ViewHolder(msgItemBinding.root) {
        fun onBind(messageHistoryModule: MessageHistoryModule) {
            msgItemBinding.msgText.text = messageHistoryModule.messageText
            msgItemBinding.editedTime.text =
                EditMessage.formatDateTime(messageHistoryModule.editedTime ?: 0)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageHistoryVH {
        return MessageHistoryVH(
            MsgItemBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: MessageHistoryVH, position: Int) {
        holder.onBind(messages[position])
    }

    override fun getItemCount(): Int {
        return messages.size
    }

}

class MsgItem(context: Context) : LinearLayout(context) {
    private var textView: TextView

    init {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER
            setPadding(10, 10, 10, 10)
            orientation = VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        textView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
        }

        addView(textView)
    }

    fun setText(str: CharSequence?) {
        textView.text = str ?: ""
    }
}