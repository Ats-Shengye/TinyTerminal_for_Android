package dev.tinyterminal

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity(), WebSocketCallback {

    companion object {
        private const val PREFS_NAME = "TinyTerminalPrefs"
        private const val SERVER_URL_KEY = "server_url"

        fun isTailscaleHost(host: String): Boolean {
            return host.matches(Regex("""^100\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))
        }
    }

    private lateinit var webView: WebView
    private lateinit var webSocketBridge: WebSocketBridge
    private var terminalService: TerminalService? = null
    private var serviceBound = false
    private val okHttpClient = OkHttpClient()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            terminalService = (binder as TerminalService.TerminalBinder).getService()
            terminalService?.callback = this@MainActivity
            webSocketBridge.connectionManager = terminalService
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            terminalService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        webView.setBackgroundColor(Color.BLACK)
        applyWindowInsets()
        setupBridge()
        setupWebView()
        startAndBindService()

        val serverUrl = getServerUrl()
        if (serverUrl.isEmpty()) {
            showServerUrlDialog()
        } else {
            loadUrl(serverUrl)
        }
    }

    private fun applyWindowInsets() {
        val rootLayout = findViewById<FrameLayout>(R.id.root_layout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, windowInsets ->
            val systemInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout()
            )
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val topTrim = (8 * resources.displayMetrics.density).toInt()
            val top = (systemInsets.top - topTrim).coerceAtLeast(0)
            val bottom = maxOf(systemInsets.bottom, imeInsets.bottom)
            view.setPadding(systemInsets.left, top, systemInsets.right, bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupBridge() {
        webSocketBridge = WebSocketBridge(webView)
        webView.addJavascriptInterface(webSocketBridge, "Android")
    }

    private fun startAndBindService() {
        val intent = Intent(this, TerminalService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()

                val requestHost = request.url.host
                if (url.endsWith("/client.js")
                    && requestHost != null
                    && isTailscaleHost(requestHost)) {
                    try {
                        val shimScript = assets.open("websocket-shim.js")
                            .bufferedReader()
                            .readText()

                        // 元のclient.jsをHTTPで取得
                        val response = okHttpClient.newCall(
                            Request.Builder().url(url).build()
                        ).execute()

                        val originalScript = response.body?.string() ?: ""
                        val combined = "$shimScript\n;\n$originalScript"

                        return WebResourceResponse(
                            "application/javascript",
                            "UTF-8",
                            ByteArrayInputStream(combined.toByteArray(Charsets.UTF_8))
                        )
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) {
                            Log.e("TinyTerminal", "Failed to inject shim", e)
                        }
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    private fun getServerUrl(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(SERVER_URL_KEY, "") ?: ""
    }

    private fun saveServerUrl(url: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(SERVER_URL_KEY, url).apply()
    }

    private fun isAllowedUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host ?: return false
        return isTailscaleHost(host)
    }

    private fun showServerUrlDialog() {
        val editText = EditText(this)
        editText.hint = getString(R.string.server_url_hint)

        AlertDialog.Builder(this)
            .setTitle(R.string.enter_server_url)
            .setView(editText)
            .setPositiveButton(R.string.ok) { _, _ ->
                val url = editText.text.toString().trim()
                if (url.isNotEmpty() && isAllowedUrl(url)) {
                    saveServerUrl(url)
                    loadUrl(url)
                } else if (url.isNotEmpty()) {
                    Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_LONG).show()
                    showServerUrlDialog()
                } else {
                    finish()
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun loadUrl(url: String) {
        webView.loadUrl(url)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // WebViewのレイアウト更新を待ってからresizeイベントを発火
        webView.post {
            webView.evaluateJavascript("window.dispatchEvent(new Event('resize'))", null)
        }
    }

    override fun onDestroy() {
        webView.clearCache(true)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }

    // WebSocketCallback implementation
    override fun onOpen(id: Int) {
        webSocketBridge.deliverOpen(id)
    }

    override fun onMessage(id: Int, data: String) {
        webSocketBridge.deliverMessage(id, data)
    }

    override fun onClose(id: Int, code: Int, reason: String, wasClean: Boolean) {
        webSocketBridge.deliverClose(id, code, reason, wasClean)
    }

    override fun onError(id: Int, message: String) {
        webSocketBridge.deliverError(id, message)
    }
}
