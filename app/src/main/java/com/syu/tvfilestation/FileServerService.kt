package com.syu.tvfilestation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.syu.tvfilestation.server.AuthManager
import com.syu.tvfilestation.server.FileHttpServer
import com.syu.tvfilestation.util.NetworkUtil

/**
 * 前台服务：承载 HTTP 文件服务器。
 * 服务开启期间持有 WakeLock 与 WifiLock，防止 TV 休眠导致传输中断。
 */
class FileServerService : Service() {

    private var server: FileHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    val auth = AuthManager()

    val isRunning: Boolean
        get() = server != null

    val port: Int
        get() = PORT

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun service(): FileServerService = this@FileServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /** 启动 HTTP 服务；返回 false 表示启动失败（如端口占用） */
    fun startServer(): Boolean {
        if (server != null) return true
        auth.regenerate()
        val s = FileHttpServer(applicationContext, PORT, auth)
        return try {
            s.start(SOCKET_TIMEOUT, false)
            server = s
            acquireLocks()
            startForegroundCompat()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            server = null
            false
        }
    }

    fun stopServer() {
        server?.stop()
        server = null
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 服务访问地址列表（可能有多网卡） */
    fun serverUrls(): List<String> =
        NetworkUtil.getLanAddresses().map { "http://$it:$PORT" }

    // ---------- 内部实现 ----------

    private fun startForegroundCompat() {
        val urls = serverUrls().joinToString("  ")
        // 带渠道的构造器仅 API 26+，低版本降级处理
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle(getString(R.string.service_started))
            .setContentText(urls.ifEmpty { "等待网络连接…" })
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tfs:wake").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tfs:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        const val PORT = 8080
        private const val SOCKET_TIMEOUT = 10_000
        private const val CHANNEL_ID = "file_server"
        private const val NOTIFICATION_ID = 1001
    }
}
