package dev.tinyterminal

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

class WebSocketBridge(
    private val webView: WebView
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    var connectionManager: WebSocketConnectionManager? = null

    @JavascriptInterface
    fun nativeConnect(id: Int, url: String, protocols: String) {
        connectionManager?.connect(id, url, protocols)
    }

    @JavascriptInterface
    fun nativeSend(id: Int, data: String) {
        connectionManager?.send(id, data)
    }

    @JavascriptInterface
    fun nativeClose(id: Int, code: Int, reason: String) {
        connectionManager?.close(id, code, reason)
    }

    // Kotlin → JS コールバック配信
    fun deliverOpen(id: Int) {
        runOnMainThread {
            webView.evaluateJavascript("window._wsShim.onNativeOpen($id)", null)
        }
    }

    fun deliverMessage(id: Int, data: String) {
        val quoted = JSONObject.quote(data)
        runOnMainThread {
            webView.evaluateJavascript(
                "window._wsShim.onNativeMessage($id, $quoted)",
                null
            )
        }
    }

    fun deliverClose(id: Int, code: Int, reason: String, wasClean: Boolean) {
        val quotedReason = JSONObject.quote(reason)
        runOnMainThread {
            webView.evaluateJavascript(
                "window._wsShim.onNativeClose($id, $code, $quotedReason, $wasClean)",
                null
            )
        }
    }

    fun deliverError(id: Int, message: String) {
        val quotedMsg = JSONObject.quote(message)
        runOnMainThread {
            webView.evaluateJavascript(
                "window._wsShim.onNativeError($id, $quotedMsg)",
                null
            )
        }
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
}

interface WebSocketConnectionManager {
    fun connect(id: Int, url: String, protocols: String)
    fun send(id: Int, data: String)
    fun close(id: Int, code: Int, reason: String)
}

interface WebSocketCallback {
    fun onOpen(id: Int)
    fun onMessage(id: Int, data: String)
    fun onClose(id: Int, code: Int, reason: String, wasClean: Boolean)
    fun onError(id: Int, message: String)
}
