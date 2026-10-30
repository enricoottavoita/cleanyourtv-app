package tv.cleanyour.core.ops

import tv.cleanyour.core.model.COMPONENT_LIST_RE
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.RUN_TOKEN_RE
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RunToken

// RFC-HELPER-1 (test build, cyt.homeHelper): the lines of the Home button helper. Like the HOME
// watchdog (Watchdog), each is a string constant with placeholders that no grammar admits, filled
// from values checked again here.
//
// Only one service is ever added or removed: the launcher's HomeKeyService (SERVICE), in its short
// (`com.gathin.tvhome/.HomeKeyService`) or full class form. Every write of the service list is a
// compare-and-set, or a removal of that one entry with every other entry left byte for byte and in
// order:
// - add: the TV reads the list and writes it with ours appended only while it still reads what the
//   phone read (EXPECTED); else nothing is written and the line exits 3, so a list someone changed
//   since the probe is never overwritten (RF-LAUNCHER-250 F9).
// - casRemove: the same compare-and-set, to the list without our entry, worked out on the phone;
//   `settings delete` when nothing is left.
// - tvRemove: the TV takes our entry out of the list it reads now (sed over the list wrapped in
//   colons, so only a whole entry matches) and writes the rest back; OFF also writes
//   accessibility_enabled 0 when nothing is left. For Undo's inverse and the laptop scripts, which
//   cannot know the list.
// - watchdog: the safety timer of the helper's check (PLAN 5.1 risk 1). Unless the phone's token
//   file defuses it, it runs tvRemove and shows the TV's home screen, so a new home screen that
//   crashes at start and is reopened by the helper over and over cannot outlive a lost connection.
// Values sit in single quotes (no list, component or token admits one) or are only ever read into
// shell variables and used inside double quotes; nothing read from the TV is pasted into a line.
object HelperLines {
    const val PKG = "com.gathin.tvhome"
    val SERVICE = Component(PackageName(PKG), ".HomeKeyService")
    const val LIST_KEY = "enabled_accessibility_services"
    const val ON_KEY = "accessibility_enabled"
    const val MAX_ENTRIES = 16
    const val LIST_CHANGED_EXIT = 3
    const val LIST_CHANGED = "cleanyourtv: the accessibility list changed since it was read"

    private const val DIR = "/data/local/tmp/cleanyourtv"
    private const val READ = "c=\$(settings get secure $LIST_KEY)"
    private const val REFUSE = "else echo '$LIST_CHANGED' >&2; exit $LIST_CHANGED_EXIT; fi"
    const val ADD_TEMPLATE = "$READ; if [ \"\$c\" = 'EXPECTED' ]; then settings put secure $LIST_KEY 'RESULT'; $REFUSE"
    const val ADD_UNSET_TEMPLATE = "$READ; if [ -z \"\$c\" ] || [ \"\$c\" = null ]; then settings put secure $LIST_KEY 'RESULT'; $REFUSE"
    const val REMOVE_TEMPLATE = "$READ; if [ \"\$c\" = 'EXPECTED' ]; then settings put secure $LIST_KEY 'RESULT'; $REFUSE"
    const val REMOVE_LAST_TEMPLATE = "$READ; if [ \"\$c\" = 'EXPECTED' ]; then settings delete secure $LIST_KEY; $REFUSE"
    // No single quote in here: the watchdog runs it inside sh -c '...'. ENTRYRE is the entry with
    // every dot in brackets and the full class form optional. It runs twice, since two entries of
    // ours side by side share the colon between them.
    private const val SED = "s#:ENTRYRE:#:#g;s#:ENTRYRE:#:#g;s#^:##;s#:\\\$##"
    const val TV_REMOVE_TEMPLATE = "$READ; n=\$(echo \":\$c:\" | sed -E \"$SED\"); " +
        "if [ \"\$n\" != \"\$c\" ]; then if [ -z \"\$n\" ]; then settings delete secure $LIST_KEY;OFF " +
        "else settings put secure $LIST_KEY \"\$n\"; fi; fi"
    private const val OFF = " settings put secure $ON_KEY 0;"
    const val WATCHDOG_BODY = "sleep SECONDS & wait; [ -f $DIR/${Watchdog.TOKEN_FILE_PREFIX}TOKEN ] && exit 0; REMOVE; " +
        "sleep 2; am start -a android.intent.action.MAIN -c android.intent.category.HOME"
    const val WATCHDOG_NOHUP_SETSID = "nohup setsid sh -c '$WATCHDOG_BODY' ${Watchdog.PROCESS_NAME_PREFIX}TOKEN " +
        ">$DIR/${Watchdog.LOG_FILE_PREFIX}TOKEN${Watchdog.LOG_FILE_SUFFIX} 2>&1 &"
    const val WATCHDOG_SUBSHELL = "( sh -c '$WATCHDOG_BODY' ${Watchdog.PROCESS_NAME_PREFIX}TOKEN " +
        ">$DIR/${Watchdog.LOG_FILE_PREFIX}TOKEN${Watchdog.LOG_FILE_SUFFIX} 2>&1 & )"

    init {
        check(RemoteDir.TMP.path == DIR) { "helper watchdog dir differs from RemoteDir.TMP" }
    }

