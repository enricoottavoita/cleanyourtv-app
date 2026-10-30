package tv.cleanyour.core.planner

import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.LauncherArtifact
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.ops.HelperLines
import tv.cleanyour.core.ops.ShellRenderer
import tv.cleanyour.core.planner.PlannerFixtures.change
import tv.cleanyour.core.planner.PlannerFixtures.choices
import tv.cleanyour.core.planner.PlannerFixtures.clock
import tv.cleanyour.core.planner.PlannerFixtures.facts
import tv.cleanyour.core.planner.PlannerFixtures.installedLauncher
import tv.cleanyour.core.planner.PlannerFixtures.launcher
import tv.cleanyour.core.planner.PlannerFixtures.snapshot
import tv.cleanyour.core.planner.PlannerFixtures.stock
import tv.cleanyour.core.spec.KnownLaunchers
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.store.SnapshotCodec
import tv.cleanyour.testing.fixtures.FakeIdGen
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// RFC-HELPER-1 (test build): the Home button helper's plans, PLAN 4.3 to 4.5 and rules S1 to S9.
class HomeHelperPlanTest {
    private val service = HomeHelper.SERVICE
    private val reader = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"
    private val voice = "com.vendor.voice/.Assist"
    private val config = BlobRef(Sha256("c".repeat(64)), 100, "blob:c")
    private val acc = SettingKey.ACCESSIBILITY_ENABLED
    private val list = SettingKey.ENABLED_ACCESSIBILITY

    private fun planner(build: Boolean = true) = DefaultPlanner(clock, FakeIdGen(), ShellRenderer(), OverlayTransport(), "0.4.8", homeHelper = build)
    private fun helperChoices() = choices(takeover = false).copy(homeHelper = true)
    private fun helperSetup(cfg: BlobRef? = config, choices: Choices = helperChoices(), profile: tv.cleanyour.core.model.Profile? = PlannerFixtures.profile()) =
        PlanRequest.Setup(profile?.let { ProfileResolution.Exact(it) } ?: ProfileResolution.None(listOf(stock)), choices,
            PlannerFixtures.artifact(), tv.cleanyour.core.model.Level.SIMPLE, config = cfg)
    private fun Snapshot.acc(l: String?, on: String?): Snapshot = withSettings(settings + mapOf(list to l, acc to on))
    private fun ok(r: PlanResult): Plan = assertIs<PlanResult.Ok>(r, r.toString()).plan
    private fun refusedWith(r: PlanResult): List<String> = assertIs<PlanResult.Refused>(r, r.toString()).reasons
    private fun helperOps(p: Plan) = p.steps.filter { it.group == DefaultPlanner.GROUP_HELPER }.map { it.op }

    // 0.4.7 (RFC-THEME-1): the helper's look (stock_home_redirect and key_filter in its config.json) goes the way any
    // look goes: through the launcher's ThemeProvider on Android 10 and later with a launcher that has it, else the sync
    // push; the helper's section follows it either way.
    @Test fun theHelpersLookTakesTheProviderRouteWhereTheLookDoes() {
        fun sdk(s: Snapshot, sdk: Int): Snapshot = s.copy(facts = s.facts.copy(sdk = sdk)).let { it.copy(contentHash = SnapshotCodec.hash(it)) }
        val req = helperSetup().copy(launcher = PlannerFixtures.artifact().copy(versionCode = 9))
        for ((v, route) in listOf(30 to tv.cleanyour.core.model.FileRoute.THEME_PROVIDER, 34 to tv.cleanyour.core.model.FileRoute.THEME_PROVIDER, 28 to tv.cleanyour.core.model.FileRoute.SYNC)) {
            val p = ok(planner().plan(req, sdk(snapshot(), v), facts, emptyList()))
            val push = p.steps.single { it.group == DefaultPlanner.GROUP_THEME && it.op is Op.PushAsset }
            assertEquals(route, (push.op as Op.PushAsset).route, "SDK $v")
            assertEquals(config, (push.op as Op.PushAsset).src, "the bytes with the helper's keys")
            assertTrue(push.index < p.steps.first { it.group == DefaultPlanner.GROUP_HELPER }.index)
            assertEquals(DefaultPlanner.SECTION_HELPER, p.sections.single().name)
        }
    }

