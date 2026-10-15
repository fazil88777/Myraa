package com.myra.assistant.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.myra.assistant.R
import com.myra.assistant.util.PermissionHelper

/** Tools tab: quick actions that actually do things (MYRA ke real options). */
class FeaturesFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_features, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val act = activity as? MainActivity ?: return

        view.findViewById<View>(R.id.cardVoice).setOnClickListener {
            act.toggleVoiceSession()
        }
        view.findViewById<View>(R.id.cardScreen).setOnClickListener {
            act.toggleScreenShare()
        }
        view.findViewById<View>(R.id.cardPhone).setOnClickListener {
            PermissionHelper.openAccessibilitySettings(requireContext())
        }
        view.findViewById<View>(R.id.cardCalls).setOnClickListener {
            toast("MYRA se voice me kaho: 'call uthao' ya 'SMS bhejo'")
        }
        view.findViewById<View>(R.id.cardCamera).setOnClickListener {
            toast("Voice session me kaho: 'camera on karo'")
        }
        view.findViewById<View>(R.id.cardScheduled).setOnClickListener {
            toast("MYRA se kaho: 'X ko [time] par ye bhej dena'")
        }
    }

    private fun toast(s: String) =
        Toast.makeText(requireContext(), s, Toast.LENGTH_LONG).show()
}
