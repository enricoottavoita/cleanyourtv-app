package tv.cleanyour.core.flows

import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.FileRoute
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.TvId

// 0.4.8 (PLAN item 2): "Tell us it worked". After the Done restart check, and in Manage once a restart held, one tap
// opens the confirmation's preview (ReportKind.CONFIRM): the block the issue bot counts (TvReport.confirmation), then
// GitHub's form with it filled in (IssueLinks.confirmation). The app sends nothing: the browser opens the link and the
// person posts it. A restart that failed offers "Report a problem" instead, whose report has the same Restarts lines.
object ItWorked {
    // The block's format version, its first line after the title line; the bot reads both.
    const val FORMAT = 1
    const val TITLE_PREFIX = "Confirmation: "

    // The card's fixed name in a problem report for a restart that failed (ReportContext.case).
    const val RESTART_FAILED = "RESTART_FAILED"

    // The look's route as a report says it: the last look this app sent to the TV (config.json in the launcher's
    // folder), by the launcher's ThemeProvider or a sync push, or none.
    fun lookRoute(changes: List<ChangeRecord>): String {
        val last = changes.lastOrNull { c -> (c.op as? Op.PushAsset)?.dest?.let { it.dir == RemoteDir.OVERLAY && it.segments.last() == "config.json" } == true }
            ?: return "none"
        return if ((last.op as Op.PushAsset).route == FileRoute.THEME_PROVIDER) "provider" else "push"
    }

    // What the reports carry for `tvId`: its path and count, or null when this app has not switched it, or has
    // counted nothing for it yet (no read on a path).
    suspend fun facts(d: ManageDeps, tvId: TvId): RestartFacts? {
        val tally = attempt { d.restarts.load(tvId) }.getOrNull() ?: return null
        val path = tally.path ?: return null
        return RestartFacts(path, tally, lookRoute(attempt { d.ledger.changes(tvId) }.getOrDefault(emptyList())))
    }
}
