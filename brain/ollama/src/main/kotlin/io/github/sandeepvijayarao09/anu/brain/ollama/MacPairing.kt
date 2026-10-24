package io.github.sandeepvijayarao09.anu.brain.ollama

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Where the user's Mac answers and how Anu knows it's that Mac (ADR-113). `scripts/mac-brain.py` prints the pairing code,
 * `anu-mac://<host>:<port>?pin=<sha-256 of its certificate>&token=<token>`. The token goes straight into the vault; only
 * [host], [port] and [pin] are kept in the clear.
 */
data class MacPairing(val host: String, val port: Int, val pin: String) {

    val baseUrl: HttpUrl get() = HttpUrl.Builder().scheme("https").host(host).port(port).build()

    /** Where requests go, as the Privacy Ledger and the consent sheet name it. */
    val destination: String get() = host

    companion object {
        /** The pairing code read: the pairing, and the token for the vault. Null when it isn't one. */
        fun parse(code: String): Pair<MacPairing, String>? {
            val url = code.trim().replaceFirst("anu-mac://", "https://").toHttpUrlOrNull() ?: return null
            val pin = url.queryParameter("pin")?.lowercase()?.takeIf { it.matches(PIN) } ?: return null
            val token = url.queryParameter("token")?.takeIf { it.length >= 32 } ?: return null
            if (!code.trim().startsWith("anu-mac://")) return null
            return MacPairing(url.host, url.port, pin) to token
        }

        private val PIN = Regex("[0-9a-f]{64}")
    }
}

/**
 * HTTPS that trusts exactly the paired Mac's certificate, by its SHA-256 (ADR-113). The Mac's certificate is its own,
 * made by `scripts/mac-brain.py`, so no authority vouches for it: the pin does, and a host other than the paired one is
 * refused. Everything else about [this] client, the vault's injector included, stays as it was.
 */
fun OkHttpClient.Builder.pinnedTo(pairing: MacPairing): OkHttpClient.Builder {
    val trust = PinnedTrust(pairing.pin)
    val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    return sslSocketFactory(tls.socketFactory, trust).hostnameVerifier { host, _ -> host.equals(pairing.host, ignoreCase = true) }
}

internal class PinnedTrust(private val pin: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("No certificate")
        if (sha256(leaf.encoded) != pin) throw CertificateException("Not the paired Mac's certificate")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException("Not a server")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
