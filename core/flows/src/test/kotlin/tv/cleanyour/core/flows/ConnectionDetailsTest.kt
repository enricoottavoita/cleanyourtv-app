package tv.cleanyour.core.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.discovery.DiscoveryEvent
import tv.cleanyour.core.discovery.DiscoverySource
import tv.cleanyour.core.discovery.FoundTv
import tv.cleanyour.core.model.DebugState
import tv.cleanyour.core.model.Endpoint
import tv.cleanyour.core.model.TrustRecord
import tv.cleanyour.core.model.TvId
import tv.cleanyour.testing.fixtures.FakeTvSession
import tv.cleanyour.testing.fixtures.Fixtures
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// RFC-REACH-1: "Copy connection details" says what the phone sees, and never a serial, a key, a whole address, a
// Wi-Fi name, a MAC or a host name, even when the record and the log hold them.
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionDetailsTest {
    private val saved = "192.168.1.23"
    private val moved = "192.168.1.44"
    private val stranger = "192.168.1.60"
    private val neverAllowed = "192.168.1.61"
    private val otherId = TvId("0123456789abcdef")

    private val log = listOf(
        "1760000000000 I Clean Your TV 0.4.7-beta4, Android 34",
        "1760000001000 I access: needs debugging; wifi yes; network different from setup; 1 saved address; 5555 closed; search none yet; handshake none",
        "1760000002000 I access: tried 192.168.1.23 serialno=R58M12ABCD on ssid=HomeNet-5G mac 3c:2e:ff:10:20:30 host mitv-living.lan id 89abcdef01234567",
        "1760000003000 I access: key AAAAB3NzaC1yc2EAAAADAQABAAABAQC7vbqajDhA1234567890abcdef fingerprint 3A:91:0C:7E:55:12:9B:44:E0:21:6D:88:F3:0A:BC:42",
        "1760000004000 I manage: something else 192.168.1.23",
    ).joinToString("\n")

    private fun details(h: Harness, facts: PhoneNetworkFacts) = ConnectionDetails(
        about = AppAbout("0.4.7-beta4", "full", "Xiaomi Redmi Note 12", "Android 14"),
        tvs = h.tvs, sessions = h.sessions, discovery = h.discovery, access = h.access, phone = { facts },
        diagnostics = { log }, clock = h.clock,
    )

    private fun found(host: String, open: Boolean?) = DiscoveryEvent.Found(FoundTv(host, "Mi TV 4A", "MiTV-AXSO0", setOf(DiscoverySource.GOOGLE_CAST), open, null, null, Samples.NOW_MS))

    @Test fun theBlockSaysWhatThePhoneSeesAndNothingThatNamesTheNetwork() = runTest(timeout = 60.seconds) {
        val h = Harness()
        val tv = Samples.tv().copy(
            nickname = "Living room TV", endpoints = listOf(Endpoint.PlainTcp(saved)), lastSubnet = "192.168.1.0/24",
            debugging = DebugState.OFF, lastSeenAt = Samples.NOW_MS - 3 * 3_600_000L,
            trust = TrustRecord(adbKeyId = "QUFBQUIzTnphQzF5YzJFQUFBQURBUUFCQUFBQkFRQzd2YnFh", tlsCertSha256 = null, firstConnectedAt = Samples.NOW_MS - 12 * 86_400_000L, pairedAt = null),
        )
        h.tvs.upsert(tv)
        h.discovery.emit(DiscoveryEvent.NetworkState(onWifi = true, subnet = "192.168.29.0/24"))
        h.sessions.register(moved, h.session)                                    // the same TV at a new address
        h.sessions.register(stranger, FakeTvSession(identity = Fixtures.miTv().identity().copy(tvId = otherId)))
        h.sessions.register(neverAllowed, FakeTvSession(identity = Fixtures.miTv().identity().copy(tvId = TvId("fedcba9876543210"))))
        h.sessions.allowPromptFor[neverAllowed] = "AB:CD"
        listOf(moved, stranger, neverAllowed).forEach { h.discovery.open5555[it] = true }
        h.discovery.emit(found(moved, true))
        h.discovery.emit(found(stranger, null))
        h.discovery.emit(found(neverAllowed, true))
        h.discovery.emit(found("192.168.1.70", false))
        val facts = PhoneNetworkFacts(setOf(Transport.WIFI, Transport.VPN), vpn = true, privateDns = PrivateDns.HOSTNAME_SET, wifiIpv4 = true)

        val text = details(h, facts).build()

        listOf(
            "192.168.", "/24", "R58M12ABCD", "HomeNet", "3c:2e:ff", "mitv-living", ".lan", "89abcdef01234567", "0123456789abcdef",
            "fedcba9876543210", tv.id.value, "QUFBQUIz", "AAAAB3Nza", "3A:91:0C", "AB:CD", "MiTV-AXSO0",
        ).forEach { secret -> assertFalse(text.contains(secret, ignoreCase = true), "\"$secret\" leaked:\n$text") }

        assertTrue(text.startsWith("Clean Your TV: connection details\nApp: 0.4.7-beta4 (full)\nPhone: Xiaomi Redmi Note 12, Android 14\n"), text)
        listOf(
            "Waiting sheet: not open",
            "On Wi-Fi: yes", "Transports: Wi-Fi, VPN", "VPN active: yes", "Private DNS: a host name is set (not shown)",
            "Wi-Fi link has an IPv4 address: yes",
            "TV 1: Living room TV", "Same network as setup: no", "Saved addresses: 1",
            "- x.x.x.23: 5555 closed (0 ms); handshake: not tried",
            "Debugging as saved: off (switched off by the app)", "Last seen: 3 h ago",
            "Trust record: yes, first connected 12 days ago; paired: no",
            "Devices found: 4", "With 5555 open: 3",
            "- x.x.x.44: 5555 open; TV 1 took this phone's key",
            "- x.x.x.60: 5555 open; refused: a different TV (WrongTv)",
            "- x.x.x.61: 5555 open; needs Allow on the TV (not asked)",
            "- x.x.x.70: 5555 closed",
            "Last access lines (3)",
        ).forEach { assertTrue(it in text.lines(), "missing \"$it\":\n$text") }
        assertFalse(text.contains("manage: something else"), "only the access lines")
        assertTrue(h.sessions.opens.isEmpty(), "never the open that can raise the Allow dialog")
        assertEquals(setOf(moved, stranger, neverAllowed), h.sessions.knownOpens.map { it.host }.toSet())
        assertEquals(listOf(Endpoint.PlainTcp(saved)), h.tvs.tvs.value.single().endpoints, "the check saves nothing")
    }

    @Test fun offWifiThereIsNoSearch() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.tvs.upsert(Samples.tv())
        h.discovery.emit(DiscoveryEvent.NetworkState(onWifi = false, subnet = null))
        val text = details(h, PhoneNetworkFacts(setOf(Transport.CELLULAR), vpn = false, privateDns = PrivateDns.AUTOMATIC, wifiIpv4 = null)).build()
        assertTrue("Network search: not run (the phone is not on Wi-Fi)" in text.lines(), text)
        assertTrue("Wi-Fi link has an IPv4 address: no Wi-Fi link" in text.lines(), text)
        assertTrue("Same network as setup: unknown" in text.lines(), text)
        assertEquals(0, h.discovery.activeCollectors)
    }

    @Test fun addressesKeepOnlyTheirLastPart() {
        assertEquals("x.x.x.23", ConnectionDetails.mask("192.168.1.23"))
        assertEquals("x.x.x.7", ConnectionDetails.mask(" 10.0.0.007 "))
        assertEquals("[host]", ConnectionDetails.mask("mitv.local"))
        assertEquals("[host]", ConnectionDetails.mask("fe80::1"))
        assertEquals("just now", ConnectionDetails.ago(20_000))
        assertEquals("5 min ago", ConnectionDetails.ago(5 * 60_000L))
        assertEquals("3 days ago", ConnectionDetails.ago(3 * 86_400_000L))
    }
}
