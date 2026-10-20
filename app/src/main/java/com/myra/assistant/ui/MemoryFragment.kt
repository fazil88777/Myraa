package com.myra.assistant.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.myra.assistant.R
import com.myra.assistant.util.MyraMemory
import com.myra.assistant.util.Prefs

/**
 * Memory screen — read-only view of what MYRA remembers on this device:
 * the user's name, saved facts, pending/done tasks and the last session
 * recap. Reached from the dashboard MEMORY pill, the Memory nav item and
 * the Intelligence Brief card's "+ MORE".
 */
class MemoryFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_memory, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.memUserName).text =
            "👤 " + Prefs.userName.ifBlank { "Dost" }
        view.findViewById<TextView>(R.id.memFacts).text =
            bullets(MyraMemory.allFacts(), "Abhi koi fact save nahi hai")
        view.findViewById<TextView>(R.id.memPending).text =
            bullets(MyraMemory.pendingTasks(), "Koi pending task nahi 🎉")
        view.findViewById<TextView>(R.id.memDone).text =
            bullets(MyraMemory.recentDone(10), "Abhi kuch complete nahi hua")
        view.findViewById<TextView>(R.id.memRecap).text =
            MyraMemory.lastRecapText().ifBlank { "Abhi koi session recap nahi hai" }
    }

    private fun bullets(items: List<String>, emptyText: String): String =
        if (items.isEmpty()) emptyText
        else items.joinToString("\n") { "• $it" }
}
