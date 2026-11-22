package il.co.dangot.alerts

import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class AlertActivity : AppCompatActivity() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var alertId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        setContentView(R.layout.activity_alert)

        alertId = intent.getStringExtra("id") ?: ""
        val title = intent.getStringExtra("title") ?: "התראה"
        val text = intent.getStringExtra("text") ?: "קריאה חדשה"

        findViewById<TextView>(R.id.alert_title).text = title
        findViewById<TextView>(R.id.alert_text).text = text

        findViewById<Button>(R.id.alert_ack).setOnClickListener { acknowledge() }

        startAlarm()
    }

    private fun startAlarm() {
        try {
            var uri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setDataSource(this@AlertActivity, uri!!)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {}
        try {
            vibrator = getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 600, 400), 0)
            }
        } catch (_: Exception) {}
    }

    private fun stopAlarm() {
        try { player?.stop(); player?.release() } catch (_: Exception) {}
        player = null
        try { vibrator?.cancel() } catch (_: Exception) {}
    }

    private fun acknowledge() {
        stopAlarm()
        try {
            getSystemService(NotificationManager::class.java).cancel(alertId.hashCode())
        } catch (_: Exception) {}
        sendAck()
        finish()
    }

    private fun sendAck() {
        val tech = Prefs.tech(this) ?: ""
        val id = alertId
        if (id.isEmpty()) return
        Thread {
            try {
                val client = OkHttpClient()
                // record the ack
                val body = JSONObject().put("tech", tech).put("at", System.currentTimeMillis())
                    .toString().toRequestBody("application/json".toMediaType())
                client.newCall(
                    Request.Builder().url(Const.DB + "/ack/" + Uri.encode(id) + ".json")
                        .put(body).build()
                ).execute().close()
                // remove the delivered notification so it won't fire again elsewhere
                client.newCall(
                    Request.Builder()
                        .url(Const.DB + "/notify/" + Uri.encode(tech) + "/" + Uri.encode(id) + ".json")
                        .delete().build()
                ).execute().close()
            } catch (_: Exception) {}
        }.start()
    }

    override fun onDestroy() {
        stopAlarm()
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        // require explicit acknowledge — ignore back button
    }
}
