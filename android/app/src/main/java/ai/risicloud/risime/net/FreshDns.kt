package ai.risicloud.risime.net

import android.net.ConnectivityManager
import android.net.DnsResolver
import android.os.Build
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A lookup that skips the system resolver's cache (Android 10+: DnsResolver FLAG_NO_CACHE_LOOKUP) on the
 * current default network. Used by the sign-in check's retries (P0 2026-10-12): after the server's public IP
 * moved, a cached answer for the old address made every attempt time out until the TTL ran out, while Chrome
 * (its own resolver) already had the new one. Falls back to the system lookup (Android 8/9, or no answer).
 */
class FreshDns(private val cm: ConnectivityManager?, private val timeoutMs: Long = 8_000) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fresh = runCatching { queryNoCache(hostname) }.getOrNull()
            if (!fresh.isNullOrEmpty()) return fresh
        }
        return Dns.SYSTEM.lookup(hostname)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun queryNoCache(hostname: String): List<InetAddress>? {
        val done = CountDownLatch(1)
        val out = AtomicReference<List<InetAddress>?>(null)
        DnsResolver.getInstance().query(
            cm?.activeNetwork, hostname, DnsResolver.FLAG_NO_CACHE_LOOKUP, java.util.concurrent.Executor { it.run() }, null,
            object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                    out.set(answer)
                    done.countDown()
                }

                override fun onError(error: DnsResolver.DnsException) {
                    done.countDown()
                }
            },
        )
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) throw UnknownHostException("$hostname: fresh lookup timed out")
        return out.get()
    }
}
