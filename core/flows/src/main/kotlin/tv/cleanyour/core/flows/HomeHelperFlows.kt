package tv.cleanyour.core.flows

import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.planner.HomeHelper
import tv.cleanyour.core.spec.HomeHelperKeys
import tv.cleanyour.core.spec.KnownLaunchers
import tv.cleanyour.core.spec.LauncherConfigDoc

// RFC-HELPER-1 (test build): the Home button helper in Manage (PLAN 4.6). Its state comes from the
// newest snapshot alone: the two accessibility settings and the overlay's stock_home_redirect.
// Whether Android has the service bound is not read (no dumpsys accessibility); "listed with
// accessibility on" stands for it, and the Home press of the next Setup or the person's remote is
// the real check.
//
// ON: listed, accessibility on, the overlay names the TV's own home screen. "Switch off".
// OFF_BY_TV: listed, accessibility off (a restart or a TV that dropped it). "Switch off" tidies the
// list; switching it back on goes through Setup's consent again.
// NO_REDIRECT: listed and on, but the look on the TV names no home screen (another tool sent one):
// the helper does nothing. "Switch off".
// REDIRECT_ONLY: the look names one but the helper is not listed (switched off on the TV, a force
// stop, a cleaner app): Setup again switches it back on.
enum class HelperShown { ON, OFF_BY_TV, NO_REDIRECT, REDIRECT_ONLY }

// `googleTv`: the TV's own home screen is Google TV's (launcherx), for the Apps only mode tip.
data class HelperCard(val shown: HelperShown, val googleTv: Boolean) {
    val canSwitchOff: Boolean get() = shown != HelperShown.REDIRECT_ONLY
}

object HomeHelperFlows {
    // Null when nothing about the helper is on this TV, or the build has no helper.
    fun card(snap: Snapshot?, build: Boolean): HelperCard? {
        if (!build || snap == null) return null
        val googleTv = HomeHelperKeys.redirectOf(snap.launcherOverlay) == KnownLaunchers.LAUNCHERX.pkg ||
            snap.home.resolved?.pkg == KnownLaunchers.LAUNCHERX.pkg
        val shown = when (HomeHelper.state(snap)) {
            HomeHelper.State.ON -> HelperShown.ON
            HomeHelper.State.LISTED_OFF -> HelperShown.OFF_BY_TV
            HomeHelper.State.LISTED_NO_REDIRECT -> HelperShown.NO_REDIRECT
            HomeHelper.State.REDIRECT_ONLY -> HelperShown.REDIRECT_ONLY
            HomeHelper.State.NONE -> return null
        }
        return HelperCard(shown, googleTv)
    }

    // A look sent while our HomeKeyService is listed keeps the helper's two keys from the TV's
    // overlay (HomeHelperKeys.carry), so a new look never leaves the helper filtering keys with no
    // redirect. In every build: it keeps what the TV has, it never adds a key the TV did not have.
    // 0.4.7 (RFC-THEME-1): where the look goes through the launcher's ThemeProvider the file is the
    // launcher's, and a TV that locks the shell out of its folder gives the probe no overlay. With our
    // service listed the TV's own home screen is still Home, so a Google one there is the redirect to
    // keep; a look must never drop it only because the shell could not read the last one.
    suspend fun carry(d: ManageDeps, tvId: TvId): (LauncherConfigDoc) -> LauncherConfigDoc {
        val snap = attempt { d.snapshots.latest(tvId) }.getOrNull() ?: return { it }
        val listed = HomeHelper.listed(snap)
        val unread = snap.launcherOverlay == null
        val home = snap.home.resolved?.pkg?.takeIf { p -> KnownLaunchers.generic.any { it.pkg == p } }
        return { doc ->
            if (listed && unread && home != null && HomeHelperKeys.REDIRECT !in doc.unknown) HomeHelperKeys.with(doc, home)
            else HomeHelperKeys.carry(doc, snap.launcherOverlay, listed)
        }
    }
}
