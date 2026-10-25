package io.github.sandeepvijayarao09.anu.connector.skills

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.sandeepvijayarao09.anu.core.agent.AgentSkill
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream

/**
 * Runs a JS skill's script as Gallery does (ADR-120): it loads `<skill>/scripts/<script>` in a hidden WebView, waits up
 * to 10 s for the page to define `ai_edge_gallery_get_result(data, secret)`, awaits it, and takes what the page hands
 * `AiEdgeGallery.onResultReady`. The whole call stops after 60 s. A skill on the phone is served from its own folder
 * at `https://appassets.androidplatform.net/`; with [network] off the WebView loads nothing from anywhere else.
 */
class JsSkillRunner(private val context: Context, private val library: SkillLibrary) {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun run(skill: AgentSkill, script: String, data: String, network: Boolean): String = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<String>()
        val web = WebView(context.applicationContext)
        var gone = false
        try {
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.allowFileAccess = false
            web.settings.allowContentAccess = false
            web.settings.blockNetworkLoads = !network
            web.addJavascriptInterface(Bridge(result), BRIDGE)
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url
                    if (url.host == HOST) return serve(skill, url)
                    // Nothing leaves the phone unless the call was approved as a send (I3).
                    return if (network) null else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (result.isCompleted) return
                    view.evaluateJavascript(call(data), null)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) result.completeExceptionally(IllegalStateException("the skill's page didn't load: ${error.description}"))
                }

                // Unhandled, a renderer that's gone takes the app with it. On the emulator, with E4B loaded, Android
                // stopped the page's renderer to free memory.
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    val why = if (detail.didCrash()) "the skill's page crashed" else "the phone stopped the skill's page to free memory"
                    result.completeExceptionally(IllegalStateException(why))
                    gone = true
                    return true
                }
            }
            web.loadUrl(pageUrl(skill, script))
            withTimeout(TIMEOUT_MS) { result.await() }
        } finally {
            if (!gone) web.removeJavascriptInterface(BRIDGE)
            web.destroy()
        }
    }

    private fun pageUrl(skill: AgentSkill, script: String): String {
        val file = script.ifBlank { "index.html" }.trimStart('/')
        return if (skill.base.startsWith("https://")) "${skill.base}/scripts/$file" else "https://$HOST/scripts/$file"
    }

    private fun serve(skill: AgentSkill, url: Uri): WebResourceResponse {
        val path = url.path.orEmpty().trimStart('/')
        val bytes = library.read(skill, path) ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        return WebResourceResponse(mimeOf(path), "utf-8", ByteArrayInputStream(bytes))
    }

    private class Bridge(private val result: CompletableDeferred<String>) {
        @JavascriptInterface
        fun onResultReady(value: String?) {
            result.complete(value.orEmpty())
        }
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
        private const val BRIDGE = "AiEdgeGallery"
        private const val TIMEOUT_MS = 60_000L

        /** Gallery's own call, word for word: data and secret go in as JSON strings, never into the page's code. */
        fun call(data: String, secret: String = ""): String = """
            (async function() {
                var startTs = Date.now();
                while(true) {
                  if (typeof ai_edge_gallery_get_result === 'function') {
                    break;
                  }
                  await new Promise(resolve=>{
                    setTimeout(resolve, 100)
                  });
                  if (Date.now() - startTs > 10000) {
                    break;
                  }
                }
                var result = await ai_edge_gallery_get_result(${JsonPrimitive(data)}, ${JsonPrimitive(secret)});
                AiEdgeGallery.onResultReady(result);
            })()
        """.trimIndent()

        fun mimeOf(path: String): String = when (path.substringAfterLast('.').lowercase()) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "wasm" -> "application/wasm"
            else -> "application/octet-stream"
        }
    }
}
