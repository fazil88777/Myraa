package com.myra.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Accessibility service backing the tap_text / input_text / scroll_screen tools.
 * Static helpers walk the active window's node tree; guard against deep trees.
 */
class MyraAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MyraAccessibilityService? = null

        fun clickOnText(text: String): Boolean {
            if (text.isBlank()) return false
            val root = instance?.rootInActiveWindow ?: return false
            return try {
                val nodes = root.findAccessibilityNodeInfosByText(text)
                for (n in nodes) {
                    var node: AccessibilityNodeInfo? = n
                    var guard = 0
                    while (node != null && !node.isClickable && guard < 8) {
                        node = node.parent
                        guard++
                    }
                    if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true
                    }
                }
                false
            } catch (_: Exception) {
                false
            }
        }

        /** Find the first node whose text contains [text] (case-insensitive). */
        private fun findTextContains(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
            if (node == null) return null
            val t = node.text?.toString()?.lowercase(Locale.US) ?: ""
            if (text in t) return node
            for (i in 0 until node.childCount) {
                val f = findTextContains(node.getChild(i), text)
                if (f != null) return f
            }
            return null
        }

        /** Find the first node whose content-description contains [sub] (case-insensitive). */
        private fun findDescContains(node: AccessibilityNodeInfo?, sub: String): AccessibilityNodeInfo? {
            if (node == null) return null
            val d = node.contentDescription?.toString()?.lowercase(Locale.US) ?: ""
            if (sub in d) return node
            for (i in 0 until node.childCount) {
                val f = findDescContains(node.getChild(i), sub)
                if (f != null) return f
            }
            return null
        }

        /** Click the nearest clickable ancestor of [node], or tap its center. */
        private fun activateNode(node: AccessibilityNodeInfo): Boolean {
            var n: AccessibilityNodeInfo? = node
            var guard = 0
            while (n != null && !n.isClickable && guard < 8) {
                n = n.parent
                guard++
            }
            if (n != null && n.isClickable) {
                try {
                    if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                } catch (_: Exception) {
                }
            }
            return try {
                val svc = instance ?: return false
                val dm = svc.resources.displayMetrics
                val r = Rect()
                node.getBoundsInScreen(r)
                val x = (r.centerX() * 1000f / dm.widthPixels).toInt().coerceIn(0, 1000)
                val y = (r.centerY() * 1000f / dm.heightPixels).toInt().coerceIn(0, 1000)
                tapAt(x, y)
            } catch (_: Exception) {
                false
            }
        }

        /** Tap the first on-screen element whose text contains [text]. */
        fun clickOnTextContains(text: String): Boolean {
            if (text.isBlank()) return false
            val root = instance?.rootInActiveWindow ?: return false
            return try {
                val hit = findTextContains(root, text.lowercase(Locale.US)) ?: return false
                activateNode(hit)
            } catch (_: Exception) {
                false
            }
        }

        /** Tap the first on-screen element whose content-description contains [sub]. */
        fun tapOnDescContains(sub: String): Boolean {
            if (sub.isBlank()) return false
            val root = instance?.rootInActiveWindow ?: return false
            return try {
                val hit = findDescContains(root, sub.lowercase(Locale.US)) ?: return false
                activateNode(hit)
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Find a search-result row for [q]: a NON-editable node whose text
         * matches, walked up to its clickable row container. Skips the search
         * box itself (it holds the query text too).
         */
        private fun findResultRow(root: AccessibilityNodeInfo, q: String): AccessibilityNodeInfo? {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                try {
                    val t = n.text?.toString()?.lowercase(Locale.US)
                    val editable = n.isEditable || n.className?.toString()?.contains("EditText") == true
                    if (!editable && !n.isFocused && t != null && t.contains(q)) {
                        var p: AccessibilityNodeInfo? = n
                        var depth = 0
                        while (p != null && depth < 8) {
                            if (p.isClickable) return p
                            p = p.parent
                            depth++
                        }
                        return n
                    }
                    for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
                } catch (_: Exception) {
                }
            }
            return null
        }

        /**
         * Tap a search result row for [text] on the row's right side
         * (away from the left-side profile photo). Never taps the search box.
         */
        fun tapSearchResult(text: String): Boolean {
            if (text.isBlank()) return false
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            return try {
                val row = findResultRow(root, text.lowercase(Locale.US)) ?: return false
                val dm = svc.resources.displayMetrics
                val r = Rect()
                row.getBoundsInScreen(r)
                val x = ((r.left + r.width() * 0.75f) * 1000f / dm.widthPixels)
                    .toInt().coerceIn(0, 1000)
                val y = (r.centerY() * 1000f / dm.heightPixels).toInt().coerceIn(0, 1000)
                tapAt(x, y)
            } catch (_: Exception) {
                false
            }
        }

        /** True when WhatsApp search is still showing (search box holds [contact]). */
        private fun searchBoxStillActive(contact: String): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            return try {
                val ed = findEditable(root) ?: return false
                ed.text?.toString()?.trim().equals(contact, ignoreCase = true)
            } catch (_: Exception) {
                false
            }
        }

        /** True when WhatsApp's contact card popup is showing (not the chat). */
        private fun isContactCard(): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            return try {
                findDescContains(root, "video call") != null && findEditable(root) == null
            } catch (_: Exception) {
                false
            }
        }

        /** Tap the chat's message input field directly (never the header photo). */
        fun focusEditableField(): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            return try {
                val target = findEditable(root) ?: return false
                val dm = svc.resources.displayMetrics
                val r = Rect()
                target.getBoundsInScreen(r)
                val x = (r.centerX() * 1000f / dm.widthPixels).toInt().coerceIn(0, 1000)
                val y = (r.centerY() * 1000f / dm.heightPixels).toInt().coerceIn(0, 1000)
                tapAt(x, y)
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Full WhatsApp send flow: open WhatsApp, find [contact]'s chat, type
         * [message] and press the Send (paper-plane) button. Must be called
         * off the main thread. Returns "OK: ..." or "ERROR: ...".
         */
        fun sendWhatsAppMessage(contact: String, message: String): String {
            val svc = instance ?: return "ERROR: MYRA Accessibility service ON nahi hai"
            try {
                val launch = svc.packageManager.getLaunchIntentForPackage("com.whatsapp")
                    ?: return "ERROR: WhatsApp installed nahi hai"
                launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                svc.startActivity(launch)
                Thread.sleep(2500)

                // Be sure we are on the chats list (back out of any open chat).
                if (!tapOnDescContains("search")) {
                    pressBack()
                    Thread.sleep(1200)
                    if (!tapOnDescContains("search")) return "ERROR: WhatsApp search nahi mila"
                }
                Thread.sleep(800)
                if (!inputText(contact)) return "ERROR: search mein naam type nahi hua"
                Thread.sleep(1800)
                // Open the chat from search results (up to 2 tries). The search
                // box itself holds the contact name, so result rows are tapped
                // while editable nodes are skipped.
                var chatOpen = false
                repeat(2) {
                    if (tapSearchResult(contact)) {
                        Thread.sleep(1500)
                        // If the tap opened the contact card instead of the chat,
                        // use the card's message icon to open the chat.
                        if (isContactCard()) {
                            if (tapOnDescContains("message") || tapOnDescContains("chat")) {
                                Thread.sleep(1500)
                            }
                        }
                        if (!isContactCard() && !searchBoxStillActive(contact)) {
                            chatOpen = true
                            return@repeat
                        }
                    }
                }
                if (!chatOpen) return "ERROR: '$contact' ka chat nahi khula"
                // Focus the chat input field directly, then type.
                if (!focusEditableField()) return "ERROR: chat ka message box nahi mila"
                Thread.sleep(500)
                if (!inputText(message)) return "ERROR: message type nahi hua"
                Thread.sleep(1000)
                // Press the Send (paper-plane) button.
                if (!tapOnDescContains("send")) return "ERROR: Send button nahi mila"
                Thread.sleep(1200)
                pressBack()
                Thread.sleep(500)
                pressBack()
                return "OK: WhatsApp par $contact ko message bhej diya"
            } catch (e: Exception) {
                return "ERROR: ${e.message}"
            }
        }

        fun inputText(text: String): Boolean {
            val svc = instance ?: return false
            return try {
                val root = svc.rootInActiveWindow ?: return false
                var target = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (target == null) target = findEditable(root)
                if (target == null) return false
                val b = Bundle()
                b.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
                target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
            } catch (_: Exception) {
                false
            }
        }

        fun scrollForward(): Boolean = scroll("down")

        /**
         * Scroll the screen. direction = "down" (default) or "up".
         * Tries every scrollable node deepest-first, then falls back to a
         * swipe gesture for screens with no scrollable node (games, webviews,
         * custom views) where ACTION_SCROLL_* silently fails.
         */
        fun scroll(direction: String): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            val forward = !direction.equals("up", ignoreCase = true)
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            return try {
                val scrollables = mutableListOf<AccessibilityNodeInfo>()
                collectScrollables(root, scrollables) // deepest first
                for (s in scrollables) {
                    try {
                        if (s.performAction(action)) return true
                    } catch (_: Exception) {
                    }
                }
                // No scrollable node worked: swipe on the screen itself.
                if (forward) swipe(500, 700, 500, 300) else swipe(500, 300, 500, 700)
            } catch (_: Exception) {
                false
            }
        }

        private fun collectScrollables(
            node: AccessibilityNodeInfo?,
            out: MutableList<AccessibilityNodeInfo>
        ) {
            if (node == null) return
            for (i in 0 until node.childCount) {
                collectScrollables(node.getChild(i), out)
            }
            if (node.isScrollable) out.add(node)
        }

        /** Press the system Back button. */
        fun pressBack(): Boolean {
            val svc = instance ?: return false
            return try {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Dump every actionable on-screen element with 0-1000 coordinates,
         * so the model taps exact targets instead of guessing from video.
         * One line per element: [index] "label" @ x,y
         */
        fun getScreenElements(): String {
            val svc = instance ?: return "ERROR: service not running"
            val root = svc.rootInActiveWindow ?: return "ERROR: no active window"
            return try {
                val metrics = svc.resources.displayMetrics
                val w = metrics.widthPixels.toFloat()
                val h = metrics.heightPixels.toFloat()
                val out = StringBuilder()
                var count = 0
                val rect = Rect()
                fun walk(node: AccessibilityNodeInfo?) {
                    if (node == null || count >= 60) return
                    if (node.isClickable || node.isEditable || node.isScrollable || node.isCheckable) {
                        node.getBoundsInScreen(rect)
                        val cx = ((rect.left + rect.right) / 2f / w * 1000).toInt().coerceIn(0, 1000)
                        val cy = ((rect.top + rect.bottom) / 2f / h * 1000).toInt().coerceIn(0, 1000)
                        val label = node.text?.toString()?.takeIf { it.isNotBlank() }
                            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                            ?: node.className?.toString()?.substringAfterLast('.')?.takeIf { it.isNotBlank() }
                            ?: "?"
                        out.append("[$count] \"$label\" @ $cx,$cy\n")
                        count++
                    }
                    for (i in 0 until node.childCount) walk(node.getChild(i))
                }
                walk(root)
                if (count == 0) "EMPTY: no actionable elements" else out.toString().trimEnd()
            } catch (e: Exception) {
                "ERROR: ${e.message}"
            }
        }

        /**
         * Tap at exact screen coordinates. x and y are 0-1000
         * (0,0 = top-left, 1000,1000 = bottom-right).
         */
        fun tapAt(x: Int, y: Int): Boolean {
            val svc = instance ?: return false
            return try {
                val metrics = svc.resources.displayMetrics
                val px = (x.coerceIn(0, 1000) / 1000f * metrics.widthPixels)
                val py = (y.coerceIn(0, 1000) / 1000f * metrics.heightPixels)
                val path = Path().apply { moveTo(px, py) }
                val stroke = GestureDescription.StrokeDescription(path, 0, 80)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                dispatchAndWait(svc, gesture)
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Swipe from (x1,y1) to (x2,y2), coordinates 0-1000.
         * Swipe up (finger moves up) scrolls content down, etc.
         */
        fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
            val svc = instance ?: return false
            return try {
                val metrics = svc.resources.displayMetrics
                val w = metrics.widthPixels.toFloat()
                val h = metrics.heightPixels.toFloat()
                val path = Path().apply {
                    moveTo(x1.coerceIn(0, 1000) / 1000f * w, y1.coerceIn(0, 1000) / 1000f * h)
                    lineTo(x2.coerceIn(0, 1000) / 1000f * w, y2.coerceIn(0, 1000) / 1000f * h)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0, 400)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                dispatchAndWait(svc, gesture)
            } catch (_: Exception) {
                false
            }
        }

        private fun dispatchAndWait(
            svc: AccessibilityService,
            gesture: GestureDescription
        ): Boolean {
            var ok = false
            val latch = CountDownLatch(1)
            try {
                svc.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            ok = true
                            latch.countDown()
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            latch.countDown()
                        }
                    },
                    null
                )
                latch.await(2, TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
            return ok
        }

        private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isEditable) return node
            for (i in 0 until node.childCount) {
                val r = findEditable(node.getChild(i))
                if (r != null) return r
            }
            return null
        }

        private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                val r = findScrollable(node.getChild(i))
                if (r != null) return r
            }
            return null
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        maybeAnnounceIncomingCall(event)
    }

    // ---- Incoming-call announcer -------------------------------------------
    // When the phone's dialer shows an incoming call, speak it aloud:
    // "Boss, <name> ka call aa raha hai." Works even when no voice session
    // is active, needs no new permissions (screen content is already visible
    // to this service).

    private var tts: TextToSpeech? = null
    private var lastCallKey: String = ""
    private var lastCallAt: Long = 0L

    private fun maybeAnnounceIncomingCall(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()?.lowercase(Locale.US) ?: return
        val isPhoneUi = pkg.contains("dialer") || pkg.contains("telecom")
                || pkg == "com.android.phone"
        if (!isPhoneUi) return
        val root = rootInActiveWindow ?: return
        val texts = mutableListOf<String>()
        collectTexts(root, texts)
        if (texts.isEmpty()) return
        val blob = texts.joinToString(" ").lowercase(Locale.US)
        val incoming = blob.contains("incoming call") || blob.contains("آنے والی کال")
        if (!incoming) return
        val name = guessCallerName(texts) ?: "unknown number"
        val now = System.currentTimeMillis()
        if (name == lastCallKey && now - lastCallAt < 90_000) return // debounce
        lastCallKey = name
        lastCallAt = now
        announce("Boss, $name ka call aa raha hai.")
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null || out.size > 200) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it.trim()) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it.trim()) }
        for (i in 0 until node.childCount) collectTexts(node.getChild(i), out)
    }

    private fun guessCallerName(texts: List<String>): String? {
        val banned = setOf(
            "incoming call", "answer", "decline", "message", "remind me",
            "hold", "mute", "speaker", "keypad", "contacts", "recents",
            "video", "voice", "block", "spam"
        )
        val cands = texts.map { it.trim() }
            .filter { it.length >= 2 }
            .filter { t -> banned.none { b -> t.equals(b, ignoreCase = true) } }
        // Prefer a real name (has letters); fall back to the number itself.
        return cands.filter { it.any { c -> c.isLetter() } }.maxByOrNull { it.length }
            ?: cands.maxByOrNull { it.length }
    }

    private fun announce(msg: String) {
        try {
            val engine = tts
            if (engine == null) {
                tts = TextToSpeech(this) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        val e = tts ?: return@TextToSpeech
                        val ur = e.setLanguage(Locale("ur", "PK"))
                        if (ur == TextToSpeech.LANG_MISSING_DATA ||
                            ur == TextToSpeech.LANG_NOT_SUPPORTED
                        ) {
                            e.setLanguage(Locale.getDefault())
                        }
                        e.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "myra-call")
                    }
                }
            } else {
                engine.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "myra-call")
            }
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        instance = null
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        super.onDestroy()
    }
}
