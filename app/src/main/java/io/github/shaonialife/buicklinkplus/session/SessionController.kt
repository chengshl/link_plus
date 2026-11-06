package io.github.shaonialife.buicklinkplus.session

import android.content.Context
import io.github.shaonialife.buicklinkplus.airplay.AirPlayClient
import io.github.shaonialife.buicklinkplus.airplay.PairVerifyClient
import io.github.shaonialife.buicklinkplus.audio.AudioProfile
import io.github.shaonialife.buicklinkplus.audio.AudioProfileMemory
import io.github.shaonialife.buicklinkplus.audio.AudioSender
import io.github.shaonialife.buicklinkplus.audio.AudioTuning
import io.github.shaonialife.buicklinkplus.bt.BtProfiles
import io.github.shaonialife.buicklinkplus.bt.BtSessionGate
import io.github.shaonialife.buicklinkplus.bt.Iap2BluetoothClient
import io.github.shaonialife.buicklinkplus.bt.Iap2BluetoothServer
import io.github.shaonialife.buicklinkplus.bt.TargetDevicePrefs
import io.github.shaonialife.buicklinkplus.capture.HiddenDisplaySource
import io.github.shaonialife.buicklinkplus.iap2.Iap2MessageIds
import io.github.shaonialife.buicklinkplus.iap2.Iap2SenderSession
import io.github.shaonialife.buicklinkplus.iap2.ReceiverProfile
import io.github.shaonialife.buicklinkplus.input.ConsumerKeys
import io.github.shaonialife.buicklinkplus.input.TouchReports
import io.github.shaonialife.buicklinkplus.input.TouchRouter
import io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
import io.github.shaonialife.buicklinkplus.launcher.settings.AppOrientationCompat
import io.github.shaonialife.buicklinkplus.launcher.settings.DisplayPrefs
import io.github.shaonialife.buicklinkplus.net.AirPlayFeatures
import io.github.shaonialife.buicklinkplus.net.CarPlayCtrlAdvertiser
import io.github.shaonialife.buicklinkplus.net.CarPlayServiceBrowser
import io.github.shaonialife.buicklinkplus.net.EndpointHosts
import io.github.shaonialife.buicklinkplus.net.WifiCarPlayConnector
import io.github.shaonialife.buicklinkplus.net.WifiSavedNetworks
import io.github.shaonialife.buicklinkplus.net.WifiStatus
import io.github.shaonialife.buicklinkplus.net.compatHost
import io.github.shaonialife.buicklinkplus.render.DisplayGeometry
import io.github.shaonialife.buicklinkplus.service.CarPlayService
import io.github.shaonialife.buicklinkplus.shizuku.CarInputClient
import io.github.shaonialife.buicklinkplus.shizuku.Shell
import io.github.shaonialife.buicklinkplus.shizuku.ShellServiceGate
import io.github.shaonialife.buicklinkplus.ui.ScreenDimmer
import io.github.shaonialife.buicklinkplus.util.Bg
import io.github.shaonialife.buicklinkplus.util.DebugFileLog
import io.github.shaonialife.buicklinkplus.gateway.GatewayAutoUpload
import io.github.shaonialife.buicklinkplus.util.shellQuote
import io.github.shaonialife.buicklinkplus.video.VideoProfile
import io.github.shaonialife.buicklinkplus.video.VideoSender
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** 进程级会话管理器，持有连接资源和日志，Activity 只订阅状态并转发操作 */
object SessionController {
    /** 向界面提供不可变快照 */
    fun snapshot(): SessionSnapshot {
        val video = videoSender
        val streaming = video?.isRunning == true
        return SessionSnapshot(
            streaming = streaming,
            stopping = SessionCommands.stopping,
            phase = phaseHint,
            video = if (streaming) video.activeProfile else null,
            audio = audioStatus(),
            network = if (streaming) networkStatus() else null,
        )
    }
    // ---- 日志缓冲（进程级；Activity 订阅渲染）----

    private val logLock = Any()
    private val logBuilder = StringBuilder()
    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 当前 Activity 的新增日志回调，销毁时清除 */
    @Volatile
    var logListener: ((String) -> Unit)? = null

    /** 「监听日志」开关（由 Activity 从偏好读取后同步；关闭时不采集不渲染）。 */
    @Volatile
    var logEnabled: Boolean = true

    @Volatile
    private var appContext: Context? = null

    /** WiFi 等待用户操作的窗口（打开 WLAN 开关 / 手动加入热点的轮询上限）。 */
    private const val WLAN_WAIT_TIMEOUT_MS = 120_000L

    /** 加入确认弹窗的应答等待上限（无人应答按中止处理）。 */
    private const val JOIN_DIALOG_TIMEOUT_S = 90L

