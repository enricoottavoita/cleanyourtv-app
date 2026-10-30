package tv.cleanyour.core.engine

import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.planner.DefaultPlanner
import tv.cleanyour.core.planner.GuardedSection
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.session.ExecResult
import tv.cleanyour.testing.fixtures.Issue2028
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Issue #2028 (a Jio JMSB200A, Android 11): the operator's launcher holds Home above every other
// claimant. 0.4.6.1 switched tvlauncher off, the SetHome of ours failed its check (Home stayed Jio's),
// and step R's SetHome and VerifyHome of tvlauncher failed on every Carry on, with the TV as it was:
// "undo step failed: SetHome com.google.android.tvlauncher/.MainActivity: guard_failed verify:
// expected com.google.android.tvlauncher/.MainActivity, got com.iwedia.ui.rjio...IntroActivity".
// Step R now holds on Home as it was before the switch, and only on that or the stock home.
class Issue2028RollbackTest {
    private val stock = Issue2028.tvlauncher
    private val token = RunToken("0123456789abcdef")

    // The Jio box as the scripted TV: Android answers Home with `answers` whatever set-home-activity
    // says (by default the operator's launcher, ranked above every other claimant).
    private fun jioBox(answers: Component = Issue2028.jio): ScriptedTv {
        val tv = ScriptedTv(Issue2028.snapshot())
        tv.resolveHomeAs = { answers }
        return tv
    }

    // The plan 0.4.6.1 made for that TV (no list, tvlauncher as the stock home): the takeover alone,
    // the SetHome's prior Home as the probe read it (StockHome.recorded), rendered by the one renderer.
    private fun planOf0461(h: Harness): Plan {
        val ops = listOf(
            Op.StartActivity(h.launcher, wait = true), Op.Mkdir(RemoteDir.TMP),
            Op.ArmHomeWatchdog(stock, DefaultPlanner.WATCHDOG_SECONDS, token), Op.Disable(stock.pkg),
            Op.SetHome(h.launcher), Op.VerifyHome(h.launcher), Op.ConfirmHome(token), Op.KillWatchdog(token),
        )
        val rollback = listOf(Op.ConfirmHome(token), Op.KillWatchdog(token), Op.Enable(stock.pkg), Op.SetHome(stock), Op.VerifyHome(stock), Op.PressHome)
        val section = GuardedSection(DefaultPlanner.SECTION_HOME, 2, 5, token, 90_000, h.launcher, setOf(stock.pkg, Issue2028.jio.pkg), rollback)
        val plan = Samples.plan(ops, tvId = h.tv.tvId, renderer = h.renderer, id = h.idGen.planId(), sections = listOf(section),
            base = h.tv.snapshot().contentHash)
        return plan.copy(steps = plan.steps.map { if (it.op is Op.SetHome) it.copy(prior = Prior(home = Issue2028.jio)) else it })
    }

    private suspend fun started(h: Harness): Pair<DefaultEngine, Plan> {
        h.tv.installBytes(h.apkBytes)   // the new home screen is on the TV and starts
        val plan = planOf0461(h)
        val engine = h.engine()
        assertEquals(StartResult.Started, engine.start(plan, h.tv, plan.hash, h.pass()))
        return engine to plan
    }

    @Test fun issue2028StepRClosesTheSectionWithHomeAsItWasBefore() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this, jioBox())
        val (engine, plan) = started(h)
        val fin = assertIs<RunState.Finished>(h.settle(engine))
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        assertEquals(HomeOutcome.VERIFY_FAILED, fin.home)
        assertEquals(EnabledState.ENABLED, h.tv.state(stock.pkg), "tvlauncher back on")
        assertTrue(h.tv.watchdogs.isEmpty())
        assertEquals(listOf("section:ARMED", "section:ROLLED_BACK"), h.types(plan).filter { it.startsWith("section") })
        assertTrue(h.types(plan).none { it.startsWith("paused") })
        // The SetHome that never took Home failed its own check; step R's SetHome and VerifyHome of
        // tvlauncher are done (verified), held by Home as it was.
        assertTrue(h.records(plan).filterIsInstance<JournalRecord.Failed>().any { it.i == 4 && "got ${Issue2028.jio.flat}" in it.error })
        val stepR = h.records(plan).dropWhile { it !is JournalRecord.Rollback }
        assertTrue(stepR.none { it is JournalRecord.Failed }, stepR.toString())
    }

    // Home on a third home screen, neither tvlauncher nor the one before the switch: step R still
    // pauses, and Carry on meets the same pause.
    @Test fun aThirdHomeStillPausesStepR() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this, jioBox(answers = Issue2028.personal))
        val (engine, plan) = started(h)
        val paused = assertIs<RunState.Paused>(h.settle(engine))
        assertEquals(PauseReason.STEP_FAILED, paused.reason)
        assertTrue("got ${Issue2028.personal.flat}" in paused.error.toString(), paused.error.toString())
        assertTrue(h.types(plan).none { it == "section:ROLLED_BACK" })
        engine.continueRun(h.tv.tvId, h.pass())
        assertEquals(PauseReason.STEP_FAILED, assertIs<RunState.Paused>(h.settle(engine)).reason)
        assertTrue(h.types(plan).none { it == "section:ROLLED_BACK" })
    }

    // Home as it was does not show tvlauncher on again: a pm enable of it that fails still pauses.
    @Test fun homeAsBeforeDoesNotHideAFailedEnable() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this, jioBox())
        h.tv.answer = { line -> if (line == "pm enable --user 0 ${stock.pkg.value}") ExecResult(1, "", "Error: refused", false, 1) else null }
        val (engine, plan) = started(h)
        val paused = assertIs<RunState.Paused>(h.settle(engine))
        assertEquals(PauseReason.STEP_FAILED, paused.reason)
        assertEquals(EnabledState.DISABLED_USER, h.tv.state(stock.pkg))
        assertTrue(h.types(plan).none { it == "section:ROLLED_BACK" })
    }
}
