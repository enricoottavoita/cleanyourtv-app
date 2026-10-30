package tv.cleanyour.core.flows

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.LauncherState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.spec.BundledThemeSource
import tv.cleanyour.core.spec.ButtonsChoice
import tv.cleanyour.core.spec.DecodeResult
import tv.cleanyour.core.spec.DockLayers
import tv.cleanyour.core.spec.LauncherConfigCodec
import tv.cleanyour.core.spec.LiveTvKeys
import tv.cleanyour.core.spec.LookGates
import tv.cleanyour.core.spec.Looks
import tv.cleanyour.core.spec.Movement
import tv.cleanyour.core.spec.StyleId
import tv.cleanyour.testing.fixtures.FakeClock
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// look2 migration (spec 8.2, C16): a saved 0.4.0 look keeps sending its 0.4.0 bytes, recorded from
// baseSha's LookDocs.of and the codec into src/test/resources/legacy-looks before look2 changed
// anything, until the person sends a new look; its draft opens from the migration table; the first
// send writes the new form with columns= always.
class LookMigrationTest {
    private val looks = BundledThemeSource()
    private val drafts = LookDrafts(looks, looks, ConfigEncoder.Codec, FakeClock(Samples.NOW_MS))

    private fun fixture(file: String): String =
        assertNotNull(javaClass.getResourceAsStream("/legacy-looks/$file"), "fixture $file").use { it.readBytes() }.decodeToString()

