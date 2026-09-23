package com.myra.assistant.telecom

import android.telecom.*
import com.myra.assistant.util.Prefs

/**
 * Screens incoming calls: records the last caller number and, when
 * auto-reject is enabled, blocks + rejects calls from non-blank numbers.
 */
class MyraCallScreeningService : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        try {
            val number = details.handle?.schemeSpecificPart ?: ""
            Prefs.lastCaller = number
            val response = if (Prefs.autoRejectUnknown && number.isNotBlank()) {
                CallResponse.Builder()
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .build()
            } else {
                CallResponse.Builder().build()
            }
            respondToCall(details, response)
        } catch (_: Exception) {
            try {
                respondToCall(details, CallResponse.Builder().build())
            } catch (_: Exception) {
            }
        }
    }
}
