package tv.cleanyour.testing.fixtures

import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.SnapshotReason

// The TV of issue #2028, as plain data from its problem report (nothing here came from a TV): a Jio
// JMSB200A set-top box, device MSTB, Android 11 (SDK 30), build Jio/4KOTTSTB/MSTB:11/25.1.9/1, no
// Google Play services, "Profile: none", "Home switch: planned". Home resolves to the operator's own
// launcher, a system app claiming it at priority 2; our home screen (on the TV from an earlier try)
// claims it at 0, two apps the report does not name at 0, and TV Settings' FallbackHome at -1000.
// 0.4.6.1 planned the switch with tvlauncher as the stock home and switched it off at step 8, so one
// of the two unnamed claimants is tvlauncher (unnamed because its system flag read false, as after an
// update under /data); the other, an app the person installed, stands in here as `personal`. The
// SetHome of ours failed its check (Home stayed Jio's), and step R's SetHome of tvlauncher then failed
// its check on every Carry on: "expected com.google.android.tvlauncher/.MainActivity, got
// com.iwedia.ui.rjio.launcher.reliance/com.iwedia.ui.rjio.launcher.ui.intro.IntroActivity".
object Issue2028 {
    val jio = Component(PackageName("com.iwedia.ui.rjio.launcher.reliance"), "com.iwedia.ui.rjio.launcher.ui.intro.IntroActivity")
    val tvlauncher = Component(PackageName("com.google.android.tvlauncher"), ".MainActivity")
    val ours = Component(PackageName("com.gathin.tvhome"), ".MainActivity")
    val personal = Component(PackageName("com.example.personal.home"), ".MainActivity")
    val fallbackHome = Component(PackageName("com.android.tv.settings"), ".system.FallbackHome")

    const val FINGERPRINT = "Jio/4KOTTSTB/MSTB:11/25.1.9/1:user/release-keys"

    // "Brand: Jio, Manufacturer: Jio, Model: JMSB200A, Device: MSTB, Android: 11 (SDK 30)".
    fun facts(): DeviceFacts = Samples.miTvFacts().copy(
        brand = "Jio", manufacturer = "Jio", model = "JMSB200A", device = "MSTB", sdk = 30, release = "11",
        fingerprint = FINGERPRINT, hasGms = false,
    )

    private fun app(system: Boolean, signer: tv.cleanyour.core.model.Sha256? = null) =
        PackageState(EnabledState.ENABLED, system = system, versionCode = 1, signer = signer, persistent = false, tvInput = false, label = null, installedByUs = false)

    // The probe's claimants, as "Claimant:" lines in the report give them.
    val home: HomeResolution = HomeResolution(jio, listOf(
        HomeClaimant(jio, 2), HomeClaimant(ours, 0), HomeClaimant(tvlauncher, 0), HomeClaimant(personal, 0), HomeClaimant(fallbackHome, -1000),
    ))

    // `ourSigner`: the key our home screen on the TV is signed with (null: the probe did not read it).
    fun snapshot(reason: SnapshotReason = SnapshotReason.BASELINE, ourSigner: tv.cleanyour.core.model.Sha256? = null): Snapshot = Samples.snapshot(
        facts = facts(), reason = reason,
        packages = mapOf(
            PackageName("android") to app(system = true),
            PackageName("com.android.tv.settings") to app(system = true),
            jio.pkg to app(system = true),
            tvlauncher.pkg to app(system = false),
            personal.pkg to app(system = false),
            ours.pkg to app(system = false, signer = ourSigner),
        ),
        home = home,
    )
}
