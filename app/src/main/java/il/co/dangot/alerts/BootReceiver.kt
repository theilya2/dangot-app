package il.co.dangot.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && Prefs.tech(context) != null) {
            ContextCompat.startForegroundService(context, Intent(context, AlertService::class.java))
        }
    }
}
