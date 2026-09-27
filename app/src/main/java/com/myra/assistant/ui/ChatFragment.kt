package com.myra.assistant.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.myra.assistant.R

/** Chat tab: text conversation with MYRA. */
class ChatFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_chat, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val vm = ViewModelProvider(requireActivity())[MainViewModel::class.java]
        val adapter = ChatAdapter()

        val recycler = view.findViewById<RecyclerView>(R.id.chatRecycler)
        recycler.layoutManager =
            LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        recycler.adapter = adapter

        val msgInput = view.findViewById<EditText>(R.id.chatMessageInput)
        view.findViewById<Button>(R.id.chatSendButton).setOnClickListener {
            val msg = msgInput.text.toString()
            if (msg.isNotBlank()) {
                vm.sendTypedText(msg)
                msgInput.text.clear()
            }
        }

        vm.messages.observe(viewLifecycleOwner) {
            adapter.submit(it)
            if (adapter.itemCount > 0) {
                recycler.scrollToPosition(adapter.itemCount - 1)
            }
        }
    }
}
