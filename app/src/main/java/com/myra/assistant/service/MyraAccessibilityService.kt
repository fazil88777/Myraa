package com.myra.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

        /**
         * Tap something by its visible text OR by an icon button's description.
         * Text matches are tried first; then every node on screen is checked for
         * a content-description containing the query (this is how icon buttons
         * like WhatsApp's "Send" paper-plane or the "Voice call" icon get tapped).
         */
        fun clickOnText(text: String): Boolean {
            if (text.isBlank()) return false
            val root = instance?.rootInActiveWindow ?: return false
            return try {
                // 1) visible text matches (existing behavior)
                val nodes = root.findAccessibilityNodeInfosByText(text)
                for (n in nodes) {
                    if (clickNode(n)) return true
                }
                // 2) icon buttons: match content-description, e.g. "Send"
                val q = text.lowercase()
                val all = mutableListOf<AccessibilityNodeInfo>()
                collectAll(root, all)
                for (n in all) {
                    val desc = n.contentDescription?.toString()?.lowercase() ?: continue
                    if (desc.contains(q) && clickNode(n)) return true
                }
                false
            } catch (_: Exception) {
                false
            }
        }

        private fun clickNode(start: AccessibilityNodeInfo): Boolean {
            return try {
                var node: AccessibilityNodeInfo? = start
                var guard = 0
                while (node != null && !node.isClickable && guard < 8) {
                    node = node.parent
                    guard++
                }
                node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            } catch (_: Exception) {
                false
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

        /**
         * Scroll the screen up or down. Tries EVERY scrollable node on the
         * screen (deepest first) until one actually moves, so it no longer
         * gives up on the first stubborn container.
         */
        fun scroll(direction: String): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            return try {
                val forward = !direction.equals("up", ignoreCase = true)
                val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                             else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                val nodes = mutableListOf<AccessibilityNodeInfo>()
                collectScrollable(root, nodes)
                for (n in nodes.asReversed()) {
                    try {
                        if (n.performAction(action)) return true
                    } catch (_: Exception) {
                    }
                }
                false
            } catch (_: Exception) {
                false
            }
        }

        /** Old wrapper, kept for safety. */
        fun scrollForward(): Boolean = scroll("down")

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

        private fun collectScrollable(
            node: AccessibilityNodeInfo?,
            out: MutableList<AccessibilityNodeInfo>
        ) {
            if (node == null) return
            if (node.isScrollable) out.add(node)
            for (i in 0 until node.childCount) {
                collectScrollable(node.getChild(i), out)
            }
        }

        private fun collectAll(
            node: AccessibilityNodeInfo?,
            out: MutableList<AccessibilityNodeInfo>
        ) {
            if (node == null) return
            out.add(node)
            for (i in 0 until node.childCount) {
                collectAll(node.getChild(i), out)
            }
        }

        /**
         * Dump every actionable element on screen with its EXACT coordinates.
         * This is how MYRA "knows" precisely where each button, search bar and
         * icon is — no more guessing coordinates from the video.
         * Format per line: [i] "label" kind @(x,y)   (x,y are 0-1000)
         */
        fun getScreenElements(): String {
            val svc = instance ?: return "ERROR: accessibility off"
            val root = svc.rootInActiveWindow ?: return "ERROR: no screen"
            return try {
                val metrics = svc.resources.displayMetrics
                val w = metrics.widthPixels.toFloat()
                val h = metrics.heightPixels.toFloat()
                val out = StringBuilder()
                var count = 0
                fun walk(n: AccessibilityNodeInfo?) {
                    if (n == null || count >= 60) return
                    val label = n.text?.toString()?.trim().orEmpty()
                        .ifEmpty { n.contentDescription?.toString()?.trim().orEmpty() }
                    val actionable =
                        n.isClickable || n.isLongClickable || n.isEditable || n.isScrollable
                    if (actionable && label.isNotEmpty()) {
                        val r = android.graphics.Rect()
                        n.getBoundsInScreen(r)
                        val cx = ((r.centerX() / w) * 1000).toInt().coerceIn(0, 1000)
                        val cy = ((r.centerY() / h) * 1000).toInt().coerceIn(0, 1000)
                        val kind = when {
                            n.isEditable -> "input"
                            n.isScrollable -> "scroll"
                            else -> "btn"
                        }
                        out.append("[").append(count).append("] \"")
                            .append(label.take(40)).append("\" ")
                            .append(kind)
                            .append(" @(").append(cx).append(",").append(cy).append(")\n")
                        count++
                    }
                    for (i in 0 until n.childCount) {
                        if (count >= 60) break
                        walk(n.getChild(i))
                    }
                }
                walk(root)
                if (count == 0) "EMPTY: no labeled elements on screen" else out.toString()
            } catch (e: Exception) {
                "ERROR: ${e.message}"
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
