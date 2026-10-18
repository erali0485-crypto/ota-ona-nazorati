package uz.mirabaad.parentcontrol

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("parent_control", Context.MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var nameField: EditText
    private lateinit var tokenField: EditText
    private lateinit var keyField: EditText
    private lateinit var status: TextView
    private lateinit var usage: TextView
    private val endpoint = "https://dhquvmipvfvvlisfhfaz.supabase.co/functions/v1/device-ingest"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,24,24,24); setBackgroundColor(0xFFF3F6FB.toInt()) }
        root.addView(TextView(this).apply { text="ОТА-ОНА НАЗОРАТИ"; textSize=24f; setTextColor(0xFF172554.toInt()) })
        root.addView(TextView(this).apply { text="Телефондаги иловалардан фойдаланиш вақтини кўриш ва рухсат билан улаш"; textSize=15f; setPadding(0,10,0,14) })
        nameField=EditText(this).apply { hint="Қурилма номи"; setText(prefs.getString("name","")) }
        root.addView(nameField,params())
        tokenField=EditText(this).apply { hint="Қурилма токени"; setSingleLine(true); setText(prefs.getString("token","")) }
        root.addView(tokenField,params())
        keyField=EditText(this).apply { hint="Supabase publishable key"; setSingleLine(true); setText(prefs.getString("key","")) }
        root.addView(keyField,params())
        root.addView(Button(this).apply { text="Фойдаланиш вақтига рухсат бериш"; setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } },params())
        root.addView(Button(this).apply { text="Охирги 24 соат статистикаси"; setOnClickListener { showUsage() } },params())
        root.addView(Button(this).apply { text="Сақлаш ва серверга уланиш"; setOnClickListener {
            prefs.edit().putString("name",nameField.text.toString().trim()).putString("token",tokenField.text.toString().trim()).putString("key",keyField.text.toString().trim()).apply()
            connect()
        } },params())
        status=TextView(this).apply { text="Ҳолат: ҳали уланмаган"; textSize=14f; setPadding(0,12,0,12) }; root.addView(status)
        usage=TextView(this).apply { textSize=14f; setTextIsSelectable(true) }
        root.addView(ScrollView(this).apply { addView(usage) },LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        setContentView(root); showUsage()
    }
    private fun params()=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin=7 }
    private fun hasAccess():Boolean {
        val ops=getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow("android:get_usage_stats",android.os.Process.myUid(),packageName)==AppOpsManager.MODE_ALLOWED
    }
    private fun showUsage() {
        if(!hasAccess()){ usage.text="Статистикани кўриш учун юқоридаги рухсат тугмасини босинг. Созламаларда «Ота-она назорати»ни топиб, Usage access ни ёқинг."; return }
        val now=System.currentTimeMillis()
        val manager=getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats=manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY,now-86400000L,now).filter { it.totalTimeInForeground>0 }.sortedByDescending { it.totalTimeInForeground }
        usage.text=if(stats.isEmpty()) "Ҳали статистика йўқ. Иловаларни ишлатиб, қайта текширинг." else "ОХИРГИ 24 СОАТ\n\n"+stats.take(40).joinToString("\n") { s ->
            val m=s.totalTimeInForeground/60000
            s.packageName+" — "+(if(m>=60) "${m/60} соат ${m%60} дақиқа" else "${m} дақиқа")
        }
    }
    private fun connect() {
        val t=tokenField.text.toString().trim(); val k=keyField.text.toString().trim()
        if(t.length<32){status.text="Токен керак: бошқарув тизимида қурилма учун берилган токенни киритинг.";return}
        if(k.isBlank()){status.text="Supabase publishable key киритилмаган.";return}
        status.text="Серверга уланиш текширилмоқда…"
        executor.execute {
            try {
                val body=JSONObject().put("kind","heartbeat").put("app_version","1.0.0")
                val c=URL(endpoint).openConnection() as HttpURLConnection
                c.requestMethod="POST";c.connectTimeout=12000;c.readTimeout=12000;c.doOutput=true
                c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("apikey",k);c.setRequestProperty("Authorization","Bearer "+t)
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code=c.responseCode
                val stream=if(code in 200..299)c.inputStream else c.errorStream
                val response=stream?.bufferedReader()?.use { it.readText() } ?: ""
                runOnUiThread { status.text=if(code in 200..299) "СЕРВЕРГА УЛАНДИ: "+response else "Сервер HTTP "+code+" қайтарди. Рухсат ва токенни текшириш керак." }
            } catch(e:Exception) { runOnUiThread { status.text="Уланиш хатоси: "+(e.message ?: "номаълум") } }
        }
    }
}
