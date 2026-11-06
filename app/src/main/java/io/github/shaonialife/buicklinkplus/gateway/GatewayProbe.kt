package io.github.shaonialife.buicklinkplus.gateway

import io.github.shaonialife.buicklinkplus.gateway.EntryAddress
import io.github.shaonialife.buicklinkplus.gateway.EntryConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.json.JSONArray
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** 每次连接独占客户端与定时器；状态中不携带 URL、凭证或服务器原始消息。 */
class GatewayProbe(
    client: OkHttpClient,
    private val observer: (Snapshot) -> Unit,
    private val heartbeatIntervalMs: Long = 10_000,
    private val replyTimeoutMs: Long = 10_000,
    private val deviceInfo: GatewayDeviceInfo? = null,
    private val appLogs: GatewayLogBuffer? = null,
    private val vehicleProvider: () -> String? = { null },
) : AutoCloseable {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    enum class State { CONNECTING, CONNECTED, HEALTHY, AUTH_FAILED, DISCONNECTED, FAILED }
    enum class Failure { NETWORK, TLS, HANDSHAKE, PROTOCOL, HEARTBEAT, CONFIGURATION, LOG_UPLOAD }

    data class Snapshot(
        val state: State,
        val failure: Failure? = null,
        val httpCode: Int? = null,
        val heartbeatCount: Int = 0,
        val latencyMs: Long = 0,
        val reportsSupported: Boolean = false,
        val reportCount: Int = 0,
        val logsSupported: Boolean = false,
        val appLogLines: Int = 0,
        val systemLogLines: Int = 0,
        val droppedLogLines: Long = 0,
        val systemPending: Boolean = false,
        val systemUploadTotal: Int = 0,
        val systemUploadAcknowledged: Int = 0,
    )

    private val timer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "gateway-probe-heartbeat").apply { isDaemon = true }
    }
    private var socket: WebSocket? = null
    private var deadline: ScheduledFuture<*>? = null
    private var nextHeartbeat: ScheduledFuture<*>? = null
    private var started = false
    private var stopped = false
    private var greeted = false
    private var pendingSince: Long? = null
    private var heartbeatCount = 0
    private var reportsSupported = false
    private var registered = false
    private var awaitingReport = false
    private var reportCount = 0
    private var latencyMs = 0L
    private var logsSupported = false
    private var vehicleSupported = false
    private var appLogLines = 0
    private var systemLogLines = 0
    private var systemUploadTotal = 0
    private var systemUploadAcknowledged = 0
    private data class LogBatch(
        val source: String,
        val lines: List<String>,
        val id: String = UUID.randomUUID().toString(),
        val vehicleId: String? = null,
        val taggedLines: List<GatewayLogBuffer.TaggedLine> = emptyList(),
    )
    private val systemBatches = ArrayDeque<LogBatch>()
    private var pendingLog: LogBatch? = null
    private var logDeadline: ScheduledFuture<*>? = null
    private var logPump: ScheduledFuture<*>? = null

    @Synchronized
    fun start(config: EntryConfig) {
        if (started || stopped) return
        started = true
        val request = runCatching {
            val address = EntryAddress.normalize(config.gatewayUrl)
            require(config.token.isNotBlank() && config.token.length <= 4096)
            require(config.token.none { it <= ' ' || it > '~' })
            Request.Builder().url(address).header("Authorization", "Bearer ${config.token}").build()
        }.getOrElse {
            finish(Snapshot(State.FAILED, Failure.CONFIGURATION))
            return
        }
        observer(Snapshot(State.CONNECTING))
        deadline = timer.schedule({ fail(Failure.NETWORK) }, replyTimeoutMs * 2, TimeUnit.MILLISECONDS)
        socket = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@GatewayProbe) {
                if (stopped) {
                    webSocket.cancel()
                    return
                }
                socket = webSocket
                deadline?.cancel(false)
                deadline = timer.schedule({ fail(Failure.PROTOCOL) }, replyTimeoutMs, TimeUnit.MILLISECONDS)
                observer(Snapshot(State.CONNECTED))
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            synchronized(this@GatewayProbe) {
                if (stopped) return
                if (text.length > 16_384) {
                    fail(Failure.PROTOCOL)
                    return
                }
                val message = runCatching { JSONObject(text) }.getOrNull()
                if (message == null) {
                    fail(Failure.PROTOCOL)
                    return
                }
                when (message.optString("type")) {
                    "hello" -> {
                        if (greeted || message.optString("mode") != "transport-test") {
                            fail(Failure.PROTOCOL)
                            return
                        }
                        greeted = true
                        deadline?.cancel(false)
                        val capabilities = message.optJSONArray("capabilities")
                        reportsSupported = deviceInfo != null && capabilities != null &&
                            (0 until capabilities.length()).any { capabilities.optString(it) == "device_status_v1" }
                        logsSupported = reportsSupported && appLogs != null &&
                            (0 until capabilities!!.length()).any { capabilities.optString(it) == "logs_v1" }
                        vehicleSupported = logsSupported &&
                            (0 until capabilities.length()).any { capabilities.optString(it) == "vehicle_scope_v1" }
                        if (reportsSupported) {
                            sendReportMessage(deviceInfo!!.registration())
                        } else {
                            sendHeartbeat()
                        }
                    }
                    "register_ack" -> {
                        if (!reportsSupported || registered || pendingSince != null) {
                            fail(Failure.PROTOCOL)
                            return
                        }
                        registered = true
                        deadline?.cancel(false)
                        sendHeartbeat()
                        if (logsSupported) logPump = timer.scheduleWithFixedDelay({ pumpLogs() }, 250, 250, TimeUnit.MILLISECONDS)
                    }
                    "heartbeat_ack" -> {
                        if (!greeted) {
                            fail(Failure.PROTOCOL)
                            return
                        }
                        val sent = pendingSince ?: return
                        pendingSince = null
                        deadline?.cancel(false)
                        heartbeatCount++
                        latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sent)
                        publishHealthy()
                        if (registered) {
                            awaitingReport = true
                            val status = JSONObject().put("type", "status")
                                .put("heartbeat_count", heartbeatCount).put("latency_ms", latencyMs)
                            if (vehicleSupported) vehicleProvider()?.let { status.put("vehicle_id", it) }
                            sendReportMessage(status)
                        } else scheduleHeartbeat()
                    }
                    "status_ack" -> {
                        if (!awaitingReport || message.optInt("report_count", -1) != reportCount + 1) {
                            fail(Failure.PROTOCOL)
                            return
                        }
                        awaitingReport = false
                        deadline?.cancel(false)
                        reportCount++
                        publishHealthy()
                        scheduleHeartbeat()
                    }
                    "logs_ack" -> {
                        val pending = pendingLog
                        if (pending == null || message.optString("batch_id") != pending.id ||
                            message.optString("source") != pending.source || message.optInt("accepted_lines", -1) != pending.lines.size) {
                            fail(Failure.PROTOCOL)
                            return
                        }
                        logDeadline?.cancel(false)
                        if (pending.source == "app") appLogLines += pending.lines.size else {
                            systemLogLines += pending.lines.size
                            systemUploadAcknowledged += pending.lines.size
                        }
                        pendingLog = null
                        if (heartbeatCount > 0) publishHealthy()
                    }
                    else -> fail(Failure.PROTOCOL)
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = fail(Failure.PROTOCOL)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            finish(Snapshot(State.DISCONNECTED))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finish(Snapshot(State.DISCONNECTED))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            response?.body?.close()
            when {
                code == 401 || code == 403 -> finish(Snapshot(State.AUTH_FAILED, httpCode = code))
                code != null -> finish(Snapshot(State.FAILED, Failure.HANDSHAKE, httpCode = code))
                generateSequence(t) { it.cause }.any { it is SSLException } -> fail(Failure.TLS)
                else -> fail(Failure.NETWORK)
            }
        }
    }

    private fun publishHealthy() = observer(Snapshot(State.HEALTHY,
        heartbeatCount = heartbeatCount, latencyMs = latencyMs,
        reportsSupported = reportsSupported, reportCount = reportCount,
        logsSupported = logsSupported, appLogLines = appLogLines, systemLogLines = systemLogLines,
        droppedLogLines = appLogs?.dropped ?: 0,
        systemPending = systemBatches.isNotEmpty() || pendingLog?.source == "system",
        systemUploadTotal = systemUploadTotal, systemUploadAcknowledged = systemUploadAcknowledged))

    /** One explicit consent for a bounded full preview; each server packet stays <=8 lines. */
    @Synchronized
    fun uploadConfirmedSystemLogs(preview: List<String>): Boolean {
        if (stopped || !registered || !logsSupported || heartbeatCount == 0 || systemBatches.isNotEmpty() || pendingLog?.source == "system") return false
        if (preview.isEmpty() || preview.size > 256 || preview.any { it.isBlank() || it.length > 256 }) return false
        systemUploadTotal = preview.size
        systemUploadAcknowledged = 0
        val confirmedVehicle = vehicleProvider()
        preview.chunked(8).forEach { systemBatches.addLast(LogBatch("system", it.toList(), vehicleId = confirmedVehicle)) }
        publishHealthy()
        return true
    }

    @Synchronized
    private fun pumpLogs() {
        if (stopped || !registered || !logsSupported || pendingLog != null) return
        val batch = systemBatches.pollFirst() ?: appLogs?.takeTagged()?.takeIf { it.isNotEmpty() }?.let {
            LogBatch("app", it.map { line -> line.text }, vehicleId = it.first().vehicleId, taggedLines = it)
        } ?: return
        pendingLog = batch
        val message = JSONObject().put("type", "logs").put("source", batch.source).put("batch_id", batch.id)
            .put("user_confirmed", batch.source == "system").put("lines", JSONArray(batch.lines))
        if (vehicleSupported) batch.vehicleId?.let { message.put("vehicle_id", it) }
        if (socket?.send(message.toString()) != true) { fail(Failure.NETWORK); return }
        logDeadline = timer.schedule({ fail(Failure.LOG_UPLOAD) }, replyTimeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun scheduleHeartbeat() {
        nextHeartbeat = timer.schedule({ sendHeartbeat() }, heartbeatIntervalMs, TimeUnit.MILLISECONDS)
    }

    private fun sendReportMessage(message: JSONObject) {
        if (socket?.send(message.toString()) != true) {
            fail(Failure.NETWORK)
            return
        }
        deadline = timer.schedule({ fail(Failure.PROTOCOL) }, replyTimeoutMs, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun sendHeartbeat() {
        if (stopped || !greeted || pendingSince != null) return
        pendingSince = System.nanoTime()
        if (socket?.send("{\"type\":\"heartbeat\"}") != true) {
            fail(Failure.NETWORK)
            return
        }
        deadline = timer.schedule({ fail(Failure.HEARTBEAT) }, replyTimeoutMs, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun networkLost() = fail(Failure.NETWORK)

    @Synchronized
    private fun fail(failure: Failure) = finish(Snapshot(State.FAILED, failure))

    @Synchronized
    private fun finish(snapshot: Snapshot) {
        if (stopped) return
        stopped = true
        deadline?.cancel(false)
        nextHeartbeat?.cancel(false)
        logPump?.cancel(false)
        logDeadline?.cancel(false)
        pendingLog?.takeIf { it.source == "app" }?.let { appLogs?.retryTagged(it.taggedLines) }
        pendingLog = null
        systemBatches.clear() // A new connection always requires a fresh system-log preview and confirmation.
        socket?.cancel()
        socket = null
        timer.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        observer(snapshot)
    }

    @Synchronized
    override fun close() = finish(Snapshot(State.DISCONNECTED))
}
