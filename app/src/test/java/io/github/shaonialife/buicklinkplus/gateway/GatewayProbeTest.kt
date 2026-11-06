package io.github.shaonialife.buicklinkplus.gateway

import io.github.shaonialife.buicklinkplus.gateway.EntryConfig
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class GatewayProbeTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private var probe: GatewayProbe? = null
    private val events = LinkedBlockingQueue<GatewayProbe.Snapshot>()

    @Before
    fun setup() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start()
        client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .connectTimeout(2, TimeUnit.SECONDS).build()
    }

    @After
    fun teardown() {
        probe?.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    private fun start(
        replyTimeout: Long = 2_000,
        deviceInfo: GatewayDeviceInfo? = null,
        appLogs: GatewayLogBuffer? = null,
        vehicleProvider: () -> String? = { null },
    ): GatewayProbe {
        return GatewayProbe(client, observer = { events.offer(it) }, heartbeatIntervalMs = 100,
            replyTimeoutMs = replyTimeout, deviceInfo = deviceInfo, appLogs = appLogs,
            vehicleProvider = vehicleProvider).also {
            probe = it
            it.start(EntryConfig(server.url("/ws/device").toString().replaceFirst("https://", "wss://"), "test-only-token"))
        }
    }

    private val info = GatewayDeviceInfo("65bddcd6-0e81-4f98-b509-7aafc1ddc3f8", "test-build", 35)

    private fun reportingGateway(
        acknowledge: Boolean, received: LinkedBlockingQueue<JSONObject>,
        logs: Boolean = false, logAck: Boolean = true, vehicleScope: Boolean = false,
    ) =
        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            private var reports = 0
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val capabilities = when {
                    logs && vehicleScope -> "[\"device_status_v1\",\"logs_v1\",\"vehicle_scope_v1\"]"
                    logs -> "[\"device_status_v1\",\"logs_v1\"]"
                    else -> "[\"device_status_v1\"]"
                }
                webSocket.send("""{"type":"hello","mode":"transport-test","capabilities":$capabilities}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = JSONObject(text)
                received.offer(message)
                when (message.getString("type")) {
                    "register" -> webSocket.send("""{"type":"register_ack"}""")
                    "heartbeat" -> webSocket.send("""{"type":"heartbeat_ack"}""")
                    "status" -> if (acknowledge) {
                        reports++
                        webSocket.send(JSONObject().put("type", "status_ack").put("report_count", reports).toString())
                    }
                    "logs" -> if (logAck) webSocket.send(JSONObject().put("type", "logs_ack")
                        .put("source", message.getString("source")).put("batch_id", message.getString("batch_id"))
                        .put("accepted_lines", message.getJSONArray("lines").length()).toString())
                }
            }
        })

    @Test
    fun registersAndOnlyCountsServerConfirmedReports() {
        val received = LinkedBlockingQueue<JSONObject>()
        server.enqueue(reportingGateway(true, received))
        start(deviceInfo = info)
        val beforeAck = await(GatewayProbe.State.HEALTHY)
        assertEquals(0, beforeAck.reportCount)
        assertTrue(beforeAck.reportsSupported)
        assertEquals(1, await(GatewayProbe.State.HEALTHY).reportCount)
        val registration = received.poll(2, TimeUnit.SECONDS)!!
        assertEquals(setOf("type", "device_id", "app_version", "sdk"), registration.keys().asSequence().toSet())
        assertEquals(info.deviceId, registration.getString("device_id"))
        assertEquals(info.appVersion, registration.getString("app_version"))
        assertEquals(info.sdk, registration.getInt("sdk"))
        assertEquals("heartbeat", received.poll(2, TimeUnit.SECONDS)!!.getString("type"))
        val status = received.poll(2, TimeUnit.SECONDS)!!
        assertEquals(setOf("type", "heartbeat_count", "latency_ms"), status.keys().asSequence().toSet())
        assertEquals(1, status.getInt("heartbeat_count"))
    }

    private fun awaitLogCount(app: Int = 0, system: Int = 0): GatewayProbe.Snapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            val event = await(GatewayProbe.State.HEALTHY)
            if (event.appLogLines >= app && event.systemLogLines >= system) return event
        }
        error("Log acknowledgement missing")
    }

    @Test
    fun appLogsStreamAutomaticallyButSystemLogsRequireExplicitConfirmation() {
        val received = LinkedBlockingQueue<JSONObject>()
        val buffer = GatewayLogBuffer(LogRedactor(listOf("test-only-token")))
        buffer.offer("软件日志：连接建立 192.0.2.2")
        server.enqueue(reportingGateway(true, received, logs = true))
        val active = start(deviceInfo = info, appLogs = buffer)
        assertTrue(awaitLogCount(app = 1).logsSupported)
        assertFalse(received.any { it.optString("source") == "system" })
        val appMessage = received.first { it.optString("type") == "logs" }
        assertEquals("app", appMessage.getString("source"))
        assertFalse(appMessage.getBoolean("user_confirmed"))
        assertFalse(appMessage.getJSONArray("lines").getString(0).contains("192.0.2.2"))
        assertTrue(active.uploadConfirmedSystemLogs(listOf("已预览系统日志")))
        assertEquals(1, awaitLogCount(system = 1).systemLogLines)
        val systemMessage = received.first { it.optString("source") == "system" }
        assertTrue(systemMessage.getBoolean("user_confirmed"))
        assertEquals("已预览系统日志", systemMessage.getJSONArray("lines").getString(0))
        active.close()
        assertFalse(active.uploadConfirmedSystemLogs(listOf("已过期预览")))
    }

    @Test
    fun longConfirmedEvidenceUploadsInAckedEightLineBatches() {
        val received = LinkedBlockingQueue<JSONObject>()
        val buffer = GatewayLogBuffer(LogRedactor())
        server.enqueue(reportingGateway(true, received, logs = true))
        val active = start(deviceInfo = info, appLogs = buffer)
        await(GatewayProbe.State.HEALTHY)
        val lines = (1..145).map { "预览行 $it [20:19:53.527]" }
        assertTrue(active.uploadConfirmedSystemLogs(lines))
        assertFalse(active.uploadConfirmedSystemLogs(listOf("不得并发提交")))
        val finished = awaitLogCount(system = lines.size)
        assertEquals(lines.size, finished.systemUploadTotal)
        assertEquals(lines.size, finished.systemUploadAcknowledged)
        assertFalse(finished.systemPending)
        val batches = received.filter { it.optString("type") == "logs" && it.optString("source") == "system" }
        assertEquals(19, batches.size) // 18 packets of 8 lines and a final 1-line packet
        assertTrue(batches.all { it.getJSONArray("lines").length() in 1..8 })
        assertTrue(batches.all { it.getBoolean("user_confirmed") })
        assertEquals(lines, batches.flatMap { m ->
            val a = m.getJSONArray("lines")
            (0 until a.length()).map { a.getString(it) }
        })
        assertFalse(active.uploadConfirmedSystemLogs((1..257).map { "过长 $it" }))
    }

    @Test
    fun legacyLogGatewayDoesNotReceiveVehicleMetadata() {
        val vehicle = "74a97cc7-51f6-4e10-acd7-e9d4c3a25130"
        val received = LinkedBlockingQueue<JSONObject>()
        val buffer = GatewayLogBuffer(LogRedactor(), vehicleProvider = { vehicle })
        buffer.offer("legacy still uploads without new fields")
        server.enqueue(reportingGateway(true, received, logs = true))
        start(deviceInfo = info, appLogs = buffer, vehicleProvider = { vehicle })
        assertEquals(1, awaitLogCount(app = 1).appLogLines)
        assertFalse(received.first { it.optString("type") == "logs" }.has("vehicle_id"))
        assertFalse(received.first { it.optString("type") == "status" }.has("vehicle_id"))
    }

    @Test
    fun negotiatedGatewayReceivesPrivateVehicleUuidOnly() {
        val vehicle = "74a97cc7-51f6-4e10-acd7-e9d4c3a25130"
        val received = LinkedBlockingQueue<JSONObject>()
        val buffer = GatewayLogBuffer(LogRedactor(), vehicleProvider = { vehicle })
        buffer.offer("modern labelled log")
        server.enqueue(reportingGateway(true, received, logs = true, vehicleScope = true))
        start(deviceInfo = info, appLogs = buffer, vehicleProvider = { vehicle })
        assertEquals(1, awaitLogCount(app = 1).appLogLines)
        assertEquals(vehicle, received.first { it.optString("type") == "logs" }.getString("vehicle_id"))
        assertEquals(vehicle, received.first { it.optString("type") == "status" }.getString("vehicle_id"))
    }

    @Test
    fun unacknowledgedAppLogBatchFailsAndReturnsToBoundedBuffer() {
        val received = LinkedBlockingQueue<JSONObject>()
        val buffer = GatewayLogBuffer(LogRedactor())
        buffer.offer("等待确认的日志")
        server.enqueue(reportingGateway(true, received, logs = true, logAck = false))
        start(replyTimeout = 700, deviceInfo = info, appLogs = buffer)
        assertEquals(GatewayProbe.Failure.LOG_UPLOAD, await(GatewayProbe.State.FAILED).failure)
        assertEquals(listOf("等待确认的日志"), buffer.take())
    }

    @Test
    fun gatewayWithoutLogCapabilityDoesNotReceiveLogs() {
        val received = LinkedBlockingQueue<JSONObject>()
        val buffer = GatewayLogBuffer(LogRedactor())
        buffer.offer("仅保留在有界缓冲中的日志")
        server.enqueue(reportingGateway(true, received))
        start(deviceInfo = info, appLogs = buffer)
        assertFalse(await(GatewayProbe.State.HEALTHY).logsSupported)
        Thread.sleep(350)
        assertFalse(received.any { it.optString("type") == "logs" })
    }

    @Test
    fun missingReportAckFailsWithoutCountingUpload() {
        server.enqueue(reportingGateway(false, LinkedBlockingQueue()))
        start(replyTimeout = 700, deviceInfo = info)
        assertEquals(0, await(GatewayProbe.State.HEALTHY).reportCount)
        assertEquals(GatewayProbe.Failure.PROTOCOL, await(GatewayProbe.State.FAILED).failure)
    }

    @Test
    fun legacyGatewayRemainsHeartbeatOnlyWithDeviceInfo() {
        server.enqueue(gateway())
        start(deviceInfo = info)
        val healthy = await(GatewayProbe.State.HEALTHY)
        assertFalse(healthy.reportsSupported)
        assertEquals(0, healthy.reportCount)
    }

    private fun await(state: GatewayProbe.State): GatewayProbe.Snapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            val event = events.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (event.state == state) return event
            check(event.state !in setOf(GatewayProbe.State.FAILED, GatewayProbe.State.AUTH_FAILED)) {
                "Unexpected terminal state: $event"
            }
        }
        error("Timed out waiting for $state")
    }

    private fun gateway(reply: Boolean = true, mode: String = "transport-test") = MockResponse().withWebSocketUpgrade(
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("{\"type\":\"hello\",\"mode\":\"$mode\"}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (reply && text == "{\"type\":\"heartbeat\"}") webSocket.send("{\"type\":\"heartbeat_ack\"}")
            }
        },
    )

    @Test
    fun authenticatesAndConfirmsRepeatedHeartbeats() {
        server.enqueue(gateway())
        start()
        val first = await(GatewayProbe.State.HEALTHY)
        val second = await(GatewayProbe.State.HEALTHY)
        assertEquals(1, first.heartbeatCount)
        assertEquals(2, second.heartbeatCount)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("Bearer test-only-token", request.getHeader("Authorization"))
        assertEquals("/ws/device", request.path)
    }

    @Test
    fun reportsIncorrectCredentialAsAuthenticationFailure() {
        server.enqueue(MockResponse().setResponseCode(401))
        start()
        assertEquals(401, await(GatewayProbe.State.AUTH_FAILED).httpCode)
    }

    @Test
    fun rejectsUntrustedServerCertificates() {
        client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build()
        server.enqueue(gateway())
        start()
        assertEquals(GatewayProbe.Failure.TLS, await(GatewayProbe.State.FAILED).failure)
    }

    @Test
    fun aHandshakeWithoutGreetingDoesNotCountAsHealthy() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        start(replyTimeout = 700)
        assertEquals(GatewayProbe.Failure.PROTOCOL, await(GatewayProbe.State.FAILED).failure)
    }

    @Test
    fun doesNotForwardCredentialAcrossRedirects() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://localhost:1/elsewhere"))
        start()
        val failed = await(GatewayProbe.State.FAILED)
        assertEquals(GatewayProbe.Failure.HANDSHAKE, failed.failure)
        assertEquals(302, failed.httpCode)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun missingHeartbeatReplyFailsInsteadOfDisplayingHealthy() {
        server.enqueue(gateway(reply = false))
        start(replyTimeout = 700)
        assertEquals(GatewayProbe.Failure.HEARTBEAT, await(GatewayProbe.State.FAILED).failure)
    }

    @Test
    fun rejectsAnUnrecognizedGatewayGreeting() {
        server.enqueue(gateway(mode = "unknown"))
        start()
        assertEquals(GatewayProbe.Failure.PROTOCOL, await(GatewayProbe.State.FAILED).failure)
    }

    @Test
    fun networkLossTerminatesTheProbe() {
        server.enqueue(gateway())
        val active = start()
        await(GatewayProbe.State.HEALTHY)
        active.networkLost()
        assertEquals(GatewayProbe.Failure.NETWORK, await(GatewayProbe.State.FAILED).failure)
    }

    @Test
    fun closingStopsHeartbeatAndIgnoresLateCallbacks() {
        server.enqueue(gateway())
        val active = start()
        await(GatewayProbe.State.HEALTHY)
        active.close()
        await(GatewayProbe.State.DISCONNECTED)
        Thread.sleep(300)
        assertFalse(events.any { it.state == GatewayProbe.State.HEALTHY })
    }
}
