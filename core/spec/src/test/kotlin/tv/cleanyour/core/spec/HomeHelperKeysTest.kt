package tv.cleanyour.core.spec

import kotlinx.serialization.json.JsonPrimitive
import tv.cleanyour.core.model.PackageName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

// RFC-HELPER-1 (test build): the two config.json keys of the Home button helper. The codec writes
// them after the modelled keys, reads them back without a warning when the redirect names a Google
// TV home screen, and warns on any other redirect (a hostile overlay's) and on mapped buttons that
// key_filter false leaves unread (PLAN 4.10).
class HomeHelperKeysTest {
    private val launcherx = KnownLaunchers.LAUNCHERX.pkg
    private val tvlauncher = KnownLaunchers.TVLAUNCHER.pkg

    private fun decode(text: String) = assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(text.encodeToByteArray()))

    @Test fun theKeysAreWrittenAndReadBack() {
        val doc = HomeHelperKeys.with(LauncherConfigDoc(columns = 5), launcherx)
        val bytes = LauncherConfigCodec.encode(doc)
        val text = bytes.decodeToString()
        assertTrue("\"stock_home_redirect\": \"com.google.android.apps.tv.launcherx\"" in text, text)
        assertTrue("\"key_filter\": false" in text, text)
        val back = decode(text)
        assertEquals(emptyList(), back.warnings)
        assertEquals(launcherx, HomeHelperKeys.redirectOf(back.doc))
        assertEquals(false, HomeHelperKeys.keyFilterOf(back.doc))
        assertEquals(launcherx, HomeHelperKeys.redirectOf(bytes))
        assertEquals(5, back.doc.columns, "the look's own keys stay")
    }

    @Test fun onlyGooglesHomeScreensAreEverNamed() {
        for (bad in listOf("com.vendor.home", "com.mediatek.wwtv.tvcenter", "com.amazon.tv.launcher")) {
            assertFailsWith<IllegalArgumentException> { HomeHelperKeys.with(LauncherConfigDoc(), PackageName(bad)) }
        }
        // A redirect another tool wrote to a vendor app: read, and warned about.
        val other = decode("{\"stock_home_redirect\": \" com.vendor.home \"}")
        assertTrue(other.warnings.any { it.startsWith("stock_home_redirect is not one of Google's TV home screens") }, other.warnings.toString())
        assertEquals(emptyList(), decode("{\"stock_home_redirect\": \"\"}").warnings)
        assertEquals(null, HomeHelperKeys.redirectOf(decode("{\"stock_home_redirect\": \"\"}").doc))
        assertEquals(emptyList(), decode("{\"stock_home_redirect\": \"com.google.android.tvlauncher\"}").warnings)
    }

    @Test fun mappedButtonsWithTheFilterOffAreWarnedAbout() {
        val w = decode("{\"key_filter\": false, \"app_keys\": [\"KEYCODE_PROG_RED:com.netflix.ninja\"]}").warnings
        assertTrue(w.any { it.startsWith("app_keys does nothing with key_filter false") }, w.toString())
        assertTrue(decode("{\"key_filter\": false}").warnings.isEmpty())
        assertTrue(decode("{\"key_filter\": true, \"app_keys\": [\"KEYCODE_PROG_RED:com.netflix.ninja\"]}").warnings.none { "key_filter" in it })
    }

    // A look pushed while the helper is listed keeps its keys; with the helper off they go with it,
    // and a redirect to anything but Google's home screens is never carried.
    @Test fun aLookCarriesTheKeysOnlyWhileTheHelperIsListed() {
        val overlay = LauncherConfigCodec.encode(HomeHelperKeys.with(LauncherConfigDoc(columns = 4), tvlauncher))
        val look = LauncherConfigDoc(columns = 6)
        val kept = HomeHelperKeys.carry(look, overlay, helperListed = true)
        assertEquals(JsonPrimitive("com.google.android.tvlauncher"), kept.unknown[HomeHelperKeys.REDIRECT])
        assertEquals(JsonPrimitive(false), kept.unknown[HomeHelperKeys.KEY_FILTER])
        assertEquals(6, kept.columns)
        assertEquals(look, HomeHelperKeys.carry(look, overlay, helperListed = false))
        assertEquals(look, HomeHelperKeys.carry(look, null, helperListed = true))
        val vendor = "{\"stock_home_redirect\": \"com.vendor.home\", \"key_filter\": false}".encodeToByteArray()
        assertEquals(look, HomeHelperKeys.carry(look, vendor, helperListed = true))
        // A look that sets the redirect itself keeps its own.
        val own = HomeHelperKeys.with(look, launcherx)
        assertEquals(own, HomeHelperKeys.carry(own, overlay, helperListed = true))
    }
}