    @Test fun theHelperKeepsTheTvsHomeAndAddsOnlyOurService() {
        val p = ok(planner().plan(helperSetup(), snapshot(), facts, emptyList()))
        // No home screen switch: HOME, the stock launcher and the wizard are left alone.
        assertTrue(p.steps.none { it.op is Op.SetHome || it.op is Op.ArmHomeWatchdog || it.op is Op.VerifyHome })
        assertTrue(p.steps.none { it.op == Op.Disable(stock.pkg) || it.op == Op.Disable(PlannerFixtures.wizard) })
        val token = (helperOps(p)[2] as Op.ArmHelperWatchdog).token
        assertEquals(listOf(
            Op.StartActivity(launcher, wait = true), Op.Mkdir(RemoteDir.TMP), Op.ArmHelperWatchdog(service, DefaultPlanner.WATCHDOG_SECONDS, token, offIfEmpty = true),
            Op.AccessibilityAdd(null, service), Op.SettingsPut(acc, "1"), Op.Settle(DefaultPlanner.HELPER_BIND_SECONDS), Op.PressHome,
            Op.Settle(DefaultPlanner.HELPER_CHECK_SECONDS), Op.ConfirmHome(token), Op.KillWatchdog(token),
        ), helperOps(p))
        // After the look, whose restart puts the TV's list back first (PLAN 4.5).
        val firstHelper = p.steps.first { it.group == DefaultPlanner.GROUP_HELPER }.index
        assertTrue(p.steps.filter { it.group == DefaultPlanner.GROUP_THEME }.all { it.index < firstHelper })
        assertTrue(p.steps.any { it.group == DefaultPlanner.GROUP_THEME && it.op is Op.PushAsset })
        val section = p.sections.single()
        assertEquals(DefaultPlanner.SECTION_HELPER, section.name)
        assertEquals(p.steps.single { it.op is Op.ArmHelperWatchdog }.index, section.from)
        assertEquals(p.steps.last { it.op == Op.Settle(DefaultPlanner.HELPER_CHECK_SECONDS) }.index, section.to)
        assertEquals(launcher, section.expectedHome)
        assertTrue(stock.pkg in section.rollbackIfFocus && launcher.pkg !in section.rollbackIfFocus)
        assertEquals(listOf(Op.AccessibilityRemove(service.flat, service), Op.SettingsPut(acc, "0"), Op.ConfirmHome(token), Op.KillWatchdog(token), Op.PressHome), section.rollback)
        // The adds are typed and their priors are what the TV had.
        assertEquals(Prior(settingValue = null), p.steps.single { it.op is Op.AccessibilityAdd }.prior)
        assertTrue(p.summary.homeScreenPossible)
    }

    @Test fun aReleaseBuildNeverPlansIt() {
        val reasons = refusedWith(planner(build = false).plan(helperSetup(), snapshot(), facts, emptyList()))
        assertEquals(listOf(HomeHelper.Refusal.NOT_THIS_BUILD.reason), reasons)
        // And the default planner is the build's: the release build bundles no marker.
        assertEquals(System.getProperty("cyt.homeHelper") == "true", HomeHelper.BUILD_ON, "the bundled switch is the build's")
    }

