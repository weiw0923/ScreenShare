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
    const val MANIFEST_URL = "https://api.github.com/repos/weiw0923/ScreenShare/releases/latest"

    data class Manifest(val versionCode: Int, val versionName: String, val apkUrl: String)

    fun check(service: Service, currentVersionCode: Int, onNew: (Manifest) -> Unit) {
        Thread {
            try {
                val conn = URL(MANIFEST_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 6000
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                conn.disconnect()
                if (text.contains("\"draft\": true") || text.contains("\"prerelease\": true")) return@Thread
                val tag = Regex("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"").find(text)?.groupValues?.get(1) ?: return@Thread
                val parts = tag.split(".").map { it.toIntOrNull() ?: 0 }
                val code = (parts.getOrNull(0) ?: 0) * 10000 + (parts.getOrNull(1) ?: 0) * 100 + (parts.getOrNull(2) ?: 0)
                val url = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]*ScreenShareTV[^\"]*\\.apk)\"")
                    .find(text)?.groupValues?.get(1) ?: return@Thread
                if (code > currentVersionCode) {
                    Log.i("UpdateChecker", "update available: v$tag ($code)")
                    val apk = download(service, url)
                    if (apk != null) installApk(service, apk, "v$tag")
                    onNew(Manifest(code, "v$tag", url))
                }
            } catch (e: Exception) {
                Log.w("UpdateChecker", "check failed: ${e.message}")
            }
        }.start()
    }

    private fun download(serviceRef: Service, url: String): java.io.File? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 30000
        val tmp = java.io.File.createTempFile("update", ".apk", serviceRef.cacheDir)
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
