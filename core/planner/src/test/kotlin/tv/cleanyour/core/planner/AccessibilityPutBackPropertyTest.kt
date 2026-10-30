package tv.cleanyour.core.planner

import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.LauncherArtifact
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.planner.PlannerFixtures.choices
import tv.cleanyour.core.planner.PlannerFixtures.facts
import tv.cleanyour.core.planner.PlannerFixtures.installedLauncher
import tv.cleanyour.core.planner.PlannerFixtures.launcher
import tv.cleanyour.core.planner.PlannerFixtures.planner
import tv.cleanyour.core.planner.PlannerFixtures.setup
import tv.cleanyour.core.planner.PlannerFixtures.snapshot
import tv.cleanyour.core.planner.PlannerFixtures.stock
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.core.ops.HelperLines
import tv.cleanyour.core.ops.ShellRenderer
import tv.cleanyour.testing.fixtures.FakeIdGen
import tv.cleanyour.testing.fixtures.Samples
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

// SECURITY.md (Launcher 3.0) and RF-LAUNCHER-240 F7: without the Home button helper's opt-in
// (RFC-HELPER-1, test build) the app never switches an accessibility service on; it only puts back
// what the TV had. With it, the second test holds the helper's own contract. Over seeded random TVs (accessibility on, off or unknown;
// lists with the launcher's helpers, someone else's service, both or none) and every kind of plan
// that can touch accessibility, each SettingsPut of enabled_accessibility_services or
// accessibility_enabled writes null, "0" or the snapshot's own value. Undo everything too (WP4 G4):
// it never writes back the value a change record says the TV had, since the person may have
// switched accessibility off or changed the list since.
class AccessibilityPutBackPropertyTest {
    private val rnd = Random(0x5EED_0A11)
    private val accessibility = setOf(SettingKey.ENABLED_ACCESSIBILITY, SettingKey.ACCESSIBILITY_ENABLED)
    private val helpers = listOf("com.gathin.tvhome/com.gathin.tvhome.InputKeyService", "com.gathin.tvhome/com.gathin.tvhome.HomeKeyService",
        "com.gathin.tvhome/.HomeKeyService")
    private val others = listOf("com.example.reader/.Reader", "com.vendor.voice/com.vendor.voice.Assist")
    private val config = BlobRef(Sha256("c".repeat(64)), 100, "blob:c")
    private val ours = Samples.launcherArtifact()

    private fun randomTv(): Snapshot {
        val home = if (rnd.nextBoolean()) launcher else stock
        val base = snapshot(home = home, claimants = listOf(launcher, stock).shuffled(rnd),
            extra = if (rnd.nextInt(5) != 0) mapOf(installedLauncher(versionCode = rnd.nextLong(1, 4))) else emptyMap())
        val list = (helpers.shuffled(rnd).take(rnd.nextInt(0, 3)) + others.shuffled(rnd).take(rnd.nextInt(0, 2))).shuffled(rnd)
        val settings = base.settings.toMutableMap()
        if (list.isNotEmpty() || rnd.nextBoolean()) settings[SettingKey.ENABLED_ACCESSIBILITY] = list.joinToString(":")
        when (rnd.nextInt(3)) {
            0 -> settings[SettingKey.ACCESSIBILITY_ENABLED] = "1"
            1 -> settings[SettingKey.ACCESSIBILITY_ENABLED] = "0"
            else -> {}
        }
        return base.withSettings(settings)
    }

    private fun requests(): List<PlanRequest> = listOf(
        setup(choices = choices(takeover = rnd.nextBoolean(), installLauncher = rnd.nextBoolean())),
        PlanRequest.ApplyTheme(LauncherConfigDoc(columns = rnd.nextInt(4, 8)), config, null),
        PlanRequest.UpdateLauncher(LauncherArtifact(ours.pkg, rnd.nextLong(2, 10), "3.0", ours.sha256, ours.signer, ours.blob)),
        PlanRequest.UseStockHome(stock),
        PlanRequest.BeforeFactoryReset,
        PlanRequest.DebugOff,
        PlanRequest.Reboot,
    )

    private fun check(plan: Plan, snap: Snapshot, label: String): Int {
        var puts = 0
        for (s in plan.steps) {
            val op = s.op as? Op.SettingsPut ?: continue
            if (op.key !in accessibility) continue
            puts++
            val ok = op.value == null || op.value == "0" || op.value == snap.settings[op.key]
            assertTrue(ok, "$label writes ${op.key.key} = ${op.value}; the TV had ${snap.settings[op.key]}")
        }
        return puts
    }

