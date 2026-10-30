package tv.cleanyour.e2e.scenarios

import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.cleanyour.adb.session.AdbSessionManager
import tv.cleanyour.core.engine.DefaultEngine
import tv.cleanyour.core.engine.DocumentPlanStore
import tv.cleanyour.core.engine.FileOverlayTransport
import tv.cleanyour.core.engine.JournalRecord
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.LauncherArtifact
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.ops.HelperLines
import tv.cleanyour.core.ops.ShellRenderer
import tv.cleanyour.core.ops.Watchdog
import tv.cleanyour.core.planner.Choices
import tv.cleanyour.core.planner.DefaultPlanner
import tv.cleanyour.core.planner.HomeHelper
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.probe.AdbProber
import tv.cleanyour.core.session.TvSession
import tv.cleanyour.core.spec.BundledFactsTable
import tv.cleanyour.core.spec.BundledProfileSource
import tv.cleanyour.core.spec.HomeHelperKeys
import tv.cleanyour.core.spec.KnownLaunchers
import tv.cleanyour.core.spec.LauncherConfigCodec
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.trust.PinnedLauncher
import tv.cleanyour.e2e.E2eScenario
import tv.cleanyour.testing.fixtures.Fixtures
import tv.cleanyour.testing.fixtures.MemoryDocumentStore
import java.io.File

// RFC-HELPER-1 (test build), PLAN 4.10 S10: the Home button helper on a real TV image, with the
// pinned launcher. The planner is made with the helper on (homeHelper = true), whatever the build.
//
// 1. Setup with the helper: the look with stock_home_redirect, our HomeKeyService added by the
//    compare-and-set, accessibility on; during the check the service is enabled, Home from the TV's
//    own home screen and from inside an app lands on the new home screen; the person says yes.
// 2. A look push (RestartHome): the put back after the restart lists the helper again and Home
//    still lands on ours.
// 3. Manage's Switch off: the list is clean, accessibility back off, Home is the TV's own again.
// 4. The TV-side removal (Undo's inverse, the laptop scripts) and the safety timer, both on the
//    TV's own sh and toybox sed: our entry goes, the other entries stay byte for byte and in order;
//    a defused timer changes nothing.
// 5. S3: with accessibility off and another service listed, the planner refuses.
// 6. The helper on again, then a force stop: what the TV does with the list is written down, and
//    the Manage card reads it.
// 7. Undo everything with the launcher's removal: the list is clean, accessibility as it was, the
//    launcher gone, Home the TV's own.
// Not here: a reboot (probeAtBoot, direct boot) and TV Settings' own switch (restricted settings),
// which need a session of their own.
class S10HomeHelper : E2eScenario() {
    private val renderer = ShellRenderer()
    private val prober by lazy { AdbProber(deps.clock) }
    private val manager by lazy { AdbSessionManager(deps.sockets, deps.signer, tls = null, clock = deps.clock, scope = deps.scope) }
    private val planner by lazy {
        DefaultPlanner(deps.clock, deps.ids, renderer, FileOverlayTransport(prober), "e2e", profiles = BundledProfileSource(), homeHelper = true)
    }
    private val engine by lazy {
        DefaultEngine(renderer, prober, journal, snapshots, DocumentPlanStore(MemoryDocumentStore()), deps.clock, deps.scope, BundledProfileSource(),
            log = { note("engine: $it") })
    }
    private val service = HelperLines.SERVICE.flat
    private val reader = "com.example.reader/.Reader"
    private val other = "com.example.other/com.example.other.Speak"
    private val out: File get() = cfg.outDir.also { it.mkdirs() }
    private val notes = StringBuilder()
    private val records = mutableListOf<ChangeRecord>()
    private val tokens = mutableListOf<RunToken>()

    @After fun putTheTvBack() {
        oracle.shell("pkill -f cleanyourtv-rollback")
        // The timers' token files and logs of the runs here (Undo removes them; a failed run may not).
        for (t in tokens) for (f in listOf(Watchdog.tokenFileName(t), Watchdog.logFileName(t))) oracle.shell("rm -f /data/local/tmp/cleanyourtv/$f")
        oracle.shell("settings delete secure enabled_accessibility_services")
        oracle.shell("settings put secure accessibility_enabled 0")
        oracle.uninstall(LAUNCHER_PKG)
        oracle.pressHome()
        File(out, "s10-home-helper.txt").writeText(notes.toString())
    }

