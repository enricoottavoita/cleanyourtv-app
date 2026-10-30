package tv.cleanyour.feature.launcher

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.time.LocalDate
import tv.cleanyour.core.flows.AppChoice
import tv.cleanyour.core.flows.HomeChoice
import tv.cleanyour.core.flows.LookDetails
import tv.cleanyour.core.flows.LookDraft
import tv.cleanyour.core.flows.LookNote
import tv.cleanyour.core.flows.LookPreview
import tv.cleanyour.core.flows.LookReason
import tv.cleanyour.core.flows.LookRow
import tv.cleanyour.core.flows.LookRows
import tv.cleanyour.core.flows.LookSample
import tv.cleanyour.core.flows.LookSamples
import tv.cleanyour.core.flows.LookTv
import tv.cleanyour.core.flows.PictureOption
import tv.cleanyour.core.flows.StyleCard
import tv.cleanyour.core.flows.WayBack
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.spec.BandChoice
import tv.cleanyour.core.spec.BundledThemeSource
import tv.cleanyour.core.spec.ButtonsChoice
import tv.cleanyour.core.flows.PreviewBand
import tv.cleanyour.core.spec.LauncherConfigCodec
import tv.cleanyour.core.spec.LookExtras
import tv.cleanyour.core.spec.LookGates
import tv.cleanyour.core.spec.TilesChoice
import tv.cleanyour.core.spec.LookKeys
import tv.cleanyour.core.spec.Movement
import tv.cleanyour.core.spec.Picture
import tv.cleanyour.core.spec.StyleId
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityCheckOptions
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityChecker
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.checkRoboAccessibility
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import org.hamcrest.Matcher
import org.hamcrest.Matchers
import tv.cleanyour.ui.design.component.SheetSurface
import tv.cleanyour.ui.design.theme.CytTheme

// The screen-test harness of this module (T8): every state in the four renderings of W7 (dark,
// light, 200% font, right to left), 360 dp wide like the mockups, with the ATF checks on. Images
// land in src/test/screenshots (record with recordRoborazziDebug, compare with
// verifyRoborazziDebug). Rendered at xhdpi for the checks, saved at half scale to keep the
// goldens small.

enum class Variant(val dark: Boolean, val fontScale: Float, val rtl: Boolean) {
    DARK(true, 1f, false),
    LIGHT(false, 1f, false),
    FONT200(true, 2f, false),
    RTL(true, 1f, true),
}

class Shot(val name: String, val height: Dp = 780.dp, val content: @Composable () -> Unit) {
    override fun toString() = name
}

const val SHOT_TAG = "shot"

// Nothing is suppressed. A suppression here needs a reason next to it.
private val atfSuppressions: Matcher<in AccessibilityViewCheckResult> = Matchers.not(Matchers.anything())

@Composable
private fun Rendered(shot: Shot, variant: Variant) {
    val d = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(d.density, variant.fontScale),
        LocalLayoutDirection provides if (variant.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        CytTheme(darkTheme = variant.dark, reducedMotion = true) {
            Box(Modifier.testTag(SHOT_TAG).size(360.dp, shot.height).background(CytTheme.colors.bg)) { shot.content() }
        }
    }
}

@OptIn(ExperimentalRoborazziApi::class)
fun ComposeContentTestRule.shoot(shot: Shot, variant: Variant) {
    setContent { Rendered(shot, variant) }
    waitForIdle()
    val node = onNodeWithTag(SHOT_TAG)
    node.captureRoboImage(
        "src/test/screenshots/${shot.name}_${variant.name.lowercase()}.png",
        roborazziOptions = RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)),
    )
    node.checkRoboAccessibility(
        RoborazziATFAccessibilityCheckOptions(
            checker = RoborazziATFAccessibilityChecker(suppressions = atfSuppressions),
            failureLevel = RoborazziATFAccessibilityChecker.CheckLevel.Error,
        ),
    )
}

// A sheet as the modal shows it: the screen under the scrim, hidden from accessibility like the
// modal hides it, and the sheet body on SheetSurface at the bottom.
@Composable
fun SheetOver(screen: @Composable () -> Unit, sheet: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().clearAndSetSemantics {}) { screen() }
        Box(Modifier.fillMaxSize().background(CytTheme.colors.scrim).clearAndSetSemantics {})
        SheetSurface(Modifier.align(Alignment.BottomCenter).padding(top = 72.dp).verticalScroll(rememberScrollState()), content = sheet)
    }
}

