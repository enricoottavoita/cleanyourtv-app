package tv.cleanyour.core.flows

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import tv.cleanyour.core.discovery.Discovery
import tv.cleanyour.core.discovery.DiscoveryEvent
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.DebugState
import tv.cleanyour.core.model.Endpoint
import tv.cleanyour.core.model.Tv
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.session.OpenResult
import tv.cleanyour.core.session.SessionManager
import tv.cleanyour.core.store.Clock
import tv.cleanyour.core.store.TvRepository

// RFC-REACH-1: how the phone is connected, read by the app at the moment "Copy connection details"
// is made. Only kinds and yes or no: never an address, a network name, a host name or an id.
enum class Transport { WIFI, CELLULAR, ETHERNET, VPN, OTHER }
enum class PrivateDns { OFF, AUTOMATIC, HOSTNAME_SET, UNKNOWN }

data class PhoneNetworkFacts(
    val transports: Set<Transport>,
    val vpn: Boolean,
    val privateDns: PrivateDns,
    val wifiIpv4: Boolean?,          // the Wi-Fi link has an IPv4 address; null when there is no Wi-Fi link
) {
    companion object { val UNKNOWN = PhoneNetworkFacts(emptySet(), vpn = false, privateDns = PrivateDns.UNKNOWN, wifiIpv4 = null) }
}

fun interface PhoneNetwork {
    fun facts(): PhoneNetworkFacts
}