    private fun note(s: String) { notes.append(s).append('\n'); println("S10: $s") }
    private fun list(): String = oracle.shell("settings get secure enabled_accessibility_services").stdout.trim()
    private fun on(): String = oracle.shell("settings get secure accessibility_enabled").stdout.trim()

    private fun fileBlob(name: String, bytes: ByteArray): BlobRef {
        val f = File(out, name).also { it.writeBytes(bytes) }
        val sha = Sha256(java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        return BlobRef(sha, bytes.size.toLong(), f.absolutePath)
    }

    private fun launcher(): LauncherArtifact {
        val f = File(Fixtures.repoRoot, "android/platform/src/main/assets/bundled/${PinnedLauncher.ASSET_NAME}")
        val bytes = f.readBytes()
        return LauncherArtifact(PackageName(PinnedLauncher.PACKAGE), PinnedLauncher.VERSION_CODE, PinnedLauncher.VERSION_NAME,
            Sha256(PinnedLauncher.SHA256), Sha256(PinnedLauncher.SIGNER_SHA256), fileBlob("s10-launcher.apk", bytes))
    }

    private suspend fun snap(session: TvSession): Snapshot = prober.snapshot(session, SnapshotReason.PRE_PLAN).also { snapshots.save(it) }

    private fun planOf(request: PlanRequest, s: Snapshot): Plan = when (val r = planner.plan(request, s, BundledFactsTable(), records.toList())) {
        is PlanResult.Ok -> r.plan
        is PlanResult.Refused -> throw AssertionError("planner refused $request: ${r.reasons}")
    }

    // The change records of a run, as the app's ledger derives them: the steps the journal says are done.
    private suspend fun keep(plan: Plan) {
        for (r in journal.read(plan.id)) {
            if (r !is JournalRecord.Done || r.i !in plan.steps.indices) continue
            val step = plan.steps[r.i]
            records += ChangeRecord(plan.id, r.i, step.op, step.prior, deps.clock.nowMs())
        }
    }

    private suspend fun run(session: TvSession, plan: Plan, onConfirm: suspend (RunState.AwaitingConfirm) -> Unit = {}): RunState.Finished {
        tokens += plan.sections.map { it.token }
        val end = runToEnd(engine, plan, session, pass(session.tvId), onConfirm)
        keep(plan)
        assertTrue("run ended $end", end is RunState.Finished)
        return end as RunState.Finished
    }

    // Home from where the TV is now, then the resumed package a few seconds later.
    private suspend fun homeLandsOn(): String? {
        oracle.pressHome()
        delay(4_000)
        return oracle.resumedPackage()
    }

    private suspend fun fromAnApp(): String? {
        oracle.shell("am start -W -a android.settings.SETTINGS")
        delay(3_000)
        note("in front before Home: ${oracle.resumedPackage()}")
        return homeLandsOn()
    }

    private fun helperSetup(s: Snapshot): Plan = planOf(helperRequest(s), s)

    private fun helperRequest(s: Snapshot): PlanRequest.Setup {
        val stock = HomeHelper.stock(s, KnownLaunchers.generic) ?: KnownLaunchers.TVLAUNCHER
        val doc = HomeHelperKeys.with(LauncherConfigDoc(columns = 5), stock.pkg)
        val config = fileBlob("s10-config-${System.nanoTime()}.json", LauncherConfigCodec.encode(doc))
        val choices = Choices(emptySet(), emptySet(), null, null, installLauncher = true, takeover = false, replaceLauncherSigner = false,
            keepDebuggingOn = true, curtain = false, homeHelper = true)
        return PlanRequest.Setup(ProfileResolution.None(KnownLaunchers.generic), choices, launcher(), Level.SIMPLE, config = config)
    }

    @Test fun theHomeButtonHelperOnATvImage() = e2e(timeoutMs = 1_200_000) {
        oracle.shell("settings delete secure enabled_accessibility_services")
        oracle.shell("settings put secure accessibility_enabled 0")
        oracle.uninstall(LAUNCHER_PKG)
        val stockPkg = oracle.homeActivity()?.substringBefore('/')
        note("API ${oracle.sdk()}, Home resolves to ${oracle.homeActivity()}")
        val session = open(manager)
        try {
            // 1. Setup with the helper.
            val setup = helperSetup(snap(session))
            assertTrue(setup.sections.single().name == DefaultPlanner.SECTION_HELPER)
            val fin = run(session, setup) { check ->
                note("check screen: section ${check.section.name}, list '${list()}', accessibility ${on()}")
                assertEquals(service, list())
                assertEquals("1", on())
                val dump = oracle.shell("dumpsys accessibility").stdout
                val enabled = dump.lineSequence().firstOrNull { it.contains("Enabled services") }.orEmpty().trim()
                val bound = dump.lineSequence().firstOrNull { it.contains("Bound services") }.orEmpty().trim()
                note("dumpsys accessibility: $enabled | ${bound.take(300)}")
                assertTrue("not enabled: $enabled", enabled.contains("HomeKeyService"))
                val fromHome = homeLandsOn()
                note("Home from the TV's own home screen lands on $fromHome")
                val fromApp = fromAnApp()
                note("Home from inside an app lands on $fromApp")
                assertEquals(LAUNCHER_PKG, fromHome)
                assertEquals(LAUNCHER_PKG, fromApp)
                engine.confirmHome(session.tvId)
            }
            note("setup ended ${fin.status}, home ${fin.home}, skipped ${fin.skipped.map { it.op::class.simpleName }}")
            assertTrue(fin.status == RunStatus.COMPLETED || fin.status == RunStatus.COMPLETED_WITH_SKIPS)
            assertEquals(service, list())
            assertEquals("1", on())
            assertEquals("HOME is never moved", stockPkg, oracle.homeActivity()?.substringBefore('/'))
            assertTrue("the timer was killed", !oracle.processAlive(Watchdog.PROCESS_NAME_PREFIX))

            // 2. A look push restarts Home; the put back lists the helper again.
            val s2 = snap(session)
            assertEquals(HomeHelper.State.ON, HomeHelper.state(s2))
            val doc = HomeHelperKeys.carry(LauncherConfigDoc(columns = 6), s2.launcherOverlay, HomeHelper.listed(s2))
            assertEquals(stockPkg, HomeHelperKeys.redirectOf(doc)?.value)
            val look = planOf(PlanRequest.ApplyTheme(doc, fileBlob("s10-look.json", LauncherConfigCodec.encode(doc)), null), s2)
            assertEquals(RunStatus.COMPLETED, run(session, look).status)
            delay(3_000)
            note("after the look push: list '${list()}', accessibility ${on()}")
            assertEquals(service, list())
            assertEquals("1", on())
            val afterLook = fromAnApp()
            note("after the look push, Home from an app lands on $afterLook")
            assertEquals(LAUNCHER_PKG, afterLook)

            // 3. Manage's Switch off.
            val off = planOf(PlanRequest.HomeHelperOff, snap(session))
            note("switch off: ${off.steps.map { it.op::class.simpleName }}")
            assertEquals(RunStatus.COMPLETED, run(session, off).status)
            assertEquals("null", list())
            assertEquals("0", on())
            val afterOff = homeLandsOn()
            note("after Switch off, Home lands on $afterOff")
            assertEquals(stockPkg, afterOff)

            // 4. The TV-side removal and the safety timer on the TV's own sh.
            tvSideLines()

            // 5. S3: accessibility off with another service listed.
            oracle.shell("settings put secure enabled_accessibility_services $reader")
            oracle.shell("settings put secure accessibility_enabled 0")
            val s5 = snap(session)
            val refused = planner.plan(helperRequest(s5), s5, BundledFactsTable(), records)
            note("S3: ${refused}")
            assertTrue(refused is PlanResult.Refused && refused.reasons.single() == HomeHelper.Refusal.OTHERS_WOULD_BIND.reason)
            oracle.shell("settings delete secure enabled_accessibility_services")

            // 6. On again, then a force stop.
            val again = helperSetup(snap(session))
            val fin2 = run(session, again) { engine.confirmHome(session.tvId) }
            note("second setup ended ${fin2.status}, home ${fin2.home}; list '${list()}', accessibility ${on()}")
            oracle.shell("am force-stop $LAUNCHER_PKG")
            delay(3_000)
            val forced = snap(session)
            note("after a force stop: list '${list()}', accessibility ${on()}, state ${HomeHelper.state(forced)}, Home lands on ${homeLandsOn()}")

            // 7. Undo everything, the launcher removed too.
            val undo = planOf(PlanRequest.UndoEverything(records.toList(), removeLauncher = true), snap(session))
            note("undo: ${undo.steps.map { "${it.op::class.simpleName}" }}")
            val undone = run(session, undo)
            note("undo ended ${undone.status}; list '${list()}', accessibility ${on()}, launcher installed ${oracle.isInstalled(LAUNCHER_PKG)}, Home ${oracle.homeActivity()}")
            assertTrue(list() == "null" || list().isEmpty())
            assertEquals("0", on())
            assertTrue(!oracle.isInstalled(LAUNCHER_PKG))
            assertEquals(stockPkg, oracle.homeActivity()?.substringBefore('/'))
        } finally {
            session.close()
        }
    }

    // The lines Undo's inverse, the laptop scripts and the safety timer run, through the oracle's
    // adb shell (the same sh -c adbd runs the app's lines with), on lists with other entries.
    private suspend fun tvSideLines() {
        val remove = renderer.render(Op.AccessibilityRemove(null, HelperLines.SERVICE, offIfEmpty = true)).lines.single().text
        val full = "com.gathin.tvhome/com.gathin.tvhome.HomeKeyService"
        for ((before, after) in listOf("$reader:$service:$other" to "$reader:$other", "$full:$reader" to reader, service to "null")) {
            oracle.shell("settings put secure enabled_accessibility_services '$before'")
            oracle.shell("settings put secure accessibility_enabled 1")
            val r = oracle.shell(remove)
            note("tv removal over '$before': exit ${r.exit} '${r.stdout.trim()}', list '${list()}', accessibility ${on()}")
            assertEquals(0, r.exit)
            assertEquals(after, list())
            // The line writes 0 only when nothing is left. From Android 11 the system keeps
            // accessibility_enabled itself and writes 0 once no listed service exists, as here,
            // where the other entries are names of apps that are not installed.
            if (after == "null") assertEquals("0", on())
        }
        // The safety timer, 30 s: fired, then defused.
        val token = RunToken("00000000000000e2")
        val arm = renderer.render(Op.ArmHelperWatchdog(HelperLines.SERVICE, Watchdog.MIN_SECONDS, token, offIfEmpty = true))
        oracle.shell("mkdir -p /data/local/tmp/cleanyourtv")
        oracle.shell("settings put secure enabled_accessibility_services '$reader:$service'")
        oracle.shell("settings put secure accessibility_enabled 1")
        for (line in arm.lines) oracle.shell(line.text)
        delay(2_000)
        assertTrue("the timer runs", oracle.processAlive(Watchdog.PROCESS_NAME_PREFIX + token.value))
        delay(40_000)
        note("timer fired: list '${list()}', accessibility ${on()}, alive ${oracle.processAlive(Watchdog.PROCESS_NAME_PREFIX + token.value)}, " +
            "log '${oracle.shell("cat /data/local/tmp/cleanyourtv/${Watchdog.logFileName(token)}").stdout.trim()}'")
        assertEquals(reader, list())
        oracle.shell("settings put secure enabled_accessibility_services '$reader:$service'")
        for (line in arm.lines) oracle.shell(line.text)
        delay(2_000)
        for (line in renderer.render(Op.ConfirmHome(token)).lines) oracle.shell(line.text)
        delay(35_000)
        note("timer defused: list '${list()}'")
        assertEquals("$reader:$service", list())
        for (f in listOf(Watchdog.tokenFileName(token), Watchdog.logFileName(token))) oracle.shell("rm -f /data/local/tmp/cleanyourtv/$f")
        oracle.shell("settings delete secure enabled_accessibility_services")
        oracle.shell("settings put secure accessibility_enabled 0")
    }
}
