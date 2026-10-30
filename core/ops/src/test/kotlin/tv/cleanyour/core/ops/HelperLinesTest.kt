package tv.cleanyour.core.ops

import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.ProbeKind
import tv.cleanyour.core.model.ReadArg
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.model.SettingsNs
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// RFC-HELPER-1: the Home button helper's lines. The renderer's arguments are checked here; the
// lines themselves run in a real POSIX sh against a stand-in `settings` (a file per key), so the
// compare-and-set, the refusal when the list changed and the TV-side removal are tested as the TV's
// shell runs them, not as strings. The emulator scenario (e2e S10) runs them on toybox.
class HelperLinesTest {
    private val renderer = ShellRenderer()
    private val service = HelperLines.SERVICE
    private val full = "com.gathin.tvhome/com.gathin.tvhome.HomeKeyService"
    private val reader = "com.example.reader/.Reader"
    private val voice = "com.vendor.voice/com.vendor.voice.Assist\$Inner"
    private val input = "com.gathin.tvhome/com.gathin.tvhome.InputKeyService"
    private val token = RunToken("0123456789abcdef")

    // The TV's sh.

    private class Tv(list: String?, on: String?) {
        val dir: File = Files.createTempDirectory("cyt-helper").toFile()
        init {
            list?.let { File(dir, HelperLines.LIST_KEY).writeText(it) }
            on?.let { File(dir, HelperLines.ON_KEY).writeText(it) }
        }
        fun list(): String? = File(dir, HelperLines.LIST_KEY).takeIf { it.isFile }?.readText()
        fun on(): String? = File(dir, HelperLines.ON_KEY).takeIf { it.isFile }?.readText()
        var homeStarts = 0

        // Runs one line as adbd would (sh -c), with `settings` and `am` stand-ins in front of it.
        fun run(line: String): Int {
            val stub = "D='${dir.absolutePath}'; " +
                "settings() { case \"\$1\" in get) if [ -f \"\$D/\$3\" ]; then cat \"\$D/\$3\"; else echo null; fi;; " +
                "put) printf '%s' \"\$4\" > \"\$D/\$3\";; delete) rm -f \"\$D/\$3\";; esac; }; " +
                "am() { echo start >> \"\$D/am\"; }; "
            val p = ProcessBuilder("/bin/sh", "-c", stub + line).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            check(p.waitFor(30, TimeUnit.SECONDS)) { "the line did not end" }
            homeStarts = File(dir, "am").takeIf { it.isFile }?.readLines()?.size ?: 0
            return p.exitValue()
        }
    }

    private val shell = File("/bin/sh").canExecute()

    @Test fun theAddAppendsOnlyWhileTheListIsWhatThePhoneRead() {
        if (!shell) return
        for (expected in listOf(null, reader, "$reader:$voice", "$input:$reader")) {
            val tv = Tv(expected, "1")
            assertEquals(0, tv.run(HelperLines.add(expected, service)), "add over $expected")
            assertEquals(HelperLines.appended(expected, service), tv.list())
            assertEquals("1", tv.on(), "the add never touches accessibility_enabled")
        }
        // Someone changed the list since the probe: nothing is written and the line says so.
        val changed = Tv("$reader:$voice", "1")
        assertEquals(HelperLines.LIST_CHANGED_EXIT, changed.run(HelperLines.add(reader, service)))
        assertEquals("$reader:$voice", changed.list())
        // Planned on an unset list, met an empty one or the word null: both are unset to the TV.
        val empty = Tv("", "0")
        assertEquals(0, empty.run(HelperLines.add(null, service)))
        assertEquals(service.flat, empty.list())
    }

    @Test fun theCompareAndSetRemovalTakesOutOurEntryAndNothingElse() {
        if (!shell) return
        val cases = mapOf(
            service.flat to null, "$reader:${service.flat}" to reader, "${service.flat}:$reader" to reader,
            "$reader:${service.flat}:$voice" to "$reader:$voice", "$input:$full:$reader" to "$input:$reader",
        )
        for ((before, after) in cases) {
            val tv = Tv(before, "1")
            assertEquals(0, tv.run(HelperLines.casRemove(before, service)), before)
            assertEquals(after, tv.list(), before)
            assertEquals(after, HelperLines.without(before))
        }
        val changed = Tv("$reader:${service.flat}:$voice", "1")
        assertEquals(HelperLines.LIST_CHANGED_EXIT, changed.run(HelperLines.casRemove("$reader:${service.flat}", service)))
        assertEquals("$reader:${service.flat}:$voice", changed.list())
    }

