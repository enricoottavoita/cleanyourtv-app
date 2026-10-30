package tv.cleanyour.core.flows

import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.spec.BandChoice
import tv.cleanyour.core.spec.ButtonsChoice
import tv.cleanyour.core.spec.GlowChoice
import tv.cleanyour.testing.fixtures.MemoryDocumentStore
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// home.txt of every version loads (0.3.x, 0.4.0 with and without columns=, look2); a look2 choice
// round trips; a value that does not read loads as null.
class HomePrefsTest {
    private val id = Samples.miTvId()
    private val dock = listOf(PackageName("com.netflix.ninja"), PackageName("com.google.android.youtube.tv"))

    private suspend fun load(text: String): HomeChoice? {
        val docs = MemoryDocumentStore()
        docs.write("tvs/${id.value}/home.txt", text.encodeToByteArray())
        return HomePrefs(docs).load(id)
    }

    @Test fun filesOfEveryVersionLoad() = runTest {
        assertEquals(HomeChoice(dock.take(1), null), load("dock=com.netflix.ninja\n"), "0.3.0: the dock only")
        assertEquals(HomeChoice(dock, "aurora", cards = true), load("dock=com.netflix.ninja,com.google.android.youtube.tv\ntheme=aurora\ncards=true\n"))
        assertEquals(HomeChoice(dock.take(1), "diwali", cards = false, previousThemeId = "horizon"),
            load("dock=com.netflix.ninja\ntheme=diwali\ncards=false\nprevious=horizon\n"), "0.3.x")
        assertEquals(HomeChoice(dock.take(1), "cinema", cards = false, picture = "mine", dim = 40),
            load("dock=com.netflix.ninja\ntheme=cinema\ncards=false\npicture=mine\ndim=40\n"), "0.4.0 without columns=")
        assertEquals(HomeChoice(dock.take(1), "console", cards = false, picture = "glass", columns = 5),
            load("dock=com.netflix.ninja\ntheme=console\ncards=false\npicture=glass\ncolumns=5\n"), "0.4.0 with columns=")
        for (old in listOf("dock=com.netflix.ninja\ntheme=console\n", "dock=com.netflix.ninja\ntheme=console\ncolumns=6\n")) {
            assertEquals(false, load(old)?.isNew, "a 0.4.0 file is a legacy choice")
        }
    }

    // 0.4.7: a dock the app picked is the one "dockchosen=false" line; a file without it (every file
    // before 0.4.7, and a dock the person set) loads as chosen, so its pushes keep fill_dock false.
    @Test fun aPickedDockIsOneLineAndOlderFilesLoadAsChosen() = runTest {
        assertEquals(true, load("dock=com.netflix.ninja\nstyle=classic\n")?.dockChosen)
        assertEquals(false, load("dock=com.netflix.ninja\nstyle=classic\ndockchosen=false\n")?.dockChosen)
        val docs = MemoryDocumentStore()
        HomePrefs(docs).save(id, HomeChoice(dock, null, dockChosen = false))
        assertEquals("dock=com.netflix.ninja,com.google.android.youtube.tv\ntheme=\ndockchosen=false\n", docs.read("tvs/${id.value}/home.txt") { it.decodeToString() })
        HomePrefs(docs).save(id, HomeChoice(dock, null))
        assertEquals("dock=com.netflix.ninja,com.google.android.youtube.tv\ntheme=\n", docs.read("tvs/${id.value}/home.txt") { it.decodeToString() })
        assertEquals(false, HomeChoice(dock, "mist", dockChosen = false).withTheme("aurora").dockChosen, "a new look keeps who made the dock")
    }

    @Test fun aLook2ChoiceRoundTrips() = runTest {
        val docs = MemoryDocumentStore()
        val prefs = HomePrefs(docs)
        val choice = HomeChoice(dock, null, cards = true, picture = "diwali", dim = null, columns = 8, style = "spotlight", movement = "quick", lift = true,
            names = true, previousPicture = "mist", details = LookDetails(bigName = false, glow = GlowChoice.FROM_TOP, spotColour = false, ring = true,
                band = BandChoice.SEE_THROUGH, seeThrough = 70, buttons = ButtonsChoice.MENU, greenWifi = true, dim = 40, sky = true, skyStrength = 85))
        prefs.save(id, choice)
        assertEquals(choice, prefs.load(id))
        val text = docs.read("tvs/${id.value}/home.txt") { it.decodeToString() }!!
        for (line in listOf("style=spotlight", "movement=quick", "lift=true", "names=true", "prevpic=mist", "columns=8", "d.bigname=false", "d.glow=top",
            "d.spotcolour=false", "d.ring=true", "d.band=see", "d.see=70", "d.buttons=menu", "d.wifi=true", "d.dim=40", "d.sky=true", "d.skystr=85")) {
            assertTrue("\n$line\n" in text, "$line in $text")
        }
        assertTrue(text.startsWith("dock=com.netflix.ninja,com.google.android.youtube.tv\ntheme=\n"), "older versions read the dock and the Default look")
        val bare = HomeChoice(dock, null, picture = "none", columns = 4, style = "classic", movement = "lively", lift = false, names = false)
        prefs.save(id, bare)
        assertEquals(bare, prefs.load(id))
        assertEquals(HomePrefs.decode(HomePrefs.encode(choice)), choice)
    }

    @Test fun badValuesLoadAsNull() = runTest {
        val c = load(listOf("dock=com.netflix.ninja,not a package", "theme=", "cards=maybe", "picture=", "dim=95", "columns=9", "style=fancy",
            "movement=zoom", "lift=1", "names=yes", "prevpic=", "d.bigname=no", "d.glow=pink", "d.spotcolour=", "d.ring=TRUE", "d.band=lace",
            "d.see=40", "d.buttons=many", "d.wifi=2", "d.dim=-1", "d.sky=on", "d.skystr=10").joinToString("\n", postfix = "\n"))
        assertEquals(HomeChoice(dock.take(1), null), c)
        assertEquals(LookDetails(), c?.details)
        assertEquals(30, load("d.see=30\nd.dim=0\nd.skystr=20\n")?.details?.let { it.seeThrough })
        assertEquals(LookDetails(seeThrough = 30, dim = 0, skyStrength = 20), load("d.see=30\nd.dim=0\nd.skystr=20\n")?.details)
    }
}
