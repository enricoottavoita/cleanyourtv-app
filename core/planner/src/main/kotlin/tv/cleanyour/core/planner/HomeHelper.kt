package tv.cleanyour.core.planner

import tv.cleanyour.core.model.COMPONENT_LIST_RE
import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.ops.HelperLines
import tv.cleanyour.core.spec.HomeHelperKeys
import tv.cleanyour.core.spec.KnownLaunchers

// RFC-HELPER-1 (test build only, -Pcyt.homeHelper=true): the Home button helper. The TV keeps its own
// home screen (and its setup wizard, so casting and the Wi-Fi it saved are never touched); the
// launcher's HomeKeyService, with stock_home_redirect naming that home screen and key_filter false,
// opens the new home screen whenever the TV's own comes to the front. It watches which app is in
// front (package and class), never what is on screen and, with key_filter false, no key presses.
// research/remote-buttons/PLAN.md sections 4.3 to 4.9 are the design; this holds its safety rules:
//
// - S1 never on Fire OS or a TV that refuses other home screens, below Android 8, on a TV that
//   refuses installs, or with a launcher that is not ours (the planner checks the signer; the engine
//   the installed APK's hash when the plan installs it).
// - S2 and S9 only while Home resolves to Google's tvlauncher, leanback or launcherx, and on a
//   resolution with stock launchers only to one of them; never a Live TV app or a vendor home.
// - S3 accessibility_enabled 1 is written only when the list without ours is empty: switching it on
//   would bind every listed service, someone else's too.
// - S4 append only, compare-and-set; refused on a list the grammar does not take or that is full.
// - S5 no grant, app-op, notification listener or broadcast (the renderer has no such verb).
// - S6 and S7 the guarded section (SECTION_HELPER): its timer, the focus check, the person's answer,
//   and step R that takes our entry out again.
// - S8 another reader in the list is allowed under S3 and S4.
//
// Not decided by the owner and so not done: TCL's AUTO_START app-op (TclAppBoot may refuse to bind
// the helper; the report says so), button mapping (app_keys).
object HomeHelper {
    const val RESOURCE = "/tv/cleanyour/core/planner/home-helper"
    val BUILD_ON: Boolean by lazy { HomeHelper::class.java.getResource(RESOURCE) != null }
    val SERVICE: Component = HelperLines.SERVICE

    enum class Refusal(val rule: String, val reason: String) {
        NOT_THIS_BUILD("S0", "the Home button helper is only in the test build"),
        TV_REFUSES("S1", "this TV only allows its own home screen"),
        OLD_ANDROID("S1", "the new home screen needs Android 8 or newer"),
        NO_INSTALLS("S1", "this TV refuses app installs"),
        NOT_GOOGLE_HOME("S2", "the TV's home screen is not Google's, so the helper has nothing it knows to send on"),
        NOT_PROFILE_HOME("S2", "the TV's home screen is not one this TV's list knows"),
        LIST_UNREADABLE("S4", "the TV's accessibility list is not in a form the app can change safely"),
        LIST_FULL("S4", "the TV's accessibility list is full"),
        OTHERS_WOULD_BIND("S3", "accessibility is off on the TV and another app's service is listed; switching it on would start that one too"),
    }

    // The TV's own home screen the helper sends on from: what Home resolves to now (S2, S9).
    fun stock(snapshot: Snapshot, stockLaunchers: List<Component>): Component? {
        val home = snapshot.home.resolved ?: return null
        if (KnownLaunchers.generic.none { it.pkg == home.pkg }) return null
        if (stockLaunchers.isNotEmpty() && stockLaunchers.none { it.pkg == home.pkg }) return null
        return home
    }

    fun list(snapshot: Snapshot): String? = snapshot.settings[SettingKey.ENABLED_ACCESSIBILITY]?.takeIf { it.isNotEmpty() && it != "null" }
    fun on(snapshot: Snapshot): Boolean = snapshot.settings[SettingKey.ACCESSIBILITY_ENABLED] == "1"
    fun listed(snapshot: Snapshot): Boolean = HelperLines.listed(list(snapshot))
    // S3: accessibility must go on for the helper, and nothing else would be bound by it.
    fun needsOn(snapshot: Snapshot): Boolean = !on(snapshot)

    // The first rule that refuses the helper on this TV, or null. `refusesOtherHomes`: the planner's
    // FireOs and TclChina test for this TV.
    fun refusal(snapshot: Snapshot, stockLaunchers: List<Component>, refusesOtherHomes: Boolean, build: Boolean = BUILD_ON): Refusal? {
        if (!build) return Refusal.NOT_THIS_BUILD
        if (refusesOtherHomes) return Refusal.TV_REFUSES
        if (snapshot.facts.sdk < DefaultPlanner.TV_MIN_SDK) return Refusal.OLD_ANDROID
        if (snapshot.facts.installRefusal) return Refusal.NO_INSTALLS
        val home = snapshot.home.resolved
        if (home == null || KnownLaunchers.generic.none { it.pkg == home.pkg }) return Refusal.NOT_GOOGLE_HOME
        if (stock(snapshot, stockLaunchers) == null) return Refusal.NOT_PROFILE_HOME
        val list = list(snapshot)
        if (list != null && !COMPONENT_LIST_RE.matches(list)) return Refusal.LIST_UNREADABLE
        if (!HelperLines.listed(list) && HelperLines.entries(list).size >= HelperLines.MAX_ENTRIES) return Refusal.LIST_FULL
        if (needsOn(snapshot) && HelperLines.others(list).isNotEmpty()) return Refusal.OTHERS_WOULD_BIND
        return null
    }

    // Whether this app switched accessibility on for the helper (from 0 or unset): only then may it
    // switch it off again, and only once the list is empty. The put back after a restart writes 1
    // over 1 and does not count.
    fun switchedOnByUs(records: List<ChangeRecord>): Boolean = records.any {
        it.op == Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "1") && it.prior?.settingValue != "1"
    } && records.any { it.op is Op.AccessibilityAdd }

    // What the TV shows about the helper, for Manage and the reports (PLAN 4.6, 4.7), from the
    // snapshot alone: the list, accessibility_enabled and the overlay's stock_home_redirect.
    enum class State { ON, LISTED_OFF, REDIRECT_ONLY, LISTED_NO_REDIRECT, NONE }

    fun state(snapshot: Snapshot): State {
        val redirect = HomeHelperKeys.redirectOf(snapshot.launcherOverlay) != null
        val listed = listed(snapshot)
        return when {
            listed && on(snapshot) && redirect -> State.ON
            listed && !on(snapshot) -> State.LISTED_OFF
            listed -> State.LISTED_NO_REDIRECT
            redirect -> State.REDIRECT_ONLY
            else -> State.NONE
        }
    }
}
