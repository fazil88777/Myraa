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

        fun scrollForward(): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            return try {
                val s = findScrollable(root)
                s?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
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
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
