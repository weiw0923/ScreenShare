package com.screenshare.send

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private var endpoints: RendererEndpoints? = null
    private lateinit var statusView: TextView
    private lateinit var deviceButton: Button
    private lateinit var pickButton: Button
    private lateinit var controlsRow: LinearLayout
    private lateinit var spinner: ProgressBar
    private lateinit var mirrorButton: Button
    private lateinit var urlButton: Button
    private lateinit var rootLayout: LinearLayout

    @SuppressLint("SetTextI18n")
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // crash surface: any uncaught exception is shown on screen so field crashes are diagnosable
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runOnUiThread {
                val stack = android.util.Log.getStackTraceString(e)
                val tv = android.widget.TextView(this).apply {
                    text = "CRASH in ${t.name}\n\n$stack"
                    textSize = 11f
                    setTextColor(0xFFFF6B6B.toInt())
                    setPadding(32, 64, 32, 32)
                    movementMethod = android.text.method.ScrollingMovementMethod()
                }
                val clip = getSystemService(android.content.ClipboardManager::class.java)
                clip.setPrimaryClip(android.content.ClipData.newPlainText("crash", "$e\n$stack"))
                setContentView(android.widget.ScrollView(this).apply { addView(tv) })
            }
            try { Thread.sleep(3000) } catch (_: Exception) {}
            prevHandler?.uncaughtException(t, e)
        }
        try {
            val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            multicastLock = wm.createMulticastLock("ssdp").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) { }
        val pad = (resources.displayMetrics.density * 24).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundResource(0)  // replaced below with gradient drawable via background layer
            setBackgroundColor(0xFF241432.toInt())
        }

        val title = TextView(this).apply {
            text = "Screen	Share"
            textSize = 30f
            setTextColor(0xFFFFFFFF.toInt())
        }
        // two-tone title: Screen white + Share amber
        title.text = ""
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        titleRow.addView(TextView(this).apply { text = "Screen"; textSize = 30f; setTextColor(0xFFFFFFFF.toInt()) })
        titleRow.addView(TextView(this).apply { text = "Share"; textSize = 30f; setTextColor(0xFFFFC53D.toInt()) })

        val deviceSection = TextView(this).apply {
            text = "── 电视 ──"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF9E7FA8.toInt())
            setPadding(0, (pad*1.2f).toInt(), 0, (pad*0.4f).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        statusView = TextView(this).apply {
            text = "正在搜索电视…"
            textSize = 16f
            setTextColor(0xFFFFC9A8.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        spinner = ProgressBar(this).apply {
            visibility = View.VISIBLE
        }

        deviceButton = Button(this).apply {
            text = "选择电视"
            visibility = View.GONE
        }
        pickButton = Button(this).apply {
            text = "选择要投的视频"
            visibility = View.GONE
        }
        mirrorButton = Button(this).apply {
            text = "屏幕镜像（投整个手机屏幕）"
            visibility = View.GONE
            setOnClickListener { startMirrorFlow() }
        }
        urlButton = Button(this).apply {
            text = "投在线视频链接"
            visibility = View.GONE
            setOnClickListener {
                val input = android.widget.EditText(this@MainActivity).apply {
                    hint = "粘贴视频直链 (http…mp4 等)"
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
                }
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("投在线视频")
                    .setView(input)
                    .setPositiveButton("投屏") { _, _ ->
                        val u = input.text.toString().trim()
                        if (u.startsWith("http")) {
                            val e = endpoints ?: return@setPositiveButton
                            statusView.text = "投屏中…"
                            DlnaController.setUriAndPlay(e, u, "在线视频") { ok ->
                                runOnUiThread {
                                    if (ok) { statusView.text = "正在电视上播放链接"; controlsRow.visibility = View.VISIBLE }
                                    else toast("投屏失败：链接不可播或被拒绝")
                                }
                            }
                        } else toast("请输入 http 开头的链接")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            val b1 = Button(this@MainActivity).apply { text = "暂停" }
            val b2 = Button(this@MainActivity).apply { text = "停止" }
            val b3 = Button(this@MainActivity).apply { text = "-30s" }
            val b4 = Button(this@MainActivity).apply { text = "+30s" }
            b3.setOnClickListener {
                endpoints?.let { e -> DlnaController.getPosition(e) { pos ->
                    val now = pos?.first ?: 0
                    DlnaController.seekTo(e, (now - 30).coerceAtLeast(0)) { ok -> toast(if (ok) "已后退" else "Seek 失败") }
                } }
            }
            b4.setOnClickListener {
                endpoints?.let { e -> DlnaController.getPosition(e) { pos ->
                    val now = pos?.first ?: 0
                    DlnaController.seekTo(e, now + 30) { ok -> toast(if (ok) "已前进" else "Seek 失败") }
                } }
            }
            b1.setOnClickListener { endpoints?.let { e -> DlnaController.pause(e) { ok -> toast(if (ok) "已暂停" else "暂停失败") } } }
            b2.setOnClickListener {
                endpoints?.let { e -> DlnaController.stop(e) { ok -> toast(if (ok) "已停止" else "停止失败") } }
                if (MirrorService.isRunning) {
                    startService(Intent(this@MainActivity, MirrorService::class.java).apply { action = MirrorService.ACTION_STOP })
                    statusView.text = "镜像已停止\n选择要投的内容"
                }
            }
            addView(b1); addView(b2); addView(b3); addView(b4)
        }

        root.addView(titleRow)
        val slogan = TextView(this).apply {
            text = "share your screens"
            textSize = 13f
            setTextColor(0xB3FFE9D6.toInt())   // warm white 70%
            setPadding(0, (pad*0.2f).toInt(), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
        }
        root.addView(slogan)
        root.addView(spinner, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(deviceSection)
        root.addView(statusView)
        root.addView(deviceButton)
        val contentSection = TextView(this).apply {
            text = "── 投什么 ──"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF9E7FA8.toInt())
            setPadding(0, (pad*1.2f).toInt(), 0, (pad*0.4f).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(contentSection)
        root.addView(pickButton)
        root.addView(mirrorButton)
        root.addView(urlButton)
        val ctlSection = TextView(this).apply {
            text = "── 控制 ──"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF9E7FA8.toInt())
            setPadding(0, (pad*1.2f).toInt(), 0, (pad*0.4f).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(ctlSection)
        root.addView(controlsRow)
        rootLayout = root
        setContentView(root)

        deviceButton.setOnClickListener { showDevicePicker() }
        pickButton.setOnClickListener { pickVideo() }
        val lastIp = getPreferences(MODE_PRIVATE).getString("last_tv_ip", null)
        if (lastIp != null) {
            connect(CastDevice("", "http://$lastIp:49152/description.xml", lastIp))
        } else {
            startSearch()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun startSearch() {
        statusView.text = "正在搜索电视…"
        spinner.visibility = View.VISIBLE
        deviceButton.visibility = View.GONE
        pickButton.visibility = View.GONE
        controlsRow.visibility = View.GONE
        DlnaDiscoverer.search(4000) { devices ->
            runOnUiThread {
                spinner.visibility = View.GONE
                if (devices.isEmpty()) {
                    // cross-subnet fallback: TCP-probe 49152 on the phone's own /24 (SSDP cannot cross subnets)
                    SubnetScanner.scan { ips ->
                        // verify each candidate really is a cast device before offering it
                        var remaining = ips.size
                        val verified = java.util.Collections.synchronizedList(mutableListOf<CastDevice>())
                        if (ips.isEmpty()) {
                            runOnUiThread {
                                statusView.text = "没有找到电视\n\n手机和电视可能不在同一网段\n用底部按钮手动输入电视 IP"
                                addRefreshButton()
                                addManualEntryButton()
                            }
                            return@scan
                        }
                        for (ip in ips) {
                            DlnaController.describe("http://$ip:49152/description.xml") { ep ->
                                if (ep != null) verified.add(CastDevice(ep.friendlyName, "http://$ip:49152/description.xml", ip))
                                remaining--
                                if (remaining == 0) {
                                    runOnUiThread {
                                        if (verified.isNotEmpty()) chooseFrom(verified.toList())
                                        else {
                                            statusView.text = "没有找到电视\n\n用底部按钮手动输入电视 IP"
                                            addRefreshButton()
                                            addManualEntryButton()
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    chooseFrom(devices)
                }
            }
        }
    }

    private var refreshButton: Button? = null
    private var manualButton: Button? = null

    private fun addRefreshButton() {
        if (refreshButton == null) {
            refreshButton = Button(this).apply {
                text = "重新搜索"
                setOnClickListener { removeView(this); removeManual(); startSearch() }
            }
            rootLayout.addView(refreshButton)
        }
    }

    private fun addManualEntryButton() {
        if (manualButton == null) {
            manualButton = Button(this).apply {
                text = "手动输入电视 IP"
                setOnClickListener {
                    val input = android.widget.EditText(this@MainActivity).apply {
                        hint = "电视的 IP，例如 192.168.0.109"
                        inputType = android.text.InputType.TYPE_CLASS_PHONE
                    }
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("手动连接")
                        .setView(input)
                        .setPositiveButton("连接") { _, _ ->
                            val ip = input.text.toString().trim()
                            if (ip.isNotEmpty()) {
                                connect(CastDevice("", "http://$ip:49152/description.xml", ip))
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            rootLayout.addView(manualButton)
        }
    }

    private fun removeManual() {
        manualButton?.let { rootLayout.removeView(it) }
        manualButton = null
    }

    private fun removeView(v: View) {
        rootLayout.removeView(v)
        refreshButton = null
    }

    private var pendingDevices: List<CastDevice> = emptyList()

    private fun chooseFrom(devices: List<CastDevice>) {
        pendingDevices = devices
        if (devices.size == 1) {
            connect(devices[0])
        } else {
            statusView.text = "找到 ${devices.size} 台设备，点击选择"
            deviceButton.visibility = View.VISIBLE
            deviceButton.text = "选择电视 (${devices.size})"
        }
    }

    private fun showDevicePicker() {
        val names = pendingDevices.map { it.ip + (if (it.friendlyName.isNotBlank()) " · ${it.friendlyName}" else "") }
        android.app.AlertDialog.Builder(this)
            .setTitle("选择电视")
            .setItems(names.toTypedArray()) { _, which -> connect(pendingDevices[which]) }
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun connect(device: CastDevice) {
        statusView.text = "连接中：${device.ip}…"
        DlnaController.describe(device.descriptionUrl) { ep ->
            runOnUiThread {
                if (ep == null) {
                    statusView.text = "连接失败，点击重新搜索"
                    addRefreshButton()
                } else {
                    endpoints = ep
                    getPreferences(MODE_PRIVATE).edit().putString("last_tv_ip", device.ip).apply()
                    deviceButton.text = "已连接：${ep.friendlyName}"
                    deviceButton.visibility = View.VISIBLE
                    statusView.text = "选择手机里的视频投上去"
                    pickButton.visibility = View.VISIBLE
                    mirrorButton.visibility = View.VISIBLE
                    urlButton.visibility = View.VISIBLE
                    refreshButton?.let { removeView(it) }
                }
            }
        }
    }

    private val projectionLauncher = 4242
    private var pendingMirror = false

    private fun startMirrorFlow() {
        val e = endpoints ?: return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingMirror = true
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 77)
            return
        }
        val pmgr = getSystemService(MediaProjectionManager::class.java)
        pendingMirror = true
        @Suppress("DEPRECATION")
        startActivityForResult(pmgr.createScreenCaptureIntent(), projectionLauncher)
    }

    private fun launchMirror(resultCode: Int, data: Intent) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // notification permission is optional for FGS but Samsung-family builds log violations;
            // request quietly, continue regardless of the answer
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 78)
        }
        val e = endpoints ?: return
        // 1) start mirror pipeline (local HTTP-TS)
        val svc = Intent(this, MirrorService::class.java).apply {
            putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MirrorService.EXTRA_DATA, data)
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
        // 2) wait a moment for server, then push URL to TV
        android.os.Handler(mainLooper).postDelayed({
            val ip = MirrorServer.localIp() ?: run { toast("获取手机 IP 失败"); return@postDelayed }
            statusView.text = "镜像推流中：$ip:8080/live.ts"
            DlnaController.setUriAndPlay(e, "http://$ip:8080/live.ts", "屏幕镜像") { ok ->
                runOnUiThread {
                    if (ok) {
                        statusView.text = "正在电视上镜像手机屏幕"
                        controlsRow.visibility = View.VISIBLE
                    } else {
                        toast("镜像投屏被电视拒绝")
                    }
                }
            }
        }, 1200)
    }

    private fun pickVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*", "audio/*"))
        }
        startActivityForResult(intent, 42)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            castUri(uri)
        }
        if (requestCode == projectionLauncher && resultCode == RESULT_OK) {
            data?.let { launchMirror(resultCode, it) }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 77 && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startMirrorFlow()
        } else if (requestCode == 77) {
            toast("没有麦克风权限，将继续无声镜像")
            val pmgr = getSystemService(MediaProjectionManager::class.java)
            pendingMirror = true
            @Suppress("DEPRECATION")
            startActivityForResult(pmgr.createScreenCaptureIntent(), projectionLauncher)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun castUri(uri: Uri) {
        val e = endpoints ?: return
        statusView.text = "投屏中…"
        // Serve the content through a local relay so the TV can read the SAF stream.
        LocalRelay.start(this, uri) { localUrl ->
            val url = if (LocalRelay.contentTypeFor(uri) != "video/mp4") "$localUrl?ct=${LocalRelay.contentTypeFor(uri)}" else localUrl
            val name = queryName(uri) ?: "media"
            DlnaController.setUriAndPlay(e, url, name) { ok ->
                runOnUiThread {
                    if (ok) {
                        statusView.text = "正在电视上播放：$name"
                        controlsRow.visibility = View.VISIBLE
                    } else {
                        statusView.text = "投屏失败：电视拒绝了请求"
                    }
                }
            }
        }
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Exception) { null }

    override fun onDestroy() {
        super.onDestroy()
        try { multicastLock?.release() } catch (_: Exception) {}
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