    // 0.4.7: a Manage push also names the harness TV's Live TV app (Samples' com.android.tv), every
    // other byte the frozen 0.4.0 path's (LookCarry, LiveTvKeys).
    private fun live(json: String): String =
        LauncherConfigCodec.encode(LiveTvKeys.apply(assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(json.encodeToByteArray())).doc,
            PackageName("com.android.tv"))).decodeToString()

    // Every fixture, through the frozen path (LegacyLook) and through a saved legacy HomeChoice as
    // every push of it encodes it (LookDrafts.docOf): JSON text equal, so a diff names the key.
    @Test fun aSavedLegacyLookKeepsItsBytes() {
        assertEquals(510, LegacyCases.all.size)
        for (c in LegacyCases.all) {
            val want = fixture(c.file)
            val (doc, _) = LegacyLook.docOf(c.theme(), c.dock, cards = false, c.picture(), c.columns)
            assertEquals(want, LauncherConfigCodec.encode(doc).decodeToString(), c.file)
            val choice = HomeChoice(c.dock, c.id, cards = false, picture = c.pictureId, dim = LegacyCases.MINE_DIM.takeIf { c.pictureId == LookPicture.MINE_ID },
                columns = c.columns)
            for (tv in listOf(LookTv(8L, 2048, true), LookTv(5L, 1024, false), LookTv())) {
                val (saved, _) = drafts.docOf(choice, c.dock, cards = false, LegacyCases.mine, tv)
                assertEquals(want, LauncherConfigCodec.encode(saved).decodeToString(), "${c.file} saved, on $tv")
            }
        }
    }

    // The draft of a saved look opens from the migration table, with its apps in a row and picture.
    @Test fun theDraftOpensFromTheMigrationTable() {
        val tv = LookTv(8L, 2048, true)
        val want = mapOf(
            "default" to Triple(StyleId.CLASSIC, LookPicture.NONE_ID, 4), "aurora" to Triple(StyleId.CLASSIC, "aurora", 4),
            "six-columns" to Triple(StyleId.CLASSIC, LookPicture.NONE_ID, 6), "cinema" to Triple(StyleId.SPOTLIGHT, "curtains", 5),
            "console" to Triple(StyleId.CLASSIC, "cobalt", 7), "horizon" to Triple(StyleId.CALM, "mountains", 6),
            "quiet" to Triple(StyleId.CALM, LookPicture.NONE_ID, 6), "diwali" to Triple(StyleId.CLASSIC, "diwali", 5),
            "library" to Triple(StyleId.ALL_APPS, "lamp", 5), "arcade" to Triple(StyleId.CLASSIC, "neon", 6), "mist" to Triple(StyleId.CLASSIC, "mist", 5),
        )
        assertEquals(LegacyCases.ids.toSet(), want.keys)
        for ((id, w) in want) {
            val d = drafts.open(HomeChoice(LegacyCases.fifteen.take(4), id), LookContext(tv = tv))
            assertEquals(w, Triple(d.style, d.picture, d.columns), id)
            assertEquals(id, d.legacyThemeId)
            assertEquals(id, d.themeId)
            assertEquals(false, d.changed, id)
            assertEquals(if (id in setOf("quiet", "library")) Movement.QUICK else Movement.LIVELY, d.movement, id)
            assertEquals(id in setOf("cinema", "console", "diwali", "mist"), d.lift, id)
            val withColumns = drafts.open(HomeChoice(LegacyCases.fifteen.take(4), id, columns = if (w.third == 8) 4 else 8, picture = "glass"), LookContext(tv = tv))
            assertEquals(if (w.third == 8) 4 else 8, withColumns.columns, "$id: the saved apps in a row")
            assertEquals(if (Looks.dated(id)) w.second else "glass", withColumns.picture, "$id: the saved picture")
        }
        // Cinema keeps App colour across the top off; Horizon and Quiet keep buttons with names;
        // Library keeps its ring off.
        val cinema = drafts.open(HomeChoice(emptyList(), "cinema"), LookContext(tv = tv))
        assertEquals(false, cinema.rows?.spotColour?.value)
        assertTrue("\"ambient_glow\": false" in cinema.settingsFile, cinema.settingsFile)
        assertEquals(ButtonsChoice.WITH_NAMES, drafts.open(HomeChoice(emptyList(), "horizon"), LookContext(tv = tv)).rows?.buttons?.value)
        assertEquals(false, drafts.open(HomeChoice(emptyList(), "library"), LookContext(tv = tv)).rows?.ring?.value)
    }

    // On a 1 to 1.5 GB TV a migrated Cinema, Console, Diwali or Mist opens with Quick and Lift and
    // zoom off, and says it was picked for this TV; Quiet black and Library already move Quick.
    @Test fun aLowMemoryTvGetsQuickOnTheLooksThatLift() {
        for (ram in listOf(1024, 1536)) for (id in LegacyCases.ids) {
            val d = drafts.open(HomeChoice(emptyList(), id), LookContext(tv = LookTv(8L, ram, true)))
            assertEquals(Movement.QUICK, d.movement, "$id at $ram")
            assertEquals(false, d.lift, "$id at $ram")
            assertEquals(id !in setOf("quiet", "library"), d.movementPicked, "$id at $ram")
        }
        val d = drafts.open(HomeChoice(emptyList(), "mist"), LookContext(tv = LookTv(8L, 2048, true)))
        assertEquals(Movement.LIVELY to true, d.movement to d.lift)
        assertEquals(false, d.movementPicked)
    }

    private val on = PackageState(EnabledState.ENABLED, false, 0, null, false, false, null, false)

    private suspend fun TestScope.home(h: Harness, choice: HomeChoice): Pair<HomeScreenMachine, TvId> {
        val tv = h.knownTv().copy(launcher = LauncherState(1, Samples.zeroHash, Samples.zeroHash, Samples.zeroHash))
        h.tvs.upsert(tv)
        h.snapshots.save(Samples.snapshot(packages = Samples.miTvPackages() + LegacyCases.fifteen.associateWith { on }, takenAtMs = Samples.NOW_MS + 1))
        HomePrefs(h.documents).save(tv.id, choice)
        val m = HomeScreenMachine(h.manageDeps(encoder = ConfigEncoder.Codec, themes = looks), backgroundScope, tv.id)
        settle()
        return m to tv.id
    }

    private fun Harness.lastPush(): String = blobs.bytes(planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().last().config).decodeToString()

    // Restore mine and a dock change on a saved legacy look send its 0.4.0 bytes, and it stays a
    // legacy choice; the first "Put it on the TV" writes the new form, with columns= always.
    @Test fun restoreMineAndADockChangeKeepTheLegacyBytesUntilTheNextSend() = runTest(timeout = 30.seconds) {
        for (columns in listOf(null, 6)) {
            val h = Harness()
            val dock = LegacyCases.fifteen.take(4)
            val (m, id) = home(h, HomeChoice(dock, "mist", cards = false, columns = columns))
            val file = "mist.cols-${columns ?: "own"}.pic-own.dock-4.json"
            m.dispatch(HomeScreenIntent.RestoreMine); settle()
            assertEquals(live(fixture(file)), h.lastPush(), "Restore mine: $file")
            m.dispatch(HomeScreenIntent.SetDock(dock)); settle()
            assertEquals(live(fixture(file)), h.lastPush(), "a dock change: $file")
            assertNull(HomePrefs(h.documents).load(id)?.style, "still the legacy choice")
            assertEquals("mist", m.state.value.themeId)
            assertEquals(StyleId.CLASSIC to "mist", m.state.value.sentStyle to m.state.value.sentPicture)
            m.dispatch(HomeScreenIntent.OpenLook); settle()
            assertEquals("mist", m.state.value.look?.legacyThemeId)
            assertEquals(columns ?: 5, m.state.value.look?.columns)
            m.dispatch(HomeScreenIntent.UseLook); settle()
            val text = assertNotNull(h.documents.read("tvs/${id.value}/home.txt") { it.decodeToString() })
            assertTrue("\nstyle=classic\n" in text && "\ncolumns=${columns ?: 5}\n" in text, text)
            assertNotEquals(live(fixture(file)), h.lastPush(), "the new form")
            val back = assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(h.lastPush().encodeToByteArray()))
            assertEquals(emptyList(), back.warnings)
            // This 1 GB TV gets Quick with Lift and zoom off.
            assertEquals(listOf(true, true, false), listOf(back.doc.animations, back.doc.reducedMotion, back.doc.advancedEffects))
            assertEquals(columns ?: 5, back.doc.columns)
            // After it, Restore mine sends the new form again, byte for byte.
            val sent = h.lastPush()
            m.dispatch(HomeScreenIntent.RestoreMine); settle()
            assertEquals(sent, h.lastPush())
        }
    }

    // The emulator matrix: every legacy-* case is the 0.4.0 fixture; next-* is the machine's first
    // send of the untouched migrated draft on a 2 GB TV; every case decodes with no warning and is
    // under 64 KB.
    @Test fun theSampleMatrixIsWhatThePushesSend() = runTest(timeout = 60.seconds) {
        val names = LookSamples.matrix.map { it.name }
        assertEquals(names.size, names.toSet().size, "unique names")
        for (n in listOf("style-classic-mist", "d-spot-ring-off", "adv-lift-on", "movement-off-diwali", "cols-4-spotlight-dock15", "legacy-console-cols5",
            "next-console-cols5")) assertTrue(n in names, n)
        for (s in LookSamples.matrix) {
            val (_, bytes, _) = LookSamples.push(s, looks, looks)
            assertTrue(bytes.size < 64 * 1024, s.name)
            assertEquals(emptyList(), assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(bytes)).warnings, s.name)
        }
        for (id in LegacyCases.ids) {
            val s = LookSamples.matrix.single { it.name == "legacy-$id" }
            assertEquals(fixture("$id.cols-own.pic-own.dock-4.json"), LookSamples.push(s, looks, looks).second.decodeToString(), s.name)
        }
        assertEquals(fixture("console.cols-5.pic-own.dock-4.json"),
            LookSamples.push(LookSamples.matrix.single { it.name == "legacy-console-cols5" }, looks, looks).second.decodeToString())
        // next-mist is what the Look sheet sends for an untouched migrated Mist on a 2 GB TV.
        val h = Harness()
        val tv = h.knownTv().copy(launcher = LauncherState(1, Samples.zeroHash, Samples.zeroHash, Samples.zeroHash))
        h.tvs.upsert(tv)
        val facts = Samples.miTvFacts().copy(ramMb = 2048)
        h.snapshots.save(Samples.snapshot(facts = facts, packages = Samples.miTvPackages() + LookSamples.dock4.associateWith { on }, takenAtMs = Samples.NOW_MS + 1))
        HomePrefs(h.documents).save(tv.id, HomeChoice(LookSamples.dock4, "mist", cards = false))
        val m = HomeScreenMachine(h.manageDeps(encoder = ConfigEncoder.Codec, themes = looks), backgroundScope, tv.id)
        settle()
        m.dispatch(HomeScreenIntent.OpenLook); settle()
        m.dispatch(HomeScreenIntent.UseLook); settle()
        val next = LookSamples.matrix.single { it.name == "next-mist" }
        assertEquals(live(LookSamples.push(next, looks, looks).second.decodeToString()), h.lastPush())
        assertEquals(next.choice.copy(dock = LookSamples.dock4), HomePrefs(h.documents).load(tv.id)?.copy(previousPicture = null))
    }

    // Every style with every picture at every apps in a row, with docks of 0, 4 and 24 apps,
    // encodes under 64 KB with no codec warning.
    @Test fun everyStylePictureAndCountEncodesUnder64Kb() {
        val pictures = listOf(LookPicture.NONE_ID, LookPicture.MINE_ID) + looks.pictures().map { it.id }
        val ctx = LookContext(mine = LookPicture.Of(BlobRef(Sha256("2".repeat(64)), 10, "/blobs/mine.jpg"), 30), tv = LookTv(8L, 2048, true))
        for (style in StyleId.entries) for (p in pictures) for (c in DockLayers.MIN_OFFERED_COLUMNS..DockLayers.MAX_OFFERED_COLUMNS) for (n in listOf(0, 4, 24)) {
            val dock = List(n) { PackageName("com.example.someone.with.a.long.name.app$it") }
            val choice = HomeChoice(dock, null, cards = true, picture = p, dim = 30, columns = c, style = style.id, movement = Movement.LIVELY.id, lift = true, names = true)
            val (doc, _) = drafts.docOf(choice, dock, cards = true, ctx.mine?.ref, ctx.tv)
            val bytes = LauncherConfigCodec.encode(doc)
            val label = "${style.id} $p $c $n"
            assertTrue(bytes.size < 64 * 1024, label)
            assertEquals(emptyList(), assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(bytes)).warnings, label)
        }
        assertEquals(StyleId.entries, LookGates.styles(8L))
    }
}

