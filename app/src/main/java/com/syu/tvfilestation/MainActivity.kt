package com.syu.tvfilestation

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * TV 端极简状态页：服务开关、访问地址、配对码、权限引导。
 */
class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvUrl: TextView
    private lateinit var tvCode: TextView
    private lateinit var btnToggle: Button
    private lateinit var layoutPermission: android.widget.LinearLayout
    private lateinit var btnGrantPermission: Button

    private var service: FileServerService? = null
    private val handler = Handler(Looper.getMainLooper())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as FileServerService.LocalBinder).service()
            refreshUi()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            refreshUi()
        }
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvUrl = findViewById(R.id.tvUrl)
        tvCode = findViewById(R.id.tvCode)
        btnToggle = findViewById(R.id.btnToggle)
        layoutPermission = findViewById(R.id.layoutPermission)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)

        btnToggle.setOnClickListener { onToggleClicked() }
        btnGrantPermission.setOnClickListener { openStoragePermissionSettings() }

        bindService(Intent(this, FileServerService::class.java), connection, BIND_AUTO_CREATE)
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        unbindService(connection)
        super.onDestroy()
    }

    // ---------- 交互 ----------

    private fun onToggleClicked() {
        val svc = service ?: return
        if (!hasStoragePermission()) {
            Toast.makeText(this, "请先授予「所有文件访问」权限", Toast.LENGTH_LONG).show()
            openStoragePermissionSettings()
            return
        }
        if (svc.isRunning) {
            AlertDialog.Builder(this)
                .setTitle("关闭服务？")
                .setMessage("关闭后电脑将无法继续传输文件")
                .setPositiveButton("关闭") { _, _ ->
                    svc.stopServer()
                    refreshUi()
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            val ok = svc.startServer()
            if (!ok) {
                Toast.makeText(this, "服务启动失败（端口可能被占用）", Toast.LENGTH_LONG).show()
            }
            refreshUi()
        }
    }

    private fun openStoragePermissionSettings() {
        val intents = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            listOf(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        } else {
            emptyList()
        }
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
                // 部分盒子裁剪了设置页，逐个降级尝试
            }
        }
        Toast.makeText(this, "请在系统设置中手动授予存储权限", Toast.LENGTH_LONG).show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
        }
    }

    // ---------- UI 刷新 ----------

    private fun refreshUi() {
        val svc = service
        val running = svc?.isRunning == true

        layoutPermission.visibility =
            if (hasStoragePermission()) android.view.View.GONE else android.view.View.VISIBLE

        if (running && svc != null) {
            tvStatus.text = getString(R.string.service_started)
            tvStatus.setTextColor(0xFF81C784.toInt())
            tvUrl.text = svc.serverUrls().joinToString("\n").ifEmpty { "未获取到 IP，请检查网络" }
            tvCode.text = svc.auth.pairingCode
            btnToggle.text = "关闭服务"
        } else {
            tvStatus.text = getString(R.string.service_stopped)
            tvStatus.setTextColor(0xFF9E9E9E.toInt())
            tvUrl.text = "--"
            tvCode.text = "------"
            btnToggle.text = "开启服务"
        }
    }

    private fun hasStoragePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    companion object {
        private const val REFRESH_INTERVAL_MS = 2000L
        private const val REQ_NOTIFICATION = 100
    }
}