    @Test fun everySafetyRuleRefuses() {
        val tv = snapshot()
        fun refusal(s: Snapshot, stock: List<Component> = listOf(PlannerFixtures.stock), refuses: Boolean = false) = HomeHelper.refusal(s, stock, refuses, build = true)
        assertEquals(null, refusal(tv))
        assertEquals(HomeHelper.Refusal.TV_REFUSES, refusal(tv, refuses = true))   // S1: Fire OS, a China-market TCL
        assertEquals(HomeHelper.Refusal.OLD_ANDROID, refusal(tv.copy(facts = tv.facts.copy(sdk = 25))))
        assertEquals(HomeHelper.Refusal.NO_INSTALLS, refusal(tv.copy(facts = tv.facts.copy(installRefusal = true))))
        val vendor = Component(PackageName("com.vendor.home"), ".Home")
        assertEquals(HomeHelper.Refusal.NOT_GOOGLE_HOME, refusal(tv.copy(home = tv.home.copy(resolved = vendor))))   // S2, S9
        assertEquals(HomeHelper.Refusal.NOT_GOOGLE_HOME, refusal(tv.copy(home = tv.home.copy(resolved = null))))
        assertEquals(HomeHelper.Refusal.NOT_GOOGLE_HOME, refusal(tv.copy(home = tv.home.copy(resolved = launcher))))
        assertEquals(HomeHelper.Refusal.NOT_PROFILE_HOME, refusal(tv, stock = listOf(KnownLaunchers.LAUNCHERX)))
        assertEquals(HomeHelper.Refusal.LIST_UNREADABLE, refusal(tv.acc("not a list", "1")))   // S4
        val sixteen = (1..16).joinToString(":") { "com.example.app$it/.S" }
        assertEquals(HomeHelper.Refusal.LIST_FULL, refusal(tv.acc(sixteen, "1")))
        assertEquals(null, refusal(tv.acc(sixteen.substringBeforeLast(':'), "1")))
        assertEquals(HomeHelper.Refusal.OTHERS_WOULD_BIND, refusal(tv.acc(reader, "0")))   // S3
        assertEquals(HomeHelper.Refusal.OTHERS_WOULD_BIND, refusal(tv.acc(reader, null)))
        assertEquals(HomeHelper.Refusal.OTHERS_WOULD_BIND, refusal(tv.acc("com.gathin.tvhome/com.gathin.tvhome.InputKeyService", "0")))
        assertEquals(null, refusal(tv.acc(reader, "1")))   // S8: a reader with accessibility on is allowed
        // Through the planner: the plan says why, and nothing is planned.
        assertTrue(refusedWith(planner().plan(helperSetup(), tv.acc(reader, "0"), facts, emptyList())).single().startsWith("accessibility is off"))
        assertEquals(listOf(DefaultPlanner.REASON_HELPER_LOOK), refusedWith(planner().plan(helperSetup(cfg = null), tv, facts, emptyList())))
        assertEquals(listOf(DefaultPlanner.REASON_HELPER_AND_SWITCH),
            refusedWith(planner().plan(helperSetup(choices = choices(takeover = true).copy(homeHelper = true)), tv, facts, emptyList())))
        val fire = tv.copy(facts = tv.facts.copy(brand = "Amazon", manufacturer = "Amazon")).let { it.copy(contentHash = SnapshotCodec.hash(it)) }
        assertTrue(refusedWith(planner().plan(helperSetup(), fire, facts, emptyList())).isNotEmpty())
    }

    @Test fun withAReaderOnTheHelperIsAppendedAndAccessibilityIsLeftAsItIs() {
        val p = ok(planner().plan(helperSetup(), snapshot().acc(reader, "1"), facts, emptyList()))
        val ops = helperOps(p)
        assertTrue(Op.AccessibilityAdd(reader, service) in ops)
        assertTrue(ops.none { it is Op.SettingsPut }, ops.toString())
        val arm = ops.filterIsInstance<Op.ArmHelperWatchdog>().single()
        assertEquals(false, arm.offIfEmpty)
        assertEquals(listOf<Op>(Op.AccessibilityRemove("$reader:${service.flat}", service), Op.ConfirmHome(arm.token), Op.KillWatchdog(arm.token), Op.PressHome),
            p.sections.single().rollback)
        assertEquals(DefaultPlanner.NARRATION_HELPER_ON_WITH_OTHERS, p.steps.single { it.op is Op.AccessibilityAdd }.narration)
    }

