package com.exo.musicplayer.desktop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URL

/** How Resonate reaches the internet. */
enum class ProxyMode(val label: String) {
    SYSTEM("System"),
    DIRECT("No proxy"),
    SOCKS5("SOCKS5 · recommended"),
    SOCKS4("SOCKS4"),
    HTTP("HTTP"),
    HTTPS("HTTPS");

    val needsAddress: Boolean
        get() = this == SOCKS5 || this == SOCKS4 || this == HTTP || this == HTTPS

    companion object {
        fun fromName(name: String?): ProxyMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

data class ProxyConfig(
    val mode: ProxyMode = ProxyMode.SYSTEM,
    val host: String = "",
    val port: Int = 0
) {
    /** A manual proxy still missing its address or port; connections go direct until then. */
    val incomplete: Boolean
        get() = mode.needsAddress && (host.isBlank() || port !in 1..65535)
}

/**
 * The one proxy setting, applied to everything that goes online.
 *
 * Resonate reaches the internet three ways, and each has to be told separately:
 *
 *  - Its own requests (lyrics, cover art, song metadata, identification, weather,
 *    the Piped search) all go through HttpURLConnection, which asks the JVM-wide
 *    ProxySelector. Replacing that selector covers every one of them at once.
 *  - yt-dlp and spotdl are separate programs. yt-dlp is given --proxy, and both
 *    get the usual proxy environment variables.
 *  - Previews are decoded by ffmpeg, which cannot speak SOCKS. Behind a proxy,
 *    PreviewPlayer fetches the stream itself and feeds ffmpeg, so that goes
 *    through this selector as well.
 *
 * SOCKS5 is handed to yt-dlp as socks5h, which resolves site names at the proxy:
 * on a network that blocks YouTube by DNS, resolving locally fails before the
 * proxy is ever reached. Checked against a local proxy, the bundled yt-dlp
 * accepts socks5h, socks5, socks4a and http.
 *
 * HTTPS reaches the proxy the same way HTTP does and tunnels secure sites
 * through it with CONNECT. A proxy that wants TLS on the connection to itself
 * cannot be used by the JVM's HTTP client at all, so treating the two alike
 * keeps the app's own requests and yt-dlp on the same route.
 */
object NetworkProxy {

    /**
     * Windows' own proxy settings, as the JVM reads them under
     * java.net.useSystemProxies. Captured before the selector is replaced.
     */
    private val systemSelector: ProxySelector? = ProxySelector.getDefault()

    @Volatile
    var config: ProxyConfig = ProxyConfig()
        private set

    fun apply(next: ProxyConfig) {
        config = next
        // Java chooses the SOCKS version for each new connection from this.
        System.setProperty("socksProxyVersion", if (next.mode == ProxyMode.SOCKS4) "4" else "5")
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = proxiesFor(uri)
            override fun connectFailed(uri: URI?, address: SocketAddress?, error: IOException?) = Unit
        })
    }

    /** The route [uri] takes under the current setting. */
    fun proxiesFor(uri: URI): List<Proxy> {
        val host = uri.host?.lowercase()
        // Local services, the local proxy itself among them, are never proxied.
        if (host == null || host == "localhost" || host == "127.0.0.1" || host == "::1") {
            return listOf(Proxy.NO_PROXY)
        }
        val current = config
        return when {
            current.mode == ProxyMode.SYSTEM ->
                runCatching { systemSelector?.select(uri) }.getOrNull()?.takeIf { it.isNotEmpty() }
                    ?: listOf(Proxy.NO_PROXY)
            current.mode == ProxyMode.DIRECT || current.incomplete -> listOf(Proxy.NO_PROXY)
            else -> listOf(
                Proxy(
                    if (current.mode == ProxyMode.SOCKS5 || current.mode == ProxyMode.SOCKS4) {
                        Proxy.Type.SOCKS
                    } else {
                        Proxy.Type.HTTP
                    },
                    InetSocketAddress.createUnresolved(current.host.trim(), current.port)
                )
            )
        }
    }

    /** The proxy [target] would use, as a URL other programs understand, or null for direct. */
    fun urlFor(target: String = "https://www.youtube.com/"): String? {
        val proxy = runCatching { proxiesFor(URI(target)) }.getOrNull()
            ?.firstOrNull { it.type() != Proxy.Type.DIRECT }
            ?: return null
        val address = proxy.address() as? InetSocketAddress ?: return null
        val where = "${address.hostString}:${address.port}"
        return when {
            proxy.type() == Proxy.Type.HTTP -> "http://$where"
            config.mode == ProxyMode.SOCKS4 -> "socks4a://$where"
            else -> "socks5h://$where"
        }
    }

    /** Arguments that put yt-dlp on the same route. */
    fun ytDlpArgs(): List<String> {
        urlFor()?.let { return listOf("--proxy", it) }
        // An empty --proxy is yt-dlp for "connect directly", which "No proxy"
        // needs even when Windows or the environment names a proxy.
        return if (config.mode == ProxyMode.DIRECT) listOf("--proxy", "") else emptyList()
    }

    /** Sets the proxy environment variables a child program reads. */
    fun configure(builder: ProcessBuilder) {
        val env = builder.environment()
        val names = listOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY")
        val url = urlFor()
        when {
            url != null -> {
                names.forEach { env.remove(it); env.remove(it.lowercase()) }
                listOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY").forEach {
                    env[it] = url
                    env[it.lowercase()] = url
                }
            }
            config.mode == ProxyMode.DIRECT -> {
                names.forEach { env.remove(it); env.remove(it.lowercase()) }
                env["NO_PROXY"] = "*"
                env["no_proxy"] = "*"
            }
            // System with nothing detected: the environment is left alone.
        }
    }

    /** Fetches a tiny YouTube page along the current route and says how it went. */
    suspend fun test(): String = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        runCatching {
            val connection = (URL("https://www.youtube.com/generate_204").openConnection()
                as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = false
            }
            try {
                val code = connection.responseCode
                val ms = (System.nanoTime() - started) / 1_000_000
                if (code in 200..399) "Reached YouTube in $ms ms" else "YouTube answered HTTP $code after $ms ms"
            } finally {
                connection.disconnect()
            }
        }.getOrElse { "Couldn't reach YouTube: ${it.message ?: it.javaClass.simpleName}" }
    }
}
