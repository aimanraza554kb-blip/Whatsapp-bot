package com.myra.assistant.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.myra.assistant.util.Logger

/**
 * Accessibility service that lets MYRA perform system-wide gestures: go home,
 * go back, open recents, take a screenshot and close the current app. This
 * powers the phone-automation features requested by voice.
 */
class MyraAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Logger.i(TAG, "Accessibility connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* reactive automation hook */ }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun openRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun takeScreenshotAction() = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
    fun lockScreen(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false

    /**
     * Repeatedly look for a view by its resource id and tap it (or its nearest
     * clickable parent) once the target screen has loaded. Used to auto-send
     * WhatsApp messages after the chat opens.
     */
    fun clickNodeById(viewId: String, description: String? = null, retries: Int = 14) {
        val handler = Handler(Looper.getMainLooper())
        fun clickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo {
            var node: AccessibilityNodeInfo? = n
            while (node != null && !node.isClickable) node = node.parent
            return node ?: n
        }
        fun attempt(left: Int) {
            val root = rootInActiveWindow
            val node = root?.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
                ?: description?.let { d -> root?.findAccessibilityNodeInfosByText(d)?.firstOrNull() }
            if (node != null) {
                clickable(node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Logger.i(TAG, "Clicked $viewId")
            } else if (left > 0) {
                handler.postDelayed({ attempt(left - 1) }, 450)
            } else {
                Logger.w(TAG, "Node not found: $viewId")
            }
        }
        handler.postDelayed({ attempt(retries) }, 900)
    }

    companion object {
        private const val TAG = "MyraA11y"
        @Volatile
        var instance: MyraAccessibilityService? = null
            private set
    }
}
