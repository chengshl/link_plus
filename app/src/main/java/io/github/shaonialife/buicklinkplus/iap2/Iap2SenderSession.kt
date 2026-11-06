package io.github.shaonialife.buicklinkplus.iap2

import io.github.shaonialife.buicklinkplus.transport.Iap2LinkChannel
import io.github.shaonialife.buicklinkplus.util.ProtocolTrace
import java.io.IOException
import java.io.InterruptedIOException
import java.security.SecureRandom

/**
 * iAP2 发送端控制会话，完成身份识别、MFi 和热点端点获取
 * 旧协议从 0x5703 取热点、0x4301 取 AirPlay 端点；新协议可从 0x4301 同时获取
 * 消息顺序参考 xcertplay Iap2WirelessControlClient，并按车机能力清单分支
 */
class Iap2SenderSession(
    private val channel: Iap2LinkChannel,
    private val log: (String) -> Unit,
    /** 本机蓝牙 MAC，用于传输标识和 ctrl 广播 */
    private val bluetoothMac: String = "02:00:00:00:00:00",
    /** 本机蓝牙名（0x4E09 设备信息）。 */
    private val deviceName: String = "iPhone",
    /** 收到热点凭据后立即绑定 WiFi 并广播 ctrl，车机接受邀请后才会发 0x4301 */
    private val onWifiCredentials: ((WifiConfiguration) -> Unit)? = null,
    /** 握手后收到迟到的 0x4301 时通知主控更新端点或重新配对 */
    private val onEndpointUpdate: ((CarPlayEndpoint) -> Unit)? = null,
    /** MFi 长度记忆的持久化上下文，null 时仅用进程内缓存 */
    private val context: android.content.Context? = null,
    /** 按实际协议阶段更新界面 */
    private val onPhase: (String) -> Unit = {},
    /** 仅本次目标连接操作内共享的临时换档，重新发起连接后不沿用 */
    private val challengeRecovery: MfiChallengeRecovery? = null,
    /** 传输自行结束仍属于本次操作，本地取消和被替换的旧会话不能写恢复结果 */
    private val isSessionCurrent: () -> Boolean = { true },
) {
    /** 从 0x5703 + 0x4301 解析出的接收端信息。 */
    data class CarPlayEndpoint(
        val ssid: String,
        val passphrase: String,
        val channel: Int,
        val securityType: Int,
        val airPlayPort: Int,
        val deviceIdentifier: String?,
        val publicKey: String?,
        val sourceVersion: String?,
        val ipAddresses: List<String>,
        /**
         * mDNS features 位图，bit38 表示 HomeKit 配对和加密能力
         * null 表示未知，按需要配对处理；未声明该位时可尝试 et=NONE
         */
        val advertisedFeatures: Long? = null,
    )

    private val parser = Iap2Csm.Parser()

    /** 实例只服务一代链路，重新同步后创建新实例 */
    val linkGeneration: Int = channel.sessionGeneration

    /** 刚解析、尚未分类的帧（到达顺序）。 */
    private val receivedFrames = ArrayDeque<Iap2Csm.Frame>()

    /** 等待目标消息时暂存其他帧，与新帧队列分开，避免反复读取同一帧形成活锁 */
    private val pendingFrames = ArrayDeque<Iap2Csm.Frame>()

    /** 暂存帧数量上限，避免长等待期间持续增长 */
    private val maxPendingFrames = 256
    private var pendingOverflowLogged = false

    private val random = SecureRandom()

    /** 0x5703 解析结果（保活期间车机重发 0x5702 时需重新应答）。 */
    @Volatile
    private var wifi: WifiConfiguration? = null

    /** 0x4301 原始体是否已记录（每轮会话一次，避免周期补发刷屏）。 */
    private var startSessionRawLogged = false

    /** 最近一次晚到 0x4301 的端点指纹（车机周期重发相同内容时不重复打详情）。 */
    private var lastLateStartSessionSignature: Int? = null

    /** 车机声明可发送的消息 ID；未声明的 StartHID 和 0x4301 不等待 */
    private var accessorySendIds: Set<Int> = emptySet()

    /** 0x1D01 参数 7：车机声明「它可接收」的消息 ID 清单。 */
    private var accessoryReceiveIds: Set<Int> = emptySet()

    /** 识别帧是否真的带了接收清单（空清单与「未提供」必须区分）。 */
    private var receiveListDeclared = false

    /** 与诊断日志共用能力判定，未知清单保留旧协议尝试 */
    private var wifiGeneration = ReceiverProfile.WifiGen.UNKNOWN

    /** 车机是否声明会发某消息；null = 未提供清单（未知，按保守策略等待）。 */
    private fun accessoryDeclaresSend(messageId: Int): Boolean? = if (accessorySendIds.isEmpty()) null else messageId in accessorySendIds

    /** 车机是否声明能收某消息；null = 未提供清单（未知，按保守策略继续）。 */
    private fun accessoryDeclaresReceive(messageId: Int): Boolean? = if (!receiveListDeclared) null else messageId in accessoryReceiveIds

    private companion object {
        /** 等 StartHID 的窗口：xcertplay 会发（订阅后立即到），DiPlay 不发。 */
        const val HID_WAIT_NANOS = 2_000_000_000L

        /** 等待旧协议 0x4301 的短窗口，明确未声明该消息时跳过 */
        const val START_SESSION_WAIT_NANOS = 3_000_000_000L

        /** 新协议等待 0x4301 的窗口，热点凭据也从该消息获取 */
        const val START_SESSION_WAIT_NEW_PROTOCOL_NANOS = 8_000_000_000L

        /** 旧协议使用独立等待窗口，给新版热点交换留出时间 */
        const val WIFI_CONFIGURATION_WAIT_NANOS = 5_000_000_000L

        /** 0x4301 原始体日志上限（Audi 实测 261B，完整记录；异常大的只截前段）。 */
        const val START_SESSION_RAW_LOG_BYTES = 1_024

        /** 已出现过静默的挑战档位缩短重试等待，首次尝试保留完整窗口 */
        const val MFI_SILENT_RETRY_WINDOW_MS = 1_500L

        /** 挑战档位（长度, 首次试探窗口）：顺序与窗口由 DiPlay/Audi 双端实测决定。 */
        private val MFI_ATTEMPTS = listOf(32 to 3_000L, 20 to 6_000L, 16 to 3_000L)

        /** 真车系接收端特征（0x1D01 name/model/manufacturer 子串，小写比对）。 */
        private val LEGACY_RECEIVER_MARKERS = listOf("audi", "delphi", "mib", "harman", "porsche")
    }

    /** 提取车机 name、model 和 manufacturer，转小写后用于选择 MFi 挑战长度 */
    private fun identityText(frame: Iap2Csm.Frame): String {
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        return listOfNotNull(
            params[0]?.value?.asStringOrEmpty(),
            params[1]?.value?.asStringOrEmpty(),
            params[2]?.value?.asStringOrEmpty(),
        ).joinToString(" ").lowercase()
    }

    fun run(timeoutMillis: Long = 60_000L): CarPlayEndpoint {
        checkSessionActive()
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        if (!channel.awaitReady(remainingMillis(deadline), linkGeneration)) {
            throw IOException(channel.failureDescription("iAP2 链路协商"))
        }
        log("iap2 link ready peerMaxControl=${channel.peerMaxControlPayload()}")
        onPhase("蓝牙协议已就绪，正在读取车机信息…")

        // ---- 识别 ----
        send(Iap2MessageIds.START_IDENTIFICATION)
        val identification = expect(Iap2MessageIds.IDENTIFICATION_INFORMATION, deadline)
        logIdentification(identification)
        send(Iap2MessageIds.IDENTIFICATION_ACCEPTED)
        log("iap2 tx=0x1d02 identification-accepted")

        // 先解析 EA 协议，等 MFi 完成后再启动，避免部分接收端停止认证
        val eaProtocols = parseEaProtocols(identification)

        // ---- MFi（跳过校验：0xAA05 自报成功）----
        onPhase("已识别车机，正在协商连接…")
        val identityText = identityText(identification)
        val legacyCar = LEGACY_RECEIVER_MARKERS.any { identityText.contains(it) }
        if (legacyCar) {
            log("iap2 识别为真车系（$identityText）——MFi 首选 20B（真 iPhone 实录长度；32B 对真车处置不稳定）")
        }
        // 完整新版能力声明支持序列号时先请求序列号，收到后再请求完整证书
        // 不缓存或伪造认证结果；不支持此流程的旧车机仍直接请求证书
        val certificateRequest =
            if (wifiGeneration == ReceiverProfile.WifiGen.NEW && accessoryDeclaresSend(Iap2MessageIds.MFI_AUTH_SERIAL) == true) {
                Iap2Csm.param(0, ByteArray(0))
            } else {
                ByteArray(0)
            }
        send(Iap2MessageIds.MFI_REQUEST_CERTIFICATE, certificateRequest)
        val certificate = expect(Iap2MessageIds.MFI_CERTIFICATE, deadline)
        log("iap2 rx=0xaa01 certificate bytes=${certificate.body.size}（跳过校验）")
        // 结合接收端身份和成功记录选择挑战长度，减少无应答的尝试
        runMfiChallenge(deadline, prefer20First = legacyCar, receiverKey = identityText, certificateBody = certificate.body)
        send(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)
        log("iap2 tx=0xaa05 authentication-succeeded（自报）")

        // ---- EA 会话启动（MFi 认证完成后）----
        // 奥迪 0x1D01 声明 de.audi.mmicconnectdatatransfer / com.audi.cpp.eadata0/1
        // 三个 EA 协议；触摸 HID 相关数据经 EA 会话。认证前发送会噎死 DiPlay（见上）。
        for ((protocolId, name) in eaProtocols) {
            sendFireAndForget(
                Iap2MessageIds.EA_OPEN_SESSION,
                Iap2Csm.u8(0, protocolId) + Iap2Csm.u16(1, protocolId),
            )
            log("iap2 tx=0xea00 startEASession protocol=$protocolId name=$name")
        }

        // 新版协议先交换热点，HID 交给后续收包循环处理；旧协议保留短暂等待
        if (wifiGeneration != ReceiverProfile.WifiGen.NEW) awaitOptionalHid(deadline)

        // 旧协议通过 0x5702/0x5703 交换热点，新协议从 0x4301 获取
        // 清单稀疏或缺失时仍先尝试旧协议
        onPhase("正在获取车机热点信息…")
        val legacyWifiExchange = wifiGeneration != ReceiverProfile.WifiGen.NEW
        val wifiFromExchange: WifiConfiguration? =
            if (!legacyWifiExchange) {
                log(
                    "iap2 能力分类为新版热点协议——直接发送 0x4300，等待 0x4301 提供 AP 信息",
                )
                null
            } else {
                send(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
                val wifiDeadline = minOf(deadline, System.nanoTime() + WIFI_CONFIGURATION_WAIT_NANOS)
                val wifiConfiguration = expectOrNull(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION, wifiDeadline)
                if (wifiConfiguration == null) {
                    log("iap2 0x5703 未在窗口内到达——改走 0x4300/0x4301（新版协议自带 AP 信息）")
                    null
                } else {
                    val parsed = parseWifiConfiguration(wifiConfiguration)
                    log(
                        "iap2 rx=0x5703 ssid=${parsed.ssid} channel=${parsed.channel} " +
                            "security=${parsed.securityType} psk=${if (parsed.passphrase.isEmpty()) "(空)" else "***"}",
                    )
                    // 真车死锁解药（catplay CarPlayInviter 实证）：车机在 WiFi 网络上发现手机的
                    // _carplay-ctrl._tcp 后主动发 GET /ctrl-int/1/connect 邀请，**邀请成功才发 0x4301**。
                    // 因此这里必须立即通知主控绑 WiFi + 广播 ctrl（带真实蓝牙 MAC 作 TXT id）。
                    checkSessionActive()
                    onWifiCredentials?.invoke(parsed)
                    parsed
                }
            }

        // 新版先发 UUID、时间、语言和名称，再发双标识 0x4300
        // 0x4E0D/0x4E0E 按完整能力清单发送，旧协议保留原有顺序
        val modernBootstrap = wifiGeneration == ReceiverProfile.WifiGen.NEW
        if (modernBootstrap && accessoryDeclaresReceive(Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER) == true) {
            sendFireAndForget(
                Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER,
                Iap2Csm.string(0, bluetoothMac),
            )
            log("iap2 新版启动前提供设备传输标识，蓝牙标识=$bluetoothMac")
        }
        if (!modernBootstrap || accessoryDeclaresReceive(Iap2MessageIds.WIRELESS_CARPLAY_UPDATE) == true) {
            send(Iap2MessageIds.WIRELESS_CARPLAY_UPDATE, Iap2Csm.u8(0, 1))
        }
        val epochSeconds = System.currentTimeMillis() / 1000L
        val timezoneMinutes =
            java.util.TimeZone
                .getDefault()
                .getOffset(System.currentTimeMillis()) / 60_000
        val timeUpdate =
            Iap2Csm.param(0, u64Bytes(epochSeconds)) +
                Iap2Csm.u16(1, timezoneMinutes and 0xFFFF) +
                Iap2Csm.u8(2, 0)
        // iAP2 语言字段使用 ISO 639 的 zh，不使用带地区的标签
        val deviceUpdates =
            listOf(
                Iap2MessageIds.DEVICE_UUID_UPDATE to Iap2Csm.string(0, deviceUuid()),
                Iap2MessageIds.DEVICE_TIME_UPDATE to timeUpdate,
                Iap2MessageIds.DEVICE_LANGUAGE_UPDATE to Iap2Csm.string(0, "zh"),
                Iap2MessageIds.DEVICE_INFORMATION_UPDATE to Iap2Csm.string(0, deviceName),
            )
        val orderedUpdates =
            if (modernBootstrap) {
                deviceUpdates
            } else {
                listOf(
                    deviceUpdates[2],
                    deviceUpdates[3],
                    deviceUpdates[1],
                    deviceUpdates[0],
                )
            }
        for ((messageId, body) in orderedUpdates) send(messageId, body)
        log(
            "iap2 设备状态问候完成（language=zh name=$deviceName mac=$bluetoothMac）",
        )

        // 新版明确支持 0x4300 时等待确认并允许重传，旧协议保持发后不等
        // 双标识由本机地址派生，无线 MAC 使用小写，两组字符串都以 NUL 结尾
        val wirelessIdentifier = if (modernBootstrap) bluetoothMac.lowercase(java.util.Locale.ROOT) else bluetoothMac
        val availability =
            if (modernBootstrap) {
                val wiredIdentifier = "00008110${bluetoothMac.replace(":", "").uppercase(java.util.Locale.ROOT)}0000"
                Iap2Csm.group(0, Iap2Csm.u8(0, 1), Iap2Csm.string(1, wiredIdentifier)) +
                    Iap2Csm.group(1, Iap2Csm.u8(0, 1), Iap2Csm.string(1, wirelessIdentifier))
            } else {
                Iap2Csm.group(1, Iap2Csm.u8(0, 1), Iap2Csm.string(1, wirelessIdentifier))
            }
        if (wifiGeneration == ReceiverProfile.WifiGen.NEW && accessoryDeclaresReceive(Iap2MessageIds.CARPLAY_AVAILABILITY) == true) {
            // 新版车机明确支持此消息，它又是唯一热点交换入口，必须允许链路确认与重传
            // buick.2 对照日志中最后一包未被确认，发后不管无法恢复未获确认的启动请求
            send(Iap2MessageIds.CARPLAY_AVAILABILITY, availability)
        } else {
            sendFireAndForget(Iap2MessageIds.CARPLAY_AVAILABILITY, availability)
        }
        log("iap2 0x4300 无线可用=true 双传输标识=$modernBootstrap 蓝牙标识=$wirelessIdentifier bytes=${availability.size + Iap2Csm.HEADER_BYTES}")
        // 已有热点且未声明 0x4301 时跳过等待，端点改由 mDNS 或网关补全
        val declaresStartSession = accessoryDeclaresSend(Iap2MessageIds.CARPLAY_START_SESSION)
        // 没拿到 0x5703（新协议车机）时，0x4301 是**唯一**的 AP 信息来源——窗口给足；
        // 真有 0x5703 的旧协议车机保持 3 秒短窗口（应答都在毫秒级）。
        val startSessionWindow =
            if (wifiFromExchange == null) START_SESSION_WAIT_NEW_PROTOCOL_NANOS else START_SESSION_WAIT_NANOS
        var startSession: Iap2Csm.Frame? = null
        // 没拿到热点时，即使稀疏清单没列 0x4301，也必须给新版交换一次机会
        if (declaresStartSession == false && wifiFromExchange != null) {
            log("iap2 车机未声明会发 0x4301——跳过等待（AirPlay 端点走 mDNS/网关）")
        } else {
            // 车机可能只发 0x4301 而完全不回答 0x5702：发送后先等一轮，
            // 若这段时间里 0x4301 先到了（新协议的正常顺序），直接采用。
            startSession =
                expectOrNull(
                    Iap2MessageIds.CARPLAY_START_SESSION,
                    minOf(deadline, System.nanoTime() + startSessionWindow),
                )
        }
        // 别克日志显示：短窗口结束时链路仍活着，是本端抛错后主动拆链
        // 没有 AP 信息就无法走 mDNS，但仍可保留蓝牙，等用户在车机确认或迟到的启动消息
        // 不重发未知握手或新增业务启动请求；未确认帧由链路层同序号重传，取消仍可关闭通道
        if (startSession == null && wifiFromExchange == null && channel.isAlive && remainingMillis(deadline) > 0) {
            log(
                "iap2 ${startSessionWindow / 1_000_000_000}s 内未收到 0x4301，尚无热点凭据——" +
                    "保持蓝牙继续等待，剩余预算 ${remainingMillis(deadline)}ms；请在车机选择 CarPlay",
            )
            onPhase("蓝牙协议协商完成，等待车机启动 CarPlay…")
            startSession = expectOrNull(Iap2MessageIds.CARPLAY_START_SESSION, deadline)
        }
        val endpoint =
            if (startSession != null) {
                parseStartSession(startSession, wifiFromExchange)
            } else {
                // 0x4301 可能晚于等待窗口到达，日志保留稍后补发的提示
                log(
                    if (wifiFromExchange == null) {
                        "iap2 未取得热点凭据，0x4301 在本轮截止前仍未到达" +
                            "（链路存活=${channel.isAlive}）——无法开始 WiFi 与 AirPlay 连接"
                    } else if (declaresStartSession == true) {
                        "iap2 0x4301 在 ${startSessionWindow / 1_000_000_000}s 窗口内未到" +
                            "（车机声明会发——可能稍后补发，保活期间收到会重新触发配对）——" +
                            "先用 mDNS/网关"
                    } else {
                        "iap2 0x4301 未到（车机未声明会发；AirPlay 端点走 mDNS _airplay._tcp）"
                    },
                )
                if (wifiFromExchange == null) {
                    throw IOException("本轮未取得车机热点凭据：0x5703 未返回，0x4301 也未提供 AP 信息，请导出日志排查")
                }
                CarPlayEndpoint(
                    ssid = wifiFromExchange.ssid,
                    passphrase = wifiFromExchange.passphrase,
                    channel = wifiFromExchange.channel,
                    securityType = wifiFromExchange.securityType,
                    airPlayPort = 0,
                    deviceIdentifier = null,
                    publicKey = null,
                    sourceVersion = null,
                    ipAddresses = emptyList(),
                )
            }
        if (startSession != null) {
            logStartSessionRaw(startSession)
            log(
                "iap2 rx=0x4301 airPlayPort=${endpoint.airPlayPort} " +
                    "device=${endpoint.deviceIdentifier} publicKey=${endpoint.publicKey?.take(16)}… " +
                    "srcvers=${endpoint.sourceVersion} ips=${endpoint.ipAddresses}",
            )
        }

        // 旧协议在 0x4301 后发送传输标识，部分车机不回 ACK，这里不等待确认
        if (!modernBootstrap) {
            sendFireAndForget(
                Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER,
                Iap2Csm.string(0, bluetoothMac),
            )
        }
        log("iap2 Phase 1 完成：拿到热点凭据与（可能的）AirPlay 端点")

        // 后续（Phase 2）：加入热点 → mDNS 解析 _airplay._tcp → AirPlay 主会话。
        // 0x5703 缺席时 AP 信息来自 0x4301（已并入 endpoint）——保活期车机重发
        // 0x5703/0x4301 都要用它解析，不能留空。
        this.wifi =
            wifiFromExchange ?: WifiConfiguration(
                ssid = endpoint.ssid,
                passphrase = endpoint.passphrase,
                channel = endpoint.channel,
                securityType = endpoint.securityType,
            )
        checkSessionActive()
        return endpoint
    }

    /** 握手后保持蓝牙链路，持续处理控制消息直到断开 */
    fun pumpIncomingForever() {
        log("SESSION 保活中（链路保持打开，持续记录配件消息）")
        // 同一消息 ID 连续到达时限流：车机进入媒体播放后会周期性发 NowPlaying 更新，
        // 逐条打日志会把文件日志撑爆（文件超 2MB 直接删，等于丢掉真正有用的上下文）。
        var lastLoggedId = -1
        var repeatCount = 0
        while (true) {
            val frame = recvFrame(30_000L)
            if (frame == null) {
                if (!channel.isAlive) break
                continue
            }
            // 先处理生命周期消息再限流，避免丢掉迟到或重复的 0x4301
            if (frame.messageId == Iap2MessageIds.CARPLAY_START_SESSION) {
                handleLateStartSession(frame)
            }
            if (frame.messageId == Iap2MessageIds.START_HID) {
                respondToStartHid(frame)
                continue
            }
            if (frame.messageId == lastLoggedId) {
                repeatCount++
                if (repeatCount % 100 != 0) continue
            } else {
                if (repeatCount >= 99) {
                    log("iap2 上一条 0x${lastLoggedId.toString(16)} 重复 ${repeatCount + 1} 次（同类已限流）")
                }
                lastLoggedId = frame.messageId
                repeatCount = 0
            }
            log(
                "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                    "(${Iap2MessageIds.nameOf(frame.messageId)}) keep-alive bytes=${frame.body.size}",
            )
        }
        log("SESSION 保活结束（链路断开）")
    }

    /** 保活期间解析迟到的 0x4301 并通知主控，无 0x5703 时从无线组读取热点凭据 */
    private fun handleLateStartSession(frame: Iap2Csm.Frame) {
        checkSessionActive()
        logStartSessionRaw(frame)
        val endpoint = parseStartSession(frame, wifi)
        if (wifi == null && endpoint.ssid.isNotEmpty()) {
            wifi =
                WifiConfiguration(
                    ssid = endpoint.ssid,
                    passphrase = endpoint.passphrase,
                    channel = endpoint.channel,
                    securityType = endpoint.securityType,
                )
            log("iap2 0x4301 自带 AP 信息：ssid=${endpoint.ssid}——已缓存，供后续 0x5703/0x4301 复用")
        }
        if (endpoint.ssid.isEmpty()) {
            log("iap2 rx=0x4301（晚到）——无线组里没解析出 SSID，本条忽略")
            return
        }
        // 车机可能周期性重发同一份 0x4301：详情只在端点变化时打，避免刷日志。
        if (endpoint.hashCode() != lastLateStartSessionSignature) {
            lastLateStartSessionSignature = endpoint.hashCode()
            log(
                "iap2 rx=0x4301（晚到，Phase 1 之后）airPlayPort=${endpoint.airPlayPort} " +
                    "device=${endpoint.deviceIdentifier} publicKey=${endpoint.publicKey?.take(16)}… " +
                    "srcvers=${endpoint.sourceVersion} ips=${endpoint.ipAddresses}" +
                    "——交给主控热更新端点/重试配对",
            )
        }
        checkSessionActive()
        onEndpointUpdate?.invoke(endpoint)
    }

    /** 每轮只记录一次 0x4301 原始体，便于比较端点字段 */
    private fun logStartSessionRaw(frame: Iap2Csm.Frame) {
        if (startSessionRawLogged) return
        startSessionRawLogged = true
        log("iap2 0x4301 载荷长度=${frame.body.size}B，不记录原始凭据载荷")
    }

    // ---- 帧收发 ----

    /**
     * 按成功记录尝试 MFi 挑战长度，拒绝或静默时换档
     * 断链只调整下一次尝试；无签名时继续由后续热点请求判断
     */
    private fun runMfiChallenge(
        deadline: Long,
        prefer20First: Boolean = false,
        receiverKey: String,
        certificateBody: ByteArray,
    ) {
        val baselineLengths =
            if (prefer20First) {
                listOf(MFI_ATTEMPTS[1].first, MFI_ATTEMPTS[0].first, MFI_ATTEMPTS[2].first)
            } else {
                MFI_ATTEMPTS.map { it.first }
            }
        val windowOf = MFI_ATTEMPTS.associate { it.first to it.second }
        val memory = MfiLengthMemory.load(context, receiverKey)
        val rememberedOrder = MfiLengthMemory.orderAttempts(baselineLengths, memory)
        val recoveryAttempt = challengeRecovery?.begin(receiverKey, rememberedOrder)
        val order = recoveryAttempt?.lengths ?: rememberedOrder
        if (!memory.isEmpty) {
            log(
                "iap2 MFi 按车机记忆（$receiverKey）：${MfiLengthMemory.describe(memory)}" +
                    " → 本轮顺序 ${order.joinToString("/")}B",
            )
        }
        for ((position, size) in order.withIndex()) {
            var challengeSent = false
            try {
                // 已知「静默过」的档位缩短窗口：它已经失败过一次，没必要再等满。
                val baseWindow = windowOf[size] ?: MFI_ATTEMPTS.last().second
                val windowMillis =
                    if (size in memory.silent && size != memory.succeeded) {
                        minOf(baseWindow, MFI_SILENT_RETRY_WINDOW_MS)
                    } else {
                        baseWindow
                    }
                val challenge = ByteArray(size).also(random::nextBytes)
                send(Iap2MessageIds.MFI_REQUEST_CHALLENGE, Iap2Csm.param(0, challenge))
                challengeSent = true
                log("iap2 tx=0xaa02 challenge=${size}B（第 ${position + 1} 次，窗口 ${windowMillis}ms）")
                val window = minOf(System.nanoTime() + windowMillis * 1_000_000L, deadline)
                var gotResponse = false
                var gotFailure = false
                while (true) {
                    val remaining = (window - System.nanoTime()) / 1_000_000L
                    if (remaining <= 0) break
                    val frame = recvFrame(remaining)
                    if (frame == null) {
                        if (!channel.isAlive) {
                            // 主动取消、传输故障和对端拒绝不能混为一谈，只有明确的 0xaa04 才记录被拒
                            throw IOException(channel.failureDescription("MFi ${size}B 挑战"))
                        }
                        continue
                    }
                    when (frame.messageId) {
                        Iap2MessageIds.MFI_RESPONSE -> {
                            log("iap2 rx=0xaa03 signature bytes=${frame.body.size}（跳过校验）")
                            if (Iap2Csm.parseParams(frame.body).any { it.id == 0 && it.value.isNotEmpty() }) {
                                MfiCertificateHint.recordResponse(certificateBody, size)
                            }
                            gotResponse = true
                        }

                        Iap2MessageIds.MFI_AUTHENTICATION_FAILED -> {
                            log("iap2 rx=0xaa04 挑战 ${size}B 被拒")
                            gotFailure = true
                        }

                        else ->
                            log(
                                "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                                    "(${Iap2MessageIds.nameOf(frame.messageId)})",
                            )
                    }
                    if (gotResponse || gotFailure) break
                }
                checkSessionActive()
                if (!channel.isAlive) throw IOException(channel.failureDescription("MFi ${size}B 挑战"))
                if (gotResponse) {
                    MfiLengthMemory.recordSuccess(context, receiverKey, size)
                    recoveryAttempt?.let { challengeRecovery.recordSuccess(it) }
                    return
                }
                if (gotFailure) {
                    MfiLengthMemory.recordRejected(context, receiverKey, size)
                } else {
                    MfiLengthMemory.recordSilent(context, receiverKey, size)
                }
                if (position < order.size - 1) {
                    log("iap2 MFi ${size}B 无应答，改用 ${order[position + 1]}B 重试")
                } else {
                    log("iap2 MFi 挑战全部无应答——跳过校验直接发 0xaa05（由 0x5702 判定车机是否放行）")
                }
            } catch (error: IOException) {
                if (challengeSent &&
                    !Thread.currentThread().isInterrupted &&
                    isSessionCurrent() &&
                    channel.canRetryMfiAfterDisconnect &&
                    recoveryAttempt != null &&
                    challengeRecovery.recordDisconnect(recoveryAttempt, size) == true
                ) {
                    log("iap2 MFi ${size}B 挑战后传输断开，下一条连接临时后移该档位；不记为长度被拒")
                }
                throw error
            }
        }
    }

    private fun send(
        messageId: Int,
        vararg body: ByteArray,
    ) {
        checkSessionActive()
        val joined =
            when (body.size) {
                0 -> ByteArray(0)
                1 -> body[0]
                else -> body.reduce { acc, bytes -> acc + bytes }
            }
        val frame = Iap2Csm.encode(messageId, joined)
        ProtocolTrace.csm("TX", messageId, joined)
        val limit = channel.peerMaxControlPayload() ?: (frame.size - 1).coerceAtLeast(1)
        var offset = 0
        while (offset < frame.size) {
            checkSessionActive()
            val end = minOf(offset + limit, frame.size)
            if (!channel.sendControl(frame.copyOfRange(offset, end), expectedGeneration = linkGeneration)) {
                throw IOException("iAP2 链路在发送 0x${messageId.toString(16)} 时关闭")
            }
            offset = end
        }
        log("iap2 tx=0x${messageId.toString(16).padStart(4, '0')}")
    }

    /** [send] 的发后不管变体：链路不因对端不 ACK 此消息而重传/死亡（见 0x4E0E）。 */
    private fun sendFireAndForget(
        messageId: Int,
        body: ByteArray,
    ) {
        checkSessionActive()
        val frame = Iap2Csm.encode(messageId, body)
        ProtocolTrace.csm("TX-NOACK", messageId, body)
        val limit = channel.peerMaxControlPayload() ?: (frame.size - 1).coerceAtLeast(1)
        var offset = 0
        while (offset < frame.size) {
            checkSessionActive()
            val end = minOf(offset + limit, frame.size)
            if (!channel.sendControl(frame.copyOfRange(offset, end), awaitAck = false, expectedGeneration = linkGeneration)) {
                throw IOException("iAP2 链路在发送 0x${messageId.toString(16)} 时关闭")
            }
            offset = end
        }
        log("iap2 tx=0x${messageId.toString(16).padStart(4, '0')}（发后不管）")
    }

    private fun expect(
        messageId: Int,
        deadline: Long,
    ): Iap2Csm.Frame =
        expectOrNull(messageId, deadline)
            ?: throw IOException(channel.failureDescription("等待 0x${messageId.toString(16)}"))

    /** 等待可选消息，超时返回 null；先查暂存区，再读新帧，其他消息只暂存不重读 */
    private fun expectOrNull(
        messageId: Int,
        deadlineNanos: Long,
    ): Iap2Csm.Frame? {
        checkSessionActive()
        pendingFrames.firstOrNull { it.messageId == messageId }?.let {
            pendingFrames.remove(it)
            return it
        }
        var fullCertificateRequested = false
        while (true) {
            val remaining = (deadlineNanos - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) return null
            val frame = recvNewFrame(remaining)
            if (frame == null) {
                if (!channel.isAlive) return null
                continue
            }
            if (frame.messageId == messageId) return frame
            if (messageId == Iap2MessageIds.MFI_CERTIFICATE && frame.messageId == Iap2MessageIds.MFI_AUTH_SERIAL) {
                if (!fullCertificateRequested) {
                    fullCertificateRequested = true
                    send(Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
                    log("iap2 rx=0xaa06 认证序列号——请求完整证书，不复用其他链路的认证结果")
                }
                continue
            }
            if (frame.messageId == Iap2MessageIds.START_HID) {
                respondToStartHid(frame)
                continue
            }
            log(
                "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                    "(${Iap2MessageIds.nameOf(frame.messageId)})，非预期，暂存待后续消费",
            )
            pendingFrames += frame
            if (pendingFrames.size > maxPendingFrames) {
                pendingFrames.removeFirst()
                if (!pendingOverflowLogged) {
                    pendingOverflowLogged = true
                    log("iap2 非预期帧暂存区超过 $maxPendingFrames 条——丢弃最旧的（车机订阅类消息过多）")
                }
            }
        }
    }

    private fun awaitOptionalHid(deadline: Long) {
        // 清单明确未声明 StartHID 时不等，避免多等 2 秒
        if (accessoryDeclaresSend(Iap2MessageIds.START_HID) == false) {
            log("iap2 车机未声明会发 0x6800 StartHID——跳过 2s 等待")
            return
        }
        val hidWindowNanos = minOf(System.nanoTime() + HID_WAIT_NANOS, deadline)
        // 复用分类等待，保留提前到达的 0x4301、热点凭据和订阅请求，不能只记日志后丢弃
        val frame = expectOrNull(Iap2MessageIds.START_HID, hidWindowNanos)
        if (frame == null) {
            log("iap2 窗口内未收到 0x6800（链路存活=${channel.isAlive}），继续后续连接步骤")
            return
        }
        respondToStartHid(frame)
    }

    /** HID 不作为新版热点交换的前置条件，等待和保活期间收到即应答 */
    private fun respondToStartHid(frame: Iap2Csm.Frame) {
        log("iap2 rx=0x6800 start-hid bytes=${frame.body.size}")
        send(
            Iap2MessageIds.DEVICE_HID_REPORT,
            Iap2Csm.u16(0, 0),
            Iap2Csm.param(1, byteArrayOf(0)),
        )
    }

    /** 先按顺序消费暂存帧，再读取新帧 */
    private fun recvFrame(timeoutMillis: Long): Iap2Csm.Frame? {
        checkSessionActive()
        pendingFrames.firstOrNull()?.let {
            pendingFrames.removeFirst()
            return it
        }
        return recvNewFrame(timeoutMillis)
    }

    /** 只取新帧（先已解析队列、再读 socket）；绝不返回暂存区的帧。 */
    private fun recvNewFrame(timeoutMillis: Long): Iap2Csm.Frame? {
        checkSessionActive()
        receivedFrames.firstOrNull()?.let {
            receivedFrames.removeFirst()
            return it
        }
        if (timeoutMillis <= 0) return null
        val bytes = channel.recvControl(timeoutMillis, linkGeneration)
        // 接收方保留中断标记并返回空值，必须在继续等待或解析缓存之前结束旧会话
        checkSessionActive()
        if (bytes == null) return null
        val frames = parser.offer(bytes)
        if (frames.isEmpty()) return null
        frames.forEach { ProtocolTrace.csm("RX", it.messageId, it.body) }
        frames.forEachIndexed { index, frame -> if (index > 0) receivedFrames += frame }
        return frames.first()
    }

    /** 关闭仍在后台进行时也响应取消，保留中断标记 */
    private fun checkSessionActive() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("iAP2 会话已取消")
        if (!isSessionCurrent()) throw IOException("iAP2 控制会话已退役")
        channel.checkGeneration(linkGeneration)
    }

    // ---- 解析 ----

    /** 从 0x5703 解析出的热点凭据（收到后应立即绑 WiFi + 广播 _carplay-ctrl）。 */
    data class WifiConfiguration(
        val ssid: String,
        val passphrase: String,
        val channel: Int,
        val securityType: Int,
    )

    private fun parseWifiConfiguration(frame: Iap2Csm.Frame): WifiConfiguration {
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        return WifiConfiguration(
            ssid = params[1]?.value?.asStringOrEmpty() ?: "",
            passphrase = params[2]?.value?.asStringOrEmpty() ?: "",
            securityType =
                params[3]
                    ?.value
                    ?.firstOrNull()
                    ?.toInt()
                    ?.and(0xFF) ?: 0,
            channel =
                params[4]
                    ?.value
                    ?.firstOrNull()
                    ?.toInt()
                    ?.and(0xFF) ?: 0,
        )
    }

    /**
     * 解析 0x4301，优先使用 0x5703 热点缓存，缺失时解析消息中的无线组
     * 兼容从 0 或 1 起编号的参数，格式参考 CarPlay Addendum
     */
    private fun parseStartSession(
        frame: Iap2Csm.Frame,
        wifi: WifiConfiguration?,
    ): CarPlayEndpoint {
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        val wirelessGroup = params[1]?.value
        val wireless = wirelessGroup?.let { Iap2Csm.parseParams(it) }
        val addresses =
            wireless
                ?.filter { it.id == 3 }
                ?.mapNotNull { param -> param.value.asStringOrEmpty()?.takeIf { it.isNotEmpty() } }
                .orEmpty()
        log(
            "iap2 0x4301 顶层参数: " +
                params.entries.joinToString(" ") { (id, param) -> "#$id(${param.value.size}B)" },
        )
        if (wireless != null) {
            log(
                "iap2 0x4301 无线组参数: " +
                    wireless.joinToString(" ") { param ->
                        "#${param.id}(${param.value.size}B)"
                    },
            )
        }
        val fromSession = wireless?.let { parseWirelessAp(it) }
        val ap = wifi ?: fromSession
        if (wifi == null && fromSession != null) {
            log(
                "iap2 0x4301 自带 AP：ssid=${fromSession.ssid} channel=${fromSession.channel} " +
                    "security=${fromSession.securityType} psk=${if (fromSession.passphrase.isEmpty()) "(空)" else "***"}",
            )
        }
        return CarPlayEndpoint(
            ssid = ap?.ssid ?: "",
            passphrase = ap?.passphrase ?: "",
            channel = ap?.channel ?: 0,
            securityType = ap?.securityType ?: 0,
            airPlayPort = params[2]?.value?.asU32() ?: 0,
            deviceIdentifier = params[3]?.value?.asStringOrEmpty(),
            publicKey = params[4]?.value?.asStringOrEmpty(),
            sourceVersion = params[5]?.value?.asStringOrEmpty(),
            ipAddresses = addresses,
        )
    }

    /**
     * 从无线组读取热点：前两个非 IP 字符串为 SSID 和密码
     * 信道优先取 id=2，再从 id=4/3 中区分安全类型；解析失败返回 null
     */
    private fun parseWirelessAp(group: List<Iap2Csm.Param>): WifiConfiguration? {
        val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        val strings =
            group
                .sortedBy { it.id }
                .mapNotNull { param -> param.value.asStringOrEmpty() }
                .filter { it.isNotEmpty() && !ipv4.matches(it) && !it.contains(':') }
        val ssid = strings.getOrNull(0)?.takeIf { it.length <= 32 } ?: return null
        val passphrase = strings.getOrNull(1) ?: ""
        val singles = group.filter { it.value.size == 1 }.associate { it.id to (it.value[0].toInt() and 0xFF) }
        val security =
            singles[4]?.takeIf { it in 0..4 }
                ?: singles[3]?.takeIf { it in 0..4 }
                ?: 0
        val channel =
            singles[2]?.takeIf { it in 1..196 }
                ?: singles[4]?.takeIf { it in 1..196 && it != security }
                ?: singles[3]?.takeIf { it in 1..196 && it != security }
                ?: 0
        return WifiConfiguration(ssid = ssid, passphrase = passphrase, channel = channel, securityType = security)
    }

    /** 从身份信息的 10/11/12 参数组解析 EA 协议，子参数 0 为编号、1 为名称，参考 DiPlay */
    private fun parseEaProtocols(frame: Iap2Csm.Frame): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        for (groupId in intArrayOf(10, 11, 12)) {
            val value = params[groupId]?.value ?: continue
            val sub = Iap2Csm.parseParams(value).associateBy { it.id }
            val protocolId =
                sub[0]
                    ?.value
                    ?.firstOrNull()
                    ?.toInt()
                    ?.and(0xFF) ?: continue
            val name = sub[1]?.value?.asStringOrEmpty() ?: "?"
            out += protocolId to name
            log("iap2 0x1d01 EA 组件[$groupId] protocol=$protocolId name=$name")
        }
        return out
    }

    private fun logIdentification(frame: Iap2Csm.Frame) {
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        val name = params[0]?.value?.asStringOrEmpty()
        val model = params[1]?.value?.asStringOrEmpty()
        val manufacturer = params[2]?.value?.asStringOrEmpty()
        log("iap2 rx=0x1d01 车机身份 name=$name model=$model manufacturer=$manufacturer")
        // 参数 6 是车机会发送的消息，参数 7 是车机可接收的消息
        val sends = params[6]?.value?.asU16List()
        val receives = params[7]?.value?.asU16List()
        if (sends != null) {
            // 后续可选消息（0x6800/0x4301）按此清单判断是否会来，避免固定窗口空等
            accessorySendIds = sends.toSet()
            log("iap2 0x1d01 车机发送清单(${sends.size})=" + sends.joinToString(" ") { "0x%04x".format(it) })
            log(
                "iap2 0x1d01 可选消息声明：StartHID(0x6800)=" +
                    (accessoryDeclaresSend(Iap2MessageIds.START_HID)?.let { if (it) "会发" else "不发" } ?: "未知") +
                    " CarPlayStartSession(0x4301)=" +
                    (accessoryDeclaresSend(Iap2MessageIds.CARPLAY_START_SESSION)?.let { if (it) "会发" else "不发" } ?: "未知"),
            )
        }
        if (receives != null) {
            // 车机能否收 0x5702 决定要不要走旧协议——不接受就必须走 0x4300/0x4301
            // （BMW iD9 的可收清单里没有 0x5702，旧流程因此每轮死等 0x5703 超时）。
            accessoryReceiveIds = receives.toSet()
            receiveListDeclared = true
            log("iap2 0x1d01 车机可收清单(${receives.size})=" + receives.joinToString(" ") { "0x%04x".format(it) })
            log(
                "iap2 0x1d01 协议代际：请求 WiFi 配置(0x5702)=" +
                    (
                        accessoryDeclaresReceive(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)?.let {
                            if (it) "可收" else "不收"
                        } ?: "未知"
                    ) +
                    " 回 WiFi 配置(0x5703)=" +
                    (
                        accessoryDeclaresSend(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION)?.let {
                            if (it) "会发" else "不发"
                        } ?: "未知"
                    ),
            )
        }
        // 缺失任一清单时也分类，日志和实际分派必须使用同一份结果
        val profile =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(
                    name = name,
                    model = model,
                    manufacturer = manufacturer,
                    sendIds = sends?.toSet(),
                    receiveIds = receives?.toSet(),
                ),
            )
        wifiGeneration = profile.wifiGen
        log(ReceiverProfile.describe(profile))
        log("iap2 0x1d01 原始体=" + frame.body.joinToString("") { "%02x".format(it) })
    }

    private fun remainingMillis(deadlineNanos: Long): Long {
        val remaining = (deadlineNanos - System.nanoTime()) / 1_000_000L
        return remaining.coerceIn(0, 60_000L)
    }

    private fun u64Bytes(value: Long): ByteArray =
        ByteArray(8) { index ->
            (value ushr (8 * (7 - index))).toByte()
        }

    /** 从蓝牙 MAC 派生稳定的设备 UUID */
    private fun deviceUuid(): String =
        java.util.UUID
            .nameUUIDFromBytes(bluetoothMac.toByteArray())
            .toString()
            .uppercase()

    /** 解析 NUL 结尾的 CSM 字符串，去掉全部尾部 NUL，避免定长地址解析失败 */
    private fun ByteArray.asStringOrEmpty(): String? {
        if (isEmpty() || last() != 0.toByte()) return null
        var end = size
        while (end > 0 && this[end - 1] == 0.toByte()) end--
        return copyOf(end).decodeToString()
    }

    private fun ByteArray.asU32(): Int =
        if (size == 4) {
            ((this[0].toInt() and 0xFF) shl 24) or
                ((this[1].toInt() and 0xFF) shl 16) or
                ((this[2].toInt() and 0xFF) shl 8) or
                (this[3].toInt() and 0xFF)
        } else {
            0
        }

    /** 参 6/7 的消息清单：大端 u16 序列（识别帧里车机声明收/发能力用）。 */
    private fun ByteArray.asU16List(): List<Int>? {
        if (size < 2 || size % 2 != 0) return null
        return (0 until size step 2).map {
            ((this[it].toInt() and 0xFF) shl 8) or (this[it + 1].toInt() and 0xFF)
        }
    }
}
