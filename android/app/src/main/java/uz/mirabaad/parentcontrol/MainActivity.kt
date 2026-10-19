package uz.mirabaad.parentcontrol

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("parent_control", Context.MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var nameField: EditText
    private lateinit var tokenField: EditText
    private lateinit var keyField: EditText
    private lateinit var status: TextView
    private lateinit var usage: TextView
    private lateinit var rootLayout: LinearLayout
    private var parentPanelOpen = false
    private val endpoint = "https://dhquvmipvfvvlisfhfaz.supabase.co/functions/v1/device-ingest"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,24,24,24); setBackgroundColor(0xFFF3F6FB.toInt()) }
        rootLayout = root
        root.addView(TextView(this).apply { text="ОТА-ОНА НАЗОРАТИ"; textSize=24f; setTextColor(0xFF172554.toInt()) })
        root.addView(Button(this).apply { text="📊 ОТА-ОНА ПАНЕЛИ — шу илова ичида"; setOnClickListener { openParentPanel() } },params())
        root.addView(TextView(this).apply { text="Фақат ота-она ва боланинг хабардор розилиги билан ишлатилади. Фойдаланиш вақти серверга юборилади."; textSize=14f; setPadding(0,10,0,14) })
        nameField=EditText(this).apply { hint="Қурилма номи"; setText(prefs.getString("name","")) }
        root.addView(nameField,params())
        tokenField=EditText(this).apply { hint="Қурилма токени"; setSingleLine(true); setText(prefs.getString("token","")) }
        root.addView(tokenField,params())
        keyField=EditText(this).apply { hint="Supabase publishable key (sb_publishable_…)"; setSingleLine(true); setText(prefs.getString("key","")) }
        root.addView(keyField,params())
        root.addView(Button(this).apply { text="Фойдаланиш вақтига рухсат бериш"; setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } },params())
        root.addView(Button(this).apply { text="Охирги 24 соат статистикаси"; setOnClickListener { showUsage() } },params())
        root.addView(Button(this).apply { text="Сақлаш ва статистикани серверга юбориш"; setOnClickListener {
            prefs.edit().putString("name",nameField.text.toString().trim()).putString("token",tokenField.text.toString().trim()).putString("key",keyField.text.toString().trim()).apply()
            requestDataConsent {
                scheduleUsageSync()
                connect()
            }
        } },params())
        status=TextView(this).apply { text="Ҳолат: ҳали уланмаган"; textSize=14f; setPadding(0,12,0,12) }; root.addView(status)
        usage=TextView(this).apply { textSize=14f; setTextIsSelectable(true) }
        root.addView(ScrollView(this).apply { addView(usage) },LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        setContentView(root); showUsage()
        if (prefs.getString("token", "").orEmpty().length >= 32 && prefs.getString("key", "").orEmpty().startsWith("sb_publishable_")) {
            Toast.makeText(this, "Доим назоратдасиз, болаларим", Toast.LENGTH_SHORT).show()
            requestDataConsent { scheduleUsageSync() }
        } else {
            Toast.makeText(this, "Доим назоратдасиз, болаларим", Toast.LENGTH_SHORT).show()
        }
    }
    private fun requestDataConsent(onGranted: () -> Unit) {
        if (prefs.getBoolean("data_consent_granted", false)) {
            onGranted()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Маълумот юборишга рухсат")
            .setMessage("Бу илова қурилманинг ишлаётганини текшириш ва ўрнатилган иловалардан фойдаланиш вақти статистикасини ота-она назорати панелига юбориши мумкин. Шахсий чатлар, хабарлар матни ва клавиатурада ёзилган сўзлар йиғилмайди. Давом этиш учун бола ва ота-она рухсати керак.")
            .setPositiveButton("Рухсат бераман") { _, _ ->
                prefs.edit().putBoolean("data_consent_granted", true).apply()
                onGranted()
            }
            .setNegativeButton("Рухсат бермайман", null)
            .setCancelable(false)
            .show()
    }

    private fun openParentPanel() {
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.loadUrl("https://erali0485-crypto.github.io/ota-ona-nazorati/")
        parentPanelOpen = true
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF3F6FB.toInt())
            addView(Button(this@MainActivity).apply {
                text = "← Орқага — ягона илова"
                setOnClickListener { parentPanelOpen = false; setContentView(rootLayout) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (parentPanelOpen) {
            parentPanelOpen = false
            setContentView(rootLayout)
        } else super.onBackPressed()
    }
    private fun scheduleUsageSync() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<UsageSyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork("parent-control-app-usage-sync", ExistingPeriodicWorkPolicy.UPDATE, request)
        if (::status.isInitialized) status.text = "Автоматик синхронлаш режалаштирилди. Android батарея чекловлари сабаб кечикиши мумкин."
    }
    private fun params()=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin=7 }
    private fun hasAccess():Boolean {
        val ops=getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow("android:get_usage_stats",android.os.Process.myUid(),packageName)==AppOpsManager.MODE_ALLOWED
    }
    private fun getUsage(): List<Pair<String,Long>> {
        if(!hasAccess()) return emptyList()
        val now=System.currentTimeMillis()
        val manager=getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        return manager.queryAndAggregateUsageStats(now-86400000L,now).values
            .filter { it.totalTimeInForeground>0 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(30)
            .map {
                val label = try {
                    val info = packageManager.getApplicationInfo(it.packageName, 0)
                    packageManager.getApplicationLabel(info).toString()
                } catch (_: Exception) { it.packageName }
                "\$label|\${it.packageName}" to (it.totalTimeInForeground/60000L)
            }
    }
    private fun showUsage() {
        if(!hasAccess()){ usage.text="Статистика учун рухсат берилмаган. «Фойдаланиш вақтига рухсат бериш»ни босинг, «Ота-она назорати»ни танланг ва Usage access рухсатини ёқинг."; return }
        val rows=getUsage()
        usage.text=if(rows.isEmpty()) "Ҳали статистика йўқ. Иловаларни ишлатиб қайта текширинг." else "ОХИРГИ 24 СОАТ\n\n"+rows.joinToString("\n") { (pkg,m) -> "$pkg — "+(if(m>=60) "${m/60} соат ${m%60} дақиқа" else "$m дақиқа") }
    }
    private fun post(body: JSONObject, token: String, key: String): Pair<Int,String> {
        val c=URL(endpoint).openConnection() as HttpURLConnection
        c.requestMethod="POST";c.connectTimeout=12000;c.readTimeout=12000;c.doOutput=true
        c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("apikey",key);c.setRequestProperty("x-device-token",token)
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code=c.responseCode
        val stream=if(code in 200..299)c.inputStream else c.errorStream
        val response=stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to response
    }
    private fun connect() {
        val t=tokenField.text.toString().trim(); val k=keyField.text.toString().trim()
        if(!hasAccess()){ status.text="Аввал Usage access рухсатини ёқинг."; return }
        if(t.length<32){status.text="Қурилма токени йўқ ёки нотўғри. Серверда қурилма рўйхатга олиниши керак.";return}
        if(!k.startsWith("sb_publishable_")){status.text="Supabase publishable key sb_publishable_ билан бошланиши керак.";return}
        status.text="Статистика серверга юборилмоқда…"
        executor.execute {
            try {
                val heartbeat=JSONObject().put("kind","heartbeat").put("app_version","1.0.2").put("device_name",nameField.text.toString().trim())
                val (code,response)=post(heartbeat,t,k)
                if(code !in 200..299) throw Exception("HTTP $code: $response")
                val rows=getUsage()
                var sent=0
                for ((pkg,minutes) in rows) {
                    val summary=JSONObject().put("package",pkg.substringBefore("|")).put("package_id",pkg.substringAfter("|",pkg)).put("minutes_24h",minutes).put("source","android_usage_stats").toString()
                    val (ec,er)=post(JSONObject().put("kind","event").put("event_type","app_usage").put("summary",summary),t,k)
                    if(ec !in 200..299) throw Exception("Статистика юборишда HTTP $ec: $er")
                    sent++
                }
                runOnUiThread { status.text="СЕРВЕРГА УЛАНДИ. 24 соатлик статистика юборилди: $sent та илова. Панелни янгиланг." }
            } catch(e:Exception) { runOnUiThread { status.text="Уланиш хатоси: "+(e.message ?: "номаълум") } }
        }
    }
}