    @Test fun theTvSideRemovalTakesOutOurEntryInEitherFormAndKeepsTheRestInOrder() {
        if (!shell) return
        val cases = listOf(
            service.flat to null, full to null, "$reader:${service.flat}" to reader, "${service.flat}:$reader" to reader,
            "$reader:$full:$voice" to "$reader:$voice", "${service.flat}:$full" to null, "$reader:${service.flat}:$full:$voice" to "$reader:$voice",
            // The launcher's other helper and a package that only starts with ours are not ours to remove.
            "$input:${service.flat}" to input, "com.gathin.tvhomex/.HomeKeyService:${service.flat}" to "com.gathin.tvhomex/.HomeKeyService",
            "com.gathin.tvhome/.HomeKeyServiceX" to "com.gathin.tvhome/.HomeKeyServiceX",
            reader to reader, "null" to "null",
        )
        for ((before, after) in cases) for (off in listOf(false, true)) {
            val tv = Tv(before, "1")
            assertEquals(0, tv.run(HelperLines.tvRemove(service, off)), before)
            assertEquals(after, tv.list(), "$before (off $off)")
            val gone = before != after && after == null
            assertEquals(if (off && gone) "0" else "1", tv.on(), "$before: accessibility off only when ours was the last (off $off)")
        }
        val unset = Tv(null, "1")
        assertEquals(0, unset.run(HelperLines.tvRemove(service, true)))
        assertEquals(null, unset.list()); assertEquals("1", unset.on())
    }

    // The safety timer's body: defused by the token file, else the removal and Home.
    @Test fun theSafetyTimerRemovesOurEntryUnlessDefused() {
        if (!shell) return
        val line = HelperLines.watchdog(WatchdogVariant.NOHUP_SETSID, service, 30, token, offIfEmpty = true)
        val body = line.substringAfter("sh -c '").substringBefore("' ${Watchdog.PROCESS_NAME_PREFIX}")
            .replace("sleep 30 & wait", "true").replace("sleep 2;", "true;")
        assertEquals(HelperLines.WATCHDOG_BODY.replace("REMOVE", HelperLines.tvRemove(service, true)).replace("TOKEN", token.value)
            .replace("sleep SECONDS & wait", "true").replace("sleep 2;", "true;"), body)
        val fired = Tv("$reader:${service.flat}", "1")
        assertEquals(0, fired.run(body.replace("/data/local/tmp/cleanyourtv", fired.dir.absolutePath)))
        assertEquals(reader, fired.list()); assertEquals("1", fired.on()); assertEquals(1, fired.homeStarts)
        val last = Tv(service.flat, "1")
        last.run(body.replace("/data/local/tmp/cleanyourtv", last.dir.absolutePath))
        assertEquals(null, last.list()); assertEquals("0", last.on())
        val defused = Tv("$reader:${service.flat}", "1")
        File(defused.dir, Watchdog.tokenFileName(token)).writeText("")
        defused.run(body.replace("/data/local/tmp/cleanyourtv", defused.dir.absolutePath))
        assertEquals("$reader:${service.flat}", defused.list()); assertEquals(0, defused.homeStarts)
    }

    // The renderer.

    @Test fun onlyOurServiceIsEverAddedOrRemovedAndNeverTwice() {
        val other = Component(PackageName("com.example.reader"), ".Reader")
        val input = Component(PackageName("com.gathin.tvhome"), ".InputKeyService")
        for (c in listOf(other, input, Component(PackageName("com.gathin.tvhome"), ".MainActivity"))) {
            assertFailsWith<RenderRefused> { renderer.render(Op.AccessibilityAdd(null, c)) }
            assertFailsWith<RenderRefused> { renderer.render(Op.AccessibilityRemove(null, c)) }
            assertFailsWith<RenderRefused> { renderer.render(Op.ArmHelperWatchdog(c, 60, token, false)) }
        }
        assertFailsWith<RenderRefused> { renderer.render(Op.AccessibilityAdd("$reader:${service.flat}", service)) }
        assertFailsWith<RenderRefused> { renderer.render(Op.AccessibilityAdd(full, service)) }
        assertFailsWith<RenderRefused> { renderer.render(Op.AccessibilityAdd("not a list", service)) }
        // S4: a list of 16 is full.
        val sixteen = (1..16).joinToString(":") { "com.example.app$it/.S" }
        assertFailsWith<RenderRefused> { renderer.render(Op.AccessibilityAdd(sixteen, service)) }
        renderer.render(Op.AccessibilityAdd(sixteen.substringBeforeLast(':'), service))
        // A compare-and-set removal of a list without our entry, or one that also switches accessibility off.
        assertFailsWith<RenderRefused> { renderer.render(Op.AccessibilityRemove(reader, service)) }
        assertFailsWith<RenderRefused> { renderer.render(Op.AccessibilityRemove(service.flat, service, offIfEmpty = true)) }
        assertFailsWith<RenderRefused> { renderer.render(Op.ArmHelperWatchdog(service, 29, token, false)) }
        assertFailsWith<RenderRefused> { renderer.render(Op.ArmHelperWatchdog(service, 301, token, false)) }
    }

