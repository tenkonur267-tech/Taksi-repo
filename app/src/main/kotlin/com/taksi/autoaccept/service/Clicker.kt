package com.taksi.autoaccept.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo

/** Kabul dugmesine basma islemi. */
object Clicker {

    /**
     * Once dugumun kendi tiklama eylemini dener; uygulama bunu desteklemiyorsa
     * dugmenin ortasina gercek bir dokunma jesti gonderir.
     *
     * @return basma denemesi baslatilabildiyse true
     */
    fun click(service: AccessibilityService, target: NodeScanner.AcceptTarget): Boolean {
        target.node?.let { node ->
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return tap(service, target)
    }

    private fun tap(service: AccessibilityService, target: NodeScanner.AcceptTarget): Boolean {
        val x = target.bounds.exactCenterX()
        val y = target.bounds.exactCenterY()
        if (x <= 0f || y <= 0f) return false

        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private const val TAP_DURATION_MS = 60L
}
