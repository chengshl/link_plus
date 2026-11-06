package io.github.shaonialife.buicklinkplus.airplay

import android.content.Context
import io.github.shaonialife.buicklinkplus.iap2.Iap2Csm
import io.github.shaonialife.buicklinkplus.iap2.Iap2LinkEngine
import io.github.shaonialife.buicklinkplus.iap2.Iap2MessageIds
import io.github.shaonialife.buicklinkplus.iap2.MfiCertificateHint
import io.github.shaonialife.buicklinkplus.iap2.MfiLengthMemory
import io.github.shaonialife.buicklinkplus.iap2.ReceiverProfile
import io.github.shaonialife.buicklinkplus.input.ConsumerKeys
import io.github.shaonialife.buicklinkplus.util.ProtocolTrace
import java.security.SecureRandom
import java.util.TimeZone
import java.util.UUID

/**
 * AirPlay 上的虚拟 iAP2 链路，按接收端分流
 * DiPlay 通过 type-130 TCP 发送，协商采用回显优先
 * MIB3 通过控制通道 POST 发送，屏幕 SETUP 后发 DETECT，等待 SYN 再回 SYN|ACK
 * 旧明文会话使用 marker 回显；入站消息统一交给 [onCarBytes]
 * MIB3 时序参考 AADisplay WifiIap2Link
 */