    @Test fun theOpsCarryTheirChecksAndInverses() {
        val add = renderer.render(Op.AccessibilityAdd(reader, service))
        assertEquals(Op.Read(ProbeKind.SETTINGS, ReadArg.Ns(SettingsNs.SECURE)), add.verify)
        assertEquals(Privilege.TAKEOVER, add.privilege)
        assertEquals(VerifyResult.Ok, renderer.check(Op.AccessibilityAdd(reader, service), "x=1\nenabled_accessibility_services=$reader:${service.flat}\n", null))
        assertTrue(renderer.check(Op.AccessibilityAdd(reader, service), "enabled_accessibility_services=$reader\n", null) is VerifyResult.Mismatch)
        // Undo of the add: the TV-side removal of that one entry; a removal is never undone.
        assertEquals(Op.AccessibilityRemove(null, service), renderer.inverse(Op.AccessibilityAdd(reader, service), Prior(settingValue = reader)))
        assertEquals(null, renderer.inverse(Op.AccessibilityRemove(service.flat, service), Prior(settingValue = service.flat)))
        assertEquals(Op.KillWatchdog(token), renderer.inverse(Op.ArmHelperWatchdog(service, 60, token, true), Prior()))
        // A removal holds once our entry is gone, whoever took it out, in either form.
        val remove = Op.AccessibilityRemove("$reader:${service.flat}", service)
        assertEquals(VerifyResult.Ok, renderer.check(remove, "enabled_accessibility_services=$reader\n", null))
        assertEquals(VerifyResult.Ok, renderer.check(remove, "accessibility_enabled=1\n", null))
        assertTrue(renderer.check(remove, "enabled_accessibility_services=$reader:$full\n", null) is VerifyResult.Mismatch)
        // The timer: two lines, the stale token file first, then the template; checked by pgrep.
        val arm = renderer.render(Op.ArmHelperWatchdog(service, 150, token, true))
        assertEquals(2, arm.lines.size)
        assertEquals(listOf("rm", "-f", "/data/local/tmp/cleanyourtv/home-ok-${token.value}"), ShellTokens.tokenize(arm.lines[0].text))
        assertTrue(HelperLines.matchesWatchdog(arm.lines[1].text, WatchdogVariant.NOHUP_SETSID, service, 150, token, true))
        assertFalse(HelperLines.matchesWatchdog(arm.lines[1].text, WatchdogVariant.NOHUP_SETSID, service, 150, token, false))
        assertEquals(Op.Read(ProbeKind.WATCHDOG_ALIVE, ReadArg.Token(token)), arm.verify)
    }

    // Over random lists: every line is printable, has only its template's quotes (the expected and
    // the result, each in single quotes), and no forbidden word.
    @Test fun everyHelperLineIsOneOfItsTemplates() {
        val rnd = Random(20261028)
        val pool = listOf(reader, voice, input, "com.a.b/.C", "com.x.y/com.x.y.Z\$1")
        repeat(2000) {
            val list = pool.shuffled(rnd).take(rnd.nextInt(0, 4)).joinToString(":").ifEmpty { null }
            val add = HelperLines.add(list, service)
            assertTrue(ShellTokens.LINE_RE.matches(add))
            assertEquals(if (list == null) 2 else 4, add.count { it == '\'' } - 2, add)   // the refusal's echo has two of its own
            assertTrue(HelperLines.matchesAdd(add, list, service))
            val withOurs = (HelperLines.entries(list) + service.flat).shuffled(rnd).joinToString(":")
            val remove = HelperLines.casRemove(withOurs, service)
            assertTrue(ShellTokens.LINE_RE.matches(remove))
            for (line in listOf(add, remove, HelperLines.tvRemove(service, rnd.nextBoolean()))) {
                assertTrue(listOf("grant", "appops", "notification", "broadcast", "accessibility_enabled 1").none { it in line }, line)
            }
        }
    }
}
