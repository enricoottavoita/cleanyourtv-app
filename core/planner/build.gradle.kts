
plugins { id("cleanyourtv.jvm") }

dependencies {
    api(project(":core:spec"))
    api(project(":core:ops"))
    testImplementation(project(":testing:fixtures"))
}

// Test build only: -Pcyt.wizardStaysAnyBrand=true (or cyt.wizardStaysAnyBrand=true in gradle.properties)
// widens R0 (HomeSwitchRisk.wizardStaysOnSafely, 0.4.6) from Xiaomi to every brand, so volunteers can
// check reboot survival on the other brands' sets with the same Home claimants before a release
// takes them. The switch is carried as a bundled marker file. Off by default: no file is generated,
// so the module's resources are byte for byte the release ones.
val wizardStaysAnyBrand = providers.gradleProperty("cyt.wizardStaysAnyBrand").map { it.toBoolean() }.getOrElse(false)
// Test build only (0.4.8 track): -Pcyt.homeHelper=true (or cyt.homeHelper=true in gradle.properties)
// bundles the marker of the Home button helper (RFC-HELPER-1, HomeHelper.BUILD_ON): the opt-in that
// keeps the TV's own home screen and lets the launcher's HomeKeyService send Home to the new one. Off
// by default: the release build bundles no marker, so it cannot offer or plan the helper.
val homeHelper = providers.gradleProperty("cyt.homeHelper").map { it.toBoolean() }.getOrElse(false)
val generatedTestBuild = layout.buildDirectory.dir("generated/testBuild")
val testBuildSwitches by tasks.registering {
    description = "Writes the test build's planner switches (cyt.wizardStaysAnyBrand, cyt.homeHelper) as bundled data"
    val on = wizardStaysAnyBrand
    val helper = homeHelper
    val out = generatedTestBuild
    inputs.property("wizardStaysAnyBrand", on)
    inputs.property("homeHelper", helper)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        if (on) File(dir, "tv/cleanyour/core/planner").apply { mkdirs() }.resolve("wizard-stays-any-brand").writeText("true\n")
        if (helper) File(dir, "tv/cleanyour/core/planner").apply { mkdirs() }.resolve("home-helper").writeText("true\n")
    }
}
sourceSets.main { resources.srcDir(testBuildSwitches) }
// HomeSwitchRiskTest checks that the bundled switch is the one this build asked for.
tasks.withType<Test>().configureEach {
    systemProperty("cyt.wizardStaysAnyBrand", wizardStaysAnyBrand.toString())
    systemProperty("cyt.homeHelper", homeHelper.toString())
}

// A hung coroutine or a deadlocked run must fail the build, not stall it.
