package tv.cleanyour.core.spec

import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Profile

// The TV's Live TV app in the launcher's config.json (0.4.7). The launcher's Inputs menu shows a
// Live TV row only when live_tv_package resolves on the TV (MainActivity.liveTvIntent, no search of
// its own), and its built-in value is MediaTek's com.mediatek.wwtv.tvcenter (config.xml), so on a
// TCL, Sony, Philips or Xiaomi set the row was missing after the switch. Every push now names the
// TV's own Live TV app: the profile's (Profile.liveTv), else the first of KNOWN on the TV, and only
// one that is installed and enabled. With none, the key is not written and the launcher keeps its
// own value, which then resolves to nothing (no row), as before.
//
// hidden_apps: the launcher hides com.mediatek.wwtv.tvcenter and com.mitv.gallery by default, and a
// hidden app is dropped even from dock_apps (AppAdapter), so a Live TV tile DockRanking put in the
// dock never showed on a MediaTek set. An app the doc places (the dock and the rows under it) is
// taken out of the hidden list; the rest of the list stays, so the Live TV app that is not placed
// stays off the grid, as upstream wants (Inputs covers it). Nothing is ever added to hidden_apps.
//
// No app_labels or app_banners: the Inputs row has its own name and icon, and a placed Live TV tile
// keeps the app's own name and banner, which is what the phone's preview shows.
object LiveTvKeys {
    // The Live TV apps the bundled profiles name, most named first (LiveTvKeysTest holds it to
    // them); the generic Android ones last, so a vendor's own app wins on a TV with both.
    val KNOWN: List<PackageName> = listOf(
        "com.mediatek.wwtv.tvcenter", "com.tcl.tv", "com.sony.dtv.tvx", "com.cltv.hybrid", "org.droidtv.playtv",
        "com.xiaomi.mitv.tvplayer", "com.realtek.tv", "com.mitv.livetv", "com.amazon.tv.livetv", "com.smartdevice.livetv",
        "com.jamdeo.tv.livetv", "com.vt.tv.tvcenter", "com.heytap.tv.livetv", "com.mediatek.wwtv.tvcenter.plus",
        "net.oqee.androidtv", "com.oneplus.tv.android.livetv", "com.sony.dtv.tvlin", "com.droidlogic.android.tv",
        "com.tcl.tvinput", "com.sdmc.tv", "com.android.tv", "com.google.android.tv",
    ).map(::PackageName)

    // The TV's Live TV app: the profile's, else the first known one, installed and enabled on the TV
    // (`packages`, a snapshot's). `off`: apps the plan switches off, which do not count.
    fun pick(profile: Profile?, packages: Map<PackageName, PackageState>, off: Set<PackageName> = emptySet()): PackageName? =
        (listOfNotNull(profile?.liveTv) + KNOWN).firstOrNull { p -> p !in off && packages[p]?.let { usable(it.enabled) } == true }

    private fun usable(e: EnabledState) = e == EnabledState.ENABLED || e == EnabledState.DEFAULT

    // The doc with this TV's Live TV app, and with no app it places hidden.
    fun apply(doc: LauncherConfigDoc, liveTv: PackageName?): LauncherConfigDoc {
        val named = if (liveTv != null) doc.copy(liveTvPackage = liveTv.value) else doc
        val placed = doc.dockApps.orEmpty().toSet()
        val hidden = doc.hiddenApps ?: ConfigCodecImpl.BUILT_IN.hiddenApps.map(::PackageName)
        if (hidden.none { it in placed }) return named
        return named.copy(hiddenApps = hidden.filter { it !in placed })
    }
}
