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
            if (p != null && p.playbackState != Player.STATE_IDLE && playerView?.player != p) {
                playerView?.player = p
                playerView?.visibility = View.VISIBLE
                statusLine?.visibility = View.GONE
                p.play()
            } else if (p == null || p.playbackState == Player.STATE_IDLE) {
                playerView?.player = null
                playerView?.visibility = View.GONE
                statusLine?.visibility = View.VISIBLE
            }
            playerView?.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.status_screen)
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
