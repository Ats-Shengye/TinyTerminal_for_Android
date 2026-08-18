package dev.tinyterminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class TerminalService : Service(), WebSocketConnectionManager {

    companion object {
        private const val CHANNEL_ID = "tinyterminal_service"
        private const val NOTIFICATION_ID = 1
    }

    inner class TerminalBinder : Binder() {
        fun getService(): TerminalService = this@TerminalService
    }

    private val binder = TerminalBinder()

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val connections = ConcurrentHashMap<Int, WebSocket>()
    private var wakeLock: PowerManager.WakeLock? = null
    var callback: WebSocketCallback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("待機中"))
        acquireWakeLock()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun connect(id: Int, url: String, protocols: String) {
        val host = Uri.parse(url).host
        if (host == null || !MainActivity.isTailscaleHost(host)) {
            if (BuildConfig.DEBUG) {
                Log.w("TinyTerminal", "Rejected WebSocket to non-Tailscale host: $host")
            }
            callback?.onError(id, "Connection rejected: not a Tailscale host")
            callback?.onClose(id, 1008, "Policy violation", false)
            return
        }

        val request = Request.Builder().url(url).build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                updateNotification("接続中")
                callback?.onOpen(id)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                callback?.onMessage(id, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connections.remove(id)
                updateNotification("切断")
                callback?.onClose(id, code, reason, true)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connections.remove(id)
                updateNotification("接続エラー")
                callback?.onError(id, t.message ?: "Connection failed")
                callback?.onClose(id, 1006, t.message ?: "", false)
            }
        })

        connections[id] = ws
    }

    override fun send(id: Int, data: String) {
        connections[id]?.send(data)
    }

    override fun close(id: Int, code: Int, reason: String) {
        connections[id]?.close(code, reason)
        connections.remove(id)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TinyTerminal::WebSocket"
        ).apply {
            acquire(4 * 60 * 60 * 1000L) // 4時間タイムアウト
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "TinyTerminal接続",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "リモートターミナル接続状態"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TinyTerminal")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    override fun onDestroy() {
        connections.forEach { (_, ws) ->
            ws.close(1001, "Service destroyed")
        }
        connections.clear()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        super.onDestroy()
    }
}
