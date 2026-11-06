package io.github.shaonialife.buicklinkplus.gateway

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Once Gateway app-log uploader. Enabled only after the user configures WSS+token.
 * Starts automatically with the Link+ process, runs independently of the CarPlay network binding.
 * Never uploads raw system logs or packet payloads; no persistent network backlog.
 */
object GatewayAutoUpload {
    data class Status(
        val state: String,
        val acknowledged: Int = 0,
        val dropped: Long = 0,
        val heartbeat: Int = 0,
    )

    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "link-plus-gateway-uploader").apply { isDaemon = true }
    }
    private val waiting = AtomicInteger()
    private val droppedBeforeQueue = AtomicLong()
    @Volatile private var app: Context? = null
    @Volatile private var enabled = false
    @Volatile private var status = Status("未配置")
    private var configuration: EntryConfig? = null
    private var buffer = GatewayLogBuffer(LogRedactor(), capacity = 2048)
    private var route: GatewayNetworkRoute? = null
    private var probe: GatewayProbe? = null
    private var retry: ScheduledFuture<*>? = null
    private var epoch = 0
    private var attempts = 0

    fun initialize(context: Context) {
        app = context.applicationContext
        worker.execute {
            val current = app ?: return@execute
            val loaded = runCatching { EntryConfigStore.load(current) }.getOrNull()
            if (loaded == null) {
                status = Status("未配置或旧凭证不可解密")
                return@execute
            }
            configuration = loaded
            enabled = true
            connect()
        }
    }

    /** Non-blocking from Bluetooth and RTSP callbacks. */
    fun offer(line: String) {
        if (!enabled) return
        if (waiting.incrementAndGet() > 1024) {
            waiting.decrementAndGet()
            droppedBeforeQueue.incrementAndGet()
            return
        }
        // Do not enqueue raw buffers or user-entered credentials; caller has already redacted its text.
        val bounded = line.take(2048)
        worker.execute {
            try {
                if (enabled) GatewayLogPolicy.prepare(bounded)?.let(buffer::offer)
            } finally {
                waiting.decrementAndGet()
            }
        }
    }

    fun currentStatus(): Status = status.copy(dropped = status.dropped + droppedBeforeQueue.get())

    /** Called after credentials have been encrypted and stored. */
    fun reload() {
        worker.execute {
            enabled = false
            stopConnection()
            buffer.clear()
            attempts = 0
            val current = app ?: return@execute
            val loaded = runCatching { EntryConfigStore.load(current) }.getOrNull()
            configuration = loaded
            if (loaded == null) {
                status = Status("未配置")
            } else {
                enabled = true
                connect()
            }
        }
    }

    /** Disables automatic delivery and clears encrypted credentials. Never deletes local Trace files. */
    fun disable() {
        worker.execute {
            enabled = false
            stopConnection()
            configuration = null
            buffer.clear()
            val current = app
            val erased = current != null && runCatching { EntryConfigStore.clear(current) }.isSuccess
            status = Status(if (erased) "已关闭，上报配置已清除" else "已停止，配置清除失败")
        }
    }

    private fun connect() {
        if (!enabled) return
        val current = app ?: return
        val cfg = configuration ?: return
        val round = ++epoch
        status = Status("连接 Gateway 中", dropped = buffer.dropped)
        val selected = runCatching { GatewayNetworkRoute.create(current) }.getOrElse {
            status = Status("当前无独立互联网网络，等待重连", dropped = buffer.dropped)
            scheduleRetry()
            return
        }
        route = selected
        val deviceInfo = runCatching { GatewayIdentity.load(current) }.getOrElse {
            selected.close()
            route = null
            status = Status("设备注册初始化失败")
            scheduleRetry()
            return
        }
        lateinit var currentProbe: GatewayProbe
        currentProbe = GatewayProbe(
            selected.client,
            observer = { snapshot ->
                worker.execute {
                    if (!enabled || round != epoch || probe !== currentProbe) return@execute
                    status = Status(
                        state = when (snapshot.state) {
                            GatewayProbe.State.HEALTHY -> if (snapshot.logsSupported) "正常上报中" else "服务端未开放 logs_v1"
                            GatewayProbe.State.CONNECTING -> "正在连接"
                            GatewayProbe.State.CONNECTED -> "连接已建立，正在握手"
                            GatewayProbe.State.AUTH_FAILED -> "鉴权失败，请检查令牌"
                            GatewayProbe.State.DISCONNECTED -> "连接已断开"
                            GatewayProbe.State.FAILED -> "连接失败：${snapshot.failure?.name ?: "UNKNOWN"}"
                        },
                        acknowledged = snapshot.appLogLines,
                        dropped = snapshot.droppedLogLines,
                        heartbeat = snapshot.heartbeatCount,
                    )
                    when (snapshot.state) {
                        GatewayProbe.State.HEALTHY -> {
                            attempts = 0
                            if (!snapshot.logsSupported) {
                                enabled = false
                                stopConnection()
                                status = status.copy(state = "服务端不支持 logs_v1，已暂停")
                            }
                        }
                        GatewayProbe.State.AUTH_FAILED -> {
                            enabled = false
                            stopConnection()
                        }
                        GatewayProbe.State.FAILED, GatewayProbe.State.DISCONNECTED -> {
                            stopConnection()
                            scheduleRetry()
                        }
                        else -> Unit
                    }
                }
            },
            deviceInfo = deviceInfo,
            appLogs = buffer,
        )
        probe = currentProbe
        selected.watch { worker.execute { if (round == epoch) currentProbe.networkLost() } }
        currentProbe.start(cfg)
    }

    private fun scheduleRetry() {
        if (!enabled) return
        retry?.cancel(false)
        val seconds = minOf(60L, 5L shl minOf(attempts++, 4))
        retry = worker.schedule({ if (enabled) connect() }, seconds, TimeUnit.SECONDS)
    }

    private fun stopConnection() {
        ++epoch
        retry?.cancel(false)
        retry = null
        val old = probe
        probe = null
        old?.close()
        route?.close()
        route = null
    }
}
