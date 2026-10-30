package tv.cleanyour.core.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.spec.BundledFactsTable
import tv.cleanyour.core.spec.BundledProfileSource
import tv.cleanyour.testing.fixtures.FakeConfigTransport
import tv.cleanyour.testing.fixtures.FakeIdGen
import tv.cleanyour.testing.fixtures.Issue2028
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// Issue #2028 (a Jio JMSB200A, Android 11, no list): the log said "Profile: none", "Home switch:
// planned", and the switch could never take Home from the operator's launcher above ours. Setup now
// leaves the home screen alone as one it does not know (UNKNOWN_HOME) and plans the clean-up alone.
@OptIn(ExperimentalCoroutinesApi::class)
class Issue2028SetupTest {
    private val source = BundledProfileSource()

    private fun harness(): Harness {
        val h = Harness(facts = BundledFactsTable())
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.prober.facts = Issue2028.facts()
        h.prober.snapshotFor = { r -> Issue2028.snapshot(r) }
        h.profiles.resolver = { f, _ -> source.resolveNow(f) }
        h.profiles.homeResolver = { f, home -> source.resolveNow(f, home = home) }
        val planner = tv.cleanyour.core.planner.DefaultPlanner(h.clock, FakeIdGen(), tv.cleanyour.core.ops.ShellRenderer(), FakeConfigTransport(), "0.4.7")
        h.planner.respond = { request, s -> planner.plan(request, s, h.facts, emptyList()) }
        return h
    }

    @Test fun issue2028SetupBlocksTheSwitchAsAnUnknownHome() = runTest(timeout = 60.seconds) {
        val h = harness()
        val m = SetupMachine(h.setupDeps(), backgroundScope, SetupStart.Fresh)
        h.discovery.emit(tv.cleanyour.core.discovery.DiscoveryEvent.Found(miTvFound))
        settle()
        m.dispatch(SetupIntent.Start)
        settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        settle(700)
        val log = h.logLines.joinToString("\n")
        val found = assertIs<SetupState.Found>(m.state.value, log)
        assertTrue(h.logLines.any { it == "setup: home switch blocked UNKNOWN_HOME" }, log)
        assertTrue(found.plan.sections.isEmpty(), "no home screen switch")
        assertTrue(found.plan.steps.none { it.op is Op.SetHome || it.op == Op.Disable(Issue2028.tvlauncher.pkg) }, log)
    }
}