// The legacy cases: the 11 ids, with apps in a row absent and each of 4 to 8 other than the look's
// own, with the picture absent, none, aurora, ruska and the person's photo at dim 30 (a dated look
// only with its own picture), each with a dock of 4 and of 15 apps.
internal object LegacyCases {
    val ids = listOf("default", "aurora", "six-columns", "cinema", "console", "horizon", "quiet", "diwali", "library", "arcade", "mist")
    val pictures = listOf<String?>(null, LookPicture.NONE_ID, "aurora", "ruska", LookPicture.MINE_ID)
    const val MINE_DIM = 30
    val mine = BlobRef(Sha256("1".repeat(64)), 1234, "/blobs/mine.jpg")

    val fifteen = listOf(
        "com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "in.startv.hotstar",
        "com.spotify.tv.android", "com.plexapp.android", "com.sonyliv", "com.graymatrix.did", "com.jio.media.ondemand",
        "com.example.tvbrowser", "org.videolan.vlc", "tv.twitch.android.app", "com.disney.disneyplus",
        "com.apple.atve.androidtv.appletv", "com.zattoo.player",
    ).map(::PackageName)

    private val source = BundledThemeSource()

    class Case(val id: String, val columns: Int?, val pictureId: String?, val dock: List<PackageName>) {
        val file: String get() = "$id.cols-${columns ?: "own"}.pic-${pictureId ?: "own"}.dock-${dock.size}.json"
        fun theme() = source.bundled().single { it.id == id }
        fun picture(): LookPicture? =
            if (pictureId == LookPicture.MINE_ID) LookPicture.Of(mine, MINE_DIM) else LookPicture.of(pictureId, source)
    }

    val all: List<Case> by lazy {
        ids.flatMap { id ->
            val own = DockLayers.columnsOf(source.bundled().single { it.id == id }.doc)
            val columns = listOf<Int?>(null) + (4..8).filter { it != own }
            val pics = if (Looks.dated(id)) listOf<String?>(null) else pictures
            columns.flatMap { c -> pics.flatMap { p -> listOf(fifteen.take(4), fifteen).map { d -> Case(id, c, p, d) } } }
        }
    }
}
