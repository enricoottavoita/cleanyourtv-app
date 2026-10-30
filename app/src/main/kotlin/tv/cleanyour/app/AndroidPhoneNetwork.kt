package tv.cleanyour.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import tv.cleanyour.core.flows.PhoneNetwork
import tv.cleanyour.core.flows.PhoneNetworkFacts
import tv.cleanyour.core.flows.PrivateDns
import tv.cleanyour.core.flows.Transport

// RFC-REACH-1: how the phone is connected right now, for "Copy connection details", read from
// ConnectivityManager with ACCESS_NETWORK_STATE only (D16). Only kinds and yes or no leave here:
// the Private DNS host name, addresses and the Wi-Fi name are never read into the facts.
@SuppressLint("MissingPermission")
class AndroidPhoneNetwork(context: Context) : PhoneNetwork {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun facts(): PhoneNetworkFacts = runCatching {
        @Suppress("DEPRECATION")   // allNetworks: the one way to see a VPN beside the Wi-Fi it runs over
        val all = cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
        val transports = all.flatMap { (_, c) -> transportsOf(c) }.toSet()
        val active = cm.activeNetwork
        val activeLink = active?.let { cm.getLinkProperties(it) }
        val privateDns = when {
            activeLink == null -> PrivateDns.UNKNOWN
            !activeLink.isPrivateDnsActive -> PrivateDns.OFF
            activeLink.privateDnsServerName != null -> PrivateDns.HOSTNAME_SET
            else -> PrivateDns.AUTOMATIC
        }
        val wifi = all.firstOrNull { (_, c) -> c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }?.first
        val wifiIpv4 = wifi?.let { n -> cm.getLinkProperties(n)?.linkAddresses.orEmpty().any { it.address is Inet4Address } }
        PhoneNetworkFacts(transports, vpn = Transport.VPN in transports, privateDns = privateDns, wifiIpv4 = wifiIpv4)
    }.getOrDefault(PhoneNetworkFacts.UNKNOWN)

    private fun transportsOf(c: NetworkCapabilities): List<Transport> = buildList {
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(Transport.WIFI)
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(Transport.CELLULAR)
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(Transport.ETHERNET)
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(Transport.VPN)
        if (isEmpty()) add(Transport.OTHER)
    }
}
