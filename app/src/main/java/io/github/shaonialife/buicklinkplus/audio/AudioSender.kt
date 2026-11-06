package io.github.shaonialife.buicklinkplus.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import io.github.shaonialife.buicklinkplus.airplay.AirPlayCrypto
import io.github.shaonialife.buicklinkplus.airplay.TimingServer
import io.github.shaonialife.buicklinkplus.util.Bg
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay RTP 音频发送端，支持 PCM 和 AAC-LC
 * 报文：[12B RTP 头][密文及 16B tag][8B LE nonce]
 * ChaCha20-Poly1305 的 AAD 为 RTP 头 4..12 字节，nonce 前补 4B 零
 * PT 使用流类型，SSRC 为 0，时间戳按样本递增，首包置 Marker
 * 每秒向 controlPort 发送 [AudioSyncPacket]，建立 RTP 与 NTP 的对应关系
 * 格式参考 catplay rtp_cipher_chacha 和 DiPlay AudioStream
 */
class AudioSender(
    private val host: String,
    private val dataPort: Int,
    private val streamKey: ByteArray,
    private val log: (String) -> Unit,
    /** 静音手机媒体音量（声音只从车机出）。null = 不做（测试用）。 */
    private val context: android.content.Context? = null,
    /** 采集采样率必须与 SETUP 协商结果一致，默认 48kHz */
    private val sampleRate: Int = 48_000,
    /** 负载编码：PCM（type=100 通路）或 AAC-LC（type=102 通路）。 */
    private val codec: AudioCodec = AudioCodec.PCM,
    /** RTP 流类型，仅用于日志（PT 与 SETUP 的 type 保持一致）。 */
    private val streamType: Long = 100L,
    /** AAC 帧是否补 ADTS 头（见 [AudioTuning.ADTS]，排查车机解码链用）。 */
    private val adtsFrames: Boolean = false,
    /** SETUP 返回的音频控制端口，大于 0 时定期发送同步包，为 0 时只发送音频 */
    private val controlPort: Int = 0,
) {
    companion object {
        const val CHANNELS = 2

        /** PCM 每包 352 个样本帧，参考 catplay MAX_FRAMES_PER_PACKET_PCM */
        const val SAMPLES_PER_PACKET = 352

        /** 一包负载字节数（16bit 立体声）。 */
        const val PACKET_BYTES = SAMPLES_PER_PACKET * CHANNELS * 2

        /** 同步包发送间隔（规范：每秒一个）。 */
        const val SYNC_INTERVAL_MILLIS = 1_000L

        private const val RTP_HEADER_LEN = 12
        private const val TAG_SIZE = 16
        private const val NONCE_SIZE = 8

        private const val READ_TIMEOUT_QUIET_MILLIS = 500L

        /** 小端 s16 → 大端 s16（每两字节换序）。CarPlay 线格式要求大端样本。 */
        fun bigEndianS16(source: ByteArray): ByteArray {
            val out = source.copyOf()
            var index = 0
            while (index + 1 < out.size) {
                val tmp = out[index]
                out[index] = out[index + 1]
                out[index + 1] = tmp
                index += 2
            }
            return out
        }
    }

    private val running = AtomicBoolean(false)

    /** stop() 幂等闸门（多条收尾路径都会调 stop）。 */
    private val stopped = AtomicBoolean(false)
    private val resourceLock = Any()
    private var socket: DatagramSocket? = null
    private var syncSocket: DatagramSocket? = null
    private var captureSource: AudioCaptureSource? = null
    private var thread: Thread? = null
    private var syncThread: Thread? = null

    /** AAC-LC 编码器（仅 [AudioCodec.AAC_LC] 时创建）。 */
    private var aacEncoder: AacLcEncoder? = null

    /** RTP 游标，供发送循环和同步包线程共同读取 */
    @Volatile private var sequence = 0

    @Volatile private var timestamp = 0L

    @Volatile private var nonceCounter = 0L

    /** 首个音频包标记（打完就清）；AAC 自检只跑一次。 */
    private var markerPending = true
    private var selfChecked = false

    /** 每包/每帧对应的 PCM 字节数（PCM 通路=一包的负载；AAC 通路=一帧的输入）。 */
    private val framePcmBytes: Int
        get() = if (codec == AudioCodec.AAC_LC) AacLcEncoder.pcmBytes(CHANNELS) else PACKET_BYTES

    /** 每包推进的样本数（AAC 一帧 1024，PCM 一包 352）。 */
    private val samplesPerPacket: Long
        get() = if (codec == AudioCodec.AAC_LC) AacLcEncoder.FRAME_SAMPLES.toLong() else SAMPLES_PER_PACKET.toLong()

    /** RTP SSRC 固定为 0，参考 catplay prepare_header */
    private val ssrc = 0

    /** 采集+推流统计（日志限流用）。 */
    @Volatile private var packetsSent = 0L

    @Volatile private var bytesSent = 0L
    private var lastLogAt = 0L
    private var readFailures = 0

    @Volatile private var capturing = false

    private val syncState = AudioSyncState()
    private val started = AtomicBoolean(false)
    val isRunning: Boolean get() = running.get()

    /** 报告采集和发送阶段，静音或暂停不记为采集失败 */
    fun status(): String =
        "采集：${if (capturing) {
            "运行中"
        } else if (isRunning) {
            "启动中"
        } else {
            "已停止"
        }}" +
            " · 发送：$packetsSent 包 / ${bytesSent / 1024} KB · 同步：${syncState.status}"

    @Volatile
    private var sourceProjection: MediaProjection? = null

    fun usesProjection(projection: MediaProjection): Boolean = sourceProjection === projection

    private val muteToken = Any()

    @Volatile private var muteError = ""
    val phoneMuteStatus: String get() =
        if (captureSource?.exclusive ==
            true
        ) {
            "媒体导航独占路由"
        } else {
            muteError.ifEmpty { MediaMuteController.status(muteToken) }
        }

    /**
     * 启动音频采集和发送，优先服务采集，兼容方式需投屏授权
     * 返回是否进入采集循环
     */
    fun start(projection: MediaProjection?): Boolean {
        if (!started.compareAndSet(false, true)) return running.get()
        if (running.get()) return true
        if (stopped.get()) return false
        return try {
            val client = DatagramSocket()
            synchronized(resourceLock) {
                if (stopped.get()) {
                    client.close()
                    return false
                }
                socket = client
            }
            client.connect(InetAddress.getByName(host), dataPort)

            // 先启动 AAC 编码器，失败时就不继续采集
            if (codec == AudioCodec.AAC_LC) {
                val encoder = AacLcEncoder(sampleRate, CHANNELS, wrapAdts = adtsFrames, log = log)
                if (!encoder.start()) {
                    client.close()
                    socket = null
                    return false
                }
                synchronized(resourceLock) {
                    if (stopped.get()) {
                        encoder.stop()
                        return false
                    }
                    aacEncoder = encoder
                }
            }

            val source = createCaptureSource(projection)
            synchronized(resourceLock) {
                if (stopped.get()) {
                    source.close()
                    return false
                }
                captureSource = source
                running.set(true)
            }
            log(
                "AUDIO: 已启动（播放捕获 ${sampleRate}Hz 立体声 → $host:$dataPort，" +
                    "通路=t$streamType/${codec.name}，" +
                    if (codec == AudioCodec.AAC_LC) {
                        "每包 1 个 AAC 帧（${AacLcEncoder.FRAME_SAMPLES} 采样" +
                            (if (adtsFrames) "，ADTS 封装））" else "，裸帧））")
                    } else {
                        "每包 ${SAMPLES_PER_PACKET} 帧 PCM）"
                    },
            )

            // 同步包线程先起：锚点越早建立，接收端越早能排播
            startSyncLoop()

            Thread(
                {
                    try {
                        source.start()
                        synchronized(resourceLock) {
                            if (stopped.get()) return@Thread
                            capturing = true
                        }
                    } catch (error: Exception) {
                        log("AUDIO: 启动采集失败 - ${error.message}")
                        stop()
                        return@Thread
                    }
                    if (stopped.get()) return@Thread
                    context?.takeIf { !source.exclusive }?.let { ctx ->
                        runCatching { MediaMuteController.acquire(ctx, muteToken, log) { !stopped.get() } }
                            .onFailure {
                                muteError = "手机静音失败：" + it.message
                                log("AUDIO: " + muteError)
                            }
                    }
                    runCaptureLoop()
                },
                "audio-capture",
            ).apply {
                isDaemon = true
                start()
                thread = this
            }
            true
        } catch (error: Exception) {
            log("AUDIO: 启动失败 - ${error.message}")
            stop()
            false
        }
    }

    /** 停止音频并恢复手机音量，可重复调用 */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        synchronized(resourceLock) {
            running.set(false)
            runCatching { captureSource?.close() }
            captureSource = null
            aacEncoder?.stop()
            aacEncoder = null
            runCatching { socket?.close() }
            socket = null
            runCatching { syncSocket?.close() }
            syncSocket = null
            syncState.stop()
            syncThread?.interrupt()
            syncThread = null
            thread = null
            context?.let { ctx ->
                // 控制器在全局锁内核对采集状态，恢复先到或获取先到都不会遗留静音
                Bg.run("media-mute-release") { MediaMuteController.release(ctx, muteToken, log) }
            }
            capturing = false
            log("AUDIO: 已停止（共发 $packetsSent 包 / ${bytesSent / 1024}KB）")
            sourceProjection = null
        }
    }

    /** 优先服务侧采集，失败时使用用户已授权的兼容方式 */
    private fun createCaptureSource(projection: MediaProjection?): AudioCaptureSource {
        context?.let { MediaMuteController.recover(it, log) }
        val service = io.github.shaonialife.buicklinkplus.shizuku.CarInputClient.service
        if (service != null) {
            runCatching {
                ShellCapture.open(service, sampleRate) { !stopped.get() }
            }.onSuccess {
                log("AUDIO: 采集来源=内置服务媒体与导航路由；不追加手机全局静音")
                return it
            }.onFailure { log("AUDIO: 内置服务采集不可用：${it.cause?.message ?: it.message}") }
        }
        check(!stopped.get()) { "音频会话已取消" }
        checkNotNull(projection) { "请在手机端申请兼容声音授权，视频连接继续" }
        val format =
            AudioFormat
                .Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
        val captureContext = checkNotNull(context) { "缺少声音采集上下文" }
        check(
            captureContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        ) {
            "请在手机端允许录音权限，视频连接继续"
        }
        val recorder =
            AudioRecord
                .Builder()
                .setAudioPlaybackCaptureConfig(buildCaptureConfig(projection))
                .setAudioFormat(format)
                .setBufferSizeInBytes(framePcmBytes * 8)
                .build()
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error("系统声音采集初始化失败")
        }
        sourceProjection = projection
        log("AUDIO: 采集来源=系统投屏授权，仅媒体、游戏及未知用途")
        return ProjectionCapture(recorder)
    }

    /**
     * 官方播放捕获仅支持媒体、游戏和未知用途，还受源应用捕获策略约束
     */
    private fun buildCaptureConfig(projection: MediaProjection): AudioPlaybackCaptureConfiguration {
        val builder = AudioPlaybackCaptureConfiguration.Builder(projection)
        listOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN,
        ).forEach { usage ->
            runCatching { builder.addMatchingUsage(usage) }
        }
        return builder.build()
    }

    // ---- 音频同步包（RTP 时间戳 ↔ NTP，每秒一个） ----

    private fun startSyncLoop() {
        val request =
            synchronized(resourceLock) {
                if (stopped.get() || !running.get()) return
                syncState.begin()
            }
        if (controlPort <= 0) {
            syncState.update(request, "车机未声明同步端口")
            log("AUDIO: 车机未下发 controlPort——不发同步包（只能靠音频包自带时间戳）")
            return
        }
        val client = DatagramSocket()
        try {
            client.connect(InetAddress.getByName(host), controlPort)
        } catch (error: Exception) {
            client.close()
            syncState.update(request, "同步不可用，媒体发送继续")
            log("AUDIO: 同步包套接字创建失败 - ${error.message}")
            return
        }
        synchronized(resourceLock) {
            if (stopped.get() || !running.get() || !syncState.owns(request)) {
                client.close()
                return
            }
            syncSocket = client
        }
        log("AUDIO: 同步包已启用 → $host:$controlPort（每 ${SYNC_INTERVAL_MILLIS}ms 一个，建立 RTP↔NTP 锚点）")
        val worker =
            Thread(
                {
                    var syncSequence = 0
                    var first = true
                    try {
                        while (running.get() && syncState.owns(request) && syncSocket === client && !client.isClosed) {
                            val next = timestamp
                            val current = (next - samplesPerPacket).coerceAtLeast(0L)
                            val ntp = TimingServer.ntpFromNanos(System.nanoTime())
                            val packet = AudioSyncPacket.build(syncSequence, current, next, ntp, first)
                            client.send(DatagramPacket(packet, packet.size))
                            syncState.update(request, "同步包已发送，接收未确认")
                            syncSequence = (syncSequence + 1) and 0xFFFF
                            if (first) {
                                first = false
                                log(
                                    "AUDIO: 首个同步包已发出（X 位=1）ts=$current→$next——" +
                                        "同步发送不代表车机已接收或扬声器已出声",
                                )
                            }
                            Thread.sleep(SYNC_INTERVAL_MILLIS)
                        }
                    } catch (error: Exception) {
                        if (running.get() && syncState.owns(request)) {
                            syncState.update(request, "发送受阻，已降级，媒体发送继续")
                            log("AUDIO: 同步包发送停止 running=${running.get()} stopped=${stopped.get()} syncOwned=${syncState.owns(request)} syncSocketClosed=${client.isClosed} sentSync=$syncSequence kind=${error.javaClass.simpleName} - ${error.message}；媒体发送继续，不能据此认定车机停止消费")
                        }
                    } finally {
                        client.close()
                    }
                },
                "audio-sync",
            )
        worker.isDaemon = true
        synchronized(resourceLock) {
            if (stopped.get() || !syncState.owns(request)) {
                client.close()
                return
            }
            syncThread = worker
            worker.start()
        }
    }

    // ---- 采集循环 → 封包发送 ----

    private fun runCaptureLoop() {
        val recorder = captureSource ?: return
        val frameBytes = framePcmBytes
        val frame = PcmFrameBuffer(frameBytes)
        val pcm = frame.bytes
        // 诊断抽样节拍：PCM 约 125 包/s（每 20s 一次）；AAC 约 47 帧/s
        val diagnosticStride = if (codec == AudioCodec.AAC_LC) 600 else 1875
        var audibleFrames = 0
        var zeroSince = 0L
        try {
            while (running.get()) {
                val read =
                    try {
                        recorder.read(pcm, frame.offset, frame.remaining)
                    } catch (error: Exception) {
                        log("AUDIO: 采集读取异常 - ${error.message}")
                        break
                    }
                if (read == -1 && recorder.exclusive) break
                if (read < 0) {
                    readFailures++
                    if (readFailures == 1 || readFailures % 100 == 0) {
                        log("AUDIO: 采集错误码 $read（连续 $readFailures 次）")
                    }
                    if (readFailures > 500) break
                    Thread.sleep(READ_TIMEOUT_QUIET_MILLIS)
                    continue
                }
                if (read == 0) {
                    Thread.sleep(READ_TIMEOUT_QUIET_MILLIS)
                    continue
                }
                if (!frame.accept(read)) continue
                readFailures = 0

                val signal = AudioSampleStats.inspect(pcm, frameBytes)
                audibleFrames = if (signal.peak > 0) audibleFrames + 1 else 0
                val sampledAt = android.os.SystemClock.uptimeMillis()
                if (signal.peak > 0) {
                    zeroSince = 0L
                } else if (zeroSince == 0L) {
                    zeroSince = sampledAt
                }

                // 记录非零样本比例，区分没采到音频和车机没有播放
                if (sequence < 3 || sequence % diagnosticStride == 0) {
                    val playing =
                        io.github.shaonialife.buicklinkplus.media.MediaSessionsState.nowPlaying
                            ?.playing
                    val content =
                        when {
                            signal.peak > 0 -> "存在非零采样，车机消费尚未确认"
                            playing == false -> "播放器已暂停，零样本不能判为采集失败"
                            playing == null -> "播放器状态未知，零样本原因待确认"
                            sampledAt - zeroSince < 3_000 -> "等待播放内容，暂不判为采集异常"
                            else -> "会话报告播放但持续无非零采样，需核对音频焦点、路由及播放捕获许可"
                        }
                    log(
                        "AUDIO: 采集样本 seq=$sequence 非零${signal.nonZeroPercent}% 峰值=${signal.peak}（$content）",
                    )
                    if (signal.peak == 0 && playing == true && sampledAt - zeroSince >= 3_000) {
                        io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
                            .recordInteractionDiagnostics("播放器报告播放但持续零采样")
                    }
                }

                // PCM 通路：采一段 = 发一包；AAC 通路：喂一帧 PCM，编码器可能吐出 0~N 帧
                val payloads: List<ByteArray> =
                    if (codec == AudioCodec.AAC_LC) {
                        val encoder = aacEncoder ?: break
                        encoder.encode(pcm, frameBytes)
                    } else {
                        listOf(pcm)
                    }
                if (payloads.isEmpty()) continue

                // 等待连续非零输入再自检 AAC，PCM 通路不使用 AAC 解码器
                if (!selfChecked && codec == AudioCodec.AAC_LC && audibleFrames >= 3) {
                    selfChecked = true
                    verifyEncodedFrame(payloads.first())
                }

                var sendFailed = false
                for (payload in payloads) {
                    if (!sendPayload(payload)) {
                        sendFailed = true
                        break
                    }
                }
                if (sendFailed) break
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastLogAt >= 30_000L) {
                    lastLogAt = now
                    log(
                        "AUDIO: 推流中（累计 $packetsSent 包，${bytesSent / 1024}KB，" +
                            "seq=$sequence ts=$timestamp）",
                    )
                }
            }
        } catch (error: Throwable) {
            // 后台线程未捕获异常会杀死整个进程——音频异常绝不允许冒泡
            log("AUDIO: 采集线程异常退出 - ${error.message}")
        } finally {
            stop()
        }
    }

    /** 封包 + 发送一包（同步更新 seq/ts/nonce 游标）。返回 false 表示链路已断。 */
    private fun sendPayload(payload: ByteArray): Boolean {
        val wire = sealPacket(payload, sequence, timestamp, nonceCounter, markerPending)
        markerPending = false
        sequence = (sequence + 1) and 0xFFFF
        timestamp += samplesPerPacket
        nonceCounter++
        val client = socket ?: return false
        return try {
            client.send(DatagramPacket(wire, wire.size))
            packetsSent++
            bytesSent += wire.size
            true
        } catch (error: IOException) {
            log("AUDIO: UDP 发送失败 running=${running.get()} stopped=${stopped.get()} socketClosed=${client.isClosed} sentMedia=$packetsSent seq=$sequence kind=${error.javaClass.simpleName} - ${error.message}")
            false
        }
    }

    /** 用平台解码器把首帧解回 PCM，记录峰值（后台线程，不阻塞推流）。 */
    private fun verifyEncodedFrame(frame: ByteArray) {
        val payload = frame.copyOf()
        Thread(
            {
                try {
                    val result = AacLcEncoder.decodePeak(payload, sampleRate, CHANNELS)
                    if (result == null) {
                        log("AUDIO: 自检 = 本机未解出采样（${payload.size}B），结果未知，继续推流")
                        return@Thread
                    }
                    val (peak, samples) = result
                    log(
                        "AUDIO: 自检 = ${payload.size}B 本机解码 $samples 个样本，峰值 $peak" +
                            if (peak > 0) {
                                "（本机负载可解码，后续加密、传输及车机消费仍需核验）"
                            } else {
                                "（本帧解码为零，单帧结果不能认定整个编码器故障）"
                            },
                    )
                } catch (error: Exception) {
                    log("AUDIO: 自检解码异常 - ${error.message}")
                }
            },
            "audio-selfcheck",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** 将 PCM 加密后组装为 RTP 头、密文、tag 和 nonce */
    internal fun sealPacket(
        pcm: ByteArray,
        sequence: Int,
        timestamp: Long,
        nonceCounter: Long,
        marker: Boolean = false,
    ): ByteArray {
        val header = buildRtpHeader(sequence, timestamp, marker)
        // AAD 取 RTP 头的 timestamp 和 SSRC，即字节 4..12
        val aad = header.copyOfRange(4, RTP_HEADER_LEN)
        // Android 采集的 PCM 是小端，CarPlay LPCM 使用大端 s16，需要换序
        // AAC 是编码后的字节流，保持原顺序
        val wirePayload = if (codec == AudioCodec.AAC_LC) pcm else bigEndianS16(pcm)
        val sealed = AirPlayCrypto.chachaSeal(streamKey, AirPlayCrypto.nonce64(nonceCounter), wirePayload, aad)
        val wire = ByteArray(RTP_HEADER_LEN + sealed.size + NONCE_SIZE)
        header.copyInto(wire, 0)
        sealed.copyInto(wire, RTP_HEADER_LEN)
        // 尾部 8 字节小端 nonce（与 AirPlayCrypto.nonce64 的低 8 字节一致）
        for (index in 0 until NONCE_SIZE) {
            wire[wire.size - NONCE_SIZE + index] =
                ((nonceCounter ushr (8 * index)) and 0xFF).toByte()
        }
        return wire
    }

    private fun buildRtpHeader(
        sequence: Int,
        timestamp: Long,
        marker: Boolean = false,
    ): ByteArray {
        val header = ByteArray(RTP_HEADER_LEN)
        header[0] = 0x80.toByte() // version=2
        // PT 跟随流类型（catplay `payload_type = stream_type`）：100=PCM 通路、102=AAC 通路
        header[1] = ((if (marker) 0x80 else 0) or (streamType.toInt() and 0x7F)).toByte()
        header[2] = ((sequence ushr 8) and 0xFF).toByte()
        header[3] = (sequence and 0xFF).toByte()
        val ts = timestamp.toInt()
        header[4] = ((ts ushr 24) and 0xFF).toByte()
        header[5] = ((ts ushr 16) and 0xFF).toByte()
        header[6] = ((ts ushr 8) and 0xFF).toByte()
        header[7] = (ts and 0xFF).toByte()
        header[8] = ((ssrc ushr 24) and 0xFF).toByte()
        header[9] = ((ssrc ushr 16) and 0xFF).toByte()
        header[10] = ((ssrc ushr 8) and 0xFF).toByte()
        header[11] = (ssrc and 0xFF).toByte()
        return header
    }
}
