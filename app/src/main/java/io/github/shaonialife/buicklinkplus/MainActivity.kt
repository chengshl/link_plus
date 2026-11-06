package io.github.shaonialife.buicklinkplus

import android.Manifest
import android.animation.ObjectAnimator
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import io.github.shaonialife.buicklinkplus.bt.BtConnectionPrefs
import io.github.shaonialife.buicklinkplus.bt.BtProfiles
import io.github.shaonialife.buicklinkplus.bt.BtSessionGate
import io.github.shaonialife.buicklinkplus.bt.TargetDevicePrefs
import io.github.shaonialife.buicklinkplus.input.TouchRouter
import io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
import io.github.shaonialife.buicklinkplus.launcher.settings.AppOrientationCompat
import io.github.shaonialife.buicklinkplus.launcher.settings.SettingsMenus
import io.github.shaonialife.buicklinkplus.legal.DisclaimerDialog
import io.github.shaonialife.buicklinkplus.legal.DisclaimerPrefs
import io.github.shaonialife.buicklinkplus.legal.needsDisclaimerAtLaunch
import io.github.shaonialife.buicklinkplus.media.NotificationAccess
import io.github.shaonialife.buicklinkplus.phone.PhoneHomeDashboard
import io.github.shaonialife.buicklinkplus.service.CarPlayService
import io.github.shaonialife.buicklinkplus.session.ConnectionPreflight
import io.github.shaonialife.buicklinkplus.session.SessionCommands
import io.github.shaonialife.buicklinkplus.session.SessionController
import io.github.shaonialife.buicklinkplus.shizuku.CarInputClient
import io.github.shaonialife.buicklinkplus.shizuku.PairingService
import io.github.shaonialife.buicklinkplus.shizuku.ServiceActivationActivity
import io.github.shaonialife.buicklinkplus.shizuku.Shell
import io.github.shaonialife.buicklinkplus.shizuku.ShellServiceGate
import io.github.shaonialife.buicklinkplus.ui.DarkMessageDialog
import io.github.shaonialife.buicklinkplus.ui.GlassActivity
import io.github.shaonialife.buicklinkplus.ui.PanelWindow
import io.github.shaonialife.buicklinkplus.ui.PhonePanelUi
import io.github.shaonialife.buicklinkplus.ui.PhonePermissionsView
import io.github.shaonialife.buicklinkplus.ui.PhoneSettingsView
import io.github.shaonialife.buicklinkplus.ui.ScreenDimmer
import io.github.shaonialife.buicklinkplus.ui.UiAppearance
import io.github.shaonialife.buicklinkplus.ui.WallpaperActivity
import io.github.shaonialife.buicklinkplus.ui.WallpaperStore
import io.github.shaonialife.buicklinkplus.ui.setAppearanceBackground
import io.github.shaonialife.buicklinkplus.ui.setAppearanceImageResource
import io.github.shaonialife.buicklinkplus.ui.setAppearanceImageTint
import io.github.shaonialife.buicklinkplus.ui.setAppearanceTextColor
import io.github.shaonialife.buicklinkplus.util.Bg
import io.github.shaonialife.buicklinkplus.util.DeviceIdentity
import io.github.shaonialife.buicklinkplus.util.LogExporter
import java.util.Locale

/**
 * 手机首页和设置入口，每秒刷新连接状态
 * 会话由 [SessionController] 持有，Activity 重建不影响连接
 */
class MainActivity : GlassActivity() {
    /**
     * 已缓存的媒体状态图标按当前主题重新着色，保留连接及动画状态
     */
    override fun onAppearanceChanged() {
        updateAudioTag()
    }

    private enum class HeroState { IDLE, CONNECTING, CONNECTED }

    /** 连接中的默认副标题（无阶段提示时）。 */
    private companion object {
        const val DEFAULT_CONNECTING_HINT = "蓝牙 iAP2 握手 → 加入车机热点 → 建立 CarPlay 会话"
        const val KEY_DISCLAIMER_DEADLINE = "disclaimer_deadline"
    }

    private lateinit var deviceSubtitle: TextView
    private lateinit var shizukuChip: TextView
    private lateinit var shizukuDesc: TextView
    private lateinit var shizukuBadge: TextView
    private lateinit var hubCar: ImageView
    private lateinit var heroStateText: TextView
    private lateinit var btnResidualCleanup: TextView
    private lateinit var heroSubline: TextView
    private lateinit var heroStats: TextView
    private lateinit var heroProgress: View
    private lateinit var ctaButton: LinearLayout
    private lateinit var ctaIcon: ImageView
    private lateinit var ctaLabel: TextView
    private lateinit var audioTag: TextView
    private lateinit var readyCount: TextView
    private lateinit var btDesc: TextView
    private lateinit var btBadge: TextView
    private lateinit var btnPickTarget: TextView
    private lateinit var audioDesc: TextView
    private lateinit var btnAudio: TextView
    private lateinit var switchAlwaysOn: androidx.appcompat.widget.SwitchCompat
    private lateinit var advBody: View
    private lateinit var advChevron: ImageView
    private lateinit var shellStateText: TextView
    private lateinit var logBody: View
    private lateinit var logChevron: ImageView
    private lateinit var switchLog: androidx.appcompat.widget.SwitchCompat
    private lateinit var logText: TextView
    private lateinit var snackbar: TextView

    // ---- 英雄卡状态机（1s 轮询 SessionController 对账）----
    private var heroState: HeroState? = null
    private var connectedAt = 0L
    private var hubBlink: ObjectAnimator? = null

    // 启动残留屏检查进行中（英雄卡显示「检查中」）；发现的残留暂缓清理时
    // 记在 pendingResidualDisplays，状态旁的「清理残留屏」按钮可再次发起
    private var startupCheckRunning = false
    private var pendingResidualDisplays: List<Pair<Int, String>>? = null

    // 英雄卡实时帧率：1s 轮询对 VideoSender 已发帧计数取差值
    private var lastSentFrames = 0L

    /** 免责声明倒计时截止时刻（elapsedRealtime；旋转重建后透传，不重新计时）。 */
    private var disclaimerDeadlineAt = 0L

    private var activeDisclaimer: androidx.appcompat.app.AlertDialog? = null

