package com.myra.assistant.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.myra.assistant.R

/** History tab: read-only conversation log. */
class HistoryFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val vm = ViewModelProvider(requireActivity())[MainViewModel::class.java]
        val adapter = ChatAdapter()
        val recycler = view.findViewById<RecyclerView>(R.id.historyRecycler)
        recycler.layoutManager =
            LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        recycler.adapter = adapter
        vm.messages.observe(viewLifecycleOwner) {
            adapter.submit(it)
            if (adapter.itemCount > 0) {
                recycler.scrollToPosition(adapter.itemCount - 1)
            }
        }
    }
}