// look2 Look sheet fixtures: drafts as LookDrafts makes them, with the previews made from the
// bytes the push would send (LookSamples, the real 2.7 keys and the bundled pictures) and the rows
// of More options and Advanced written out here by hand (a fake in test sources; the flows' rules
// are tested in :core:flows).
object LookFixtures {
    private val source = BundledThemeSource()
    val gallery: Map<String, Picture> = source.pictures().associateBy { it.id }
    fun resourcePath(ref: BlobRef?): String? = ref?.path?.removePrefix(BundledThemeSource.PREFIX)?.let { "/$it" }
    val pictureFiles: Map<String, ImageBitmap> by lazy {
        gallery.values.mapNotNull { resourcePath(it.wallpaper) }.distinct().associateWith { path ->
            BitmapFactory.decodeStream(LookFixtures::class.java.getResourceAsStream(path), null, BitmapFactory.Options().apply { inSampleSize = 4 })!!
                .asImageBitmap()
        }
    }
    val pictureOptions: List<PictureOption> get() = gallery.values.map { PictureOption(it.id, it.name, resourcePath(it.wallpaper), it.until, it.backgroundArgb) }

    private fun app(pkg: String, name: String) = AppChoice(PackageName(pkg), name)
    val dock4 = listOf(
        app("com.netflix.ninja", "Netflix"), app("com.google.android.youtube.tv", "YouTube"),
        app("com.amazon.amazonvideo.livingroom", "Prime Video"), app("in.startv.hotstar", "JioHotstar"),
    )

    fun choice(style: StyleId, picture: String, columns: Int, details: LookDetails, names: Boolean, movement: Movement, lift: Boolean, dock: List<AppChoice>,
        extras: LookExtras = LookExtras.DEFAULT) =
        HomeChoice(dock.map { it.pkg }, null, cards = false, picture = picture, columns = columns, style = style.id, movement = movement.id, lift = lift,
            names = names, details = details, extras = extras)

    fun preview(style: StyleId = StyleId.CLASSIC, picture: String = "mist", columns: Int = 5, details: LookDetails = LookDetails(), names: Boolean = false,
        movement: Movement = Movement.LIVELY, lift: Boolean = false, dock: List<AppChoice> = dock4, extras: LookExtras = LookExtras.DEFAULT): LookPreview {
        // A TV that reads 3.0 (versionCode 9) when the look has 3.0 switches, so the push writes them.
        val tv = LookTv(if (extras == LookExtras.DEFAULT) 8L else 9L, 2048, false)
        val sample = LookSample("shot", choice(style, picture, columns, details, names, movement, lift, dock, extras), dock.map { it.pkg }, tv)
        val p = LookSamples.preview(sample, source, source, LocalDate.of(2026, 10, 26), 20 * 60 + 30)
        return p.copy(picturePath = gallery[picture]?.let { resourcePath(it.wallpaper) }, dock = dock.map { it.name })
    }

    // The rows as the flows resolve them for the common cases (spec 7.3).
    fun rows(style: StyleId, details: LookDetails = LookDetails(), picture: String = "mist", reads27: Boolean = true, ramMb: Int? = 2048,
        movement: Movement = Movement.LIVELY, lift: Boolean = false, names: Boolean = false, resettable: Int = 0): LookRows {
        val own = LookKeys.own(style)
        val need = if (reads27) null else LookReason.NEEDS_UPDATE
        val spot = style == StyleId.SPOTLIGHT
        val all = style == StyleId.ALL_APPS
        fun <T> row(set: T?, ownV: T) = if (reads27) LookRow(set ?: ownV, true) else LookRow(ownV, false, need)
        val band = when {
            all -> LookRow<BandChoice>(null, false, LookReason.ALL_APPS_NO_BAND)
            spot -> LookRow(BandChoice.SEE_THROUGH, false, LookReason.SPOTLIGHT_BAND)
            else -> row(details.band, own.band)
        }
        val buttons = row(details.buttons, own.buttons)
        val sky = LookRow(details.sky ?: (picture == "mountains"), true)
        val liftOk = movement == Movement.LIVELY || movement == Movement.SMOOTH
        return LookRows(
            names = LookRow(names && reads27, reads27, need),
            bigName = when {
                all -> LookRow(false, false, LookReason.ALL_APPS_NO_BIG_NAME)
                spot -> LookRow(true, false, LookReason.SPOTLIGHT_BIG_NAME)
                else -> LookRow(details.bigName ?: own.bigName, true)
            },
            glow = if (spot) LookRow(null, false, visible = false) else row(details.glow, own.glow),
            spotColour = if (spot) row(details.spotColour, own.spotColour) else LookRow(false, false, visible = false),
            ring = row(details.ring, own.ring),
            band = band,
            buttons = buttons,
            greenWifi = if (buttons.value == ButtonsChoice.MENU) LookRow(own.greenWifi, false, LookReason.MENU_NO_WIFI) else row(details.greenWifi, own.greenWifi),
            dim = if (picture == "none") LookRow(0, false, LookReason.NO_PICTURE) else LookRow(details.dim ?: 0, true),
            sky = sky,
            lift = LookRow(lift && liftOk, liftOk, if (liftOk) null else LookReason.LIFT_MOVEMENT, LookGates.liftShown(ramMb, lift)),
            seeThrough = if (band.enabled && band.value == BandChoice.SEE_THROUGH) LookRow(details.seeThrough ?: 50, true)
                else LookRow(details.seeThrough ?: 50, false, LookReason.SEE_THROUGH_OFF),
            skyStrength = if (sky.value == true) LookRow(details.skyStrength ?: 55, true) else LookRow(details.skyStrength ?: 55, false, LookReason.SKY_OFF),
            resettable = resettable, festiveRing = picture == "diwali", liftSlowRamMb = ramMb?.takeIf { lift && it < 2048 },
            dimChanged = picture != "none" && details.dim != null && details.dim != 0, skyChanged = details.sky != null && details.sky != (picture == "mountains"),
        )
    }

