package com.deniscerri.ytdl.ui.more.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Build
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.setPadding
import com.deniscerri.ytdl.receiver.ShareActivity
import com.deniscerri.ytdl.ui.BaseActivity
import java.net.URI
import java.util.Locale

/** Lightweight browser that hands ordinary media URLs to the existing yt-dlp flow. */
class BrowserActivity : BaseActivity() {
    private val tabs = mutableListOf<WebView>()
    private var activeTab = 0
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var download: Button
    private var adBlockEnabled = true
    private var privateMode = false
    private var desktopMode = false
    @Volatile private var detectedMediaUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        address = EditText(this).apply {
            hint = "Search or enter website URL"; setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO) { navigateFromAddress(); true } else false
            }
        }
        status = TextView(this).apply { text = "MediaFetch Browser" }
        newTab(intent.getStringExtra("url") ?: "https://www.google.com")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newTab(url: String) {
        val web = WebView(this)
        applySettings(web)
        web.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) { status.text = title ?: "MediaFetch Browser" }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                address.setText(url ?: ""); detectedMediaUrl = null; download.isEnabled = false
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                address.setText(url ?: "")
                view?.evaluateJavascript("(function(){var e=document.querySelector('video,audio');return e?(e.currentSrc||e.src||''):''})()") { value ->
                    val candidate = value?.trim('"')?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    if (candidate != null && isMediaUrl(candidate)) {
                        detectedMediaUrl = candidate
                        runOnUiThread { download.isEnabled = true; status.text = "Media stream detected" }
                    }
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val scheme = request?.url?.scheme ?: return false
                return scheme != "http" && scheme != "https"
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                val urlText = request?.url?.toString() ?: return null
                if (isMediaUrl(urlText)) {
                    detectedMediaUrl = urlText
                    runOnUiThread { download.isEnabled = true; status.text = "Media stream detected" }
                }
                if (adBlockEnabled && isBlockedHost(request.url.host ?: "")) {
                    return android.webkit.WebResourceResponse("text/plain", "utf-8", 204, "Blocked", emptyMap(), null)
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
        tabs.add(web)
        activeTab = tabs.lastIndex
        showActiveTab()
        web.loadUrl(normalizeUrl(url))
    }

    private fun showActiveTab() {
        (address.parent as? ViewGroup)?.removeView(address)
        (status.parent as? ViewGroup)?.removeView(status)
        (currentWebView().parent as? ViewGroup)?.removeView(currentWebView())
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(8) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(address, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(Button(this).apply { text = "Go"; setOnClickListener { navigateFromAddress() } })
        root.addView(bar)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(Button(this).apply { text = "+"; setOnClickListener { newTab("https://www.google.com") } })
        controls.addView(Button(this).apply { text = "Tabs"; setOnClickListener { cycleTab() } })
        controls.addView(Button(this).apply { text = if (privateMode) "Private ON" else "Private"; setOnClickListener {
            privateMode = !privateMode; applySettings(currentWebView())
            text = if (privateMode) "Private ON" else "Private"
            Toast.makeText(this@BrowserActivity, "Private mode disables cache/history; WebView cookie isolation is not guaranteed", Toast.LENGTH_LONG).show()
        } })
        controls.addView(Button(this).apply { text = if (adBlockEnabled) "Block ON" else "Block OFF"; setOnClickListener {
            adBlockEnabled = !adBlockEnabled; text = if (adBlockEnabled) "Block ON" else "Block OFF"; currentWebView().reload()
        } })
        controls.addView(Button(this).apply { text = "Desktop"; setOnClickListener {
            desktopMode = !desktopMode; applySettings(currentWebView()); currentWebView().reload()
        } })
        download = Button(this).apply {
            text = "Download"; isEnabled = detectedMediaUrl != null
            setOnClickListener { sendToDownloader(detectedMediaUrl ?: currentWebView().url) }
        }
        controls.addView(download)
        root.addView(controls)
        root.addView(status)
        root.addView(currentWebView(), LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun currentWebView() = tabs[activeTab]
    private fun cycleTab() {
        if (tabs.isEmpty()) return
        activeTab = (activeTab + 1) % tabs.size
        detectedMediaUrl = null; showActiveTab(); address.setText(currentWebView().url ?: "")
    }
    private fun navigateFromAddress() { currentWebView().loadUrl(normalizeUrl(address.text.toString())) }
    private fun normalizeUrl(input: String): String {
        val value = input.trim()
        if (value.isEmpty()) return "https://www.google.com"
        return if (value.contains(" ") || !value.contains(".")) "https://www.google.com/search?q=" + java.net.URLEncoder.encode(value, "UTF-8")
        else if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun applySettings(web: WebView) {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = !privateMode
        web.settings.databaseEnabled = !privateMode
        web.settings.cacheMode = if (privateMode) android.webkit.WebSettings.LOAD_NO_CACHE else android.webkit.WebSettings.LOAD_DEFAULT
        web.settings.userAgentString = if (desktopMode) "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36" else android.webkit.WebSettings.getDefaultUserAgent(this)
        web.settings.useWideViewPort = desktopMode
        web.settings.loadWithOverviewMode = desktopMode
        web.settings.javaScriptCanOpenWindowsAutomatically = false
        web.settings.setSupportMultipleWindows(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) web.settings.safeBrowsingEnabled = true
    }
    private fun isMediaUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.lowercase(Locale.ROOT) }.getOrDefault("")
        return listOf(".mp4", ".m4a", ".mp3", ".webm", ".mkv", ".m3u8", ".mpd", ".aac", ".ogg").any { path.endsWith(it) }
    }
    private fun isBlockedHost(host: String): Boolean {
        val h = host.lowercase(Locale.ROOT)
        return BLOCKED_HOSTS.any { h == it || h.endsWith(".$it") }
    }
    private fun sendToDownloader(url: String?) {
        if (url.isNullOrBlank() || !(url.startsWith("https://") || url.startsWith("http://"))) {
            Toast.makeText(this, "No supported media URL detected", Toast.LENGTH_SHORT).show(); return
        }
        startActivity(Intent(this, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND; type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url)
        })
    }
    override fun onBackPressed() {
        if (tabs.isNotEmpty() && currentWebView().canGoBack()) currentWebView().goBack() else super.onBackPressed()
    }
    override fun onDestroy() { tabs.forEach { it.stopLoading(); it.destroy() }; tabs.clear(); super.onDestroy() }
    companion object {
        private val BLOCKED_HOSTS = setOf("doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "adnxs.com", "taboola.com", "outbrain.com", "popads.net", "popcash.net", "exoclick.com", "scorecardresearch.com", "googletagmanager.com")
    }
}
