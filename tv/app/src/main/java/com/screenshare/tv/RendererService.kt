package com.screenshare.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.common.C
import com.screenshare.tv.ssdp.SsdpResponder
import com.screenshare.tv.upnp.PlaybackRequest
import com.screenshare.tv.upnp.UpnpHttpServer
import java.util.UUID

/**
 * The renderer: SSDP responder + UPnP AVTransport server + ExoPlayer.
 * Runs as a foreground service so mirroring/media survive app switches.
 */
class RendererService : Service() {

    companion object {
        const val CHANNEL_ID = "renderer"
        private const val HTTP_PORT = 49152
        @Volatile var instance: RendererService? = null
        @Volatile var onPlaybackStarted: (() -> Unit)? = null
    }

    private var ssdp: SsdpResponder? = null
    private var upnp: UpnpHttpServer? = null
    @Volatile var player: ExoPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundInternal()
        val uuid = "uuid:" + UUID.nameUUIDFromBytes(
            (android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.ANDROID_ID) + "screenshare")
                .toByteArray()).toString()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    DefaultDataSource.Factory(this)
                )
            )
            .build()

        upnp = UpnpHttpServer(
            friendlyName = "ScreenShare",
            httpPort = HTTP_PORT,
            uuid = uuid,
            onRequest = { req -> startPlayback(req) },
            onControl = { action -> handleControl(action) }
        ).also { it.start() }

        ssdp = SsdpResponder(this, "ScreenShare", HTTP_PORT, uuid).also {
            it.start()
            it.startAliveNotifier()
        }
        Log.i("RendererService", "renderer up: ssdp+upnp on :$HTTP_PORT")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startPlayback(req: PlaybackRequest) {
        runOnMain {
            val p = player ?: return@runOnMain
            Log.i("RendererService", "play ${req.uri}")
            val item = MediaItem.Builder()
                .setUri(req.uri)
                .build()
            p.setMediaItem(item)
            p.prepare()
            p.playWhenReady = true
            onPlaybackStarted?.invoke()
        }
    }

    private fun handleControl(action: String): Boolean {
        return runOnMainReturning {
            val p = player ?: return@runOnMainReturning false
            when {
                action == "Play" -> { p.playWhenReady = true; true }
                action == "Pause" -> { p.playWhenReady = false; true }
                action == "Stop" -> { p.stop(); true }
                action.startsWith("Seek:") -> {
                    val t = action.substringAfter("Seek:")
                    val parts = t.split(":").map { it.toIntOrNull() ?: 0 }
                    if (parts.size == 3) {
                        p.seekTo((parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L)
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else android.os.Handler(android.os.Looper.getMainLooper()).post { block() }
    }

    private fun <T> runOnMainReturning(block: () -> T): T {
        var result: T? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        val run = {
            result = block(); latch.countDown()
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) run()
        else android.os.Handler(android.os.Looper.getMainLooper()).post(run)
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun startForegroundInternal() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ScreenShare 接收", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ScreenShare 接收端")
                .setContentText("等待手机投屏…")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("ScreenShare 接收端")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .build()
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2001, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(2001, n)
        }
    }

    override fun onDestroy() {
        ssdp?.stop(); upnp?.stop(); player?.release()
        ssdp = null; upnp = null; player = null
        instance = null
        super.onDestroy()
    }
}
