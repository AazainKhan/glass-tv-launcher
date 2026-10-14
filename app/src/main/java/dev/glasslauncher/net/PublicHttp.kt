package dev.glasslauncher.net

import okhttp3.Dns
import okhttp3.OkHttpClient
import okio.BufferedSource
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Fetching a picture on behalf of something untrusted (an add-on, a media session) without letting it probe the home network:
 * public names only, https only on every hop (including redirects, at most [MAX_HOPS]), private addresses refused at DNS.
 * One shared implementation (add-on art and Now Playing art both go through it).
 * Accepted gap: DNS is resolved by the client right before it connects, by name; a resolver that answers differently a moment
 * later (rebinding) is refused again by the same check at connect time, but the window between check and connect is not closed. Fine for art.
 */
object PublicHttp {
    const val MAX_HOPS = 3

    /** One hop (the first request or any redirect): https, and a public name (an IP literal is refused by name). */
    fun hopAllowed(scheme: String, host: String) = scheme == "https" && !PublicHosts.blockedName(host)

    fun tooManyHops(redirects: Int) = redirects > MAX_HOPS

    /** A client that follows up to [MAX_HOPS] redirects by itself but re-checks https and the host on every hop. */
    fun client(dns: Dns = SafeDns()): OkHttpClient = OkHttpClient.Builder()
        .dns(dns).followRedirects(true).followSslRedirects(false)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS)
        // Every hop, redirects included: https and a public name (an IP literal skips DNS, so it is refused here).
        .addNetworkInterceptor { chain ->
            val u = chain.request().url
            if (!hopAllowed(u.scheme, u.host)) throw IOException("blocked hop")
            chain.proceed(chain.request())
        }
        .addInterceptor { chain ->
            val r = chain.proceed(chain.request())
            var hops = 0; var p = r.priorResponse
            while (p != null) { hops++; p = p.priorResponse }
            if (tooManyHops(hops)) { r.close(); throw IOException("too many redirects") }
            r
        }
        .build()

    /** Reads at most [cap] bytes; null if the source has more (so a huge or endless body is never held). */
    fun readCapped(source: BufferedSource, cap: Int): ByteArray? {
        val buf = okio.Buffer()
        while (buf.size <= cap) {
            if (source.read(buf, 8192L) == -1L) return buf.readByteArray()
        }
        return null
    }
}

/** Which hosts untrusted art may come from: public names only. It must not make Glass (on a stick that may be rooted) probe the home network. */
object PublicHosts {
    private val SUFFIXES = listOf(".local", ".localhost", ".internal", ".lan", ".home", ".corp", ".intranet")
    private val IPV4ISH = Regex("^[0-9.]+$")

    /** Names that are never fetched: localhost, no dot, any IP literal (v4, v6 or odd forms), and local-network suffixes. */
    fun blockedName(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty() || '.' !in h || ':' in h || '[' in h) return true
        if (h == "localhost" || SUFFIXES.any { h.endsWith(it) }) return true
        if (IPV4ISH.matches(h)) return true
        val last = h.substringAfterLast('.')
        return last.all { it.isDigit() } || last.startsWith("0x")
    }

    /** Loopback, any-local, link-local, multicast, RFC 1918, CGNAT 100.64/10, 0/8, and IPv6 unique-local fc00::/7 (an IPv4-mapped v6 address is judged as v4). */
    fun isPrivate(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
        val b = a.address
        if (b.size == 4) {
            val b0 = b[0].toInt() and 0xFF; val b1 = b[1].toInt() and 0xFF
            return b0 == 0 || (b0 == 100 && (b1 and 0xC0) == 0x40)
        }
        return (b[0].toInt() and 0xFE) == 0xFC
    }
}

/** DNS for untrusted art: a name that resolves to any private address is refused, on every hop (this is what stops DNS rebinding). */
class SafeDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (PublicHosts.blockedName(hostname)) throw UnknownHostException("blocked host")
        val all = delegate.lookup(hostname)
        if (all.isEmpty() || all.any { PublicHosts.isPrivate(it) }) throw UnknownHostException("blocked address")
        return all
    }
}