    @Test fun aHelperAlreadyOnIsNothingMoreToDo() {
        val tv = snapshot(extra = mapOf(installedLauncher())).acc("$reader:${service.flat}", "1")
        val p = ok(planner().plan(helperSetup(), tv, facts, emptyList()))
        assertTrue(helperOps(p).isEmpty())
        assertTrue(p.sections.isEmpty())
        // Listed but switched off by the TV: only accessibility goes on, and its timer.
        val off = ok(planner().plan(helperSetup(), snapshot(extra = mapOf(installedLauncher())).acc(service.flat, "0"), facts, emptyList()))
        assertTrue(helperOps(off).none { it is Op.AccessibilityAdd })
        assertTrue(Op.SettingsPut(acc, "1") in helperOps(off))
        assertEquals(listOf(Op.SettingsPut(acc, "0")), off.sections.single().rollback.filter { it is Op.SettingsPut || it is Op.AccessibilityRemove })
    }

    // PLAN 4.5: once the helper is on, a look push, Undo's clear and a launcher update restart Home
    // and drop it; the put back after the restart writes the list back with our entry in it.
    @Test fun theRestartsPutTheHelperBack() {
        val on = "$reader:${service.flat}"
        val tv = snapshot(extra = mapOf(installedLauncher())).acc(on, "1")
        val look = ok(planner().plan(PlanRequest.ApplyTheme(LauncherConfigDoc(), config, null), tv, facts, emptyList()))
        val restart = look.steps.indexOfFirst { it.op is Op.RestartHome }
        val after = look.steps.drop(restart + 1).map { it.op }.filterIsInstance<Op.SettingsPut>()
        assertEquals(listOf(Op.SettingsPut(list, null), Op.SettingsPut(acc, "0"), Op.SettingsPut(list, on), Op.SettingsPut(acc, "1")), after)
        val pin = Samples.launcherArtifact()
        val update = ok(planner().plan(PlanRequest.UpdateLauncher(LauncherArtifact(pin.pkg, 5, "3.0", pin.sha256, pin.signer, pin.blob)), tv, facts, emptyList()))
        assertTrue(update.steps.none { it.op is Op.RestartHome }, "Home is the TV's own: no restart")
        assertTrue(Op.SettingsPut(list, on) in update.steps.map { it.op })
    }

    private val ourAdd = listOf(
        change(10, Op.AccessibilityAdd(null, service), Prior(settingValue = null)),
        change(11, Op.SettingsPut(acc, "1"), Prior(settingValue = "0")),
    )

    @Test fun switchingOffTakesOutOurEntryAndNothingElse() {
        val tv = snapshot(extra = mapOf(installedLauncher()))
        val withOthers = ok(planner().plan(PlanRequest.HomeHelperOff, tv.acc("$reader:${service.flat}:$voice", "1"), facts, ourAdd))
        assertEquals(PlanKind.HOME_HELPER_OFF, withOthers.kind)
        assertEquals(listOf<Op>(Op.AccessibilityRemove("$reader:${service.flat}:$voice", service)), withOthers.steps.map { it.op })
        val last = ok(planner().plan(PlanRequest.HomeHelperOff, tv.acc(service.flat, "1"), facts, ourAdd))
        assertEquals(listOf(Op.AccessibilityRemove(service.flat, service), Op.SettingsPut(acc, "0")), last.steps.map { it.op })
        // Accessibility was on before this app: it stays on.
        val wasOn = ok(planner().plan(PlanRequest.HomeHelperOff, tv.acc(service.flat, "1"), facts, ourAdd.take(1)))
        assertEquals(listOf<Op>(Op.AccessibilityRemove(service.flat, service)), wasOn.steps.map { it.op })
        assertTrue(ok(planner().plan(PlanRequest.HomeHelperOff, tv.acc(reader, "1"), facts, ourAdd)).steps.isEmpty())
        // The launcher's other helper, listed by a laptop install, stays.
        val input = "com.gathin.tvhome/com.gathin.tvhome.InputKeyService"
        assertEquals(listOf<Op>(Op.AccessibilityRemove("$input:${service.flat}", service)),
            ok(planner().plan(PlanRequest.HomeHelperOff, tv.acc("$input:${service.flat}", "1"), facts, ourAdd)).steps.map { it.op })
    }

