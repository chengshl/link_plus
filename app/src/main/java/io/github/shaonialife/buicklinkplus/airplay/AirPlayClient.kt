package io.github.shaonialife.buicklinkplus.airplay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import androidx.core.content.edit
import io.github.shaonialife.buicklinkplus.audio.AudioCodec
import io.github.shaonialife.buicklinkplus.audio.AudioProfile
import io.github.shaonialife.buicklinkplus.audio.AudioSender
import io.github.shaonialife.buicklinkplus.audio.AudioTuning
import io.github.shaonialife.buicklinkplus.iap2.Iap2Csm
import io.github.shaonialife.buicklinkplus.iap2.Iap2MessageIds
import io.github.shaonialife.buicklinkplus.iap2.Iap2SenderSession
import io.github.shaonialife.buicklinkplus.input.TouchReports
import io.github.shaonialife.buicklinkplus.net.EndpointHosts
import io.github.shaonialife.buicklinkplus.net.existingNetworks
import io.github.shaonialife.buicklinkplus.render.DisplayAreas
import io.github.shaonialife.buicklinkplus.render.PixelArea
import io.github.shaonialife.buicklinkplus.session.SessionTermination
import io.github.shaonialife.buicklinkplus.util.DeviceIdentity
import io.github.shaonialife.buicklinkplus.util.ProtocolTrace
import io.github.shaonialife.buicklinkplus.util.ProtocolTraceFormat
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** AirPlay 配对与流会话客户端，长期身份保存在 SharedPreferences */
class AirPlayClient(
    initialEndpoint: Iap2SenderSession.CarPlayEndpoint,
    private val log: (String) -> Unit,
    private val context: Context? = null,
    /** 每轮配对重试前合并新的 mDNS 端点；无更新返回 null，回调不可阻塞 */
    private val endpointRefresher: ((Iap2SenderSession.CarPlayEndpoint) -> Iap2SenderSession.CarPlayEndpoint?)? = null,
    /** 配对重试时约每 10 秒重播 ctrl 公告并更新发现状态，回调不可阻塞 */
    private val retryTick: ((attempt: Int) -> Unit)? = null,
    /** 请求耗时使用的单调时钟，测试可注入 */
    private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() },
    /** 本轮车机网络，地址作用域和控制连接共用此链路 */
    private val carNetwork: Network? = null,
) {
    /** 绑定网络失效后通知会话层停止重试 */
    var onNetworkUnavailable: (() -> Unit)? = null
    internal var termination = SessionTermination()
        private set
    internal var initialSetupAttempts = 0
        private set
    internal var lastSetupFailure = SetupRetryPolicy.Failure.NONE
        private set

    /** 当前接收端端点，更新后在下一轮配对重试生效 */
    @Volatile
    private var endpoint: Iap2SenderSession.CarPlayEndpoint = initialEndpoint

    /** 端点代数：ensureConnected 用它检测「连接途中端点被换掉」，避免把新端点的请求发到旧地址。 */
    @Volatile
    private var endpointGeneration = 0

    @Volatile
    private var socket: Socket? = null
    private var cSeq = 0
    private var receiverVolumePolicy = ReceiverVolumePolicy()

    /** 配对时连通的接收端地址，IPv6 链路本地地址带作用域；其余通道复用此地址 */
    @Volatile
    var activeHost: String? = null
        private set

    /** 连通地址的解析结果（视频/音频端口复用同一 InetAddress，避免二次解析失败）。 */
    @Volatile
    private var activeAddress: InetAddress? = null

    /** 按需启动的 NTP 计时服务，跨配对重试保留，close() 不关闭它 */
    private val timingServer = TimingServer(log)

    /** pair-verify 之后启用：所有后续 RTSP 报文整体加密封装。 */
    @Volatile
    private var cipher: ControlCipher? = null

    /** 握手阶段缓存的 /info（音频协商用：audioType/格式/延迟按车机声明选）。 */
    @Volatile
    private var negotiatedInfo: Map<*, *>? = null

    // ---- 对外流程 ----

    /** pair-setup 是否收到 403，供主控选择蓝牙退役策略 */
    @Volatile
    var pairSetupRejected: Boolean = false
        private set

    /** 配对期间收到过 403；保留蓝牙并在窗口内重试，等待车机就绪 */
    @Volatile
    var pairingForbidden: Boolean = false
        private set

    /** 是否已连上接收端开始配对，供选择 DiPlay 断链策略 */
    @Volatile
    var attemptedPairing: Boolean = false
        private set

    /** 接收端不支持 HomeKit 配对，转用 et=NONE 和 AES-CTR 屏幕流 */
    @Volatile
    var pairingUnsupported: Boolean = false
        private set

    /** 控制通道是否已加密（pair-verify 完成）。false = 明文会话（老车机 / 未配对）。 */
    val controlEncrypted: Boolean get() = cipher != null

    /** TXT 未声明 HomeKit 加密能力时先走 et=NONE；SETUP 被拒后再尝试配对 */
    fun skipPairing(reason: String) {
        pairingUnsupported = true
        log("AIRPLAY: 跳过 HomeKit 配对（$reason）——使用 et=NONE 明文会话 + AES-CTR 屏幕流")
    }

    /** 重新建立控制通道（配对失败/跳过配对后，SETUP 之前必须有一次新连接）。 */
    fun reconnect(timeoutMillis: Int = 5_000): Boolean = ensureConnected(timeoutMillis)

    /** 明文 SETUP 被拒且尚未配对时，补做配对后重试 */
    fun retryWithPairing(windowMillis: Long = 30_000): PairVerifyClient.ControlKeys? {
        pairingUnsupported = false
        close()
        log("AIRPLAY: 明文 SETUP 未通过——尝试加密升级（补做 HomeKit 配对）")
        return runPairing(windowMillis)
    }

    /**
     * 先 pair-setup 再 pair-verify；403 时尝试已配对路径，每轮使用新 TCP 连接
     * 不预先请求 /info，避免旧固件拒绝后续配对；请求头与 catplay PairingHelperTx 对齐
     */

    /** 取消标志（会话退役时置位）：配对重试循环立即退出，不再跑满等待窗口。 */
    @Volatile
    var cancelled: Boolean = false

    /** 配对重试循环是否仍在跑（主控据此判断「晚到端点」应热更新还是忽略）。 */
    @Volatile
    var pairingInProgress: Boolean = false
        private set

    /** 当前可用端点，合并迟到消息时保留已有地址 */
    val currentEndpoint: Iap2SenderSession.CarPlayEndpoint get() = endpoint

    /**
     * 更新端点并关闭旧 TCP，让下一轮配对使用新地址
     *
     * @return 端点发生变化时返回 true
     */
    fun updateEndpoint(
        newEndpoint: Iap2SenderSession.CarPlayEndpoint,
        reason: String,
    ): Boolean {
        val old = endpoint
        val unchanged =
            old.ipAddresses == newEndpoint.ipAddresses &&
                old.airPlayPort == newEndpoint.airPlayPort &&
                old.publicKey == newEndpoint.publicKey &&
                old.deviceIdentifier == newEndpoint.deviceIdentifier
        if (unchanged) return false
        endpoint = newEndpoint
        endpointGeneration++
        log(
            "AIRPLAY: 端点更新（$reason）" +
                "${old.ipAddresses.firstOrNull()}:${old.airPlayPort} → " +
                "${newEndpoint.ipAddresses.firstOrNull()}:${newEndpoint.airPlayPort}" +
                "（配对循环下一轮使用新地址）",
        )
        close()
        return true
    }

    // 请求时限按调用线程隔离，不能把配对期限施加到后台心跳
    private val operationDeadline = ThreadLocal<Long>()

    /** 连接、请求及退避共同消费本轮剩余预算 */
    private fun remainingOperationMillis(requested: Int): Int {
        val deadline = operationDeadline.get() ?: return requested
        val remaining = deadline - nowMillis()
        if (remaining <= 0) throw java.net.SocketTimeoutException("控制操作总预算已耗尽")
        return minOf(requested.toLong(), remaining).coerceAtLeast(1).toInt()
    }

    /** 配对退避不允许越过总截止时间 */
    private fun boundedOperationSleep(millis: Long) {
        Thread.sleep(remainingOperationMillis(millis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).toLong())
    }

    fun runPairing(windowMillis: Long = PAIRING_WINDOW_MILLIS): PairVerifyClient.ControlKeys? {
        operationDeadline.set(nowMillis() + windowMillis)
        pairingInProgress = true
        try {
            return runPairingLoop(windowMillis)
        } catch (error: java.net.SocketTimeoutException) {
            log("AIRPLAY: 配对预算结束 - ${error.message}")
            return null
        } finally {
            operationDeadline.remove()
            pairingInProgress = false
        }
    }

    private fun runPairingLoop(windowMillis: Long): PairVerifyClient.ControlKeys? {
        val identity = loadOrCreateIdentity()
        log("AIRPLAY: 控制器身份 pairingId=${identity.pairingId}")
        // 车机可能在用户确认或收到邀请后才开放端口，默认每 2 秒重试，最多等 120 秒
        val deadline = nowMillis() + windowMillis
        var attempt = 0
        while (nowMillis() < deadline && !cancelled) {
            attempt++
            // 每轮重试前吸收一次 mDNS 新结果：网关兜底地址连不通时，车机的
            // _airplay._tcp 可能已经在后台发现到，及时换过去比空转 120s 强。
            endpointRefresher?.invoke(endpoint)?.let { refreshed ->
                updateEndpoint(refreshed, "配对期间 mDNS 刷新")
            }
            // 低频钩子：主控据此重新公告 ctrl mDNS（车机 Bonjour 可能漏掉首次公告）
            if (attempt == 1 || attempt % RETRY_TICK_ATTEMPTS == 0) {
                runCatching { retryTick?.invoke(attempt) }
            }
            close()
            if (!ensureConnected(2_000)) {
                if (attempt == 1 || attempt % 5 == 0) {
                    log("AIRPLAY: 车机 AirPlay 端口未开（等待你点车机上的手机图标 / 车机发 ctrl 邀请）…第 $attempt 次重试")
                }
                boundedOperationSleep(2_000)
                continue
            }
            attemptedPairing = true
            val transport = TlvTransport { path, body -> postPairing(path, body) }
            try {
                val setupStatus =
                    try {
                        PairSetupClient(identity, transport, log).run()
                        null
                    } catch (error: IOException) {
                        val message = error.message.orEmpty()
                        val forbidden = message.contains("403")
                        if (forbidden) {
                            pairSetupRejected = true
                            pairingForbidden = true
                        }
                        val unsupported = PAIRING_UNSUPPORTED_STATUS.any { message.contains(it) }
                        log(
                            "AIRPLAY: pair-setup " +
                                when {
                                    forbidden -> "被 403 拒绝（车机不接受匿名配对——iAP2 已完成信任）→ 转已配对快路径"
                                    unsupported -> "不被支持（$message）——该接收端是 pre-HomeKit 老车机，转明文会话"
                                    else -> "失败（$message）"
                                },
                        )
                        if (unsupported) {
                            pairingUnsupported = true
                            close()
                            return null
                        }
                        if (forbidden) "403" else "failed"
                    }
                // 403 后旧连接的状态机不可用：换全新连接走快路径
                if (setupStatus == "403") {
                    close()
                    if (!ensureConnected(2_000)) continue
                    val fastPathTransport = TlvTransport { path, body -> postPairing(path, body) }
                    val receiverStaticKey = endpoint.publicKey?.let(::hexToBytes)
                    val keys = PairVerifyClient(identity, fastPathTransport, log).run(receiverStaticKey)
                    cipher = ControlCipher(keys.phoneReadKey, keys.phoneWriteKey)
                    log("AIRPLAY: 控制通道加密已启用（已配对快路径）")
                    return keys
                }
                val receiverStaticKey = endpoint.publicKey?.let(::hexToBytes)
                val keys = PairVerifyClient(identity, transport, log).run(receiverStaticKey)
                // pair-verify 之后双方立即启用加密（接收端在 M4 应答后开启）
                cipher = ControlCipher(keys.phoneReadKey, keys.phoneWriteKey)
                log("AIRPLAY: 控制通道加密已启用")
                return keys
            } catch (error: IOException) {
                val message = error.message.orEmpty()
                // verify/认证段的 403 与 pair-setup 403 同源（车机信任已建立），
                // 见 pairingForbidden 注释：标记后主控不再断 BT / 不降级明文
                if (message.contains("403")) pairingForbidden = true
                log("AIRPLAY: 配对第 $attempt 次尝试失败 - $message")
                if (PAIRING_UNSUPPORTED_STATUS.any { message.contains(it) }) {
                    pairingUnsupported = true
                    log("AIRPLAY: 接收端不提供 HomeKit 配对通道——停止重试，转明文会话（et=NONE）")
                    close()
                    return null
                }
                // 403 退避（真机抓包：车机首连被拒后需 18~20s 才进入可接受状态，
                // 期间高频重试除徒增车机负载外可能一直重置其就绪状态机）；
                // 其余失败维持快速轮询等端口开放
                boundedOperationSleep(pairingRetryBackoffMs(message))
            }
        }
        log("AIRPLAY: 配对最终失败（接收端 RTSP 无应答）")
        return null
    }

    /** 拉取并解析 /info（注意：接收端在活跃会话期间可能不应答）。 */
    fun fetchInfo(timeoutMillis: Int = 5_000): Map<*, *>? {
        if (!ensureConnected()) return null
        return try {
            val (statusLine, body) = requestInfo(timeoutMillis)
            log("AIRPLAY: ✓ /info [$statusLine] body=${body.size} 字节")
            if (body.isEmpty()) return null
            val root = BinaryPlist.parse(body)
            if (root is Map<*, *>) {
                logInfoSummary(root)
                root
            } else {
                null
            }
        } catch (error: IOException) {
            log("AIRPLAY: /info 失败 - ${error.message}")
            null
        }
    }

    /** 每 5 秒向 keepAlivePort 发送 4 字节随机 UDP，给车机 10 秒看门狗留出余量 */
    fun startKeepAlive(
        host: String,
        port: Int,
    ) {
        if (port <= 0) return
        val sessionSocket = socket ?: return
        Thread({
            var udp: java.net.DatagramSocket? = null
            try {
                udp = java.net.DatagramSocket()
                val address = java.net.InetAddress.getByName(host)
                var sent = 0
                var lastSentAt = 0L
                while (isSessionAlive(sessionSocket) && udp.isClosed == false) {
                    val now = SystemClock.uptimeMillis()
                    val gap = if (lastSentAt == 0L) 0L else now - lastSentAt
                    lastSentAt = now
                    val payload = ByteArray(4).also(java.security.SecureRandom()::nextBytes)
                    udp.send(java.net.DatagramPacket(payload, payload.size, address, port))
                    sent++
                    if (sent <= 3 || sent % 60 == 0) {
                        log("AIRPLAY: keepAlive ping #$sent → $host:$port（间隔 ${gap}ms）")
                    }
                    Thread.sleep(KEEPALIVE_INTERVAL_MS)
                }
            } catch (_: InterruptedException) {
            } catch (error: IOException) {
                log("AIRPLAY: keepAlive 结束 - ${error.message}")
            } finally {
                udp?.close()
            }
        }, "airplay-keepalive").apply {
            isDaemon = true
            start()
        }
    }

    /** 每秒 POST 空 /feedback，RTSP 事务由 [rtspLock] 串行处理 */
    fun startFeedbackLoop() {
        val sessionSocket = socket ?: return
        Thread({
            var sent = 0
            while (isSessionAlive(sessionSocket)) {
                try {
                    val (status, body) = request("POST", "/feedback", ByteArray(0), expectedSocket = sessionSocket)
                    sent++
                    if (sent <= 6 || sent % 30 == 0) {
                        log("AIRPLAY: feedback #$sent [$status]${if (body.isNotEmpty()) " body=${body.size}B" else ""}")
                    }
                    // 起流后的前 8 次反馈完整记录，音频 sampleTime 可用来判断车机是否消费了数据
                    if (body.isNotEmpty() && isSessionAlive(sessionSocket)) {
                        logFeedbackBody(body, verbose = sent <= 8 || sent % 30 == 0)
                    }
                } catch (_: IOException) {
                    break // 连接已断（会话结束）
                }
                try {
                    Thread.sleep(1_000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "airplay-feedback").apply {
            isDaemon = true
            start()
        }
    }

    @Volatile private var activeAudioConnectionId: Long? = null

    @Volatile private var lastAudioSample: Double? = null

    @Volatile private var lastAudioFeedbackAt = 0L

    @Volatile private var lastAudioAdvanceAt = 0L

    /** 记录当前音频流的消费时钟推进 */
    val audioConsumptionStatus: String
        get() {
            val now = android.os.SystemClock.uptimeMillis()
            return when {
                lastAudioFeedbackAt == 0L -> "尚未收到本流反馈"
                now - lastAudioFeedbackAt > 15_000L -> "本流反馈已过期"
                lastAudioAdvanceAt > 0L && now - lastAudioAdvanceAt <= 15_000L -> "本流消费时钟推进中"
                else -> "已收到本流反馈，尚未确认时钟推进"
            }
        }

    /** 解析 /feedback 中的播放时钟字段并写日志，字段参考 catplay StreamFeedback */
    private fun logFeedbackBody(
        body: ByteArray,
        verbose: Boolean,
    ) {
        val root =
            runCatching { BinaryPlist.parse(body) as? Map<*, *> }.getOrNull()
        if (root == null) {
            if (verbose) log("AIRPLAY: feedback body 解析失败（${body.size}B）hex=${body.joinToString("") { "%02x".format(it) }}")
            return
        }
        val streams = root["streams"] as? List<*>
        if (streams == null) {
            if (verbose) log("AIRPLAY: feedback 无 streams 键（keys=${root.keys}）全字段=$root")
            return
        }
        streams.forEachIndexed { index, entry ->
            val stream = entry as? Map<*, *> ?: return@forEachIndexed
            if ((stream["streamConnectionID"] as? Number)?.toLong() == activeAudioConnectionId && activeAudioConnectionId != null) {
                val sample = (stream["sampleTime"] as? Number)?.toDouble()
                if (sample != null) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (lastAudioSample?.let { sample > it } == true) lastAudioAdvanceAt = now
                    lastAudioSample = sample
                    lastAudioFeedbackAt = now
                }
            }
            if (verbose) {
                log(
                    "AIRPLAY: feedback.stream[$index] type=${stream["type"]} sr=${stream["sr"]} " +
                        "connId=${stream["streamConnectionID"]} sampleTime=${stream["sampleTime"]} " +
                        "ts=${stream["timestamp"]} tsRawNs=${stream["timestampRawNs"]} 全字段=$stream",
                )
            }
        }
    }

    /** 必须属于同一条控制连接，旧线程不能借重连后的 socket 继续工作 */
    private fun isSessionAlive(sessionSocket: Socket): Boolean = socket === sessionSocket && !sessionSocket.isClosed

    fun close() {
        closeSession(null)
    }

    /** 只退役预期的控制连接，迟到的旧读线程不能关闭新连接 */
    private fun closeSession(expectedSocket: Socket?) =
        synchronized(rtspWriteLock) {
            if (expectedSocket != null && socket !== expectedSocket) return@synchronized
            socket?.let {
                io.github.shaonialife.buicklinkplus.ui.UiAppearance
                    .endReceiver(it)
            }
            runCatching { socket?.close() }
            socket = null
            activeHost = null
            activeAudioConnectionId = null
            lastAudioSample = null
            lastAudioFeedbackAt = 0L
            lastAudioAdvanceAt = 0L
            activeAddress = null
            // 新连接总是从**明文**开始（pair-verify 成功后才切加密）：旧连接的 cipher 必须
            // 清掉，否则重连后的读线程会把明文应答当密文帧解，后续所有请求超时。
            cipher = null
            readerSocket = null
            readerThread = null
            readerGeneration++
            failAllPending("控制通道关闭")
            timingServer.stop()
            runCatching { iapTunnel?.socket?.close() }
            iapTunnel = null
            runCatching { eventCommandSocket?.close() }
            eventCommandSocket = null
            eventCommandCipher = null
            eventHidDeviceUuids = emptyList()
            iapLink?.let { link ->
                log("IAPLINK: 会话退役，${link.controlSessionStatus}")
                link.close()
            }
            iapLink = null
            resourceState = AirPlayResourceState()
            screenOwnershipConfirmed = false
            // 注意：controlIapTx 不在这里 shutdown——close() 会被同实例的重试路径
            // （retryWithPairing 等）复用，杀掉执行器会废掉重连后的 MIB3 出站；
            // 守护线程常驻的代价可忽略（每客户端实例一根）
        }

    /** 发送 hidSetInputMode 启用车机触摸，uuid 使用 /info 声明的 HID 十六进制标识 */
    fun sendHidSetInputMode(
        deviceUuidHex: String,
        mode: Int = 0,
    ): Boolean = sendHidSetInputMode(deviceUuidHex, mode, null, null)

    /** 资源归还后启用输入前，检查控制和事件连接仍属于原会话 */
    private fun sendHidSetInputMode(
        deviceUuidHex: String,
        mode: Int,
        expectedControlSocket: Socket?,
        expectedEventSocket: Socket?,
    ): Boolean {
        val sent =
            postEventCommand(
                linkedMapOf(
                    "type" to "hidSetInputMode",
                    "params" to
                        linkedMapOf<String, Any?>(
                            "hidInputMode" to mode.toLong(),
                            "uuid" to deviceUuidHex,
                        ),
                ),
                expectedControlSocket,
                expectedEventSocket,
            )
        log("AIRPLAY: hidSetInputMode(事件通道) uuid=$deviceUuidHex mode=$mode ok=$sent")
        return sent
    }

    // HID 设置走事件通道，iAP2、资源声明和蓝牙交接走 RTSP
    // 事件命令不等应答，使用独立 CSeq

    @Volatile
    private var eventCommandSocket: Socket? = null

    /** null 表示 et=NONE 会话使用明文事件通道 */
    @Volatile
    private var eventCommandCipher: ControlCipher? = null

    @Volatile
    private var eventHidDeviceUuids: List<String> = emptyList()

    private val eventCommandLock = Any()
    private var eventCseq = 0
    /** 本地事件序号：车机没有发送 CSeq 时仍能关联收到的请求与发出的应答，不写入线上协议。 */
    private val inboundEventSerial = java.util.concurrent.atomic.AtomicLong(0)

    /** 未知请求（非 /command 的合法请求）累计数：前 10 条留痕后静默。 */
    @Volatile
    private var unknownEventRequests = 0

    /** /command 里 plist 解不出来的累计数：前 8 条带原始 hex 留痕（排查车机命令通路）。 */
    @Volatile
    private var badEventCommands = 0

    /** 按长度和前 8 字节去重未知 /command，避免重复日志 */
    private val badEventSignatures = LinkedHashSet<String>()

    /** 事件通道建立后由 connectEventChannel 调用。cipher=null 表示明文通道。 */
    private fun registerEventCommandChannel(
        socket: Socket,
        cipher: ControlCipher?,
        hidDeviceUuids: List<String>,
    ) {
        synchronized(eventCommandLock) {
            eventCommandCipher = cipher
            eventHidDeviceUuids = hidDeviceUuids.toList()
            eventCommandSocket = socket
        }
    }

    /** 在事件通道上发 POST /command（通道加密时加密）。通道未就绪时返回 false。 */
    fun postEventCommand(command: Map<String, Any?>): Boolean = postEventCommand(command, null, null)

    /** 写锁内再次检查连接，防止旧输入任务写入新事件通道 */
    private fun postEventCommand(
        command: Map<String, Any?>,
        expectedControlSocket: Socket?,
        expectedEventSocket: Socket?,
    ): Boolean {
        val socket = eventCommandSocket ?: return false
        return try {
            synchronized(eventCommandLock) {
                if (eventCommandSocket !== socket ||
                    socket.isClosed ||
                    cancelled ||
                    (expectedEventSocket != null && socket !== expectedEventSocket) ||
                    (expectedControlSocket != null && !isSessionAlive(expectedControlSocket))
                ) {
                    return false
                }
                val cipher = eventCommandCipher
                eventCseq++
                val body = BinaryPlist.encode(command)
                val head =
                    "POST /command RTSP/1.0\r\n" +
                        "Content-Type: application/x-apple-binary-plist\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "CSeq: $eventCseq\r\n\r\n"
                val plaintext = head.toByteArray(Charsets.US_ASCII) + body
                val bytes = cipher?.encrypt(plaintext) ?: plaintext
                socket.getOutputStream().apply {
                    write(bytes)
                    flush()
                }
                val kind = (command["type"] as? String)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,40}")) } ?: "other"
                ProtocolTrace.event("EVENT-TX", "cseq=$eventCseq type=$kind bytes=${body.size}")
                if (eventCommandSocket === socket && command["type"] == "hidSetInputMode") {
                    val uuid = (command["params"] as? Map<*, *>)?.get("uuid") as? String
                    if (uuid != null && uuid !in eventHidDeviceUuids) eventHidDeviceUuids = eventHidDeviceUuids + uuid
                }
            }
            true
        } catch (error: IOException) {
            log("EVENT: 命令发送失败 - ${error.message}")
            false
        }
    }

    /**
     * 只回应车机请求并回传 CSeq，不回应响应报文，避免回声循环
     * 与 postEventCommand 共用写锁，防止加密帧交叉
     */
    private fun respondEventRequest(cseq: String?, localEventId: Long) {
        val socket = eventCommandSocket ?: return
        try {
            synchronized(eventCommandLock) {
                if (eventCommandSocket !== socket || socket.isClosed) return
                val cipher = eventCommandCipher
                val head =
                    "RTSP/1.0 200 OK\r\nContent-Length: 0\r\n" +
                        (cseq?.let { "CSeq: $it\r\n" } ?: "") + "\r\n"
                val plaintext = head.toByteArray(Charsets.US_ASCII)
                socket.getOutputStream().apply {
                    write(cipher?.encrypt(plaintext) ?: plaintext)
                    flush()
                }
                ProtocolTrace.event("EVENT-ACK", "local=$localEventId cseq=${cseq?.toIntOrNull() ?: -1} status=200")
            }
        } catch (error: IOException) {
            ProtocolTrace.event("EVENT-ACK-FAIL", "local=$localEventId type=${error.javaClass.simpleName}")
        }
    }

    private val modeCommandLock = Any()

    @Volatile
    private var resourceState = AirPlayResourceState()

    @Volatile
    private var screenOwnershipConfirmed = false

    private val pendingResourceCommands =
        java.util.concurrent.atomic
            .AtomicInteger()

    /** 主屏归属获车机接受后，才允许交接 */
    val phoneOwnsScreen: Boolean
        get() = pendingResourceCommands.get() == 0 && screenOwnershipConfirmed && resourceState.phoneOwnsScreen

    /** 慢资源命令独立处理，不能阻塞事件读线程接收认证与链路确认 */
    private val resourceCommands =
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "airplay-resource-commands").apply { isDaemon = true }
        }

    /** 先发布初始资源状态，再申请主屏；车机后续借屏由事件仲裁处理 */
    fun assertModes(): Boolean =
        synchronized(modeCommandLock) {
            val sessionSocket = socket ?: return@synchronized false
            val state = resourceState
            state.claimAudio()
            val initialAccepted = publishModes(state, sessionSocket)
            if (!initialAccepted || !isSessionAlive(sessionSocket)) return@synchronized false
            state.requestPhoneScreen()
            publishModes(state, sessionSocket)
        }

    /** 车机接管或归还资源时只应答实际归属，不把所有请求解释成手机接管 */
    internal fun handleModeCommand(
        command: Map<*, *>,
        expectedSocket: Socket? = null,
    ): Boolean =
        synchronized(modeCommandLock) {
            val sessionSocket = socket ?: return@synchronized false
            if (expectedSocket != null && sessionSocket !== expectedSocket) return@synchronized false
            val state = resourceState
            when (command["type"]) {
                "changeModes" -> state.applyCarChange(command["params"] as? Map<*, *>)
                "requestUI" -> state.requestPhoneScreen()
                else -> return@synchronized false
            }
            val accepted = publishModes(state, sessionSocket)
            log("EVENT: ${command["type"]} 接受=$accepted 手机主屏=$phoneOwnsScreen")
            accepted
        }

    /** 界面重新归手机后补关键帧，不重发 RECORD 或重建视频流 */
    var onPhoneScreenReady: (() -> Unit)? = null

    /** 资源命令可等待响应，链路确认与认证数据必须继续在读线程即时分发 */
    internal fun dispatchEventCommand(
        command: Map<*, *>,
        sessionSocket: Socket,
        eventSocket: Socket,
        onCommand: (Map<*, *>) -> Unit,
    ) {
        if (!isSessionAlive(sessionSocket) || eventCommandSocket !== eventSocket || cancelled) return
        // 仅采纳明确字段，普通事件不改变当前日夜状态
        val nightMode = command["nightMode"] ?: (command["params"] as? Map<*, *>)?.get("nightMode")
        io.github.shaonialife.buicklinkplus.ui.UiAppearance
            .updateReceiver(sessionSocket, nightMode)
        if (command["type"] == "changeModes" || command["type"] == "requestUI") {
            val hidDevices = eventHidDeviceUuids
            screenOwnershipConfirmed = false
            pendingResourceCommands.incrementAndGet()
            resourceCommands.execute {
                var accepted = false
                try {
                    if (isSessionAlive(sessionSocket) && eventCommandSocket === eventSocket && !cancelled) {
                        accepted = handleModeCommand(command, sessionSocket)
                    }
                } catch (error: Exception) {
                    log("EVENT: 资源命令处理异常 - ${error.message}")
                } finally {
                    pendingResourceCommands.decrementAndGet()
                }
                if (accepted && isSessionAlive(sessionSocket) && eventCommandSocket === eventSocket && phoneOwnsScreen) {
                    // 同车型参考日志在界面恢复后重启输入，恢复画面时必须同步重新启用 HID
                    hidDevices.forEach { uuid -> sendHidSetInputMode(uuid, 0, sessionSocket, eventSocket) }
                    if (isSessionAlive(sessionSocket) && eventCommandSocket === eventSocket && phoneOwnsScreen) {
                        onPhoneScreenReady?.invoke()
                    }
                }
            }
        } else {
            try {
                onCommand(command)
            } catch (error: Exception) {
                log("EVENT: 命令处理异常 - ${error.message}")
            }
        }
    }

    private fun publishModes(
        state: AirPlayResourceState,
        sessionSocket: Socket,
    ): Boolean {
        screenOwnershipConfirmed = false
        return try {
            val (status, body) =
                request(
                    "POST",
                    "/command",
                    BinaryPlist.encode(state.command()),
                    contentType = "application/x-apple-binary-plist",
                    expectedSocket = sessionSocket,
                )
            val accepted =
                AirPlayCommandResult.accepted(status, body) &&
                    isSessionAlive(sessionSocket) &&
                    resourceState === state &&
                    !cancelled
            screenOwnershipConfirmed = accepted && state.phoneOwnsScreen
            log("AIRPLAY: modesChanged 手机主屏=${state.phoneOwnsScreen} 接受=$accepted [$status]")
            accepted
        } catch (error: IOException) {
            log("AIRPLAY: modesChanged 失败 - ${error.message}")
            false
        }
    }

    /** 主控检查首帧、网络状态和稳定窗口后，再检查连接代次 */
    fun requestBluetoothHandoff(): Boolean {
        val sessionSocket = socket ?: return false
        val link = iapLink ?: return false
        val generation = link.controlGeneration
        if (cancelled || !link.supportsBootstrapHandoff || !link.networkStable || !phoneOwnsScreen) return false
        val deviceId = setupDeviceId()
        if (deviceId == DeviceIdentity.PLACEHOLDER_MAC || !Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(deviceId)) {
            log("HANDOFF: 本机蓝牙标识不可用，保留引导连接")
            return false
        }
        return try {
            val command =
                linkedMapOf(
                    "type" to "disableBluetooth",
                    "params" to linkedMapOf<String, Any?>("deviceID" to deviceId),
                )
            val (status, body) =
                request(
                    "POST",
                    "/command",
                    BinaryPlist.encode(command),
                    contentType = "application/x-apple-binary-plist",
                    expectedSocket = sessionSocket,
                    expectedIapLink = link,
                    expectedIapGeneration = generation,
                )
            val accepted =
                AirPlayCommandResult.accepted(status, body) &&
                    !cancelled &&
                    isSessionAlive(sessionSocket) &&
                    iapLink === link &&
                    link.controlGeneration == generation &&
                    link.networkStable &&
                    phoneOwnsScreen
            log("HANDOFF: disableBluetooth 接受=$accepted [$status] 代次=$generation")
            accepted
        } catch (error: IOException) {
            log("HANDOFF: 交接未确认，保留蓝牙 - ${error.message}")
            false
        }
    }

    /** 网络认证后，等待车机逐帧确认状态 */
    val networkReady: Boolean get() = iapLink?.networkReady == true

    val networkStable: Boolean get() = iapLink?.networkStable == true

    val supportsBootstrapHandoff: Boolean get() = iapLink?.supportsBootstrapHandoff == true

    /** 首次 SETUP 发送完整能力清单并接收端口；车机只启用双方能力的交集 */

    /** 初始协商及能力查询共同受剩余时限约束 */
    fun runSetup(windowMillis: Long = 30_000L): SetupInfo? {
        operationDeadline.set(nowMillis() + windowMillis)
        return try {
            runSetupWithinDeadline()
        } catch (error: IOException) {
            lastSetupFailure = SetupRetryPolicy.classify(null, error, cancelled)
            log("AIRPLAY: SETUP 结束 分类=${lastSetupFailure.title} - ${error.message}")
            null
        } finally {
            operationDeadline.remove()
        }
    }

    private fun runSetupWithinDeadline(): SetupInfo? {
        initialSetupAttempts++
        lastSetupFailure = SetupRetryPolicy.Failure.INVALID
        try {
            if (cancelled) {
                lastSetupFailure = SetupRetryPolicy.Failure.CANCELLED
                return null
            }
            if (!ensureConnected()) {
                lastSetupFailure = SetupRetryPolicy.Failure.CLOSED
                return null
            }
        } catch (error: IOException) {
            lastSetupFailure = SetupRetryPolicy.classify(null, error, cancelled)
            return null
        }
        val setupSocket = socket ?: return null
        // 计时端口：catplay 真车实现始终绑定真实 UDP timing server 通告给车机
        // （媒体时钟同步源）；timingPort=0 曾是我们的屏幕流 400 头号嫌疑。
        if (!timingServer.start()) {
            log("AIRPLAY: 计时服务未启动（继续，但媒体时钟无法同步）")
        }
        val deviceId = setupDeviceId()
        val body = buildSessionSetupBody(deviceId, timingServer.port, encrypted = cipher != null)
        log(
            "AIRPLAY: 初始 SETUP body=${body.size}B timingPort=${timingServer.port} " +
                "deviceID=$deviceId 加密=${if (cipher != null) "是" else "否（et=NONE）"}",
        )
        val (statusLine, responseBody) =
            try {
                // 部分老固件只接受相对路径 /setup，绝对 URI 会返回 400 或 404
                request(
                    "SETUP",
                    "/setup",
                    body,
                    contentType = "application/x-apple-binary-plist",
                    expectedSocket = setupSocket,
                )
            } catch (error: IOException) {
                lastSetupFailure = SetupRetryPolicy.classify(null, error, cancelled)
                log("AIRPLAY: SETUP 失败 - ${error.message}")
                return null
            }
        if (!isSessionAlive(setupSocket) || cancelled) {
            lastSetupFailure = if (cancelled) SetupRetryPolicy.Failure.CANCELLED else SetupRetryPolicy.Failure.CLOSED
            return null
        }
        if (!statusLine.contains("200")) {
            lastSetupFailure = SetupRetryPolicy.classify(statusLine, null, cancelled)
            log("AIRPLAY: SETUP 应答 [$statusLine]（自报 deviceID=$deviceId）")
            if (statusLine.contains("403")) {
                // 403 的两类成因按可能性排序：设备过滤（WinPlay 等「只接受指定
                // iPhone」）最常见，其次是接收端不接受的配对/信任状态。把本机
                // 蓝牙地址直接打出来，用户对照接收端设置即可自查。
                log(
                    "AIRPLAY: 403 排查——接收端若启用了设备过滤，请在接收端选择本机蓝牙地址 " +
                        "$deviceId" +
                        if (deviceId == DeviceIdentity.PLACEHOLDER_MAC) {
                            "（当前是占位值：未能解析真实蓝牙 MAC，内置服务是否就绪？）"
                        } else {
                            ""
                        },
                )
            }
            return null
        }
        val root =
            try {
                BinaryPlist.parse(responseBody)
            } catch (error: IllegalArgumentException) {
                log("AIRPLAY: SETUP 应答 plist 解析失败（${responseBody.size} 字节）")
                null
            } as? Map<*, *>
        if (root == null) {
            log("AIRPLAY: SETUP 应答不是字典")
            return null
        }
        // /info 决定显示尺寸、HID 和音频能力，失败时记下原因，方便排查
        var infoRoot: Map<*, *>? = null
        val infoMap = loadInfoWithDiagnostics(setupSocket)
        if (!isSessionAlive(setupSocket) || cancelled) return null
        infoRoot = infoMap
        io.github.shaonialife.buicklinkplus.ui.UiAppearance
            .initializeReceiver(setupSocket, infoMap?.get("nightMode"))
        resourceState.initialize(infoMap?.get("modes") as? Map<*, *>)
        val display = DisplayAreas.mainDisplay(infoMap?.get("displays") as? List<*>)
        if (display == null) {
            log(
                "AIRPLAY: 未取得 /info displays → 显示屏参数退回手机分辨率兜底" +
                    "（车机侧可能是小画面/黑边）；HID 设备为空 → 车机不会发 hidSendReport（触摸不可用）",
            )
        }
        // /info 全量转储：找奥迪私有的数据流 UUID（type-130 的 clientTypeUUID 候选）、
        // HID 设备描述（触摸）、以及任何我们没实现的能力声明
        // uuid 原样保留（奥迪发字符串 "0"/"1"，DiPlay 用整数——hidSetInputMode 原样回传）
        val parsedHidUuids = ArrayList<String>()
        // uuid → HID 描述符（媒体键记录需按下标取 Usage，触摸记录不需要但一并留存）
        val parsedHidDescriptors = HashMap<String, ByteArray>()
        val parsedTouchDevices = HashMap<String, TouchReports.Device>()
        infoRoot?.let { root ->
            val skipped = setOf("displays")
            val summary =
                root.entries.joinToString("; ") { (key, value) ->
                    if (key in skipped) "$key=[${(value as? List<*>)?.size ?: "?"}项]" else "$key=${value.toString().take(200)}"
                }
            log("AIRPLAY: /info 字段: $summary")
            // displays 已在 logInfoSummary 里逐条打印（含键值明细），此处不再重复
            // 音频能力逐条打印（整行 /info 被 take(200) 截断，audioFormats 恰好断在中间）：
            // 决定 audioType 只能靠这里——奥迪 type=100 到底声明了 compatibility 还是 media
            (root["audioFormats"] as? List<*>)?.forEachIndexed { index, entry ->
                val format = entry as? Map<*, *> ?: return@forEachIndexed
                val output = (format["audioOutputFormats"] as? Number)?.toLong() ?: 0L
                val input = (format["audioInputFormats"] as? Number)?.toLong() ?: 0L
                log(
                    "AIRPLAY: audioFormat[$index] type=${format["type"]} audioType=${format["audioType"]} " +
                        "output=0x${output.toString(16)} input=0x${input.toString(16)}" +
                        "（PCM44.1k/48k stereo=" +
                        "${if ((output and (1L shl 11)) != 0L) "✓" else "✗"}" +
                        "${if ((output and (1L shl 15)) != 0L) "✓" else "✗"}，" +
                        "AAC-LC 44.1k=${if ((output and (1L shl 22)) != 0L) "✓" else "✗"}）",
                )
            }
            (root["audioLatencies"] as? List<*>)?.forEachIndexed { index, entry ->
                val latency = entry as? Map<*, *> ?: return@forEachIndexed
                log(
                    "AIRPLAY: audioLatency[$index] type=${latency["type"]} audioType=${latency["audioType"]} " +
                        "in=${latency["inputLatencyMicros"]} out=${latency["outputLatencyMicros"]}",
                )
            }
            for (hidKey in listOf("hidDevices", "hids", "hid")) {
                (root[hidKey] as? List<*>)?.forEachIndexed { index, h ->
                    log("AIRPLAY: /info $hidKey[$index]=$h")
                    if (h is Map<*, *>) {
                        // UUID 可能是字符串或整数，统一转成字符串并原样回传
                        val uuid =
                            when (val raw = h["uuid"]) {
                                is Number -> raw.toString()
                                is String -> raw
                                else -> null
                            }
                        if (uuid != null) {
                            parsedHidUuids += uuid
                            val device = TouchReports.Device(h["hidDescriptor"] as? ByteArray, uuidText(h["displayUUID"]))
                            parsedTouchDevices[uuid] = device
                            if (device.format != null) {
                                log("TOUCH: 设备 uuid=$uuid display=${device.displayUuid} 格式=${device.format} 逻辑范围=${device.coordinateRange}")
                            }
                        }
                        (h["hidDescriptor"] as? ByteArray)?.let { descriptor ->
                            log(
                                "AIRPLAY: hidDescriptor[$index] ${descriptor.size}B hex=" +
                                    descriptor.take(96).joinToString(" ") { "%02x".format(it) },
                            )
                            // 媒体键（Consumer Control）的 Usage 数组顺序只能从描述符得到：
                            // Array 报告的字节是「下标」而非 Usage ID（见 ConsumerKeys 注释）
                            if (uuid != null) parsedHidDescriptors[uuid] = descriptor
                        }
                    }
                }
            }
        }
        log("AIRPLAY: hidDevices 解析结果 uuids=$parsedHidUuids")
        val displayW = ((display?.get("widthPixels") as? Number)?.toInt() ?: 1080).coerceIn(2, 16384)
        val displayH = ((display?.get("heightPixels") as? Number)?.toInt() ?: 2320).coerceIn(2, 16384)
        val info =
            SetupInfo(
                eventPort = portOf(root["eventPort"]),
                timingPort = portOf(root["timingPort"]),
                keepAlivePort = root["keepAlivePort"]?.let(::portOf)?.takeIf { it != 0 },
                enabledFeatures = (root["enabledFeatures"] as? List<*>)?.map { it.toString() }.orEmpty(),
                displayWidth = displayW,
                displayHeight = displayH,
                // /info displays[0] 的显示屏 UUID（真实 iPhone 会把它回填进屏幕流 SETUP；
                // 键名以小写 "uuid" 为准，大写 "UUID" 兜底）
                displayUuid =
                    uuidText(
                        display?.get("uuid")
                            ?: display?.get("UUID")
                            ?: display?.get("displayUUID"),
                    ),
                maxFps = ((display?.get("maxFPS") as? Number)?.toInt()?.takeIf { it > 0 } ?: 30).coerceAtMost(120),
                viewArea = DisplayAreas.viewArea(display, displayW, displayH),
                outsideUiAllowed = DisplayAreas.allowsOutsideUi(display),
                safeArea =
                    DisplayAreas.resolve(
                        display,
                        displayW,
                        displayH,
                    ),
                hidDeviceUuids = parsedHidUuids,
                hidDescriptors = parsedHidDescriptors,
                hidTouchDevices = parsedTouchDevices,
                // 奥迪 MMI 3315 声明 true：屏幕流 keepAlive 用 opcode=5 带 bplist 统计体
                keepAliveSendStatsAsBody = (infoRoot?.get("keepAliveSendStatsAsBody") as? Boolean) == true,
                receiverNightMode =
                    io.github.shaonialife.buicklinkplus.ui
                        .parseNightMode(infoRoot?.get("nightMode")),
            )
        log(
            "AIRPLAY: ✓ SETUP 完成 eventPort=${info.eventPort} timingPort=${info.timingPort} " +
                "keepAlive=${info.keepAlivePort ?: "无"} features=${info.enabledFeatures} " +
                "display=${info.displayWidth}x${info.displayHeight}@${info.maxFps} " +
                "displayUuid=${info.displayUuid} nightMode=${info.receiverNightMode ?: "跟随手机"}",
        )
        remainingOperationMillis(1)
        lastSetupFailure = SetupRetryPolicy.Failure.NONE
        return info
    }

    /** 读取 /info 并记录解析结果；会话内被拒时用独立短连接重试 */
    private fun loadInfoWithDiagnostics(sessionSocket: Socket): Map<*, *>? {
        var statusLine = "未请求"
        var body = ByteArray(0)
        try {
            val first = requestInfo(expectedSocket = sessionSocket)
            statusLine = first.first
            body = first.second
            if (!statusLine.contains("200") || body.isEmpty()) {
                log("AIRPLAY: /info 首次 [$statusLine] body=${body.size}B → 800ms 后重试一次")
                boundedOperationSleep(800)
                if (!isSessionAlive(sessionSocket) || cancelled) return null
                val retry = requestInfo(5_000, sessionSocket)
                log("AIRPLAY: /info 重试 [${retry.first}] body=${retry.second.size}B")
                if (retry.first.contains("200") && retry.second.isNotEmpty()) {
                    statusLine = retry.first
                    body = retry.second
                }
            }
        } catch (error: Exception) {
            log("AIRPLAY: /info 请求异常 - ${error.javaClass.simpleName}: ${error.message}")
        }
        if (!isSessionAlive(sessionSocket) || cancelled) return null
        parseInfoBody(statusLine, body)?.let { return it }
        scheduleSideProbe()
        return null
    }

    /** 延后 [delayMillis] 用短连接探测 /info，避开会话启动期；发现 HID 后补发 hidSetInputMode */
    private fun scheduleSideProbe(delayMillis: Long = 25_000) {
        val sessionSocket = socket ?: return
        Thread(
            {
                runCatching { Thread.sleep(delayMillis) }
                if (!isSessionAlive(sessionSocket) || cancelled) return@Thread
                val info = probeInfoOnSideConnection() ?: return@Thread
                if (!isSessionAlive(sessionSocket) || cancelled) return@Thread
                io.github.shaonialife.buicklinkplus.ui.UiAppearance
                    .initializeReceiver(sessionSocket, info["nightMode"])
                val eventSocket = eventCommandSocket ?: return@Thread
                val uuids =
                    (info["hidDevices"] as? List<*>).orEmpty().mapNotNull { entry ->
                        val hid = entry as? Map<*, *> ?: return@mapNotNull null
                        when (val raw = hid["uuid"]) {
                            is Number -> raw.toString()
                            is String -> raw
                            else -> null
                        }
                    }
                if (uuids.isEmpty()) {
                    log("AIRPLAY: 侧信道 /info 成功但车机未声明 HID 设备 → 触摸不走 AirPlay HID 通路")
                    return@Thread
                }
                log("AIRPLAY: 侧信道拿到 HID 设备 $uuids → 补发 hidSetInputMode（触摸可能立即生效）")
                uuids.forEach { uuid -> runCatching { sendHidSetInputMode(uuid, 0, sessionSocket, eventSocket) } }
            },
            "info-side-probe",
        ).apply { isDaemon = true }.start()
    }

    /** `/info` 报文 → 字典。失败时把原因与原始 hex 落日志，并缓存到 negotiatedInfo。 */
    private fun parseInfoBody(
        statusLine: String,
        body: ByteArray,
    ): Map<*, *>? {
        log("AIRPLAY: /info 应答 [$statusLine] body=${body.size}B")
        if (body.isEmpty()) return null
        val info =
            try {
                val result = BinaryPlist.parseRecovered(body)
                result.note?.let { log("AIRPLAY: /info $it") }
                result.value as? Map<*, *>
            } catch (error: IllegalArgumentException) {
                log("AIRPLAY: /info plist 解析失败 - ${error.message}")
                log("AIRPLAY: /info 原始体（${body.size}B）hex=" + hexDump(body, 320))
                null
            }
        if (info == null) return null
        logInfoSummary(info)
        negotiatedInfo = info
        return info
    }

    /** 用独立明文短连接探测 /info，读取一条响应后关闭 */
    private fun probeInfoOnSideConnection(timeoutMillis: Int = 4_000): Map<*, *>? {
        val target = endpoint
        if (target.airPlayPort <= 0) return null
        val host =
            activeHost ?: EndpointHosts.candidates(target.ipAddresses).firstOrNull() ?: return null
        var probe: Socket? = null
        return try {
            log("AIRPLAY: /info 侧信道探测 → $host:${target.airPlayPort}（独立短连接，不碰会话）")
            probe =
                Socket().apply {
                    connect(InetSocketAddress(resolveAddress(host), target.airPlayPort), timeoutMillis)
                    soTimeout = timeoutMillis
                }
            val payload = BinaryPlist.encode(linkedMapOf<String, Any?>())
            val head =
                "GET /info RTSP/1.0\r\n" +
                    "CSeq: 1\r\n" +
                    "User-Agent: AirPlay/$SENDER_SOURCE_VERSION\r\n" +
                    "Content-Type: application/x-apple-binary-plist\r\n" +
                    "Content-Length: ${payload.size}\r\n\r\n"
            probe.getOutputStream().apply {
                write(head.toByteArray(Charsets.US_ASCII) + payload)
                flush()
            }
            val input = probe.getInputStream()
            val collected = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var expected = -1
            while (true) {
                val count =
                    try {
                        input.read(chunk)
                    } catch (_: java.net.SocketTimeoutException) {
                        -1
                    }
                if (count < 0) break
                collected.write(chunk, 0, count)
                val bytes = collected.toByteArray()
                if (expected < 0) {
                    val separator = indexOf(bytes, HEAD_SEPARATOR)
                    if (separator >= 0) {
                        val (_, contentLength) =
                            parseRtspHead(String(bytes, 0, separator, Charsets.ISO_8859_1))
                        expected = separator + HEAD_SEPARATOR.size + contentLength
                    }
                }
                if (expected >= 0 && bytes.size >= expected) break
            }
            val bytes = collected.toByteArray()
            val separator = indexOf(bytes, HEAD_SEPARATOR)
            if (separator < 0) {
                log("AIRPLAY: /info 侧信道无有效应答（${bytes.size}B，车机可能只服务已绑定会话）")
                return null
            }
            val headText = String(bytes, 0, separator, Charsets.ISO_8859_1)
            val (line, contentLength) = parseRtspHead(headText)
            val bodyStart = separator + HEAD_SEPARATOR.size
            val infoBody = bytes.copyOfRange(bodyStart, minOf(bodyStart + contentLength, bytes.size))
            return parseInfoBody("侧信道 $line", infoBody)
        } catch (error: Exception) {
            log("AIRPLAY: /info 侧信道失败 - ${error.javaClass.simpleName}: ${error.message}")
            null
        } finally {
            runCatching { probe?.close() }
        }
    }

    class SetupInfo(
        val eventPort: Int,
        val timingPort: Int,
        val keepAlivePort: Int?,
        val enabledFeatures: List<String>,
        /** 主显示屏参数（/info displays[0]，跟随接收端当前屏幕方向）。 */
        val displayWidth: Int,
        val displayHeight: Int,
        val displayUuid: String?,
        val maxFps: Int,
        val viewArea: PixelArea = PixelArea(0, 0, displayWidth, displayHeight),
        val safeArea: PixelArea = viewArea,
        val outsideUiAllowed: Boolean = false,
        /** /info hidDevices[].uuid 列表（0=触摸、1=旋钮；DiPlay 同款编号）。 */
        val hidDeviceUuids: List<String> = emptyList(),
        /** HID uuid 到描述符的映射；媒体键 Array 报告需按描述符中的 Usage 下标解码 */
        val hidDescriptors: Map<String, ByteArray> = emptyMap(),
        /** 每个设备独立的触控格式、逻辑范围与显示屏绑定 */
        val hidTouchDevices: Map<String, TouchReports.Device> = emptyMap(),
        /** 屏幕流保活是否带统计体：true 用 opcode=5 和 bplist，false 用 opcode=2 空帧 */
        val keepAliveSendStatsAsBody: Boolean = false,
        /** 本次连接明确通告的车机日夜值，缺失时跟随手机 */
        val receiverNightMode: Boolean? = null,
    )

    /** 屏幕流协商结果。 */
    class ScreenStreamInfo(
        val streamConnectionId: Long,
        val dataPort: Int,
        /** 帧加密方式：ChaCha20（已配对）或 AES-128-CTR（未配对会话）。 */
        val cipher: StreamFrameCipher,
    )

    /**
     * type-130 iAP 隧道，接收车机的 iAP2 消息
     * ChaCha 帧为 2B LE 长度 AAD、密文和 16B tag，nonce 为 8B LE 计数器
     * 密钥使用 DataStream-Salt<seed> 和 DataStream-Output-Encryption-Key 派生
     * 明文为 APTransportPackage：4B BE 总长、32B 头、偏移 16 的 comm 类型及 iAP2 消息体
     */
    inner class IapTunnelClient(
        val socket: Socket,
        private val readKey: ByteArray,
        private val logPrefix: String = "TUNNEL",
        private val writeKey: ByteArray = readKey,
    ) {
        @Volatile
        var messageCount = 0
            private set

        /** 车机→手机 iAP2 消息回调（CSM 帧字节）。 */
        var onIapMessage: ((Iap2Csm.Frame) -> Unit)? = null

        private val parser =
            Iap2Csm
                .Parser()

        private val writeLock = Any()
        private var writeCounter = 0L
        private var writeFailureLogged = false

        /**
         * 加密后写入 type-130 隧道，连接关闭或写入失败返回 false
         */
        fun writeIapBytes(bytes: ByteArray): Boolean {
            if (socket.isClosed || !socket.isConnected || bytes.isEmpty()) return false
            return try {
                val size = TUNNEL_PACKAGE_HEADER_BYTES + bytes.size
                val plain = ByteArray(size)
                plain[0] = (size ushr 24).toByte()
                plain[1] = (size ushr 16).toByte()
                plain[2] = (size ushr 8).toByte()
                plain[3] = size.toByte()
                plain[16] = 'c'.code.toByte()
                plain[17] = 'o'.code.toByte()
                plain[18] = 'm'.code.toByte()
                plain[19] = 'm'.code.toByte()
                bytes.copyInto(plain, TUNNEL_PACKAGE_HEADER_BYTES)
                val aad =
                    byteArrayOf(
                        (size and 0xFF).toByte(),
                        ((size ushr 8) and 0xFF).toByte(),
                    )
                // 计数器自增必须与写 socket 同锁：ChaCha nonce 乱序 = 对端解密失败
                synchronized(writeLock) {
                    val sealed =
                        AirPlayCrypto.chachaSeal(
                            writeKey,
                            AirPlayCrypto.nonce64(writeCounter++),
                            plain,
                            aad,
                        )
                    val output = socket.getOutputStream()
                    output.write(aad)
                    output.write(sealed)
                    output.flush()
                }
                writeFailureLogged = false
                true
            } catch (error: IOException) {
                if (!writeFailureLogged) {
                    writeFailureLogged = true
                    log("$logPrefix: 写出失败 - ${error.message}")
                }
                false
            }
        }

        fun startReading() {
            Thread({
                val input = socket.getInputStream()
                var pending = ByteArray(0)
                var plaintext = ByteArray(0)
                val buffer = ByteArray(16 * 1024)
                try {
                    while (socket.isClosed == false) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        pending += buffer.copyOf(count)
                        val decrypted = decryptFrames(pending)
                        pending = decrypted.second
                        plaintext += decrypted.first
                        plaintext = parsePackages(plaintext)
                    }
                    log("$logPrefix: 通道关闭（EOF），共 $messageCount 条 iAP2 消息")
                } catch (error: IOException) {
                    log("$logPrefix: 读取结束 - ${error.message}")
                }
            }, "iap-tunnel-reader").apply {
                isDaemon = true
                start()
            }
        }

        /** ChaCha 帧解密（与 ControlCipher 同帧格式，独立计数器/密钥）。 */
        private fun decryptFrames(buffer: ByteArray): Pair<ByteArray, ByteArray> {
            val output = ArrayList<ByteArray>()
            var offset = 0
            while (buffer.size - offset >= 2) {
                val length = (buffer[offset].toInt() and 0xFF) or ((buffer[offset + 1].toInt() and 0xFF) shl 8)
                val frameLength = 2 + length + 16
                if (buffer.size - offset < frameLength) break
                val aad = buffer.copyOfRange(offset, offset + 2)
                val sealed = buffer.copyOfRange(offset + 2, offset + frameLength)
                val plain =
                    AirPlayCrypto.chachaOpen(
                        readKey,
                        AirPlayCrypto.nonce64(tunnelReadCounter++),
                        sealed,
                        aad,
                    )
                output += plain
                offset += frameLength
            }
            val rest = buffer.copyOfRange(offset, buffer.size)
            return if (output.isEmpty()) ByteArray(0) to rest else output.reduce { a, b -> a + b } to rest
        }

        /** 解析 APTransportPackage：4B BE 总长、32B 头，偏移 16 的 comm 后接 iAP2 消息体 */
        private fun parsePackages(buffer: ByteArray): ByteArray {
            var offset = 0
            while (buffer.size - offset >= 32) {
                val size =
                    ((buffer[offset].toInt() and 0xFF) shl 24) or
                        ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
                        ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
                        (buffer[offset + 3].toInt() and 0xFF)
                if (size < 32 || buffer.size - offset < size) break
                val messageType = String(buffer, offset + 16, 4, Charsets.US_ASCII)
                if (messageType == "comm") {
                    val body = buffer.copyOfRange(offset + 32, offset + size)
                    onIapBytes(body)
                }
                offset += size
            }
            return buffer.copyOfRange(offset, buffer.size)
        }

        private fun onIapBytes(bytes: ByteArray) {
            messageCount++
            val frames = parser.offer(bytes)
            for (frame in frames) {
                // hex 格式化有成本：只在真要打日志的分支里做（此前每条消息都先算一遍）
                if (messageCount <= 20) {
                    val hex = bytes.joinToString(" ") { "%02x".format(it) }
                    log(
                        "$logPrefix: iAP2 msg #$messageCount id=0x${frame.messageId.toString(16)} " +
                            "(${Iap2MessageIds.nameOf(frame.messageId)}) body=${frame.body.size}B " +
                            "hex=${hex.take(160)}",
                    )
                } else if (messageCount % 100 == 0) {
                    log("$logPrefix: iAP2 msg #$messageCount id=0x${frame.messageId.toString(16)} body=${frame.body.size}B")
                }
                onIapMessage?.invoke(frame)
            }
        }

        private var tunnelReadCounter = 0L
    }

    /**
     * 建立 type-130 隧道并连接 dataPort，失败返回 null
     * 手机发往车机的事件命令使用 [sendIap]
     */
    fun runIapTunnel(sessionKey: ByteArray?): IapTunnelClient? {
        val sessionSocket = socket ?: return null
        if (iapLink?.usesControlTransport == true) {
            log("TUNNEL: 已观察到控制通道 iAP2 协商，跳过 type-130，保持当前路由")
            return null
        }
        // 隧道 socket 用 DataStream 密钥加密，未配对会话没有共享密钥可派生 → 跳过。
        if (sessionKey == null) {
            log("TUNNEL: 未配对会话没有共享密钥——跳过 iAP 隧道（触摸不可用）")
            return null
        }
        val host = receiverHost() ?: return null
        val seed = (System.currentTimeMillis() % 0x7FFF_FFFFL) + 1L
        val connectionId = java.security.SecureRandom().nextLong() and Long.MAX_VALUE or 1L
        val body =
            BinaryPlist.encode(
                linkedMapOf(
                    "streams" to
                        listOf(
                            linkedMapOf<String, Any?>(
                                "type" to 130L,
                                "clientTypeUUID" to IAP_DATASTREAM_UUID,
                                "seed" to seed,
                                "streamConnectionID" to connectionId,
                            ),
                        ),
                ),
            )
        val (statusLine, responseBody) =
            try {
                request("SETUP", "/", body, contentType = "application/x-apple-binary-plist", expectedSocket = sessionSocket)
            } catch (error: IOException) {
                log("TUNNEL: SETUP 失败 - ${error.message}")
                return null
            }
        if (!statusLine.contains("200")) {
            log("TUNNEL: SETUP 应答 [$statusLine]（响应头：${lastResponseHeadOfRequest()}）")
            return null
        }
        val root =
            try {
                BinaryPlist.parse(responseBody) as? Map<*, *>
            } catch (_: IllegalArgumentException) {
                null
            } ?: run {
                log("TUNNEL: SETUP 应答解析失败")
                return null
            }
        val streams = root["streams"] as? List<*>
        val stream = streams?.firstOrNull() as? Map<*, *>
        val dataPort = ((stream?.get("dataPort") as? Long) ?: 0L).toInt() and 0xFFFF
        if (dataPort == 0) {
            log("TUNNEL: SETUP 应答无 dataPort（streams=$streams）")
            return null
        }
        val key =
            AirPlayCrypto.hkdfSha512(
                sessionKey,
                "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII),
                "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
            )
        val tcp =
            try {
                Socket()
            } catch (error: IOException) {
                return null
            }
        return try {
            tcp.tcpNoDelay = true
            tcp.connect(java.net.InetSocketAddress(host, dataPort), 5_000)
            log("TUNNEL: ✓ iAP 隧道已建立 $host:$dataPort（seed=$seed）")
            val client = IapTunnelClient(tcp, key)
            synchronized(rtspWriteLock) {
                if (!isSessionAlive(sessionSocket) || cancelled || iapLink?.usesControlTransport == true) {
                    tcp.close()
                    return null
                }
                iapTunnel = client
                client.startReading()
            }
            client
        } catch (error: IOException) {
            log("TUNNEL: TCP 连接失败 - ${error.message}")
            runCatching { tcp.close() }
            null
        }
    }

    /** type-130 隧道存在时，虚拟链路通过其 TCP 发送；否则使用 [sendIap] 事件通道 */
    @Volatile
    private var iapTunnel: IapTunnelClient? = null

    private var sendIapCount = 0

    /** 通过事件通道发送 iAPSendMessage，使用 params 信封和 data 字节 */
    fun sendIap(data: ByteArray): Boolean {
        val sent =
            postEventCommand(
                linkedMapOf(
                    "type" to "iAPSendMessage",
                    "params" to linkedMapOf<String, Any?>("data" to data),
                ),
            )
        sendIapCount++
        if (sendIapCount <= 10) {
            log(
                "IAPLINK: tx(事件通道) ${data.size}B ok=$sent hex=" +
                    data.joinToString(" ") { "%02x".format(it) }.take(120),
            )
        }
        return sent
    }

    // 事件通道也可能携带虚拟 iAP2 消息，由 Iap2LinkEngine 处理握手和收发

    @Volatile
    private var iapLink: VirtualIap2Link? = null

    /** 车机媒体键回调：iAPSendMessage → 0x6802 → ConsumerKeys → Android 按键 */
    var onMediaKey: ((Int) -> Unit)? = null

    /** 虚拟链路唯一实例（出站路由/三形态语义见 [VirtualIap2Link]）。 */
    private fun obtainIapLink(): VirtualIap2Link =
        synchronized(rtspWriteLock) {
            val sessionSocket = socket ?: throw IOException("控制连接已退役")
            if (sessionSocket.isClosed) throw IOException("控制连接已关闭")
            iapLink
                ?: VirtualIap2Link(
                    log = { line -> log(line) },
                    sendEventIap = ::sendIap,
                    sendControlIap = { frame -> postControlIap(frame, sessionSocket) },
                    tunnelWriter = { iapTunnel },
                    alive = { isSessionAlive(sessionSocket) },
                    mfiContext = context,
                    localBluetoothMac = setupDeviceId(),
                    localDeviceName = context?.let(DeviceIdentity::deviceName) ?: "iPhone",
                    nowMillis = nowMillis,
                ).also {
                    it.onMediaKey = { keyCode -> onMediaKey?.invoke(keyCode) }
                    it.begin()
                    iapLink = it
                }
        }

    /** 车机 iAPSendMessage 字节入口（SessionController 事件命令处理器调用）。 */
    fun onIapEventBytes(data: ByteArray) {
        if (socket?.isClosed != false) return
        obtainIapLink().onCarBytes(data)
    }

    /** 屏幕流 SETUP 后经控制通道发送 DETECT 并等待 MIB3 SYN，明文会话跳过 */
    private fun armMib3VirtualLink() {
        if (cipher == null) return
        if (iapTunnel != null) return // DiPlay 隧道形态：不需要（也不该）布防
        obtainIapLink().armMib3()
    }

    /**
     * MIB3 的 iAP2 帧经控制连接 POST /command 发送
     * 使用串行执行器避免阻塞链路节拍，与 /feedback 共用 CSeq 和写锁
     */
    private val controlIapTx =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "mib3-iap-tx").apply { isDaemon = true }
        }
    private var controlIapCount = 0

    fun postControlIap(frame: ByteArray) {
        val sessionSocket = socket ?: return
        postControlIap(frame, sessionSocket)
    }

    /** 入队和写入时都检查连接代次，丢弃旧任务 */
    private fun postControlIap(
        frame: ByteArray,
        sessionSocket: Socket,
    ) {
        if (!isSessionAlive(sessionSocket)) return
        val targetLink = iapLink
        val targetGeneration = targetLink?.controlGeneration
        controlIapTx.execute {
            if (!isSessionAlive(sessionSocket)) return@execute
            if (targetLink != null && (iapLink !== targetLink || targetLink.controlGeneration != targetGeneration)) return@execute
            try {
                val body =
                    BinaryPlist.encode(
                        linkedMapOf(
                            "type" to "iAPSendMessage",
                            "params" to linkedMapOf<String, Any?>("data" to frame),
                        ),
                    )
                val (statusLine, reply) =
                    request(
                        "POST",
                        "/command",
                        body,
                        contentType = "application/x-apple-binary-plist",
                        expectedSocket = sessionSocket,
                        expectedIapLink = targetLink,
                        expectedIapGeneration = targetGeneration,
                    )
                controlIapCount++
                val head =
                    "IAPLINK: tx(控制通道) #$controlIapCount ${frame.size}B [$statusLine]" +
                        (if (reply.isEmpty()) "" else " resp=${reply.size}B hex=" + hexPrefix(reply))
                if (controlIapCount <= 10 || controlIapCount % 50 == 0 || reply.isNotEmpty()) {
                    log(head)
                }
                // 车机可能把链路字节直接塞在应答体里（而不是另发事件通道）：认出来就喂给
                // 链路引擎，别让它白躺在响应里。
                if (isSessionAlive(sessionSocket) &&
                    iapLink === targetLink &&
                    targetLink?.controlGeneration == targetGeneration &&
                    reply.isNotEmpty() &&
                    looksLikeIapLink(reply)
                ) {
                    log("IAPLINK: 控制通道应答内含链路字节 ${reply.size}B → 喂给链路引擎")
                    targetLink?.onCarBytes(reply, targetGeneration)
                }
            } catch (error: IOException) {
                log("IAPLINK: 控制通道 POST 失败 - ${error.message}")
            }
        }
    }

    /** 应答体的十六进制前缀（诊断用，截断到 24 字节）。 */
    private fun hexPrefix(
        bytes: ByteArray,
        limit: Int = 24,
    ): String = bytes.take(limit).joinToString(" ") { "%02x".format(it) } + if (bytes.size > limit) "…" else ""

    /** 输出报文十六进制，默认最多 480 字节，每行 48 字节 */
    private fun hexDump(
        bytes: ByteArray,
        limit: Int = 480,
        perLine: Int = 48,
    ): String {
        if (bytes.isEmpty()) return "（空）"
        val shown = minOf(bytes.size, limit)
        val builder = StringBuilder(shown * 3)
        var index = 0
        while (index < shown) {
            if (index > 0) builder.append("\n    ")
            val end = minOf(index + perLine, shown)
            for (position in index until end) {
                builder.append("%02x".format(bytes[position]))
                if (position != end - 1) builder.append(' ')
            }
            index = end
        }
        if (bytes.size > shown) builder.append(" …（共 ${bytes.size}B，只显示前 $shown）")
        return builder.toString()
    }

    /** 应答体是否是 iAP2 链路帧（marker 0xFF55 / 同步包 0xFF5A）。 */
    private fun looksLikeIapLink(bytes: ByteArray): Boolean =
        bytes.size >= 6 &&
            bytes[0] == 0xFF.toByte() &&
            (bytes[1] == 0x5A.toByte() || bytes[1] == 0x55.toByte())

    /** 音频流协商结果（RTP over UDP；密钥同视频流规则，盐用音频连接 ID）。 */
    class AudioStreamInfo(
        val streamConnectionId: Long,
        val dataPort: Int,
        val controlPort: Int,
        val streamKey: ByteArray,
        /** 协商出的采样率（Hz）：按车机声明选择，AudioSender 必须用同一值采集。 */
        val sampleRate: Int = 48_000,
    )

    /** plist 端口是 u16 但以 2 字节有符号整数编码（>32767 读出为负），按位掩出真实值。 */
    private fun portOf(value: Any?): Int = ((value as? Long) ?: 0L).toInt() and 0xFFFF

    private companion object {
        /** 主屏视频流类型，100 为主音频，130 为 iAP 隧道 */
        const val SCREEN_STREAM_TYPE = 110L

        /** 主音频流类型（DiPlay STREAM_TYPE_MAIN_AUDIO；PCM 通路）。 */
        const val AUDIO_STREAM_TYPE = 100L

        /** 高质量主音频流类型，参考 catplay MainHighAudio */
        const val AUDIO_STREAM_TYPE_MAIN_HIGH = 102L

        /** PCM 48kHz、16bit 立体声格式位，参考 catplay AudioFormat */
        const val AUDIO_FORMAT_PCM_48K_STEREO = 1L shl 15

        /** 音频格式位：PCM 44100Hz 16bit 立体声（1<<11；48k 不可用时的回退）。 */
        const val AUDIO_FORMAT_PCM_44K_STEREO = 1L shl 11

        /** AAC-LC 44.1kHz 立体声格式位，参考 catplay AAC_LC_44100_STEREO */
        const val AUDIO_FORMAT_AAC_LC_44K_STEREO = 1L shl 22

        /** AAC-LC 48kHz 立体声格式位，参考 catplay AAC_LC_48000_STEREO */
        const val AUDIO_FORMAT_AAC_48K_STEREO = 1L shl 23

        /** 媒体通道标识，供 [negotiateAudio] 优先选择；/info 缺失时也使用此值 */
        const val AUDIO_TYPE_MEDIA = "media"

        /** 车机未声明输出延迟时使用 250ms，合法范围 32～2000ms */
        const val AUDIO_LATENCY_FALLBACK_MS = 250L

        /** 车机音量 ≤ 这个值（dB）视为静音位（AirPlay 里 -144 约等于全静音）。 */
        private const val MUTE_THRESHOLD_DB = -100f

        /** 从静音恢复的安全音量，单位 dB */
        private const val SAFE_VOLUME_DB = -25.0f

        /** 解析 `volume: -12.345678` 这类文本参数。 */
        private val VOLUME_REGEX = Regex("volume:\\s*(-?[0-9.]+)")

        /** SETUP 使用的完整能力清单，参考 catplay；接收端按双方交集启用功能 */
        val CONTROLLER_FEATURES =
            listOf(
                "uiContext",
                "viewAreas",
                "cornerMasks",
                "focusTransfer",
                "h.264Level5.1",
                "mainBuffered",
                "altScreen",
                "enhancedSiri",
                "hevc",
                "sessionManagement",
                "logTransfer",
                "iAPChannel",
                "vehicleStateProtocol",
                "videoPlayback",
            )

        /** 等车机打开 AirPlay 窗口的总时长（用户在车机上点手机图标的等待窗口）。 */
        private const val PAIRING_WINDOW_MILLIS = 120_000L

        /** 配对重试中重新公告 ctrl 的间隔轮数 */
        private const val RETRY_TICK_ATTEMPTS = 10

        /** 表示不支持配对接口的状态码，命中后转用明文会话 */
        private val PAIRING_UNSUPPORTED_STATUS = listOf("404", "405", "455", "501")

        /** 发送端 AirPlay 版本，参考 catplay，不能使用车机版本 */
        private const val SENDER_SOURCE_VERSION = "566.25.21"

        /** UDP 保活间隔，短于车机 10 秒看门狗超时 */
        private const val KEEPALIVE_INTERVAL_MS = 5_000L

        /** 读线程结束/连接关闭时投递给等待者的哨兵状态行（非真实 RTSP 应答）。 */
        private const val RTSP_CLOSED_MARK = "__closed__"

        /** 屏幕流延迟（catplay c2a car_manager 实测值）。 */
        private const val SCREEN_LATENCY_MS = 25L

        /** iAP DataStream 的 clientTypeUUID，参考 DiPlay IAP_DATASTREAM_UUID */
        private const val IAP_DATASTREAM_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"

        /** APTransportPackage 头：4B BE 总长、12B 保留、4B comm、12B 保留 */
        private const val TUNNEL_PACKAGE_HEADER_BYTES = 32

        /** RTSP 头分隔符和字段正则，供请求与响应重组共用 */
        private val HEAD_SEPARATOR = "\r\n\r\n".toByteArray(Charsets.US_ASCII)

        private val CONTENT_LENGTH_REGEX = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)

        private val CSEQ_REGEX = Regex("CSeq:\\s*(\\d+)", RegexOption.IGNORE_CASE)

        /** 解析 RTSP 首行和 Content-Length */
        fun parseRtspHead(headText: String): Pair<String, Int> {
            val contentLength =
                CONTENT_LENGTH_REGEX
                    .find(headText)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull() ?: 0
            val firstLine = headText.lineSequence().firstOrNull() ?: ""
            return firstLine to contentLength
        }

        fun indexOf(
            source: ByteArray,
            target: ByteArray,
        ): Int {
            outer@ for (index in 0..source.size - target.size) {
                for (offset in target.indices) {
                    if (source[index + offset] != target[offset]) continue@outer
                }
                return index
            }
            return -1
        }
    }

    /**
     * 协商音频端口和密钥，采样率与 [AudioSender] 一致
     * 复用 /info 选择 audioType 和延迟，连接编号使用随机正 i64
     */
    fun runAudioStream(
        sharedSecret: ByteArray,
        profile: AudioProfile,
        tuning: AudioTuning = AudioTuning.RAW_SPF,
    ): AudioStreamInfo? {
        // 调用方结合车机声明和成功记录选择档位，失败后再换下一档
        val negotiation = profile
        log(
            "AIRPLAY: 音频协商 → audioType=${profile.audioType} " +
                "format=0x${profile.formatBit.toString(16)} ${profile.sampleRate}Hz " +
                "latency=${profile.latencyMs}ms（档位=${profile.label}，" +
                "封装=${tuning.detail}）",
        )
        // 随机正 i64：BinaryPlist 按有符号整数编码，掩掉符号位避免负数
        val connectionId = (SecureRandom().nextLong() and Long.MAX_VALUE).coerceAtLeast(1L)
        // spf 取值：AAC 一帧 1024 采样；PCM 与发送端每包 352 帧保持一致
        val spfValue =
            tuning.spf?.let {
                if (profile.codec == AudioCodec.AAC_LC) it.toLong() else AudioSender.SAMPLES_PER_PACKET.toLong()
            }
        val body =
            BinaryPlist.encode(
                linkedMapOf(
                    "streams" to
                        listOf(
                            linkedMapOf<String, Any?>().apply {
                                put("type", profile.streamType)
                                put("streamConnectionID", connectionId)
                                put("audioFormat", negotiation.formatBit)
                                put("audioType", negotiation.audioType)
                                put("audioLatencyMs", negotiation.latencyMs)
                                // 显式声明只下行、不发送冗余包，避免严格接收端拒绝音频流
                                put("input", false)
                                put("supportsRTPPacketRedundancy", false)
                                put("supportsHighAccuracyTimestamps", tuning.highAccuracyTimestamps)
                                // 声明每包样本帧数：AAC 为 1024，PCM 为 352，供车机安排播放
                                spfValue?.let { put("spf", it) }
                            },
                        ),
                ),
            )
        val host = endpoint.ipAddresses.firstOrNull() ?: "receiver"
        val startedAt = SystemClock.uptimeMillis()
        val (statusLine, responseBody) =
            try {
                request(
                    "SETUP",
                    "rtsp://$host:${endpoint.airPlayPort}/",
                    body,
                    // 音频 SETUP 可能耗时数秒，留出 8 秒；等待时心跳仍由独立线程发送
                    timeoutMillis = 8_000,
                    contentType = "application/x-apple-binary-plist",
                )
            } catch (error: IOException) {
                log("AIRPLAY: 音频流协商失败 - ${error.message}")
                return null
            }
        log("AIRPLAY: 音频 SETUP 往返 ${SystemClock.uptimeMillis() - startedAt}ms [$statusLine]")
        if (!statusLine.contains("200")) {
            log("AIRPLAY: 音频流协商应答 [$statusLine]")
            return null
        }
        val root =
            try {
                BinaryPlist.parse(responseBody) as? Map<*, *>
            } catch (error: IllegalArgumentException) {
                null
            } ?: run {
                log("AIRPLAY: 音频流协商应答解析失败")
                return null
            }
        val streams =
            root["streams"] as? List<*> ?: run {
                log("AIRPLAY: 音频流协商应答缺少 streams")
                return null
            }
        val audio =
            streams.firstOrNull { entry ->
                ((entry as? Map<*, *>)?.get("type") as? Long) == profile.streamType
            } as? Map<*, *> ?: run {
                log("AIRPLAY: 音频流被拒绝（streams=$streams）")
                return null
            }
        val dataPort = portOf(audio["dataPort"])
        if (dataPort == 0) {
            log("AIRPLAY: 音频流未返回 dataPort（$audio）")
            return null
        }
        val streamKey =
            AirPlayCrypto.hkdfSha512(
                sharedSecret,
                "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
                "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
            )
        log(
            "AIRPLAY: ✓ 音频流协商成功（type=${profile.streamType} ${negotiation.audioType} " +
                "format=0x${negotiation.formatBit.toString(16)} ${negotiation.sampleRate}Hz " +
                "latency=${negotiation.latencyMs}ms）" +
                "dataPort=$dataPort controlPort=${portOf(audio["controlPort"])} connId=$connectionId" +
                " 全字段=$audio",
        )
        // 音频通路已建好，但「车机在收数据、喇叭没声」还剩一种很常见的原因：
        // 接收端的 AirPlay 音量处于静音位（-144dB）——真 iPhone 会主动 SET_PARAMETER
        // volume。这里先查一次（顺带把值记进日志），查到静音位就抬到 -25dB。
        probeAndFixVolume(host)
        activeAudioConnectionId = connectionId
        lastAudioSample = null
        lastAudioFeedbackAt = 0L
        lastAudioAdvanceAt = 0L
        return AudioStreamInfo(
            streamConnectionId = connectionId,
            dataPort = dataPort,
            controlPort = portOf(audio["controlPort"]),
            streamKey = streamKey,
            sampleRate = negotiation.sampleRate,
        )
    }

    /** 查询接收端音量；低于 [MUTE_THRESHOLD_DB] 时调到 [SAFE_VOLUME_DB]，查询失败不影响连接 */
    private fun probeAndFixVolume(host: String) {
        val expectedSocket = socket ?: return
        val policy = receiverVolumePolicy
        if (!policy.setAllowed) return
        val url = "rtsp://$host:${endpoint.airPlayPort}/"
        val volume =
            if (!policy.queryAllowed) {
                null
            } else {
                try {
                    val (status, body) =
                        request(
                            "GET_PARAMETER",
                            url,
                            "volume".toByteArray(Charsets.US_ASCII),
                            timeoutMillis = 4_000,
                            contentType = "text/parameters",
                            expectedSocket = expectedSocket,
                        )
                    val accepted = policy.observe(true, status)
                    val text = body.toString(Charsets.UTF_8).trim()
                    log("AIRPLAY: 音量查询 状态=$status 接受=$accepted 长度=${body.size}B 查询能力=${policy.queryAllowed}")
                    if (accepted) {
                        VOLUME_REGEX
                            .find(text)
                            ?.groupValues
                            ?.get(1)
                            ?.toFloatOrNull()
                    } else {
                        null
                    }
                } catch (error: Exception) {
                    log("AIRPLAY: 音量查询失败 ${error.message}，不影响媒体发送")
                    null
                }
            }
        if (volume != null && volume > MUTE_THRESHOLD_DB) return
        if (!isSessionAlive(expectedSocket) || receiverVolumePolicy !== policy || !policy.setAllowed) return
        try {
            val payload = String.format(java.util.Locale.US, "volume: %.6f", SAFE_VOLUME_DB)
            val (status, body) =
                request(
                    "SET_PARAMETER",
                    url,
                    payload.toByteArray(Charsets.US_ASCII),
                    timeoutMillis = 4_000,
                    contentType = "text/parameters",
                    expectedSocket = expectedSocket,
                )
            val accepted = policy.observe(false, status)
            log(
                "AIRPLAY: 音量设置目标=$SAFE_VOLUME_DB dB 原值=$volume 接受=$accepted 状态=$status 长度=${body.size}B " +
                    "设置能力=${policy.setAllowed}；接受不代表扬声器已出声",
            )
        } catch (error: Exception) {
            log("AIRPLAY: 音量设置失败 ${error.message}，不影响媒体发送")
        }
    }

    /**
     * 按 /info 排列音频候选，失败后可依次换档
     * PCM 优先 48k 立体声，缺少声明时使用 media、48k 和 250ms
     * [preferCompat] 开启时优先兼容档
     */
    fun audioCandidates(preferCompat: Boolean = false): List<AudioProfile> {
        val baseline =
            listOf(
                AudioProfile(AUDIO_TYPE_MEDIA, AUDIO_FORMAT_PCM_48K_STEREO, 48_000, AUDIO_LATENCY_FALLBACK_MS),
            )
        val info = negotiatedInfo ?: return baseline
        val declared =
            (info["audioFormats"] as? List<*>)
                ?.mapNotNull { it as? Map<*, *> }
                .orEmpty()
        if (declared.isEmpty()) {
            log("AIRPLAY: 车机 /info 未声明 audioFormats → 按协议基线尝试")
            return baseline
        }

        // AAC-LC 优先使用 102/media，再尝试 type=100，避免选到不播放的通路
        val aac = mutableListOf<AudioProfile>()
        val aacOptions =
            listOf(
                Triple(AUDIO_STREAM_TYPE_MAIN_HIGH, AUDIO_FORMAT_AAC_LC_44K_STEREO, 44_100),
                Triple(AUDIO_STREAM_TYPE_MAIN_HIGH, AUDIO_FORMAT_AAC_48K_STEREO, 48_000),
                Triple(AUDIO_STREAM_TYPE, AUDIO_FORMAT_AAC_LC_44K_STEREO, 44_100),
                Triple(AUDIO_STREAM_TYPE, AUDIO_FORMAT_AAC_48K_STEREO, 48_000),
            )
        aacOptions.forEach { (streamType, bit, rate) ->
            declared
                .filter { ((it["type"] as? Number)?.toLong()) == streamType }
                .forEach { channel ->
                    val output = (channel["audioOutputFormats"] as? Number)?.toLong() ?: 0L
                    if ((output and bit) == 0L) return@forEach
                    val audioType = (channel["audioType"] as? String)?.lowercase() ?: return@forEach
                    // 媒体音频只走 media 档；compatibility/default 是提示音/兼容场景
                    if (audioType != AUDIO_TYPE_MEDIA) return@forEach
                    aac +=
                        AudioProfile(
                            audioType = audioType,
                            formatBit = bit,
                            sampleRate = rate,
                            latencyMs = latencyFor(info, audioType, streamType),
                            streamType = streamType,
                            codec = AudioCodec.AAC_LC,
                        )
                }
        }

        // ---- 2) PCM 通路（只存在于 type=100）----
        val formats = declared.filter { ((it["type"] as? Number)?.toLong()) == AUDIO_STREAM_TYPE }
        val pcm = mutableListOf<AudioProfile>()
        // 只考虑「媒体类」档位：车机还会声明 telephony / speechRecognition / alert 等档，
        // 它们位图里同样有 PCM48k，但把媒体流推进语音/提示音通路只会得到怪声或无声。
        // media / compatibility / default 这三档是收发两端都当作媒体通路的档位。
        val mediaTypes =
            formats.filter { channel ->
                val type = (channel["audioType"] as? String)?.lowercase()
                io.github.shaonialife.buicklinkplus.audio.AudioPreference
                    .isMediaChannel(type)
            }
        val pool = mediaTypes
        // 外层按格式（48k → 44.1k），内层按车机声明顺序：同档位时 48k 自然排在前面
        listOf(AUDIO_FORMAT_PCM_48K_STEREO to 48_000, AUDIO_FORMAT_PCM_44K_STEREO to 44_100)
            .forEach { (bit, rate) ->
                pool.forEach { channel ->
                    val output = (channel["audioOutputFormats"] as? Number)?.toLong() ?: 0L
                    if ((output and bit) == 0L) return@forEach
                    val type = (channel["audioType"] as? String)?.lowercase() ?: return@forEach
                    pcm += AudioProfile(type, bit, rate, latencyFor(info, type, AUDIO_STREAM_TYPE))
                }
            }
        // 媒体档最优先；其余按 compatibility → default 的熟悉度排序（组内保持 48k 先）
        val priority = { type: String ->
            when {
                type == AUDIO_TYPE_MEDIA && !preferCompat -> 0
                type == "compatibility" && !preferCompat -> 1
                type == "compatibility" && preferCompat -> 0
                type == AUDIO_TYPE_MEDIA && preferCompat -> 2
                else -> 3
            }
        }
        // 「兼容档」= 强制走 PCM 旧通路（跳过 AAC）：AAC-LC 在某些车机上会被 SETUP 直接
        // 拒掉，留一个人工入口，免得自动候选每次都先撞一次墙。
        val ordered =
            (if (preferCompat) emptyList() else aac.distinctBy { it.key }) +
                pcm.sortedBy { priority(it.audioType) }
        if (ordered.isEmpty()) {
            log(
                "AIRPLAY: 车机未声明可用的 AAC-LC / PCM 立体声格式" +
                    "（共 ${declared.size} 档）→ 不尝试未声明或语音通道",
            )
            return emptyList()
        }
        log("AIRPLAY: 音频候选（按偏好）=${ordered.map { it.label }}")
        return ordered.distinctBy { it.key }
    }

    /** 读取当前档位的输出延迟，缺失时使用默认值 */
    private fun latencyFor(
        info: Map<*, *>,
        audioType: String,
        streamType: Long = AUDIO_STREAM_TYPE,
    ): Long {
        val declaredMs =
            (info["audioLatencies"] as? List<*>)
                ?.mapNotNull { it as? Map<*, *> }
                ?.firstOrNull {
                    ((it["type"] as? Number)?.toLong()) == streamType &&
                        (it["audioType"] as? String)?.lowercase() == audioType
                }?.let { (it["outputLatencyMicros"] as? Number)?.toLong() }
                ?.takeIf { it > 0 }
                ?.let { it / 1000 }
        // 车机上报的是自身输出延迟，缓冲至少留 250ms 来承受 WiFi 抖动
        return declaredMs?.coerceAtLeast(AUDIO_LATENCY_FALLBACK_MS) ?: AUDIO_LATENCY_FALLBACK_MS
    }

    /** 用 /info 中稳定的 model 和 manufacturer 生成音频档位记忆键 */
    fun receiverIdentity(): String {
        val info = negotiatedInfo ?: return "unknown"
        val manufacturer = (info["manufacturer"] as? String).orEmpty().trim()
        val model = (info["model"] as? String).orEmpty().trim()
        return "$manufacturer $model".trim().ifBlank { "unknown" }
    }

    /**
     * 协商 type-110 视频流，客户端指定连接编号，车机返回 TCP 端口
     * [sessionKey] 为空时使用 et=NONE 和全零会话密钥派生 AES-128-CTR 密钥
     */
    fun runScreenStream(
        sessionKey: ByteArray?,
        displayUuid: String? = null,
    ): ScreenStreamInfo? {
        // 对照 catplay（真车验证）：streamConnectionID = 随机 u64（+1 保证非零）
        val connectionId = java.security.SecureRandom().nextLong() and Long.MAX_VALUE or 1L
        val streamUuid =
            displayUuid ?: java.util.UUID
                .randomUUID()
                .toString()
        log(
            "AIRPLAY: 主屏流 SETUP uuid=$streamUuid " +
                if (displayUuid == null) {
                    "（⚠️ /info 未提供 displayUuid，只能用随机值——车机可能收下流却不绑到任何屏）"
                } else {
                    "（来自 /info displays[].uuid）"
                },
        )
        val body =
            BinaryPlist.encode(
                linkedMapOf(
                    "streams" to
                        listOf(
                            linkedMapOf<String, Any?>(
                                "type" to SCREEN_STREAM_TYPE, // 110 = 主屏（100 是音频，DiPlay 会开 UDP 端口）
                                "streamConnectionID" to connectionId,
                                // UUID 使用 /info 返回的显示屏标识，随机值可能被拒；latencyMs 沿用 25
                                "uuid" to streamUuid,
                                "latencyMs" to SCREEN_LATENCY_MS,
                            ),
                        ),
                ),
            )
        // 请求路径与 catplay 完全一致：流 SETUP 走 "/"（初始 SETUP 走 "/setup"）。
        // 部分接收端（Audi 固件）对路径敏感，路径不符会整包 400。
        val (statusLine, responseBody) =
            try {
                request(
                    "SETUP",
                    "/",
                    body,
                    contentType = "application/x-apple-binary-plist",
                )
            } catch (error: IOException) {
                log("AIRPLAY: 流协商失败 - ${error.message}")
                return null
            }
        if (!statusLine.contains("200")) {
            log(
                "AIRPLAY: 流协商应答 [$statusLine]（响应头：${lastResponseHeadOfRequest()}）\n" +
                    "  ↳ 请求体(${body.size}B)=" + body.joinToString("") { "%02x".format(it) },
            )
            return null
        }
        val root =
            try {
                BinaryPlist.parse(responseBody) as? Map<*, *>
            } catch (error: IllegalArgumentException) {
                null
            } ?: run {
                log("AIRPLAY: 流协商应答解析失败")
                return null
            }
        val streams =
            root["streams"] as? List<*> ?: run {
                log("AIRPLAY: 流协商应答缺少 streams")
                return null
            }
        val screen = streams.firstOrNull() as? Map<*, *>
        val dataPort = portOf(screen?.get("dataPort"))
        if (screen == null || dataPort == 0) {
            log("AIRPLAY: 主屏流被拒绝（streams=$streams）")
            return null
        }
        val cipher =
            if (sessionKey != null) {
                StreamFrameCipher.ChaCha(AirPlayStreamKeys.chachaStreamKey(sessionKey, connectionId))
            } else {
                val (key, iv) = AirPlayStreamKeys.aesStreamKeys(AirPlayStreamKeys.ZERO_SESSION_KEY, connectionId)
                StreamFrameCipher.AesCtr(key, iv)
            }
        log(
            "AIRPLAY: ✓ 主屏流协商成功 dataPort=$dataPort connId=$connectionId " +
                "加密=${if (cipher is StreamFrameCipher.ChaCha) "ChaCha20" else "AES-128-CTR（未配对会话）"}",
        )
        // MIB3：屏幕流 SETUP 200 拿到 dataPort 后，真机此时在控制通道发 detect（Frida
        // 时序：screen SETUP → phone detect on control → HU SYN on control）。见 armMib3VirtualLink。
        armMib3VirtualLink()
        return ScreenStreamInfo(connectionId, dataPort, cipher)
    }

    /** 发送 RECORD 激活会话，路径使用 / */
    fun record(): Boolean =
        try {
            val (statusLine, _) =
                request(
                    "RECORD",
                    "/",
                    BinaryPlist.encode(linkedMapOf<String, Any?>()),
                    contentType = "application/x-apple-binary-plist",
                )
            val ok = statusLine.contains("200")
            log(if (ok) "AIRPLAY: ✓ RECORD 完成——会话已激活" else "AIRPLAY: RECORD 应答 [$statusLine]")
            ok
        } catch (error: IOException) {
            log("AIRPLAY: RECORD 失败 - ${error.message}")
            false
        }

    /**
     * 连接 SETUP 返回的事件端口，重组 RTSP 消息并回调 [onCommand]
     * 有 [sessionKey] 时按接收端 Events-Read 方向加密，为 null 时使用明文
     */
    fun connectEventChannel(
        sessionKey: ByteArray?,
        setup: SetupInfo,
        onCommand: (Map<*, *>) -> Unit = {},
    ) {
        if (setup.eventPort == 0) {
            log("EVENT: SETUP 未提供 eventPort，跳过")
            return
        }
        val host = receiverHost() ?: return
        val sessionSocket = this.socket ?: return
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(resolveAddress(host), setup.eventPort), 5_000)
            socket.tcpNoDelay = true
            socket.soTimeout = 30_000
            log("EVENT: ✓ 已连接事件通道 $host:${setup.eventPort}（${if (sessionKey != null) "加密" else "明文"}）")
            val cipher =
                sessionKey?.let { secret ->
                    val phoneWriteKey =
                        AirPlayCrypto.hkdfSha512(
                            secret,
                            "Events-Salt".toByteArray(),
                            "Events-Read-Encryption-Key".toByteArray(),
                        )
                    val phoneReadKey =
                        AirPlayCrypto.hkdfSha512(
                            secret,
                            "Events-Salt".toByteArray(),
                            "Events-Write-Encryption-Key".toByteArray(),
                        )
                    ControlCipher(phoneReadKey, phoneWriteKey)
                }
            synchronized(rtspWriteLock) {
                if (!isSessionAlive(sessionSocket)) {
                    socket.close()
                    return
                }
                registerEventCommandChannel(socket, cipher, setup.hidDeviceUuids)
            }
            Thread({
                val accumulator = RtspAccumulator()
                var pending = ByteArray(0)
                val buffer = ByteArray(8192)
                try {
                    // 注册后可能立即退役，取输入流也必须在异常收尾范围内
                    val input = socket.getInputStream()
                    while (isSessionAlive(sessionSocket) && eventCommandSocket === socket) {
                        val count =
                            try {
                                input.read(buffer)
                            } catch (_: java.net.SocketTimeoutException) {
                                // 事件通道长时间空闲很常见，超时只唤醒，不关闭连接
                                continue
                            }
                        if (count < 0) {
                            log("EVENT: 通道关闭（EOF）")
                            break
                        }
                        if (!isSessionAlive(sessionSocket) || eventCommandSocket !== socket) break
                        pending += buffer.copyOf(count)
                        val plain =
                            if (cipher == null) {
                                val all = pending
                                pending = ByteArray(0)
                                all
                            } else {
                                val decrypted = cipher.decrypt(pending)
                                pending = decrypted.rest
                                decrypted.data
                            }
                        var eventOtherLogged = 0
                        for (message in accumulator.offer(plain)) {
                            // 旧事件可能已经解码完，不能回应或派发到重连后的会话
                            if (!isSessionAlive(sessionSocket) || eventCommandSocket !== socket) return@Thread
                            val firstLine = message.firstLine
                            // (a) 状态行（"RTSP/1.0 200 OK" 等）= 车机对**我们命令**的应答：
                            //     静默跳过——不回、不记录（回应答会触发乒乓回声，见 respondEventRequest）
                            if (firstLine.startsWith("RTSP/") || firstLine.startsWith("HTTP/")) {
                                val code = Regex("[0-9]{3}").find(firstLine)?.value ?: "unknown"
                                val responseCseq = CSEQ_REGEX.find(message.head)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                                ProtocolTrace.event("EVENT-RX-ACK", "cseq=$responseCseq status=$code bytes=${message.body.size}")
                                continue
                            }
                            // (b) 请求判定：方法 token + "/"-开头路径。畸形行（如 "OK 200 OK"：
                            //     第 2 token 是数字）判为非请求 → 静默丢弃，不回不记
                            val tokens = firstLine.split(" ")
                            val isRequest = tokens.size >= 2 && tokens[1].startsWith("/")
                            if (!isRequest) continue
                            // 车机请求：立即回 200（echo 请求 CSeq）；/command 走分发，其余节流留痕
                            val cseq = CSEQ_REGEX.find(message.head)?.groupValues?.get(1)
                            val localEventId = inboundEventSerial.incrementAndGet()
                            ProtocolTrace.rtsp("EVENT-RX", tokens.first(), tokens[1], cseq?.toIntOrNull() ?: -1, message.body.size)
                            ProtocolTrace.event("EVENT-RX-ID", ProtocolTraceFormat.eventCorrelation(localEventId, cseq?.toIntOrNull() ?: -1, message.body.size))
                            respondEventRequest(cseq, localEventId)
                            if (!firstLine.contains("/command")) {
                                unknownEventRequests++
                                if (unknownEventRequests <= 10) {
                                    log("EVENT: 未知请求 [$firstLine] body=${message.body.size}B")
                                }
                                continue
                            }
                            var commandError: String? = null
                            val parsed =
                                try {
                                    val result = BinaryPlist.parseRecovered(message.body)
                                    result.note?.let { note ->
                                        log("EVENT: /command 容错解析成功——$note")
                                    }
                                    result.value
                                } catch (error: Throwable) {
                                    // 不能只 catch IllegalArgumentException：越界/长度异常若逃出
                                    // 这里会直接打死事件读线程，车机会把整个会话拆掉。
                                    commandError = "${error.javaClass.simpleName}: ${error.message}"
                                    null
                                }
                            val command = parsed as? Map<*, *>
                            if (command == null) {
                                // 车机每秒都会 POST 一条解不出来的 /command——按「体型指纹」
                                // 去重，每种体型留一次全文（原因 + 报文头 + 完整 hex），其余只计数。
                                badEventCommands++
                                val signature =
                                    message.body.size.toString() + ":" + hexPrefix(message.body, 8)
                                if (badEventSignatures.add(signature) && badEventSignatures.size <= 4) {
                                    log(
                                        "EVENT: 命令无法识别 [$firstLine] body=${message.body.size}B " +
                                            "原因=" + (commandError ?: "解析结果=${parsed?.javaClass?.simpleName}"),
                                    )
                                    log(
                                        "EVENT: 命令报文头=" +
                                            message.head.replace("\r\n", " | ").take(300),
                                    )
                                    log("EVENT: 命令报文体（${message.body.size}B）hex=" + hexDump(message.body))
                                }
                                if (badEventCommands % 120 == 0) {
                                    log("EVENT: 累计 $badEventCommands 条 /command 解析失败（已静默，仅计数）")
                                }
                                continue
                            }
                            dispatchEventCommand(command, sessionSocket, socket, onCommand)
                        }
                    }
                } catch (error: IOException) {
                    log("EVENT: 读取结束 - ${error.message}")
                } finally {
                    runCatching { socket.close() }
                    synchronized(rtspWriteLock) {
                        if (eventCommandSocket === socket) closeSession(sessionSocket)
                    }
                }
            }, "event-reader").apply {
                isDaemon = true
                start()
            }
        } catch (error: IOException) {
            log("EVENT: 连接失败 - ${error.message}")
        }
    }

    /** 流式 RTSP 报文重组器：喂入任意分片明文，弹出完整消息（首行 + body）。 */
    private class RtspAccumulator {
        /** 完整报文：首行（分类用）、头部全文（提取 CSeq）、body。 */
        class Message(
            val firstLine: String,
            val head: String,
            val body: ByteArray,
        )

        private var buffer = ByteArray(0)

        fun offer(bytes: ByteArray): List<Message> {
            if (bytes.isEmpty()) return emptyList()
            buffer += bytes
            val messages = ArrayList<Message>()
            while (true) {
                val index = indexOf(buffer, HEAD_SEPARATOR)
                if (index < 0) break
                val headText = String(buffer, 0, index, Charsets.ISO_8859_1)
                val (firstLine, contentLength) = parseRtspHead(headText)
                val bodyStart = index + HEAD_SEPARATOR.size
                if (buffer.size - bodyStart < contentLength) break
                messages +=
                    Message(
                        firstLine,
                        headText,
                        buffer.copyOfRange(bodyStart, bodyStart + contentLength),
                    )
                buffer = buffer.copyOfRange(bodyStart + contentLength, buffer.size)
            }
            return messages
        }
    }

    /** GET /info 携带空 bplist 字典，Content-Type 使用 application/x-apple-binary-plist */
    private fun requestInfo(
        timeoutMillis: Int = 10_000,
        expectedSocket: Socket? = null,
    ): Pair<String, ByteArray> =
        request(
            "GET",
            "/info",
            BinaryPlist.encode(linkedMapOf<String, Any?>()),
            timeoutMillis,
            contentType = "application/x-apple-binary-plist",
            expectedSocket = expectedSocket,
        )

    // ---- RTSP 请求 ----

    private fun ensureConnected(timeoutMillis: Int = 5_000): Boolean {
        if (socket?.isConnected == true && socket?.isClosed == false) return true
        // 快照端点与代数：连接期间主控可能换端点（0x4301 晚到），
        // 若用旧地址建好连接再被请求，会一直连错机器到本轮超时。
        val target = endpoint
        val generation = endpointGeneration
        val candidates = EndpointHosts.candidates(target.ipAddresses)
        if (candidates.isEmpty()) {
            log("AIRPLAY: 0x4301/ mDNS 未提供可用地址，无法连接")
            return false
        }
        if (target.airPlayPort <= 0) {
            log("AIRPLAY: 端口未知（${target.airPlayPort}），无法连接")
            return false
        }
        val failures = ArrayList<String>(candidates.size)
        for (host in candidates) {
            val address =
                try {
                    resolveAddress(host)
                } catch (error: Exception) {
                    if (error is CarNetworkUnavailable) {
                        onNetworkUnavailable?.invoke()
                        throw error
                    }
                    failures += "$host（解析失败：${error.message}）"
                    continue
                }
            val newSocket = Socket()
            try {
                carNetwork?.bindSocket(newSocket)
                newSocket.connect(InetSocketAddress(address, target.airPlayPort), remainingOperationMillis(timeoutMillis))
                if (generation != endpointGeneration) {
                    // 连接途中端点已更新：丢弃这条旧连接，下一轮按新地址重连
                    runCatching { newSocket.close() }
                    return false
                }
                // 读由独立读线程负责（阻塞等数据），请求超时由待响应表的 poll 控制；
                // 这里若留 soTimeout，读线程会周期性收到 SocketTimeoutException 而退出
                newSocket.soTimeout = 0
                synchronized(rtspWriteLock) {
                    if (generation != endpointGeneration || cancelled) {
                        runCatching { newSocket.close() }
                        return false
                    }
                    if (socket?.isConnected == true && socket?.isClosed == false) {
                        runCatching { newSocket.close() }
                        return true
                    }
                    io.github.shaonialife.buicklinkplus.ui.UiAppearance
                        .beginReceiver(newSocket)
                    socket = newSocket
                    receiverVolumePolicy = ReceiverVolumePolicy()
                    termination = SessionTermination()
                    activeAddress = address
                    activeHost = addressLiteral(address)
                }
                log("AIRPLAY: TCP 已连通 $activeHost:${target.airPlayPort}（本机 ${newSocket.localAddress.hostAddress}）")
                return true
            } catch (error: IOException) {
                runCatching { newSocket.close() }
                if (error.message?.contains("ENONET") == true) {
                    onNetworkUnavailable?.invoke()
                    throw IOException("车机绑定网络已失效，需要重新入网", error)
                }
                failures += "$host（${error.message}）"
            }
        }
        log(
            "AIRPLAY: 连接失败 - ${failures.joinToString("；")}\n" +
                "  ↳ 若反复失败：确认手机当前连的是接收端热点 \"${target.ssid}\"，" +
                "而不是家里/公司 WiFi（绑定到的网络不对时到达不了车机网段）",
        )
        return false
    }

    /**
     * 网络确认失效与普通地址解析失败分别处理，交给会话层重新入网
     */
    private class CarNetworkUnavailable(
        message: String,
    ) : IOException(message)

    private fun resolveAddress(host: String): InetAddress {
        if (carNetwork != null && EndpointHosts.isLinkLocalIpv6(host)) {
            val manager = context?.getSystemService(ConnectivityManager::class.java)
            checkNotNull(manager) { "网络管理服务不可用" }
            val changed = java.util.concurrent.Semaphore(0)
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onLinkPropertiesChanged(
                        network: android.net.Network,
                        properties: android.net.LinkProperties,
                    ) {
                        if (network == carNetwork) changed.release()
                    }

                    override fun onLost(network: android.net.Network) {
                        if (network == carNetwork) changed.release()
                    }
                }
            val request =
                android.net.NetworkRequest
                    .Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
            manager.registerNetworkCallback(request, callback)
            val iface =
                try {
                    val deadline = android.os.SystemClock.uptimeMillis() + 4_000
                    var found: NetworkInterface? = null
                    var ready = false
                    while (!cancelled && android.os.SystemClock.uptimeMillis() < deadline) {
                        if (!manager.existingNetworks().contains(carNetwork)) throw CarNetworkUnavailable("车机网络已移除，需要重新入网")
                        val properties = manager.getLinkProperties(carNetwork)
                        found = properties?.interfaceName?.let { NetworkInterface.getByName(it) }
                        if (found != null && properties?.linkAddresses?.isNotEmpty() == true) {
                            ready = true
                            break
                        }
                        changed.tryAcquire(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                    }
                    if (cancelled) throw IOException("连接已取消")
                    if (!ready) throw IOException("车机网络属性尚未就绪，等待后续网络变化重试")
                    checkNotNull(found)
                } finally {
                    runCatching { manager.unregisterNetworkCallback(callback) }
                }
            val address = InetAddress.getByName(EndpointHosts.stripScope(host))
            return Inet6Address.getByAddress(null, address.address, iface)
        }
        val address = InetAddress.getByName(host)
        if (address is Inet6Address && address.isLinkLocalAddress && address.scopeId == 0) {
            throw IOException("链路本地地址缺少车机网络作用域")
        }
        return address
    }

    /** 将 IPv6 链路本地地址转成带数字 scope id 的文本，兼容不同 ROM 的解析 */
    private fun addressLiteral(address: InetAddress): String {
        val text = address.hostAddress ?: return address.toString()
        if (address is Inet6Address && address.scopeId != 0) {
            return "${text.substringBefore('%')}%${address.scopeId}"
        }
        return text
    }

    private fun post(
        path: String,
        body: ByteArray,
    ): ByteArray {
        val (statusLine, responseBody) = request("POST", path, body)
        if (!statusLine.contains("200")) {
            throw IOException("POST $path 应答 [$statusLine]")
        }
        return responseBody
    }

    /** 配对请求头参考 catplay PairingHelperTx：pairing+tlv8、X-Apple-HKP=2，verify 另带 X-Apple-PD=1 */
    private fun postPairing(
        path: String,
        body: ByteArray,
    ): ByteArray {
        val extra =
            buildList {
                add("X-Apple-HKP" to "2")
                if (path.endsWith("pair-verify")) add("X-Apple-PD" to "1")
            }
        val (statusLine, responseBody) =
            request(
                "POST",
                path,
                body,
                contentType = "application/pairing+tlv8",
                extraHeaders = extra,
            )
        // 诊断（真车 403 排查）：请求长度 + 应答状态行/长度/头部字节
        val headHex = responseBody.take(16).joinToString(" ") { "%02x".format(it) }
        log(
            "AIRPLAY: $path req=${body.size}B -> [$statusLine] resp=${responseBody.size}B" +
                if (responseBody.isNotEmpty()) " head=[$headHex]" else "",
        )
        if (!statusLine.contains("200")) {
            throw IOException("POST $path 应答 [$statusLine]")
        }
        return responseBody
    }

    private fun request(
        method: String,
        path: String,
        body: ByteArray,
        timeoutMillis: Int = 10_000,
        contentType: String = "application/octet-stream",
        extraHeaders: List<Pair<String, String>> = emptyList(),
        expectedSocket: Socket? = null,
        expectedIapLink: VirtualIap2Link? = null,
        expectedIapGeneration: Int? = null,
    ): Pair<String, ByteArray> {
        val client = socket ?: throw IOException("未连接")
        val startedAt = nowMillis()
        val queue = java.util.concurrent.LinkedBlockingQueue<Pair<String, ByteArray>>(1)
        val cseq: Int
        synchronized(rtspWriteLock) {
            if (socket !== client || client.isClosed || (expectedSocket != null && expectedSocket !== client)) {
                throw IOException("请求所属的控制连接已退役")
            }
            if (expectedIapGeneration != null &&
                (iapLink !== expectedIapLink || expectedIapLink?.controlGeneration != expectedIapGeneration)
            ) {
                throw IOException("请求所属的 iAP2 控制会话已更新，丢弃旧代次数据")
            }
            remainingOperationMillis(timeoutMillis)
            startReader(client)
            cseq = ++cSeq
            // 先登记再写：响应可能在 write 返回前就到达
            pendingResponses[cseq] = queue
            try {
                val head =
                    "$method $path RTSP/1.0\r\n" +
                        "CSeq: $cseq\r\n" +
                        "User-Agent: AirPlay/$SENDER_SOURCE_VERSION\r\n" +
                        "Content-Type: $contentType\r\n" +
                        extraHeaders.joinToString("") { "${it.first}: ${it.second}\r\n" } +
                        "Content-Length: ${body.size}\r\n" +
                        "\r\n"
                val plaintext = head.toByteArray(Charsets.US_ASCII) + body
                val encryption = cipher
                val wire =
                    if (encryption != null) {
                        synchronized(encryption) { encryption.encrypt(plaintext) }
                    } else {
                        plaintext
                    }
                ProtocolTrace.rtsp("TX", method, path, cseq, body.size)
                val output = client.getOutputStream()
                output.write(wire)
                output.flush()
            } catch (error: Throwable) {
                pendingResponses.remove(cseq)
                throw error
            }
        }
        val response =
            try {
                queue.poll(remainingOperationMillis(timeoutMillis).toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    ?: throw IOException("RTSP 超时（${timeoutMillis}ms，CSeq=$cseq $method $path）")
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("RTSP 被中断（CSeq=$cseq $method $path）")
            } finally {
                pendingResponses.remove(cseq)
            }
        // 记录超过 1.5 秒的慢应答，方便排查车机资源等待
        val elapsed = nowMillis() - startedAt
        if (elapsed > 1_500) {
            log("AIRPLAY: 慢应答 ${elapsed}ms（$method $path，CSeq=$cseq）——等待期间心跳未中断")
        }
        if (response.first == RTSP_CLOSED_MARK) {
            throw IOException("控制通道已关闭（CSeq=$cseq $method $path）")
        }
        ProtocolTrace.rtsp("RX", method, path, cseq, response.second.size, response.first, elapsed)
        lastRequestCSeq.set(cseq)
        return response
    }

    /** 控制通道统一收包解密，按 CSeq 派发响应；长请求不阻塞 /feedback 心跳 */

    /** 启用加密时丢弃明文缓冲，避免残留字节破坏密文帧边界 */
    private fun readLoop(
        client: Socket,
        generation: Int,
    ) {
        var cipherPending = ByteArray(0)
        var buffer = ByteArray(0)
        var encryptedMode = false
        try {
            val input = client.getInputStream()
            val chunk = ByteArray(8192)
            while (isSessionAlive(client) && generation == readerGeneration) {
                val count = input.read(chunk)
                if (count < 0) break
                val encryption =
                    synchronized(rtspWriteLock) {
                        if (!isSessionAlive(client) || generation != readerGeneration) return
                        cipher
                    }
                if (encryption != null && !encryptedMode) {
                    // 切换点：清掉明文侧残留，之后一律走帧解密
                    encryptedMode = true
                    cipherPending = ByteArray(0)
                }
                val data =
                    if (encryption == null) {
                        chunk.copyOf(count)
                    } else {
                        cipherPending += chunk.copyOf(count)
                        val decrypted =
                            try {
                                synchronized(encryption) { encryption.decrypt(cipherPending) }
                            } catch (error: Exception) {
                                log("AIRPLAY: 控制通道解密失败 - ${error.message}")
                                break
                            }
                        cipherPending = decrypted.rest
                        decrypted.data
                    }
                if (data.isEmpty()) continue
                buffer += data
                while (true) {
                    val parsed = parseRtspMessage(buffer) ?: break
                    buffer = parsed.rest
                    synchronized(rtspWriteLock) {
                        if (!isSessionAlive(client) || generation != readerGeneration) return
                        dispatchResponse(parsed.statusLine, parsed.body, parsed.head)
                    }
                }
            }
        } catch (_: IOException) {
        } finally {
            // 只由「当前」读线程清账：重连会换 socket 与新读线程，旧线程不得误伤新会话
            synchronized(rtspWriteLock) {
                if (generation == readerGeneration && socket === client) {
                    termination.record("控制通道EOF或读取失败", nowMillis())
                    log("AIRPLAY: 控制通道读线程结束——退役本轮连接及虚拟链路")
                    closeSession(client)
                }
            }
        }
    }

    /** 控制通道报文（CSeq 派发用；head 供诊断与请求/应答分类）。 */
    private class RtspMessage(
        val statusLine: String,
        val body: ByteArray,
        val head: String,
    ) {
        val cseq: Int? get() =
            CSEQ_REGEX
                .find(head)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()

        /** 区分车机主动请求和 RTSP 响应 */
        val isRequest: Boolean
            get() {
                val tokens = statusLine.split(" ")
                return tokens.size >= 2 && tokens[1].startsWith("/")
            }
    }

    private fun dispatchResponse(
        statusLine: String,
        body: ByteArray,
        head: String,
    ) {
        val message = RtspMessage(statusLine, body, head)
        if (message.isRequest) {
            val tokens = statusLine.split(" ")
            ProtocolTrace.rtsp("RX-REQUEST", tokens.firstOrNull() ?: "", tokens.getOrNull(1) ?: "", message.cseq ?: -1, body.size)
        } else {
            ProtocolTrace.rtsp("RX-WIRE", "REPLY", "/", message.cseq ?: -1, body.size, statusLine)
        }
        message.cseq?.let { responseHeads[it] = head }
        val queue = message.cseq?.let { pendingResponses[it] }
        if (queue != null) {
            queue.offer(message.statusLine to message.body)
            return
        }
        // 无匹配：车机可能在控制通道上发自己的请求（须回 200 否则它会拆通道）。
        // 仅节流留痕，不静默丢弃——此前同步实现会把它错当成前一个请求的应答。
        unmatchedMessages++
        if (unmatchedMessages <= 5) {
            log(
                "AIRPLAY: 控制通道无匹配报文（#$unmatchedMessages）" +
                    "[$statusLine] cseq=${message.cseq} request=${message.isRequest} body=${body.size}B",
            )
        }
        if (message.isRequest) {
            // 复用请求 CSeq 回空应答（与事件通道同一约定）
            runCatching {
                val payload =
                    "RTSP/1.0 200 OK\r\nContent-Length: 0\r\n" +
                        (message.cseq?.let { "CSeq: $it\r\n" } ?: "") + "\r\n"
                val encryption = cipher
                synchronized(rtspWriteLock) {
                    val out = socket?.getOutputStream() ?: return@runCatching
                    out.write(
                        if (encryption != null) {
                            synchronized(encryption) { encryption.encrypt(payload.toByteArray(Charsets.US_ASCII)) }
                        } else {
                            payload.toByteArray(Charsets.US_ASCII)
                        },
                    )
                    out.flush()
                }
            }
        }
    }

    /** 无匹配报文计数（节流日志用）。 */
    private var unmatchedMessages = 0

    private fun failAllPending(reason: String) {
        val waiting = pendingResponses.values.toList()
        pendingResponses.clear()
        if (waiting.isEmpty()) return
        log("AIRPLAY: $reason——唤醒 ${waiting.size} 个待响应请求")
        waiting.forEach { it.offer(RTSP_CLOSED_MARK to ByteArray(0)) }
    }

    private fun startReader(client: Socket) {
        val previous: Socket?
        synchronized(rtspWriteLock) {
            if (readerSocket === client && readerThread?.isAlive == true) return
            previous = readerSocket
            readerSocket = client
            val generation = ++readerGeneration
            readerThread =
                Thread({ readLoop(client, generation) }, "airplay-rtsp-reader").apply {
                    isDaemon = true
                    start()
                }
        }
        // 旧 socket 由本线程持有其输入流：必须关掉（会抛 SocketException 让旧读线程退出），
        // 否则两个读线程可能阻塞在同一连接上抢包——异步派发后这会导致响应错投
        if (previous != null && previous !== client) {
            runCatching { previous.close() }
        }
    }

    /** 按 CSeq 等待的响应队列，长请求不阻塞心跳 */
    private val pendingResponses =
        java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.LinkedBlockingQueue<Pair<String, ByteArray>>>()

    /** 串行分配 CSeq、更新加密计数和写 socket */
    private val rtspWriteLock = Any()

    private var readerThread: Thread? = null
    private var readerSocket: Socket? = null

    /** 读线程代次，旧线程不清理新会话的响应队列 */
    private var readerGeneration = 0

    /** 按 CSeq 保存响应头，避免并发请求错配 */
    private val responseHeads = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** 本线程最近一次 request() 的 CSeq（诊断取响应头用；ThreadLocal 避免并发错配）。 */
    private val lastRequestCSeq = ThreadLocal<Int>()

    /** 取本线程最近一次请求的响应头（诊断用；读不到返回空串）。 */
    private fun lastResponseHeadOfRequest(): String =
        lastRequestCSeq
            .get()
            ?.let { responseHeads[it] }
            ?.replace("\r\n", " | ")
            .orEmpty()

    /** 解析结果（报文 + 未消费的剩余字节）。 */
    private class ParsedRtsp(
        val statusLine: String,
        val body: ByteArray,
        val head: String,
        val rest: ByteArray,
    )

    /** 在控制通道读线程中，按头和 Content-Length 切出完整报文 */
    private fun parseRtspMessage(buffer: ByteArray): ParsedRtsp? {
        val separatorIndex = indexOf(buffer, HEAD_SEPARATOR)
        if (separatorIndex < 0) return null
        val headText = String(buffer, 0, separatorIndex, Charsets.ISO_8859_1)
        val (statusLine, contentLength) = parseRtspHead(headText)
        val bodyStart = separatorIndex + HEAD_SEPARATOR.size
        if (buffer.size - bodyStart < contentLength) return null
        val responseBody = buffer.copyOfRange(bodyStart, bodyStart + contentLength)
        val rest =
            if (buffer.size > bodyStart + contentLength) {
                buffer.copyOfRange(bodyStart + contentLength, buffer.size)
            } else {
                ByteArray(0)
            }
        return ParsedRtsp(statusLine, responseBody, headText, rest)
    }

    // ---- 身份持久化 ----

    private fun loadOrCreateIdentity(): ControllerIdentity {
        val preferences = context?.getSharedPreferences("airplay_identity", Context.MODE_PRIVATE)
        val storedId = preferences?.getString("pairingId", null)
        val storedPrivate = preferences?.getString("privateKey", null)
        if (storedId != null && storedPrivate != null) {
            val privateKey = Base64.getDecoder().decode(storedPrivate)
            val publicKey = AirPlayCrypto.ed25519PublicKey(privateKey)
            return ControllerIdentity(storedId, privateKey, publicKey)
        }
        val keyPair = AirPlayCrypto.ed25519Generate()
        val pairingId = UUID.randomUUID().toString()
        preferences
            ?.edit {
                putString("pairingId", pairingId)
                putString("privateKey", Base64.getEncoder().encodeToString(keyPair.privateKey))
            }
        return ControllerIdentity(pairingId, keyPair.privateKey, keyPair.publicKey)
    }

    // ---- 工具 ----

    /** 事件、隧道和音视频通道共用配对时连通的接收端地址 */
    private fun receiverHost(): String? = activeHost ?: EndpointHosts.candidates(endpoint.ipAddresses).firstOrNull()

    /** SETUP 使用本机蓝牙 MAC 作为 deviceID，优先读缓存，缺失时再阻塞查询 */
    private fun setupDeviceId(): String {
        // 缓存命中即返回（BT 阶段已解析）；缺失时才允许一次阻塞查询。
        DeviceIdentity.cachedBluetoothMac()?.let { return it }
        val applicationContext = context ?: return DeviceIdentity.PLACEHOLDER_MAC
        return DeviceIdentity.bluetoothMac(applicationContext)
    }

    /** 构造会话 SETUP 请求体，deviceID 使用本机蓝牙地址 */
    internal fun buildSessionSetupBody(
        deviceId: String,
        timingPort: Int,
        /** 未配对会话显式携带 et=NONE */
        encrypted: Boolean = true,
    ): ByteArray {
        val fields =
            linkedMapOf<String, Any?>(
                "deviceID" to deviceId,
                "macAddress" to "aa:bb:cc:dd:ee:ff",
                "name" to "iPhone",
                "model" to "iPhone14,2",
                "osName" to "iPhone OS",
                "osBuildVersion" to "19A404",
                "osVersion" to "15.0.2",
                // iPhone 自己的 AirPlay 版本（catplay 实证值）；此前误用车机的 320.17.7
                "sourceVersion" to SENDER_SOURCE_VERSION,
                "timingPort" to timingPort.toLong(),
                "sessionUUID" to
                    java.util.UUID
                        .randomUUID()
                        .toString()
                        .uppercase(),
                "sessionCorrelationUUID" to
                    java.util.UUID
                        .randomUUID()
                        .toString()
                        .uppercase(),
                "statsCollectionEnabled" to false,
                "updateSessionRequest" to false,
                "keepAliveLowPower" to true,
                "features" to CONTROLLER_FEATURES,
            )
        if (!encrypted) {
            // AirPlayEncryptionType::NONE = 1<<0（catplay 未走 /pair-setup 时的降级值）。
            // 缺这个键时严格接收端可能把会话当成「加密但无密钥」而拒绝。
            fields["et"] = 1L
        }
        return BinaryPlist.encode(fields)
    }

    private fun typeName(value: Any?): String =
        when (value) {
            null -> "null"
            is ByteArray -> "data(${value.size}B)"
            is List<*> -> "list(${value.size})"
            is Map<*, *> -> "dict(${value.size})"
            is String -> "str(${value.length})"
            else -> value.javaClass.simpleName
        }

    /** 把字符串、16 字节 UUID 和归档 UID 转成接收端声明的屏幕标识 */
    private fun uuidText(value: Any?): String? =
        when (value) {
            null -> null
            is String -> value.takeIf { it.isNotBlank() }
            is ByteArray ->
                if (value.size == 16) {
                    val hex = value.joinToString("") { "%02x".format(it) }
                    "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                        "${hex.substring(16, 20)}-${hex.substring(20)}"
                } else {
                    value.joinToString("") { "%02x".format(it) }
                }
            is Number -> value.toString()
            else -> value.toString().takeIf { it.isNotBlank() }
        }

    private fun logInfoSummary(root: Map<*, *>) {
        // 顶层字段全量清单（键: 类型）——车机把能力藏在哪个键下一目了然，避免只认得
        // Apple 官方那几个键（宝马 iD9 的 HID 声明可能就在我们没解析的键上）
        log(
            "AIRPLAY: /info 顶层字段(${root.size})=" +
                root.entries.joinToString(" ") { (key, value) -> "$key:${typeName(value)}" },
        )
        // 任何名字里带 hid 的键都完整打印（设备清单 + 描述符是触摸通路的前提）
        root.entries
            .filter { (key, _) -> key.toString().contains("hid", ignoreCase = true) }
            .forEach { (key, value) -> log("AIRPLAY: /info $key=$value") }
        val features = root["features"] as? Long
        log(
            "AIRPLAY: /info 摘要 name=${root["name"]} model=${root["model"]} " +
                "deviceID=${root["deviceID"]}" +
                if (features != null) " features=0x${features.toString(16)}" else "",
        )
        (root["displays"] as? List<*>)?.forEachIndexed { index, display ->
            log("AIRPLAY: /info displays[$index]=$display")
            if (display is Map<*, *>) {
                log(
                    "AIRPLAY: /info displays[$index] 键值=" +
                        display.entries.joinToString(" ") { (key, value) ->
                            "$key=${value.toString().take(80)}"
                        },
                )
            }
        }
        // 音频能力逐条打印（整行 /info 的日志会被截断，audioFormats 恰好落在截断处）：
        // 各车机为 type=100 声明的 audioType 档位与位图差异很大，协商依赖这些明细
        (root["audioFormats"] as? List<*>)?.forEachIndexed { index, entry ->
            val format = entry as? Map<*, *> ?: return@forEachIndexed
            val output = (format["audioOutputFormats"] as? Number)?.toLong() ?: 0L
            val input = (format["audioInputFormats"] as? Number)?.toLong() ?: 0L
            log(
                "AIRPLAY: audioFormat[$index] type=${format["type"]} audioType=${format["audioType"]} " +
                    "output=0x${output.toString(16)} input=0x${input.toString(16)}" +
                    "（PCM48k立体声 bit15=${if ((output and (1L shl 15)) != 0L) "✓" else "✗"}）",
            )
        }
        (root["audioLatencies"] as? List<*>)?.forEachIndexed { index, entry ->
            val latency = entry as? Map<*, *> ?: return@forEachIndexed
            log(
                "AIRPLAY: audioLatency[$index] type=${latency["type"]} audioType=${latency["audioType"]} " +
                    "in=${latency["inputLatencyMicros"]} out=${latency["outputLatencyMicros"]}",
            )
        }
    }

    private fun hexToBytes(hex: String): ByteArray? {
        val cleaned = hex.trim()
        if (cleaned.length % 2 != 0) return null
        return try {
            ByteArray(cleaned.length / 2) { index ->
                cleaned.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}

/** 403 时放慢配对重试，等待车机就绪；其余失败保持快速重试 */
internal fun pairingRetryBackoffMs(failureMessage: String): Long = if (failureMessage.contains("403")) 5_000L else 500L
