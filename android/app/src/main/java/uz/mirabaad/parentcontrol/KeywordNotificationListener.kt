package uz.mirabaad.parentcontrol

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Consent-based keyword alerts from visible Android notifications only.
 * It does not access notification history, hidden chats, keyboard input, or message databases.
 */
class KeywordNotificationListener : NotificationListenerService() {
    private val executor = Executors.newSingleThreadExecutor()
    private val endpoint = "https://dhquvmipvfvvlisfhfaz.supabase.co/functions/v1/device-ingest"
    private val keywords = listOf(
        "жиҳодга брамиз", "жиходга брамиз", "жиҳодга борамиз", "жиходга борамиз",
        "бродар", "пул бермасанг мазангни қочираман",
        "мен ҳоир уйдагиларни алдаб чиқаман", "мен ҳозир уйдагиларни алдаб чиқаман",
        "даданг ойинг билмасин", "букун кел", "бораман", "бораолмайман",
        "соғиндим", "согиндим", "менҳам", "мен ҳам",
        "қачон кўришамиз", "качон куришамиз"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = getSharedPreferences("parent_control", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("data_consent_granted", false) ||
            !prefs.getBoolean("keyword_notification_consent", false)) return
        if (sbn.packageName == packageName || sbn.packageName.startsWith("com.android.systemui")) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty()
        val lines = extras.getCharSequenceArray("android.textLines")
            ?.joinToString("\n") { it.toString() }.orEmpty()
        val bodyText = if (bigText.isNotBlank()) bigText else if (lines.isNotBlank()) lines else text
        val visibleText = listOf(title, bodyText).filter { it.isNotBlank() }
            .distinct().joinToString(": ").take(330)
        if (visibleText.isBlank()) return

        val normalized = normalize(visibleText)
        val matched = keywords.filter { normalize(it) in normalized }.distinct()
        if (matched.isEmpty()) return

        val token = prefs.getString("token", "").orEmpty().trim()
        val key = prefs.getString("key", "").orEmpty().trim()
        if (token.length < 32 || !key.startsWith("sb_publishable_")) return

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(sbn.postTime))
        val summary = ("Калит сўз: ${matched.joinToString(", ")} | Илова: ${appLabel(sbn.packageName)} " +
            "(${sbn.packageName}) | Вақт: $timestamp | Хабар матни: $visibleText").take(500)
        val payload = JSONObject().put("kind", "event")
            .put("event_type", "keyword_alert").put("summary", summary).toString()

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
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                connection.responseCode
            } catch (_: Exception) {
                // Notification content is not stored locally.
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString().take(70)
    } catch (_: Exception) { pkg }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKC)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ").trim()
}
