package uz.mirabaad.parentcontrol

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class UsageSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val endpoint = "https://dhquvmipvfvvlisfhfaz.supabase.co/functions/v1/device-ingest"

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("parent_control", Context.MODE_PRIVATE)
        val token = prefs.getString("token", "").orEmpty().trim()
        val key = prefs.getString("key", "").orEmpty().trim()
        val name = prefs.getString("name", "Muhammadbilol Android").orEmpty().trim()
        if (token.length < 32 || !key.startsWith("sb_publishable_")) return Result.failure()
        if (!hasUsageAccess()) return Result.retry()

        return try {
            val now = System.currentTimeMillis()
            val stats = (applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)
                .queryAndAggregateUsageStats(now - 24 * 60 * 60 * 1000L, now)
                .values
                .filter { it.totalTimeInForeground > 0 && it.packageName != applicationContext.packageName }
                .sortedByDescending { it.totalTimeInForeground }
                .take(40)

            post(JSONObject().put("kind", "heartbeat").put("app_version", "1.0.2")
                .put("device_name", name).put("platform", "Android"), token, key)

            for (stat in stats) {
                val appName = try {
                    val info = applicationContext.packageManager.getApplicationInfo(stat.packageName, 0)
                    applicationContext.packageManager.getApplicationLabel(info).toString()
                } catch (_: Exception) { stat.packageName }
                val summary = JSONObject()
                    .put("package", appName)
                    .put("package_id", stat.packageName)
                    .put("minutes_24h", stat.totalTimeInForeground / 60000L)
                    .put("source", "android_usage_stats")
                    .toString()
                post(JSONObject().put("kind", "event").put("event_type", "app_usage").put("summary", summary), token, key)
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun hasUsageAccess(): Boolean {
        val ops = applicationContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow("android:get_usage_stats", Process.myUid(), applicationContext.packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun post(body: JSONObject, token: String, key: String) {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("apikey", key)
            connection.setRequestProperty("x-device-token", token)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}