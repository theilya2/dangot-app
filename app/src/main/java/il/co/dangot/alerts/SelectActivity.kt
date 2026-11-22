package il.co.dangot.alerts

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** First-run screen: pick your name once. After registering, the choice is locked. */
class SelectActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // already registered -> go straight to the main app
        if (Prefs.tech(this) != null) { goMain(); return }
        setContentView(R.layout.activity_select)

        val spinner = findViewById<Spinner>(R.id.tech_spinner)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Const.TECHS)

        findViewById<Button>(R.id.register_btn).setOnClickListener {
            val tech = spinner.selectedItem as String
            Prefs.setTech(this, tech)
            requestPermissions()
            requestOverlay()
            requestBatteryExemption()
            startService()
            goMain()
        }
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm != null && !nm.canUseFullScreenIntent()) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                } catch (_: Exception) {}
            }
        }
    }

    private fun requestOverlay() {
        try {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        } catch (_: Exception) {}
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        } catch (_: Exception) {}
    }

    private fun startService() {
        ContextCompat.startForegroundService(this, Intent(this, AlertService::class.java))
    }
}
