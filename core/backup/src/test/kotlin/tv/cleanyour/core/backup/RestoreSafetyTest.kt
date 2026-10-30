package tv.cleanyour.core.backup

import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PlanId
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.ops.ShellRenderer
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// WP4 G4 review: the restore files and the laptop restore for records the earlier builds and the
// 0.4.5 update path leave behind.
class RestoreSafetyTest {
    private val stock = Samples.stockLauncher
    private val launcher = Samples.launcherComponent
    private val chooser = Component(PackageName("android"), "com.android.internal.app.ResolverActivity")
    private val helpers = "com.gathin.tvhome/com.gathin.tvhome.InputKeyService"
    private fun rec(step: Int, op: Op, prior: Prior?) = ChangeRecord(PlanId("01SETUPPLAN000000000000001"), step, op, prior, Samples.NOW_MS + step)

    private val takeover = listOf(
        rec(0, Op.ArmHomeWatchdog(stock, 150, RunToken("00000000000000aa")), null),
        rec(1, Op.Disable(stock.pkg), Prior(packageState = EnabledState.ENABLED)),
        rec(2, Op.SetHome(launcher), Prior(home = chooser)),
    )

    // (3) A pre-0.4.5 journal with the chooser as the SetHome prior: homeBefore (restore-point.json,
    // restore.txt, the note) and the laptop's SetHome name the stock home the switch armed its
    // watchdog with; without that record the laptop step says why it cannot put HOME back.
    @Test fun aPlaceholderHomePriorIsNeverTheHomeToGoBackTo() {
        assertEquals(stock, ChangeSummary.of(takeover).homeBefore)
        assertEquals(null, ChangeSummary.of(takeover.drop(1)).homeBefore)
        val home = RestoreCommands(ShellRenderer()).steps(takeover).single { it.change.op is Op.SetHome }
        assertEquals(RestoreStepKind.PUT_BACK, home.kind)
        assertEquals(Op.SetHome(stock), home.inverse)
        val none = RestoreCommands(ShellRenderer()).steps(takeover.drop(1)).single { it.change.op is Op.SetHome }
        assertEquals(RestoreStepKind.NOT_FROM_LAPTOP, none.kind)
        assertTrue(none.lines.isEmpty())
        // A real home screen prior is used as it is.
        val real = takeover.map { if (it.op is Op.SetHome) it.copy(prior = Prior(home = stock)) else it }
        assertEquals(stock, ChangeSummary.of(real).homeBefore)
    }

    // (1) The helper put back's records: a laptop script cannot read the TV first, so it never
    // writes accessibility_enabled 1 or the service list back; those steps say why instead.
    @Test fun theLaptopRestoreNeverSwitchesAccessibilityOn() {
        val putBack = listOf(
            rec(0, Op.SettingsPut(SettingKey.ENABLED_ACCESSIBILITY, null), Prior(settingValue = helpers)),
            rec(1, Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "0"), Prior(settingValue = "1")),
            rec(2, Op.SettingsPut(SettingKey.ENABLED_ACCESSIBILITY, helpers), Prior(settingValue = helpers)),
            rec(3, Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "1"), Prior(settingValue = "1")),
        )
        val steps = RestoreCommands(ShellRenderer()).steps(putBack)
        assertTrue(steps.isNotEmpty())
        assertTrue(steps.all { it.kind == RestoreStepKind.NOT_FROM_LAPTOP && it.lines.isEmpty() }, steps.toString())
        assertTrue(steps.none { "accessibility_enabled' '1'" in it.lines.joinToString() })
        // Accessibility off before us: the off write goes back as it was, which switches nothing on.
        val offBefore = listOf(rec(0, Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "1"), Prior(settingValue = "0")))
        assertEquals(listOf(RestoreStepKind.PUT_BACK), RestoreCommands(ShellRenderer()).steps(offBefore).map { it.kind })
    }

    // RFC-HELPER-1 (test build), PLAN 4.4: the laptop scripts cannot read the list first, so the
    // helper's add goes back as the TV-side removal of that one entry, which keeps every other entry;
    // accessibility goes back off only inside that line, when nothing else is left. The separate
    // accessibility_enabled line, which would switch off another service, is left out. A removal of
    // ours is never undone, and no line switches accessibility on.
    @Test fun theLaptopRestoreTakesOutOnlyTheHelper() {
        val service = tv.cleanyour.core.ops.HelperLines.SERVICE
        val reader = "com.example.reader/.Reader"
        val on = listOf(
            rec(0, Op.ArmHelperWatchdog(service, 150, RunToken("00000000000000ab"), true), null),
            rec(1, Op.AccessibilityAdd(null, service), Prior(settingValue = null)),
            rec(2, Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "1"), Prior(settingValue = "0")),
            rec(3, Op.ConfirmHome(RunToken("00000000000000ab")), null),
        )
        val steps = RestoreCommands(ShellRenderer()).steps(on)
        val helper = steps.single()
        assertEquals(RestoreStepKind.PUT_BACK, helper.kind)
        assertEquals(Op.AccessibilityRemove(null, service, offIfEmpty = true), helper.inverse)
        assertEquals(listOf(tv.cleanyour.core.ops.HelperLines.tvRemove(service, offIfEmpty = true)), helper.lines)
        assertTrue(helper.lines.none { "accessibility_enabled 1" in it || "accessibility_enabled' '1'" in it })
        // Accessibility was on before: the removal only, never accessibility off.
        val wasOn = RestoreCommands(ShellRenderer()).steps(listOf(rec(1, Op.AccessibilityAdd(reader, service), Prior(settingValue = reader)))).single()
        assertEquals(Op.AccessibilityRemove(null, service, offIfEmpty = false), wasOn.inverse)
        // Switched off since through Manage: nothing to put back from the helper.
        val off = on + rec(9, Op.AccessibilityRemove(service.flat, service), Prior(settingValue = service.flat))
        assertEquals(listOf(Op.AccessibilityRemove(null, service, offIfEmpty = true)), RestoreCommands(ShellRenderer()).steps(off).map { it.inverse })
        val onlyRemoved = listOf(rec(0, Op.AccessibilityRemove("$reader:${service.flat}", service), Prior(settingValue = "$reader:${service.flat}")))
        assertTrue(RestoreCommands(ShellRenderer()).steps(onlyRemoved).isEmpty(), "a removal is never undone")
    }
}
