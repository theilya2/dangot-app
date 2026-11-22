package il.co.dangot.alerts

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private lateinit var content: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var title: TextView
    private var tab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tech = Prefs.tech(this)
        if (tech == null) { startActivity(Intent(this, SelectActivity::class.java)); finish(); return }

        setContentView(R.layout.activity_main)
        ContextCompat.startForegroundService(this, Intent(this, AlertService::class.java))

        content = findViewById(R.id.content)
        progress = findViewById(R.id.progress)
        title = findViewById(R.id.topbar_title)
        findViewById<TextView>(R.id.topbar_tech).text = tech

        findViewById<View>(R.id.rail_sched).setOnClickListener { select(0) }
        findViewById<View>(R.id.rail_notif).setOnClickListener { select(1) }
        findViewById<View>(R.id.rail_upd).setOnClickListener { select(2) }

        select(0)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun select(which: Int) {
        tab = which
        // rail highlight
        val ids = intArrayOf(R.id.rail_sched, R.id.rail_notif, R.id.rail_upd)
        for (i in ids.indices) {
            findViewById<View>(ids[i]).setBackgroundColor(
                if (i == which) Color.parseColor("#E8EEFB") else Color.TRANSPARENT
            )
        }
        content.removeAllViews()
        when (which) {
            0 -> { title.text = "הלו״ז שלי"; loadSchedule() }
            1 -> { title.text = "התראות שלי"; loadNotifications() }
            2 -> { title.text = "עדכונים"; showUpdates() }
        }
    }

    private fun msg(text: String) {
        content.removeAllViews()
        val tv = TextView(this)
        tv.text = text; tv.textSize = 15f
        tv.setTextColor(Color.parseColor("#55606C")); tv.setPadding(dp(8), dp(28), dp(8), dp(8))
        content.addView(tv)
    }

    // ---------------- Schedule tab ----------------

    private data class Contact(val label: String, val phone: String?)
    private data class Case(
        val idx: String, val caseId: String, val type: String, val sla: String,
        val customer: String, val address: String, val contacts: List<Contact>
    )

    private fun loadSchedule() {
        val tech = Prefs.tech(this) ?: return
        progress.visibility = View.VISIBLE
        Thread {
            var raw: String? = null; var date = ""; var at = 0L
            try {
                client.newCall(Request.Builder().url("${Const.DB}/schedule.json").build()).execute().use { r ->
                    if (r.isSuccessful) {
                        val b = r.body?.string()
                        if (!b.isNullOrBlank() && b != "null") {
                            val o = JSONObject(b)
                            date = o.optString("date", ""); at = o.optLong("at", 0L)
                            raw = o.optJSONObject("techs")?.optString(tech, "")
                        }
                    }
                }
            } catch (_: Exception) {}
            val text = raw
            runOnUiThread {
                progress.visibility = View.GONE
                if (tab != 0) return@runOnUiThread
                content.removeAllViews()
                addSub(buildSub(date, at))
                if (text.isNullOrBlank() || text == "null") {
                    val tv = TextView(this); tv.text = "אין לך קריאות להיום 👍\nהלו״ז מתעדכן כשהיומן נדחף מהמוקד."
                    tv.textSize = 15f; tv.setTextColor(Color.parseColor("#55606C")); tv.setPadding(dp(8), dp(16), dp(8), dp(8))
                    content.addView(tv); return@runOnUiThread
                }
                val cases = parse(text)
                if (cases.isEmpty()) { msg(stripTags(text)); return@runOnUiThread }
                for (c in cases) content.addView(card(c))
            }
        }.start()
    }

    private fun addSub(s: String) {
        if (s.isBlank()) return
        val tv = TextView(this); tv.text = s; tv.textSize = 13f
        tv.setTextColor(Color.parseColor("#78808C")); tv.setPadding(dp(4), 0, dp(4), dp(8))
        content.addView(tv)
    }

    private fun buildSub(date: String, at: Long): String {
        val p = ArrayList<String>()
        if (date.isNotBlank()) p.add(date)
        if (at > 0) p.add("עודכן " + SimpleDateFormat("HH:mm", Locale.US).format(Date(at)))
        return p.joinToString(" · ")
    }

    private fun stripTags(s: String): String = s
        .replace("<b>", "").replace("</b>", "").replace("<i>", "").replace("</i>", "")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        .replace("&lt;", "<").replace("&gt;", ">")

    private val phoneRe = Regex("0\\d[\\d\\- ]{6,10}\\d")

    private fun parse(rawText: String): List<Case> {
        val lines = stripTags(rawText).split("\n")
        val out = ArrayList<Case>()
        var idx = ""; var caseId = ""; var type = ""; var sla = ""
        var customer = ""; var address = ""; var contacts = ArrayList<Contact>(); var open = false
        val headRe = Regex("^(\\d+)\\.\\s*(\\S+)\\s*—\\s*(.*)$")
        fun flush() {
            if (open && caseId.isNotBlank()) out.add(Case(idx, caseId, type, sla, customer, address, ArrayList(contacts)))
            idx = ""; caseId = ""; type = ""; sla = ""; customer = ""; address = ""; contacts = ArrayList(); open = false
        }
        for (line in lines) {
            val t = line.trim(); val m = headRe.find(t)
            when {
                m != null -> {
                    flush(); open = true; idx = m.groupValues[1]; caseId = m.groupValues[2]
                    val rest = m.groupValues[3].split("·").map { it.trim() }
                    type = rest.getOrElse(0) { "" }; sla = rest.getOrElse(1) { "" }
                }
                t.startsWith("👤") -> customer = t.removePrefix("👤").trim()
                t.startsWith("📍") -> address = t.removePrefix("📍").trim()
                t.startsWith("🧑") || t.startsWith("🏢") -> {
                    val emoji = t.substring(0, 2); var rest = t.substring(2).trim()
                    val ph = phoneRe.find(rest)?.value?.filter { it.isDigit() }
                    rest = rest.replace("📞", "").replace(phoneRe, "").replace("·", "").trim()
                    contacts.add(Contact("$emoji $rest".trim(), ph))
                }
            }
        }
        flush(); return out
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
        lp.bottomMargin = dp(10); card.layoutParams = lp

        val titleRow = LinearLayout(this)
        titleRow.orientation = LinearLayout.HORIZONTAL; titleRow.gravity = Gravity.CENTER_VERTICAL
        val cid = TextView(this)
        cid.text = "${c.idx}. ${c.caseId}"; cid.setTextColor(Color.parseColor("#0D3B66"))
        cid.setTypeface(null, Typeface.BOLD); cid.textSize = 17f
        cid.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        titleRow.addView(cid)
        val badgeText = listOf(c.type, c.sla).filter { it.isNotBlank() }.joinToString(" · ")
        if (badgeText.isNotBlank()) {
            val badge = TextView(this)
            badge.text = badgeText; badge.setTextColor(Color.WHITE); badge.textSize = 12f
            badge.setTypeface(null, Typeface.BOLD); badge.setPadding(dp(10), dp(4), dp(10), dp(4))
            val bg = resources.getDrawable(R.drawable.badge_bg, theme).mutate()
            bg.setTint(slaColor(c.sla, c.type)); badge.background = bg
            titleRow.addView(badge)
        }
        card.addView(titleRow)

        if (c.customer.isNotBlank()) card.addView(line("👤", c.customer, false, null))
        if (c.address.isNotBlank()) card.addView(line("📍", c.address, true) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(c.address)))) } catch (_: Exception) {}
        })
        for (ct in c.contacts) card.addView(contactRow(ct))
        return card
    }

    private fun line(emoji: String, text: String, linkish: Boolean, onClick: (() -> Unit)?): TextView {
        val tv = TextView(this)
        tv.text = "$emoji  $text"; tv.textSize = 14.5f
        tv.setTextColor(if (linkish) Color.parseColor("#1565C0") else Color.parseColor("#2A2F36"))
        tv.setPadding(0, dp(6), 0, 0); tv.setLineSpacing(0f, 1.1f)
        if (onClick != null) tv.setOnClickListener { onClick() }
        return tv
    }

    private fun contactRow(ct: Contact): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(7), 0, 0)
        val name = TextView(this)
        name.text = ct.label + (if (ct.phone != null) "  ·  ${pretty(ct.phone)}" else "")
        name.textSize = 14.5f; name.setTextColor(Color.parseColor("#2A2F36"))
        name.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(name)
        if (ct.phone != null) {
            val call = TextView(this)
            call.text = "📞 חייג"; call.textSize = 14f; call.setTypeface(null, Typeface.BOLD)
            call.setTextColor(Color.parseColor("#2E7D32")); call.setBackgroundResource(R.drawable.call_btn_bg)
            call.setPadding(dp(14), dp(7), dp(14), dp(7))
            call.setOnClickListener { try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${ct.phone}"))) } catch (_: Exception) {} }
            row.addView(call)
        }
        return row
    }

    private fun pretty(p: String): String =
        if (p.length == 10 && p.startsWith("0")) "${p.substring(0, 3)}-${p.substring(3)}" else p

    // ---------------- Notifications tab ----------------

    private fun loadNotifications() {
        val tech = Prefs.tech(this) ?: return
        progress.visibility = View.VISIBLE
        Thread {
            val items = ArrayList<Pair<String, JSONObject>>()
            try {
                val url = "${Const.DB}/notify/${Uri.encode(tech)}.json"
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    val b = r.body?.string()
                    if (r.isSuccessful && !b.isNullOrBlank() && b != "null") {
                        val o = JSONObject(b); val keys = o.keys()
                        while (keys.hasNext()) {
                            val k = keys.next(); val n = o.optJSONObject(k) ?: continue
                            items.add(k to n)
                        }
                    }
                }
            } catch (_: Exception) {}
            items.sortByDescending { it.second.optLong("ts", 0L) }
            runOnUiThread {
                progress.visibility = View.GONE
                if (tab != 1) return@runOnUiThread
                content.removeAllViews()
                val hint = TextView(this)
                hint.text = "קריאות שממתינות לאישור. אשר/י כל אחת כאן או בהתראה הקופצת."
                hint.textSize = 12.5f; hint.setTextColor(Color.parseColor("#9AA3AF")); hint.setPadding(dp(4), 0, dp(4), dp(8))
                content.addView(hint)
                if (items.isEmpty()) {
                    val tv = TextView(this); tv.text = "אין קריאות שממתינות לאישור ✓"
                    tv.textSize = 15f; tv.setTextColor(Color.parseColor("#55606C")); tv.setPadding(dp(8), dp(16), dp(8), dp(8))
                    content.addView(tv); return@runOnUiThread
                }
                for ((id, n) in items) content.addView(notifCard(id, n))
            }
        }.start()
    }

    private fun ackNotification(id: String) {
        val tech = Prefs.tech(this) ?: return
        if (id.isEmpty()) return
        Prefs.addSeen(this, id)
        try { getSystemService(android.app.NotificationManager::class.java).cancel(id.hashCode()) } catch (_: Exception) {}
        progress.visibility = View.VISIBLE
        Thread {
            try {
                val body = JSONObject().put("tech", tech).put("at", System.currentTimeMillis())
                    .toString().toRequestBody("application/json".toMediaType())
                client.newCall(Request.Builder().url(Const.DB + "/ack/" + Uri.encode(id) + ".json").put(body).build()).execute().close()
                client.newCall(Request.Builder().url(Const.DB + "/notify/" + Uri.encode(tech) + "/" + Uri.encode(id) + ".json").delete().build()).execute().close()
            } catch (_: Exception) {}
            runOnUiThread { if (tab == 1) loadNotifications() }
        }.start()
    }

    private fun notifCard(id: String, n: JSONObject): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL; card.setBackgroundResource(R.drawable.card_bg)
        card.setPadding(dp(14), dp(12), dp(14), dp(12))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10); card.layoutParams = lp

        val t = TextView(this)
        t.text = n.optString("title", "התראה"); t.setTypeface(null, Typeface.BOLD)
        t.textSize = 16f; t.setTextColor(Color.parseColor("#0D3B66"))
        card.addView(t)

        val body = n.optString("text", "")
        if (body.isNotBlank()) {
            val b = TextView(this); b.text = body; b.textSize = 14.5f
            b.setTextColor(Color.parseColor("#2A2F36")); b.setPadding(0, dp(6), 0, 0); b.setLineSpacing(0f, 1.12f)
            card.addView(b)
        }
        val footer = LinearLayout(this)
        footer.orientation = LinearLayout.HORIZONTAL; footer.gravity = Gravity.CENTER_VERTICAL
        footer.setPadding(0, dp(10), 0, 0)
        val ts = n.optLong("ts", 0L)
        val d = TextView(this)
        d.text = if (ts > 0) SimpleDateFormat("HH:mm · dd/MM", Locale.US).format(Date(ts)) else ""
        d.textSize = 12f; d.setTextColor(Color.parseColor("#9AA3AF"))
        d.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        footer.addView(d)

        val ack = TextView(this)
        ack.text = "✓ אשר קבלה"; ack.textSize = 14f; ack.setTypeface(null, Typeface.BOLD)
        ack.setTextColor(Color.WHITE); ack.setPadding(dp(16), dp(8), dp(16), dp(8))
        val abg = resources.getDrawable(R.drawable.badge_bg, theme).mutate()
        abg.setTint(Color.parseColor("#2E7D32")); ack.background = abg
        ack.setOnClickListener { ackNotification(id) }
        footer.addView(ack)
        card.addView(footer)
        return card
    }

    // ---------------- Updates tab ----------------

    private fun showUpdates() {
        content.removeAllViews()
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL; card.setBackgroundResource(R.drawable.card_bg)
        card.setPadding(dp(16), dp(16), dp(16), dp(16))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(12); card.layoutParams = lp

        val v = TextView(this)
        v.text = "גרסה מותקנת: ${BuildConfig.VERSION_NAME}"
        v.textSize = 16f; v.setTypeface(null, Typeface.BOLD); v.setTextColor(Color.parseColor("#0D3B66"))
        card.addView(v)

        val latest = TextView(this)
        latest.text = "בודק גרסה אחרונה…"; latest.textSize = 13.5f
        latest.setTextColor(Color.parseColor("#78808C")); latest.setPadding(0, dp(6), 0, 0)
        card.addView(latest)

        val btn = Button(this)
        btn.text = "⬇️ בדוק עדכון עכשיו"; btn.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E40AF"))
        btn.setTextColor(Color.WHITE)
        val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); blp.topMargin = dp(14)
        btn.layoutParams = blp
        btn.setOnClickListener { Updater.check(this, true) }
        card.addView(btn)
        content.addView(card)

        // reset card
        val rcard = LinearLayout(this)
        rcard.orientation = LinearLayout.VERTICAL; rcard.setBackgroundResource(R.drawable.card_bg)
        rcard.setPadding(dp(16), dp(16), dp(16), dp(16))
        val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rcard.layoutParams = rlp

        val rt = TextView(this)
        rt.text = "שינוי טכנאי"; rt.textSize = 15f; rt.setTypeface(null, Typeface.BOLD); rt.setTextColor(Color.parseColor("#0D3B66"))
        rcard.addView(rt)
        val rsub = TextView(this)
        rsub.text = "מחובר כעת: ${Prefs.tech(this)}\nאיפוס דורש קוד הרשאה."
        rsub.textSize = 13f; rsub.setTextColor(Color.parseColor("#78808C")); rsub.setPadding(0, dp(6), 0, 0)
        rcard.addView(rsub)
        val rbtn = Button(this)
        rbtn.text = "🔓 אפס בחירת טכנאי"; rbtn.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B00020"))
        rbtn.setTextColor(Color.WHITE)
        val rblp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); rblp.topMargin = dp(12)
        rbtn.layoutParams = rblp
        rbtn.setOnClickListener { askResetCode() }
        rcard.addView(rbtn)
        content.addView(rcard)

        // fetch latest version info
        Thread {
            var txt = "לא ניתן לבדוק גרסה כרגע"
            try {
                client.newCall(Request.Builder().url("${Const.DB}/appversion.json").build()).execute().use { r ->
                    val b = r.body?.string()
                    if (r.isSuccessful && !b.isNullOrBlank() && b != "null") {
                        val o = JSONObject(b)
                        val code = o.optInt("versionCode", 0); val name = o.optString("versionName", "")
                        txt = if (code > BuildConfig.VERSION_CODE) "🔵 עדכון זמין: $name — לחץ בדוק עדכון"
                        else "✓ האפליקציה מעודכנת (אחרונה: $name)"
                    }
                }
            } catch (_: Exception) {}
            runOnUiThread { if (tab == 2) latest.text = txt }
        }.start()
    }

    private fun askResetCode() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        input.hint = "קוד הרשאה"
        val pad = dp(20)
        val wrap = LinearLayout(this); wrap.setPadding(pad, dp(8), pad, 0); wrap.addView(input)
        AlertDialog.Builder(this)
            .setTitle("איפוס בחירת טכנאי")
            .setMessage("הזן/י קוד הרשאה כדי לאפשר שינוי טכנאי:")
            .setView(wrap)
            .setPositiveButton("אשר") { _, _ ->
                if (input.text.toString().trim() == Prefs.RESET_CODE) {
                    Prefs.clearTech(this)
                    startActivity(Intent(this, SelectActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(this, "קוד שגוי", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }
}