    /** 激活页面完成后按原意继续连接，快捷设置入口不自动连接 */
    private var pendingServiceThenConnect = false
    private val serviceActivationLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val continueConnection = pendingServiceThenConnect
            pendingServiceThenConnect = false
            if (!isFinishing && !isDestroyed) {
                updateReadyRow()
                updateServiceChip()
                phonePermissions?.refresh()
                if (CarInputClient.ready) {
                    ensureNotificationAccessAsync()
                    if (continueConnection) beginConnectionPreparation()
                }
            }
        }

    /** 身份查询放在后台，查询期间不能重复开启连接 */
    private var connectionCheckRunning = false

    /** 前台恢复检查只保留一个，身份读取不得阻塞页面 */
    private var automaticRestoreCheckRunning = false
    private var lastObservedServiceReady = false

    /** 从选择车机入口申请权限后，只返回选择器，不自动发起连接 */
    private var pendingTargetPickerAfterPermission = false

    /** 录音授权完成后才申请投屏，避免同时弹出两种系统授权 */
    private var pendingProjectionAfterAudioPermission = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val heroTick: Runnable =
        object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                tickHero()
                mainHandler.postDelayed(this, 1_000L)
            }
        }

    /** 订阅日志并回填 SSID，保留正在运行的会话记录 */
    private val uiHintSinkRef: (String) -> Unit = { message ->
        runOnUiThread { if (!isFinishing && !isDestroyed) snack(message, 6_000L) }
    }

    /** 当前打开的「加入热点」确认弹窗：取消连接时联动关闭（触发流程中止）。 */
    private var wifiJoinDialog: androidx.appcompat.app.AlertDialog? = null

    private var phoneSettingsVisible = false
    private var settingsOpenedFromHome = false
    private var homeDashboard: PhoneHomeDashboard? = null
    private var phoneSettings: PhoneSettingsView? = null
    private var phonePermissions: PhonePermissionsView? = null
    private var quickPermissionRequestPending = false
    private var homeHeroScroll: android.widget.ScrollView? = null
    private var phoneConnectionContent: View? = null
    private var soundStatus: TextView? = null
    private var audioTagIconResource = 0
    private var audioTagIconColor = 0

    private val logListener: (String) -> Unit = { line ->
        runOnUiThread {
            if (!isFinishing && !isDestroyed && ::logBody.isInitialized && logBody.isShown) appendConsole(line)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val continueStart = pendingStartAfterBtPermission.getAndSet(false)
            val reopenPicker = pendingTargetPickerAfterPermission
            pendingTargetPickerAfterPermission = false
            log("PREFLIGHT: 附近设备权限结果=$grants")
            if (grants.isNotEmpty() && grants.values.all { it }) {
                if (reopenPicker) {
                    showTargetPicker()
                } else if (continueStart) {
                    beginConnectionPreparation()
                }
            } else if (continueStart || reopenPicker) {
                snack("附近设备权限未完整授予，请在应用设置中允许后重新检查")
            }
        }

    // 权限页的申请结果只刷新页面，不能消费连接流程的继续标志
    private val quickPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            quickPermissionRequestPending = false
            if (isFinishing || isDestroyed) return@registerForActivityResult
            phonePermissions?.refresh()
            snack(
                getString(
                    if (grants.isNotEmpty() && grants.values.all { it }) {
                        R.string.phone_permissions_granted_result
                    } else {
                        R.string.phone_permissions_denied_result
                    },
                ),
            )
        }

    /** 蓝牙权限授权后自动继续启动连接。 */
    private val pendingStartAfterBtPermission =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("MODE: RECORD_AUDIO 授权: $granted（音频通道需要）")
            val resumeProjection = pendingProjectionAfterAudioPermission
            pendingProjectionAfterAudioPermission = false
            if (resumeProjection && granted) {
                requestAudioProjection(pendingStartAfterProjection.get())
            } else if (resumeProjection) {
                val connectAfter = pendingStartAfterProjection.get()
                pendingStartAfterProjection.set(false)
                snack(if (connectAfter) "未授予声音捕获权限，本次未发起连接；可重新选择仅推视频" else "未授予声音捕获权限，可在应用设置中允许后重试")
            }
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode != RESULT_OK || data == null) {
                pendingStartAfterProjection.set(false)
                log("MODE: 投屏权限被拒绝（车机侧声音不可用）")
                return@registerForActivityResult
            }
            // Android 14+ 顺序：先把结果交给服务，由服务升级 FGS 类型后调 getMediaProjection
            CarPlayService.pendingProjectionResult =
                result.resultCode to data
            CarPlayService
                .start(this)
            log("MODE: ✓ 投屏权限已授予（重启手机后需重新授权）")
            // 会话已在进行中授权（授权晚于连接）：等投影就绪后动态补启音频通道
            Bg.run("audio-late-start") {
                val deadline = SystemClock.uptimeMillis() + 5_000L
                while (SystemClock.uptimeMillis() < deadline) {
                    if (CarPlayService.mediaProjection != null) break
                    Thread.sleep(200)
                }
                if (CarPlayService.mediaProjection == null) {
                    pendingStartAfterProjection.set(false)
                    log("MODE: 投影 5 秒未就绪（服务异常？），音频通道本次未启动")
                    return@run
                }
                SessionController.maybeStartAudioNow(applicationContext)
                // 「启动连接」时选了开启声音、投屏授权在此刻才拿到：接着启动连接
                if (pendingStartAfterProjection.compareAndSet(true, false)) {
                    startConnectionNow()
                }
            }
        }

    /** 投屏授权是「启动连接」的前置（选了开启声音且尚未授权）时置位。 */
    private val pendingStartAfterProjection =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /** 连接前选择是否推送音频，默认仅视频；按钮竖排以适应窄屏 */
    private fun showAudioChoiceDialog() {
        // 默认档 = 高质量媒体通路（type=102 + AAC-LC，真 iPhone 走这条）；
        // 「兼容档」= 强制只走 PCM 旧通路（type=100），AAC 被车机直接拒掉时的人工退路。
        // 按钮竖排每个约 36dp：横屏屏高只有 ~300dp，再多一个「取消」就会顶出屏幕，
        // 因此横屏省略「取消」（弹窗默认可返回键/点外部关闭）。
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val actions =
            buildList {
                add(
                    DarkMessageDialog.Action(
                        "仅视频（更流畅）",
                        DarkMessageDialog.Emphasis.PRIMARY,
                    ) { beginWithAudio(enabled = false) },
                )
                add(DarkMessageDialog.Action("开启声音") { beginWithAudio(enabled = true) })
                if (!landscape) add(DarkMessageDialog.Action("取消") {})
            }
        DarkMessageDialog.show(
            activity = this,
            title = "是否在车机侧播放声音？",
            message =
                "开启后手机媒体/导航声音投到车机播放、手机本机静音（不开启则车上无声）。\n" +
                    "音视频同时传会增加负载，以看视频为主时可先不开启。\n" +
                    "优先使用内置服务媒体与导航采集，不可用时申请兼容投屏授权。",
            actions = actions,
        )
    }

    /** 确认取消当前连接尝试，断开蓝牙和 WiFi */
    private fun promptCancelConnecting() {
        DarkMessageDialog.show(
            activity = this,
            title = "取消连接？",
            message = "将停止当前连接尝试：蓝牙与 WiFi 链路都会断开，需重新点「启动连接」。",
            actions =
                listOf(
                    DarkMessageDialog.Action("继续连接") {},
                    DarkMessageDialog.Action("取消连接", DarkMessageDialog.Emphasis.DANGER) {
                        // 关闭仍在等待的「加入热点」确认弹窗（其 onChoice(null) 会与
                        // connectCancelled 一起让 autoJoin 流程立即中止）
                        wifiJoinDialog?.cancel()
                        wifiJoinDialog = null
                        SessionController.cancelConnecting(this)
                    },
                ),
        )
    }

    /** 连接前检查蓝牙权限、蓝牙开关和 Android 12 的 WLAN 定位要求，通过后继续 */
    private fun ensureBluetoothReady(onReady: () -> Unit) {
        val missingPermissions =
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
        if (missingPermissions.isNotEmpty()) {
            pendingStartAfterBtPermission.set(true)
            permissionLauncher.launch(missingPermissions.toTypedArray())
            return
        }
        ensureWifiLocationReady {
            val on =
                try {
                    (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                        .adapter
                        ?.isEnabled == true
                } catch (_: SecurityException) {
                    false
                }
            if (!on) {
                DarkMessageDialog.show(
                    activity = this,
                    title = "蓝牙未开启",
                    message = "与车机的无线配对通信依赖蓝牙。请先开启手机蓝牙，并确认已与车机配对。",
                    actions =
                        listOf(
                            DarkMessageDialog.Action("取消") {},
                            DarkMessageDialog.Action("去开启蓝牙", DarkMessageDialog.Emphasis.PRIMARY) {
                                runCatching {
                                    startActivity(
                                        android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
                                    )
                                }.onFailure { log("BT: 无法打开蓝牙设置，请手动开启") }
                            },
                        ),
                )
                log("BT: 蓝牙未开启——本次连接未发起")
                return@ensureWifiLocationReady
            }
            val preparation = ConnectionPreflight.inspect(this, requireAudio = false).beforeIdentity()
            if (!preparation.ready) {
                showConnectionPreparationProblem(preparation)
                return@ensureWifiLocationReady
            }
            onReady()
        }
    }

    /** Android 12 加入专属网络需位置权限和定位服务，13 起使用 NEARBY_WIFI_DEVICES */
    private fun ensureWifiLocationReady(onReady: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                pendingStartAfterBtPermission.set(true)
                permissionLauncher.launch(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES))
                return
            }
            onReady()
            return
        }
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingStartAfterLocationPermission.set(true)
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
            return
        }
        val locationOn =
            try {
                androidx.core.location.LocationManagerCompat
                    .isLocationEnabled(getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager)
            } catch (_: Exception) {
                false
            }
        if (!locationOn) {
            DarkMessageDialog.show(
                activity = this,
                title = "需要开启位置服务",
                message =
                    "Android 12 加入车机热点（应用专属 WiFi）要求位置服务开启——系统用它判定" +
                        "可连接的 WiFi，不会记录你的位置。\n\n请打开位置服务后重试；也可以到" +
                        "WLAN 设置手动连接热点。",
                actions =
                    listOf(
                        DarkMessageDialog.Action("取消") {},
                        DarkMessageDialog.Action("去开启", DarkMessageDialog.Emphasis.PRIMARY) {
                            runCatching {
                                startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                                )
                            }.onFailure { log("WIFI: 无法打开位置服务设置，请手动开启") }
                        },
                    ),
            )
            log("WIFI: 位置服务未开启——Android 12 下应用专属网络不可用，本次连接未发起")
            return
        }
        onReady()
    }

    /** 位置权限授权后自动继续启动连接（仅 Android 12/12L）。 */
    private val pendingStartAfterLocationPermission =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val continueStart = pendingStartAfterLocationPermission.getAndSet(false)
            log("ACCESS_FINE_LOCATION 授权: $granted")
            if (granted && continueStart) {
                beginConnectionPreparation()
            } else if (continueStart) {
                snack("Android 12 连接车机热点需要精确位置权限，本次未发起连接")
            }
        }

    /** 确认断开连接，并提示下次推送声音需要重新授权 */
    private fun promptDisconnect() {
        DarkMessageDialog.show(
            activity = this,
            title = "断开连接？",
            message =
                "将停止车机推流并关闭保活服务，车机画面会立即中断。\n\n" +
                    "注意：投屏授权会随服务停止失效，下次如需车机侧声音请重新授权。",
            actions =
                listOf(
                    DarkMessageDialog.Action("取消") {},
                    DarkMessageDialog.Action("断开", DarkMessageDialog.Emphasis.DANGER) {
                        SessionCommands.disconnect(this)
                    },
                ),
        )
    }

    /** 内置服务就绪时在后台补授通知使用权，已授权则跳过 */
    private fun ensureNotificationAccessAsync() {
        Bg.run("notification-access") {
            if (ShellServiceGate.state(this) != ShellServiceGate.State.READY) {
                log("MEDIA: 内置服务未就绪——暂无法自动授予通知使用权（就绪后会补）")
                return@run
            }
            NotificationAccess.ensureGranted(this, ::log)
        }
    }

    /**
     * 连接前要求服务就绪，区分首次配对、服务停止与正在激活
     */
    private fun ensureServiceReady(onReady: () -> Unit) {
        val state = ShellServiceGate.state(this)
        if (state == ShellServiceGate.State.READY) {
            // 内置服务就绪即可静默补授权（此前若因未就绪而跳过，这里补上）
            ensureNotificationAccessAsync()
            onReady()
            return
        }
        if (state == ShellServiceGate.State.INPUT_UNAVAILABLE) {
            recheckInputService(onReady)
            return
        }
        log("SERVICE: 未就绪（$state）——已阻止本次连接")
        promptServiceNotReady(state, connectAfter = true)
    }

    /**
     * 在线权限服务优先在后台复用探活，检查通过才执行原来的操作
     */
    private fun recheckInputService(onReady: () -> Unit) {
        if (isFinishing || isDestroyed || connectionCheckRunning || SessionController.isSessionRunning()) return
        connectionCheckRunning = true
        val app = applicationContext
        Bg.run("input-service-recheck") {
            val ready = CarInputClient.awaitReady(app, retry = true)
            runOnUiThread {
                connectionCheckRunning = false
                if (isFinishing || isDestroyed || SessionController.isSessionRunning()) return@runOnUiThread
                updateReadyRow()
                updateServiceChip()
                phonePermissions?.refresh()
                if (ready && CarInputClient.ready) {
                    ensureNotificationAccessAsync()
                    onReady()
                } else {
                    showInputServiceProblem { recheckInputService(onReady) }
                }
            }
        }
    }

    /**
     * 等待超时允许直接重试，不把服务在线误报成需要重新配对
     */
    private fun showInputServiceProblem(retry: () -> Unit) {
        DarkMessageDialog.show(
            this,
            "触控服务暂未就绪",
            "本次未启动车机连接，可点击重试。若持续无响应，请结束并重新打开本软件后激活服务。",
            listOf(
                DarkMessageDialog.Action("取消") {},
                DarkMessageDialog.Action("重试", DarkMessageDialog.Emphasis.PRIMARY, onClick = retry),
            ),
        )
    }

    /** 内置服务未就绪时，用 [DarkMessageDialog] 提供激活和取消操作 */
    private fun promptServiceNotReady(
        state: ShellServiceGate.State,
        connectAfter: Boolean = false,
    ) {
        val guidance = ShellServiceGate.guidanceOf(state)
        DarkMessageDialog.show(
            this,
            guidance.title,
            guidance.message,
            listOf(
                DarkMessageDialog.Action("取消") {},
                DarkMessageDialog.Action(guidance.primaryLabel ?: "激活服务", DarkMessageDialog.Emphasis.PRIMARY) {
                    if (state == ShellServiceGate.State.INPUT_UNAVAILABLE) {
                        recheckInputService { if (connectAfter) beginConnectionPreparation() }
                    } else {
                        pendingServiceThenConnect = connectAfter
                        serviceActivationLauncher.launch(Intent(this, ServiceActivationActivity::class.java))
                    }
                },
            ),
        )
    }

    private fun beginWithAudio(enabled: Boolean) {
        SessionController.audioEnabledForSession = enabled
        SessionController.audioPreference =
            io.github.shaonialife.buicklinkplus.audio.AudioPreference
                .read(this)
        if (!enabled) {
            log("MODE: 本次连接仅推视频（未开启车机侧声音）")
            startConnectionNow()
            return
        }
        if (CarPlayService.mediaProjection != null &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            log("MODE: 已选择开启声音（投屏权限已就绪）")
            startConnectionNow()
            return
        }
        connectionCheckRunning = true
        Bg.run("audio-capability") {
            val supported =
                io.github.shaonialife.buicklinkplus.audio.AudioCaptureSupport
                    .refresh()
            runOnUiThread {
                connectionCheckRunning = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (supported) startConnectionNow() else requestAudioProjection(connectAfter = true)
            }
        }
    }

    /** 录音与投屏按顺序申请，连接前授权和独立授权入口共用 */
    private fun requestAudioProjection(connectAfter: Boolean) {
        log(if (connectAfter) "MODE: 顺序检查声音与投屏授权，通过后继续连接" else "MODE: 准备声音捕获授权，本入口不自动连接")
        pendingStartAfterProjection.set(connectAfter)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingProjectionAfterAudioPermission = true
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (CarPlayService.mediaProjection != null) {
            pendingStartAfterProjection.set(false)
            if (connectAfter) startConnectionNow() else snack("声音捕获授权已就绪")
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    /** 授权返回后重新检查全部准备项 */
    private fun beginConnectionPreparation() {
        if (isFinishing || isDestroyed || connectionCheckRunning || SessionController.isSessionRunning()) return
        ensureServiceReady { ensureBluetoothReady { showAudioChoiceDialog() } }
    }

    /** 列出未就绪项，点击执行对应准备操作 */
    private fun showConnectionPreparationProblem(preparation: ConnectionPreflight.Report) {
        val first = preparation.missing.firstOrNull()?.requirement ?: return
        if (first == ConnectionPreflight.Requirement.SHELL_SERVICE) {
            promptServiceNotReady(ShellServiceGate.state(this), connectAfter = true)
            return
        }
        log("PREFLIGHT: 已阻止连接，缺少：${preparation.missing.joinToString("、") { it.requirement.title }}")
        DarkMessageDialog.show(
            activity = this,
            title = "请先完成连接准备",
            message = preparation.describe() + "\n\n热点名称和密码由蓝牙握手后获取，无需提前填写。",
            actions =
                listOf(
                    DarkMessageDialog.Action("取消") {},
                    DarkMessageDialog.Action(first.action, DarkMessageDialog.Emphasis.PRIMARY) {
                        val settingsAction =
                            when (first) {
                                ConnectionPreflight.Requirement.BLUETOOTH_ENABLED -> Settings.ACTION_BLUETOOTH_SETTINGS
                                ConnectionPreflight.Requirement.WIFI_ENABLED -> Settings.ACTION_WIFI_SETTINGS
                                ConnectionPreflight.Requirement.LOCATION_ENABLED -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                                else -> null
                            }
                        if (settingsAction != null) {
                            runCatching { startActivity(Intent(settingsAction)) }.onFailure { snack("无法打开系统设置，请手动完成后重新检查") }
                        } else {
                            when (first) {
                                ConnectionPreflight.Requirement.TARGET -> showTargetPicker()
                                ConnectionPreflight.Requirement.IDENTITY -> startConnectionNow()
                                ConnectionPreflight.Requirement.INPUT_SERVICE -> startConnectionNow()
                                ConnectionPreflight.Requirement.AUDIO_PERMISSION,
                                ConnectionPreflight.Requirement.PROJECTION,
                                -> beginWithAudio(true)
                                else -> beginConnectionPreparation()
                            }
                        }
                    },
                ),
        )
    }

    /** 最终启动前复核全部环境，后台解析本机身份后再开放实际连接 */
    private fun startConnectionNow() {
        if (SessionCommands.stopping) {
            snack("正在释放上次连接，请稍候")
            return
        }
        runOnUiThread {
            if (isFinishing || isDestroyed || connectionCheckRunning || SessionController.isSessionRunning()) return@runOnUiThread
            val preparation = ConnectionPreflight.inspect(this, SessionController.audioEnabledForSession).beforeIdentity()
            if (!preparation.ready) {
                showConnectionPreparationProblem(preparation)
                return@runOnUiThread
            }
            connectionCheckRunning = true
            Bg.run("connection-preflight") {
                val inputReady =
                    runCatching { CarInputClient.awaitReady(applicationContext, retry = true) }
                        .onFailure { log("PREFLIGHT: 输入服务检查中断：${it.message ?: "原因未知"}") }
                        .getOrDefault(false)
                if (!inputReady) {
                    runOnUiThread {
                        connectionCheckRunning = false
                        if (!isFinishing && !isDestroyed) {
                            showInputServiceProblem { startConnectionNow() }
                        }
                    }
                    return@run
                }
                val displaysReady =
                    runCatching { prepareConnectionDisplays() }
                        .onFailure {
                            if (it is InterruptedException) Thread.currentThread().interrupt()
                            log("PREFLIGHT: 残留屏检查异常，本次未启动连接 类型=${it.javaClass.simpleName}")
                        }.getOrDefault(false)
                if (!displaysReady) {
                    runOnUiThread {
                        connectionCheckRunning = false
                        if (!isFinishing && !isDestroyed) snack("连接前未能确认残留屏已清理，请查看日志后重试", 4_000L)
                    }
                    return@run
                }
                runCatching { DeviceIdentity.bluetoothMac(applicationContext, forceRetry = true) }
                    .onFailure { log("PREFLIGHT: 本机身份检查异常：${it.message ?: "原因未知"}") }
                runOnUiThread finishPreparation@{
                    connectionCheckRunning = false
                    if (isFinishing || isDestroyed || SessionController.isSessionRunning()) return@finishPreparation
                    val finalPreparation = ConnectionPreflight.inspect(this, SessionController.audioEnabledForSession)
                    if (!finalPreparation.ready) {
                        showConnectionPreparationProblem(finalPreparation)
                        return@finishPreparation
                    }
                    log("PREFLIGHT: 必需环境全部就绪，目标已配对，本机身份已解析，开始连接")
                    SessionController.startConnection(this)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 旋转/重建：沿用原倒计时截止时刻（onResume 据此续弹）
        disclaimerDeadlineAt = savedInstanceState?.getLong(KEY_DISCLAIMER_DEADLINE, 0L) ?: 0L

        deviceSubtitle = findViewById(R.id.deviceSubtitle)
        shizukuChip = findViewById(R.id.shizukuChip)
        shizukuDesc = findViewById(R.id.shizukuDesc)
        shizukuBadge = findViewById(R.id.shizukuBadge)
        hubCar = findViewById(R.id.hubCar)
        heroStateText = findViewById(R.id.heroStateText)
        btnResidualCleanup = findViewById(R.id.btnResidualCleanup)
        btnResidualCleanup.setOnClickListener {
            pendingResidualDisplays?.let { promptResidualDisplays(it) }
        }
        heroSubline = findViewById(R.id.heroSubline)
        heroStats = findViewById(R.id.heroStats)
        heroProgress = findViewById(R.id.heroProgress)
        ctaButton = findViewById(R.id.ctaButton)
        ctaIcon = findViewById(R.id.ctaIcon)
        ctaLabel = findViewById(R.id.ctaLabel)
        audioTag = findViewById(R.id.audioTag)
        readyCount = findViewById(R.id.readyCount)
        btDesc = findViewById(R.id.btDesc)
        btBadge = findViewById(R.id.btBadge)
        btnPickTarget = findViewById(R.id.btnPickTarget)
        audioDesc = findViewById(R.id.audioDesc)
        btnAudio = findViewById(R.id.btnAudio)
        switchAlwaysOn = findViewById(R.id.switchAlwaysOn)
        advBody = findViewById(R.id.advBody)
        advChevron = findViewById(R.id.advChevron)
        shellStateText = findViewById(R.id.shellStateText)
        logBody = findViewById(R.id.logBody)
        logChevron = findViewById(R.id.logChevron)
        switchLog = findViewById(R.id.switchLog)
        logText = findViewById(R.id.logText)
        snackbar = findViewById(R.id.snackbar)

        deviceSubtitle.text = deviceName()
        logText.movementMethod = ScrollingMovementMethod()
        // 控制台在长页面 ScrollView 内：按下时申请父级不拦截竖向手势，否则无法手动滚动
        // 仅协调父容器的手势拦截并返回 false，正常点击由既有 click 监听处理
        @Suppress("ClickableViewAccessibility")
        logText.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.parent.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_MOVE,
                android.view.MotionEvent.ACTION_UP,
                -> updateLogBottomButton()
            }
            false
        }
        findViewById<ImageView>(R.id.btnLogBottom).setOnClickListener {
            // 跳到底部：滚动后自动跟底也随之恢复
            scrollConsoleToBottom()
        }

        // 会话中枢（进程级）：登记 applicationContext + 创建会话对象；
        // 挂屏注册：日志续显 + SSID 回填
        SessionController.ensure(this)
        SessionController.logListener = logListener
        // 连接流程的重要提示（WiFi 未开启/热点加入限制等）以 Snackbar 长显
        SessionController.uiHintSink = uiHintSinkRef
        // 加入热点确认弹窗：继续自动加入 / 打开 WLAN 设置手动连。
        // 取消连接时 cancel() 联动关闭（触发 onChoice(null) → 流程检查取消标志中止）
        SessionController.dialogSink = { spec, onChoice ->
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    onChoice(null)
                    return@runOnUiThread
                }
                var answered = false
                // 关闭返回 null；按钮只回传选择，是否打开 WLAN 由会话调用分支处理
                val dialog =
                    DarkMessageDialog.show(
                        activity = this,
                        title = spec.title,
                        message = spec.message,
                        actions =
                            listOf(
                                DarkMessageDialog.Action(spec.negative) {
                                    answered = true
                                    onChoice(false)
                                },
                                DarkMessageDialog.Action(
                                    spec.positive,
                                    DarkMessageDialog.Emphasis.PRIMARY,
                                ) {
                                    answered = true
                                    onChoice(true)
                                },
                            ),
                        onCancel = { if (!answered) onChoice(null) },
                    )
                wifiJoinDialog = dialog
            }
        }
        renderConsole(SessionController.logText())

        setUpUiControls()
        setupSimpleNavigation()
        selectPhoneTab(savedInstanceState?.getBoolean("settingsTab", false) == true)
        savedInstanceState?.getInt("settingsSection", -1)?.takeIf { it >= 0 }?.let { phoneSettings?.showSection(it) }
        savedInstanceState?.getBundle("settingsNavigation")?.let { phoneSettings?.restoreNavigation(it) }
        settingsOpenedFromHome = savedInstanceState?.getBoolean("settingsOpenedFromHome", false) == true
        // 以横屏启动时不会触发 onConfigurationChanged，这里按当前方向定一次排布。
        // 首次调用时 contentArea 未测量（width=0，只能退回屏宽估算），
        // 布局完成后再精确重算一次，避免英雄卡宽度与右栏让位差几个像素。
        val startLandscape =
            resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        applyContentLayout(startLandscape)
        findViewById<android.widget.FrameLayout>(R.id.contentArea).post {
            if (!isFinishing && !isDestroyed) {
                // 延迟布局读取当前方向，避免启动后快速旋转时回写旧方向
                applyContentLayout(
                    resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
                )
            }
        }

        // 免责声明：未接受当前版本时强制弹（不可取消、需倒计时 + 滑到底）。
        // savedState 非空（旋转/重建）时复用原截止时刻，不重新计时。
        if (savedInstanceState == null &&
            needsDisclaimerAtLaunch(
                DisclaimerPrefs.acceptedVersion(this),
                DisclaimerPrefs.CURRENT_VERSION,
            )
        ) {
            showDisclaimer(requireAcceptance = true)
        }
    }

    /** 底部「免责声明」入口：只读重看（不限制时长、不要求滑到底）。 */
    private fun showDisclaimer(requireAcceptance: Boolean) {
        if (activeDisclaimer?.isShowing == true) return
        if (requireAcceptance && disclaimerDeadlineAt == 0L) {
            disclaimerDeadlineAt = SystemClock.elapsedRealtime() + DisclaimerDialog.COUNTDOWN_MS
        }
        activeDisclaimer =
            DisclaimerDialog
                .show(
                    activity = this,
                    requireAcceptance = requireAcceptance,
                    deadlineAt = disclaimerDeadlineAt,
                    onDecline = {
                        // 放弃使用：结束本任务并退出（不用 System.exit，保留正常生命周期收尾）。
                        // 若已有会话在跑，先正常断开，避免留下无人管理的推流与虚拟屏。
                        runCatching { SessionCommands.disconnect(this) }
                        finishAndRemoveTask()
                    },
                    onDismiss = {
                        activeDisclaimer = null
                        restoreServiceOnAppOpen()
                    },
                )
    }

    /** 初始化手机操作控件和折叠状态 */
    private fun setUpUiControls() {
        val preferences = getSharedPreferences("settings", Context.MODE_PRIVATE)
        // 清理旧模式偏好，当前统一使用隐形屏
        if (preferences.contains("mode")) {
            preferences.edit {
                remove("mode")
            }
        }

        // ---- 免责声明（只读重看）----
        findViewById<LinearLayout>(R.id.btnDisclaimer).setOnClickListener {
            showDisclaimer(requireAcceptance = false)
        }

        // ---- 内置服务状态行：未就绪时点它直接看引导（与「启动连接」被拦下同一套文案）----
        findViewById<LinearLayout>(R.id.rowShizuku).setOnClickListener {
            val state = ShellServiceGate.state(this)
            if (state == ShellServiceGate.State.READY) {
                snack("内置服务已就绪，触控服务将在连接前核验")
            } else {
                promptServiceNotReady(state)
            }
        }

        findViewById<TextView>(R.id.sleepPolicyStatus).setOnClickListener { showSleepGuidance() }
        // ---- 息屏策略：保留用户此前明确选择；vivo/iQOO 默认兼容暗屏（主屏息屏会冻结虚拟屏）----
        ScreenDimmer.enabled =
            preferences.getBoolean("alwaysOn", Build.MANUFACTURER.equals("vivo", ignoreCase = true))
        switchAlwaysOn.isChecked = ScreenDimmer.enabled
        switchAlwaysOn.setOnCheckedChangeListener { _, checked ->
            ScreenDimmer.enabled = checked
            preferences.edit {
                putBoolean("alwaysOn", checked)
            }
            if (checked) {
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    promptOverlayPermission("已开启兼容暗屏，手机会保持常亮并自动显示黑色暗幕。")
                } else {
                    ScreenDimmer
                        .attach(this)
                }
            } else {
                if (VirtualLauncher.appDisplayId >= 0) {
                    // 会话中：仅关闭常亮/暗幕（投屏探针等资源留待会话结束统一释放）
                    ScreenDimmer
                        .disableDim()
                } else {
                    ScreenDimmer
                        .detach()
                }
            }
            log("MODE: 常亮+暗幕 ${if (checked) "开启" else "关闭"}")
        }

        // ---- 英雄卡主操作 ----
        ctaButton.setOnClickListener {
            when (heroState) {
                HeroState.CONNECTED -> promptDisconnect()
                // 前置检查：内置服务（硬性依赖）→ 蓝牙权限/开关 → 音频选择
                HeroState.IDLE -> beginConnectionPreparation()
                // 连接中可取消：卡在错误网络/等待回连时用户主动脱身（弹窗防误触）
                HeroState.CONNECTING -> promptCancelConnecting()
                null -> {}
            }
        }

        // ---- 连接准备 ----
        findViewById<TextView>(R.id.btnWifi).setOnClickListener {
            if (SessionController.videoSender?.isRunning == true) {
                snack("请先断开投屏，再使用手动连接")
                return@setOnClickListener
            }
            // 只读取车机下发的完整凭据，不接收界面输入或展示热点名称
            val credentials = SessionController.lastWifiCredentials
            if (credentials == null) {
                snack("尚未收到车机热点参数，请先完成蓝牙启动握手")
                log("WiFi: 尚未收到车机热点参数（由蓝牙 0x4301 或 0x5703 提供），请先完成启动握手")
                return@setOnClickListener
            }
            if (credentials.passphrase.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("车机连接密码", credentials.passphrase))
                snack("连接密码已复制，可在系统 Wi-Fi 设置中粘贴")
            } else {
                snack("车机使用开放网络，请在系统 Wi-Fi 设置中连接")
            }
            log("WiFi: 用户选择手动兼容连接，打开系统 WLAN 设置")
            try {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            } catch (_: Exception) {
                log("WiFi: 无法打开WLAN设置，请手动前往 设置 > WLAN")
            }
        }

        // 目标车机选择：配对了多台车机时指定唯一目标（只向它发请求/只接受它连入）
        btnPickTarget.setOnClickListener { showTargetPicker() }
        findViewById<TextView>(R.id.btnBtConnectionMode).setOnClickListener { showBluetoothConnectionMode() }

        // 车机侧声音授权：先申请录音权限，再申请投屏权限
        btnAudio.setOnClickListener {
            requestAudioProjection(connectAfter = false)
        }

        // ---- 车机显示 ----
        findViewById<LinearLayout>(R.id.rowDim).setOnClickListener {
            if (!ScreenDimmer
                    .isAttached()
            ) {
                snack("暗幕未就绪（连接成功后自动启用）")
                return@setOnClickListener
            }
            ScreenDimmer
                .enterDimNow()
            log("DIMMER: 手动假息屏（点击屏幕恢复）")
        }

        findViewById<TextView>(R.id.btnWallpaperPick).apply {
            text = "编辑壁纸"
            setOnClickListener { startActivity(Intent(this@MainActivity, WallpaperActivity::class.java)) }
        }
        findViewById<TextView>(R.id.btnWallpaperReset).setOnClickListener {
            Bg.run("wallpaper-reset") {
                val ok = WallpaperStore.resetToDefault(applicationContext)
                runOnUiThread { if (!isFinishing && !isDestroyed) snack(if (ok) "已清除壁纸" else "清除失败") }
            }
        }

        // ---- 折叠卡：高级 / 日志 ----
        bindExpandable(R.id.advHeader, advBody, advChevron)
        bindExpandable(R.id.logHeader, logBody, logChevron)

        // ---- Shell 全局状态 ----
        findViewById<View>(R.id.btnShellRefresh).setOnClickListener { refreshShellState() }
        findViewById<View>(R.id.btnShellReset).setOnClickListener { resetShellState() }
        refreshShellState(startupCheck = true)

        // ---- 日志开关（默认开；关闭后不采集、不渲染；选择持久化） ----
        var logEnabled = preferences.getBoolean("logEnabled", true)
        SessionController.logEnabled = logEnabled
        switchLog.isChecked = logEnabled
        switchLog.setOnCheckedChangeListener { _, checked ->
            logEnabled = checked
            SessionController.logEnabled = checked
            preferences.edit {
                putBoolean("logEnabled", checked)
            }
            renderConsole(SessionController.logText())
        }
        findViewById<ImageView>(R.id.btnClearLog).setOnClickListener {
            // 清除缓冲后必须显式重渲染：日志回调是增量追加语义，clearLog 的通知
            // 无法清 UI（见 SessionController.clearLog 注释，「点了没反应」根因）
            SessionController.clearLog()
            renderConsole(SessionController.logText())
        }
        findViewById<ImageView>(R.id.btnCopyLog).setOnClickListener {
            val text = SessionController.logText()
            if (text.isEmpty()) {
                snack("日志为空")
                return@setOnClickListener
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Buick Link + 日志", text))
            snack("已复制 ${text.length} 字符日志")
        }
        findViewById<ImageView>(R.id.btnExportLog).setOnClickListener { exportLogFile() }
        // 默认导出本次启动的日志，长按导出全部日志
        findViewById<ImageView>(R.id.btnExportLog).setOnLongClickListener {
            exportLogFile(LogExporter.Scope.ALL)
            true
        }

        // 通知使用权（媒体会话枚举，音乐卡数据源）：内置服务就绪时可静默授予。
        // 幂等可重试——启动时不一定就绪（重启后内置服务未运行、改包名后未授权），
        // 所以内置服务授权成功、连接建立等时机都会再调一次。
        ensureNotificationAccessAsync()

        // ---- 车机设置（手机侧二级菜单：桌面/横屏/超采样/dock/默认地图） ----
        findViewById<LinearLayout>(R.id.btnCarSettings).setOnClickListener {
            startActivity(android.content.Intent(this, PhoneSettingsActivity::class.java))
        }

        // 启动即检查：常亮开 + 无悬浮窗权限 → 主动请求
        if (ScreenDimmer.enabled) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                promptOverlayPermission("「常亮+暗幕」已开启。")
            } else {
                // attach 只监听触摸，自动暗幕和常亮由会话状态控制
                ScreenDimmer
                    .attach(this)
            }
        }

        TouchRouter.logSink = { line -> log(line) }
        VirtualLauncher.logSink = { line -> log(line) }
        CarInputClient.logSink = { line -> log(line) }

        log("Buick Link + Phase 0 就绪")
        log("真车：与车机蓝牙配对后点「启动连接」，自动请求应用专属热点；手动入网仅作兼容入口")
        log("测试台：两台手机蓝牙配对 -> 备用机跑 xcertplay 无线模式 -> 点「启动连接」等待回连")
    }

    /** 按用途将操作控件放入设置，首页展示车辆和连接操作 */
    private fun setupSimpleNavigation() {
        val detail = findViewById<LinearLayout>(R.id.detailColumn)
        val area = findViewById<android.widget.FrameLayout>(R.id.contentArea)
        homeHeroScroll =
            android.widget
                .ScrollView(this)
                .apply {
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    visibility = View.GONE
                }.also { area.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1)) }
        val settings =
            PhoneSettingsView(this)
                .apply {
                    visibility = View.GONE
                    onSectionRequested = { category ->
                        val panelId = if (category < 100) category else SettingsMenus.directPhonePanel(category)
                        if (panelId != null) SettingsContentActivity.start(this@MainActivity, SettingsMenus.itemOf(panelId))
                        panelId != null
                    }
                    onSectionShown = { id ->
                        if (id == SettingsMenus.ABOUT) renderConsole(SessionController.logText())
                        if (id == SettingsMenus.CONNECTION) refreshConnectionSection()
                        if (id == SettingsMenus.PERMISSIONS) phonePermissions?.refresh()
                    }
                    onBackRequested = {
                        if (settingsOpenedFromHome) {
                            settingsOpenedFromHome = false
                            selectPhoneTab(false)
                            true
                        } else {
                            false
                        }
                    }
                }.also {
                    phoneSettings = it
                    area.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1))
                }
        val density = resources.displayMetrics.density

        fun move(
            view: View,
            target: LinearLayout,
        ) {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            target.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (12 * density).toInt() })
        }

        fun panel(
            body: LinearLayout,
            id: Int,
        ) {
            val item = SettingsMenus.itemOf(id)
            val row = layoutInflater.inflate(R.layout.item_phone_category, body, false)
            row.setAppearanceBackground(R.drawable.bg_phone_card)
            row.findViewById<ImageView>(R.id.categoryIcon).setAppearanceImageResource(item.iconRes)
            row.findViewById<ImageView>(R.id.categoryIcon).setAppearanceImageTint(R.color.m3_primary)
            row.findViewById<TextView>(R.id.categoryTitle).text = if (id == SettingsMenus.DOCK) "侧栏样式" else item.title
            row.setOnClickListener { SettingsContentActivity.start(this@MainActivity, item) }
            body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * density).toInt() })
        }
        val appearance = settings.body(SettingsMenus.APPEARANCE)
        move(findViewById<View>(R.id.btnWallpaperPick).parent as View, appearance)
        panel(appearance, SettingsMenus.DISPLAY)
        panel(appearance, SettingsMenus.CAR_APPEARANCE)
        panel(appearance, SettingsMenus.DOCK)
        val apps = settings.body(SettingsMenus.APPS)
        panel(apps, SettingsMenus.DESKTOP)
        panel(apps, SettingsMenus.SUPERSAMPLE)
        panel(apps, SettingsMenus.ORIENTATION)
        panel(apps, SettingsMenus.OFFICIAL_CAR_APPS)
        // 保留旧控件及监听以兼容现有引用，主列表使用真实面板入口
        move(findViewById(R.id.btnCarSettings), apps)
        findViewById<View>(R.id.btnCarSettings).visibility = View.GONE
        panel(settings.body(SettingsMenus.LAYOUT), SettingsMenus.DISPLAY)
        panel(settings.body(SettingsMenus.MAP), SettingsMenus.DEFAULT_MAP)
        val sound = settings.body(SettingsMenus.AUDIO)
        panel(sound, SettingsMenus.DASHBOARD_WIDGET)
        move(audioDesc.parent.parent as View, sound)
        panel(sound, SettingsMenus.AUDIO)
        soundStatus =
            TextView(this)
                .apply {
                    setAppearanceTextColor(R.color.m3_on_var)
                    textSize = 13f
                }.also { sound.addView(it) }
        val connection = settings.body(SettingsMenus.CONNECTION)
        val permissionRow = layoutInflater.inflate(R.layout.item_phone_category, connection, false)
        permissionRow.findViewById<TextView>(R.id.categoryTitle).text = SettingsMenus.titleOf(SettingsMenus.PERMISSIONS)
        permissionRow.findViewById<ImageView>(R.id.categoryIcon).setAppearanceImageResource(R.drawable.ic_shizuku_shield)
        permissionRow.setOnClickListener { settings.showSection(SettingsMenus.PERMISSIONS) }
        connection.addView(permissionRow)
        phoneConnectionContent =
            findViewById<View>(R.id.phoneConnectionContent).also { content ->
                move(content, connection)
                PhonePanelUi.bindDisclosure(
                    content,
                    R.id.phoneConnectionMore,
                    R.id.phoneConnectionMoreBody,
                    R.id.phoneConnectionArrow,
                    expanded = false,
                )
            }
        val permissions = settings.body(SettingsMenus.PERMISSIONS)
        // 旧控件仍挂在窗口树内，保留既有授权、状态和息屏保存回调
        val legacyPermissions = LinearLayout(this).apply { visibility = View.GONE }
        permissions.addView(legacyPermissions)
        move(readyCount.parent.parent as View, legacyPermissions)
        move(switchAlwaysOn.parent.parent as View, legacyPermissions)
        phonePermissions =
            PhonePermissionsView(this)
                .apply {
                    onAction = ::handlePermissionShortcut
                    onCompatChanged = { checked ->
                        switchAlwaysOn.isChecked = checked
                        refresh()
                    }
                }.also { permissions.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        val about = settings.body(SettingsMenus.ABOUT)
        move(heroStats, about)
        move(findViewById(R.id.advCard), about)
        move(findViewById(R.id.logCard), about)
        // Gateway 配置入口：自动上报需要独立 token 和 WSS 地址；不改动车机运行链路。
        val gatewaySettings = TextView(this).apply {
            text = "Once 日志自动上报 · 设置 / 状态"
            textSize = 16f
            val pad = (14 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener {
                startActivity(
                    android.content.Intent(
                        this@MainActivity,
                        io.github.shaonialife.buicklinkplus.gateway.GatewaySettingsActivity::class.java,
                    ),
                )
            }
        }
        about.addView(gatewaySettings)
        val updateCenter = TextView(this).apply {
            text = "软件更新 · 固定签名 / 检查新版本"
            textSize = 16f
            val pad = (14 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener {
                startActivity(android.content.Intent(
                    this@MainActivity,
                    io.github.shaonialife.buicklinkplus.update.TraceUpdateCenterActivity::class.java,
                ))
            }
        }
        about.addView(updateCenter)
        move(findViewById(R.id.btnDisclaimer), about)
        homeDashboard =
            PhoneHomeDashboard(this, detail, onOpen = { id ->
                val panel = if (id < 100) id else SettingsMenus.directPhonePanel(id)
                if (panel != null) {
                    SettingsContentActivity.start(this, SettingsMenus.itemOf(panel))
                } else {
                    settingsOpenedFromHome = true
                    selectPhoneTab(true)
                    settings.showSection(id)
                }
            }, onTarget = ::showTargetPicker)
        val cellular = layoutInflater.inflate(R.layout.view_phone_cellular, detail, false)
        // 新卡片在旧控件初始化之后创建，开关必须在布局加载后读取和绑定
        val switchCellular = cellular.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchCellular)
        val cellularPreferences = getSharedPreferences("settings", MODE_PRIVATE)
        switchCellular.isChecked = cellularPreferences.getBoolean("useCellularForInternet", true)
        switchCellular.setOnCheckedChangeListener { _, checked ->
            cellularPreferences.edit {
                putBoolean("useCellularForInternet", checked)
            }
            log("MODE: 优先使用手机流量 ${if (checked) "开启（上网走手机流量）" else "关闭（上网走车机热点）"}")
        }
        connection.addView(cellular)
        move(findViewById(R.id.homeSessionNote), detail)
        (findViewById<View>(R.id.homeSessionNote).layoutParams as LinearLayout.LayoutParams).topMargin = (12 * density).toInt()
        findViewById<View>(R.id.phoneHomeTab).setOnClickListener {
            settingsOpenedFromHome = false
            selectPhoneTab(false)
        }
        findViewById<View>(R.id.phoneSettingsTab).setOnClickListener {
            settingsOpenedFromHome = false
            if (phoneSettingsVisible) settings.showIndex()
            selectPhoneTab(true)
        }
        findViewById<View>(R.id.heroStats).visibility = View.GONE
    }

    /**
     * 底部导航只调整页面可见性，连接与授权状态继续由原宿主管理
     */
    private fun selectPhoneTab(settings: Boolean) {
        phoneSettingsVisible = settings
        phoneSettings?.visibility = if (settings) View.VISIBLE else View.GONE
        findViewById<View>(R.id.mainScroll).visibility = if (settings) View.GONE else View.VISIBLE
        findViewById<View>(R.id.heroCard).visibility = if (settings) View.GONE else View.VISIBLE
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        homeHeroScroll?.visibility = if (!settings && landscape) View.VISIBLE else View.GONE
        shizukuChip.visibility = if (settings) View.GONE else View.VISIBLE
        val tabs =
            listOf(
                intArrayOf(R.id.phoneHomeTab, R.id.phoneHomeIndicator, R.id.phoneHomeIcon, R.id.phoneHomeLabel),
                intArrayOf(R.id.phoneSettingsTab, R.id.phoneSettingsIndicator, R.id.phoneSettingsIcon, R.id.phoneSettingsLabel),
            )
        tabs.forEachIndexed { index, ids ->
            val selected = (index == 1) == settings
            val color = if (selected) R.color.m3_primary else R.color.m3_on_var
            findViewById<View>(ids[0]).isSelected = selected
            findViewById<View>(ids[1]).setAppearanceBackground(0)
            findViewById<ImageView>(ids[2]).setAppearanceImageTint(color)
            findViewById<TextView>(ids[3]).apply {
                setAppearanceTextColor(color)
                paint.isFakeBoldText = selected
            }
        }
    }

    /**
     * 返回先退出设置详情和设置页，不影响正在进行的连接
     */
    override fun onBackPressed() {
        when {
            phoneSettingsVisible && settingsOpenedFromHome -> {
                settingsOpenedFromHome = false
                selectPhoneTab(false)
            }
            phoneSettingsVisible && phoneSettings?.currentSection != null -> phoneSettings?.showIndex()
            phoneSettingsVisible -> selectPhoneTab(false)
            else -> super.onBackPressed()
        }
    }

    /**
     * 权限页动作与连接向导隔离，打开设置或授权后只更新准备状态
     */
    private fun handlePermissionShortcut(action: PhonePermissionsView.Action) {
        if (quickPermissionRequestPending ||
            pendingStartAfterBtPermission.get() ||
            pendingTargetPickerAfterPermission ||
            pendingServiceThenConnect ||
            pendingProjectionAfterAudioPermission ||
            pendingStartAfterProjection.get() ||
            connectionCheckRunning
        ) {
            snack(getString(R.string.phone_permissions_request_pending))
            return
        }
        when (action) {
            PhonePermissionsView.Action.CHECK_SERVICE -> checkPermissionService()
            PhonePermissionsView.Action.GUIDE ->
                DarkMessageDialog.show(
                    this,
                    getString(R.string.phone_permissions_guide),
                    getString(R.string.phone_permissions_guide_body),
                    listOf(
                        DarkMessageDialog.Action(
                            getString(R.string.phone_permissions_configure_shizuku),
                            DarkMessageDialog.Emphasis.PRIMARY,
                        ) {
                            handlePermissionShortcut(PhonePermissionsView.Action.CHECK_SERVICE)
                        },
                        DarkMessageDialog.Action(getString(R.string.phone_permissions_configure_devices)) {
                            handlePermissionShortcut(PhonePermissionsView.Action.DEVICE_PERMISSIONS)
                        },
                        DarkMessageDialog.Action(getString(R.string.phone_permissions_done)) {},
                    ),
                )
            PhonePermissionsView.Action.DEVICE_PERMISSIONS -> {
                val permissions = mutableListOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                if (Build.VERSION.SDK_INT >= 33) {
                    permissions += Manifest.permission.NEARBY_WIFI_DEVICES
                } else {
                    // Android 12 需同时请求粗略与精确位置，精确位置用于本地 Wi-Fi 连接
                    permissions += Manifest.permission.ACCESS_COARSE_LOCATION
                    permissions += Manifest.permission.ACCESS_FINE_LOCATION
                }
                requestQuickPermissions(permissions)
            }
            PhonePermissionsView.Action.BLUETOOTH -> openPermissionSettings(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            PhonePermissionsView.Action.WIFI -> openPermissionSettings(Intent(Settings.ACTION_WIFI_SETTINGS))
            PhonePermissionsView.Action.LOCATION -> openPermissionSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            PhonePermissionsView.Action.OVERLAY ->
                openPermissionSettings(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, ("package:$packageName").toUri()),
                )
            PhonePermissionsView.Action.BATTERY -> openPermissionSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            PhonePermissionsView.Action.BACKGROUND -> showBackgroundPermissionGuide()
            PhonePermissionsView.Action.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestQuickPermissions(listOf(Manifest.permission.POST_NOTIFICATIONS))
                } else {
                    openPermissionSettings(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                    )
                }
            }
            PhonePermissionsView.Action.MEDIA_ACCESS -> openPermissionSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            PhonePermissionsView.Action.SIGNAL -> requestQuickPermissions(listOf(Manifest.permission.READ_PHONE_STATE))
            PhonePermissionsView.Action.AUDIO -> {
                val requested = getSharedPreferences("phone_permissions", MODE_PRIVATE)
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
                    requested.getBoolean("audio_requested", false) &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                ) {
                    openPermissionSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ("package:$packageName").toUri()))
                } else {
                    requested.edit {
                        putBoolean("audio_requested", true)
                    }
                    requestAudioProjection(connectAfter = false)
                }
            }
            PhonePermissionsView.Action.SLEEP_HELP -> showSleepGuidance()
            PhonePermissionsView.Action.DIM_NOW -> findViewById<View>(R.id.rowDim).performClick()
        }
    }

    /**
     * 快捷设置只打开激活页面，不继续之前的连接任务
     */
    private fun checkPermissionService() {
        pendingServiceThenConnect = false
        serviceActivationLauncher.launch(Intent(this, ServiceActivationActivity::class.java))
    }

    /** 只申请缺失权限，永久拒绝时打开设置，返回后不自动连接 */
    private fun requestQuickPermissions(permissions: List<String>) {
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            phonePermissions?.refresh()
            snack(getString(R.string.phone_permissions_granted_result))
            return
        }
        val preferences = getSharedPreferences("phone_permissions", MODE_PRIVATE)
        if (missing.any { preferences.getBoolean("requested:$it", false) && !shouldShowRequestPermissionRationale(it) }) {
            openPermissionSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ("package:$packageName").toUri()))
            return
        }
        preferences.edit {
            missing.forEach { putBoolean("requested:$it", true) }
        }
        // 精确位置申请必须与粗略位置成对提交，即使粗略位置此前已允许
        val request =
            if (Manifest.permission.ACCESS_FINE_LOCATION in missing) {
                (missing + Manifest.permission.ACCESS_COARSE_LOCATION).distinct()
            } else {
                missing
            }
        quickPermissionRequestPending = true
        runCatching { quickPermissionLauncher.launch(request.toTypedArray()) }.onFailure {
            quickPermissionRequestPending = false
            openPermissionSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ("package:$packageName").toUri()))
        }
    }

    /**
     * 系统设置始终打开在手机主屏，厂商未提供对应页面时回退本应用详情
     */
    private fun openPermissionSettings(intent: Intent) {
        val options =
            android.app.ActivityOptions
                .makeBasic()
                .apply { launchDisplayId = 0 }
        runCatching { startActivity(intent, options.toBundle()) }.onFailure {
            snack(getString(R.string.phone_permissions_settings_unavailable))
            if (intent.action != Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ("package:$packageName").toUri()),
                        options.toBundle(),
                    )
                }
            }
        }
    }

    /** 分别展示系统检查结果和用户确认的厂商后台设置 */
    private fun showBackgroundPermissionGuide() {
        val vendor = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase(Locale.ROOT)
        val guide =
            when {
                listOf("xiaomi", "redmi").any { it in vendor } -> R.string.phone_permissions_vendor_xiaomi
                listOf("huawei", "honor").any { it in vendor } -> R.string.phone_permissions_vendor_huawei
                listOf("vivo", "iqoo").any { it in vendor } -> R.string.phone_permissions_vendor_vivo
                listOf("oppo", "oneplus", "realme").any { it in vendor } -> R.string.phone_permissions_vendor_oppo
                else -> R.string.phone_permissions_vendor_common
            }
        val confirmed = phonePermissions?.backgroundConfirmed == true
        DarkMessageDialog.show(
            this,
            getString(R.string.phone_permissions_vendor),
            getString(guide) + "\n\n" + getString(R.string.phone_permissions_vendor_note),
            listOf(
                DarkMessageDialog.Action(getString(R.string.phone_permissions_open_app), DarkMessageDialog.Emphasis.PRIMARY) {
                    openPermissionSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ("package:$packageName").toUri()))
                },
                DarkMessageDialog.Action(
                    getString(
                        if (confirmed) R.string.phone_permissions_clear_acknowledgement else R.string.phone_permissions_acknowledge,
                    ),
                ) {
                    phonePermissions?.setBackgroundConfirmed(!confirmed)
                },
                DarkMessageDialog.Action(getString(R.string.phone_permissions_close)) {},
            ),
        )
    }

    private fun bindExpandable(
        headerId: Int,
        body: View,
        chevron: ImageView,
    ) {
        findViewById<View>(headerId).setOnClickListener {
            val opening = body.visibility != View.VISIBLE
            body.visibility = if (opening) View.VISIBLE else View.GONE
            if (opening && headerId == R.id.logHeader) renderConsole(SessionController.logText())
            chevron
                .animate()
                .rotation(if (opening) 180f else 0f)
                .setDuration(250L)
                .start()
        }
    }

    // ==================== 英雄卡状态机 ====================

    private fun tickHero() {
        homeDashboard?.refresh()
        refreshConnectionSection()
        phonePermissions?.refresh()
        soundStatus?.takeIf { it.isShown }?.text = SessionController.audioStatus()
        val s =
            when {
                SessionController.videoSender?.isRunning == true -> HeroState.CONNECTED
                SessionController.isSessionRunning() || SessionController.sessionStarting.get() ->
                    HeroState.CONNECTING
                else -> HeroState.IDLE
            }
        if (s != heroState) {
            if (s == HeroState.CONNECTED) connectedAt = SystemClock.elapsedRealtime()
            heroState = s
            applyHeroState(s)
        }
        findViewById<View>(R.id.homeSessionNote).visibility = if (s == HeroState.CONNECTED) View.VISIBLE else View.GONE
        // 连接中：副标题跟随阶段提示（英雄卡内即可见，不必划到底部看日志）
        if (s == HeroState.CONNECTING) {
            heroSubline.text = SessionController.phaseHint ?: DEFAULT_CONNECTING_HINT
        }
        // 启动残留屏检查进行中：状态与副标题临时切换为检查提示
        if (s == HeroState.IDLE && startupCheckRunning) {
            heroStateText.text = "检查中"
            heroSubline.text = "正在检查残留屏…"
            heroProgress.visibility = View.VISIBLE
        }
        // 空闲但内置服务未就绪：副标题直接说明「点了也连不上」，避免用户反复点
        if (s == HeroState.IDLE && !startupCheckRunning) {
            val gate = ShellServiceGate.state(this)
            if (gate != ShellServiceGate.State.READY) {
                heroSubline.text =
                    getString(R.string.service_not_ready_details, ShellServiceGate.guidanceOf(gate).title)
            }
        }
        if (s == HeroState.IDLE && connectionCheckRunning) {
            heroStateText.text = "检查中"
            heroSubline.text = "正在核对连接环境和本机蓝牙身份…"
            heroProgress.visibility = View.VISIBLE
        } else if (s == HeroState.IDLE && !startupCheckRunning) {
            heroStateText.text = "未连接"
            heroProgress.visibility = View.GONE
        }
        updateConnectedSubline()
        updateHeroStats()
        updateAudioTag()
        updateReadyRow()
        updateServiceChip()
        if (SessionCommands.stopping) {
            ctaButton.isEnabled = false
            heroStateText.text = "正在断开"
            heroSubline.text = "正在后台释放本次连接资源…"
        }
    }

    /** 连接时显示运行分辨率和帧率，实际帧率由发送帧数差值计算，新会话重置基线 */
    private fun updateHeroStats() {
        if (heroState != HeroState.CONNECTED) return
        val sent = SessionController.videoSender?.framesSentTotal ?: 0
        if (sent < lastSentFrames) lastSentFrames = sent
        val fps = sent - lastSentFrames
        lastSentFrames = sent
        heroStats.text =
            getString(R.string.hero_frame_stats, SessionController.activeScalePercent, fps, SessionController.activeTargetFps)
    }

    private fun applyHeroState(s: HeroState) {
        hubCar.setAppearanceImageTint(R.color.phone_hero_text)

        hubBlink?.cancel()
        hubBlink = null
        hubCar.alpha = 1f
        if (s == HeroState.CONNECTING) {
            hubBlink =
                ObjectAnimator.ofFloat(hubCar, View.ALPHA, 1f, 0.45f).apply {
                    duration = 1_200L
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    start()
                }
        }

        heroStateText.text =
            when (s) {
                HeroState.IDLE -> "未连接"
                HeroState.CONNECTING -> "正在连接"
                HeroState.CONNECTED -> "已连接"
            }
        heroProgress.visibility = if (s == HeroState.CONNECTING) View.VISIBLE else View.GONE
        heroStats.visibility = if (s == HeroState.CONNECTED) View.VISIBLE else View.GONE

        when (s) {
            HeroState.IDLE -> {
                val pending = pendingResidualDisplays
                heroSubline.text =
                    if (pending != null) {
                        "上次会话异常退出，遗留 ${pending.size} 个虚拟屏待清理"
                    } else {
                        "与车机蓝牙完成配对后，点击下方按钮发起无线连接"
                    }
                styleCta(R.drawable.bg_phone_hero_action, R.color.phone_hero_action_text, R.drawable.ic_play, "启动连接")
                audioTag.visibility = View.GONE
            }
            HeroState.CONNECTING -> {
                heroSubline.text = SessionController.phaseHint ?: DEFAULT_CONNECTING_HINT
                styleCta(R.drawable.bg_phone_hero_action, R.color.phone_hero_action_text, R.drawable.ic_close, "取消连接")
                audioTag.visibility = View.GONE
            }
            HeroState.CONNECTED -> {
                styleCta(R.drawable.bg_phone_disconnect, R.color.m3_error, R.drawable.ic_power, "断开连接")
                audioTag.visibility = View.VISIBLE
            }
        }

        ctaIcon.rotation = 0f
    }

    /** 英雄卡主操作按钮统一样式（三个状态仅风格/图标/文案不同）。 */
    private fun styleCta(
        bgRes: Int,
        colorRes: Int,
        iconRes: Int,
        label: String,
    ) {
        ctaButton.setAppearanceBackground(bgRes)
        ctaLabel.setAppearanceTextColor(colorRes)
        ctaIcon.setAppearanceImageTint(colorRes)
        ctaIcon.setAppearanceImageResource(iconRes)
        ctaLabel.text = label
        ctaButton.isEnabled = true
    }

    /**
     * 连接页只展示车辆和网络状态，热点名称与密码始终由连接层管理
     */
    private fun refreshConnectionSection() {
        val content = phoneConnectionContent?.takeIf { it.isShown } ?: return
        val target = TargetDevicePrefs.get(this)
        content.findViewById<TextView>(R.id.phoneConnectionVehicle).text =
            target?.name?.takeIf { it.isNotBlank() } ?: getString(R.string.phone_connection_choose_vehicle)
        btnPickTarget.text = if (target == null) "选择" else "更换"
        val state = SessionController.networkSnapshot()
        val streaming = SessionController.videoSender?.isRunning == true
        content.findViewById<TextView>(R.id.phoneWifiState).text =
            when {
                streaming -> "已连接"
                state?.hotspotBound == true -> "网络已连接"
                SessionController.isSessionRunning() || SessionController.sessionStarting.get() -> "连接中"
                else -> getString(R.string.phone_connection_waiting)
            }
        content.findViewById<TextView>(R.id.phoneNetworkValue).text =
            state?.let { it.defaultTransport + if (it.internetValidated) " · 已验证" else " · 未验证互联网" }
                ?: "连接后检测"
        content.findViewById<TextView>(R.id.phoneCellularValue).text =
            when (state?.cellularAvailable) {
                true -> "已检测到"
                false -> "未检测到"
                null -> "连接后检测"
            }
        content.findViewById<TextView>(R.id.btnWifi).apply {
            isEnabled = SessionController.lastWifiCredentials != null && !streaming
            alpha = if (isEnabled) 1f else 0.4f
        }
    }

    private fun updateConnectedSubline() {
        if (heroState != HeroState.CONNECTED) return
        val elapsed = SystemClock.elapsedRealtime() - connectedAt
        val mm = elapsed / 60_000
        val ss = elapsed % 60_000 / 1_000
        heroSubline.text = String.format(Locale.US, "车机已连接 %02d:%02d", mm, ss)
    }

    /**
     * 首页声音入口始终可见，状态对账只在图标或主题颜色变化时重建图形
     */
    private fun updateAudioTag() {
        val homeLabel =
            getString(
                when {
                    heroState != HeroState.CONNECTED -> R.string.phone_home_audio_hint
                    !SessionController.audioEnabledForSession -> R.string.phone_home_audio_off
                    SessionController.audioSender?.isRunning == true -> R.string.phone_home_audio_on
                    CarPlayService.mediaProjection == null -> R.string.phone_home_audio_permission
                    else -> R.string.phone_home_audio_waiting
                },
            )
        audioTag.contentDescription = homeLabel
        if (heroState != HeroState.CONNECTED) return
        val on =
            SessionController.audioEnabledForSession &&
                SessionController.audioSender?.isRunning == true
        val iconResource = if (on) R.drawable.ic_music_small else R.drawable.ic_volume_off_small
        val iconColor = UiAppearance.color(this, if (on) R.color.m3_primary else R.color.m3_outline)
        if (audioTagIconResource != iconResource || audioTagIconColor != iconColor) {
            val icon = checkNotNull(UiAppearance.drawable(this, iconResource)).mutate()
            DrawableCompat.setTint(icon, iconColor)
            audioTag.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
            audioTagIconResource = iconResource
            audioTagIconColor = iconColor
        }
        val label =
            if (on) {
                val status = SessionController.audioSender?.phoneMuteStatus.orEmpty()
                "车机声音 · " + status.ifEmpty { "采集启动中" }
            } else if (SessionController.audioEnabledForSession && CarPlayService.mediaProjection == null) {
                "车机声音 · 采集未启动，可申请兼容授权"
            } else {
                "车机侧声音 · 关闭"
            }
        if (audioTag.text.toString() != label) audioTag.text = label
    }

    /** 展示当前省电状态，系统设置只由用户主动打开 */
    private fun refreshSleepPolicy() {
        val power = getSystemService(android.os.PowerManager::class.java)
        val exempt = power?.isIgnoringBatteryOptimizations(packageName) == true
        findViewById<TextView>(R.id.sleepPolicyStatus).text =
            getString(R.string.sleep_policy_details, if (ScreenDimmer.enabled) "兼容暗屏" else "跟随系统真实息屏", if (exempt) "电池优化已豁免" else "电池优化未豁免")
    }

    private fun showSleepGuidance() {
        val vivo =
            android.os.Build.MANUFACTURER
                .contains("vivo", true)
        val guide =
            if (vivo) {
                "vivo：请在设置／i管家中允许 Buick Link + 自启动、后台高耗电运行，并在最近任务中锁定应用。不同系统版本的菜单名称可能不同。"
            } else {
                "请为 Buick Link + 允许后台运行，并检查自启动与电池优化设置。"
            }

        fun openSettings(intent: android.content.Intent) {
            runCatching {
                val options =
                    android.app.ActivityOptions
                        .makeBasic()
                        .apply { launchDisplayId = 0 }
                startActivity(intent, options.toBundle())
            }.onFailure { snack("系统设置页不可用，请手动前往手机设置") }
        }
        DarkMessageDialog.show(
            this,
            "息屏与后台运行",
            guide +
                "\n\n跟随系统：允许真实息屏。若锁屏后系统停止投屏授权，需要重新授权车机声音。" +
                "\n\n兼容暗屏：手机保持常亮，用黑色暗幕降低亮度，可用于锁屏会冻结投屏的机型；vivo/iQOO 默认开启。",
            actions =
                listOf(
                    DarkMessageDialog.Action("电池优化设置", DarkMessageDialog.Emphasis.PRIMARY) {
                        openSettings(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    },
                    DarkMessageDialog.Action("应用后台设置") {
                        openSettings(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                ("package:" + packageName).toUri(),
                            ),
                        )
                    },
                    DarkMessageDialog.Action("知道了") {},
                ),
        )
    }

    /** 按连接前检查结果更新准备项 */
    private fun updateReadyRow() {
        refreshSleepPolicy()
        val preparation = ConnectionPreflight.inspect(this, SessionController.audioEnabledForSession)
        val shizukuState = ShellServiceGate.state(this)
        updateServiceRow(shizukuState)
        val btGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        var bondedCount = -1
        if (btGranted) {
            try {
                val adapter =
                    (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                        .adapter
                bondedCount = adapter?.bondedDevices?.size ?: -1
            } catch (_: SecurityException) {
                bondedCount = -1
            }
        }
        btBadge.visibility = if (bondedCount >= 0) View.VISIBLE else View.GONE
        if (bondedCount >= 0) {
            val target = TargetDevicePrefs.get(this)
            if (bondedCount > 0) {
                PhonePanelUi.styleStatusBadge(btBadge, ready = true)
                btBadge.text = "就绪"
                btDesc.text =
                    if (target != null) {
                        "已配对 · 仅连接这台车机"
                    } else {
                        "请选择要连接的车机"
                    }
            } else {
                PhonePanelUi.styleStatusBadge(btBadge, ready = false)
                btBadge.text = "未配对"
                btDesc.text = "点击选择，扫描并配对你的车机"
            }
            // 目标设备已不在已配对列表（如系统里删了配对）时提示重选
            if (target != null && bondedCount > 0) {
                val stillBonded =
                    try {
                        (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                            .adapter
                            ?.bondedDevices
                            ?.any { TargetDevicePrefs.matches(target, it.address) } == true
                    } catch (_: SecurityException) {
                        true
                    }
                if (!stillBonded) {
                    PhonePanelUi.styleStatusBadge(btBadge, ready = false)
                    btBadge.text = "需重选"
                    btDesc.text = "配对已失效，请重新选择车机"
                }
            }
            // 弹窗内可扫描并直接配对未配对设备，因此不再只在「已有配对」时显示：
            // 一台都没配对时，这里正是用户的入口。
            btnPickTarget.visibility = View.VISIBLE
        } else {
            btnPickTarget.visibility = View.GONE
        }
        val btMode = BtConnectionPrefs.get(this, TargetDevicePrefs.get(this)?.address)
        findViewById<TextView>(R.id.btnBtConnectionMode).text = getString(R.string.ui_bluetooth_strategy, btMode.title)
        val projectionReady =
            CarPlayService.mediaProjection != null
        if (projectionReady) {
            btnAudio.text = "已授权"
            btnAudio.setAppearanceTextColor(R.color.m3_ok)
            audioDesc.text = "投屏权限已就绪 · 连接时可选开启"
        } else {
            btnAudio.text = "授权"
            btnAudio.setAppearanceTextColor(R.color.m3_primary)
            audioDesc.text = "内置服务优先采集 · 此处可申请兼容投屏授权"
        }
        val targetReady = preparation.checks.any { it.requirement == ConnectionPreflight.Requirement.TARGET && it.ready }
        if (!targetReady && btGranted) {
            btBadge.visibility = View.VISIBLE
            PhonePanelUi.styleStatusBadge(btBadge, ready = false)
            btBadge.text = "待准备"
            btDesc.text = "请选择已配对的车机后再连接"
        }
        if (!btGranted) {
            btDesc.text = "选择车机时请允许附近设备权限"
        }
        btnPickTarget.visibility = View.VISIBLE
        readyCount.text =
            getString(R.string.ui_ready_count, preparation.checks.count { it.ready }.toString(), preparation.checks.size.toString())
        findViewById<TextView>(R.id.environmentStatus).text =
            getString(R.string.connection_preflight_status, preparation.describe())
        if (heroState == HeroState.IDLE) {
            ctaLabel.text =
                when {
                    connectionCheckRunning -> "正在检查环境…"
                    preparation.ready -> "启动连接"
                    else -> "检查连接环境"
                }
            ctaButton.isEnabled = !connectionCheckRunning && !startupCheckRunning
            if (!connectionCheckRunning && !startupCheckRunning && pendingResidualDisplays == null) {
                heroSubline.text =
                    if (preparation.ready) {
                        "连接环境已就绪，可以启动无线连接"
                    } else {
                        preparation.missing.firstOrNull()?.let { "下一步：${it.requirement.action}，详见连接准备" } ?: "请检查连接准备"
                    }
            }
        }
    }

    /** 刷新准备清单中的内置服务状态，未就绪时点击打开激活引导 */
    private fun updateServiceRow(state: ShellServiceGate.State) {
        val ready = state == ShellServiceGate.State.READY
        PhonePanelUi.styleStatusBadge(shizukuBadge, ready)
        shizukuBadge.text = if (ready) "就绪" else "未就绪"
        shizukuDesc.text =
            when (state) {
                ShellServiceGate.State.READY -> "shell 权限已就绪 · 虚拟屏与触摸可用"
                ShellServiceGate.State.STOPPED -> "尚未恢复——点此查看 WLAN 和无线调试要求"
                ShellServiceGate.State.ACTIVATING -> "正在激活——点此查看进度"
                ShellServiceGate.State.INPUT_UNAVAILABLE -> "权限服务在线——点此重新检查触控"
                ShellServiceGate.State.NEEDS_PAIRING -> "尚未配对——点此完成首次激活"
            }
    }

    // ---- 目标车机选择弹窗（扫描/配对状态）----

    /** 当前打开的弹窗（扫描回调要往里加行；关闭时统一收尾）。 */
    private var targetPicker: androidx.appcompat.app.AlertDialog? = null

    private var targetPickerReceiver: BroadcastReceiver? = null

    /** 弹窗内已展示的扫描结果（按地址去重；已配对的单独进上组，不进这里）。 */
    private val scannedTargets = LinkedHashMap<String, TargetPickerEntry>()

    /** 扫描期间点过「配对」的设备地址（配对完成后自动选中为目标）。 */
    private var pendingBondAddress: String? = null

    private data class TargetPickerEntry(
        val address: String,
        val name: String,
        val rssi: Int,
        val bonded: Boolean,
    )

    /** 扫描权限被拒后置位：授权回调里继续（弹窗可能已关，则忽略）。 */
    private var pendingTargetScan = false

    private val targetScanPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("BT-SCAN: BLUETOOTH_SCAN 授权: $granted")
            if (granted && pendingTargetScan) {
                pendingTargetScan = false
                targetPicker?.let { startTargetScan(it) }
            } else if (!granted) {
                pendingTargetScan = false
                targetPicker?.let { _ ->
                    updatePickerStatus("未授予「附近的设备」权限——无法扫描；可到系统设置授权后重开本弹窗", warn = true)
                    pickerToast("未授予「附近的设备」权限——无法扫描附近设备")
                }
            }
        }

    /**
     * 选择目标车机，已配对设备可直接选择，附近设备配对成功后设为目标
     * 连接或身份检查期间禁止切换，清除目标后需重新选择
     */
    private fun showTargetPicker() {
        if (connectionCheckRunning || SessionController.isSessionRunning() || SessionController.sessionStarting.get()) {
            snack("请先取消连接或断开会话，再选择目标车机")
            return
        }
        val missingPermissions =
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
        if (missingPermissions.isNotEmpty()) {
            pendingTargetPickerAfterPermission = true
            permissionLauncher.launch(missingPermissions.toTypedArray())
            return
        }
        val adapter =
            try {
                (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                    .adapter
            } catch (_: Exception) {
                null
            }
        if (adapter == null || !adapter.isEnabled) {
            snack("蓝牙未开启——请先开启蓝牙")
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_target_picker, null)
        val dialog =
            androidx.appcompat.app.AlertDialog
                .Builder(this)
                .setView(view)
                .create()
        targetPicker = dialog
        scannedTargets.clear()
        pendingBondAddress = null
        view.findViewById<TextView>(R.id.btnCancelPicker).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.btnRescan).setOnClickListener { startTargetScan(dialog) }
        renderBondedGroup(view)
        dialog.setOnDismissListener {
            stopTargetScan()
            targetPicker = null
            pendingBondAddress = null
            updateReadyRow()
        }
        dialog.setOnShowListener {
            // 面板自带圆角：窗口底色必须透明，否则四角露出方角
            PanelWindow.apply(dialog, this)
        }
        dialog.show()
        startTargetScan(dialog)
    }

    /** 显示已配对设备和清除入口，连接前需选定目标 */
    private fun renderBondedGroup(view: View) {
        val list = view.findViewById<LinearLayout>(R.id.bondedList)
        val empty = view.findViewById<TextView>(R.id.bondedEmpty)
        list.removeAllViews()
        val current = TargetDevicePrefs.get(this)
        // 保留清除旧目标的入口，但不能把未指定目标显示为可直接连接
        view.findViewById<LinearLayout>(R.id.autoRowContainer).apply {
            removeAllViews()
            addView(
                inflateTargetRow(
                    name = "清除目标（连接前需重新选择）",
                    address = "清除后暂不可连接，需选择已配对的车机",
                    action = if (current == null) "当前" else "选择",
                    highlighted = current == null,
                    iconTint = R.color.m3_outline,
                ) {
                    if (connectionCheckRunning || SessionController.isSessionRunning() || SessionController.sessionStarting.get()) {
                        pickerNotice("连接已开始，不能清除当前目标；请先取消或断开")
                        return@inflateTargetRow
                    }
                    TargetDevicePrefs.set(this@MainActivity, null)
                    SessionController.clearWifiCredentials()
                    log("TARGET: 已清除目标，启动守卫将要求重新选择已配对的车机")
                    // 先关弹窗再提示（snackbar 在 Activity 窗口内，会被弹窗盖住）
                    targetPicker?.dismiss()
                    snack("已清除目标，请选择车机后再启动连接")
                },
            )
        }
        val bonded = bondedDevices()
        empty.visibility = if (bonded.isEmpty()) View.VISIBLE else View.GONE
        bonded.forEach { device ->
            val address = device.address
            val name = deviceDisplayName(device)
            val isCurrent = current != null && TargetDevicePrefs.matches(current, address)
            list.addView(
                inflateTargetRow(
                    name = name,
                    address = address,
                    action = if (isCurrent) "当前" else "选择",
                    highlighted = isCurrent,
                    iconTint = R.color.m3_primary,
                ) {
                    setTarget(device, name, closePicker = true)
                },
            )
        }
    }

    /** 设置目标蓝牙策略，连接期间不可切换 */
    private fun showBluetoothConnectionMode() {
        if (SessionController.isSessionRunning() || SessionController.sessionStarting.get()) {
            snack("请先取消连接或断开会话，再切换蓝牙连接策略")
            return
        }
        val target = TargetDevicePrefs.get(this)
        val modes = BtConnectionPrefs.Mode.entries
        val current = BtConnectionPrefs.get(this, target?.address)
        DarkMessageDialog.show(
            this,
            "蓝牙策略 · ${target?.name ?: "自动模式"}",
            "当前策略：${current.title}",
            modes.map { mode ->
                DarkMessageDialog.Action(
                    mode.title,
                    if (mode == current) DarkMessageDialog.Emphasis.PRIMARY else DarkMessageDialog.Emphasis.TEXT,
                ) {
                    if (SessionController.isSessionRunning() || SessionController.sessionStarting.get()) {
                        snack("连接已启动，请先取消连接再修改策略")
                    } else {
                        BtConnectionPrefs.set(this, target?.address, mode)
                        log("BT-POLICY: ${target?.name ?: "自动模式"} 已选择 ${mode.title}")
                        updateReadyRow()
                    }
                }
            } + DarkMessageDialog.Action("取消") {},
        )
    }

    /** 单个设备行（两组共用；highlighted 时名称用主色）。 */
    private fun inflateTargetRow(
        name: String,
        address: String,
        action: String,
        highlighted: Boolean,
        iconTint: Int,
        onClick: () -> Unit,
    ): View {
        val row = layoutInflater.inflate(R.layout.item_target_device, LinearLayout(this), false)
        row.setAppearanceBackground(if (highlighted) R.drawable.bg_bt_device_row_current else R.drawable.bg_bt_device_row)
        row.findViewById<TextView>(R.id.devName).apply {
            text = name
            setAppearanceTextColor(if (highlighted) R.color.m3_primary else R.color.m3_on_surface)
        }
        row.findViewById<TextView>(R.id.devAddr).text = address
        row.findViewById<TextView>(R.id.devAction).apply {
            // 当前项通过勾选与实心主题色标识，文字使用配套前景色
            if (highlighted) {
                text = getString(R.string.ui_selected_action, action)
                setAppearanceTextColor(R.color.m3_on_primary)
                setAppearanceBackground(R.drawable.bg_pill_current)
            } else {
                text = action
                setAppearanceTextColor(R.color.m3_primary)
                setAppearanceBackground(R.drawable.bg_tbtn)
            }
            // 实心/描边切换后 padding 由 drawable 决定，不需要重设
            setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
        }
        row.findViewById<ImageView>(R.id.devIcon).setAppearanceImageTint(iconTint)
        row.setOnClickListener { onClick() }
        return row
    }

    /** 扫描附近蓝牙设备，按地址去重并排除已配对项；点击未配对设备发起系统配对 */
    private fun startTargetScan(dialog: androidx.appcompat.app.AlertDialog) {
        val adapter =
            try {
                (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                    .adapter
            } catch (_: Exception) {
                null
            } ?: return
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingTargetScan = true
            updatePickerStatus("需要「附近的设备」权限才能扫描…", warn = true)
            targetScanPermissionLauncher.launch(Manifest.permission.BLUETOOTH_SCAN)
            return
        }
        val scannedList = dialog.findViewById<LinearLayout>(R.id.scannedList) ?: return
        // 已配对的地址集合：扫描结果里剔掉（避免同一设备在两处出现）
        val bondedAddresses =
            bondedDevices()
                .map { it.address }
                .toSet()
        scannedTargets.clear()
        scannedList.removeAllViews()
        updatePickerStatus("正在扫描附近的蓝牙设备…")
        stopTargetScan()
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            val device =
                                IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                                    ?: return
                            val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, 0).toInt()
                            onTargetFound(dialog, device, rssi, bondedAddresses)
                        }

                        BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                            val device =
                                IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                                    ?: return
                            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                            onBondStateChanged(dialog, device, state)
                        }
                    }
                }
            }
        targetPickerReceiver = receiver
        val filter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
        // 系统（蓝牙栈）发出的广播：必须用 EXPORTED 才能在部分 ROM 上收到
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        val started =
            try {
                adapter.startDiscovery()
            } catch (_: SecurityException) {
                false
            }
        if (!started) {
            updatePickerStatus("扫描启动失败——请确认蓝牙已开启后点「重新扫描」", warn = true)
        } else {
            log("BT-SCAN: 开始扫描附近蓝牙设备")
        }
    }

    /** 扫描到一台设备：已配对→忽略；新设备→加一行（点「配对」发起系统配对）。 */
    private fun onTargetFound(
        dialog: androidx.appcompat.app.AlertDialog,
        device: BluetoothDevice,
        rssi: Int,
        bondedAddresses: Set<String>,
    ) {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return
            }
        if (address in bondedAddresses || scannedTargets.containsKey(address)) return
        val name = deviceDisplayName(device)
        scannedTargets[address] = TargetPickerEntry(address, name, rssi, bonded = false)
        val list = dialog.findViewById<LinearLayout>(R.id.scannedList) ?: return
        // 扫描仍在继续：状态行保留并计数，用户知道还可以继续等其它设备。
        // 刻意不走 updatePickerStatus——它会把列表滚到状态行，发现新设备时抢滚动位置。
        dialog.findViewById<TextView>(R.id.scanStatus)?.apply {
            text = getString(R.string.ui_scanning_found, scannedTargets.size.toString())
            setAppearanceTextColor(R.color.m3_mono_dim)
            visibility = View.VISIBLE
        }
        list.addView(
            inflateTargetRow(
                name = name,
                address = "$address · ${rssi}dBm",
                action = "配对",
                highlighted = false,
                iconTint = R.color.m3_amber,
            ) {
                beginBond(device)
            },
        )
    }

    /** 点击未配对设备：先停扫描（扫描期间配对易失败），再 createBond()。 */
    private fun beginBond(device: BluetoothDevice) {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return
            }
        stopDiscoveryOnly()
        pendingBondAddress = address
        val name = deviceDisplayName(device)
        val ok =
            try {
                device.createBond()
            } catch (error: SecurityException) {
                log("BT-PAIR: 配对「$name」失败 - 缺少权限：${error.message}")
                false
            }
        if (ok) {
            log("BT-PAIR: 已发起与「$name」($address) 的配对——请在系统弹窗确认")
            pickerNotice("正在配对「$name」：请在系统弹窗确认")
        } else {
            pendingBondAddress = null
            log("BT-PAIR: 配对「$name」($address) 发起失败（设备可能已配对或蓝牙忙）")
            pickerNotice("无法发起配对——请在系统蓝牙设置中手动配对")
        }
    }

    /** 弹窗内更新状态行，并用系统 Toast 提示，避免被窗口遮住 */
    private fun pickerNotice(message: String) {
        pickerToast(message)
        updatePickerStatus(message, warn = true)
    }

    /** 弹窗打开时用系统 Toast 显示短提示 */
    private fun pickerToast(message: String) {
        android.widget.Toast
            .makeText(this, message, android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    /** 更新弹窗内的状态行并滚动到可见位置（`scanStatus` 在「附近的设备」标题下）。 */
    private fun updatePickerStatus(
        message: String,
        warn: Boolean = false,
    ) {
        val dialog = targetPicker ?: return
        val status = dialog.findViewById<TextView>(R.id.scanStatus) ?: return
        status.text = message
        status.setAppearanceTextColor(if (warn) R.color.m3_amber else R.color.m3_mono_dim)
        status.visibility = View.VISIBLE
        // 状态行在视口外才滚动（用户在浏览上面的已配对列表时不该被抢走位置）。
        status.post {
            val scroll = dialog.findViewById<android.widget.ScrollView>(R.id.pickerScroll) ?: return@post
            val content = scroll.getChildAt(0) ?: return@post
            val top = offsetWithin(status, content)
            val bottom = top + status.height
            val visibleTop = scroll.scrollY
            val visibleBottom = visibleTop + scroll.height
            if (top < visibleTop || bottom > visibleBottom) {
                val y = top - (12 * resources.displayMetrics.density).toInt()
                scroll.smoothScrollTo(0, y.coerceAtLeast(0))
            }
        }
    }

    /** view 相对 ancestor 的内容偏移（沿父链累加 top；ancestor 必须是其祖先）。 */
    private fun offsetWithin(
        view: View,
        ancestor: View,
    ): Int {
        var current: View? = view
        var y = 0
        while (current != null && current !== ancestor) {
            y += current.top
            current = current.parent as? View
        }
        return y
    }

    /** 配对状态变化：成功后设为目标并关弹窗；失败给出提示。 */
    private fun onBondStateChanged(
        dialog: androidx.appcompat.app.AlertDialog,
        device: BluetoothDevice,
        state: Int,
    ) {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return
            }
        if (address != pendingBondAddress) return
        when (state) {
            BluetoothDevice.BOND_BONDED -> {
                pendingBondAddress = null
                val name = deviceDisplayName(device)
                log("BT-PAIR: ✓ 已与「$name」($address) 完成配对——自动设为目标")
                // 先关弹窗再提示：snackbar 在 Activity 窗口内，弹窗未关时会被盖住
                dialog.dismiss()
                setTarget(device, name)
                // 仅用户选择仅配对策略时处理音频配置文件，未知车机默认保留连接
                val app = applicationContext
                Bg.run("bt-pair-only") {
                    repeat(2) { attempt ->
                        if (BtConnectionPrefs.get(app, address) != BtConnectionPrefs.Mode.PAIR_ONLY ||
                            BtSessionGate.controlActive ||
                            BtSessionGate.sessionActive ||
                            SessionController.isSessionRunning()
                        ) {
                            return@run
                        }
                        // 配对回调也必须原子占用，不能在状态检查后与新控制通道交错执行
                        val lease = BtSessionGate.acquireOutbound() ?: return@run
                        try {
                            val dropped = lease.withOwnership { BtProfiles.disconnect(app, device) }
                            if (dropped.isNotEmpty()) {
                                val left = lease.withOwnership { BtProfiles.stillConnected(app, address) }
                                log(
                                    "BT-PAIR: 已按所选策略请求断开 ${dropped.joinToString("/")} 连接" +
                                        "，仍在线=${left.joinToString("/").ifEmpty { "无" }}",
                                )
                            }
                        } finally {
                            lease.close()
                        }
                        if (attempt == 0) {
                            try {
                                Thread.sleep(1_500)
                            } catch (_: InterruptedException) {
                                return@run
                            }
                        }
                    }
                }
            }

            BluetoothDevice.BOND_NONE -> {
                pendingBondAddress = null
                log("BT-PAIR: 与 $address 配对未完成（用户取消或失败）")
                // 弹窗仍开着：用 Toast（浮在弹窗之上）+ 弹窗内状态行，不用被遮挡的 snackbar
                pickerNotice("配对未完成——可再点「配对」重试，或到系统蓝牙设置手动配对")
            }
            // BOND_BONDING：继续等（系统确认框未应答）
        }
    }

    /**
     * 设置连接目标
     *
     * @param closePicker 是否先关闭选择弹窗再提示
     */
    private fun setTarget(
        device: BluetoothDevice,
        name: String,
        closePicker: Boolean = false,
    ) {
        if (connectionCheckRunning || SessionController.isSessionRunning() || SessionController.sessionStarting.get()) {
            pickerNotice("连接已开始，本次不切换目标；请先取消或断开后重新选择")
            return
        }
        if (closePicker) targetPicker?.dismiss()
        val previous = TargetDevicePrefs.get(this)
        TargetDevicePrefs.set(this, TargetDevicePrefs.Target(device.address, name))
        if (previous == null || !previous.address.equals(device.address, ignoreCase = true)) {
            SessionController.clearWifiCredentials()
        }
        log(
            "TARGET: 目标车机已设为「$name」(${device.address})" +
                "——出站只连该设备、入站拒绝其它设备" +
                if (previous != null && previous.address != device.address) {
                    "（原目标「${previous.name}」）"
                } else {
                    ""
                },
        )
        snack("目标已设为「$name」——只向该设备发请求")
        updateReadyRow()
    }

    /** 停掉扫描并注销监听（弹窗关闭/重扫时调用）。 */
    private fun stopTargetScan() {
        stopDiscoveryOnly()
        targetPickerReceiver?.let {
            runCatching { unregisterReceiver(it) }
            targetPickerReceiver = null
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun stopDiscoveryOnly() {
        // 调用点均已先确认 BLUETOOTH_SCAN 已授予（扫描流程入口），且此处整体捕获异常
        try {
            (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                .adapter
                ?.cancelDiscovery()
        } catch (_: Exception) {
        }
    }

    private fun bondedDevices(): List<BluetoothDevice> =
        try {
            (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                .adapter
                ?.bondedDevices
                ?.toList()
                .orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }

    /** 设备展示名（无名称时退回地址）；读名需要 BLUETOOTH_CONNECT，异常时用地址。 */
    private fun deviceDisplayName(device: BluetoothDevice): String =
        try {
            device.name?.takeIf { it.isNotBlank() } ?: device.address
        } catch (_: SecurityException) {
            try {
                device.address
            } catch (_: SecurityException) {
                "未知设备"
            }
        }

    /** 设备营销名（ro.product.marketname），取不到时退回 Build.MODEL。 */
    // 仅只读营销名称，厂商无此字段或隐藏接口不可用时回退 Build.MODEL
    @android.annotation.SuppressLint("PrivateApi")
    private fun deviceName(): String =
        try {
            val sp = Class.forName("android.os.SystemProperties")
            val get = sp.getMethod("get", String::class.java)
            (get.invoke(null, "ro.product.marketname") as? String)
                ?.takeIf { it.isNotBlank() } ?: Build.MODEL
        } catch (_: Exception) {
            Build.MODEL
        }

    private fun updateServiceChip() {
        val state = ShellServiceGate.state(this)
        val ready = state == ShellServiceGate.State.READY
        if (ready && !lastObservedServiceReady) ensureNotificationAccessAsync()
        lastObservedServiceReady = ready
        shizukuChip.text =
            when (state) {
                ShellServiceGate.State.READY -> "服务就绪"
                ShellServiceGate.State.ACTIVATING -> "恢复中"
                else -> "待激活"
            }
        shizukuChip.contentDescription = ShellServiceGate.guidanceOf(state).title
        io.github.shaonialife.buicklinkplus.ui.AppearanceBindings.compoundTint(
            shizukuChip,
            if (ready) R.color.m3_ok_dot else R.color.m3_amber,
        )
        // 机型来自系统公开信息，不需要额外权限，连接条件仍由准备清单表达
        val model =
            Build.MODEL
                .orEmpty()
                .trim()
                .ifEmpty { "Android 手机" }
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val device = if (manufacturer.isBlank() || model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
        deviceSubtitle.text = getString(R.string.phone_device_model, device, Build.VERSION.RELEASE)
    }

    // ==================== 通用 UI 工具 ====================

    private fun log(message: String) {
        // 写入进程级缓冲（logcat + 文件 + 挂屏渲染均由 SessionController 统一处理）
        SessionController.appendLog(message)
    }

    /** 诊断面板共用的 shell 调用，只能在后台执行 */
    private fun shExec(
        cmd: String,
        timeoutMs: Long = 4_000L,
    ): Shell.Result =
        Shell
            .exec(cmd, timeoutMs)

    private fun sh(cmd: String): String = shExec(cmd).output

    /** 导出日志到下载目录并弹窗告知位置；默认导出本次启动日志，长按导出全部 */
    private fun exportLogFile(scope: LogExporter.Scope = LogExporter.Scope.CURRENT_SESSION) {
        snack(if (scope == LogExporter.Scope.ALL) "正在导出全部日志…" else "正在导出本次启动的日志…")
        val fallback = SessionController.logText()
        Bg.run("export-log") {
            val result = LogExporter.export(applicationContext, fallback, scope)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result == null) {
                    snack("导出失败——请检查存储空间后重试", 4_000L)
                    return@runOnUiThread
                }
                log("日志已导出（${scope.label}）: ${result.displayPath}（${result.bytes} 字节）")
                val scopeNote =
                    if (scope == LogExporter.Scope.CURRENT_SESSION) {
                        "仅含本次 App 启动之后的日志。需要更早的历史，请长按导出按钮。"
                    } else {
                        "包含历史日志（文件上限 2MB，超出时只保留最近一段）。"
                    }
                DarkMessageDialog.show(
                    this,
                    title = "日志已导出",
                    message =
                        "文件位置：\n${result.displayPath}\n\n" +
                            "大小：${formatBytes(result.bytes)}\n$scopeNote\n\n" +
                            "可在「文件管理 → 下载 → Buick Link＋」中找到，也可直接分享给开发者排查问题。",
                    actions =
                        listOf(
                            DarkMessageDialog.Action("打开文件", DarkMessageDialog.Emphasis.PRIMARY) {
                                openExportedFile(result.uri, result.displayPath)
                            },
                            DarkMessageDialog.Action("打开文件夹") {
                                openExportFolder(result.displayPath)
                            },
                            DarkMessageDialog.Action("分享文件") {
                                shareExportedFile(result.uri)
                            },
                            DarkMessageDialog.Action("关闭", onClick = {}),
                        ),
                )
            }
        }
    }

    /** 格式化文件大小，小于 1KB 时显示字节 */
    private fun formatBytes(bytes: Long): String =
        when {
            bytes < 1024 -> "$bytes 字节"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        }

    /** 打开日志目录，优先系统文件管理器，失败时依次回退到通用入口和文字提示 */
    private fun openExportFolder(displayPath: String) {
        val folder = displayPath.substringBeforeLast('/')
        val relative = folder.removePrefix("/storage/emulated/0/")
        val dirUri =
            (
                "content://com.android.externalstorage.documents/document/" +
                    "primary%3A" + Uri.encode(relative).replace("/", "%2F")
            ).toUri()
        val explicit =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(dirUri, "vnd.android.document/directory")
                component =
                    android.content.ComponentName(
                        "com.android.documentsui",
                        "com.android.documentsui.files.FilesActivity",
                    )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val implicit =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(dirUri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val storageRoot =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    ("content://com.android.externalstorage.documents/root/primary").toUri(),
                    "vnd.android.document/directory",
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        for (intent in listOf(explicit, implicit, storageRoot)) {
            if (runCatching { startActivity(intent) }.isSuccess) {
                if (intent !== explicit) snack("请按路径打开：$folder", 5_000L)
                return
            }
        }
        snack("请到「文件管理 → 下载 → Buick Link＋」查看", 5_000L)
    }

    /** 用系统默认应用打开（多数 ROM 会落到文件管理器 / 文本查看器预览）。 */
    private fun openExportedFile(
        uri: Uri,
        displayPath: String,
    ) {
        val view =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (runCatching { startActivity(view) }.isSuccess) return
        // 无文本查看器时退到「文件管理器」：DocumentsUI 的内部存储根（用户按路径进 下载/Buick Link＋）
        val storageRoot =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    ("content://com.android.externalstorage.documents/root/primary").toUri(),
                    "vnd.android.document/root",
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (runCatching { startActivity(storageRoot) }.isSuccess) {
            snack("请按路径打开：$displayPath", 5_000L)
            return
        }
        snack("没有可打开文件的应用——请到文件管理器中按上述路径查找", 5_000L)
    }

    /** 分享日志文件（微信/QQ/邮件等），内容 URI 带临时读权限。 */
    private fun shareExportedFile(uri: Uri) {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        runCatching { startActivity(Intent.createChooser(send, "分享日志文件")) }
            .onFailure { snack("没有可分享的应用") }
    }

    private fun updateLogBottomButton() {
        findViewById<ImageView>(R.id.btnLogBottom)?.visibility =
            if (consoleAtBottom()) View.INVISIBLE else View.VISIBLE
    }

    /** 日志内容最大滚动偏移。 */
    private fun consoleMaxScroll(): Int {
        val layout = logText.layout ?: return 0
        return (
            layout.getLineBottom(layout.lineCount - 1) +
                logText.paddingBottom - logText.height
        ).coerceAtLeast(0)
    }

    /** 判断是否滚动到底部，允许 40px 误差 */
    private fun consoleAtBottom(): Boolean = consoleMaxScroll() - logText.scrollY <= 40

    private fun scrollConsoleToBottom() {
        // +16px 过冲补齐舍入差（控制台深色底，过冲不可见；View 不钳制但视觉无感）
        logText.scrollTo(0, consoleMaxScroll() + 16)
        logText.post { updateLogBottomButton() }
    }

    private fun renderConsole(text: String) {
        // 改文本前已在底部（或用户上翻阅读中）：决定本次是否自动跟底
        val follow = consoleAtBottom()
        logText.text =
            if (SessionController.logEnabled) text else "日志采集已关闭（打开「监听日志」开关恢复）"
        logText.post {
            if (follow) scrollConsoleToBottom() else updateLogBottomButton()
        }
    }

    /** 逐行追加日志，超限后从会话缓冲重新裁剪，避免每次重绘整段文本 */
    private fun appendConsole(line: String) {
        val follow = consoleAtBottom()
        if (logText.text.length > 44_000) {
            renderConsole(SessionController.logText())
            return
        }
        logText.append(line)
        logText.post {
            if (follow) scrollConsoleToBottom() else updateLogBottomButton()
        }
    }

    private var snackRunnable: Runnable? = null

    /** 自绘 M3 Snackbar（底部反色胶囊，替代 Toast）。 */
    private fun snack(
        message: String,
        durationMs: Long = 2_400L,
    ) {
        snackbar.text = message
        snackbar.visibility = View.VISIBLE
        snackbar.translationY = snackbar.height + 60f
        snackbar
            .animate()
            .translationY(0f)
            .setDuration(260L)
            .start()
        snackRunnable?.let { mainHandler.removeCallbacks(it) }
        snackRunnable =
            Runnable {
                snackbar
                    .animate()
                    .translationY(snackbar.height + 60f)
                    .setDuration(260L)
                    .withEndAction { snackbar.visibility = View.GONE }
                    .start()
            }.also { mainHandler.postDelayed(it, durationMs) }
    }

    /** 从 dumpsys display 解析 CarPlay 屏 ID 和名称，不能把 uniqueId 末尾代次当屏 ID */
    private fun parseCarPlayDisplays(dump: String): List<Pair<Int, String>> =
        (
            Regex("displayId=(\\d+), uniqueId='[^'\\r\\n]*CarPlay(Dock|AppHi|App|Map)")
                .findAll(dump)
                .map { it.groupValues[1].toInt() to "CarPlay${it.groupValues[2]}" } +
                // 系统遍历尚未更新触摸视口时，逻辑显示已存在，不能只凭视口为空放行
                Regex("""DisplayInfo\{"CarPlay(Dock|AppHi|App|Map)[^"\r\n]*", displayId (\d+)[^\r\n]* type VIRTUAL,""")
                    .findAll(dump)
                    .map { it.groupValues[2].toInt() to "CarPlay${it.groupValues[1]}" }
        ).distinctBy { it.first }.toList()

    /** 读取 shell 全局状态（后台线程 + UI 回填）。[startupCheck] 时顺带做残留虚拟屏检测。 */
    private fun refreshShellState(startupCheck: Boolean = false) {
        if (startupCheck) {
            startupCheckRunning = true
            tickHero()
        }
        shellStateText.text = "读取中…"
        Bg.run("shell-state") {
            if (io.github.shaonialife.buicklinkplus.shizuku.EmbeddedServiceProvider.isRegistered) {
                CarInputClient.awaitReady(applicationContext)
            }
            if (ShellServiceGate
                    .state(this) !=
                ShellServiceGate.State.READY
            ) {
                runOnUiThread {
                    startupCheckRunning = false
                    if (heroState == HeroState.IDLE) applyHeroState(HeroState.IDLE)
                    shellStateText.setText(R.string.ui_shell_read_unavailable)
                }
                return@run
            }

            val letterbox =
                Regex("Aspect ratio:\\s*([0-9.]+)")
                    .find(sh("cmd window get-letterbox-style"))
                    ?.groupValues
                    ?.get(1) ?: "?"
            val forceResizable = sh("settings get global force_resizable_activities").ifBlank { "null" }
            val overlay = sh("settings get global overlay_display_devices").ifBlank { "null" }
            val wmSize = sh("wm size").replace("\n", " / ").ifBlank { "?" }
            val wmDensity = sh("wm density").replace("\n", " / ").ifBlank { "?" }
            val carDisplays = parseCarPlayDisplays(sh("dumpsys display"))
            val displays = carDisplays.joinToString(", ") { "${it.second}(${it.first})" }
            val timeout = sh("settings get system screen_off_timeout").ifBlank { "?" }
            val text =
                buildString {
                    appendLine("letterbox 宽高比: $letterbox  （1.05=会话调优生效；0.0=系统默认）")
                    appendLine("force_resizable: $forceResizable  （平板模式实验；默认 null/0）")
                    appendLine("overlay_display_devices: $overlay  （叠加屏实验残留；应为 null）")
                    appendLine("wm size: $wmSize")
                    appendLine("wm density: $wmDensity")
                    appendLine("CarPlay 虚拟屏: " + displays.trim().replace("\n", ", ").ifEmpty { "无" })
                    appendLine("screen_off_timeout: $timeout  （App 常亮功能管理）")
                }
            runOnUiThread { shellStateText.text = text.trim() }
            if (startupCheck) {
                var residual = carDisplays.filter { it.first !in currentSessionDisplayIds() }
                if (residual.isNotEmpty()) {
                    log(
                        "SHELL: 启动检测到 ${residual.size} 个残留虚拟屏: " +
                            residual.joinToString(", ") { "${it.second}(${it.first})" },
                    )
                    // 残留屏 = 上次会话异常退出（进程被杀）：横屏覆盖的会话末复原
                    // 也没执行——一并清理，避免应用在手机侧残留横屏状态
                    runCatching {
                        val reset =
                            AppOrientationCompat
                                .resetAll(applicationContext)
                        if (reset > 0) log("ORIENTATION: 残留会话的横屏覆盖已复原 $reset 个")
                    }
                    // 先清理残留屏，兼容迁移前独立服务进程持有的屏；跨进程屏须回收原持有进程
                    val released = releaseDisplays(residual.map { it.first })
                    log("SHELL: 启动残留自动清理（释放 $released/${residual.size}）")
                    if (released > 0) {
                        residual =
                            parseCarPlayDisplays(sh("dumpsys display"))
                                .filter { it.first !in currentSessionDisplayIds() }
                    }
                }
                runOnUiThread {
                    startupCheckRunning = false
                    if (residual.isNotEmpty()) {
                        // 用户「暂不」时保留 pending，状态旁的按钮可再次发起清理
                        pendingResidualDisplays = residual
                        updateResidualChip()
                        if (heroState == HeroState.IDLE) applyHeroState(HeroState.IDLE)
                        promptResidualDisplays(residual)
                        // 自动展开日志卡：启动清理的两条记录直接可见（卡片默认折叠）
                        findViewById<View>(R.id.logBody).visibility = View.VISIBLE
                        findViewById<ImageView>(R.id.logChevron)
                            .animate()
                            .rotation(180f)
                            .setDuration(250L)
                            .start()
                    } else if (heroState == HeroState.IDLE) {
                        // 无残留：恢复「未连接」默认状态展示
                        applyHeroState(HeroState.IDLE)
                    }
                }
            }

            // 后台预热输入服务；残留扫描必须先完成，避免旧屏提前消失后遗漏方向策略复原
            CarInputClient.ensure(this)
        }
    }

    /** 当前会话持有的虚拟屏（不参与残留清理）。 */
    private fun currentSessionDisplayIds(): Set<Int> =
        buildSet {
            val launcher = VirtualLauncher
            listOf(
                launcher.dockDisplayId,
                launcher.appDisplayId,
                launcher.appHiDisplayId,
                launcher.mapDisplayId,
                launcher.gridDisplayId,
            ).forEach { if (it >= 0) add(it) }
        }

    /** 服务恢复后补查残留屏，沿用原释放流程并回读确认 */
    private fun prepareConnectionDisplays(): Boolean {
        val owner = CarInputClient.service ?: return false
        if (SessionController.isSessionRunning() || SessionController.sessionStarting.get()) return false
        val before = Shell.exec("dumpsys display", 5_000L)
        if (!before.ok || !before.output.contains("DISPLAY MANAGER")) {
            log("PREFLIGHT: 显示状态读取失败，本次未启动蓝牙连接")
            return false
        }
        if (CarInputClient.service !== owner || SessionController.isSessionRunning()) return false
        val residual = parseCarPlayDisplays(before.output).filter { it.first !in currentSessionDisplayIds() }
        if (residual.isNotEmpty()) {
            log("PREFLIGHT: 连接前发现残留虚拟屏 ${residual.joinToString { it.first.toString() }}，开始清理并回读")
            releaseDisplays(residual.map { it.first })
        }
        if (CarInputClient.service !== owner || SessionController.isSessionRunning()) return false
        val after = if (residual.isEmpty()) before else Shell.exec("dumpsys display", 5_000L)
        if (!after.ok || !after.output.contains("DISPLAY MANAGER") || CarInputClient.service !== owner) {
            log("PREFLIGHT: 残留清理结果无法确认，本次未启动蓝牙连接")
            return false
        }
        val remaining = parseCarPlayDisplays(after.output).filter { it.first !in currentSessionDisplayIds() }
        if (remaining.isNotEmpty()) {
            log("PREFLIGHT: 仍有残留虚拟屏 ${remaining.joinToString { it.first.toString() }}，本次未启动蓝牙连接")
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    pendingResidualDisplays = remaining
                    updateResidualChip()
                }
            }
            return false
        }
        log("PREFLIGHT: 显示状态已回读，未发现非当前会话的 CarPlay 残留屏")
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                pendingResidualDisplays = null
                updateResidualChip()
            }
        }
        return true
    }

    /** 发现残留 CarPlay 虚拟屏时询问是否清理 */
    private fun promptResidualDisplays(residual: List<Pair<Int, String>>) {
        if (isFinishing || isDestroyed) return
        val names = residual.joinToString("\n") { "· ${it.second}（displayId=${it.first}）" }
        DarkMessageDialog.show(
            activity = this,
            title = "检测到残留虚拟屏",
            message =
                "发现 ${residual.size} 个上次会话遗留的 CarPlay 虚拟屏：\n$names\n\n" +
                    "残留屏会持续占用显示资源，并可能影响下次连接。是否立即清理？",
            actions =
                listOf(
                    DarkMessageDialog.Action("暂不") {},
                    DarkMessageDialog.Action("清理", DarkMessageDialog.Emphasis.PRIMARY) {
                        pendingResidualDisplays = null
                        updateResidualChip()
                        if (heroState == HeroState.IDLE) applyHeroState(HeroState.IDLE)
                        Bg.run("residual-cleanup") {
                            val released = releaseDisplays(residual.map { it.first })
                            log("SHELL: 残留虚拟屏清理完成（释放 $released/${residual.size}）")
                            runOnUiThread {
                                snack("已释放 $released/${residual.size} 个残留屏")
                                refreshShellState()
                            }
                        }
                    },
                ),
            onCancel = {
                // 返回键/点外部关闭 = 暂不：保留英雄卡的「清理残留屏」入口
                updateResidualChip()
            },
        )
    }

    /** 英雄卡状态旁的「清理残留屏」按钮显隐（有待清理残留时出现）。 */
    private fun updateResidualChip() {
        btnResidualCleanup.visibility =
            if (pendingResidualDisplays != null) View.VISIBLE else View.GONE
    }

    /** 释放指定虚拟屏（CarInputClient 懒绑定 + 3 秒短等）。后台线程调用，返回成功数。 */
    private fun releaseDisplays(ids: List<Int>): Int {
        if (ids.isEmpty()) return 0
        var svc = CarInputClient.service
        if (svc == null) {
            CarInputClient
                .ensure(this)
            val deadline = SystemClock.uptimeMillis() + 3_000L
            while (svc == null && SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(150)
                svc = CarInputClient.service
            }
        }
        val bound = svc ?: return 0
        ids.forEach { runCatching { bound.releaseDisplay(it) } }
        // 未直接释放的屏可能属于迁移前的旧服务进程，当前服务无法跨进程释放
        // 回收旧进程让其随进程销毁释放，再以实际扫描结果复核（进程回收是异步的）。
        CarInputClient.reapStaleUserServices()
        val deadline = SystemClock.uptimeMillis() + 2_000L
        while (true) {
            val alive = parseCarPlayDisplays(sh("dumpsys display")).map { it.first }.toSet()
            val gone = ids.count { it !in alive }
            if (gone >= ids.size || SystemClock.uptimeMillis() >= deadline) return gone
            Thread.sleep(200)
        }
    }

    /** 一键把 shell 全局状态复位为系统默认值（含残留虚拟屏释放，保留当前会话屏）。 */
    private fun resetShellState() {
        shellStateText.text = "复位中…"
        Bg.run("shell-reset") {
            if (ShellServiceGate
                    .state(this) !=
                ShellServiceGate.State.READY
            ) {
                runOnUiThread { shellStateText.setText(R.string.ui_shell_reset_unavailable) }
                return@run
            }

            fun sh(cmd: String) =
                Shell
                    .exec(cmd, 5_000L)
            val steps =
                listOf(
                    "letterbox 宽高比" to "cmd window reset-letterbox-style aspectRatio",
                    "overlay 叠加屏" to "settings delete global overlay_display_devices",
                    "force_resizable" to "settings delete global force_resizable_activities",
                    "wm size" to "wm size reset",
                    "wm density" to "wm density reset",
                )
            val results =
                steps.map { (name, cmd) ->
                    val r = sh(cmd)
                    if (r.ok) "✓ $name" else "✗ $name（${r.output.take(40)}）"
                }
            // 残留虚拟屏释放：保留当前会话的各屏，其余 CarPlay* 屏逐一释放
            val ids =
                parseCarPlayDisplays(sh("dumpsys display").output)
                    .map { it.first }
                    .filter { it !in currentSessionDisplayIds() }
                    .toList()
            val released = releaseDisplays(ids)
            val summary = results.joinToString("；") + "；残留屏释放 $released/${ids.size}"
            log("SHELL-RESET: $summary")
            runOnUiThread {
                shellStateText.text =
                    getString(R.string.ui_shell_reset_result, results.joinToString("\n"), released.toString(), ids.size.toString())
                refreshShellState()
            }
        }
    }

    /** 悬浮窗权限请求：先弹说明弹窗，用户确认后再跳系统设置。 */
    private fun promptOverlayPermission(reason: String) {
        if (android.provider.Settings.canDrawOverlays(this)) return
        DarkMessageDialog.show(
            activity = this,
            title = "需要「显示在其他应用上层」权限",
            message =
                "$reason\n\n" +
                    "用途：无操作时在屏幕上盖一层暗幕（替代息屏，防止手机锁屏后车机触摸失效）。" +
                    "不会读取或遮挡其他应用内容，点击屏幕立即消失。",
            actions =
                listOf(
                    DarkMessageDialog.Action("暂不") {
                        log("DIMMER: 用户暂未授权悬浮窗（暗幕不可用，常亮仍生效）")
                    },
                    DarkMessageDialog.Action("去授权", DarkMessageDialog.Emphasis.PRIMARY) {
                        try {
                            startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    ("package:$packageName").toUri(),
                                ),
                            )
                        } catch (_: Exception) {
                            log("DIMMER: 无法打开授权页，请手动前往 设置>应用>特殊权限>显示在其他应用上层")
                        }
                    },
                ),
        )
    }

    override fun onResume() {
        super.onResume()
        io.github.shaonialife.buicklinkplus.update.TraceUpdater.maybeAutoCheck(this)
        mainHandler.post(heroTick)
        // 生命周期在 onResume 返回后才进入 RESUMED，下一次主线程调度再检查前台状态
        mainHandler.post { restoreServiceOnAppOpen() }
        // 旋转/重建后尚未接受：用原截止时刻续弹（不重新等 10 秒）
        if (needsDisclaimerAtLaunch(
                DisclaimerPrefs.acceptedVersion(this),
                DisclaimerPrefs.CURRENT_VERSION,
            ) &&
            activeDisclaimer?.isShowing != true
        ) {
            showDisclaimer(requireAcceptance = true)
        }
    }

    /**
     * 打开软件后复用已有配对自动恢复，离开前台或尚未接受使用说明时不发起
     */
    private fun restoreServiceOnAppOpen() {
        if (automaticRestoreCheckRunning ||
            isFinishing ||
            isDestroyed ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            needsDisclaimerAtLaunch(DisclaimerPrefs.acceptedVersion(this), DisclaimerPrefs.CURRENT_VERSION)
        ) {
            return
        }
        automaticRestoreCheckRunning = true
        val app = applicationContext
        Bg.run("app-service-restore") {
            val initialized =
                runCatching {
                    io.github.shaonialife.buicklinkplus.shizuku.EmbeddedAdb
                        .initialize(app)
                }.onFailure { log("SERVICE: 自动恢复读取身份失败 类型=${it.javaClass.simpleName}") }
                    .isSuccess
            runOnUiThread {
                automaticRestoreCheckRunning = false
                if (!initialized || isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    return@runOnUiThread
                }
                if (PairingService.restoreOnAppOpen(this)) log("SERVICE: 打开软件后自动恢复内置服务，无需再次点击激活")
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_DISCLAIMER_DEADLINE, disclaimerDeadlineAt)
        outState.putBoolean("settingsTab", phoneSettingsVisible)
        outState.putInt("settingsSection", phoneSettings?.currentSection ?: -1)
        outState.putBundle("settingsNavigation", phoneSettings?.saveNavigation())
        outState.putBoolean("settingsOpenedFromHome", settingsOpenedFromHome)
    }

    /** 旋转时保留视图状态，切换内容分栏并重算已打开的弹窗尺寸 */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isFinishing || isDestroyed) return
        applyContentLayout(landscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        PanelWindow.resizeShowing(this)
        // 目标车机弹窗不走 DarkMessageDialog，单独重算
        targetPicker?.window?.let {
            val width = (resources.displayMetrics.widthPixels * PanelWindow.WIDTH_FRACTION).toInt()
            it.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * 竖屏单列，横屏两列独立滚动，窄高窗口和大字体也能完整访问主操作
     */
    private fun applyContentLayout(landscape: Boolean) {
        val scroll = findViewById<android.widget.ScrollView>(R.id.mainScroll)
        val row = findViewById<android.widget.LinearLayout>(R.id.contentRow)
        val hero = findViewById<android.widget.LinearLayout>(R.id.heroCard)
        val heroScroll = homeHeroScroll ?: return
        val detail = findViewById<android.widget.LinearLayout>(R.id.detailColumn)
        val density = resources.displayMetrics.density
        val gap = (12 * density).toInt()
        // 窄屏将服务状态单独成行，短横屏减少重复说明并使用紧凑导航
        val stackedHeader = !landscape && (resources.configuration.screenWidthDp < 360 || resources.configuration.fontScale >= 1.3f)
        val shortLandscape = landscape && resources.configuration.screenHeightDp < 400
        findViewById<View>(R.id.heroEyebrow).visibility = if (shortLandscape) View.GONE else View.VISIBLE
        val header = findViewById<LinearLayout>(R.id.phoneHeader)
        header.orientation = if (stackedHeader) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        header.gravity = if (stackedHeader) android.view.Gravity.START else android.view.Gravity.CENTER_VERTICAL
        findViewById<View>(R.id.phoneHeaderIcon).visibility = if (stackedHeader || shortLandscape) View.GONE else View.VISIBLE
        findViewById<View>(R.id.phoneBrand).layoutParams =
            LinearLayout.LayoutParams(if (stackedHeader) -1 else 0, -2, if (stackedHeader) 0f else 1f)
        shizukuChip.layoutParams =
            LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = if (stackedHeader) 0 else (8 * density).toInt()
                topMargin = if (stackedHeader) (4 * density).toInt() else 0
            }
        deviceSubtitle.visibility = if (shortLandscape) View.GONE else View.VISIBLE
        val navigation = findViewById<LinearLayout>(R.id.phoneBottomNavigation)
        val navPadding = ((if (shortLandscape) 0 else 4) * density).toInt()
        navigation.setPadding(0, navPadding, 0, navPadding)
        // 底栏铺满窗口并与系统手势栏同色，短横屏减少高度仍保留完整热区
        listOf(R.id.phoneHomeTab to R.id.phoneHomeLabel, R.id.phoneSettingsTab to R.id.phoneSettingsLabel).forEach { (tabId, labelId) ->
            findViewById<LinearLayout>(tabId).apply {
                orientation = if (shortLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                minimumHeight = ((if (shortLandscape) 48 else 56) * density).toInt()
            }
            findViewById<TextView>(labelId).layoutParams =
                LinearLayout.LayoutParams(-2, -2).apply {
                    marginStart = if (shortLandscape) (6 * density).toInt() else 0
                    topMargin = if (shortLandscape) 0 else (2 * density).toInt()
                }
        }
        findViewById<View>(R.id.heroWaveFrame).visibility =
            if (landscape || resources.configuration.fontScale >= 1.3f) View.GONE else View.VISIBLE
        val verticalPadding = ((if (landscape) 14 else 20) * density).toInt()
        hero.setPadding((20 * density).toInt(), verticalPadding, (20 * density).toInt(), verticalPadding)
        row.orientation = android.widget.LinearLayout.VERTICAL
        if (landscape) {
            if (hero.parent !== heroScroll) {
                (hero.parent as? android.view.ViewGroup)?.removeView(hero)
                heroScroll.addView(hero, android.widget.FrameLayout.LayoutParams(-1, -2))
            }
            // 宽度用**屏幕宽减页面内边距**推算，不读 contentArea.width：
            // 旋转瞬间后者还是旧方向的测量值（竖屏 1168 → 英雄卡只有 537px，
            // 副标题被挤成三行，真机截图暴露）。
            val pagePadding =
                (16 * resources.displayMetrics.density * 2).toInt()
            val available =
                (resources.displayMetrics.widthPixels - pagePadding).coerceAtLeast(1)
            val heroWidth = (available * 0.46f).toInt().coerceAtLeast(1)
            // 左列独立滚动，右列滑动不再带走连接卡；屏高不足时按钮仍可到达
            heroScroll.layoutParams =
                android.widget.FrameLayout
                    .LayoutParams(
                        heroWidth,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    ).apply {
                        gravity = android.view.Gravity.START or android.view.Gravity.TOP
                        topMargin = gap
                    }
            // 右侧内容让出连接卡宽度，两列均隐藏滚动条
            scroll.setPadding(heroWidth + gap, scroll.paddingTop, scroll.paddingEnd, scroll.paddingBottom)
            (detail.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
            }
        } else {
            if (hero.parent !== row) {
                (hero.parent as? android.view.ViewGroup)?.removeView(hero)
                row.addView(
                    hero,
                    0,
                    android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            (hero.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
                topMargin = gap
                marginEnd = 0
            }
            scroll.setPadding(0, scroll.paddingTop, scroll.paddingEnd, scroll.paddingBottom)
            (detail.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
            }
        }
        selectPhoneTab(phoneSettingsVisible)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(heroTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 强制声明不得随 Activity 销毁残留窗口（旋转时 Activity 重建，onResume 会续弹）
        runCatching { activeDisclaimer?.dismiss() }
        activeDisclaimer = null
        pendingServiceThenConnect = false
        pendingTargetPickerAfterPermission = false
        pendingProjectionAfterAudioPermission = false
        pendingStartAfterProjection.set(false)
        pendingStartAfterBtPermission.set(false)
        pendingStartAfterLocationPermission.set(false)
        // 蓝牙扫描/配对监听随界面销毁收尾：Activity 退出后不再需要（也避免广播泄漏）
        runCatching { stopTargetScan() }
        targetPicker = null
        // 只解绑"挂屏"，不动会话——连接由前台服务保活、对象归 SessionController 持有；
        // 显式断开请用「断开连接」按钮（任意实例都能断到真管线）。
        if (SessionController.logListener === logListener) SessionController.logListener = null
        if (SessionController.uiHintSink === uiHintSinkRef) SessionController.uiHintSink = null
        SessionController.dialogSink = null
    }
}
