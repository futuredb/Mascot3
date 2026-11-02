package com.generativemascot.app.overlay

import android.graphics.Region
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver

/** Apply only to standalone overlay roots, never to an Activity's settings preview. */
internal fun installOverlayTouchRegion(view: View, region: () -> Region) {
    if (Build.VERSION.SDK_INT < 33) return
    var previous: Region? = null
    val listener = ViewTreeObserver.OnPreDrawListener {
        val root = view.rootSurfaceControl
        if (root != null) {
            val next = region()
            if (next != previous) { root.setTouchableRegion(next); previous = Region(next) }
        }
        true
    }
    view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { previous = null; v.viewTreeObserver.addOnPreDrawListener(listener) }
        override fun onViewDetachedFromWindow(v: View) {
            if (v.viewTreeObserver.isAlive) v.viewTreeObserver.removeOnPreDrawListener(listener)
            previous = null
        }
    })
}