    @Test fun everyAccessibilityWriteIsOffOrWhatTheTvHad() {
        var puts = 0; var plans = 0
        repeat(300) { i ->
            val snap = randomTv()
            for (req in requests()) {
                val r = planner(OverlayTransport()).plan(req, snap, facts, emptyList())
                val plan = (r as? PlanResult.Ok)?.plan ?: continue
                plans++
                puts += check(plan, snap, "#$i ${req::class.simpleName}")
                // Apply it to a model TV, then undo everything from its change records.
                val tv = ModelTv(snap)
                tv.apply(plan)
                val applied = tv.snapshot()
                val undo = planner(OverlayTransport()).plan(PlanRequest.UndoEverything(tv.changes, removeLauncher = rnd.nextBoolean()), applied, facts, tv.changes)
                (undo as? PlanResult.Ok)?.plan?.let { puts += check(it, applied, "#$i undo after ${req::class.simpleName}") }
            }
        }
        assertTrue(plans > 1000 && puts > 150, "generator lost coverage: plans=$plans, accessibility writes=$puts")
    }

    // RFC-HELPER-1 (PLAN 4.10): over random TVs, a plan without the opt-in never adds a service, even
    // in a build that has the helper; the helper's plan adds exactly our HomeKeyService over the list
    // the TV had; accessibility goes on only with nothing else listed (S3); every removal, step R's,
    // Manage's and Undo's, leaves the other entries byte for byte and in order.
    @Test fun theHelperAddsOnlyOurServiceAndEveryRemovalKeepsTheRest() {
        val helperPlanner = DefaultPlanner(PlannerFixtures.clock, FakeIdGen(), ShellRenderer(),
            OverlayTransport(), "0.4.8", homeHelper = true)
        var helperPlans = 0; var removals = 0
        fun removalKeepsTheRest(op: Op, before: String?, label: String) {
            if (op !is Op.AccessibilityRemove) return
            removals++
            assertTrue(op.component == HomeHelper.SERVICE, label)
            val others = HelperLines.entries(op.expected ?: before).filter { !HelperLines.isEntry(it) }
            assertTrue(HelperLines.without(op.expected ?: before) == others.joinToString(":").ifEmpty { null }, label)
        }
        repeat(300) { i ->
            val snap = randomTv()
            for (req in requests()) {
                val plan = (helperPlanner.plan(req, snap, facts, emptyList()) as? PlanResult.Ok)?.plan ?: continue
                assertTrue(plan.steps.none { it.op is Op.AccessibilityAdd }, "#$i ${req::class.simpleName} without the opt-in adds a service")
            }
            val req = setup(choices = choices(takeover = false, installLauncher = rnd.nextBoolean()).copy(homeHelper = true)).copy(config = config)
            val plan = (helperPlanner.plan(req, snap, facts, emptyList()) as? PlanResult.Ok)?.plan ?: return@repeat
            helperPlans++
            val list = snap.settings[SettingKey.ENABLED_ACCESSIBILITY]?.takeIf { it.isNotEmpty() }
            for (s in plan.steps) {
                val op = s.op
                if (op is Op.AccessibilityAdd) {
                    assertTrue(op.component == HomeHelper.SERVICE && op.expected == list, "#$i adds ${op.component.flat} over ${op.expected}, the TV had $list")
                }
                if (op == Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "1") && s.group == DefaultPlanner.GROUP_HELPER) {
                    assertTrue(HelperLines.others(list).isEmpty(), "#$i S3: accessibility on with $list")
                }
            }
            plan.sections.flatMap { it.rollback }.forEach { removalKeepsTheRest(it, list, "#$i step R") }
            // Applied, then Manage's switch off and Undo everything on the TV it leaves.
            val model = ModelTv(snap)
            model.apply(plan)
            val applied = model.snapshot()
            val now = applied.settings[SettingKey.ENABLED_ACCESSIBILITY]
            for (r in listOf(PlanRequest.HomeHelperOff, PlanRequest.UndoEverything(model.changes, removeLauncher = rnd.nextBoolean()))) {
                val p = (helperPlanner.plan(r, applied, facts, model.changes) as? PlanResult.Ok)?.plan ?: continue
                p.steps.forEach { removalKeepsTheRest(it.op, now, "#$i ${r::class.simpleName}") }
                assertTrue(p.steps.none { it.op is Op.AccessibilityAdd || it.op == Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "1") }, "#$i ${r::class.simpleName}")
                if (p.steps.any { it.op == Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "0") && it.group != DefaultPlanner.GROUP_THEME && it.group != DefaultPlanner.GROUP_UNDO || it.op == Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "0") && r is PlanRequest.HomeHelperOff }) {
                    assertTrue(HelperLines.others(now).isEmpty(), "#$i ${r::class.simpleName} switches accessibility off with $now")
                }
            }
        }
        assertTrue(helperPlans > 60 && removals > 60, "generator lost coverage: helper plans=$helperPlans, removals=$removals")
    }
}
