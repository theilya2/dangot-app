package il.co.dangot.alerts

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.text.Html
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONTokener
import java.util.concurrent.TimeUnit

/** Shows the selected technician's daily schedule, read from Firebase /schedule/techs/<tech>. */
class ScheduleActivity : AppCompatActivity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private lateinit var body: TextView
    private lateinit var progress: ProgressBar
    private lateinit var header: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule)

        header = findViewById(R.id.sched_header)
        body = findViewById(R.id.sched_body)
        progress = findViewById(R.id.sched_progress)

        val tech = Prefs.tech(this)
        header.text = if (tech != null) "הלו״ז של $tech" else "לא נבחר טכנאי"

        findViewById<Button>(R.id.sched_refresh).setOnClickListener { load() }
        findViewById<Button>(R.id.sched_back).setOnClickListener { finish() }

        load()
    }

    private fun load() {
        val tech = Prefs.tech(this) ?: run {
            body.text = "בחר טכנאי במסך הראשי והירשם."
            return
        }
        progress.visibility = View.VISIBLE
        body.text = ""
        Thread {
            val text = fetch(tech)
            runOnUiThread {
                progress.visibility = View.GONE
                if (text.isNullOrBlank() || text == "null") {
                    body.text = "אין לו״ז זמין עבורך כרגע.\nהלו״ז מתעדכן כשהיומן נדחף מהתוסף."
                } else {
                    val html = text.replace("\n", "<br>")
                    body.text = if (Build.VERSION.SDK_INT >= 24)
                        Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
                    else @Suppress("DEPRECATION") Html.fromHtml(html)
                }
            }
        }.start()
    }

    private fun fetch(tech: String): String? {
        return try {
            val url = "${Const.DB}/schedule/techs/${Uri.encode(tech)}.json"
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return "שגיאת טעינה (${r.code})"
                val raw = r.body?.string() ?: return null
                if (raw.isBlank() || raw == "null") return null
                val v = JSONTokener(raw).nextValue()
                if (v is String) v else v.toString()
            }
        } catch (e: Exception) {
            "שגיאה: ${e.message}"
        }
    }
}
