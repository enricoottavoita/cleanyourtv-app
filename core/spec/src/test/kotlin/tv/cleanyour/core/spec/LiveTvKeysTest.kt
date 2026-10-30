package tv.cleanyour.core.spec

import org.junit.Test
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.testing.fixtures.Fixtures
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 0.4.7: live_tv_package names the TV's own Live TV app, so the launcher's Inputs menu has its Live
// TV row on a TV whose app is not MediaTek's (92 of the 173 profiles), and a Live TV tile in the dock
// is never hidden by the launcher's default hidden_apps.
class LiveTvKeysTest {
    private val unconverted = ProfileConverter.readUnconverted(File(Fixtures.repoRoot, "core/spec/upstream-feedback/unconverted.txt"))
    private val profiles = Fixtures.upstreamExamples().filter { it.folder !in unconverted }.map { DebloatTxtParser.parse(it.debloatTxt, it.folder) }
    private fun profile(id: String) = profiles.single { it.id == id }
    private fun p(s: String) = PackageName(s)
    private fun state(e: EnabledState = EnabledState.ENABLED) = PackageState(e, true, 1, null, false, false, null, false)
    private val mediatek = p("com.mediatek.wwtv.tvcenter")
    private val netflix = p("com.netflix.ninja")

    @Test fun everyProfilesLiveTvAppIsKnown() {
        val named = profiles.mapNotNull { it.liveTv }
        assertEquals(129, named.size)
        assertEquals(92, named.count { it != mediatek }, "92 are not the launcher's default")
        assertEquals(named.toSet(), named.toSet() intersect LiveTvKeys.KNOWN.toSet())
        assertEquals(LiveTvKeys.KNOWN.size, LiveTvKeys.KNOWN.toSet().size)
        assertEquals(listOf(p("com.android.tv"), p("com.google.android.tv")), LiveTvKeys.KNOWN.takeLast(2), "the generic ones last")
    }

    // The profile's own app, even with MediaTek's (the launcher's default) also on the TV.
    @Test fun tclSonyPhilipsAndXiaomiGetTheirOwnApp() {
        for ((id, app) in listOf("tcl-55c715" to "com.tcl.tv", "sony-kd-55x85j" to "com.sony.dtv.tvx", "philips-50pus8517" to "org.droidtv.playtv",
            "xiaomi-mitv-mteq0" to "com.xiaomi.mitv.tvplayer")) {
            val pr = profile(id)
            assertEquals(p(app), pr.liveTv, id)
            val tv = mapOf(p(app) to state(), mediatek to state(), netflix to state())
            assertEquals(p(app), LiveTvKeys.pick(pr, tv), id)
            val doc = LiveTvKeys.apply(LauncherConfigDoc(dockApps = listOf(netflix, p(app))), LiveTvKeys.pick(pr, tv))
            assertEquals(app, doc.liveTvPackage, id)
            assertNull(doc.hiddenApps, "$id: nothing placed is hidden, so the launcher's list stays")
            val bytes = LauncherConfigCodec.encode(doc).decodeToString()
            assertTrue("\"live_tv_package\": \"$app\"" in bytes, bytes)
            // Switched off on the TV, or in this Setup: never named; the next known app on the TV is.
            assertEquals(mediatek, LiveTvKeys.pick(pr, tv + (p(app) to state(EnabledState.DISABLED_USER))), id)
            assertEquals(mediatek, LiveTvKeys.pick(pr, tv, off = setOf(p(app))), id)
            assertNull(LiveTvKeys.pick(pr, tv - mediatek, off = setOf(p(app))), id)
        }
    }

    // A MediaTek set (no profile): its tvcenter is named outright, and as a dock tile it leaves the
    // launcher's hidden list, which keeps com.mitv.gallery. Not placed, it stays hidden (Inputs has it).
    @Test fun aMediatekTvKeepsItsDockTile() {
        val tv = mapOf(mediatek to state(EnabledState.DEFAULT), p("com.android.tv") to state(), netflix to state())
        assertEquals(mediatek, LiveTvKeys.pick(null, tv), "a vendor app before Android's own")
        val docked = LiveTvKeys.apply(LauncherConfigDoc(dockApps = listOf(netflix, mediatek)), mediatek)
        assertEquals("com.mediatek.wwtv.tvcenter", docked.liveTvPackage)
        assertEquals(listOf(p("com.mitv.gallery")), docked.hiddenApps)
        val effective = LauncherConfigCodec.effective(docked, LauncherConfigCodec.builtInDefaults)
        assertTrue(mediatek.value !in effective.hiddenApps)
        val notDocked = LiveTvKeys.apply(LauncherConfigDoc(dockApps = listOf(netflix)), mediatek)
        assertNull(notDocked.hiddenApps)
        // A hidden list the doc already has loses only the placed app.
        assertEquals(listOf(p("com.example.ad")), LiveTvKeys.apply(LauncherConfigDoc(dockApps = listOf(mediatek), hiddenApps = listOf(mediatek, p("com.example.ad"))), null).hiddenApps)
    }

    // No Live TV app on the TV (a streaming box): nothing is written, the launcher keeps its own.
    @Test fun noLiveTvAppWritesNothing() {
        val box = profiles.first { it.liveTv == null }
        val tv = mapOf(netflix to state())
        assertNull(LiveTvKeys.pick(box, tv))
        assertNull(LiveTvKeys.pick(null, tv))
        assertNull(LiveTvKeys.pick(profile("tcl-55c715"), tv), "the profile's app not installed")
        val doc = LauncherConfigDoc(dockApps = listOf(netflix), columns = 5)
        assertEquals(doc, LiveTvKeys.apply(doc, null))
    }
}
