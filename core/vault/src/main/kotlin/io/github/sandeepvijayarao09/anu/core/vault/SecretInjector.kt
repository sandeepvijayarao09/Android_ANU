package io.github.sandeepvijayarao09.anu.core.vault

import io.github.sandeepvijayarao09.anu.core.security.SecretUse
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Attaches a vault secret to requests tagged with [SecretUse] (§11, I1). It is installed as a network
 * interceptor, so it checks the host of the hop actually being sent. Build clients with [withVault].
 */
class SecretInjector internal constructor(private val vault: Vault, private val requireHttps: Boolean) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val use = request.tag(SecretUse::class.java) ?: return chain.proceed(request)
        if (requireHttps && !request.isHttps) throw VaultPolicyException("Secrets are only sent over HTTPS")
        val authorized = vault.useSecret(use.ref, request.url.host, use.caller) { value ->
            request.newBuilder().header(use.header, use.scheme?.let { "$it $value" } ?: value).build()
        }
        return chain.proceed(authorized)
    }
}

/**
 * A client that can carry vault secrets. Redirects are off, so a secret never follows a redirect to
 * another host. Custom headers such as `x-goog-api-key` are not stripped on redirect the way
 * `Authorization` is.
 */
fun OkHttpClient.Builder.withVault(vault: Vault): OkHttpClient.Builder = vaultClient(vault, requireHttps = true)

internal fun OkHttpClient.Builder.vaultClient(vault: Vault, requireHttps: Boolean): OkHttpClient.Builder =
    followRedirects(false)
        .followSslRedirects(false)
        .addNetworkInterceptor(SecretInjector(vault, requireHttps))
