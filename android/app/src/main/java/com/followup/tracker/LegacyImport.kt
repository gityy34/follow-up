package com.followup.tracker

import android.annotation.SuppressLint
import android.app.Activity
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONTokener
import kotlin.coroutines.resume

private const val legacyHost = "appassets.androidplatform.net"
internal fun legacyId(kind: String, value: String) = UUID.nameUUIDFromBytes("follow-up-v1:$kind:$value".toByteArray()).toString()

internal data class LegacyRow(val id: String, val title: String, val contact: String, val due: Long, val note: String, val done: Boolean)

internal fun parseLegacy(text: String): List<LegacyRow> {
    val items = JSONArray(text)
    return List(items.length()) { index ->
        val row = items.getJSONObject(index)
        val due = runCatching { LocalDate.parse(row.optString("date")).atTime(12, 0).atZone(zone()).toInstant().toEpochMilli() }
            .getOrDefault(now())
        LegacyRow(row.optString("id").ifBlank { index.toString() }, row.optString("title").trim().ifBlank { "Follow-up" }.take(120),
            row.optString("contact").trim().ifBlank { "General follow-ups" }.take(100), due, row.optString("notes").take(2000), row.optBoolean("done"))
    }
}

@SuppressLint("SetJavaScriptEnabled")
internal suspend fun Activity.importLegacy(repo: Repository) {
    val settings = getSharedPreferences("previous_web_tracker", android.content.Context.MODE_PRIVATE)
    if (settings.getBoolean("imported", false)) return
    val oldStorage = File(applicationInfo.dataDir, "app_webview/Default/Local Storage")
    if (!oldStorage.isDirectory) return
    val reader = WebView(this)
    try {
        reader.settings.javaScriptEnabled = true
        reader.settings.domStorageEnabled = true
        reader.settings.allowFileAccess = false
        reader.settings.allowContentAccess = false
        reader.settings.blockNetworkLoads = true
        val raw = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine<String?> { result ->
                reader.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                        WebResourceResponse("text/html", "UTF-8", "<!doctype html><meta charset=utf-8>".byteInputStream())

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                    override fun onPageFinished(view: WebView, url: String?) {
                        if (url != "https://$legacyHost/" || !result.isActive) return
                        view.evaluateJavascript("localStorage.getItem('follow-up.entries.v1') || '[]'") { value ->
                            if (result.isActive) result.resume(JSONTokener(value ?: "null").nextValue() as? String)
                        }
                    }
                }
                reader.loadUrl("https://$legacyHost/")
            }
        } ?: return
        repo.importLegacy(parseLegacy(raw))
        settings.edit().putBoolean("imported", true).apply()
    } finally { reader.destroy() }
}
