package il.co.dangot.alerts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Checks Firebase /appversion for a newer build and, if found, downloads the
 * APK and launches the system installer. The repo's CI writes /appversion on
 * every build: {"versionCode":N,"versionName":"x","url":"<apk url>","notes":"..."}.
 */
object Updater {

    private const val TAG = "DangotUpdater"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** @param manual true when the user tapped "check for update" (show toasts even when up to date). */
    fun check(ctx: Context, manual: Boolean) {
        Thread {
            try {
                val url = "${Const.DB}/appversion.json"
                val req = Request.Builder().url(url).build()
                val body = client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) { toast(ctx, manual, "בדיקת עדכון נכשלה (${r.code})"); return@Thread }
                    r.body?.string()
                } ?: return@Thread
                if (body.isBlank() || body == "null") { toast(ctx, manual, "אין מידע על גרסה"); return@Thread }

                val j = JSONObject(body)
                val remoteCode = j.optInt("versionCode", 0)
                val apkUrl = j.optString("url", "")
                val notes = j.optString("notes", "")
                val local = BuildConfig.VERSION_CODE

                if (remoteCode <= local || apkUrl.isEmpty()) {
                    toast(ctx, manual, "האפליקציה מעודכנת (גרסה $local)")
                    return@Thread
                }

                toast(ctx, true, "מוריד עדכון… $notes")
                val apk = download(ctx, apkUrl) ?: run { toast(ctx, true, "הורדת העדכון נכשלה"); return@Thread }
                install(ctx, apk)
            } catch (e: Exception) {
                Log.e(TAG, "update check failed", e)
                toast(ctx, manual, "שגיאה בבדיקת עדכון")
            }
        }.start()
    }

    private fun download(ctx: Context, url: String): File? {
        return try {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "dangot-update.apk")
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                r.body?.byteStream()?.use { input ->
                    out.outputStream().use { input.copyTo(it) }
                } ?: return null
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "download failed", e)
            null
        }
    }

    private fun install(ctx: Context, apk: File) {
        // Android O+: the app needs permission to install from unknown sources.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            toast(ctx, true, "אשר התקנה ממקור זה ולחץ שוב על בדוק עדכון")
            try {
                val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            } catch (_: Exception) {}
            return
        }
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
        } catch (e: Exception) {
            Log.e(TAG, "install launch failed", e)
            toast(ctx, true, "לא ניתן לפתוח את המתקין")
        }
    }

    private fun toast(ctx: Context, show: Boolean, msg: String) {
        if (!show) return
        if (ctx is Activity) ctx.runOnUiThread { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
        else android.os.Handler(ctx.mainLooper).post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
    }
}
