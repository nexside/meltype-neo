// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.ui

import android.os.Build
import android.view.View
import android.view.WindowInsets

/** 元の余白に加算する。再配信や画面回転のたびに余白を積み上げない。 */
object ScreenInsets {
    fun apply(view: View, includeIme: Boolean, includeTop: Boolean = true, enabled: () -> Boolean = { true }) {
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
        view.setOnApplyWindowInsetsListener { target, insets ->
            val active = enabled()
            if (Build.VERSION.SDK_INT >= 30) {
                val types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or
                    (if (includeIme) WindowInsets.Type.ime() else 0)
                val safe = insets.getInsets(types)
                target.setPadding(left + if (active) safe.left else 0,
                    top + if (active && includeTop) safe.top else 0,
                    right + if (active) safe.right else 0,
                    bottom + if (active) safe.bottom else 0)
            } else {
                @Suppress("DEPRECATION")
                target.setPadding(left + if (active) insets.systemWindowInsetLeft else 0,
                    top + if (active && includeTop) insets.systemWindowInsetTop else 0,
                    right + if (active) insets.systemWindowInsetRight else 0,
                    bottom + if (active) insets.systemWindowInsetBottom else 0)
            }
            insets
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { v.requestApplyInsets() }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
        if (view.isAttachedToWindow) view.requestApplyInsets()
    }
}