    // PLAN 4.4: Undo everything takes the helper out first among the settings, before the look is
    // cleared and the launcher removed, and the restart after the clear does not bring it back.
    @Test fun undoEverythingTakesTheHelperOutFirst() {
        val model = ModelTv(snapshot())
        val setup = ok(planner().plan(helperSetup(), snapshot(), facts, emptyList()))
        model.apply(setup)
        val applied = model.snapshot()
        assertEquals(service.flat, applied.settings[list])
        assertEquals("1", applied.settings[acc])
        val undo = ok(planner().plan(PlanRequest.UndoEverything(model.changes, removeLauncher = true), applied, facts, model.changes))
        val ops = undo.steps.map { it.op }
        val remove = ops.indexOf(Op.AccessibilityRemove(service.flat, service))
        assertTrue(remove >= 0, ops.toString())
        assertTrue(ops.withIndex().filter { (_, op) -> op is Op.RemoveFile || op is Op.Uninstall || op is Op.SettingsPut }.all { it.index > remove }, ops.toString())
        assertTrue(Op.SettingsPut(acc, "0") in ops)
        assertTrue(ops.none { it is Op.SettingsPut && it.key == list && it.value != null }, "no list is written back: $ops")
        assertTrue(ops.none { it == Op.SettingsPut(acc, "1") })
        model.apply(undo)
        assertEquals(null, model.settings[list])
        // The person added a reader since: ours goes, the reader stays, and accessibility stays on.
        val later = ModelTv(snapshot()).also { it.apply(setup) }
        val withReader = later.snapshot().acc("${service.flat}:$reader", "1")
        val undo2 = ok(planner().plan(PlanRequest.UndoEverything(later.changes, removeLauncher = false), withReader, facts, later.changes))
        val ops2 = undo2.steps.map { it.op }
        assertTrue(Op.AccessibilityRemove("${service.flat}:$reader", service) in ops2, ops2.toString())
        assertTrue(ops2.none { it is Op.SettingsPut && it.key == acc }, ops2.toString())
        // Undo never adds the helper: a switched off one stays off.
        val off = later.snapshot().acc(reader, "1")
        assertTrue(ok(planner().plan(PlanRequest.UndoEverything(later.changes, removeLauncher = false), off, facts, later.changes)).steps.none {
            it.op is Op.AccessibilityAdd || it.op is Op.AccessibilityRemove })
    }

    // The watchdog's files of the helper's timer go with the launcher too (tidyWatchdogFiles).
    @Test fun theTimersFilesGoWithTheLauncher() {
        val model = ModelTv(snapshot())
        val setup = ok(planner().plan(helperSetup(), snapshot(), facts, emptyList()))
        model.apply(setup)
        val records: List<ChangeRecord> = model.changes + setup.steps.filter { it.op is Op.ArmHelperWatchdog || it.op is Op.ConfirmHome }
            .map { change(it.index, it.op, null) }
        val undo = ok(planner().plan(PlanRequest.UndoEverything(records, removeLauncher = true), model.snapshot(), facts, records))
        val token = setup.steps.firstNotNullOf { it.op as? Op.ArmHelperWatchdog }.token
        assertTrue(undo.steps.any { (it.op as? Op.RemoveFile)?.path?.segments == listOf("home-ok-${token.value}") }, undo.steps.map { it.op }.toString())
    }

    @Test fun theRemovalListsAreTheOthersInOrder() {
        for (l in listOf(service.flat, "$reader:${service.flat}", "${service.flat}:$reader:$voice", "$reader:$voice:${service.flat}")) {
            val expected = HelperLines.entries(l).filter { it != service.flat }.joinToString(":").ifEmpty { null }
            assertEquals(expected, HelperLines.without(l))
        }
    }
}
