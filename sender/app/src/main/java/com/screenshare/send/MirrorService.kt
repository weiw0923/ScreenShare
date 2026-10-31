package com.screenshare.send

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Surface
import java.util.concurrent.Executors

/**
 * Screen-capture → H.264 encode → TS mux → HTTP serve pipeline,
 * mirroring the vendor app's DLNA-mirror architecture with standard APIs only.
 */
class MirrorService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "mirror"
        const val ACTION_STOP = "com.screenshare.send.MIRROR_STOP"

        @Volatile var isRunning = false
        var requestStart: ((Activity) -> Unit)? = null
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private val codecExecutor = Executors.newSingleThreadExecutor()
    private val muxer = TsMuxer()
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var ptsBaseUs = 0L
    private var lastPtsUs = 0L
    private var audioThread: Thread? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: android.media.AudioRecord? = null
    @Volatile private var audioRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        if (isRunning) return START_STICKY
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (code < 0 || data == null) { stopSelf(); return START_NOT_STICKY }

        startForegroundInternal()
        startMirror(code, data)
        return START_STICKY
    }

    private fun startForegroundInternal() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "屏幕镜像", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ScreenShare 镜像中")
                .setContentText("正在向电视传输屏幕画面")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("ScreenShare 镜像中")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .build()
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1001, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1001, n)
        }
    }

    private fun startMirror(resultCode: Int, data: Intent) {
        try {
            startMirrorInner(resultCode, data)
        } catch (e: Exception) {
            android.util.Log.e("MirrorService", "startMirror failed", e)
            stopSelfSafely()
        }
    }

    private fun startMirrorInner(resultCode: Int, data: Intent) {
        val metrics: DisplayMetrics = resources.displayMetrics
        // scale to keep long side <= 1280 for bandwidth sanity
        var w = metrics.widthPixels
        var h = metrics.heightPixels
        val longSide = maxOf(w, h)
        if (longSide > 1280) {
            val scale = 1280f / longSide
            w = (w * scale / 2).toInt() * 2
            h = (h * scale / 2).toInt() * 2
        }
        w = w and (Int.MAX_VALUE - 1); h = h and (Int.MAX_VALUE - 1)
        width = w; height = h

        val pmgr = getSystemService(MediaProjectionManager::class.java)
        projection = pmgr.getMediaProjection(resultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelfSafely() }
        }, android.os.Handler(android.os.Looper.getMainLooper()))

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = createInputSurface()
            start()
        }

        virtualDisplay = projection!!.createVirtualDisplay(
            "ssmirror", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )

        // bring PAT/PMT up and register with server
        MirrorServer.start()
        MirrorServer.broadcastInit(muxer.buildPat() + muxer.buildPmt())
        drainCodec()
        startAudio()
        isRunning = true
    }

    private fun startAudio() {
        try {
            val sampleRate = 44100
            val minBuf = android.media.AudioRecord.getMinBufferSize(
                sampleRate, android.media.AudioFormat.CHANNEL_IN_STEREO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            audioRecord = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC, sampleRate,
                android.media.AudioFormat.CHANNEL_IN_STEREO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, 8192)
            )
            val aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            }
            audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            audioRunning = true
            audioRecord?.startRecording()
            audioThread = Thread({
                val info = MediaCodec.BufferInfo()
                val pcm = ByteArray(8192)
                var ptsUs = 0L
                val bytesPerUs = sampleRate.toDouble() / 1_000_000.0 / 4.0  // stereo 16bit
                var lastRealtime = System.nanoTime() / 1000
                while (audioRunning) {
                    val rec = audioRecord ?: break
                    val n = rec.read(pcm, 0, pcm.size)
                    if (n <= 0) continue
                    val c = audioCodec ?: break
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = c.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        inBuf.put(pcm, 0, n)
                        c.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                        ptsUs += (n / bytesPerUs).toLong()
                    }
                    var outIdx = c.dequeueOutputBuffer(info, 10_000)
                    while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            outIdx = c.dequeueOutputBuffer(info, 0)
                            continue
                        }
                        if (info.size > 0) {
                            val out = ByteArray(info.size)
                            c.getOutputBuffer(outIdx)?.get(out)
                            // wrap raw AAC into ADTS header (LC, 44.1k stereo)
                            val adts = adtsHeader(info.size, sampleRate, 2) + out
                            MirrorServer.broadcast(muxer.packetizeAudio(adts, info.presentationTimeUs))
                        }
                        c.releaseOutputBuffer(outIdx, false)
                        outIdx = c.dequeueOutputBuffer(info, 0)
                    }
                    lastRealtime = System.nanoTime() / 1000
                }
            }, "MirrorAudio").also { it.start() }
        } catch (_: Exception) {
            // audio optional: video continues without it
            audioRunning = false
        }
    }

    private fun adtsHeader(dataLen: Int, sampleRate: Int, channels: Int): ByteArray {
        val srIndex = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4; 32000 -> 5
            24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11; else -> 4
        }
        val frameLen = dataLen + 7
        val b = ByteArray(7)
        b[0] = 0xFF.toByte()
        b[1] = 0xF1.toByte()                       // MPEG-4, no CRC
        b[2] = ((1 shl 6) or (srIndex shl 2) or (channels shr 2)).toByte()  // AAC-LC profile(2bits)=01
        b[3] = (((channels and 3) shl 6) or ((frameLen shr 11) and 3)).toByte()
        b[4] = ((frameLen shr 3) and 0xFF).toByte()
        b[5] = (((frameLen and 7) shl 5) or 0x1F).toByte()
        b[6] = 0xFC.toByte()
        return b
    }

    private fun drainCodec() {
        codecExecutor.execute {
            val info = MediaCodec.BufferInfo()
            while (isRunning) {
                val c = codec ?: break
                try {
                    val idx = c.dequeueOutputBuffer(info, 10_000)
                    if (idx < 0) continue
                    when {
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> {
                            // SPS/PPS
                            val out = ByteArray(info.size)
                            c.getOutputBuffer(idx)?.get(out)
                            parseSpsPps(out)
                        }
                        info.size > 0 -> {
                            val out = ByteArray(info.size)
                            c.getOutputBuffer(idx)?.get(out)
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            val ptsUs = info.presentationTimeUs
                            lastPtsUs = ptsUs
                            val ts = muxer.packetizeVideo(out, key, ptsUs)
                            MirrorServer.broadcast(ts)
                        }
                    }
                    c.releaseOutputBuffer(idx, false)
                } catch (_: Exception) { break }
            }
        }
    }

    private fun parseSpsPps(config: ByteArray) {
        // split Annex-B into NALs, collect SPS(7)/PPS(8)
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var i = 0
        val starts = mutableListOf<Int>()
        while (i < config.size - 3) {
            if (config[i].toInt() == 0 && config[i+1].toInt() == 0 &&
                config[i+2].toInt() == 0 && config[i+3].toInt() == 1) {
                starts.add(i + 4)
                i += 4
            } else i++
        }
        for (j in starts.indices) {
            val start = starts[j]
            val end = if (j + 1 < starts.size) starts[j + 1] - 4 else config.size
            val nal = config.copyOfRange(start, minOf(end, config.size))
            if (nal.isEmpty()) continue
            val type = nal[0].toInt() and 0x1F
            if (type == 7) sps = nal
            if (type == 8) pps = nal
        }
        if (sps != null && pps != null) muxer.setSpsPps(sps, pps)
    }

    private fun stopSelfSafely() {
        isRunning = false
        audioRunning = false
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        try { audioCodec?.stop(); audioCodec?.release() } catch (_: Exception) {}
        try { audioThread?.join(500) } catch (_: Exception) {}
        audioRecord = null; audioCodec = null; audioThread = null
        try { android.media.AudioManager::class.java } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { MirrorServer.stop() } catch (_: Exception) {}
        virtualDisplay = null; codec = null; projection = null; surface = null
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopSelfSafely()
        super.onDestroy()
    }
}
