package io.github.sandeepvijayarao09.anu.core.security

/**
 * A handle to a vault item (P3). Code and models pass these around; only the vault can resolve one,
 * and only while attaching it to an HTTP request for a bound host (I1).
 */
@JvmInline
value class SecretRef(val id: String) {
    override fun toString(): String = "SecretRef($id)"
}

/**
 * Put on an OkHttp request as a tag to ask the vault to attach a secret. The vault adds [header]
 * (as "[scheme] value" when [scheme] is set), and only if the request's host is bound to the item and
 * [caller] is allowed to use it (§11).
 */
data class SecretUse(val ref: SecretRef, val header: String, val caller: String, val scheme: String? = null)
