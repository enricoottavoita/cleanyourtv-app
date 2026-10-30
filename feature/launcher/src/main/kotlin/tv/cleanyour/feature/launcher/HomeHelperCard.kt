package tv.cleanyour.feature.launcher

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tv.cleanyour.core.flows.HelperCard
import tv.cleanyour.core.flows.HelperShown
import tv.cleanyour.core.flows.HomeScreenIntent
import tv.cleanyour.ui.design.component.Banner
import tv.cleanyour.ui.design.component.BannerTone

// RFC-HELPER-1 (test build): what the TV shows about the Home button helper (PLAN 4.6), with
// "Switch off" while our service is listed. Switching it on again is Setup's, with its consent.
@Composable
internal fun ColumnScope.HomeHelperCard(card: HelperCard, enabled: Boolean, onIntent: (HomeScreenIntent) -> Unit) {
    val text = stringResource(when (card.shown) {
        HelperShown.ON -> R.string.helper_card_on
        HelperShown.OFF_BY_TV -> R.string.helper_card_off_by_tv
        HelperShown.NO_REDIRECT -> R.string.helper_card_no_redirect
        HelperShown.REDIRECT_ONLY -> R.string.helper_card_redirect_only
    })
    val tone = if (card.shown == HelperShown.ON) BannerTone.INFO else BannerTone.WARNING
    Banner(stringResource(R.string.helper_card_title) + ". " + text, tone, Modifier.padding(top = 12.dp),
        actionLabel = if (card.canSwitchOff && enabled) stringResource(R.string.helper_card_switch_off) else null,
        onAction = { if (card.canSwitchOff && enabled) onIntent(HomeScreenIntent.HelperOff) }, guardAction = true)
    if (card.shown == HelperShown.ON) {
        Body(stringResource(R.string.helper_card_escape), Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
        if (card.googleTv) Body(stringResource(R.string.helper_card_apps_only), Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp))
    }
}
