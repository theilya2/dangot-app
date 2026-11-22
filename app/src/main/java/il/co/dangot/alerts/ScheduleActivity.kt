package il.co.dangot.alerts

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Shows the selected technician's daily schedule as clean cards, read from Firebase /schedule. */
class ScheduleActivity : AppCompatActivity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private lateinit var list: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var header: TextView
    private lateinit var sub: TextView

    private data class Contact(val label: String, val phone: String?)
    private data class Case(
        val idx: String, val caseId: String, val type: String, val sla: String,
        val customer: String, val address: String, val contacts: List<Contact>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule)

        header = findViewById(R.id.sched_header)
        sub = findViewById(R.id.sched_sub)
        list = findViewById(R.id.sched_list)
        progress = findViewById(R.id.sched_progress)

        val tech = Prefs.tech(this)
        header.text = if (tech != null) "הלו״ז של $tech" else "לא נבחר טכנאי"

        findViewById<Button>(R.id.sched_refresh).setOnClickListener { load() }
        findViewById<Button>(R.id.sched_back).setOnClickListener { finish() }

        load()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun load() {
        val tech = Prefs.tech(this) ?: run {
            showMessage("בחר טכנאי במסך הראשי והירשם.")
            return
        }
        progress.visibility = View.VISIBLE
        list.removeAllViews()
        Thread {
            var raw: String? = null
            var dateStr = ""
            var atMs = 0L
            try {
                val req = Request.Builder().url("${Const.DB}/schedule.json").build()
                client.newCall(req).execute().use { r ->
                    if (r.isSuccessful) {
                        val b = r.body?.string()
                        if (!b.isNullOrBlank() && b != "null") {
                            val o = JSONObject(b)
                            dateStr = o.optString("date", "")
                            atMs = o.optLong("at", 0L)
                            raw = o.optJSONObject("techs")?.optString(tech, "")
                        }
                    }
                }
            } catch (_: Exception) {}
            val text = raw
            runOnUiThread {
                progress.visibility = View.GONE
                sub.text = buildSub(dateStr, atMs)
                if (text.isNullOrBlank() || text == "null") {
                    showMessage("אין לו״ז זמין עבורך כרגע.\nהלו״ז מתעדכן כשהיומן נדחף מהתוסף.")
                    return@runOnUiThread
                }
                val cases = parse(text)
                if (cases.isEmpty()) { showMessage(stripTags(text)); return@runOnUiThread }
                for (c in cases) list.addView(card(c))
            }
        }.start()
    }

    private fun buildSub(date: String, at: Long): String {
        val parts = ArrayList<String>()
        if (date.isNotBlank()) parts.add(date)
        if (at > 0) parts.add("עודכן " + SimpleDateFormat("HH:mm", Locale.US).format(Date(at)))
        return parts.joinToString(" · ")
    }

    private fun showMessage(msg: String) {
        list.removeAllViews()
        val tv = TextView(this)
        tv.text = msg
        tv.textSize = 15f
        tv.setTextColor(Color.parseColor("#55606C"))
        tv.setPadding(dp(8), dp(24), dp(8), dp(8))
        list.addView(tv)
    }

    /** decode the Telegram-HTML entities and strip tags, keep newlines */
    private fun stripTags(s: String): String = s
        .replace("<b>", "").replace("</b>", "")
        .replace("<i>", "").replace("</i>", "")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

    private val phoneRe = Regex("0\\d[\\d\\- ]{6,10}\\d")

    private fun parse(rawText: String): List<Case> {
        val text = stripTags(rawText)
        val lines = text.split("\n")
        val out = ArrayList<Case>()
        var idx = ""; var caseId = ""; var type = ""; var sla = ""
        var customer = ""; var address = ""; var contacts = ArrayList<Contact>()
        var open = false
        val headRe = Regex("^(\\d+)\\.\\s*(\\S+)\\s*—\\s*(.*)$")

        fun flush() {
            if (open && caseId.isNotBlank())
                out.add(Case(idx, caseId, type, sla, customer, address, ArrayList(contacts)))
            idx = ""; caseId = ""; type = ""; sla = ""; customer = ""; address = ""
            contacts = ArrayList(); open = false
        }

        for (line in lines) {
            val t = line.trim()
            val m = headRe.find(t)
            when {
                m != null -> {
                    flush()
                    open = true
                    idx = m.groupValues[1]
                    caseId = m.groupValues[2]
                    val rest = m.groupValues[3].split("·").map { it.trim() }
                    type = rest.getOrElse(0) { "" }
                    sla = rest.getOrElse(1) { "" }
                }
                t.startsWith("👤") -> customer = t.removePrefix("👤").trim()
                t.startsWith("📍") -> address = t.removePrefix("📍").trim()
                t.startsWith("🧑") || t.startsWith("🏢") -> {
                    val emoji = t.substring(0, 2)
                    var rest = t.substring(2).trim()
                    val ph = phoneRe.find(rest)?.value?.filter { it.isDigit() }
                    rest = rest.replace("📞", "").replace(phoneRe, "").replace("·", "").trim()
                    contacts.add(Contact("$emoji $rest".trim(), ph))
                }
            }
        }
        flush()
        return out
    }

    private fun slaColor(sla: String, type: String): Int = when {
        sla.contains("4") && !sla.contains("24") && !sla.contains("48") -> Color.parseColor("#E53935")
        sla.contains("24") -> Color.parseColor("#F57C00")
        sla.contains("48") -> Color.parseColor("#2E7D32")
        type.contains("התקנה") -> Color.parseColor("#1565C0")
        else -> Color.parseColor("#607D8B")
    }

    private fun card(c: Case): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundResource(R.drawable.card_bg)
        card.setPadding(dp(14), dp(12), dp(14), dp(12))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        card.layoutParams = lp

        // title row: case id + type/sla badge
        val titleRow = LinearLayout(this)
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL

        val cid = TextView(this)
        cid.text = "${c.idx}. ${c.caseId}"
        cid.setTextColor(Color.parseColor("#0D3B66"))
        cid.setTypeface(null, Typeface.BOLD)
        cid.textSize = 17f
        val cidLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        cid.layoutParams = cidLp
        titleRow.addView(cid)

        val badgeText = listOf(c.type, c.sla).filter { it.isNotBlank() }.joinToString(" · ")
        if (badgeText.isNotBlank()) {
            val badge = TextView(this)
            badge.text = badgeText
            badge.setTextColor(Color.WHITE)
            badge.textSize = 12f
            badge.setTypeface(null, Typeface.BOLD)
            badge.setPadding(dp(10), dp(4), dp(10), dp(4))
            val bg = resources.getDrawable(R.drawable.badge_bg, theme).mutate()
            bg.setTint(slaColor(c.sla, c.type))
            badge.background = bg
            titleRow.addView(badge)
        }
        card.addView(titleRow)

        if (c.customer.isNotBlank()) card.addView(line("👤", c.customer, false))
        if (c.address.isNotBlank()) {
            val addr = line("📍", c.address, true)
            addr.setOnClickListener {
                try {
                    val uri = Uri.parse("geo:0,0?q=" + Uri.encode(c.address))
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: Exception) {}
            }
            card.addView(addr)
        }
        for (ct in c.contacts) card.addView(contactRow(ct))
        return card
    }

    private fun line(emoji: String, text: String, linkish: Boolean): TextView {
        val tv = TextView(this)
        tv.text = "$emoji  $text"
        tv.textSize = 14.5f
        tv.setTextColor(if (linkish) Color.parseColor("#1565C0") else Color.parseColor("#2A2F36"))
        tv.setPadding(0, dp(6), 0, 0)
        tv.setLineSpacing(0f, 1.1f)
        return tv
    }

    private fun contactRow(ct: Contact): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(7), 0, 0)

        val name = TextView(this)
        name.text = ct.label + (if (ct.phone != null) "  ·  ${pretty(ct.phone)}" else "")
        name.textSize = 14.5f
        name.setTextColor(Color.parseColor("#2A2F36"))
        name.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(name)

        if (ct.phone != null) {
            val call = TextView(this)
            call.text = "📞 חייג"
            call.textSize = 14f
            call.setTypeface(null, Typeface.BOLD)
            call.setTextColor(Color.parseColor("#2E7D32"))
            call.setBackgroundResource(R.drawable.call_btn_bg)
            call.setPadding(dp(14), dp(7), dp(14), dp(7))
            call.setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${ct.phone}"))) } catch (_: Exception) {}
            }
            row.addView(call)
        }
        return row
    }

    private fun pretty(p: String): String =
        if (p.length == 10 && p.startsWith("0")) "${p.substring(0,3)}-${p.substring(3)}" else p
}