class VirtualIap2Link(
    private val log: (String) -> Unit,
    /** 事件通道 iAPSendMessage 出站（旧形态兜底）。 */
    private val sendEventIap: (ByteArray) -> Boolean,
    /** 控制通道 iAPSendMessage 出站（MIB3 手机侧帧的唯一正确通道）。 */
    private val sendControlIap: (ByteArray) -> Unit,
    /** DiPlay type-130 隧道写入口（每次出站动态取用，可为 null）。 */
    private val tunnelWriter: () -> AirPlayClient.IapTunnelClient?,
    /** 节拍线程存活条件（宿主控制连接未关）。 */
    private val alive: () -> Boolean,
    /** MFi 长度记忆的持久化上下文；null = 进程内记忆（单测）。 */
    private val mfiContext: Context? = null,
    /** 时钟（单测可注入）。 */
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    /** 与蓝牙启动和 AirPlay SETUP 一致的本机标识 */
    private val localBluetoothMac: String = "02:00:00:00:00:00",
    /** 与蓝牙启动使用相同设备名称 */
    private val localDeviceName: String = "iPhone",
) {
    private val lock = Any()
    private val engine = Iap2LinkEngine(maxPeerRestarts = MAX_PEER_RESTARTS, waitForPeerAcknowledgement = true)
    private var parser = Iap2Csm.Parser()
    private var frameCount = 0
    private var eaCount = 0
    private var started = false

    @Volatile
    private var closed = false

    /** 出站任务携带会话代次，旧代次不再写入 */
    @Volatile
    var controlGeneration: Int = 0
        private set

    private val pacer: Thread
    private var linkDiagnosticFrames = 0

    // ---- DiPlay 隧道路径状态 ----
    private var tunnelCsmStarted = false
    private var tunnelIdentified = false

    /** 同步回显写入隧道 TCP 后，才允许发送 0x1D00 */
    private var pendingEchoFlush = false
    private var tunnelEchoDelivered = false

    // ---- MIB3 路径状态 ----
    private var mib3Armed = false
    private var mib3SynAckQueued = false
    private var mib3CsmStarted = false
    private var mib3Identified = false
    private var mib3AuthStage = 0
    private var mib3MarkerIgnores = 0
    private var mib3DeferredLogs = 0
    private var mib3WirelessSessionId: String? = null
    private var detectReplyCount = 0
    private var lastDetectReplyAt: Long? = null

    /** 车机持续重复 marker 时回退为发送本端 SYN，收发通道保持不变 */
    private var eventLinkArmed = false
    private var eventCsmStarted = false

    /**
     * 控制通道协商轮次：先探测，无进展时发送 marker 和本端会话表
     * 每轮等待 [CONTROL_SYN_ROUND_MILLIS]，最多 [MAX_CONTROL_SYN_ROUNDS] 轮，之后静默等待
     */
    private var controlSynRounds = 0
    private var controlSynRoundStartedAtMs = 0L
    private var controlSynGaveUp = false

    /** 未处理的控制消息计数，供排查媒体键使用 */
    private var unknownCsmIds = 0

    /** 上次已记录的链路状态，避免重复日志 */
    private var lastLinkState = Iap2LinkEngine.State.IDLE

    // ---- 车机 HID 媒体键（0x6802）状态 ----
    /** 上一次报文里按下的 Usage 集合（用于判定「新按下」，松开后自动清空）。 */
    private var lastHidUsages: Set<Int> = emptySet()

    /** 当前这批按键的起始时刻（连续同一按键超过 [HID_HOLD_REPEAT_MILLIS] 才允许连发）。 */
    private var hidHoldStartedAtMs = 0L

    /** 已打印的参数明细帧数（只打前几帧，之后按需打动作）。 */
    private var hidReportFrames = 0

    // 隧道和蓝牙分别保存 MFi 挑战长度，两条链路的认证后端可能不同
    private var mfiIdentity: String? = null
    private var mfiMemory = MfiLengthMemory.Entry()
    private var mfiOrder: List<Int> = emptyList()
    private var mfiPosition = -1
    private var mfiCurrentLength = 0
    private var mfiWindowMillis = 0L
    private var mfiChallengeSentAtMs = 0L
    private var mfiFinished = false
    private var mfiCertificateBody = ByteArray(0)

    /** 新版能力使用对应状态序列，不发送旧协议私有消息 */
    private var modernDeviceUpdates = false

    /** 本轮认证后的状态已入队，等待车机确认 */
    private var statusAwaitingAcknowledgement = false

    /** 认证和状态确认时间，重新协商后重置 */
    private var networkReadyAt: Long? = null

    /** 采用控制连接后不再另建隧道切换出站路由 */
    val usesControlTransport: Boolean
        get() =
            synchronized(lock) {
                !closed && tunnelWriter() == null && (mib3SynAckQueued || mib3CsmStarted || eventCsmStarted)
            }

    /** 只对支持新版控制流程的链路开放蓝牙交接 */
    val supportsBootstrapHandoff: Boolean
        get() = synchronized(lock) { modernDeviceUpdates && !closed }

    /** 本轮签名和状态全部确认后标记网络就绪 */
    val networkReady: Boolean
        get() =
            synchronized(lock) {
                updateNetworkReadinessLocked()
                networkReadyAt != null && !closed && engine.state == Iap2LinkEngine.State.NORMAL
            }

    /** 网络就绪后再等稳定窗口，不立即关闭蓝牙 */
    val networkStable: Boolean
        get() =
            synchronized(lock) {
                updateNetworkReadinessLocked()
                val readyAt = networkReadyAt
                readyAt != null &&
                    !closed &&
                    engine.state == Iap2LinkEngine.State.NORMAL &&
                    !engine.awaitingPeerSynchronization &&
                    nowMillis() - readyAt >= 1_500L
            }

    /** 断开时保留控制阶段，NORMAL 不代表认证完成 */
    val controlSessionStatus: String
        get() =
            synchronized(lock) {
                when {
                    mib3AuthStage == 4 -> "已收到认证应答"
                    mib3AuthStage == -1 -> "认证无应答"
                    mfiCurrentLength > 0 -> "等待 ${mfiCurrentLength}B 认证应答"
                    else -> "链路=${engine.state}，认证阶段=$mib3AuthStage"
                }
            }

    init {
        engine.onEaData = { bytes -> handleEaData(bytes) }
        engine.onSynchronizationEchoSent = { pendingEchoFlush = true }
        engine.onPeerRestarted = { count -> resetControlSessionLocked(count) }
        engine.onPeerRestartNotice = { message -> log("IAPLINK: $message") }
        engine.onPeerDetect = { newProbe -> handlePeerDetectLocked(newProbe) }
        engine.onPeerSynchronizationAdopted = { peer ->
            log("IAPLINK: 对端窗口=${peer.maxOutgoing} 最大帧=${peer.maxLength} 重传=${peer.maxRetransmissions} 会话=${peer.sessions}")
        }
        engine.onFrameReceived = { control, sequence, ack, session, size ->
            ProtocolTrace.linkFrame("VIRTUAL-RX", control, sequence, ack, session, size)
            noteFrameLocked("收", control, sequence, ack, session, size)
        }
        engine.onFrameSent = { control, sequence, ack, session, size ->
            ProtocolTrace.linkFrame("VIRTUAL-TX", control, sequence, ack, session, size)
            noteFrameLocked("发", control, sequence, ack, session, size)
        }
        pacer =
            Thread(
                {
                    while (!closed && alive()) {
                        try {
                            Thread.sleep(200)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                        synchronized(lock) {
                            if (closed || !alive()) return@Thread
                            engine.advanceTime(nowMillis())
                            drainLocked()
                        }
                    }
                },
                "iap-link-pacer",
            ).apply {
                isDaemon = true
                start()
            }
    }

    /** 事件形态启动：服务端待命（收到 marker 回 marker，等车机 SYN）。 */
    fun begin() {
        synchronized(lock) {
            if (closed || started) return
            started = true
            engine.enterNegotiating()
        }
    }

    /**
     * 屏幕 SETUP 完成后向控制通道发一次 DETECT，等待车机 SYN
     * 车机持续重发 marker 时，再由 [startControlRoundLocked] 发送本端会话表
     */
    fun armMib3() {
        synchronized(lock) {
            if (closed) return
            begin()
            if (mib3Armed) return
            mib3Armed = true
            sendControlIap(Iap2LinkEngine.MARKER)
            log("IAPLINK: MIB3 detect 已发（控制通道 iAPSendMessage）——等待车机 SYN")
        }
    }

    /**
     * 开一轮控制通道协商：marker + 本表同步包（会话 10/11/12），刷新引擎重发节拍。
     */
    private fun startControlRoundLocked(reason: String) {
        val now = nowMillis()
        controlSynRounds++
        controlSynRoundStartedAtMs = now
        engine.initiate(now)
        log(
            "IAPLINK: 控制通道 marker + 本表同步包（第 $controlSynRounds/$MAX_CONTROL_SYN_ROUNDS 轮，$reason）" +
                "——等车机同步包（FF5A）",
        )
    }

    /** 统一处理完整、分片和合并探测，失败时有限重应答 */
    private fun handlePeerDetectLocked(newProbe: Boolean) {
        val onTunnel = tunnelWriter() != null
        if (!onTunnel && !mib3Armed) {
            if (mib3DeferredLogs++ < 2) log("IAPLINK: SETUP 前的车机 marker 暂不回应（布防后主动 detect）")
            return
        }
        val now = nowMillis()
        if (newProbe) {
            detectReplyCount = 0
            lastDetectReplyAt = null
        }
        if (!onTunnel && engine.state != Iap2LinkEngine.State.NORMAL) {
            mib3MarkerIgnores++
            // 首次先按成功基线回探测；仍只有 marker 的接收端保留本表协商回退
            if (!eventLinkArmed && mib3MarkerIgnores >= EVENT_LINK_MARKER_THRESHOLD) {
                eventLinkArmed = true
                startControlRoundLocked("车机 marker 重发")
                return
            }
            if (eventLinkArmed) {
                if (!controlSynGaveUp && now - controlSynRoundStartedAtMs >= CONTROL_SYN_ROUND_MILLIS) {
                    if (controlSynRounds >= MAX_CONTROL_SYN_ROUNDS) {
                        controlSynGaveUp = true
                        engine.stopNegotiationRetries()
                        log("IAPLINK: 控制通道 $MAX_CONTROL_SYN_ROUNDS 轮协商无应答，停止自动重发")
                    } else {
                        startControlRoundLocked("上一轮 ${CONTROL_SYN_ROUND_MILLIS}ms 无应答")
                    }
                }
                return
            }
        }
        // 仅收到对端重探测才补发，最多三次且间隔至少一秒，避免回显乒乓
        val lastReply = lastDetectReplyAt
        if (detectReplyCount >= 3 || (lastReply != null && now - lastReply < 1_000L)) return
        detectReplyCount++
        lastDetectReplyAt = now
        sendLinkBytes(Iap2LinkEngine.MARKER)
        log("IAPLINK: 排队回 DETECT #$detectReplyCount（保留视频和显示屏，等待车机 SYN）")
    }

    /** 接收车机链路字节，持锁后丢弃旧代次应答 */
    fun onCarBytes(
        bytes: ByteArray,
        expectedGeneration: Int? = null,
    ) {
        synchronized(lock) {
            if (closed || (expectedGeneration != null && expectedGeneration != controlGeneration)) return
            // 隧道存在时切换「回显先行」协商语义（DiPlay 对端引擎硬性期望）
            engine.echoFirst = tunnelWriter() != null
            engine.feed(bytes, nowMillis())
            drainLocked()
        }
    }

    /** 重新协商时清理旧控制状态，保留视频、外层连接和蓝牙 */
    private fun resetControlSessionLocked(count: Int) {
        controlGeneration++
        parser = Iap2Csm.Parser()
        frameCount = 0
        eaCount = 0
        linkDiagnosticFrames = 0
        pendingEchoFlush = false
        tunnelEchoDelivered = false
        tunnelCsmStarted = false
        tunnelIdentified = false
        mib3SynAckQueued = false
        mib3CsmStarted = false
        mib3Identified = false
        mib3AuthStage = 0
        mib3WirelessSessionId = null
        mib3MarkerIgnores = 0
        detectReplyCount = 0
        lastDetectReplyAt = null
        eventCsmStarted = false
        controlSynRounds = 0
        controlSynRoundStartedAtMs = 0L
        controlSynGaveUp = false
        unknownCsmIds = 0
        mfiIdentity = null
        mfiMemory = MfiLengthMemory.Entry()
        mfiOrder = emptyList()
        mfiPosition = -1
        mfiCurrentLength = 0
        mfiWindowMillis = 0L
        mfiChallengeSentAtMs = 0L
        mfiFinished = false
        mfiCertificateBody = ByteArray(0)
        modernDeviceUpdates = false
        statusAwaitingAcknowledgement = false
        networkReadyAt = null
        hidDescriptor = null
        lastHidUsages = emptySet()
        hidHoldStartedAtMs = 0L
        hidReportFrames = 0
        log("IAPLINK: 对端重新协商 $count/$MAX_PEER_RESTARTS，旧控制会话已清理，保留视频和蓝牙连接")
    }

    /** 退役后停止节拍、换档和迟到事件 */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            controlGeneration++
            engine.feedEof()
            engine.takeOutput()
        }
        pacer.interrupt()
    }

    /** 限量记录帧头和长度，区分收包与链路确认 */
    private fun noteFrameLocked(
        direction: String,
        control: Int,
        sequence: Int,
        ack: Int,
        session: Int,
        size: Int,
    ) {
        linkDiagnosticFrames++
        if (control and 0x90 != 0 || linkDiagnosticFrames <= 80 || linkDiagnosticFrames % 100 == 0) {
            log("IAPLINK: 链路$direction ctl=0x${control.toString(16)} seq=$sequence ack=$ack session=$session len=$size")
        }
    }

    /** 按接收端发送链路帧：优先隧道 TCP，其次控制通道 POST，旧明文形态使用事件通道 */
    private fun sendLinkBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val tunnel = tunnelWriter()
        when {
            tunnel != null -> tunnel.writeIapBytes(bytes)
            eventLinkArmed -> sendControlIap(bytes)
            mib3Armed -> sendControlIap(bytes)
            else -> sendEventIap(bytes)
        }
    }

    /** SYN|ACK 成功写入后再启动 CSM，避免对端在链路就绪前收到裸 ACK */
    private fun flushOutputLocked() {
        val out = engine.takeOutput()
        if (out.isEmpty()) return
        val tunnel = tunnelWriter()
        when {
            tunnel != null -> {
                val ok = tunnel.writeIapBytes(out)
                if (ok && pendingEchoFlush) {
                    pendingEchoFlush = false
                    if (!tunnelEchoDelivered) {
                        tunnelEchoDelivered = true
                        log("IAPLINK: ✓ 同步回显已写入隧道（链路协商完成，发送端 CSM 就绪）")
                    }
                }
            }

            eventLinkArmed -> {
                // 入站事件通道 / 出站控制通道：应答、同步包、CSM 一律写控制通道
                sendControlIap(out)
                if (pendingEchoFlush) {
                    pendingEchoFlush = false
                    log("IAPLINK: ✓ SYN|ACK 已投递控制通道——链路协商完成，启动控制会话")
                }
            }

            mib3Armed -> {
                sendControlIap(out)
                if (pendingEchoFlush) {
                    pendingEchoFlush = false
                    mib3SynAckQueued = true
                    log("IAPLINK: ✓ MIB3 SYN|ACK 已投递控制通道——等待链路确认后启动 CSM")
                }
            }

            else -> {
                sendEventIap(out)
                pendingEchoFlush = false
            }
        }
    }

    private fun drainLocked() {
        while (true) {
            when (val event = engine.pollEvent()) {
                null -> break
                is Iap2LinkEngine.Event.Control ->
                    handleFrameLocked(event.bytes)

                is Iap2LinkEngine.Event.Dead -> {
                    log("IAPLINK: 虚拟链路终止 - ${event.reason ?: "EOF"}")
                    close()
                    return
                }
            }
        }
        noteLinkStateLocked()
        // 先冲刷（回显必须先行送达对端），再启动 CSM；超时换档可能新排队帧，最后再冲一次
        flushOutputLocked()
        maybeStartControlCsmLocked()
        checkMfiTimeoutLocked()
        flushOutputLocked()
        updateNetworkReadinessLocked()
    }

    /** 状态确认只记录一次，媒体订阅不撤销就绪状态 */
    private fun updateNetworkReadinessLocked() {
        if (closed || !statusAwaitingAcknowledgement || networkReadyAt != null) return
        if (mib3AuthStage != 4 || !engine.controlOutputAcknowledged) return
        networkReadyAt = nowMillis()
        statusAwaitingAcknowledgement = false
        log("IAPLINK: 网络认证与设备状态均已获确认，代次=$controlGeneration")
    }

    /** 链路状态变化时记录一次日志 */
    private fun noteLinkStateLocked() {
        val state = engine.state
        if (state == lastLinkState) return
        lastLinkState = state
        log("IAPLINK: 链路状态 → $state（NORMAL 才可能收到车机 0x6800/0x6802 媒体键）")
    }

    /** MFi 挑战超时后记为静默并换档，由 200ms 节拍驱动 */
    private fun checkMfiTimeoutLocked() {
        val identity = mfiIdentity ?: return
        if (mfiFinished || mfiCurrentLength == 0) return
        if (nowMillis() - mfiChallengeSentAtMs < mfiWindowMillis) return
        val failed = mfiCurrentLength
        MfiLengthMemory.recordSilent(mfiContext, identity, failed)
        log("IAPLINK: 隧道 MFi ${failed}B 静默 ${mfiWindowMillis}ms——记入记忆，换下一档重试")
        mfiCurrentLength = 0
        startNextMfiChallengeLocked()
    }

    /** 按车机记忆选择下一档 MFi 挑战，默认先试 20B；全部失败时不发送认证成功及后续初始化 */
    private fun startNextMfiChallengeLocked() {
        val identity = mfiIdentity ?: return
        if (mfiOrder.isEmpty()) {
            mfiMemory = MfiLengthMemory.load(mfiContext, identity)
            mfiOrder = MfiCertificateHint.orderAttempts(mfiCertificateBody, TUNNEL_MFI_BASELINE, mfiMemory)
            log("IAPLINK: 隧道 MFi 按本链路记忆与同证书应答提示排序：${mfiOrder.joinToString("/")}B")
            if (!mfiMemory.isEmpty) {
                log(
                    "IAPLINK: 隧道 MFi 按车机记忆：${MfiLengthMemory.describe(mfiMemory)}" +
                        " → 本轮顺序 ${mfiOrder.joinToString("/")}B",
                )
            }
        }
        mfiPosition += 1
        if (mfiPosition >= mfiOrder.size) {
            log("IAPLINK: 隧道 MFi 挑战全部无应答——认证未完成，停止认证换档和初始化突发")
            mfiFinished = true
            mfiCurrentLength = 0
            mib3AuthStage = -1
            return
        }
        mfiCurrentLength = mfiOrder[mfiPosition]
        val base = TUNNEL_MFI_WINDOWS[mfiCurrentLength] ?: TUNNEL_MFI_SILENT_WINDOW_MS
        mfiWindowMillis =
            if (mfiCurrentLength in mfiMemory.silent && mfiCurrentLength != mfiMemory.succeeded && mfiPosition > 0) {
                minOf(base, TUNNEL_MFI_SILENT_WINDOW_MS)
            } else {
                base
            }
        mfiChallengeSentAtMs = nowMillis()
        val challenge = ByteArray(mfiCurrentLength).also { SecureRandom().nextBytes(it) }
        sendCsmLocked(Iap2MessageIds.MFI_REQUEST_CHALLENGE, Iap2Csm.param(0, challenge))
        mib3AuthStage = 3
        log("IAPLINK: 隧道 tx=0xaa02 challenge=${mfiCurrentLength}B（第 ${mfiPosition + 1} 次，窗口 ${mfiWindowMillis}ms）")
    }

    /** 链路 NORMAL 且 SYN|ACK 已发送后，发起 0x1D00 身份识别 */
    private fun maybeStartControlCsmLocked() {
        if (engine.state != Iap2LinkEngine.State.NORMAL) return
        val onTunnel = tunnelWriter() != null
        if (onTunnel) {
            if (tunnelCsmStarted || !tunnelEchoDelivered) return
            tunnelCsmStarted = true
        } else if (eventLinkArmed) {
            // 车机发起方形态：出站走控制通道，只要已采纳对端会话表即可开工
            if (eventCsmStarted || engine.peerSynchronization == null) return
            eventCsmStarted = true
        } else {
            if (!mib3Armed || mib3CsmStarted || !mib3SynAckQueued) return
            mib3CsmStarted = true
        }
        sendCsmLocked(Iap2MessageIds.START_IDENTIFICATION)
    }

    /** 链路上的 CSM 发送（进入引擎 backlog，由引擎按发送窗口 flush）。 */
    private fun sendCsmLocked(
        messageId: Int,
        body: ByteArray = ByteArray(0),
    ) {
        val frame = Iap2Csm.encode(messageId, body)
        ProtocolTrace.csm("VIRTUAL-TX", messageId, body)
        val ok = engine.sendControl(frame, nowMillis())
        log(
            "IAPLINK: 隧道 tx 0x${messageId.toString(16)} " +
                "(${Iap2MessageIds.nameOf(messageId)}) ${frame.size}B ok=$ok",
        )
    }

    /** 直接发送抓包常量 CSM 帧，保留字节布局 */
    private fun sendRawCsmLocked(frame: ByteArray) {
        val ok = engine.sendControl(frame, nowMillis())
        val messageId = ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)
        ProtocolTrace.csm("VIRTUAL-TX-RAW", messageId, frame.copyOfRange(Iap2Csm.HEADER_BYTES, frame.size))
        log(
            "IAPLINK: 隧道 tx 0x${messageId.toString(16)} " +
                "(${Iap2MessageIds.nameOf(messageId)}) ${frame.size}B raw ok=$ok",
        )
    }

    /**
     * 处理隧道和 MIB3 的身份识别、MFi 认证及订阅
     * 0x1D01 → 0x1D02 + 0xAA00，0xAA06 → 0xAA00，0xAA01 → 0xAA02，0xAA03 → 0xAA05
     * MIB3 订阅返回对应空态；0xEA02、0xA101 和 0xFFFB 只由链路层 ACK
     */
    private fun maybeRespondControlCsmLocked(frame: Iap2Csm.Frame) {
        val onTunnel = tunnelWriter() != null
        // 车机发起方形态复用 MIB3 那套识别/MFi 骨架（出站同样走控制通道）
        val mib3 = (mib3Armed || eventLinkArmed) && !onTunnel
        if (!onTunnel && !mib3) return
        val ids = Iap2MessageIds
        when (frame.messageId) {
            ids.IDENTIFICATION_INFORMATION -> {
                val identified = if (onTunnel) tunnelIdentified else mib3Identified
                if (identified) return
                if (onTunnel) tunnelIdentified = true else mib3Identified = true
                val params =
                    Iap2Csm
                        .parseParams(frame.body)
                        .associateBy { it.id }
                log(
                    "IAPLINK: 隧道 rx 0x1d01 接收端身份 " +
                        "name=${params[0]?.value?.decodeToString()?.trimEnd('\u0000')} " +
                        "model=${params[1]?.value?.decodeToString()?.trimEnd('\u0000')}",
                )
                // 参 6/7 = 车机声明「它会发 / 它可收」的消息清单。媒体键排查就靠这两行：
                // 若 0x6800/0x6802 不在发送清单里，说明这台车机根本不用 iAP2 HID 送按键，
                // 不必再在这条通路上耗（改查蓝牙/AVRCP 侧）。
                params[6]?.value?.let { raw ->
                    val sends = u16List(raw)
                    log(
                        "IAPLINK: 车机发送清单(${sends.size})=" +
                            sends.joinToString(" ") { "0x%04x".format(it) } +
                            " 含HID0x6800=${sends.contains(Iap2MessageIds.START_HID)}" +
                            " 含0x6802=${sends.contains(Iap2MessageIds.ACCESSORY_HID_REPORT)}",
                    )
                }
                params[7]?.value?.let { raw ->
                    val receives = u16List(raw)
                    log(
                        "IAPLINK: 车机可收清单(${receives.size})=" +
                            receives.joinToString(" ") { "0x%04x".format(it) },
                    )
                }
                if (mib3) {
                    // 记忆键 = 车机身份 + " tunnel"：与蓝牙链路的记忆互不污染
                    mfiIdentity = mfiIdentityOf(params) + " tunnel"
                }
                val sendIds = params[6]?.value?.let(::u16List)?.toSet()
                val receiveIds = params[7]?.value?.let(::u16List)?.toSet()
                val profile = ReceiverProfile.classify(ReceiverProfile.Signals(sendIds = sendIds, receiveIds = receiveIds))
                modernDeviceUpdates = mib3 && profile.wifiGen == ReceiverProfile.WifiGen.NEW
                log("IAPLINK: 设备状态策略=${if (modernDeviceUpdates) "新版能力序列" else "既有链路序列"}；${ReceiverProfile.describe(profile)}")
                sendCsmLocked(ids.IDENTIFICATION_ACCEPTED)
                val certificateRequest =
                    if (modernDeviceUpdates &&
                        sendIds?.contains(ids.MFI_AUTH_SERIAL) == true
                    ) {
                        Iap2Csm.param(0, ByteArray(0))
                    } else {
                        ByteArray(0)
                    }
                sendCsmLocked(ids.MFI_REQUEST_CERTIFICATE, certificateRequest)
                mib3AuthStage = 1
            }

            ids.MFI_AUTH_SERIAL -> {
                if (!mib3 || mib3AuthStage != 1) return
                sendCsmLocked(ids.MFI_REQUEST_CERTIFICATE)
                mib3AuthStage = 2
                log("IAPLINK: MIB3 0xaa06（auth serial）→ 再发 0xaa00")
            }

            ids.MFI_AUTHENTICATION_FAILED -> {
                if (!mib3 || mfiFinished || mfiCurrentLength == 0) return
                mfiIdentity?.let { MfiLengthMemory.recordRejected(mfiContext, it, mfiCurrentLength) }
                log("IAPLINK: 隧道 MFi ${mfiCurrentLength}B 被拒（0xaa04）——记入记忆，换下一档")
                mfiCurrentLength = 0
                startNextMfiChallengeLocked()
            }

            ids.MFI_CERTIFICATE -> {
                if (mib3) {
                    // 重复证书不能提前换档，否则旧签名无法与当前挑战对应
                    if (mfiFinished || mfiCurrentLength != 0) return
                    mfiCertificateBody = frame.body.copyOf()
                    startNextMfiChallengeLocked()
                } else {
                    // DiPlay CARLINKIT 身份只接受精确 32B（长度不符直接断链）
                    val challenge =
                        ByteArray(32).also { SecureRandom().nextBytes(it) }
                    sendCsmLocked(ids.MFI_REQUEST_CHALLENGE, Iap2Csm.param(0, challenge))
                    mib3AuthStage = 3
                }
            }

            ids.MFI_RESPONSE -> {
                if (mib3) {
                    if (mfiFinished || mfiCurrentLength == 0) return
                    if (Iap2Csm.parseParams(frame.body).none { it.id == 0 && it.value.isNotEmpty() }) {
                        log("IAPLINK: 隧道认证应答不含签名，继续等待有效应答")
                        return
                    }
                    mfiIdentity?.let { MfiLengthMemory.recordSuccess(mfiContext, it, mfiCurrentLength) }
                    log("IAPLINK: 隧道 rx=0xaa03 签名 ${frame.body.size}B（${mfiCurrentLength}B 挑战成功，已记忆）")
                    mfiCurrentLength = 0
                    mfiFinished = true
                }
                sendCsmLocked(ids.MFI_AUTHENTICATION_SUCCEEDED)
                if (mib3) {
                    mib3AuthStage = 4
                    sendMib3BurstLocked()
                    statusAwaitingAcknowledgement = true
                } else {
                    log("IAPLINK: 隧道 MFi 完成——等待对端订阅（随后其标记隧道就绪）")
                }
            }

            START_COMMUNICATIONS_UPDATES -> {
                if (!mib3) return
                sendRawCsmLocked(MIB3_COMMUNICATIONS_UPDATE_IDLE)
                log("IAPLINK: MIB3 0x4157 → 0x4158（空态）")
            }

            START_CALL_STATE_UPDATES -> {
                if (!mib3) return
                sendCsmLocked(
                    CALL_STATE_UPDATE,
                    Iap2Csm.param(0x0002, byteArrayOf(0)),
                )
                log("IAPLINK: MIB3 0x4154 → 0x4155（无通话）")
            }

            START_LIST_UPDATES -> {
                if (!mib3) return
                sendCsmLocked(
                    LIST_UPDATE,
                    Iap2Csm.param(0x0000, byteArrayOf(0)) +
                        Iap2Csm.param(0x0002, byteArrayOf(0, 0)) +
                        Iap2Csm.param(0x0005, byteArrayOf(0)) +
                        Iap2Csm.param(0x0007, byteArrayOf(0, 0)),
                )
                log("IAPLINK: MIB3 0x4170 → 0x4171（空列表）")
            }

            Iap2MessageIds.START_NOW_PLAYING_UPDATES -> {
                if (!mib3) return
                sendRawCsmLocked(MIB3_NOW_PLAYING_UPDATE_IDLE)
                // 仪表盘歌词/媒体卡的数据通路（0x5001 + file-transfer 封面）后续接
                // MediaSessionsState——空态应答是 MIB3 存活的前置，先落这版。
                log("IAPLINK: MIB3 0x5000 → 0x5001（空态）——仪表盘媒体数据待接")
            }

            ids.START_HID -> {
                // 车机声明「我要开始发 HID 报告了」，通常带描述符（决定报告字节语义）
                val params = Iap2Csm.parseParams(frame.body)
                val descriptor = params.map { it.value }.firstOrNull { it.size > 8 }
                if (descriptor != null) {
                    hidDescriptor = descriptor
                    log(
                        "IAPLINK: 车机 0x6800 StartHID 描述符 ${descriptor.size}B hex=" +
                            descriptor.joinToString(" ") { "%02x".format(it) }.take(120),
                    )
                } else {
                    log("IAPLINK: 车机 0x6800 StartHID（无描述符）参数=${params.map { it.id }}")
                }
            }

            ids.ACCESSORY_HID_REPORT -> {
                // 媒体键数据在参数 1，参数 0 是报告描述，不能当作按键值
                val params = Iap2Csm.parseParams(frame.body)
                val report = hidReportFromParams(params)
                val keyMap = hidDescriptor?.let(ConsumerKeys::parseDescriptor)
                val usages =
                    when {
                        report == null -> emptyList()
                        keyMap != null -> keyMap.usagesFor(report)
                        // 无描述符：退化为「单字节即 Usage ID」
                        else -> listOf(report[0].toInt() and 0xFF).filter { it != 0 }
                    }
                if (hidReportFrames < HID_REPORT_VERBOSE_FRAMES) {
                    hidReportFrames++
                    log(
                        "IAPLINK: 车机 0x6802 params=" +
                            params.joinToString(",") { "${it.id}=" + it.value.joinToString("") { b -> "%02x".format(b) } } +
                            " 报告=" + (report?.joinToString(" ") { "%02x".format(it) } ?: "空") +
                            " 位图=" + (keyMap?.isArray?.let { if (it) "Array" else "Variable" } ?: "无描述符"),
                    )
                }
                fireHidKeysLocked(usages)
            }

            else -> {
                // 未处理的消息也留痕：车机若用非常规 ID 下发媒体键/按键，这里能第一时间
                // 在日志里看到对应消息号（切歌按键排查的关键观测点）。
                // 带全量 hex：宝马 iD9 的 0xad00/0xad02/0xad03 家族既不在 Apple 公开表里，
                // 也没有参考实现的命名——只能靠报文体结构反推它是不是输入（触摸/HID）通路。
                unknownCsmIds++
                if (unknownCsmIds <= 20) {
                    log(
                        "IAPLINK: 未处理的 CSM 0x${frame.messageId.toString(16)} " +
                            "(${Iap2MessageIds.nameOf(frame.messageId)}) ${frame.body.size}B hex=" +
                            frame.body.joinToString(" ") { "%02x".format(it) },
                    )
                }
            }
        }
    }

    /** 只对新按下的 HID Usage 触发媒体键；按住超过 [HID_HOLD_REPEAT_MILLIS] 后允许重复切歌 */
    private fun fireHidKeysLocked(usages: List<Int>) {
        val now = nowMillis()
        if (usages.isEmpty()) {
            lastHidUsages = emptySet()
            hidHoldStartedAtMs = 0L
            return
        }
        val fresh = usages.filter { it !in lastHidUsages }
        val repeat =
            fresh.isEmpty() &&
                hidHoldStartedAtMs > 0 &&
                now - hidHoldStartedAtMs >= HID_HOLD_REPEAT_MILLIS
        lastHidUsages = usages.toSet()
        if (fresh.isNotEmpty()) hidHoldStartedAtMs = now
        if (fresh.isEmpty() && !repeat) return
        if (repeat) hidHoldStartedAtMs = now
        val firing = if (fresh.isNotEmpty()) fresh else usages
        for (usage in firing) {
            val keyCode = ConsumerKeys.keyCodeFor(usage)
            log(
                "IAPLINK: 车机媒体键 ${ConsumerKeys.nameOf(usage)}(0x%02x) → keyCode=".format(usage) +
                    (if (keyCode != 0) "$keyCode 已注入" else "无对应 Android 键（忽略）"),
            )
            if (keyCode != 0) onMediaKey?.invoke(keyCode)
        }
    }

    /** 认证成功后发送初始化消息，字节布局参考 AADisplay WifiIap2Link 的抓包常量 */
    private fun sendMib3BurstLocked() {
        if (modernDeviceUpdates) {
            // 同车成功会话仅主动发这四条，订阅相关消息在对端请求时再应答
            // UUID 与蓝牙链路由同一地址派生，字符串均保留协议要求的 NUL
            val deviceUuid =
                UUID
                    .nameUUIDFromBytes(
                        localBluetoothMac.toByteArray(Charsets.UTF_8),
                    ).toString()
                    .uppercase(java.util.Locale.ROOT)
            sendCsmLocked(Iap2MessageIds.DEVICE_UUID_UPDATE, Iap2Csm.string(0, deviceUuid))
            sendCsmLocked(Iap2MessageIds.DEVICE_TIME_UPDATE, mib3DeviceTimeParams())
            sendCsmLocked(Iap2MessageIds.DEVICE_LANGUAGE_UPDATE, Iap2Csm.string(0, "zh"))
            sendCsmLocked(Iap2MessageIds.DEVICE_INFORMATION_UPDATE, Iap2Csm.string(0, localDeviceName))
            log("IAPLINK: 新版设备状态已发（UUID/时间/语言/名称），等待车机订阅")
            return
        }
        val sessionId =
            mib3WirelessSessionId ?: UUID.randomUUID().toString().uppercase().also {
                mib3WirelessSessionId = it
            }
        sendCsmLocked(
            WIRELESS_SESSION_ID,
            Iap2Csm.param(0, sessionId.toByteArray(Charsets.UTF_8)),
        )
        sendCsmLocked(AUX_STATUS_NOTIFICATION, Iap2Csm.param(0x0006, ByteArray(0)))
        sendCsmLocked(
            DEVICE_LANGUAGE,
            Iap2Csm.param(0, "zh".toByteArray(Charsets.UTF_8)),
        )
        sendCsmLocked(DEVICE_TIME, mib3DeviceTimeParams())
        sendCsmLocked(
            DEVICE_INFORMATION_UPDATE,
            Iap2Csm.param(0, "iPhone".toByteArray(Charsets.UTF_8)),
        )
        sendRawCsmLocked(MIB3_START_LOCATION_INFORMATION)
        sendRawCsmLocked(MIB3_GPRMC_DATA_STATUS)
        log("IAPLINK: MIB3 突发已发（会话标识 $sessionId）——等待车机订阅")
    }

    /** 0x4E0B：u64 秒 + s16 时区偏移(分) + u8 夏令时。 */
    private fun mib3DeviceTimeParams(): ByteArray {
        val now = System.currentTimeMillis()
        val zone = TimeZone.getDefault()
        val offsetMin = zone.getOffset(now) / 60_000
        val dst = if (zone.inDaylightTime(java.util.Date(now))) 1 else 0
        val seconds = ByteArray(8)
        for (i in 0 until 8) {
            seconds[i] = (now / 1000L ushr ((7 - i) * 8)).toByte()
        }
        val tz =
            byteArrayOf(
                (offsetMin shr 8).toByte(),
                offsetMin.toByte(),
            )
        return Iap2Csm.param(0, seconds) + Iap2Csm.param(1, tz) + Iap2Csm.param(2, byteArrayOf(dst.toByte()))
    }

    /** 大端 u16 数组（识别信息里的消息 ID 清单）。 */
    private fun u16List(bytes: ByteArray): List<Int> =
        (0 until bytes.size / 2).map {
            ((bytes[it * 2].toInt() and 0xFF) shl 8) or (bytes[it * 2 + 1].toInt() and 0xFF)
        }

    /** 从 0x1D01 提取车机身份作为记忆键，去掉 NUL 和空字段 */
    private fun mfiIdentityOf(params: Map<Int, Iap2Csm.Param>): String =
        listOf(0, 1, 2)
            .mapNotNull { id ->
                params[id]
                    ?.value
                    ?.decodeToString()
                    ?.trimEnd('\u0000')
                    ?.takeIf { it.isNotBlank() }
            }.joinToString(" ")

    private fun handleFrameLocked(bytes: ByteArray) {
        frameCount++
        val frames = parser.offer(bytes)
        for (frame in frames) {
            ProtocolTrace.csm("VIRTUAL-RX", frame.messageId, frame.body)
            if (frameCount <= 30 || frameCount % 100 == 0) {
                val hex = frame.body.joinToString(" ") { "%02x".format(it) }
                log(
                    "IAPLINK: rx 0x${frame.messageId.toString(16)} " +
                        "(${Iap2MessageIds.nameOf(frame.messageId)}) ${frame.body.size}B hex=${hex.take(160)}",
                )
            }
            maybeRespondControlCsmLocked(frame)
        }
        if (frames.isEmpty() && frameCount <= 10) {
            log("IAPLINK: rx ${bytes.size}B（非 CSM）hex=" + bytes.joinToString(" ") { "%02x".format(it) }.take(160))
        }
    }

    /** EA 会话数据回调，供解析触摸 HID */
    var onEaData: ((ByteArray) -> Unit)? = null

    /** 将 0x6802 HID 报告解析出的媒体键交给回调 */
    var onMediaKey: ((Int) -> Unit)? = null

    /** 车机在 0x6800 StartHID 里给出的 HID 描述符（解析报告用；null = 未提供）。 */
    private var hidDescriptor: ByteArray? = null

    fun handleEaData(bytes: ByteArray) {
        eaCount++
        val hex = bytes.joinToString(" ") { "%02x".format(it) }
        if (eaCount <= 30 || eaCount % 100 == 0) {
            log("IAPLINK: EA 数据 #$eaCount ${bytes.size}B hex=${hex.take(200)}")
        }
        onEaData?.invoke(bytes)
    }

    companion object {
        /** 每条网络链路最多采纳三次对端重新协商，避免无限循环 */
        const val MAX_PEER_RESTARTS = 3

        /** 隧道首次优先尝试 20B 挑战，后续按车机记忆排序 */
        val TUNNEL_MFI_BASELINE = listOf(20, 32, 16)
        val TUNNEL_MFI_WINDOWS = mapOf(20 to 6_000L, 32 to 3_000L, 16 to 3_000L)
        const val TUNNEL_MFI_SILENT_WINDOW_MS = 1_500L

        /** 连续两次收到 SYN marker 后启用兼容回退，避开 MIB3 的单次探测重发 */
        const val EVENT_LINK_MARKER_THRESHOLD = 2

        /** 协商每轮等待 4 秒，最多 5 轮；无进展后静默等待后续消息 */
        const val CONTROL_SYN_ROUND_MILLIS = 4_000L
        const val MAX_CONTROL_SYN_ROUNDS = 5

        /** 0x6802 中 HID 数据的参数号，param 0 是报告类型 */
        const val HID_REPORT_DATA_PARAM = 1

        /** 仅前几帧记录参数明细 */
        const val HID_REPORT_VERBOSE_FRAMES = 8

        /** 同一按键持续按住多久才允许再次触发（模拟长按连发，短按绝不重复）。 */
        const val HID_HOLD_REPEAT_MILLIS = 1_500L

        /** 优先读取 [HID_REPORT_DATA_PARAM]，缺失时取最后一个 1～8 字节参数，跳过报告类型 */
        fun hidReportFromParams(params: List<Iap2Csm.Param>): ByteArray? =
            params.firstOrNull { it.id == HID_REPORT_DATA_PARAM }?.value?.takeIf { it.isNotEmpty() }
                ?: params.lastOrNull { it.value.isNotEmpty() && it.value.size <= 8 }?.value

        // ---- MIB3 消息号（无正式名/未入 Iap2MessageIds 的小集合） ----
        const val WIRELESS_SESSION_ID = 0x4E0C
        const val DEVICE_TIME = 0x4E0B
        const val DEVICE_LANGUAGE = 0x4E0A
        const val DEVICE_INFORMATION_UPDATE = 0x4E09
        const val AUX_STATUS_NOTIFICATION = 0xA100
        const val START_COMMUNICATIONS_UPDATES = 0x4157
        const val CALL_STATE_UPDATE = 0x4155
        const val START_CALL_STATE_UPDATES = 0x4154
        const val START_LIST_UPDATES = 0x4170
        const val LIST_UPDATE = 0x4171

        /** MIB3 初始化消息，字节来自 iPhone 到 Audi MMI 的抓包 */
        private val HEX_DIGITS = "0123456789abcdef"

        /** 0xFFFA StartLocationInformation。 */
        internal val MIB3_START_LOCATION_INFORMATION: ByteArray =
            hex(
                "40400032fffa00080000000000140004000100088001000000fa" +
                    "0004000200088002000000fa000400040008800400000014",
            )

        /** 0xFFF0 GPRMCDataStatusValuesNotification。 */
        internal val MIB3_GPRMC_DATA_STATUS: ByteArray =
            hex("4040000efff00004000000040001")

        /** 0x5001 NowPlayingUpdate 空态（无媒体/无播放）。 */
        internal val MIB3_NOW_PLAYING_UPDATE_IDLE: ByteArray =
            hex(
                "404000555001002a00000005000100000800040000000000050006000005000c0000" +
                    "0500100000050012000005001a8000250001000500000000080001000000000005" +
                    "000500000500060000050007000005001000",
            )

        /** 0x4158 CommunicationsUpdate 空态（无 SIM/无信号场景的可用占位）。 */
        internal val MIB3_COMMUNICATIONS_UPDATE_IDLE: ByteArray =
            hex(
                "404000644158000500000100050001060005000200000e0004e697a053494de58da1" +
                    "00000500050100050006010005000700000500080000050009000005000a000005" +
                    "000b000005000c010005000d000005000e000005000f0000050010000005001100",
            )

        private fun hex(s: String): ByteArray {
            val clean = s.replace(" ", "")
            return ByteArray(clean.length / 2) { i ->
                val hi = HEX_DIGITS.indexOf(clean[i * 2])
                val lo = HEX_DIGITS.indexOf(clean[i * 2 + 1])
                ((hi shl 4) or lo).toByte()
            }
        }
    }
}