// RFC-REACH-1 (a test build for a person whose TV never answered): "Copy connection details" on the
// debugging step and in App settings. A plain-text block, made at the moment it is asked for, with
// what the phone's network looks like, each saved TV's addresses (only their last part) and whether
// port 5555 answers there, a fresh network search of `searchMs`, the last "access:" log lines and the
// debugging step's state. The rules of RFC-RECONNECT-1 hold: only openKnown, the handshake with the
// app's signature and nothing more, so a TV that never allowed this phone sees no dialog and a
// different TV is never connected. No serial, key, full address, Wi-Fi name, MAC or host name: the
// values are built from fixed words and counts, the TV names and the log lines are scrubbed
// (Scrub, ConnectErrorSummary) and the app's redactor runs over the whole block as well.
class ConnectionDetails(
    private val about: AppAbout,
    private val tvs: TvRepository,
    private val sessions: SessionManager,
    private val discovery: Discovery,
    private val access: TvAccess,
    private val phone: PhoneNetwork,
    private val diagnostics: Diagnostics,
    private val clock: Clock,
    private val redact: (String) -> String = { it },
    private val searchMs: Long = 10_000,
    private val probeMs: Long = 1_500,
    private val handshakeMs: Long = 8_000,
) {
    suspend fun build(): String = coroutineScope {
        val known = tvs.tvs.value
        val net = discovery.network().value
        val facts = attempt { phone.facts() }.getOrDefault(PhoneNetworkFacts.UNKNOWN)
        // The search runs beside the checks of the saved addresses, so the whole block takes about `searchMs`.
        val search = async { if (net.onWifi) search(known) else null }
        val perTv = known.map { tv -> tv to savedChecks(tv) }
        val found = search.await()
        val log = attempt { diagnostics.redactedText() }.getOrDefault("").lines().filter { ACCESS in it }.takeLast(LOG_LINES)

        val text = buildString {
            appendLine("Clean Your TV: connection details")
            appendLine("App: ${OwnVersion.field(about.appLine) { LogCodes.clean(it, 120) }.ifEmpty { "unknown" }}")
            appendLine("Phone: ${LogCodes.clean(about.phoneLine, 120).ifEmpty { "unknown" }}")
            appendLine("Waiting sheet: ${reachLine()}")
            appendLine()
            appendLine("Phone network")
            appendLine("On Wi-Fi: ${yes(net.onWifi)}")
            appendLine("Transports: ${facts.transports.sortedBy { it.ordinal }.joinToString(", ") { transport(it) }.ifEmpty { "none" }}")
            appendLine("VPN active: ${yes(facts.vpn)}")
            appendLine("Private DNS: ${privateDns(facts.privateDns)}")
            appendLine("Wi-Fi link has an IPv4 address: ${facts.wifiIpv4?.let(::yes) ?: "no Wi-Fi link"}")
            if (known.isEmpty()) { appendLine(); appendLine("Saved TVs: none") }
            perTv.forEachIndexed { i, (tv, checks) ->
                appendLine()
                appendLine("TV ${i + 1}: ${name(tv)}")
                appendLine("Same network as setup: ${sameNetwork(net, tv)}")
                appendLine("Saved addresses: ${checks.size}")
                checks.forEach { appendLine("- $it") }
                appendLine("Debugging as saved: ${debugging(tv.debugging)}")
                appendLine("Last seen: ${ago(clock.nowMs() - tv.lastSeenAt)}")
                appendLine("Trust record: yes, first connected ${ago(clock.nowMs() - tv.trust.firstConnectedAt)}; paired: ${yes(tv.trust.pairedAt != null)}")
                appendLine("Connected now: ${yes(access.live(tv.id) != null)}")
                appendLine("Last handshake error: ${access.lastHandshake(tv.id) ?: "none"}")
            }
            appendLine()
            if (found == null) {
                appendLine("Network search: not run (the phone is not on Wi-Fi)")
            } else {
                appendLine("Network search (${searchMs / 1000} s)")
                appendLine("Devices found: ${found.size}")
                appendLine("With 5555 open: ${found.count { it.open }}")
                found.forEach { appendLine("- ${it.line}") }
            }
            appendLine()
            appendLine("Last access lines (${log.size})")
            log.forEach { appendLine(line(it)) }
        }
        OwnVersion.keep(text, about.version, redact).trimEnd() + "\n"
    }

    private fun reachLine(): String {
        val waited = "waited ${access.check.value.waitedMs / 1000} s"
        return when (val r = access.reach.value) {
            Reach.Idle -> "not open"
            is Reach.Connecting -> "connecting, $waited"
            is Reach.NeedsDebugging -> "needs debugging, $waited"
            is Reach.AwaitingAllow -> "waiting for Allow on the TV, $waited"
            is Reach.WrongNetwork -> "phone not on Wi-Fi, $waited"
        }.let { s -> if (access.reach.value == Reach.Idle) s else "$s (${access.check.value.summary()})" }
    }

    // Each saved address: its last part, port 5555 with the time it took, and the known-TV handshake when the port is
    // open (skipped while the TV shows the Allow prompt the debugging step raised, or when it is connected already).
    private suspend fun savedChecks(tv: Tv): List<String> = tv.endpoints.filterIsInstance<Endpoint.PlainTcp>().map { e ->
        val port = probe(e.host)
        val hello = when {
            port.word != "open" -> "not tried"
            access.live(tv.id) != null -> "connected now"
            (access.reach.value as? Reach.AwaitingAllow)?.tvId == tv.id -> "not tried, the TV is showing the Allow prompt"
            else -> handshake(e, tv.id, known = listOf(tv))
        }
        "${mask(e.host)}: 5555 ${port.word} (${port.ms} ms); handshake: $hello"
    }

    private class Found(val line: String, val open: Boolean)

    // The network search: every device Discovery hears of in `searchMs`, its last part, port 5555, and for each that
    // answers there the known-TV handshake, matched against the saved TVs.
    private suspend fun search(known: List<Tv>): List<Found> {
        val hosts = LinkedHashMap<String, Boolean?>()
        withTimeoutOrNull(searchMs) {
            discovery.discover().collect { e ->
                val f = when (e) { is DiscoveryEvent.Found -> e.tv; is DiscoveryEvent.Updated -> e.tv; else -> null } ?: return@collect
                if (hosts[f.host] != true) hosts[f.host] = f.port5555Open
            }
        }
        val saved = known.flatMap { tv -> tv.endpoints.filterIsInstance<Endpoint.PlainTcp>().map { it.host to tv } }.toMap()
        return hosts.map { (host, open) ->
            val port = if (open == true) Probe("open", 0) else probe(host)
            if (port.word != "open") return@map Found("${mask(host)}: 5555 ${port.word}", false)
            val savedTv = saved[host]
            val hello = when {
                known.isEmpty() -> "not tried, no saved TV"
                savedTv != null -> "the saved address of TV ${known.indexOf(savedTv) + 1}, checked above"
                else -> handshake(Endpoint.PlainTcp(host), known.first().id, known)
            }
            Found("${mask(host)}: 5555 open; $hello", true)
        }
    }

    private class Probe(val word: String, val ms: Long)

    // probe5555 says only open or not; a "not" that took the whole timeout is a timeout, a quick one was refused.
    private suspend fun probe(host: String): Probe {
        val start = clock.monotonicMs()
        val open = attempt { discovery.probe5555(host, probeMs) }.getOrDefault(false)
        val ms = (clock.monotonicMs() - start).coerceAtLeast(0)
        return Probe(if (open) "open" else if (ms >= probeMs - 100) "timeout" else "closed", ms)
    }

    // openKnown only (RFC-RECONNECT-1): the app's signature, never its public key, so no Allow dialog anywhere.
    private suspend fun handshake(e: Endpoint, expected: TvId, known: List<Tv>): String {
        val r = withTimeoutOrNull(handshakeMs) { attempt { sessions.openKnown(e, expected) }.getOrNull() } ?: return "no answer in ${handshakeMs / 1000} s"
        fun which(id: TvId) = known.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { "TV ${it + 1}" }
        return when (r) {
            is OpenResult.Ok -> "${which(r.session.tvId) ?: "a saved TV"} took this phone's key"
            is OpenResult.Failed -> when (val err = r.error) {
                is AdbError.Policy.WrongTv -> which(err.found)?.let { "$it took this phone's key (not saved at this address)" } ?: "refused: a different TV (WrongTv)"
                AdbError.Auth.Denied -> "needs Allow on the TV (not asked)"
                else -> "failed: ${LogCodes.of(err)}"
            }
        }
    }

    private fun name(tv: Tv): String = LogCodes.clean(tv.nickname, 60).ifEmpty { "unnamed" }

    private fun sameNetwork(net: DiscoveryEvent.NetworkState, tv: Tv): String = when {
        !net.onWifi || net.subnet == null || tv.lastSubnet == null -> "unknown"
        net.subnet == tv.lastSubnet -> "yes"
        else -> "no"
    }

    // A log line, scrubbed once more: addresses, MACs, ids, keys, serials, Wi-Fi names and local host names.
    private fun line(s: String): String = ConnectErrorSummary.redact(Scrub.text(LogCodes.clean(s, 400)))

    companion object {
        const val LOG_LINES = 40
        private const val ACCESS = "access: "

        // The last part of an IPv4 address ("x.x.x.23"), so a person and a maintainer can tell whether it changed.
        fun mask(host: String): String {
            val parts = host.trim().split('.')
            val ok = parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() <= 255 }
            return if (ok) "x.x.x.${parts[3].toInt()}" else "[host]"
        }

        fun ago(ms: Long): String {
            val m = ms.coerceAtLeast(0) / 60_000
            return when {
                m < 1 -> "just now"
                m < 60 -> "$m min ago"
                m < 48 * 60 -> "${m / 60} h ago"
                else -> "${m / (24 * 60)} days ago"
            }
        }

        private fun yes(b: Boolean) = if (b) "yes" else "no"
        private fun transport(t: Transport) = when (t) {
            Transport.WIFI -> "Wi-Fi"; Transport.CELLULAR -> "cellular"; Transport.ETHERNET -> "Ethernet"; Transport.VPN -> "VPN"; Transport.OTHER -> "other"
        }
        private fun privateDns(p: PrivateDns) = when (p) {
            PrivateDns.OFF -> "off"; PrivateDns.AUTOMATIC -> "automatic"; PrivateDns.HOSTNAME_SET -> "a host name is set (not shown)"; PrivateDns.UNKNOWN -> "unknown"
        }
        private fun debugging(d: DebugState) = when (d) { DebugState.ON -> "on"; DebugState.OFF -> "off (switched off by the app)"; DebugState.UNKNOWN -> "unknown" }
    }
}
