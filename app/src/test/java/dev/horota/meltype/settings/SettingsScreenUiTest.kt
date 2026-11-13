// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.settings

import android.graphics.Insets
import android.view.View
import android.view.WindowInsets
import android.widget.TextView
import dev.horota.meltype.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 37])
class SettingsScreenUiTest {
    @Test fun titleAndVersionAreVisibleTogetherBelowSystemBarsAndCutout() {
        val lifecycle = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = lifecycle.get()
            val root = activity.window.decorView.findViewWithTag<View>("settings-safe-root")
            val version = root.findViewWithTag<TextView>("settings-version")
            val title = root.findViewWithTag<TextView>("settings-title")
            assertEquals("ver ${BuildConfig.VERSION_NAME}", version.text.toString())
            assertEquals("meltype:neo", title.text.toString())
            assertSame(title.parent, version.parent)
            val insets = WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 80, 0, 60))
                .setInsets(WindowInsets.Type.displayCutout(), Insets.of(0, 130, 0, 0))
                .build()
            root.dispatchApplyWindowInsets(insets)
            root.dispatchApplyWindowInsets(insets)
            assertEquals(130, root.paddingTop)
            assertEquals(60, root.paddingBottom)
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 2400)
            assertTrue((title.parent as View).top >= 130)
            assertTrue(title.height > 0)
            assertTrue(version.height > 0)
        } finally { lifecycle.pause().stop().destroy() }
    }

    @Test fun landscapeCutoutAndImeReserveTheirActualEdgesWithoutAccumulation() {
        val lifecycle = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val root = lifecycle.get().window.decorView.findViewWithTag<View>("settings-safe-root")
            val insets = WindowInsets.Builder()
                .setInsets(WindowInsets.Type.displayCutout(), Insets.of(110, 0, 0, 0))
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 40, 30, 50))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 720))
                .build()
            root.dispatchApplyWindowInsets(insets)
            root.dispatchApplyWindowInsets(insets)
            assertEquals(110, root.paddingLeft)
            assertEquals(40, root.paddingTop)
            assertEquals(30, root.paddingRight)
            assertEquals(720, root.paddingBottom)
        } finally { lifecycle.pause().stop().destroy() }
    }
}
