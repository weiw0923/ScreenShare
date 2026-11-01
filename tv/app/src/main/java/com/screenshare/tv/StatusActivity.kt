package com.screenshare.tv

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.media3.ui.PlayerView
import androidx.media3.common.Player

class StatusActivity : Activity() {
    private var playerView: PlayerView? = null
    private var statusLine: TextView? = null
    private val poll = object : Runnable {
        override fun run() {
            val svc = RendererService.instance
            val p = svc?.player
            val rendering = p != null && p.playbackState == Player.STATE_READY &&
                (p.isPlaying || p.playbackState == Player.STATE_READY && p.currentPosition > 0)
            if (p != null && p.playbackState == Player.STATE_READY && playerView?.player != p) {
                playerView?.player = p
                p.play()
            }
            // fullscreen only once video actually renders; status column stays during buffering
            findViewById<View>(R.id.status_column).visibility =
                if (rendering) View.GONE else View.VISIBLE
            playerView?.visibility = if (rendering) View.VISIBLE else View.GONE
            if (rendering) {
                (playerView?.parent as? android.view.ViewGroup)?.keepScreenOn = true
            }
            if (p == null || p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
                playerView?.player = null
                (playerView?.parent as? android.view.ViewGroup)?.keepScreenOn = false
            }
            playerView?.postDelayed(this, 500)
        }
    }

    override fun onBackPressed() {
        // 退出机制：状态页按返回键 = 停止接收服务并退出
        stopService(Intent(this, RendererService::class.java))
        finish()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.status_screen)
        try {
            val v = packageManager.getPackageInfo(packageName, 0).versionName
            findViewById<TextView>(R.id.version_line).text = "v$v"
        } catch (_: Exception) {}
        playerView = findViewById(R.id.player_view)
        statusLine = findViewById(R.id.status_line)
        playerView?.setControllerAutoShow(false)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(Intent(this, RendererService::class.java))
        } else {
            startService(Intent(this, RendererService::class.java))
        }
        RendererService.onPlaybackStarted = { playerView?.postDelayed(poll, 300) }
        playerView?.postDelayed(poll, 500)
    }
}
