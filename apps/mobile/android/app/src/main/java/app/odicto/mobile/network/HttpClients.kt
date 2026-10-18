package app.odicto.mobile.network

import app.odicto.mobile.BuildConfig
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * One connection pool for every provider call. A dictation reuses the TLS connection the previous one opened instead
 * of paying DNS, TCP and TLS again, and AI mode's second call reuses its provider's connection too. Idle connections
 * close after two minutes and carry no pings, so nothing is sent while the app is idle. Built on first use.
 */
object HttpClients {
    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(4, 2, TimeUnit.MINUTES))
            .followRedirects(false).followSslRedirects(false)
            .apply { if (BuildConfig.DEBUG) eventListenerFactory { PhaseTimings { runCatching { android.util.Log.i("OdictoNet", it) } } } }
            .build()
    }
    /** Groq transcription, then the Gemini or OpenRouter answer. */
    val voice: OkHttpClient by lazy {
        shared.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()
    }
    /** Gemini Live only: the socket pings while it is open. */
    val live: OkHttpClient by lazy { voice.newBuilder().pingInterval(20, TimeUnit.SECONDS).build() }
}

/** Debug builds only: how long each phase of one provider call took. Host, sizes and durations; never content. */
internal class PhaseTimings(private val log: (String) -> Unit) : EventListener() {
    private var start = 0L
    private var dnsAt = 0L
    private var connectAt = 0L
    private var tlsAt = 0L
    private var dns = -1L
    private var connect = -1L
    private var tls = -1L
    private var sent = -1L
    private var uploaded = 0L
    private var firstByte = -1L
    private var connected = false
    private fun now() = System.nanoTime()
    private fun since(at: Long) = (now() - at) / 1_000_000
    override fun callStart(call: Call) { start = now() }
    override fun dnsStart(call: Call, domainName: String) { dnsAt = now() }
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) { dns = since(dnsAt) }
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { connected = true; connectAt = now() }
    override fun secureConnectStart(call: Call) { tlsAt = now() }
    override fun secureConnectEnd(call: Call, handshake: Handshake?) { tls = since(tlsAt) }
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) { connect = since(connectAt) }
    override fun requestBodyEnd(call: Call, byteCount: Long) { sent = since(start); uploaded = byteCount }
    override fun responseHeadersStart(call: Call) { firstByte = since(start) }
    override fun callEnd(call: Call) = report(call, "ok")
    override fun callFailed(call: Call, ioe: IOException) = report(call, ioe.javaClass.simpleName)
    private fun report(call: Call, outcome: String) = log(
        "${call.request().url.host} $outcome total=${since(start)}ms reused=${!connected} dns=${dns}ms connect=${connect}ms " +
            "tls=${tls}ms upload=${uploaded}B sent=${sent}ms firstByte=${firstByte}ms")
}