    fun draft(
        style: StyleId = StyleId.CLASSIC, picture: String = "mist", columns: Int = 5, details: LookDetails = LookDetails(), names: Boolean = false,
        movement: Movement = Movement.LIVELY, lift: Boolean = false, dock: List<AppChoice> = dock4, waiting: Int = 0,
        reads27: Boolean = true, ramMb: Int? = 2048, wayBack: WayBack = WayBack.NONE, changed: Boolean = false, newPhoto: Boolean = false,
        note: LookNote? = null, keyNames: Boolean = false, tvChanged: Boolean = false, movementPicked: Boolean = false, resettable: Int = 0,
        previews: Boolean = true, reads30: Boolean = false, extras: LookExtras = LookExtras.DEFAULT,
        // 0.4.8: the sheet offers "Put it on the TV" (LookDraft.canSend); false is a draft the TV already has.
        canSend: Boolean = true,
    ): LookDraft {
        val e = if (reads30) extras else LookExtras.DEFAULT
        val ch = choice(style, picture, columns, details, names, movement, lift, dock, e)
        // A TV that reads 3.0: the push and Advanced's settings file as the flows make them (the look
        // keys without dock_apps and module_cards), so the file shows the 3.0 keys the look writes.
        val pushed = LookSamples.push(if (reads30) LookSample("shot", ch, ch.dock, LookTv(9L, 2048, false)) else LookSample("shot", ch, ch.dock), source, source)
        val full = pushed.second.decodeToString()
        val settings = if (reads30) LauncherConfigCodec.encode(pushed.first.copy(dockApps = null, moduleCards = null)).decodeToString() else SETTINGS
        return LookDraft(
            "default", picture, preview = if (previews) preview(style, picture, columns, details, names, movement, lift, dock, e) else null,
            columns = columns, lookColumns = columns, style = style, names = names, movement = movement, movementPicked = movementPicked, lift = lift,
            details = details,
            styleCards = StyleId.entries.map { s ->
                StyleCard(s, if (previews) preview(s, picture, columns, dock = dock) else null, reads27 || s == StyleId.CLASSIC)
            },
            rows = rows(style, details, picture, reads27, ramMb, movement, lift, names, resettable), changed = changed, newPhoto = newPhoto,
            wayBack = wayBack, note = note, styles = if (reads27) StyleId.entries else listOf(StyleId.CLASSIC), reads27 = reads27, ramMb = ramMb,
            keyNames = keyNames, settingsFile = settings, fullFile = full, apps = dock.size + waiting, waiting = waiting, tvChanged = tvChanged,
            reads30 = reads30, extras = e, unsent = canSend && !changed && !newPhoto && !tvChanged,
            actingExtras = if (reads30) acting(style, rows(style, details), e) else LookExtras.DEFAULT,
            offeredExtras = if (reads30) acting(style, rows(style, details), ALL_ON) else LookExtras.DEFAULT,
        )
    }

    // LookRules.extras as the flows apply it (internal there), for the hand-made drafts: what of
    // the 3.0 switches acts in this look.
    private val ALL_ON = LookExtras(true, true, true, true, true, true, true, TilesChoice.ICONS, tv.cleanyour.core.spec.HighlightChoice.RIM)
    private fun acting(style: StyleId, r: LookRows, e: LookExtras): LookExtras {
        val dock = style != StyleId.ALL_APPS
        val oneRow = e.oneRow && dock
        val noBand = e.noBand && dock && style != StyleId.SPOTLIGHT
        return e.copy(oneRow = oneRow, noBand = noBand, bandAll = e.bandAll && oneRow && !noBand,
            wifiWarnings = e.wifiWarnings && r.greenWifi.value == true && r.buttons.value != ButtonsChoice.MENU)
    }


    // A short look settings file, as Advanced shows it.
    private val SETTINGS = listOf(
        "{", "  \"columns\": 5,", "  \"dock_title\": true,", "  \"ambient_glow\": true,", "  \"glow_style\": \"radial\",", "  \"app_glow\": false,",
        "  \"dock_glass\": false,", "  \"dock_opacity\": 100,", "  \"wallpaper\": true,", "  \"wallpaper_dim\": 0", "}",
    ).joinToString("\n")
}
