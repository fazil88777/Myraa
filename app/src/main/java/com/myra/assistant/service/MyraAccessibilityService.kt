package com.myra.assistant.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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
