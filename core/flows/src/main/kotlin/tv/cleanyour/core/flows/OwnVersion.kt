package tv.cleanyour.core.flows

// 0.4.7-beta2: a four-part hotfix version (0.4.6.1, 0.4.7.1-beta1) is four numbers of 0 to 255 with
// dots, so every address mask took the app's own version for an IPv4 address. 141 reports since
// 0.4.6.1 said "App: [address] (full)", and the log's first line said "Clean Your TV x.x.x.1". The
// masks stay as they were for every other text; these keep the version only where the app writes
// it about itself. The version is no secret (the issue form's own version field carries it), so
// keeping it can leak nothing, and the same digits anywhere else are still masked.
object OwnVersion {
    // The versionName forms the release scripts take (release-on-forge.sh): 0.4.6, 0.4.6.1,
    // 0.4.7-beta2, 0.4.7.1-beta1.
    private const val FORM = "\\d+\\.\\d+\\.\\d+(?:\\.\\d+)?(?:-[0-9A-Za-z.]+)?"
    private val VERSION = Regex(FORM)
    // A value that is the app's own words about a build: "0.4.6.1" or "0.4.6.1 (full, release)".
    private val LEADING = Regex("($FORM)(?: (.+))?")

    fun isVersion(s: String): Boolean = VERSION.matches(s)

    // Runs `mask` over `text` but keeps `version` (the running app's) whole where a line starts with
    // the app's own words for it: "Clean Your TV " (the diagnostics log's first line) or "App: " (a
    // report's and "Copy error"'s App line). The text around it is masked in pieces, cut at the
    // version, which only ever sits between a space and a space, a comma, a bracket or the line's
    // end, so no mask loses a match it had. The same digits inside a log line are masked as before.
    fun keep(text: String, version: String?, mask: (String) -> String): String {
        if (version == null || !isVersion(version)) return mask(text)
        val own = Regex("(?m)^(?:Clean Your TV |App: )(${Regex.escape(version)})(?=[ ,(]|$)")
        val out = StringBuilder()
        var from = 0
        for (m in own.findAll(text)) {
            val at = m.groups[1]!!.range
            out.append(mask(text.substring(from, at.first))).append(version)
            from = at.last + 1
        }
        return out.append(mask(text.substring(from))).toString()
    }

    // A whole value the app wrote about a build and kept on the phone (AppAbout.appLine, a Home
    // decision's app, a restart tally's build), which can be another version than the running one:
    // a leading version is kept and what follows it masked. TV text never comes here.
    fun field(value: String, mask: (String) -> String): String {
        val m = LEADING.matchEntire(value.trim()) ?: return mask(value)
        val rest = m.groupValues[2]
        return if (rest.isEmpty()) m.groupValues[1] else m.groupValues[1] + " " + mask(rest)
    }
}