    // The list arithmetic: the phone side of the compare-and-set.

    fun entries(list: String?): List<String> = list?.takeIf { it.isNotEmpty() && it != "null" }?.split(':').orEmpty()
    // The component in its short or full class form (Android accepts and keeps either).
    fun isEntry(entry: String, component: Component = SERVICE): Boolean {
        val full = if (component.cls.startsWith(".")) component.pkg.value + component.cls else component.cls
        val short = if (full.startsWith(component.pkg.value + ".")) full.removePrefix(component.pkg.value) else null
        return entry == "${component.pkg.value}/$full" || (short != null && entry == "${component.pkg.value}/$short")
    }
    fun listed(list: String?, component: Component = SERVICE): Boolean = entries(list).any { isEntry(it, component) }
    fun others(list: String?, component: Component = SERVICE): List<String> = entries(list).filterNot { isEntry(it, component) }
    fun appended(expected: String?, component: Component): String = (entries(expected) + component.flat).joinToString(":")
    fun without(expected: String?, component: Component = SERVICE): String? = others(expected, component).joinToString(":").ifEmpty { null }

    // The lines.

    fun add(expected: String?, component: Component): String {
        ours(component)
        if (expected != null) require(COMPONENT_LIST_RE.matches(expected)) { "the expected list fails COMPONENT_LIST_RE" }
        require(!listed(expected, component)) { "the expected list already names ${component.flat}" }
        val result = appended(expected, component)
        require(entries(result).size <= MAX_ENTRIES && COMPONENT_LIST_RE.matches(result)) { "the list would have more than $MAX_ENTRIES entries" }
        val line = if (expected == null) fill(ADD_UNSET_TEMPLATE, result = result) else fill(ADD_TEMPLATE, expected = expected, result = result)
        return checked(line)
    }

    fun casRemove(expected: String, component: Component): String {
        ours(component)
        require(COMPONENT_LIST_RE.matches(expected)) { "the expected list fails COMPONENT_LIST_RE" }
        require(listed(expected, component)) { "the expected list does not name ${component.flat}" }
        val result = without(expected, component)
        val line = if (result == null) fill(REMOVE_LAST_TEMPLATE, expected = expected) else fill(REMOVE_TEMPLATE, expected = expected, result = result)
        return checked(line)
    }

    fun tvRemove(component: Component, offIfEmpty: Boolean): String {
        ours(component)
        return checked(fill(TV_REMOVE_TEMPLATE, off = offIfEmpty))
    }

    fun watchdog(variant: WatchdogVariant, component: Component, seconds: Int, token: RunToken, offIfEmpty: Boolean): String {
        ours(component)
        require(seconds in Watchdog.MIN_SECONDS..Watchdog.MAX_SECONDS) { "watchdog seconds $seconds outside ${Watchdog.MIN_SECONDS}..${Watchdog.MAX_SECONDS}" }
        require(RUN_TOKEN_RE.matches(token.value)) { "run token fails RUN_TOKEN_RE" }
        val template = when (variant) {
            WatchdogVariant.NOHUP_SETSID -> WATCHDOG_NOHUP_SETSID
            WatchdogVariant.SUBSHELL -> WATCHDOG_SUBSHELL
        }
        val remove = tvRemove(component, offIfEmpty)
        check(!remove.contains('\'')) { "the removal would close the watchdog's quote" }
        // One pass: the removal and the token are never rescanned for placeholders.
        val line = Regex("REMOVE|SECONDS|TOKEN").replace(template) { m ->
            when (m.value) { "REMOVE" -> remove; "SECONDS" -> seconds.toString(); else -> token.value }
        }
        check(line.count { it == '\'' } == 2) { "the helper watchdog has quotes of its own" }
        return checked(line)
    }

    // The renderer's and the tests' inverse checks: the line is exactly a template filled with these
    // values and nothing else.
    fun matchesAdd(line: String, expected: String?, component: Component): Boolean = runCatching { add(expected, component) == line }.getOrDefault(false)
    fun matchesWatchdog(line: String, variant: WatchdogVariant, component: Component, seconds: Int, token: RunToken, offIfEmpty: Boolean): Boolean =
        runCatching { watchdog(variant, component, seconds, token, offIfEmpty) == line }.getOrDefault(false)

    private fun ours(component: Component) {
        require(component == SERVICE) { "only ${SERVICE.flat} is ever added or removed" }
    }

    // ENTRYRE for SERVICE: the package, a slash, the package again or nothing, then the class.
    private val ENTRY_RE: String = PKG.replace(".", "[.]") + "/(" + PKG.replace(".", "[.]") + ")?" + SERVICE.cls.replace(".", "[.]")

    // One pass per template: a value is never rescanned for placeholders.
    private fun fill(template: String, expected: String = "", result: String = "", off: Boolean = false): String =
        Regex("EXPECTED|RESULT|ENTRYRE|OFF").replace(template) { m ->
            when (m.value) {
                "EXPECTED" -> expected
                "RESULT" -> result
                "ENTRYRE" -> ENTRY_RE
                else -> if (off) OFF else ""
            }
        }

    private fun checked(line: String): String {
        check(ShellTokens.LINE_RE.matches(line)) { "helper line is not printable ASCII" }
        return line
    }
}
