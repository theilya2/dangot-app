package il.co.dangot.alerts

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AlertService : Service() {

    @Volatile private var running = false
    @Volatile private var streamGen = 0
    @Volatile private var currentTech: String? = null
    private var client: OkHttpClient? = null
    private var worker: Thread? = null
    @Volatile private var call: okhttp3.Call? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels()
        val n = foregroundNotif("מאזין לקריאות חדשות…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tech = Prefs.tech(this)
        if (tech == null) { stopSelf(); return START_NOT_STICKY }
        running = true
        if (tech != currentTech) {
            currentTech = tech
            try { call?.cancel() } catch (_: Exception) {}
            startStream(tech)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { worker?.interrupt() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("fg", "שירות התראות", NotificationManager.IMPORTANCE_LOW)
            )
            val alertCh = NotificationChannel("alert", "התראות קריאה", NotificationManager.IMPORTANCE_HIGH)
            alertCh.description = "התראות על קריאות חדשות וחריגות"
            alertCh.enableVibration(true)
            alertCh.setBypassDnd(true)
            nm.createNotificationChannel(alertCh)
        }
    }

    private fun foregroundNotif(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, "fg")
            .setContentTitle("Dangot Notifications")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateFg(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(1, foregroundNotif(text))
        } catch (_: Exception) {}
    }

    private fun startStream(tech: String) {
        val myGen = ++streamGen
        if (client == null) client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        val url = Const.DB + "/notify/" + Uri.encode(tech) + ".json"
        updateFg("מתחבר… ($tech)")
        worker = Thread {
            while (running && myGen == streamGen) {
                try {
                    val req = Request.Builder().url(url).header("Accept", "text/event-stream").build()
                    val c = client!!.newCall(req); call = c
                    c.execute().use { resp ->
                        if (!resp.isSuccessful) { updateFg("שגיאת חיבור ${resp.code}"); throw RuntimeException("http ${resp.code}") }
                        updateFg("מחובר ✓ — מאזין ($tech)")
                        val src = resp.body?.source() ?: return@use
                        var event = ""
                        while (running && myGen == streamGen && !src.exhausted()) {
                            val line = src.readUtf8Line() ?: break
                            when {
                                line.startsWith("event:") -> event = line.substring(6).trim()
                                line.startsWith("data:") -> handle(event, line.substring(5).trim())
                            }
                        }
                    }
                } catch (_: Exception) {}
                if (running && myGen == streamGen) { updateFg("מתחבר מחדש… ($tech)"); try { Thread.sleep(3000) } catch (_: Exception) {} }
            }
        }
        worker!!.start()
    }

    private fun handle(event: String, data: String) {
        if (event != "put" && event != "patch") return
        if (data.isEmpty() || data == "null") return
        try {
            val obj = JSONObject(data)
            val path = obj.optString("path", "/")
            val d = obj.opt("data")
            if (path == "/") {
                // full snapshot: alert fresh unseen items
                if (d is JSONObject) {
                    val keys = d.keys()
                    while (keys.hasNext()) {
                        val id = keys.next()
                        val n = d.optJSONObject(id) ?: continue
                        maybeAlert(id, n)
                    }
                }
            } else {
                val id = path.trim('/')
                if (id.isNotEmpty() && !id.contains('/')) {
                    if (d is JSONObject) maybeAlert(id, d)
                }
            }
        } catch (_: Exception) {}
    }

    private fun maybeAlert(id: String, n: JSONObject) {
        if (Prefs.isSeen(this, id)) return
        val ts = n.optLong("ts", 0L)
        // ignore items older than 2 hours (stale), but still mark seen
        if (ts in 1..(System.currentTimeMillis() - 2 * 3600_000L)) { Prefs.addSeen(this, id); return }
        Prefs.addSeen(this, id)
        fireAlert(id, n.optString("text", "קריאה חדשה"), n.optString("title", "התראה"))
    }

    private fun fireAlert(id: String, text: String, title: String) {
        val intent = Intent(this, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("id", id)
            putExtra("text", text)
            putExtra("title", title)
        }
        // direct launch (works when app foreground, or with draw-over-other-apps permission)
        try { startActivity(intent) } catch (_: Exception) {}
        val fsPi = PendingIntent.getActivity(
            this, id.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, "alert")
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fsPi, true)
            .setAutoCancel(true)
            .setOngoing(true)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(id.hashCode(), notif)
    }
}
