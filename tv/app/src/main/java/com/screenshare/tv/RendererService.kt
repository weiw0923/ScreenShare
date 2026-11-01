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
    private var lastUri: String = ""
    private var liveMode: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundInternal()
        val uuid = "uuid:" + UUID.nameUUIDFromBytes(
            (android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.ANDROID_ID) + "screenshare")
                .toByteArray()).toString()

        val retryPolicy = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(
            /* minimumLoadableRetryCount = */ Int.MAX_VALUE
        ) {
            override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                // live streams: hiccup -> short backoff, never fatal
                return 1500L
            }
            override fun getMinimumLoadableRetryCount(dataType: Int): Int = Int.MAX_VALUE
        }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    DefaultDataSource.Factory(this)
                ).setLoadErrorHandlingPolicy(retryPolicy)
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
        // live-stream watchdog: if the current URI dies (CDN token expiry etc), retry it
        // for a while — the phone sender will push a fresh URL when it reconnects.
        val watchdog = android.os.Handler(android.os.Looper.getMainLooper())
        val watchdogRun = object : Runnable {
            override fun run() {
                val p = player
                if (p != null && lastUri.isNotEmpty() &&
                    (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) &&
                    p.playWhenReady) {
                    Log.i("RendererService", "watchdog: replay $lastUri")
                    val u = lastUri.lowercase()
                    val liveMode = u.contains("live") || u.endsWith(".flv") || u.endsWith(".m3u8") || u.endsWith(".ts")
                    val builder = MediaItem.Builder().setUri(lastUri)
                    if (liveMode) builder.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(3000).build())
                    p.setMediaItem(builder.build())
                    p.prepare()
                    p.playWhenReady = true
                }
                watchdog.postDelayed(this, 4000)
            }
        }
        watchdog.postDelayed(watchdogRun, 8000)
        Log.i("RendererService", "renderer up: ssdp+upnp on :$HTTP_PORT")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startPlayback(req: PlaybackRequest) {
        runOnMain {
            val p = player ?: return@runOnMain
            Log.i("RendererService", "play ${req.uri}")
            lastUri = req.uri
            val u = req.uri.lowercase()
            val liveMode = u.contains("live") || u.endsWith(".flv") || u.endsWith(".m3u8") || u.endsWith(".ts")
            val builder = MediaItem.Builder().setUri(req.uri)
            if (liveMode) {
                builder.setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(3000)
                        .setMinOffsetMs(1000)
                        .setMaxOffsetMs(8000)
                        .build()
                )
            }
            p.setMediaItem(builder.build())
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
