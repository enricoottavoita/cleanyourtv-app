package tv.cleanyour.core.flows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tv.cleanyour.core.model.TvId

// 0.3.2 B1 and B2: the report preview. "Help add this TV" (TV) is the TV report for a TV with no exact
// profile; "Report a problem" (PROBLEM) is the redacted diagnostics log with a short TV summary. Both
// are made from what this phone stored, so the TV need not be on. The screen shows the exact text,
// then copies it and opens the GitHub form, or hands it to the share sheet. Nothing is sent by the
// app: the clipboard, the browser and the share sheet are the person's.
// 0.4.8: CONFIRM ("confirm") is "Tell us it worked", the confirmation of a TV whose restarts this phone counted
// (ItWorked); it needs a TV, like TV.
enum class ReportKind(val key: String) {
    TV("tv"), PROBLEM("problem"), CONFIRM("confirm");

    companion object { fun of(key: String): ReportKind? = entries.firstOrNull { it.key == key } }
}

data class ReportState(
    val kind: ReportKind,
    val loading: Boolean = true,
    // What the preview shows and the GitHub form gets (in the link, or from the clipboard).
    val text: String = "",
    val url: String = "",
    // False when there is nothing to report from (TV: this phone has no snapshot of the TV).
    val available: Boolean = true,
    // 0.3.3 D1: the report rides in the link, so the form opens with it filled in. False: too long
    // for the link, the person pastes it, and the screen says so before GitHub opens.
    val inLink: Boolean = false,
    // What Share sends: the TV report with one package per line (TvReport.text); the problem report
    // as shown. Empty means the shown text.
    val shareText: String = "",
    // 0.4.6: the link holds a shorter problem report (ProblemReports.fit: fewer log lines); the
    // whole one is shown, copied and shared.
    val shortened: Boolean = false,
    // 0.4.7 (must 4): "Help add this TV" remembers the report it sent (SentReports): when, and the
    // issue once the person pasted its link. Null before the first send. `issueError`: the pasted
    // text is not a link to an issue of this app.
    val sent: SentReport? = null,
    val issueError: Boolean = false,
)

sealed interface ReportIntent {
    data object CopyAndOpen : ReportIntent
    data object Share : ReportIntent
    // 0.4.7: the issue the TV report went to, pasted by the person; empty forgets it.
    data class SaveIssue(val url: String) : ReportIntent
    data object OpenIssue : ReportIntent
}

