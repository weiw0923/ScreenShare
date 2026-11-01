package com.screenshare.tv.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-hosted auto-update: fetch a JSON manifest, compare versionCode, download the APK,
 * then hand it to the system PackageInstaller (user confirms once on the TV screen).
 *
 * Manifest format (served next to the APK):
 * { "versionCode": 14, "versionName": "1.2.0", "apkUrl": "http://host/ScreenShareTV-1.2.0.apk" }
 */
object UpdateChecker {
    const val MANIFEST_URL = "http://192.168.0.110:8910/update.json"

    data class Manifest(val versionCode: Int, val versionName: String, val apkUrl: String)

    fun check(service: Service, currentVersionCode: Int, onNew: (Manifest) -> Unit) {
        Thread {
            try {
                val conn = URL(MANIFEST_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 6000
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                conn.disconnect()
                val code = Regex("\"versionCode\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                val name = Regex("\"versionName\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: ""
                val url = Regex("\"apkUrl\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: ""
                if (code != null && code > currentVersionCode && url.isNotEmpty()) {
                    Log.i("UpdateChecker", "update available: $name ($code)")
                    val apk = download(url)
                    if (apk != null) installApk(service, apk, name)
                    onNew(Manifest(code, name, url))
                }
            } catch (e: Exception) {
                Log.w("UpdateChecker", "check failed: ${e.message}")
            }
        }.start()
    }

    private fun download(url: String): java.io.File? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 30000
        val tmp = java.io.File.createTempFile("update", ".apk", java.io.File("/data/local/tmp"))
        conn.inputStream.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        conn.disconnect()
        Log.i("UpdateChecker", "downloaded ${tmp.length()} bytes")
        tmp
    } catch (e: Exception) {
        Log.w("UpdateChecker", "download failed: ${e.message}")
        null
    }

    private fun installApk(service: Service, apk: java.io.File, versionName: String) {
        try {
            val pm = service.packageManager
            if (Build.VERSION.SDK_INT >= 24) {
                val session = pm.packageInstaller.openSession(pm.packageInstaller.createSession(
                    android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)))
                session.openWrite("screenshare", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(service, service.javaClass)
                val pi = PendingIntent.getService(service, 0, intent, if (Build.VERSION.SDK_INT >= 31)
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT)
                session.commit(pi.intentSender)
                session.close()
                notifyUser(service, "ScreenShare $versionName 更新已就绪，确认后安装")
            } else {
                @Suppress("DEPRECATION")
                val i = Intent(android.content.Intent.ACTION_INSTALL_PACKAGE).apply {
                    setDataAndType(android.net.Uri.fromFile(apk), "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                service.startActivity(i)
            }
        } catch (e: Exception) {
            Log.w("UpdateChecker", "install failed: ${e.message}")
        }
    }

    private fun notifyUser(service: Service, text: String) {
        val nm = service.getSystemService(NotificationManager::class.java)
        val n = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("update", "更新", NotificationManager.IMPORTANCE_DEFAULT))
            Notification.Builder(service, "update").setContentTitle("ScreenShare").setContentText(text)
                .setSmallIcon(android.R.drawable.ic_popup_sync).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(service).setContentTitle("ScreenShare").setContentText(text)
                .setSmallIcon(android.R.drawable.ic_popup_sync).build()
        }
        nm.notify(3001, n)
    }
}
