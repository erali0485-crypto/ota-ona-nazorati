package uz.mirabaad.parentcontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Visible, consent-based foreground service. Sends a small heartbeat every five seconds
 * while Android permits this foreground service to run. It does not read messages/calls.
 */
class HeartbeatService : Service() {
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val endpoint = "https://dhquvmipvfvvlisfhfaz.supabase.co/functions/v1/device-ingest"

    override fun onCreate() {
        super.onCreate()
        val channelId = "parent_control_connection"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Ота-она назорати алоқаси", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("Ота-она назорати ишлаяпти")
                .setContentText("Қурилма алоқаси ҳар 5 сонияда текширилади")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Ота-она назорати ишлаяпти")
                .setContentText("Қурилма алоқаси ҳар 5 сонияда текширилади")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(true)
                .build()
        }
        startForeground(4701, notification)
        scheduler.scheduleWithFixedDelay({ sendHeartbeat() }, 0, 5, TimeUnit.SECONDS)
    }

    private fun sendHeartbeat() {
        val prefs = getSharedPreferences("parent_control", Context.MODE_PRIVATE)
        val token = prefs.getString("token", "").orEmpty().trim()
        val key = prefs.getString("key", "").orEmpty().trim()
        val name = prefs.getString("name", "Muhammadbilol Android").orEmpty().trim()
        if (token.length < 32 || !key.startsWith("sb_publishable_")) return
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 4000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", key)
                setRequestProperty("x-device-token", token)
            }
            val body = JSONObject().put("kind", "heartbeat").put("app_version", "1.0.3")
                .put("device_name", name).put("platform", "Android").toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            connection.responseCode
        } catch (_: Exception) {
            // Keep service alive; next 5-second attempt retries when network returns.
        } finally {
            connection?.disconnect()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        scheduler.shutdownNow()
        super.onDestroy()
    }
}
