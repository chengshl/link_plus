package io.github.shaonialife.buicklinkplus.transport

import io.github.shaonialife.buicklinkplus.iap2.Iap2LinkEngine
import io.github.shaonialife.buicklinkplus.util.ProtocolTrace
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 用读线程和节拍线程驱动 [Iap2LinkEngine]，提供阻塞收发和就绪等待 */
class Iap2LinkChannel(
    private val stream: DuplexStream,
    name: String = "iap2-link",
    allowPeerRestart: Boolean = false,
    private val respondToPeerHandshake: Boolean = false,
    private val onReady: () -> Unit = {},
    private val log: (String) -> Unit = {},
) : Closeable {
    private val engine =
        Iap2LinkEngine(
            maxPeerRestarts = if (allowPeerRestart) MAX_PEER_RESTARTS else 0,
            waitForPeerAcknowledgement = allowPeerRestart,
        )

    // 此监视器需要 wait/notify，保留 Java Object 的显式接口
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()
    private val controls = LinkedBlockingQueue<ByteArray?>()

    /** 链路恢复后重新创建识别、认证和分帧状态 */
    class PeerRestartedException : IOException("车机已重新同步，旧蓝牙控制会话已失效")

    private var generation = 0
    val sessionGeneration: Int get() = synchronized(lock) { generation }
    private var pendingDetectReply = false
    private var pendingInitialDetectReply = false
    private var detectReplyCount = 0
    private var lastDetectReplyAt: Long? = null

    @Volatile
    private var terminated = false

    /** 每个方向的原始字节记录上限 */
    private var rxDumpBudget = RAW_DUMP_LIMIT
    private var txDumpBudget = RAW_DUMP_LIMIT_TX

    /** 逐帧摘要日志预算（每方向每连接）。 */
    private var rxFrameLogs = 0
    private var txFrameLogs = 0
    private var receivedBytes = 0L
    private var silentDeadlineExpired = false
    private var endDescription: String? = null
    private var readyNotified = false

    @Volatile
    private var localCloseRequested = false

    @Volatile
    private var remoteTransportEnded = false

    /** 分别记录无接收、EOF 和取消等失败原因 */
    fun failureDescription(stage: String): String =
        synchronized(lock) {
            val reason =
                when {
                    Thread.currentThread().isInterrupted -> "已取消"
                    terminated -> endDescription ?: "链路已结束"
                    receivedBytes == 0L -> "截止前未收到任何数据"
                    else -> "等待超时"
                }
            "$stage：$reason 状态=${engine.state} 接收字节=$receivedBytes 代次=$generation"
        }

    val isAlive: Boolean get() = !terminated

    /** 只对传输 EOF 或读写异常临时换档，本地关闭和协议超时不计入 */
    val canRetryMfiAfterDisconnect: Boolean get() = terminated && remoteTransportEnded && !localCloseRequested

    /** 仅初始协商无接收超时可触发兼容切换 */
    val silentNegotiationTimedOut: Boolean
        get() = synchronized(lock) { silentDeadlineExpired && !terminated && receivedBytes == 0L && generation == 0 }

    init {
        require(!respondToPeerHandshake || allowPeerRestart) { "应答握手必须启用蓝牙探测和最终确认处理" }
        if (allowPeerRestart) {
            engine.onPeerRestartNotice = { message -> log("BT-LINK: $message") }
            engine.onPeerDetect = { newProbe ->
                if (respondToPeerHandshake && engine.state == Iap2LinkEngine.State.NEGOTIATING && engine.peerSynchronization == null) {
                    // 手机应答形态先回完整探测，随后由车机发 SYN；不主动循环重发探测或同步包
                    pendingInitialDetectReply = true
                }
                // 正常模式保留原有主动协商；已建链后的探测应答不主动循环打点
                if (engine.state == Iap2LinkEngine.State.NORMAL) {
                    if (newProbe) {
                        detectReplyCount = 0
                        lastDetectReplyAt = null
                    }
                    val now = nowMillis()
                    val lastReply = lastDetectReplyAt
                    if (detectReplyCount < MAX_DETECT_REPLIES && (lastReply == null || now - lastReply >= DETECT_REPLY_INTERVAL_MILLIS)) {
                        detectReplyCount++
                        lastDetectReplyAt = now
                        pendingDetectReply = true
                        log("BT-LINK: 完整 DETECT，应答 $detectReplyCount/$MAX_DETECT_REPLIES，等待有效新 SYN")
                    }
                }
            }
            engine.onPeerRestarted = { count ->
                generation++
                // 仅有效新 SYN 才到这里；探测本身不能清认证、半帧或业务队列
                controls.clear()
                controls.offer(RESTART)
                pendingDetectReply = false
                detectReplyCount = 0
                lastDetectReplyAt = null
                lock.notifyAll()
                log("BT-LINK: 采纳重新同步 $count/$MAX_PEER_RESTARTS，代次=$generation，清理旧控制消息")
            }
        }
        engine.onPeerSynchronizationAdopted = { sync ->
            log(
                "LINK: 采纳对端会话表 maxOutgoing=${sync.maxOutgoing} maxLen=${sync.maxLength} " +
                    "sessions=" + sync.sessions.joinToString { "${it.id}/k${it.kind}/v${it.version}" },
            )
        }
        engine.onUnknownSessionData = { sessionId, size ->
            log("LINK: 丢弃未知会话 $sessionId 的数据帧（$size 字节）")
        }
        engine.onFrameReceived = { control, sequence, ack, sessionId, size ->
            ProtocolTrace.linkFrame("RX", control, sequence, ack, sessionId, size)
            // 真车调试关键观测：车机在识别阶段是否回帧、回什么（静默分歧全靠它定位）
            if (rxFrameLogs < FRAME_LOG_LIMIT) {
                rxFrameLogs++
                log(
                    "LINK rx: ctl=0x${"%02x".format(control)} seq=$sequence ack=$ack " +
                        "session=$sessionId len=$size",
                )
            }
        }
        engine.onFrameSent = { control, sequence, ack, sessionId, size ->
            ProtocolTrace.linkFrame("TX", control, sequence, ack, sessionId, size)
            if (txFrameLogs < FRAME_LOG_LIMIT) {
                txFrameLogs++
                log(
                    "LINK tx: ctl=0x${"%02x".format(control)} seq=$sequence ack=$ack " +
                        "session=$sessionId len=$size",
                )
            }
        }
        Thread({ runReader() }, "$name-reader").apply {
            isDaemon = true
            start()
        }
        Thread({ runPacer() }, "$name-pacer").apply {
            isDaemon = true
            start()
        }
    }

    /** 每代同步重新等待就绪 */
    fun awaitReady(
        timeoutMillis: Long,
        expectedGeneration: Int? = null,
    ): Boolean {
        val deadline = nowMillis() + timeoutMillis.coerceAtLeast(0)
        synchronized(lock) {
            while (true) {
                checkGenerationLocked(expectedGeneration)
                if (terminated) return false
                if (engine.state == Iap2LinkEngine.State.NORMAL) return true
                val remaining = deadline - nowMillis()
                if (remaining <= 0) {
                    if (generation == 0 && receivedBytes == 0L && engine.state == Iap2LinkEngine.State.NEGOTIATING) {
                        silentDeadlineExpired = true
                    }
                    return false
                }
                try {
                    lock.wait(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
    }

    /** 读取暂存帧或交付端点前复核代次，旧会话不能消费新链路数据 */
    fun checkGeneration(expectedGeneration: Int) {
        synchronized(lock) { checkGenerationLocked(expectedGeneration) }
    }

    /** 与发送和队列交接共用同一把锁，避免检查后才发生同步切换 */
    private fun checkGenerationLocked(expectedGeneration: Int?) {
        if (expectedGeneration != null && expectedGeneration != generation) throw PeerRestartedException()
    }

    /** 对端协商的最大控制载荷（已扣除帧头与校验和），协商前为 null。 */
    fun peerMaxControlPayload(): Int? = synchronized(lock) { engine.peerSynchronization?.maxLength?.minus(Iap2LinkEngine.HEADER_BYTES + 1) }

    /** 排入一个完整控制载荷；链路已死返回 false。窗口满时内部积压，由 ACK 驱动发出。 */
    fun sendControl(
        bytes: ByteArray,
        awaitAck: Boolean = true,
        expectedGeneration: Int? = null,
    ): Boolean {
        synchronized(lock) {
            checkGenerationLocked(expectedGeneration)
            if (terminated) return false
            val accepted = engine.sendControl(bytes, nowMillis(), awaitAck)
            drainOutputLocked()
            return accepted
        }
    }

    /** 取下一个控制载荷；超时或链路结束返回 null。 */
    fun recvControl(
        timeoutMillis: Long,
        expectedGeneration: Int? = null,
    ): ByteArray? {
        synchronized(lock) { checkGenerationLocked(expectedGeneration) }
        if (timeoutMillis == 0L && terminated && controls.isEmpty()) return null
        val polled =
            try {
                controls.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        synchronized(lock) { checkGenerationLocked(expectedGeneration) }
        return if (polled === null || polled === POISON || polled === RESTART) null else polled
    }

    override fun close() {
        localCloseRequested = true
        finish("本地主动关闭")
    }

    // ---- 内部 ----

    private fun runReader() {
        try {
            synchronized(lock) {
                if (terminated) return
                if (respondToPeerHandshake) {
                    engine.enterNegotiating()
                    log("BT-LINK: 本轮使用应答握手，等待车机 DETECT/SYN，不主动重发同步包")
                } else {
                    engine.start(nowMillis())
                }
                drainOutputLocked()
            }
            val buffer = ByteArray(4096)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) {
                    finish("对端关闭", remoteTransport = true)
                    return
                }
                if (count == 0) continue
                synchronized(lock) {
                    if (terminated) return
                    receivedBytes += count
                    dumpInbound(buffer.copyOf(count))
                    engine.feed(buffer.copyOf(count), nowMillis())
                    processEventsLocked()
                    drainOutputLocked()
                    signalReadyLocked()
                }
            }
        } catch (error: Throwable) {
            finish(error.message ?: error.javaClass.simpleName, remoteTransport = error is IOException)
        }
    }

    /** 按方向记录前 [RAW_DUMP_LIMIT] 字节，限制长连接日志量 */
    private fun dumpInbound(bytes: ByteArray) {
        if (bytes.isEmpty() || rxDumpBudget <= 0) return
        val shown = minOf(bytes.size, rxDumpBudget)
        log("RAW rx: [载荷已隐藏，长度=${bytes.size}B]")
        rxDumpBudget -= shown
    }

    private fun runPacer() {
        while (!terminated) {
            val deadline: Long?
            synchronized(lock) {
                if (terminated) return
                engine.advanceTime(nowMillis())
                processEventsLocked()
                drainOutputLocked()
                signalReadyLocked()
                deadline = engine.nextDeadlineMillis()
                if (deadline == null) {
                    try {
                        lock.wait(PACER_POLL_MILLIS)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
            }
            if (deadline != null) {
                val sleep = (deadline - nowMillis()).coerceIn(1, PACER_POLL_MILLIS)
                try {
                    Thread.sleep(sleep)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    /** 持锁调用：抽取引擎事件并搬运输出。 */
    private fun processEventsLocked() {
        while (true) {
            when (val event = engine.pollEvent() ?: return) {
                is Iap2LinkEngine.Event.Control -> {
                    if (!controls.offer(event.bytes)) {
                        engine.feedEof()
                        return
                    }
                }

                is Iap2LinkEngine.Event.Dead -> {
                    finishLocked(event.reason?.let { IOException(it) })
                    signalReadyLocked()
                    return
                }
            }
        }
    }

    private fun drainOutputLocked() {
        // 同一读取里若新业务已证明旧链路继续工作，就撤销尚未发出的探测应答
        val replyToDetect =
            pendingInitialDetectReply ||
                (pendingDetectReply && engine.awaitingPeerSynchronization && engine.state == Iap2LinkEngine.State.NORMAL)
        pendingInitialDetectReply = false
        pendingDetectReply = false
        if (replyToDetect) {
            dumpOutbound(Iap2LinkEngine.MARKER)
            try {
                stream.write(Iap2LinkEngine.MARKER)
            } catch (error: IOException) {
                finishLocked(error, remoteTransport = true)
                return
            }
        }
        val bytes = engine.takeOutput()
        if (bytes.isEmpty()) return
        dumpOutbound(bytes)
        try {
            stream.write(bytes)
        } catch (error: IOException) {
            finishLocked(error, remoteTransport = true)
        }
        signalReadyLocked()
    }

    private fun dumpOutbound(bytes: ByteArray) {
        if (txDumpBudget <= 0) return
        val shown = minOf(bytes.size, txDumpBudget)
        log("RAW tx: [载荷已隐藏，长度=${bytes.size}B]")
        txDumpBudget -= shown
    }

    private fun signalReadyLocked() {
        if (!terminated && engine.state == Iap2LinkEngine.State.NORMAL && !readyNotified) {
            readyNotified = true
            try {
                onReady()
            } catch (error: IOException) {
                finishLocked(error)
            }
        }
        if (engine.state == Iap2LinkEngine.State.NORMAL || terminated) lock.notifyAll()
    }

    private fun finish(
        reason: String?,
        remoteTransport: Boolean = false,
    ) {
        synchronized(lock) { finishLocked(reason?.let { IOException(it) }, remoteTransport) }
    }

    private fun finishLocked(
        failure: IOException?,
        remoteTransport: Boolean = false,
    ) {
        if (terminated) return
        remoteTransportEnded = remoteTransport
        terminated = true
        endDescription = failure?.message
        controls.offer(POISON) // 哨兵：唤醒阻塞的接收方（LBQ 不接受 null）
        lock.notifyAll()
        runCatching { stream.close() }
        if (failure != null) {
            log("LINK closed: ${failure.message}")
        } else {
            log("LINK closed")
        }
    }

    private fun nowMillis(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val PACER_POLL_MILLIS = 20L
        const val MAX_PEER_RESTARTS = 3
        const val MAX_DETECT_REPLIES = 3
        const val DETECT_REPLY_INTERVAL_MILLIS = 1_000L

        /** 只用于唤醒旧代次接收者，不是有效控制载荷 */
        val RESTART = ByteArray(0)

        /** 每方向原始转储最多 4096 字节 */
        // 保留连接初期的协议数据，长会话按预算限制日志量
        const val RAW_DUMP_LIMIT = 4096
        const val RAW_DUMP_LIMIT_TX = 4096

        /** 逐帧摘要日志上限（每方向每连接）。 */
        const val FRAME_LOG_LIMIT = 40

        /** 链路终结哨兵；真实控制载荷至少包含一段 CSM 帧，不会为空。 */
        val POISON = ByteArray(0)
    }
}
