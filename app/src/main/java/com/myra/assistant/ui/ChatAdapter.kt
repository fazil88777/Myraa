package com.myra.assistant.ui

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.myra.assistant.R
import com.myra.assistant.data.ChatMessage

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {

    private val items = mutableListOf<ChatMessage>()

    fun submit(list: List<ChatMessage>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView.findViewById(R.id.messageText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = items[position]
        holder.textView.text = msg.text
        val params = holder.textView.layoutParams as FrameLayout.LayoutParams
        if (msg.role == "user") {
            params.gravity = Gravity.END
            holder.textView.setBackgroundColor(0xFFDCF8C6.toInt())
        } else {
            params.gravity = Gravity.START
            holder.textView.setBackgroundColor(0xFFFFFFFF.toInt())
        }
        holder.textView.layoutParams = params
        holder.textView.setTextColor(Color.BLACK)
    }

    override fun getItemCount(): Int = items.size
}
