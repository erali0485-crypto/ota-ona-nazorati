package uz.mirabaad.parentcontrol

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Consent-based keyword alerts from visible Android notifications only.
 * Does not access notification history, hidden chats, keyboard input, or message databases.
 */
class KeywordNotificationListener : NotificationListenerService() {
    private val executor = Executors.newSingleThreadExecutor()
    private val endpoint = "https://dhquvmipvfvvlisfhfaz.supabase.co/functions/v1/device-ingest"
    private val keywords = listOf(
        "жиҳодга брамиз", "жиҳодга борамиз", "бродар",
        "пул бермасанг мазангни қочираман",
        "мен ҳоир уйдагиларни алдаб чиқаман",
        "мен ҳозир уйдагиларни алдаб чиқаман",
        "даданг ойинг билмасин", "букун кел", "бораман",
        "бораолмайман", "соғиндим", "менҳам", "мен ҳам"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = getSharedPreferences("parent_control", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("data_consent_granted", false) ||
            !prefs.getBoolean("keyword_notification_consent", false)) return

        // Avoid this app's own notifications and system noise.
        if (sbn.packageName == packageName || sbn.packageName.startsWith("com.android.systemui")) return
        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val lines = extras.getCharSequenceArray("android.textLines")?.joinToString("\n") { it.toString() }.orEmpty()
        val visibleText = listOf(title, text, lines).filter { it.isNotBlank() }.distinct().joinToString("\n").take(3000)
        if (visibleText.isBlank()) return

        val normalized = normalize(visibleText)
        val matched = keywords.filter { normalize(it) in normalized }.distinct()
        if (matched.isEmpty()) return

        val token = prefs.getString("token", "").orEmpty().trim()
        val key = prefs.getString("key", "").orEmpty().trim()
        if (token.length < 32 || !key.startsWith("sb_publishable_")) return

        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("apikey", key)
                    setRequestProperty("x-device-token", token)
                }
                val summary = JSONObject()
                    .put("source", "visible_notification_keyword_match")
                    .put("app_package", sbn.packageName)
                    .put("app_name", appLabel(sbn.packageName))
                    .put("matched_keywords", matched)
                    .put("notification_text", visibleText)
                    .put("posted_at", sbn.postTime)
                    .toString()
                val body = JSONObject().put("kind", "event")
                    .put("event_type", "keyword_alert").put("summary", summary).toString()
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                connection.responseCode
            } catch (_: Exception) {
                // Do not retain private notification content locally; a later notification can retry.
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKC)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ").trim()
}