class ReportMachine(
    private val d: ManageDeps,
    scope: CoroutineScope,
    kind: ReportKind,
    private val tvId: TvId?,
) : Machine<ReportState, ReportIntent, ManageEffect>(ReportState(kind), scope) {

    private data class Loaded(val text: String, val url: String, val available: Boolean, val inLink: Boolean = false, val share: String = "", val shortened: Boolean = false,
                              val sent: SentReport? = null)
    private data class Sent(val r: SentReport?, val error: Boolean = false)

    init { scope.launch { send(build(kind)) } }

    override fun reduce(state: ReportState, event: Any): ReportState = when (event) {
        is Loaded -> state.copy(loading = false, text = event.text, url = event.url, available = event.available, inLink = event.inLink, shareText = event.share,
            shortened = event.shortened, sent = event.sent)
        is Sent -> state.copy(sent = event.r ?: state.sent, issueError = event.error)
        else -> state
    }

    override fun handle(event: Any, before: ReportState, after: ReportState) {
        if (after.loading || !after.available) return
        when (event) {
            // 0.3.3 D1: copied also when the report is in the link, in case the form opens without it
            // (a sign-in on the way can drop it). The screen says which way it goes.
            ReportIntent.CopyAndOpen -> { effect(ManageEffect.Copy(after.text)); effect(ManageEffect.OpenUrl(after.url)); remember(); told() }
            ReportIntent.Share -> { effect(ManageEffect.Share(after.shareText.ifEmpty { after.text })); remember(); told() }
            is ReportIntent.SaveIssue -> scope.launch { saveIssue(event.url) }
            ReportIntent.OpenIssue -> after.sent?.issueUrl?.let { effect(ManageEffect.OpenUrl(it)) }
            else -> {}
        }
    }

    // 0.4.7: a TV report that left the phone (copied and opened, or shared) is remembered for the
    // TV, with today's date; the issue link it went to is kept once the person pastes it. Sending
    // again keeps the link and moves the date.
    private fun remember() {
        val id = tvId ?: return
        if (state.value.kind != ReportKind.TV) return
        scope.launch {
            val r = SentReport(d.clock.nowMs(), state.value.sent?.issueUrl)
            attempt { d.sentReports.save(id, r) }.onSuccess { send(Sent(r)) }
        }
    }

    // 0.4.8: a confirmation that left the phone is remembered with the count it carried, so Manage says so.
    private fun told() {
        val id = tvId ?: return
        if (state.value.kind != ReportKind.CONFIRM) return
        scope.launch { attempt { d.restarts.told(id, d.clock.nowMs()) } }
    }

    private suspend fun saveIssue(text: String) {
        val id = tvId ?: return
        val current = state.value.sent ?: return
        if (text.isBlank()) {
            val r = current.copy(issueUrl = null)
            attempt { d.sentReports.save(id, r) }.onSuccess { send(Sent(r)) }
            return
        }
        val url = SentReports.issueUrl(text) ?: return send(Sent(null, error = true))
        val r = current.copy(issueUrl = url)
        attempt { d.sentReports.save(id, r) }.onSuccess { send(Sent(r)) }
    }

    private suspend fun build(kind: ReportKind): Loaded = when (kind) {
        ReportKind.TV -> when (val input = ProblemReports.input(d, tvId)) {
            null -> Loaded("", "", available = false)
            else -> {
                val compact = TvReport.compact(input)
                val link = IssueLinks.tvSupport(input, compact)
                Loaded(compact, link.url, true, link.reportInLink, share = TvReport.text(input), sent = tvId?.let { id -> attempt { d.sentReports.load(id) }.getOrNull() })
            }
        }
        // 0.4.8: the confirmation block (TvReport.confirmation) in the confirmation form; none before a restart counted.
        ReportKind.CONFIRM -> {
            val input = ProblemReports.input(d, tvId)
            val facts = input?.restarts?.takeIf { it.tally.held > 0 }
            if (input == null || facts == null) Loaded("", "", available = false) else {
                val text = TvReport.confirmation(d.about, input, facts)
                val link = IssueLinks.confirmation(input, text)
                Loaded(text, link.url, true, link.reportInLink, share = text)
            }
        }
        // Settings asks without a TV: the TV used last, when there is one (ProblemReports.build).
        // 0.4.6: the link always holds the report, shortened to fit when it must.
        ReportKind.PROBLEM -> {
            val r = ProblemReports.build(d, tvId, null)
            Loaded(r.full, r.link.url, true, r.link.reportInLink, share = r.full, shortened = r.shortened)
        }
    }

    companion object {
        // The log can hold a few megabytes (five 1 MB files); a GitHub issue takes 65,536 characters
        // and the clipboard and the share sheet about a megabyte. The report keeps the log's first line
        // (the app and the phone) and its newest lines, at most MAX_LOG_LINES and MAX_LOG_CHARS.
        const val MAX_LOG_LINES = 300
        const val MAX_LOG_CHARS = 40_000

        fun tail(log: String): String {
            val lines = log.trimEnd().lines()
            if (lines.size <= 1) return log.trimEnd()
            val head = lines.first()
            val kept = ArrayDeque<String>()
            var chars = head.length
            for (line in lines.drop(1).asReversed()) {
                if (kept.size >= MAX_LOG_LINES || chars + line.length + 1 > MAX_LOG_CHARS) break
                kept.addFirst(line)
                chars += line.length + 1
            }
            val dropped = lines.size - 1 - kept.size
            return (listOf(head) + (if (dropped > 0) listOf("($dropped older lines left out)") else emptyList()) + kept).joinToString("\n")
        }
    }
}
