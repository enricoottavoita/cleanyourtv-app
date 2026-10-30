package tv.cleanyour.core.ops

import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PlanId
import tv.cleanyour.core.model.RunToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// A start-up fallback screen is a placeholder whatever its class is called, the same test the Setup
// card uses (#481): the TCL Percee TV's com.android.boot.fallbackhome/.FallbackHomeCustomizer is one.
class HomePlaceholderTest {
    private fun c(pkg: String, cls: String) = Component(PackageName(pkg), cls)

    @Test fun everyFallbackHomeIsAPlaceholder() {
        assertTrue(HomePlaceholder.isPlaceholder(c("com.android.tv.settings", ".system.FallbackHome")))
        assertTrue(HomePlaceholder.isPlaceholder(c("com.android.boot.fallbackhome", ".FallbackHomeCustomizer")))
    }

    // Issue #1944: a factory test app that answers Home is never a home screen (upstream's
    // tools/disable.sh names: *factorytest*, *factory_test*, *factorymode*; and TCL's factory view).
    // Only the MOOQ0 list's factory test app is one that may go off; TCL's stays on.
    @Test fun aFactoryTestAppIsAPlaceholder() {
        assertTrue(HomePlaceholder.isPlaceholder(c("com.duokan.factorytest", ".FactoryLauncher")))
        assertTrue(HomePlaceholder.isPlaceholder(c("com.tcl.factory.view", ".tv.FactoryTvActivity")))
        assertTrue(HomePlaceholder.isFactoryHome(PackageName("com.mediatek.factorymode")))
        assertTrue(HomePlaceholder.isFactoryHome(PackageName("com.vendor.factory_test")))
        assertFalse(HomePlaceholder.isFactoryHome(PackageName("com.smartdevice.factory")))
        assertFalse(HomePlaceholder.isFactoryHome(PackageName("mediatek.factorymenu.ui")))
        assertEquals(setOf(PackageName("com.duokan.factorytest")), HomePlaceholder.FACTORY_HOMES_OFF)
        // Never the home screen a switch hands back to, even from an old journal.
        val factory = c("com.duokan.factorytest", ".FactoryLauncher")
        val arm = ChangeRecord(PlanId("01FAKEPLAN0000000000000001"), 0, Op.ArmHomeWatchdog(factory, 150, RunToken("0123456789abcdef")), null, 1L)
        assertNull(HomePlaceholder.stockFromRecords(listOf(arm)))
    }

    @Test fun aRealHomeScreenIsNot() {
        assertFalse(HomePlaceholder.isPlaceholder(c("com.google.android.apps.tv.launcherx", ".home.HomeActivity")))
        assertFalse(HomePlaceholder.isPlaceholder(c("com.gathin.tvhome", ".MainActivity")))
    }
}
