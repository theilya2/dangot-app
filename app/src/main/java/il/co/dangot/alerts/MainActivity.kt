package il.co.dangot.alerts

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val spinner = findViewById<Spinner>(R.id.tech_spinner)
        val status = findViewById<TextView>(R.id.status)
        val btn = findViewById<Button>(R.id.register_btn)
        val testBtn = findViewById<Button>(R.id.test_btn)

        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Const.TECHS)

        Prefs.tech(this)?.let { t ->
            val idx = Const.TECHS.indexOf(t)
            if (idx >= 0) spinner.setSelection(idx)
        }

        btn.setOnClickListener {
            val tech = spinner.selectedItem as String
            Prefs.setTech(this, tech)
            requestPermissions()
            requestOverlay()
            requestBatteryExemption()
            startService()
            refresh(status)
        }

        testBtn.setOnClickListener {
            val i = Intent(this, AlertActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("id", "TEST-LOCAL")
                putExtra("title", "בדיקת התראה מקומית")
                putExtra("text", "אם אתה רואה את זה — מסך ההתראה והצליל עובדים. לחץ אישור.")
            }
            startActivity(i)
        }

        refresh(status)
    }

    private fun refresh(status: TextView) {
        val t = Prefs.tech(this)
        status.text = if (t != null) "מחובר כ: $t\nהשירות פעיל ברקע." else "בחר טכנאי והירשם."
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }
    }

    private fun requestOverlay() {
        try {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        } catch (_: Exception) {}
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            }
        } catch (_: Exception) {}
    }

    private fun startService() {
        val i = Intent(this, AlertService::class.java)
        ContextCompat.startForegroundService(this, i)
    }
}