    /** 首帧、网络确认、稳定窗口和主屏归属均满足后，才退役蓝牙引导链路 */
    private fun startNetworkHandoff(
        client: AirPlayClient,
        video: VideoSender,
    ) {
        val btServer = bluetoothServer
        val btClient = bluetoothClient
        Bg.run("network-ready-handoff") {
            val deadline = android.os.SystemClock.uptimeMillis() + 30_000L
            var nextDiagnostic = 0L
            while (currentClient === client && videoSender === video && video.isRunning && !connectCancelled) {
                val now = android.os.SystemClock.uptimeMillis()
                val firstFrame = video.framesSentTotal > 0L
                val ready = client.networkReady
                val stable = client.networkStable
                val screen = client.phoneOwnsScreen
                if (now >= nextDiagnostic) {
                    appendLog("HANDOFF: 首帧=$firstFrame 网络确认=$ready 稳定=$stable 手机主屏=$screen")
                    nextDiagnostic = now + 5_000L
                }
                if (client.supportsBootstrapHandoff && firstFrame && stable && screen) {
                    if (client.requestBluetoothHandoff()) {
                        synchronized(SessionController) {
                            if (currentClient !== client || videoSender !== video || !video.isRunning || connectCancelled) return@run
                            if (bluetoothServer === btServer) btServer?.detachCurrentSession()
                            if (bluetoothClient === btClient) btClient?.detachCurrentSession()
                            // 保持 sessionActive 门闸，避免引导线程立刻抢连；系统蓝牙及通话连接继续保留
                            appendLog("HANDOFF: 车机已接受交接，蓝牙引导连接已退役，网络视频继续运行")
                            video.requestKeyFrame()
                        }
                    } else {
                        appendLog("HANDOFF: 未获有效确认，继续保留蓝牙引导连接")
                    }
                    return@run
                }
                if (now >= deadline) {
                    appendLog("HANDOFF: 就绪条件未齐备，保留蓝牙；视频及网络会话继续运行")
                    return@run
                }
                try {
                    Thread.sleep(100L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@run
                }
            }
        }
    }

    /** 等待 ctrl 邀请的上限，收到即连接；旧车机发现 AirPlay 服务后也可提前继续 */
    private const val CTRL_INVITE_WAIT_MS = 4_000L

    /** 端点不完整时等 mDNS 缓存命中：绑网时已开始发现，通常几百毫秒内命中。 */
    private const val ENDPOINT_RESOLVE_WAIT_MS = 3_000L

    /** 明文 SETUP 被拒后的补配对窗口，不支持配对时立即结束 */
    private const val PAIRING_UPGRADE_WINDOW_MS = 30_000L

    /** 明文 SETUP 重试窗口，等待旧车机延迟开放端口 */
    private const val INITIAL_SETUP_WINDOW_MS = 30_000L

    /** 等待手机加入车机热点，超时后提示并复位连接状态 */
    private const val CAR_WIFI_BIND_WAIT_MS = 45_000L

    /** 等待 WiFi onAvailable 的上限，留出手动入网和 DHCP 时间 */
    private const val WIFI_BIND_WAIT_S = 15L

    /** 等待用户确认专属网络弹窗，[connectCancelled] 可提前结束 */
    private const val SPECIFIER_JOIN_TIMEOUT_MS = 120_000L

    /** 登记应用上下文并初始化会话对象，可重复调用 */
    fun ensure(context: Context) {
        appContext = context.applicationContext
        ensureSessionObjects(context.applicationContext)
        // 横屏兼容写入失败的原因（user build 限制 / 内置服务故障）只有这里能落盘：
        // 车机侧设置面板的 toast 只给用户看，排查需要日志。
        AppOrientationCompat.diagSink = ::appendLog
        // 主线程 shell 调用写入文件日志，方便排查 ANR
        Shell.violationSink = ::appendLog
    }

    /** 追加日志到文件、缓冲和界面，任意线程可调用 */
    fun appendLog(message: String) {
        // 把 NUL 换成可见字符，避免导出的文本被识别成二进制文件
        val clean =
            io.github.shaonialife.buicklinkplus.util.LogRedactor
                .redact(message.replace(0.toChar().toString(), "␀"))
        android.util.Log.i("CarPlaySender", clean)
        GatewayAutoUpload.offer("APP $clean")
        appContext?.let {
            DebugFileLog
                .append(it, clean)
        }
        if (!logEnabled) return
        val line =
            synchronized(logLock) {
                val stamped = '[' + timestamp.format(Date()) + "] " + clean + '\n'
                logBuilder.append(stamped)
                if (logBuilder.length > 40_000) logBuilder.delete(0, logBuilder.length - 20_000)
                stamped
            }
        // 增量行直送 UI：此前每行 toString 整个缓冲再 setText 全文，
        // 高频日志（触摸/帧）下是 UI 线程与 GC 的双重放大器
        logListener?.invoke(line)
    }

    fun logText(): String = synchronized(logLock) { logBuilder.toString() }

    /** 通过 AudioManager 把媒体键送给活跃会话，音乐卡和方向盘共用此入口 */
    fun dispatchMediaKey(keyCode: Int) {
        val ctx = appContext ?: return
        io.github.shaonialife.buicklinkplus.media.MediaSessionsState
            .dispatchMediaKey(ctx, keyCode, "车机媒体键")
    }

    /** 清空日志缓冲，调用方负责刷新界面，不能用空日志行代替清除事件 */
    fun clearLog() {
        synchronized(logLock) { logBuilder.setLength(0) }
    }

    // ---- 会话对象（进程级所有权；Activity 重建不影响）----

    // 仅持有 applicationContext 的会话资源；退出时停止并清理，不持有 Activity
    @android.annotation.SuppressLint("StaticFieldLeak")
    private var wifiConnector: WifiCarPlayConnector? = null

    /** 当前绑定热点的 SSID，同一热点复用绑定，避免解绑破坏 ctrl 广播 */
    @Volatile
    private var boundCarSsid: String? = null

    /** 本次进程内已询问的 SSID；明确选择另行持久化，超时与取消不写盘 */
    private val forgetPromptHandled =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    /** 串行完成热点检查、确认、入网和登记，重复端点复用已有绑定 */
    private val wifiBindLock = Any()

    /** 双检锁发布字段需使用 volatile */
    @Volatile
    // 仅持有 applicationContext 的会话资源；退出时停止并清理，不持有 Activity
    @android.annotation.SuppressLint("StaticFieldLeak")
    private var bluetoothServer: Iap2BluetoothServer? = null

    // 仅持有 applicationContext 的会话资源；退出时停止并清理，不持有 Activity
    @android.annotation.SuppressLint("StaticFieldLeak")
    private var bluetoothClient: Iap2BluetoothClient? = null

    // 仅持有 applicationContext 的会话资源；退出时停止并清理，不持有 Activity
    @android.annotation.SuppressLint("StaticFieldLeak")
    private var ctrlAdvertiser: CarPlayCtrlAdvertiser? = null

    /** 入网后常驻发现 AirPlay 服务，端点交接时读取缓存 */
    private var endpointBrowser: CarPlayServiceBrowser? = null

    @Volatile
    var videoSender: VideoSender? = null

    // ---- 「显示」运行时生效值（会话建立时快照；英雄卡展示运行时值而非偏好值）----

    /** 本会话流分辨率缩放百分比（按车机分辨率的百分比生成；100=原尺寸）。 */
    @Volatile
    var activeScalePercent: Int = 100

    /** 本会话目标帧率（合成节奏上限；「显示」设置运行中切换可实时生效）。 */
    @Volatile
    var activeTargetFps: Int = 30

    @Volatile
    // 仅持有 applicationContext 的会话资源；退出时停止并清理，不持有 Activity
    @android.annotation.SuppressLint("StaticFieldLeak")
    var audioSender: AudioSender? = null

    /** 只停止使用失效授权的原音频会话 */
    @Synchronized
    fun onProjectionStopped(projection: android.media.projection.MediaProjection) {
        val sender = audioSender ?: return
        if (!sender.usesProjection(projection)) return
        appendLog("AUDIO: 系统投屏授权停止，关闭本轮采集并恢复手机媒体；视频链路继续保留")
        sender.stop()
        if (audioSender === sender) audioSender = null
        phaseHint = "音频投屏授权已被系统停止，请在手机重新授权"
    }

    @Volatile
    var currentClient: AirPlayClient? = null

    @Volatile
    // 仅持有 applicationContext 的会话资源；退出时停止并清理，不持有 Activity
    @android.annotation.SuppressLint("StaticFieldLeak")
    var currentHiddenSource: HiddenDisplaySource? = null

    /** 当前会话的共享密钥与接收端地址：投影授权晚于连接时，动态补启音频用。 */
    @Volatile
    var currentSharedSecret: ByteArray? = null

    @Volatile
    var currentAudioHost: String? = null

    /** 本次会话是否在车机侧播放声音（由「启动连接」弹窗选择）。 */
    @Volatile
    var audioEnabledForSession: Boolean = false

    /**
     * 连接前保存的用户音频策略，本会话期间修改设置不改变正在运行的通道
     */
    @Volatile
    var audioPreference: io.github.shaonialife.buicklinkplus.audio.AudioPreference =
        io.github.shaonialife.buicklinkplus.audio.AudioPreference.AUTO

    /**
     * 展示实际网络，不把进程热点绑定误认为系统上网路径
     */
    fun networkStatus(): String = wifiConnector?.status() ?: "投屏：未连接 · 系统网络尚未初始化"

    /** 分别展示投屏和互联网状态，不包含热点参数 */
    fun networkSnapshot(): WifiCarPlayConnector.Status? = wifiConnector?.statusSnapshot()

    /** 分别展示采集、发送和车机反馈 */
    fun audioStatus(): String {
        val sender = audioSender ?: return "采集：未启动 · 发送：未启动 · 车机反馈：未确认"
        return sender.status() + " · 车机反馈：" + (currentClient?.audioConsumptionStatus ?: "未确认") + " · 扬声器播放：未由本机确认"
    }

    /** 本轮真正用上的音频档位（拆链归因与记忆回写用；null = 本次没有音频）。 */
    @Volatile
    private var activeAudioProfile: AudioProfile? = null

    /** 音频档位记忆的键（车机身份文本，见 [AirPlayClient.receiverIdentity]）。 */
    @Volatile
    private var activeAudioReceiverKey: String = ""

    /** 音频通道真正开始推流的时刻（uptimeMillis）；0 = 未开始。 */
    @Volatile
    private var activeAudioStartedAt: Long = 0L

    /** 音频档位至少存活 20 秒才记录为可用 */
    private const val AUDIO_PROFILE_PROBE_MS = 20_000L

    /** 会话建立互斥（端点交接 → 配对 → 推流全程只允许一条）。 */
    internal val sessionStarting = SessionAttemptGate()

    /** AirPlayClient 创建前收到的迟到端点，配对线程启动时取用并清空 */
    @Volatile
    private var pendingEndpointUpdate: Iap2SenderSession.CarPlayEndpoint? = null

    /** 取消标志，配对线程在各等待点检查 */
    @Volatile
    var connectCancelled: Boolean = false

    /** 当前连接阶段提示，null 时由界面显示默认文案 */
    @Volatile
    var phaseHint: String? = null

    /** 取消连接尝试并停止链路 */
    fun cancelConnecting(context: Context) {
        connectCancelled = true
        appendLog("SESSION: 用户取消连接——正在停止蓝牙/WiFi 链路")
        disconnect(context)
        // 强制释放互斥：配对流程线程可能阻塞在不可中断的连接/mDNS 等待上（如手机
        // 不在热点网段时连热点 IP），finally 遥遥无期——用户显式取消即强制解锁，
        // 否则英雄卡永久「正在连接」（旧流程产出已被 retireSession 清理，无冲突）
        phaseHint = "已取消连接"
    }

    /**
     * 车机最近下发的热点凭据作为整体保存在内存，供手动兼容连接读取
     */
    data class WifiCredentials(
        val ssid: String,
        val passphrase: String,
    )

    @Volatile
    var lastWifiCredentials: WifiCredentials? = null
        private set

    /**
     * 开始新连接或更换目标后同时清除旧热点名称和密码
     */
    fun clearWifiCredentials() {
        lastWifiCredentials = null
    }

    /** 挂屏 UI 钩子：重要连接提示（WiFi 未开启/热点加入限制等）弹给用户看。 */
    @Volatile
    var uiHintSink: ((String) -> Unit)? = null

    /** 弹窗规格（加入热点确认等两按钮询问）。 */
    data class DialogSpec(
        val title: String,
        val message: String,
        val positive: String,
        val negative: String,
    )

    /** 界面的双按钮确认回调：true 为确认，false 为取消，null 为关闭或无应答 */
    @Volatile
    var dialogSink: ((DialogSpec, onChoice: (Boolean?) -> Unit) -> Unit)? = null

    private fun ensureSessionObjects(context: Context) {
        if (bluetoothServer != null) return
        synchronized(this) {
            if (bluetoothServer != null) return
            wifiConnector =
                WifiCarPlayConnector(context) { line -> appendLog(line) }.also { connector ->
                    // 应用专属网络被系统断开（车机熄火/离开范围）：明确告知用户，
                    // 而不是让英雄卡在「已连接」与失败之间静默摇摆
                    connector.onNetworkLost = { ssid ->
                        boundCarSsid = null
                        phaseHint = "车机网络已断开"
                        uiHintSink?.invoke("车机网络已断开，请重新启动连接")
                        appendLog(">>> 车机热点 \"$ssid\" 已断开——本次会话需重新连接")
                    }
                }
            val wifiCredentialHook: (Iap2SenderSession.WifiConfiguration) -> Unit =
                { wifi -> onWifiCredentials(wifi) }
            // 目标车机动态读取：选择后下一轮连接尝试/下一次连入即生效，无需重启链路
            val targetProvider: () -> TargetDevicePrefs.Target? = {
                appContext?.let { TargetDevicePrefs.get(it) }
            }
            val phaseHook: (String) -> Unit = { hint ->
                if (!connectCancelled && !BtSessionGate.sessionActive) phaseHint = hint
            }
            bluetoothServer =
                Iap2BluetoothServer(
                    context,
                    log = { line -> appendLog(line) },
                    onEndpoint = { endpoint -> onCarPlayEndpoint(endpoint, "车机回连") },
                    onWifiCredentials = wifiCredentialHook,
                    onEndpointUpdate = { endpoint -> onLateEndpoint(endpoint, "车机回连") },
                    targetProvider = targetProvider,
                    onPhase = phaseHook,
                )
            bluetoothClient =
                Iap2BluetoothClient(
                    context,
                    log = { line -> appendLog(line) },
                    onEndpoint = { endpoint -> onCarPlayEndpoint(endpoint, "主动连车机") },
                    onWifiCredentials = wifiCredentialHook,
                    onEndpointUpdate = { endpoint -> onLateEndpoint(endpoint, "主动连车机") },
                    targetProvider = targetProvider,
                    onPhase = phaseHook,
                )
            ctrlAdvertiser = CarPlayCtrlAdvertiser(context) { line -> appendLog(line) }
            endpointBrowser =
                CarPlayServiceBrowser(
                    context,
                    log = { line -> appendLog(line) },
                    accept = { info ->
                        // 只接受车机所在网络的端点，避免连到其他 AirPlay 设备
                        // 地址判断同时支持 IPv4、全局 IPv6 和链路本地 IPv6
                        val ip = info.compatHost()?.hostAddress ?: return@CarPlayServiceBrowser false
                        EndpointHosts.isSameNetwork(
                            ip,
                            wifiConnector?.localIpv4Address(),
                            wifiConnector?.localIpv6Addresses().orEmpty(),
                        )
                    },
                )
        }
    }

    /** 状态行文本（Activity 渲染 statusText 用）。 */
    fun statusLine(): String =
        "wifi=${wifiConnector?.currentNetwork != null} " +
            "bt=${bluetoothServer?.isRunning == true} ctrl=${(ctrlAdvertiser?.port ?: -1) > 0}"

    /** 会话是否活跃（推出流进程或 BT 任一在跑）。 */
    fun isSessionRunning(): Boolean =
        videoSender?.isRunning == true ||
            bluetoothServer?.isRunning == true ||
            bluetoothClient?.isRunning == true

    /** 启动前重查环境，再开启保活、广播和蓝牙；已有会话时不重复启动 */
    @Synchronized
    fun startConnection(context: Context) {
        if (SessionCommands.stopping) return
        if (isSessionRunning() || sessionStarting.get()) return
        // 最终入口再次核对，避免权限回调、设置页返回或后续新入口绕过准备检查
        val preparation = ConnectionPreflight.inspect(context, audioEnabledForSession)
        if (!preparation.ready) {
            val missing = preparation.missing.joinToString("、") { it.requirement.title }
            phaseHint = "连接环境未就绪：$missing"
            appendLog("PREFLIGHT: 已阻止连接，缺少：$missing")
            uiHintSink?.invoke("请先完成连接准备：$missing")
            return
        }
        connectCancelled = false
        clearWifiCredentials()
        phaseHint = "正在建立车机蓝牙连接…"
        when (
            ShellServiceGate
                .state(context)
        ) {
            ShellServiceGate.State.READY ->
                appendLog("SERVICE: 就绪（触摸注入可用）")

            else -> {
                phaseHint = "内置服务状态发生变化，请重新检查连接环境"
                appendLog("PREFLIGHT: 启动前内置服务已失去就绪状态，本次未发起连接")
                uiHintSink?.invoke("内置服务已失去就绪状态，请重新检查连接环境")
                return
            }
        }
        CarPlayService
            .start(context)
        // 连接开始就发布 ctrl 能力，换网后由 ensureForNetwork 重新注册
        ctrlAdvertiser?.resetInviteCount()
        // 后台发布：start() 首次要向内置服务解析真实蓝牙 MAC（shell），不能在主线程做
        Bg.run("ctrl-publish") { ctrlAdvertiser?.start() }
        // 真车路径：手机主动连车机（SDP …caff 配件服务）
        // 测试台路径：监听手机侧 iPhone UUID，等 DiPlay/xcertplay 回连
        bluetoothClient?.start()
        bluetoothServer?.start()
        // 记录目标设备的蓝牙音频和通话状态，方便排查连接失败
        Bg.run("bt-profile-diag") {
            val target = appContext?.let { TargetDevicePrefs.get(it) } ?: return@run
            val connected = BtProfiles.connectedNames(context.applicationContext, target.address)
            appendLog(
                "BT-PROFILE: 连接前「${target.name}」的蓝牙配置文件状态=" +
                    (connected.takeIf { it.isNotEmpty() }?.joinToString("/") ?: "未连接"),
            )
        }
    }

    /** 断开进程级会话 */
    @Synchronized
    fun disconnect(context: Context) {
        connectCancelled = true
        sessionStarting.cancel()
        val hadProjection = CarPlayService.mediaProjection != null
        pendingEndpointUpdate = null
        retireSession(reason = "用户断开或取消")
        // 用户断开时关闭 BT socket，让阻塞读取及时退出
        // 配对过程中保留 BT，避免接收端拆掉整个无线连接
        detachActiveBtSessions()
        phaseHint = null
        CarPlayService
            .stop(context)
        bluetoothServer?.stop()
        bluetoothClient?.stop()
        ctrlAdvertiser?.stop()
        endpointBrowser?.stop()
        wifiConnector?.release()
        boundCarSsid = null
        if (hadProjection) {
            // Android 14+：MediaProjection 与保活服务（mediaProjection FGS 类型）绑定，
            // 停服务会连带失效授权——下次连接需重新点「申请投屏权限」
            appendLog("已手动断开并停止保活服务（常亮已恢复）；投屏授权已随服务停止失效，下次连接如需声音请重新申请")
        } else {
            appendLog("已手动断开并停止保活服务（常亮已恢复）")
        }
    }

    /** 结束上一代会话，释放编码器、虚拟屏和链路资源 */
    @Synchronized
    fun retireSession(
        expected: AirPlayClient? = null,
        reason: String = "会话退役",
    ) {
        if (expected != null && currentClient !== expected) return
        if (currentClient == null && videoSender == null && audioSender == null && currentHiddenSource == null) return
        // 先撤销所有权，再停线程，防止旧推流的收尾回调误伤重连中的会话
        val retiringClient = currentClient
        val cause = retiringClient?.termination?.record(reason, android.os.SystemClock.elapsedRealtime())
        lastSessionEnd = "会话=${System.identityHashCode(retiringClient)} 首因=$cause"
        appendLog("SESSION-END: $lastSessionEnd")
        currentClient = null
        val retiringVideo = videoSender
        videoSender = null
        retiringClient?.cancelled = true
        retiringClient?.close()
        retiringVideo?.stop()
        // 暗幕/常亮随会话收掉（本方法在每轮配对起步也会执行，新一轮推流起来后会重新置位）
        ScreenDimmer.setSessionActive(false)
        audioSender?.stop()
        audioSender = null
        currentSharedSecret = null
        currentAudioHost = null
        currentHiddenSource?.release()
        currentHiddenSource = null
        TouchRouter
            .reset()
        // 配对开始也会调用这里，保留 BT 控制连接；用户断开时再释放
        // 会话结束后恢复出站探测
        BtSessionGate.sessionActive = false
        // 后台串行恢复方向覆盖，保证先恢复旧会话，再应用新会话设置
        appContext?.let { ctx -> AppOrientationCompat.resetAllAsync(ctx, ::appendLog) }
    }

    @Volatile private var lastSessionEnd = "尚无结束记录"

    /** 导出带采样时间和会话 ID 的状态快照 */
    fun diagnosticSnapshot(): String =
        "采样经过时间=${android.os.SystemClock.elapsedRealtime()}ms ${sessionStarting.diagnostics()} " +
            "会话=${System.identityHashCode(currentClient)} 输入服务就绪=${CarInputClient.ready} " +
            "地图=${VirtualLauncher.mapDiagnostics()} 音频=${audioStatus()} 上次结束=$lastSessionEnd"

    /** 会话已建立且投影就绪、但音频尚未启动时，动态补启（投影授权晚于连接的场景）。 */
    @Synchronized
    fun maybeStartAudioNow(context: Context) {
        val client =
            currentClient ?: run {
                appendLog("MODE: 投屏已就绪（当前无会话——下次连接时自动启用音频）")
                return
            }
        val secret = currentSharedSecret
        if (secret == null) {
            appendLog("MODE: 明文会话（未配对）无共享密钥——不补启音频")
            return
        }
        if (audioSender?.isRunning == true) return
        appendLog("MODE: 会话进行中，动态补启音频通道…")
        startAudioChannel(context, client, secret, currentAudioHost)
    }

    /** 收到热点凭据后绑定 WiFi 并广播 ctrl，供车机邀请后下发 0x4301 */
    fun onWifiCredentials(wifi: Iap2SenderSession.WifiConfiguration) {
        // 推流时只更新热点凭据，不重新绑网或注册广播，避免打断连接
        if (videoSender?.isRunning == true) {
            lastWifiCredentials = WifiCredentials(wifi.ssid, wifi.passphrase)
            appendLog("冗余车机热点凭据（会话推流中）——保持现有 WiFi 绑定与 ctrl 广播不变")
            return
        }
        lastWifiCredentials = WifiCredentials(wifi.ssid, wifi.passphrase)
        phaseHint = "拿到车机热点凭据，绑定 WiFi…"
        appendLog(">>> 拿到车机热点凭据——立即绑定 WiFi + 广播 _carplay-ctrl")
        // 先登记 SSID 并更新广播，加入热点后还会按新网络重新注册
        ctrlAdvertiser?.ensureForNetwork(wifi.ssid)
        appendLog("CTRL: 广播已在启动连接时发布，此处对齐到热点 ${wifi.ssid}")
        // 绑定与切换统一走自动加入流程：bindCurrentWifi 是「任意 WiFi 即成功」的盲绑，
        // 手机连着公司 WiFi 时会误判已在车机热点（ctrl 广播落在错误网络 → 双方互等死锁），
        // 必须先核对真实 SSID 再决定绑定或切换
        autoJoinCarWifi(wifi)
    }

    /**
     * 通过 shell 检查并加入车机热点，确认 SSID 后再绑定
     * 每步检查取消，失败时引导用户手动加入
     */
    private fun autoJoinCarWifi(wifi: Iap2SenderSession.WifiConfiguration) {
        Bg.run("wifi-auto-join") {
            val shellReady =
                appContext?.let { ShellServiceGate.state(it) == ShellServiceGate.State.READY } == true
            // 1) WLAN 开关。shell 不可用时跳过检查——requestNetwork 会以 Unavailable 反馈。
            if (shellReady && shellWifiDisabled() && !waitForWifiEnabled()) return@run
            if (connectCancelled) return@run
            // 优先使用手机流量时用应用专属网络，并清除系统保存的同名热点
            // 关闭此项则通过系统加入热点，内置服务不可用时提示手动连接
            if (!useCellularForInternet()) {
                appendLog(">>> 「优先使用手机流量」关闭——系统级加入 \"${wifi.ssid}\"，手机上网走车机热点")
                legacyJoinCarWifi(wifi, shellReady)
                return@run
            }
            when (val result = joinCarWifiSpecifier(wifi, shellReady)) {
                is WifiCarPlayConnector.JoinResult.Joined -> Unit

                WifiCarPlayConnector.JoinResult.Unsupported -> {
                    appendLog("WIFI: 热点安全类型 ${wifi.securityType} 无法用应用专属网络表达——退回系统级加入")
                    legacyJoinCarWifi(wifi, shellReady)
                }

                WifiCarPlayConnector.JoinResult.Unavailable -> promptSpecifierFailed(wifi, shellReady)
            }
        }
    }

    /** 串行加入目标热点，已连接时复用绑定，否则请求专属网络；登记和广播在同一锁内完成 */
    private fun joinCarWifiSpecifier(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ): WifiCarPlayConnector.JoinResult =
        synchronized(wifiBindLock) {
            val connector = wifiConnector ?: return@synchronized WifiCarPlayConnector.JoinResult.Unavailable
            if (connectCancelled) return@synchronized WifiCarPlayConnector.JoinResult.Unavailable
            val existing = connector.currentNetwork
            if (boundCarSsid == wifi.ssid && existing != null) {
                appendLog("WIFI: 目标热点已绑定，复用当前网络，不再请求加入")
                return@synchronized WifiCarPlayConnector.JoinResult.Joined(existing)
            }
            // 只复用明确匹配的目标热点，不把任意已连接 Wi-Fi 当作车机网络
            val systemConnected = shellReady && shellWifiStatus().second == wifi.ssid
            // 「优先使用手机流量」开启时自动清除系统保存的车机热点，防止它抢默认网络
            if (shellReady && useCellularForInternet()) {
                autoForgetSavedNetwork(wifi.ssid)
                if (connectCancelled) return@synchronized WifiCarPlayConnector.JoinResult.Unavailable
            }
            // 用户保留（开关关闭或无人应答）且系统仍连着：复用系统连接绑定
            if (systemConnected && shellReady && shellWifiStatus().second == wifi.ssid) {
                appendLog("WIFI: 系统已连接目标热点，保留当前连接并绑定")
                if (bindCarWifiAndRegister(wifi.ssid) && !connectCancelled) {
                    connector.currentNetwork?.let { return@synchronized WifiCarPlayConnector.JoinResult.Joined(it) }
                }
                if (connectCancelled) return@synchronized WifiCarPlayConnector.JoinResult.Unavailable
                appendLog("WIFI: 当前目标热点绑定未成功，继续尝试应用专属网络")
            }
            phaseHint = "正在连接车机网络…"
            appendLog(
                ">>> 以应用专属网络加入 \"${wifi.ssid}\"（不保存为系统 WiFi，系统上网路径由系统决定）",
            )
            val result =
                connector.joinCarPlayNetwork(
                    ssid = wifi.ssid,
                    passphrase = wifi.passphrase,
                    securityType = wifi.securityType,
                    timeoutMillis = SPECIFIER_JOIN_TIMEOUT_MS,
                    isCancelled = { connectCancelled },
                )
            if (result is WifiCarPlayConnector.JoinResult.Joined) {
                boundCarSsid = wifi.ssid
                phaseHint = "已加入车机热点"
                // 成功登记在释放互斥前完成，重复路径才能直接复用而不撤销刚建立的网络
                ctrlAdvertiser?.ensureForNetwork(wifi.ssid, force = true, network = connector.currentNetwork)
                appendLog(">>> WiFi 已加入车机热点（应用专属网络），ctrl 广播已重新注册，等待车机 ctrl 邀请")
            } else if (!connectCancelled) {
                appendLog("WIFI: 应用专属网络加入未成功（${result.javaClass.simpleName}）")
            }
            result
        }

    /** 读取手机流量优先设置，实际默认网络仍由系统决定 */
    private fun useCellularForInternet(): Boolean =
        appContext
            ?.getSharedPreferences("settings", Context.MODE_PRIVATE)
            ?.getBoolean("useCellularForInternet", true)
            ?: true

    /** 开启手机流量优先时，清除系统保存的车机热点，避免自动连接抢占默认网络 */
    private fun autoForgetSavedNetwork(ssid: String) {
        if (!forgetPromptHandled.add(ssid)) return
        val ids = savedNetworkIds(ssid)
        if (ids.isEmpty()) return
        appendLog(
            "WIFI: 「优先使用手机流量」开启——自动清除系统保存的 \"$ssid\"（netId=${ids.joinToString(",")}），避免抢占上网网络",
        )
        for (id in ids) {
            val result = Shell.exec("cmd wifi forget-network $id", 5_000L)
            appendLog("WIFI: 清除保存热点 \"$ssid\"（netId=$id ok=${result.ok}）")
        }
    }

    /** 查系统已保存的、SSID 与目标完全一致的网络 id（`cmd wifi list-networks`）。 */
    private fun savedNetworkIds(ssid: String): List<Int> {
        val listing = Shell.exec("cmd wifi list-networks", 5_000L)
        if (!listing.ok) {
            appendLog("WIFI: 无法读取系统已保存网络（exit=${listing.exitCode}）——跳过检查")
            return emptyList()
        }
        return WifiSavedNetworks.idsOf(listing.output, ssid)
    }

    /** WiFi 未开启时的引导与等待；true = 已开启可继续。 */
    private fun waitForWifiEnabled(): Boolean {
        val open =
            askWifiJoinChoice(
                DialogSpec(
                    title = "WiFi 未开启",
                    message = "连接车机热点需要打开手机 WLAN。是否打开 WLAN 设置？",
                    positive = "打开 WLAN 设置",
                    negative = "取消",
                ),
            )
        if (connectCancelled) return false
        when (open) {
            true -> {
                openWlanSettings()
                uiHintSink?.invoke("请在 WLAN 设置中打开 WiFi 开关")
            }

            false -> {
                phaseHint = "已取消——连接车机热点需要开启 WLAN"
                appendLog("WIFI: 用户取消——自动加入中止（WiFi 未开启）")
                return false
            }

            null -> {
                phaseHint = "WiFi 未开启——连接车机热点需要开启 WLAN"
                appendLog("WIFI: 弹窗无人应答——自动加入中止（WiFi 未开启）")
                return false
            }
        }
        var waited = 0
        while (shellWifiDisabled() && waited < WLAN_WAIT_TIMEOUT_MS && !connectCancelled) {
            if (waited == 0) {
                phaseHint = "等待打开 WLAN（最长 120 秒，取消连接可中止）"
                appendLog("WIFI: 等待用户打开 WLAN（最长 120 秒，取消连接可中止）")
            }
            Thread.sleep(2_000)
            waited += 2_000
        }
        if (connectCancelled) {
            phaseHint = "已取消连接"
            return false
        }
        if (shellWifiDisabled()) {
            phaseHint = "WiFi 未开启——无法连接车机热点"
            appendLog("WIFI: 等待超时 WiFi 仍未开启——自动加入中止，请打开 WLAN 后重新连接")
            return false
        }
        return true
    }

    /** 应用专属网络被系统拒绝/超时：让用户重试或转手动连接。 */
    private fun promptSpecifierFailed(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ) {
        if (connectCancelled) return
        val choice =
            askWifiJoinChoice(
                DialogSpec(
                    title = "未能加入车机热点",
                    message =
                        "系统没有允许连接车机网络（可能点了拒绝，或车机不在范围内）。\n\n" +
                            "「重试」会再次弹出系统确认框。也可以到 WLAN 设置手动连接——" +
                            "手动连接会把热点保存为系统网络，可能影响移动数据。",
                    positive = "重试",
                    negative = "打开 WLAN 设置",
                ),
            )
        if (connectCancelled) return
        when (choice) {
            true -> {
                when (val retry = joinCarWifiSpecifier(wifi, shellReady)) {
                    is WifiCarPlayConnector.JoinResult.Joined -> Unit
                    WifiCarPlayConnector.JoinResult.Unsupported -> legacyJoinCarWifi(wifi, shellReady)
                    WifiCarPlayConnector.JoinResult.Unavailable -> {
                        phaseHint = "未能加入车机热点——请到 WLAN 设置手动连接"
                        appendLog("WIFI: 重试仍未加入 \"${wifi.ssid}\"——请到系统 WLAN 手动连接")
                    }
                }
            }

            false -> manualJoinCarWifi(wifi)

            null -> {
                phaseHint = "未能加入车机热点——请重新连接"
                appendLog("WIFI: 弹窗无人应答——自动加入中止")
            }
        }
    }

    /** 手动兜底：密码进剪贴板 + 跳 WLAN 设置，轮询用户是否连上。 */
    private fun manualJoinCarWifi(wifi: Iap2SenderSession.WifiConfiguration) {
        copyHotspotPsk(wifi.passphrase)
        openWlanSettings()
        phaseHint = "请在 WLAN 设置中连接车机网络…"
        appendLog(
            "WIFI: 用户选择手动连接——等待 WLAN 连上 \"${wifi.ssid}\"（最长 120 秒）；" +
                "注意：手动连接会把该热点保存为系统网络，若它没有互联网可能影响移动数据",
        )
        val manualDeadline = android.os.SystemClock.uptimeMillis() + WLAN_WAIT_TIMEOUT_MS
        while (android.os.SystemClock.uptimeMillis() < manualDeadline) {
            if (connectCancelled) return
            Thread.sleep(5_000)
            if (shellWifiStatus().second == wifi.ssid) {
                bindCarWifiAndRegister(wifi.ssid)
                return
            }
        }
        phaseHint = "尚未连接车机网络，自动加入已中止"
        appendLog("WIFI: 手动连接等待超时——自动加入中止")
    }

    /** WEP 等专属网络不支持的类型通过 cmd wifi 连接，会保存为系统网络 */
    private fun legacyJoinCarWifi(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ) {
        if (!shellReady) {
            phaseHint = "手机未连接车机网络，请在 WLAN 设置中连接"
            appendLog(
                ">>> 手机不在车机热点 \"${wifi.ssid}\" 且内置服务未就绪（无法自动加入）——" +
                    "请到 系统设置>WLAN 手动加入（密码可点「去连接」复制）",
            )
            return
        }
        val current = shellWifiStatus().second
        if (current == wifi.ssid) {
            appendLog("WIFI: 手机已在车机热点 \"$current\"——直接绑定")
            bindCarWifiAndRegister(wifi.ssid)
            return
        }
        if (connectCancelled) return
        val choice =
            askWifiJoinChoice(
                DialogSpec(
                    title = "连接车机热点",
                    message =
                        "将自动连接车机网络。若该网络没有互联网，系统可能自动切回原 Wi-Fi。" +
                            "\n\n如果反复断连，可以到 WLAN 设置中手动连接车机网络。手动连接可能影响移动数据。",
                    positive = "继续自动加入",
                    negative = "打开 WLAN 设置",
                ),
            )
        if (connectCancelled) return
        if (choice == false) {
            manualJoinCarWifi(wifi)
            return
        }
        if (choice == null) {
            phaseHint = "未能加入车机热点——请重新连接"
            appendLog("WIFI: 弹窗无人应答——自动加入中止")
            return
        }
        phaseHint = "正在自动连接车机网络…"
        appendLog(
            ">>> 手机当前 WiFi: ${current ?: "未连接"}（非车机热点）——" +
                "经内置服务自动切换到 \"${wifi.ssid}\"",
        )
        if (connectCancelled) return
        val security =
            if (wifi.passphrase.isEmpty()) "open" else "wpa2 ${shellQuote(wifi.passphrase)}"
        val result =
            Shell
                .exec("cmd wifi connect-network ${shellQuote(wifi.ssid)} $security", 10_000L)
        if (!result.ok) {
            phaseHint = "自动连接失败，请在 WLAN 设置中连接车机网络"
            appendLog(
                "WIFI: connect-network 失败（${result.output.take(80).ifEmpty { "exit=${result.exitCode}" }}）" +
                    "——请到 系统设置>WLAN 手动加入（密码可点「去连接」复制）",
            )
            return
        }
        phaseHint = "正在连接车机网络…若反复断开，请在 WLAN 中手动连接"
        appendLog("WIFI: connect-network 已发起（自动保存网络，等待切换…）")
        // 系统切换是异步的（断开当前网/扫描/关联/DHCP，2-10 秒）：轮询 SSID 确认。
        repeat(20) {
            if (connectCancelled) {
                appendLog("WIFI: 自动加入已取消（用户断开）")
                return
            }
            val ssid = shellWifiStatus().second
            if (ssid == null) {
                // status 命令不可用（非 AOSP 系统）：退化为直接尝试绑定（无 SSID 校验）
                bindCarWifiAndRegister(wifi.ssid)
                return
            }
            if (ssid == wifi.ssid) {
                appendLog("WIFI: 已切到车机热点 ${wifi.ssid}")
                bindCarWifiAndRegister(wifi.ssid)
                return
            }
            Thread.sleep(800)
        }
        phaseHint = "车机网络连接未能保持，请在 WLAN 设置中手动连接"
        uiHintSink?.invoke("车机网络连接未能保持，请在 WLAN 设置中手动连接")
        appendLog(
            ">>> WiFi 自动加入超时（可能被系统切回原 WiFi）——" +
                "请到 系统设置>WLAN 手动连接 \"${wifi.ssid}\"（手动连接不会被自动切回）",
        )
    }

    /**
     * 串行绑定车机 WiFi 并重注册 ctrl，等待期间支持取消
     *
     * @return 进程网络是否绑定成功
     */
    private fun bindCarWifiAndRegister(ssid: String): Boolean {
        var bound = false
        synchronized(wifiBindLock) {
            // 另一个路径已经绑定同一热点：别再走一遍 release+requestNetwork
            if (boundCarSsid == ssid && wifiConnector?.validNetwork(ssid) != null) {
                bound = true
            } else {
                if (connectCancelled || shellWifiStatus().second != ssid) {
                    appendLog("WIFI: 当前热点未确认匹配目标或连接已取消，跳过系统网络绑定")
                    return false
                }
                val latch = java.util.concurrent.CountDownLatch(1)
                wifiConnector?.bindCurrentWifi(ssid) { ok ->
                    bound = ok
                    latch.countDown()
                }
                // 分片等待：期间用户取消要能立刻退出，不必等满窗口
                val deadline = android.os.SystemClock.uptimeMillis() + WIFI_BIND_WAIT_S * 1_000
                while (!connectCancelled &&
                    bound.not() &&
                    android.os.SystemClock.uptimeMillis() < deadline
                ) {
                    if (latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) break
                }
                // 绑定期间系统可能切网，核验不一致时不能继续向其他热点建立会话
                if (bound && (connectCancelled || shellWifiStatus().second != ssid)) {
                    wifiConnector?.release()
                    bound = false
                    appendLog("WIFI: 绑定期间目标热点发生变化或连接已取消，已撤销本次绑定")
                } else if (!bound) {
                    // 返回失败前结束观察，避免迟到回调重新绑定已经放弃的连接
                    wifiConnector?.release()
                    bound = false
                }
                if (bound) boundCarSsid = ssid
            }
        }
        if (bound && !connectCancelled) {
            phaseHint = "已加入车机热点"
            // 关键时机：bindProcessToNetwork 完成后再注册，车机才能看到 ctrl 广播
            ctrlAdvertiser?.ensureForNetwork(ssid, force = true, network = wifiConnector?.currentNetwork)
            appendLog(">>> WiFi 已绑定车机热点，ctrl 广播已重新注册，等待车机 ctrl 邀请（随后才回 0x4301）")
        } else if (!connectCancelled) {
            phaseHint = "手机未连接车机网络，请在 WLAN 设置中连接"
            appendLog(
                ">>> 等待 ${WIFI_BIND_WAIT_S}s 仍未绑定到 \"$ssid\"——" +
                    "请在 系统设置>WLAN 手动加入该热点后重新连接",
            )
        }
        return bound
    }

    /** 等待用户选择、关闭或 90 秒超时；true 为确认，false 为取消，null 为无应答 */
    private fun askWifiJoinChoice(spec: DialogSpec): Boolean? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var choice: Boolean? = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (connectCancelled) {
                latch.countDown()
                return@post
            }
            val sink = dialogSink
            if (sink == null) {
                // 无 UI 宿主（Activity 销毁窗口）：宁可中止也不默认替用户决定加入
                latch.countDown()
                return@post
            }
            sink(spec) { result ->
                choice = result
                latch.countDown()
            }
        }
        val deadline = android.os.SystemClock.uptimeMillis() + JOIN_DIALOG_TIMEOUT_S * 1_000L
        while (!connectCancelled && android.os.SystemClock.uptimeMillis() < deadline) {
            if (latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) return choice
        }
        return null
    }

    private fun openWlanSettings() {
        appContext?.let { ctx ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    ctx.startActivity(
                        android.content
                            .Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }

    /** 热点密码复制到剪贴板（手动到 WLAN 页连接时粘贴），并提示用户。 */
    private fun copyHotspotPsk(passphrase: String) {
        appContext?.let { ctx ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    val cm =
                        ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText("carplay-wifi", passphrase),
                    )
                    uiHintSink?.invoke("热点密码已复制（${passphrase.length} 位）——粘贴到密码框即可")
                }
            }
        }
    }

    /** 通过一次 cmd wifi status 读取 WiFi 开关和当前 SSID，不读取扫描缓存 */
    private fun shellWifiStatus(): Pair<Boolean, String?> {
        val result =
            Shell
                .exec("cmd wifi status", 5_000L)
        if (!result.ok) return true to null
        val enabled = !result.output.contains("Wifi is disabled")
        val ssid = WifiStatus.connectedSsid(result.output)
        return enabled to ssid
    }

    private fun shellWifiDisabled(): Boolean = !shellWifiStatus().first

    /** 断开当前 iAP2 控制链路，供需要结束蓝牙控制后才响应 RTSP 的接收端使用 */
    private fun detachActiveBtSessions() {
        bluetoothServer?.detachCurrentSession()
        bluetoothClient?.detachCurrentSession()
    }

    /**
     * 等待 ctrl 邀请，已发现 AirPlay 端点时可提前继续
     *
     * @return 是否收到邀请或发现端点
     */
    private fun awaitCtrlInvite(
        ssid: String,
        timeoutMillis: Long,
    ): Boolean {
        val advertiser = ctrlAdvertiser
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMillis
        val startedAt = android.os.SystemClock.uptimeMillis()
        var lastLogAt = startedAt
        var reannounced = false
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (connectCancelled) return false
            val invites = advertiser?.inviteCount ?: 0
            if (invites > 0) {
                appendLog(
                    "CTRL: ✓ 收到车机邀请（${android.os.SystemClock.uptimeMillis() - startedAt}ms，第 $invites 次）" +
                        "——立即连接 AirPlay",
                )
                return true
            }
            if (endpointBrowser?.awaitEndpoint(0) != null) {
                appendLog(
                    "CTRL: 已发现 _airplay._tcp（未收到邀请，旧机型行为）——直接连接 AirPlay",
                )
                return true
            }
            val now = android.os.SystemClock.uptimeMillis()
            // 等待中途补发一次广播，补上绑网瞬间可能丢失的公告
            if (!reannounced && now - startedAt >= 2_000) {
                reannounced = true
                appendLog("CTRL: 2s 未收到邀请——强制重新公告一次（catplay 同款 force_announce）")
                ctrlAdvertiser?.ensureForNetwork(ssid, force = true, network = wifiConnector?.currentNetwork)
            }
            if (now - lastLogAt > 1_200) {
                appendLog("CTRL: 等待车机 ctrl 邀请…（已等 ${now - startedAt}ms）")
                lastLogAt = now
            }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return false
            }
        }
        appendLog("CTRL: ${timeoutMillis}ms 未收到邀请——按超时继续连接（可能车机走旧发现路径）")
        return false
    }

    // ---- 以下方法体自 MainActivity 原样迁入（会话逻辑不依赖 Activity）----

    /**
     * 按用户选择协商音频，启动播放采集和 RTP 加密推流
     *
     * @return 音频通道是否已启动，供调用方决定是否补发 RECORD
     */
    @Synchronized
    fun startAudioChannel(
        context: Context,
        client: AirPlayClient,
        sharedSecret: ByteArray?,
        host: String?,
    ): Boolean {
        // 与退役串行，防止视频已结束后迟到的音频 SETUP 又创建一套发送管线
        if (currentClient !== client || client.cancelled) return false
        if (!audioEnabledForSession) {
            appendLog("AUDIO: 本次连接未开启车机侧声音（「启动连接」弹窗选择）——仅推视频")
            return false
        }
        if (sharedSecret == null) {
            // 明文会话没有可派生的音频共享密钥，不启动加密 RTP
            appendLog("AUDIO: 明文会话（未配对）无共享密钥——跳过音频通道")
            return false
        }
        if (host == null) {
            appendLog("AUDIO: 无接收端可用地址，跳过音频通道")
            return false
        }
        val projection = CarPlayService.mediaProjection
        return try {
            // 车机身份 → 只用于「本车机哪些音频档位踩过雷」的持久化记忆。
            val identity = client.receiverIdentity()
            // 音频封装参数已定案（见 AudioTuning），不再提供切换
            val tuning = AudioTuning.RAW_SPF
            var entry = AudioProfileMemory.load(context.applicationContext, identity)
            if (entry.disabled) {
                // 用户主动开启声音时清除旧的禁用记录，重新尝试音频档位
                AudioProfileMemory.clearAll(context.applicationContext)
                entry = AudioProfileMemory.Entry()
                appendLog("AUDIO: 本车机（$identity）有「全部档位拆链」的旧记忆，已自动清除并重试")
            }
            val candidates =
                client.audioCandidates(
                    preferCompat =
                        audioPreference == io.github.shaonialife.buicklinkplus.audio.AudioPreference.COMPATIBILITY,
                )
            val ordered =
                io.github.shaonialife.buicklinkplus.audio.AudioPreference.order(
                    candidates,
                    AudioProfileMemory.order(candidates, entry),
                    audioPreference,
                )
            if (ordered.isEmpty()) {
                appendLog("AUDIO: 没有可用音频档位（候选=${candidates.map { it.label }}）——仅推视频")
                return false
            }
            appendLog(
                "AUDIO: 音频档位候选=${ordered.map { it.label }}" +
                    "（策略=${audioPreference.title}；本车机记忆：${AudioProfileMemory.describe(entry)}；" +
                    "封装=${tuning.detail}）",
            )
            // SETUP 失败不会扰动会话（车机只是不接这条流的请求），所以本会话内就把候选
            // 试完，不必等下一次连接。这一点对当前场景很关键：102/AAC-LC 与 100/PCM 是
            // 两条完全不同的通路，前者不被接受时应当立刻退到后者，而不是让用户白连一次。
            var chosen: AudioProfile? = null
            var streamInfo: AirPlayClient.AudioStreamInfo? = null
            for (candidate in ordered) {
                val result = client.runAudioStream(sharedSecret, candidate, tuning)
                if (result != null) {
                    chosen = candidate
                    streamInfo = result
                    break
                }
                // SETUP 被拒可能是暂时故障，不据此禁用车机音频
                AudioProfileMemory.recordRejected(
                    context.applicationContext,
                    identity,
                    candidate.key,
                    ordered.map { it.key }.toSet(),
                    markExhausted = false,
                )
                appendLog("AUDIO: 档位 ${candidate.label} 协商失败——本会话继续试下一档")
            }
            val profile = chosen
            val audio = streamInfo
            if (profile == null || audio == null) {
                appendLog("AUDIO: 全部候选档位协商失败（共 ${ordered.size} 档）——本次仅推视频")
                return false
            }
            activeAudioProfile = profile
            activeAudioReceiverKey = identity
            activeAudioStartedAt = android.os.SystemClock.uptimeMillis()
            val sender =
                AudioSender(
                    host = host,
                    dataPort = audio.dataPort,
                    streamKey = audio.streamKey,
                    log = { line -> appendLog(line) },
                    context = context.applicationContext,
                    sampleRate = audio.sampleRate,
                    codec = profile.codec,
                    streamType = profile.streamType,
                    adtsFrames = tuning.adts,
                    // 同步包发往 SETUP 返回的 controlPort，给车机提供 RTP 与 NTP 的对应关系
                    controlPort = audio.controlPort,
                )
            audioSender = sender
            if (!sender.start(projection)) {
                audioSender = null
                activeAudioProfile = null
                activeAudioStartedAt = 0L
                false
            } else {
                true
            }
        } catch (error: Exception) {
            appendLog("AUDIO: 通道启动异常 - ${error.message}")
            audioSender = null
            activeAudioProfile = null
            activeAudioStartedAt = 0L
            false
        }
    }

    /** 记录音频存活时间；整条会话过早结束时不禁用音频档位，避免误判 */
    private fun attributeAudioTeardown() {
        val profile = activeAudioProfile ?: return
        val startedAt = activeAudioStartedAt
        val identity = activeAudioReceiverKey
        val lived = if (startedAt > 0) android.os.SystemClock.uptimeMillis() - startedAt else -1L
        // 本轮记忆很快失效：下次连接重新按最新记忆选档
        activeAudioProfile = null
        activeAudioStartedAt = 0L
        if (startedAt == 0L || identity.isBlank()) return
        val ctx = appContext?.applicationContext
        if (lived >= AUDIO_PROFILE_PROBE_MS) {
            AudioProfileMemory.recordWorking(ctx, identity, profile.key)
            appendLog("AUDIO: 档位 ${profile.label} 通道存活 ${lived / 1000}s——保留存活候选，扬声器播放尚未确认")
            return
        }
        appendLog(
            "AUDIO: 档位 ${profile.label} 起流 ${lived / 1000}s 后会话结束——" +
                "无法仅凭整链断开确定音频不兼容，本次不改写拒绝名单",
        )
    }

    /** 先合并 mDNS 地址缓存，再决定是否等待或使用网关兜底，保留 IPv4 和 IPv6 候选 */
    private fun resolveEndpointHost(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ): Iap2SenderSession.CarPlayEndpoint {
        var effective = endpoint
        val cached = endpointBrowser?.awaitEndpoint(0)
        if (cached != null) {
            effective = mergeBrowserEndpoint(effective, cached, source, needPort = effective.airPlayPort <= 0)
        }
        val needHost = !EndpointHosts.hasUsable(effective.ipAddresses)
        val needPort = effective.airPlayPort <= 0
        if (!needHost && !needPort) return effective

        phaseHint = "mDNS 解析车机地址…"
        appendLog(">>> [$source] 0x4301 端点不完整（port=${effective.airPlayPort} ips=${effective.ipAddresses}），取 mDNS 车机 _airplay._tcp…")
        // 用常驻发现器的缓存（绑网时已开始后台发现，通常此时已命中）。
        // 此前现起一次发现并硬重试 3×6s：车机 mDNS 稳定时白等，不稳定时又拖满 18s。
        // 网段过滤（防误连同网 macOS 接收器）由 browser 的 accept 统一做。
        val info =
            endpointBrowser?.awaitEndpoint(ENDPOINT_RESOLVE_WAIT_MS) ?: run {
                // mDNS 没有结果时，尝试热点网关的 7000 端口
                val gateway = wifiConnector?.gatewayAddress()
                if (gateway != null && EndpointHosts.candidates(listOf(gateway)).isNotEmpty()) {
                    appendLog(">>> [$source] mDNS 未发现，兜底使用网关地址 $gateway:7000（车机热点网关=车机）")
                    return effective.copy(
                        ipAddresses = listOf(gateway) + effective.ipAddresses,
                        airPlayPort = if (needPort) 7000 else effective.airPlayPort,
                    )
                }
                appendLog(">>> [$source] mDNS 未发现车机 AirPlay 服务（车机可能尚未启动无线会话）")
                return effective
            }
        return mergeBrowserEndpoint(effective, info, source, needPort)
    }

    /** 将 mDNS 地址并入候选，其余字段只补缺失值，不覆盖 0x4301 */
    private fun mergeBrowserEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        info: android.net.nsd.NsdServiceInfo,
        source: String,
        needPort: Boolean,
    ): Iap2SenderSession.CarPlayEndpoint {
        var result = endpoint
        val address = info.compatHost()?.hostAddress
        if (address != null &&
            EndpointHosts.candidates(listOf(address)).isNotEmpty() &&
            result.ipAddresses.none { EndpointHosts.stripScope(it) == EndpointHosts.stripScope(address) }
        ) {
            result = result.copy(ipAddresses = listOf(address) + result.ipAddresses)
            appendLog(">>> [$source] mDNS 解析到车机地址 $address:${info.port}")
        }
        if (needPort && info.port > 0) {
            result = result.copy(airPlayPort = info.port)
        }
        // TXT 公钥兜底（pair-verify 校验用；0x4301 缺失时只能从这里拿）
        if (result.publicKey == null) {
            txtValue(info, "pk")?.let { result = result.copy(publicKey = it) }
        }
        if (result.deviceIdentifier == null) {
            txtValue(info, "deviceid")?.let { result = result.copy(deviceIdentifier = it) }
        }
        if (result.sourceVersion == null) {
            txtValue(info, "srcvers")?.let { result = result.copy(sourceVersion = it) }
        }
        if (result.advertisedFeatures == null) {
            AirPlayFeatures.parse(txtValue(info, "features"))?.let {
                result = result.copy(advertisedFeatures = it)
            }
        }
        return result
    }

    /** 处理迟到端点：推流中忽略，配对中更新或暂存，上一轮失败则重新交接 */
    private fun onLateEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ) {
        if (connectCancelled) {
            appendLog(">>> [$source] 0x4301 晚到（已取消连接）——忽略")
            return
        }
        if (videoSender?.isRunning == true) {
            appendLog(">>> [$source] 0x4301 晚到（推流中）——忽略冗余端点")
            return
        }
        val client = currentClient
        if (sessionStarting.get()) {
            if (client != null && client.pairingInProgress) {
                // 晚到的 0x4301 可能缺地址，只补充字段，不覆盖已解析的端点
                val merged = preferEndpoint(endpoint, client.currentEndpoint)
                val changed = client.updateEndpoint(merged, "0x4301 晚到")
                appendLog(
                    if (changed) {
                        ">>> [$source] 0x4301 晚到（配对进行中）——端点已热更新，下一轮重试用新地址"
                    } else {
                        ">>> [$source] 0x4301 晚到（配对进行中）——端点无变化，忽略"
                    },
                )
            } else {
                pendingEndpointUpdate = endpoint
                appendLog(">>> [$source] 0x4301 晚到（交接尚未创建配对客户端）——暂存，配对起步时采用")
            }
            return
        }
        // 用户已断开（BT 组件已停）时不得用晚到消息复活会话
        if (bluetoothClient?.isRunning != true && bluetoothServer?.isRunning != true) {
            appendLog(">>> [$source] 0x4301 晚到（蓝牙链路已退役）——忽略")
            return
        }
        appendLog(">>> [$source] 0x4301 晚到（上一轮已结束）——按新端点重走一轮连接")
        onCarPlayEndpoint(endpoint, "$source/0x4301 晚到")
    }

    /** 把交接期间暂存的晚到端点并入新建的客户端（建 client 与收到 0x4301 的竞态窗口）。 */
    private fun drainPendingEndpoint(client: AirPlayClient) {
        val late = pendingEndpointUpdate ?: return
        pendingEndpointUpdate = null
        if (client.updateEndpoint(preferEndpoint(late, client.currentEndpoint), "交接期间晚到 0x4301")) {
            appendLog("AIRPLAY: 交界窗口的 0x4301 端点已并入配对客户端")
        }
    }

    /** 配对重试前只读发现缓存并补全端点，无更新返回 null，不阻塞 */
    private fun enrichEndpointFromBrowser(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ): Iap2SenderSession.CarPlayEndpoint? {
        val info = endpointBrowser?.awaitEndpoint(0) ?: return null
        var result = endpoint
        val address = info.compatHost()?.hostAddress
        if (address != null &&
            EndpointHosts.candidates(listOf(address)).isNotEmpty() &&
            result.ipAddresses.none { EndpointHosts.stripScope(it) == EndpointHosts.stripScope(address) }
        ) {
            result = result.copy(ipAddresses = listOf(address) + result.ipAddresses)
        }
        if (result.airPlayPort <= 0 && info.port > 0) {
            result = result.copy(airPlayPort = info.port)
        }
        if (result.publicKey == null) {
            txtValue(info, "pk")?.let { result = result.copy(publicKey = it) }
        }
        if (result.deviceIdentifier == null) {
            txtValue(info, "deviceid")?.let { result = result.copy(deviceIdentifier = it) }
        }
        if (result.sourceVersion == null) {
            txtValue(info, "srcvers")?.let { result = result.copy(sourceVersion = it) }
        }
        if (result.advertisedFeatures == null) {
            AirPlayFeatures.parse(txtValue(info, "features"))?.let {
                result = result.copy(advertisedFeatures = it)
            }
        }
        if (result == endpoint) return null
        appendLog(
            ">>> [$source] 配对期间 mDNS 刷新端点 → " +
                "${result.ipAddresses.firstOrNull()}:${result.airPlayPort}",
        )
        return result
    }

    private fun txtValue(
        info: android.net.nsd.NsdServiceInfo,
        key: String,
    ): String? =
        info.attributes[key]
            ?.toString(Charsets.UTF_8)
            ?.trimEnd { it.code == 0 }
            ?.takeIf { it.isNotEmpty() }

    /** 迟到端点的端口和公钥优先，地址与现有候选取并集 */
    private fun preferEndpoint(
        late: Iap2SenderSession.CarPlayEndpoint,
        fallback: Iap2SenderSession.CarPlayEndpoint,
    ): Iap2SenderSession.CarPlayEndpoint =
        late.copy(
            airPlayPort = if (late.airPlayPort > 0) late.airPlayPort else fallback.airPlayPort,
            ipAddresses = (late.ipAddresses + fallback.ipAddresses).distinct(),
            publicKey = late.publicKey ?: fallback.publicKey,
            deviceIdentifier = late.deviceIdentifier ?: fallback.deviceIdentifier,
            sourceVersion = late.sourceVersion ?: fallback.sourceVersion,
            advertisedFeatures = late.advertisedFeatures ?: fallback.advertisedFeatures,
        )

    /** 已配对会话 SETUP 失败后直接收尾；明文会话在窗口内每 2 秒重连重试 */
    private fun runInitialSetup(
        client: AirPlayClient,
        plainSession: Boolean,
        onRepaired: (io.github.shaonialife.buicklinkplus.airplay.PairVerifyClient.ControlKeys) -> Unit,
    ): AirPlayClient.SetupInfo? {
        val deadline = android.os.SystemClock.elapsedRealtime() + INITIAL_SETUP_WINDOW_MS
        val first = client.runSetup(deadline - android.os.SystemClock.elapsedRealtime())
        if (first != null) return first
        val failure = client.lastSetupFailure
        val retry =
            io.github.shaonialife.buicklinkplus.airplay.SetupRetryPolicy.canRetry(
                failure,
                client.initialSetupAttempts,
                deadline - android.os.SystemClock.elapsedRealtime(),
            )
        client.close()
        appendLog("AIRPLAY: 初始SETUP失败 分类=$failure 完整重试=$retry 次数=${client.initialSetupAttempts}")
        if (!retry || connectCancelled || currentClient !== client) return null
        try {
            Thread.sleep(2_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        if (connectCancelled || currentClient !== client) return null
        val remaining = deadline - android.os.SystemClock.elapsedRealtime()
        if (remaining <= 0) return null
        if (plainSession) {
            if (!client.reconnect(minOf(remaining, 2_000).toInt())) return null
        } else {
            val repaired = client.runPairing(remaining) ?: return null
            if (connectCancelled || currentClient !== client) {
                client.close()
                return null
            }
            onRepaired(repaired)
        }
        if (connectCancelled || currentClient !== client || android.os.SystemClock.elapsedRealtime() >= deadline) return null
        return client.runSetup(deadline - android.os.SystemClock.elapsedRealtime()).also { if (it == null) client.close() }
    }

    /** 蓝牙端点交接后依次绑定 WiFi、配对、SETUP 和推流 */
    private fun onCarPlayEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
        expectedAttempt: Long? = null,
    ) {
        val context = appContext ?: return
        // 推流期间忽略重复端点，避免重新配对拆掉正在使用的会话
        if (videoSender?.isRunning == true) {
            appendLog(">>> [$source] 会话推流中——忽略冗余端点（接收端周期重连的重复 0x4301）")
            return
        }
        if (connectCancelled) return
        val attempt =
            synchronized(SessionController) {
                if (connectCancelled ||
                    videoSender?.isRunning == true ||
                    (expectedAttempt != null && !sessionStarting.owns(expectedAttempt))
                ) {
                    return
                }
                sessionStarting.begin()?.also { pendingEndpointUpdate = null }
            } ?: run {
                appendLog(">>> [$source] 已有会话在处理中，忽略本次端点（等待上一轮结束）")
                return
            }

        fun currentAttempt() = sessionStarting.owns(attempt) && !connectCancelled
        val continued = AtomicBoolean(false)
        val networkRecoveryNeeded = AtomicBoolean(false)
        // 新一轮交接开始：清掉上一轮（可能中途失败）遗留的晚到端点暂存，
        // 否则它会在本轮的 drain 阶段覆盖掉本会话刚解析出的端点。
        appendLog(
            ">>> [$source] 拿到接收端热点：SSID=${endpoint.ssid}；" +
                "密码=${if (endpoint.passphrase.isEmpty()) "(空)" else "已获取"}",
        )
        phaseHint = "蓝牙已连上车机，解析热点地址…"
        // 新协议只在 0x4301 给密码，也必须供手动加入热点入口使用
        lastWifiCredentials = WifiCredentials(endpoint.ssid, endpoint.passphrase)
        // 记录端点地址和配对能力，方便排查老车机连接失败
        val addresses = endpoint.ipAddresses
        val hasIpv4 = addresses.any { EndpointHosts.isIpv4(it) }
        val pairingFlag = AirPlayFeatures.supportsPairing(endpoint.advertisedFeatures)
        appendLog(
            ">>> [$source] 端点诊断：地址=${addresses.ifEmpty { listOf("(无)") }}" +
                "（${if (hasIpv4) {
                    "IPv4"
                } else if (addresses.isEmpty()) {
                    "无地址"
                } else {
                    "仅 IPv6"
                }}）" +
                " features=" + (endpoint.advertisedFeatures?.let { "0x${it.toString(16)}" } ?: "未知") +
                " HomeKit配对=" +
                when (pairingFlag) {
                    true -> "声明支持"
                    false -> "未声明（按 pre-HomeKit 明文会话处理）"
                    null -> "未知（按需配对）"
                },
        )
        // 车机画像（信号收口点）：mDNS 侧信号到达——era 判定 + 依据一行可解释
        appendLog(
            ReceiverProfile.describe(
                ReceiverProfile.classify(
                    ReceiverProfile.Signals(
                        features = endpoint.advertisedFeatures,
                        srcvers = endpoint.sourceVersion,
                    ),
                ),
            ),
        )
        // 先绑定车机网络，再解析端点，避免读到旧网络结果
        // 绑定后更新广播、启动发现并等待车机邀请
        val continueWithNetwork: (Boolean) -> Unit = proceed@{ bound ->
            if (!currentAttempt() || !continued.compareAndSet(false, true)) return@proceed
            if (!bound) {
                phaseHint = "手机未连接车机网络，请在 WLAN 设置中连接"
                appendLog(">>> [$source] 手机当前不在该热点——请到 设置>WLAN 加入 \"${endpoint.ssid}\" 后断开重连")
                sessionStarting.finish(attempt)
                return@proceed
            }
            // 网络绑定完成：ctrl 广播重新注册（落到车机所在网络），同时开始后台 mDNS 发现。
            // 两者都发生在等邀请期间，不额外占用时间。
            ctrlAdvertiser?.ensureForNetwork(endpoint.ssid, force = true, network = wifiConnector?.currentNetwork)
            endpointBrowser?.resetCache()
            endpointBrowser?.start()
            // 0x4301 自带完整地址+端口 = 接收端已明确告知「连这里」，无需等邀请
            // （DiPlay 测试台路径：本轮直接配对，省掉固定等待）。
            // IPv6 也算地址：奥迪 4xxx 的 0x4301 常只给 fe80::（旧判据只看 IPv4，
            // 会把这类端点当成「不完整」，白等 ctrl 邀请再退回网关兜底）。
            val endpointSelfContained =
                endpoint.airPlayPort > 0 && EndpointHosts.hasUsable(endpoint.ipAddresses)
            Bg.run("airplay-pairing") {
                var pipelineClient: AirPlayClient? = null
                try {
                    if (!currentAttempt()) {
                        appendLog("AIRPLAY: 用户已取消——中止配对")
                        sessionStarting.finish(attempt)
                        return@run
                    }
                    if (endpointSelfContained) {
                        appendLog("AIRPLAY: 0x4301 端点完整（${endpoint.ipAddresses.firstOrNull()}:${endpoint.airPlayPort}）——直接配对，不等 ctrl 邀请")
                    } else {
                        // 等 ctrl 邀请（catplay CarPlayCtrlServer：邀请=车机已发现手机且
                        // AirPlay 窗口已开，收到即刻连接）。此前是「固定睡 1.6s 赌它已到」，
                        // 快的时候白等、慢的时候不够；改为事件驱动。
                        awaitCtrlInvite(endpoint.ssid, CTRL_INVITE_WAIT_MS)
                    }
                    // 端点解析放在邀请之后：车机的 `_airplay._tcp` 常在受邀后才上线，
                    // 且这一步最长 3s——必须在后台线程做，不能占住网络回调线程。
                    var effectiveEndpoint = resolveEndpointHost(endpoint, source)
                    if (!currentAttempt()) return@run
                    // 交接流程起步期间车机补发的 0x4301（比 mDNS 合成更权威）在此并入。
                    pendingEndpointUpdate?.let { late ->
                        pendingEndpointUpdate = null
                        effectiveEndpoint = preferEndpoint(late, effectiveEndpoint)
                        appendLog(
                            "AIRPLAY: 并入交接期间晚到的 0x4301 端点 → " +
                                "${effectiveEndpoint.ipAddresses.firstOrNull()}:${effectiveEndpoint.airPlayPort}",
                        )
                    }
                    if (!currentAttempt()) {
                        appendLog("AIRPLAY: 用户已取消——中止配对")
                        sessionStarting.finish(attempt)
                        return@run
                    }

                    val bindingNetwork =
                        wifiConnector?.validNetwork(endpoint.ssid)
                            ?: throw java.io.IOException("配对前车机网络已失效，请重新连接")

                    fun newClient() =
                        AirPlayClient(
                            effectiveEndpoint,
                            log = { line -> appendLog(line) },
                            context = context.applicationContext,
                            carNetwork = bindingNetwork,
                            endpointRefresher = { current -> enrichEndpointFromBrowser(current, source) },
                            retryTick = retry@{ attempt ->
                                if (!currentAttempt()) return@retry
                                // 重试时定期重播 ctrl 并记录 mDNS 状态，方便区分缺少邀请和地址错误
                                ctrlAdvertiser?.ensureForNetwork(
                                    endpoint.ssid,
                                    force = true,
                                    network = wifiConnector?.currentNetwork,
                                )
                                val invites = ctrlAdvertiser?.inviteCount ?: 0
                                val found = endpointBrowser?.awaitEndpoint(0)
                                appendLog(
                                    "AIRPLAY: 重试 #$attempt —— 已重新公告 ctrl；" +
                                        "车机邀请=$invites 次，mDNS _airplay._tcp=" +
                                        (found?.let { "${it.compatHost()?.hostAddress}:${it.port}" } ?: "未发现"),
                                )
                            },
                        ).also { created ->
                            created.onNetworkUnavailable = {
                                if (currentClient === created && currentAttempt()) {
                                    networkRecoveryNeeded.set(true)
                                    created.cancelled = true
                                    wifiConnector?.invalidate(bindingNetwork)
                                    appendLog("AIRPLAY: 原网络确认失效，停止该网络上的配对，收尾后重新入网")
                                }
                            }
                            // 车机媒体键（iAP2 0x6802 通路）：方向盘/面板的上一曲、下一曲。
                            // 放在这里而不是调用点，保证重连重建 client 时也挂得上。
                            created.onMediaKey = { keyCode ->
                                if (currentClient === created && currentAttempt()) {
                                    dispatchMediaKey(keyCode)
                                    appendLog("EVENT: 车机媒体键（iAP2 0x6802）→ keyCode=$keyCode 已提交")
                                }
                            }
                        }
                    synchronized(SessionController) {
                        if (!currentAttempt()) return@run
                        retireSession()
                        BtSessionGate.sessionActive = true
                    }
                    // 端点交接后暂停其他接收端的出站探测，保留 BT 开始配对
                    phaseHint = "AirPlay 配对中…"
                    var client = newClient()
                    synchronized(SessionController) {
                        if (!currentAttempt()) {
                            client.cancelled = true
                            client.close()
                            return@run
                        }
                        currentClient = client
                        pipelineClient = client
                    }
                    drainPendingEndpoint(client)
                    // 明确不支持 HomeKit 配对的老接收端使用明文 SETUP 和 AES-CTR 屏幕流
                    val noPairing =
                        AirPlayFeatures.supportsPairing(client.currentEndpoint.advertisedFeatures) == false
                    var keys: PairVerifyClient.ControlKeys? =
                        if (noPairing) {
                            client.skipPairing("TXT features 未声明 HK_PAIRING_AND_ENCRYPT")
                            client.reconnect()
                            null
                        } else {
                            client.runPairing()
                        }
                    if (!currentAttempt()) {
                        retireSession(client, "连接请求已取消")
                        appendLog("AIRPLAY: 用户已取消——中止配对")
                        sessionStarting.finish(attempt)
                        return@run
                    }
                    // 配对失败后的兜底路径（纯函数决策，真机抓包场景由 PairingFallbackTest 钉死）
                    val fallbackAfterFirstRound =
                        pairingFallback(
                            keysEstablished = keys != null,
                            noPairing = noPairing,
                            pairSetupRejected = client.pairSetupRejected,
                            pairingForbidden = client.pairingForbidden,
                            attemptedPairing = client.attemptedPairing,
                            pairingUnsupported = client.pairingUnsupported,
                        )
                    if (fallbackAfterFirstRound.useBtRetireHack) {
                        // 仅对实际连通过 RTSP 的 DiPlay 尝试断 BT 后重试
                        // 遇到 403 时保留 BT，等待车机重新邀请
                        detachActiveBtSessions()
                        client = newClient()
                        synchronized(SessionController) {
                            if (!currentAttempt()) {
                                client.cancelled = true
                                client.close()
                                return@run
                            }
                            currentClient = client
                            pipelineClient = client
                        }
                        drainPendingEndpoint(client)
                        keys = client.runPairing()
                        if (!currentAttempt()) {
                            retireSession(client, "连接请求已取消")
                            appendLog("AIRPLAY: 用户已取消——中止配对")
                            sessionStarting.finish(attempt)
                            return@run
                        }
                    }
                    // 未配对会话是否回退明文由 pairingFallback 决定，403 时不降级
                    // 重试后可能已拿到密钥，以最新结果为准
                    val plainSession =
                        pairingFallback(
                            keysEstablished = keys != null,
                            noPairing = noPairing,
                            pairSetupRejected = client.pairSetupRejected,
                            pairingForbidden = client.pairingForbidden,
                            attemptedPairing = client.attemptedPairing,
                            pairingUnsupported = client.pairingUnsupported,
                        ).usePlainSession
                    if (plainSession) {
                        phaseHint = "建立明文会话…"
                        appendLog(
                            "AIRPLAY: 配对不可用（${if (client.pairingUnsupported) "接收端不支持" else "配对失败"}）" +
                                "——改用 et=NONE 明文会话 + AES-CTR 屏幕流（catplay 接收端分类 1/2）",
                        )
                        // 配对循环结束时 socket 已关/状态机不可用：SETUP 前必须重连一次。
                        // 失败不直接判死：车机 AirPlay 端口可能还没开，下面会按窗口重试。
                        if (!client.reconnect()) {
                            appendLog("AIRPLAY: 明文会话首次重连未成功——继续按窗口重试 SETUP")
                        }
                    }
                    // keys 是 lambda 内的可变捕获变量：Kotlin 无法智能转换，先固化成不可变值。
                    val pairedKeys = keys
                    if (pairedKeys != null || plainSession) {
                        phaseHint = if (pairedKeys != null) "配对完成，建立 CarPlay 会话…" else "建立明文会话…"
                        if (pairedKeys != null) {
                            appendLog(
                                "AIRPLAY: ✓ 配对完成，控制通道密钥已派生（不记录密钥或前缀）",
                            )
                        }
                        // 已配对 → 共享密钥；明文会话 → null（流加密降级为 AES-CTR 零密钥）
                        var sessionKey = pairedKeys?.sharedSecret
                        var setup = runInitialSetup(client, plainSession) { sessionKey = it.sharedSecret }
                        if (setup == null &&
                            plainSession &&
                            noPairing &&
                            client.initialSetupAttempts < 2 &&
                            currentAttempt() &&
                            currentClient === client
                        ) {
                            // catplay 的「加密升级」路径：只有在**我们主动跳过配对**（TXT 未声明
                            // HK_PAIRING_AND_ENCRYPT）且明文 SETUP 仍失败时才补做。
                            // 反过来（配对先试过且失败）再试一次没有意义，只会多等 30s。
                            val upgraded = client.retryWithPairing(PAIRING_UPGRADE_WINDOW_MS)
                            if (upgraded != null) {
                                sessionKey = upgraded.sharedSecret
                                setup = client.runSetup()
                            }
                        }
                        if (setup != null) {
                            // 保存当前连通端点，供后续补启音频使用，不能再取配对前的旧地址
                            currentSharedSecret = sessionKey
                            currentAudioHost =
                                client.activeHost ?: EndpointHosts.candidates(client.currentEndpoint.ipAddresses).firstOrNull()
                            client.onPhoneScreenReady = {
                                if (currentClient === client) videoSender?.requestKeyFrame()
                            }
                            client.connectEventChannel(sessionKey, setup) { command ->
                                when (command["type"]) {
                                    "hidSendReport" -> {
                                        val uuid = command["uuid"]?.toString()
                                        val report = command["hidReport"] as? ByteArray
                                        // 媒体键按 Consumer 页描述符识别，报告值是 Usage 数组下标
                                        // 不能按长度或 UUID 判断，避免把旋钮和电话报告误当媒体键
                                        val descriptor = uuid?.let { setup.hidDescriptors[it] }
                                        val keyMap =
                                            descriptor?.let(ConsumerKeys::parseDescriptor)
                                        val rawValue =
                                            report
                                                ?.takeIf { it.isNotEmpty() }
                                                ?.let { it[0].toInt() and 0xFF }
                                        val consumerUsage =
                                            when {
                                                // 有描述符：只有 Consumer 设备才解析（非 Consumer → null）
                                                descriptor != null -> keyMap?.let { rawValue?.let(it::usageFor) }
                                                // 描述符缺失（未知车机）：保守回退——仅 1 字节报告，
                                                // 值当 Usage ID 直读
                                                report != null && report.size == 1 -> rawValue?.takeIf { it != 0 }
                                                else -> null
                                            }
                                        val contacts =
                                            if (consumerUsage == null && report != null) {
                                                // 触摸格式按设备描述符判定（Digitizer 页 + 单指/多槽），
                                                // 描述符不可用时按报文长度回退——不依赖车机型号或 uuid
                                                val device = uuid?.let { setup.hidTouchDevices[it] } ?: TouchReports.Device(descriptor)
                                                if (device.targetsDisplay(setup.displayUuid)) {
                                                    device.parse(report, setup.displayWidth, setup.displayHeight)
                                                } else {
                                                    null
                                                }
                                            } else {
                                                null
                                            }
                                        if (consumerUsage != null) {
                                            val keyCode = ConsumerKeys.keyCodeFor(consumerUsage)
                                            if (keyCode != 0) {
                                                dispatchMediaKey(keyCode)
                                                appendLog(
                                                    "EVENT: 车机媒体键 ${ConsumerKeys.nameOf(consumerUsage)}" +
                                                        "（uuid=$uuid usage=0x%02x → keyCode=$keyCode）"
                                                            .format(consumerUsage),
                                                )
                                            } else {
                                                appendLog(
                                                    "EVENT: 车机媒体键 ${ConsumerKeys.nameOf(consumerUsage)}" +
                                                        "（uuid=$uuid usage=0x%02x）无对应 Android 键，忽略"
                                                            .format(consumerUsage),
                                                )
                                            }
                                        } else if (keyMap != null && report != null) {
                                            // Consumer 设备的「无按键」/释放报文（值为 0 或下标越界）：
                                            // 静默计数，避免释放事件刷屏
                                            consumerReleaseCount++
                                        } else if (contacts != null) {
                                            if (touchLogCount <= 30 || touchLogCount % 50 == 0) {
                                                appendLog(
                                                    "EVENT: 触摸（显示像素 ${setup.displayWidth}×${setup.displayHeight}） " +
                                                        contacts.joinToString {
                                                            "#${it.slot}(${it.xPixels},${it.yPixels})${if (it.down) "↓" else "↑"}"
                                                        },
                                                )
                                            }
                                            touchLogCount++
                                            contacts.forEach {
                                                TouchRouter.onContact(
                                                    context.applicationContext,
                                                    it,
                                                )
                                            }
                                        } else if (report != null) {
                                            appendLog(
                                                "EVENT: 其他 HID 报告 uuid=$uuid bytes=${report.size}" +
                                                    " hex=${report.joinToString(" ") { "%02x".format(it) }}",
                                            )
                                        }
                                    }

                                    "iAPSendMessage" -> {
                                        // 事件通道上的 iAP2 虚拟链路字节（车机先发 marker 开场）。
                                        // 喂给链路引擎：握手 → EA 会话 → 触摸 HID。
                                        val params = command["params"] as? Map<*, *>
                                        val data = params?.get("data") as? ByteArray
                                        if (data == null) {
                                            appendLog("EVENT: iAPSendMessage 无 data（params=$params）")
                                        } else {
                                            iapEventMessageCount++
                                            client.onIapEventBytes(data)
                                        }
                                    }

                                    "forceKeyFrame" -> {
                                        // 车机解不出画面时会要 I 帧。必须立即响应：
                                        // 拖到下一个周期 I 帧（1s）车机可能一直卡在等帧状态。
                                        val forced = videoSender?.requestKeyFrame() == true
                                        appendLog(
                                            "EVENT: 车机请求关键帧（forceKeyFrame）→ " +
                                                if (forced) "已强制编码 I 帧" else "编码器未就绪，等周期 I 帧",
                                        )
                                    }

                                    else ->
                                        appendLog(
                                            // 未识别的命令必须带参数：车机的其他私有命令
                                            // 到底要什么，全在 params 里——只打 type 等于没打。
                                            "EVENT: 命令 type=${command["type"]} " +
                                                command.entries
                                                    .filter { it.key?.toString() != "type" }
                                                    .joinToString(" ") { (key, value) ->
                                                        "$key=" +
                                                            when (value) {
                                                                is ByteArray -> value.joinToString("") { "%02x".format(it) }
                                                                else -> value.toString().take(240)
                                                            }
                                                    }.take(600),
                                        )
                                }
                            }
                            // 资源声明（must）：真车对未声明资源的屏幕流 SETUP 直接 400
                            client.assertModes()
                            // HID 输入模式武装：/info hidDevices（奥迪字符串 "0"/"1"，触摸="1"）。
                            // display features=0x08（高保真触摸）已声明，缺这步车机不发 hidSendReport。
                            setup.hidDeviceUuids.forEach { uuid ->
                                client.sendHidSetInputMode(uuid, mode = 0)
                            }
                            val screen = client.runScreenStream(sessionKey, setup.displayUuid)
                            if (screen != null) {
                                // 地址取 client 真正连通的那个（配对期间可能被 mDNS 刷新过）：
                                // 视频数据端口与 keepAlive 都按它建 socket；IPv6 端点也算
                                // 可用地址（EndpointHosts 统一排序：IPv4 → 全局 IPv6 → 链路本地 IPv6）。
                                val host = client.activeHost ?: EndpointHosts.candidates(client.currentEndpoint.ipAddresses).firstOrNull()
                                // 启动 UDP 和 RTSP 心跳；视频数据端口另有独立保活
                                if (host != null && setup.keepAlivePort != null) {
                                    client.startKeepAlive(host, setup.keepAlivePort)
                                }
                                client.startFeedbackLoop()
                                // 屏幕 SETUP 后尽快连接 dataPort，避免等待音频协商触发车机超时
                                if (host != null) {
                                    // 「显示」偏好：流画布/虚拟屏按车机分辨率百分比生成。
                                    // 只在会话建立时生效——编码器与虚拟屏尺寸此时定型；
                                    // 密度同步缩放，逻辑布局（dp）与 100% 完全一致。
                                    val scalePct =
                                        appContext
                                            ?.let {
                                                DisplayPrefs
                                                    .scalePercent(it)
                                            } ?: 100
                                    // 缩放后向下对齐偶数（H.264 要求）：虚拟屏/合成器/编码器
                                    // 三方必须同一尺寸，故在源头对齐（VideoSender 内另有兜底）
                                    val (streamW, streamH) = VideoProfile.scaledSize(setup.displayWidth, setup.displayHeight, scalePct)
                                    val fps =
                                        appContext
                                            ?.let {
                                                DisplayPrefs
                                                    .fps(it)
                                            } ?: 30
                                    activeScalePercent = scalePct
                                    activeTargetFps = VideoProfile.limitFps(fps, setup.maxFps)
                                    appendLog(
                                        "DISPLAY: 流 ${setup.displayWidth}x${setup.displayHeight} → " +
                                            "${streamW}x$streamH（$scalePct%）请求${fps}fps，车机上限${setup.maxFps}fps",
                                    )
                                    videoSender =
                                        VideoSender(
                                            host = host,
                                            dataPort = screen.dataPort,
                                            frameCipher = screen.cipher,
                                            width = streamW,
                                            height = streamH,
                                            targetFps = activeTargetFps,
                                            bitrateMbps = appContext?.let { DisplayPrefs.bitrateMbps(it) } ?: 0,
                                            nativeWidth = setup.displayWidth,
                                            nativeHeight = setup.displayHeight,
                                            log = { line -> appendLog(line) },
                                            keepAliveWithStats = setup.keepAliveSendStatsAsBody,
                                            onEnded = {
                                                synchronized(SessionController) {
                                                    if (currentClient === client) {
                                                        appendLog(
                                                            "SESSION-END: 会话=${System.identityHashCode(client)} " +
                                                                "视频=${videoSender?.activeProfile} 已发送=${videoSender?.framesSentTotal} " +
                                                                "音频=${audioSender?.status()}，发送失败与控制通道结束须按时间顺序关联",
                                                        )
                                                        appendLog(">>> CarPlay 会话结束（推流终止）——退役本轮连接，恢复蓝牙探测")
                                                        attributeAudioTeardown()
                                                        retireSession(client, "视频推流终止")
                                                    }
                                                }
                                            },
                                        )
                                    // 先连端口（车机看门狗），编码器等 RECORD 后再启动
                                    videoSender?.openDataPort()
                                }
                                // 先 RECORD 并起视频，再协商音频，避免屏幕流等待超时
                                // 音频协商失败时仍保留视频
                                client.record()
                                if (host != null) {
                                    val startingVideo = videoSender
                                    val started =
                                        startingVideo?.start { surface, profile ->
                                            synchronized(SessionController) {
                                                check(currentClient === client && videoSender === startingVideo && !client.cancelled) {
                                                    "视频会话已退役"
                                                }
                                                val geometry =
                                                    DisplayGeometry.create(
                                                        setup.displayWidth,
                                                        setup.displayHeight,
                                                        profile.width,
                                                        profile.height,
                                                        setup.safeArea,
                                                        DisplayPrefs.safeMargins(context),
                                                        viewArea = setup.viewArea,
                                                        outsideUiAllowed = setup.outsideUiAllowed,
                                                    )
                                                TouchRouter.setGeometry(geometry)
                                                activeScalePercent = kotlin.math.round(profile.width * 100f / setup.displayWidth).toInt()
                                                activeTargetFps = profile.fps
                                                val baseDensity =
                                                    (420f * minOf(setup.displayWidth, setup.displayHeight) / 540f).coerceIn(120f, 1680f)
                                                val videoDensity =
                                                    (baseDensity * profile.height / setup.displayHeight).toInt().coerceAtLeast(1)
                                                appendLog("DISPLAY: 生效配置=" + profile + " 安全区=" + geometry.content)
                                                // 唯一模式：隐形屏（双屏启动器：dock + 应用屏）
                                                val source =
                                                    HiddenDisplaySource(
                                                        context.applicationContext,
                                                        log = { line -> appendLog(line) },
                                                    )
                                                currentHiddenSource = source
                                                val ok =
                                                    source.attach(
                                                        surface,
                                                        profile.width,
                                                        profile.height,
                                                        videoDensity,
                                                        geometry,
                                                        profile.fps,
                                                    )
                                                val modeStatus =
                                                    if (ok) "已启动 display=${source.displayId}" else "启动失败（内置服务就绪？）"
                                                appendLog("MODE: 隐形屏 $modeStatus")
                                                // 暗幕/常亮只在真正开始投屏后生效（见 ScreenDimmer.sessionActive）：
                                                // 应用启动时只挂触摸探针，避免「会话早断了、手机过一会儿还是变暗」。
                                                check(ok) { "隐形屏启动失败" }
                                                ScreenDimmer.setSessionActive(true)
                                            }
                                        } == true
                                    if (!started && startingVideo?.configurationExhausted == true) {
                                        connectCancelled = true
                                        phaseHint = "编码器无可用配置，请调整分辨率后重新连接"
                                        uiHintSink?.invoke("编码器无可用配置，请调整分辨率后重新连接")
                                        Bg.run("unsupported-video-stop") {
                                            bluetoothClient?.stop()
                                            bluetoothServer?.stop()
                                        }
                                    }
                                    check(started) { "视频启动失败" }
                                }
                                videoSender?.let { startNetworkHandoff(client, it) }
                                // 可选隧道必须排在视频启动之后，不能占用屏幕数据端口的连接窗口
                                // iAP 隧道（type-130）：车机→手机的 iAP2 EA 数据（触摸 HID 载体）。
                                // 建立失败不阻塞会话（触摸不可用但视频照常）。
                                val tunnel = client.runIapTunnel(sessionKey)
                                tunnel?.onIapMessage = { frame ->
                                    // 记录触摸输入，方便核对坐标和路由
                                    if (frame.messageId != 0x0000) {
                                        val hex = frame.body.joinToString(" ") { "%02x".format(it) }
                                        appendLog(
                                            "TUNNEL: rx 0x${frame.messageId.toString(16)} " +
                                                "(${Iap2MessageIds.nameOf(
                                                    frame.messageId,
                                                )}) ${frame.body.size}B" +
                                                if (frame.body.isNotEmpty()) " hex=${hex.take(120)}" else "",
                                        )
                                    }
                                }
                                // 音频通道（可选）：SETUP type=100 → UDP RTP 推流。
                                // 会话已激活（RECORD 完成）后再协商；失败不影响视频主链路。
                                val audioStarted = startAudioChannel(context, client, sessionKey, host)
                                if (audioStarted) {
                                    // 音频流在首次 RECORD 后加入，需要再发一次 RECORD 才会播放
                                    appendLog("AUDIO: 音频流已加入——补发 RECORD 激活（流后加入会话）")
                                    client.record()
                                }
                            }
                        } else {
                            // 会话级 SETUP 被拒（典型：接收端按设备过滤回 403，见
                            // AirPlayClient.runSetup 的 deviceID 说明）。主动关闭控制通道：
                            // 否则接收端要等约 45s 视频超时才拆 BT 链路，用户只看到
                            // 「连上之后自动断开」，无从判断是会话建立失败。
                            phaseHint = "会话建立未通过：${client.lastSetupFailure.title}"
                            appendLog("AIRPLAY: 会话建立失败——主动关闭控制通道，本轮结束")
                            uiHintSink?.invoke("会话建立未通过，请查看日志中的SETUP失败分类")
                            runCatching { client.close() }
                        }
                    }
                } catch (error: Throwable) {
                    // 配对线程任何异常都不能留下「已有会话在处理中」的卡死状态
                    val category =
                        io.github.shaonialife.buicklinkplus.util.FaultCategory
                            .classify(error.message, !currentAttempt())
                    appendLog("AIRPLAY: 配对线程结束 分类=${category.title} - ${error.message ?: error.javaClass.simpleName}")
                } finally {
                    // 配对未成功建立推流（fail/异常/start 失败提前退出）时解除静默降噪，
                    // 否则 BT-C 会永久停在门闸外、连真车路径失效。
                    // 推流活跃时保持关闭，由 VideoSender.onEnded（推流终止）负责解除。
                    synchronized(SessionController) {
                        if (pipelineClient != null && currentClient === pipelineClient && videoSender?.isRunning != true) {
                            appendLog("DISPLAY: 视频未能启动，释放本轮显示与音频资源")
                            retireSession(pipelineClient, "启动未完成")
                        }
                        if (currentAttempt() && videoSender?.isRunning != true) {
                            BtSessionGate.sessionActive = false
                            // 配对收尾时再取一次暂存端点，避免互斥释放前到达的 0x4301 无人处理
                            val pending = pendingEndpointUpdate ?: endpoint.takeIf { networkRecoveryNeeded.get() }
                            if (pending != null && currentAttempt()) {
                                pendingEndpointUpdate = null
                                appendLog(
                                    if (networkRecoveryNeeded.get()) ">>> 原网络失效收尾完成，重新执行车机入网和端点发现" else ">>> 收尾时发现暂存的晚到 0x4301——立即按新端点重走一轮",
                                )
                                Bg.run("late-endpoint-retry") {
                                    onCarPlayEndpoint(pending, "0x4301 晚到（本轮收尾补投）", attempt)
                                }
                            }
                        }
                        sessionStarting.finish(attempt)
                    }
                }
            }
        }
        // 已绑定过网络（autoJoinCarWifi 刚绑好并重注册过）就不要再走一遍加入流程：
        // WifiCarPlayConnector 内部先 release() → bindProcessToNetwork(null)，
        // 会把刚注册好的 ctrl mDNS 广播打断，再等系统重建绑定。
        val wifiConfig =
            Iap2SenderSession.WifiConfiguration(
                endpoint.ssid,
                endpoint.passphrase,
                endpoint.channel,
                endpoint.securityType,
            )
        if (boundCarSsid == endpoint.ssid && wifiConnector?.validNetwork(endpoint.ssid) != null) {
            appendLog(">>> [$source] 已绑定车机热点 ${endpoint.ssid}，跳过重复绑定")
            continueWithNetwork(true)
        } else {
            // 端点先到或上次换网失败时也要确认车机热点，不能直接绑定当前任意 WiFi
            Bg.run("wifi-join-on-endpoint") {
                while (currentAttempt()) {
                    val shellReady =
                        appContext?.let { ShellServiceGate.state(it) == ShellServiceGate.State.READY } == true
                    when (val result = joinCarWifiSpecifier(wifiConfig, shellReady)) {
                        is WifiCarPlayConnector.JoinResult.Joined -> {
                            continueWithNetwork(true)
                            return@run
                        }

                        WifiCarPlayConnector.JoinResult.Unsupported -> {
                            // WEP 等无法用 specifier 表达：等用户在系统 WLAN 里连上后绑定
                            waitForManualWifiThenContinue(wifiConfig) { bound ->
                                continueWithNetwork(bound)
                            }
                            return@run
                        }

                        WifiCarPlayConnector.JoinResult.Unavailable -> {
                            promptSpecifierFailed(wifiConfig, shellReady)
                            // promptSpecifierFailed 内部可能已重试/转手动；两者都会
                            // 通过 joinCarWifiSpecifier → continueWithNetwork 续上流程，
                            // 这里只在彻底失败时结束本轮
                            if (boundCarSsid == wifiConfig.ssid && wifiConnector?.validNetwork(wifiConfig.ssid) != null) {
                                continueWithNetwork(true)
                            } else {
                                sessionStarting.finish(attempt)
                            }
                            return@run
                        }
                    }
                }
                sessionStarting.finish(attempt)
            }
        }
    }

    /** specifier 不可用时的手动兜底：轮询系统 WLAN 是否连上目标热点，而后绑定。 */
    private fun waitForManualWifiThenContinue(
        wifi: Iap2SenderSession.WifiConfiguration,
        onBound: (Boolean) -> Unit,
    ) {
        val deadline = android.os.SystemClock.uptimeMillis() + CAR_WIFI_BIND_WAIT_MS
        var lastHintAt = android.os.SystemClock.uptimeMillis()
        while (android.os.SystemClock.uptimeMillis() < deadline && !connectCancelled) {
            val current = shellWifiStatus().second
            if (current == wifi.ssid) {
                onBound(bindCarWifiAndRegister(wifi.ssid))
                return
            }
            if (android.os.SystemClock.uptimeMillis() - lastHintAt > 5_000) {
                lastHintAt = android.os.SystemClock.uptimeMillis()
                phaseHint = "等待手机连接车机网络…"
                appendLog("WIFI: 等待用户在系统 WLAN 中加入车机热点（当前 ${current ?: "未连接"}）…")
                openWlanSettings()
            }
            Thread.sleep(1_000)
        }
        if (!connectCancelled) {
            phaseHint = "手机未连接车机网络，请在 WLAN 设置中连接"
            appendLog(">>> 等待车机热点超时——请到 设置>WLAN 加入 \"${wifi.ssid}\" 后重新连接")
            uiHintSink?.invoke("未连上车机网络，请在 WLAN 设置中手动连接")
        }
        onBound(false)
    }

    /** 触摸和隧道事件计数，限量写日志 */
    @Volatile
    private var touchLogCount = 0

    /** 车机媒体键「释放」报文计数（值为 0 的 1 字节报告，静默丢弃，仅计数备查）。 */
    @Volatile
    private var consumerReleaseCount = 0

    @Volatile
    private var iapEventMessageCount = 0
}

/** 选择配对失败后的恢复方式 */
internal data class PairingFallback(
    /** DiPlay 兼容重试：结束蓝牙控制后再配对 */
    val useBtRetireHack: Boolean,
    /** et=NONE 明文会话降级（pre-HomeKit 老车机 / 配对全拒但 RTSP 连上过的兜底）。 */
    val usePlainSession: Boolean,
)

/** 配对出现 403 时保留蓝牙并退避重试，避免断链或明文降级打断车机重新邀请 */
internal fun pairingFallback(
    keysEstablished: Boolean,
    noPairing: Boolean,
    pairSetupRejected: Boolean,
    pairingForbidden: Boolean,
    attemptedPairing: Boolean,
    pairingUnsupported: Boolean,
): PairingFallback {
    if (keysEstablished) return PairingFallback(useBtRetireHack = false, usePlainSession = false)
    val useBtRetireHack = !noPairing && !pairSetupRejected && !pairingForbidden && attemptedPairing
    val usePlainSession = !pairingForbidden && (pairingUnsupported || attemptedPairing)
    return PairingFallback(useBtRetireHack = useBtRetireHack, usePlainSession = usePlainSession)
}
