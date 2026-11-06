package io.github.shaonialife.buicklinkplus.util

import android.content.Context
import android.os.SystemClock
import io.github.shaonialife.buicklinkplus.BuildConfig
import io.github.shaonialife.buicklinkplus.iap2.Iap2MessageIds
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 仅保存协议元数据：方向、序号、ACK、CSM ID/TLV 长度、RTSP CSeq/状态/耗时。
 * 不落盘原始载荷、密码、密钥、证书、设备标识或 URL 查询参数。
 * 异步单线程 + 4x512KiB 滚动文件；不做远程上传。
 */
object ProtocolTrace {
    private const val FILE_COUNT = 4
    private const val MAX_FILE_BYTES = 512 * 1024
    private const val MAX_PENDING = 1024
    private const val PREFS = "protocol_trace_state"
    private const val ACTIVE_SLOT = "active_slot"
    private const val MARKER = "TRACE_PROCESS_START"

    @Volatile private var context: Context? = null
    private val sequence = AtomicLong(0)
    private val queued = AtomicInteger(0)
    private val dropped = AtomicLong(0)
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "link-plus-protocol-trace").apply { isDaemon = true }
    }
    private val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun initialize(app: Context) {
        if (BuildConfig.BUILD_TYPE != "trace") return
        context = app.applicationContext
        event("PROCESS", "$MARKER build=${BuildConfig.BUILD_ID}")
    }

    fun event(source: String, detail: String) {
        val app = context ?: return
        if (queued.incrementAndGet() > MAX_PENDING) {
            queued.decrementAndGet()
            dropped.incrementAndGet()
            return
        }
        val index = sequence.incrementAndGet()
        val tick = SystemClock.elapsedRealtime()
        val safe = LogRedactor.redact(detail.replace('\n', ' ').replace('\r', ' ')).take(1024)
        val tag = source.filter { it.isLetterOrDigit() || it == '-' }.take(32)
        writer.execute {
            try {
                val loss = dropped.getAndSet(0)
                if (loss > 0) appendLine(app, "[TRACE] backpressure-dropped=$loss")
                appendLine(app, "+${tick}ms #$index [$tag] $safe")
            } catch (_: Exception) {
                // 故障取证不允许干扰连接/音频/视频控制线程
            } finally {
                queued.decrementAndGet()
            }
        }
    }

    fun linkFrame(direction: String, control: Int, sequence: Int, ack: Int, session: Int, size: Int) {
        event("IAP2-LINK", "$direction ctl=0x${control.toString(16).padStart(2, '0')} seq=$sequence ack=$ack session=$session bytes=$size")
    }

    fun csm(direction: String, messageId: Int, body: ByteArray) {
        event("IAP2-CSM", ProtocolTraceFormat.csm(direction, messageId, Iap2MessageIds.nameOf(messageId), body))
    }

    fun rtsp(
        direction: String,
        method: String,
        path: String,
        cseq: Int,
        bytes: Int,
        status: String? = null,
        elapsedMs: Long? = null,
    ) {
        event("AIRPLAY-RTSP", ProtocolTraceFormat.rtsp(direction, method, path, cseq, bytes, status, elapsedMs))
    }

    /** 仅由现有后台导出路径读取；先等待写入队列，避免丢失尾部 ACK。 */
    fun export(app: Context, currentSessionOnly: Boolean): String {
        if (BuildConfig.BUILD_TYPE != "trace") return "本构建未启用协议追踪"
        return runCatching {
            writer.submit<String> {
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val current = prefs.getInt(ACTIVE_SLOT, 0).mod(FILE_COUNT)
                val all = (1..FILE_COUNT)
                    .map { offset -> (current + offset).mod(FILE_COUNT) }
                    .map { slot -> File(app.filesDir, "protocol_trace-$slot.log") }
                    .filter(File::exists)
                    .joinToString("") { it.readText(Charsets.UTF_8) }
                val marker = all.lastIndexOf(MARKER)
                if (currentSessionOnly && marker >= 0) {
                    val previousLine = all.lastIndexOf('\n', marker)
                    all.substring(if (previousLine < 0) 0 else previousLine + 1)
                } else {
                    all
                }
            }.get(3, TimeUnit.SECONDS)
        }.getOrElse { "协议追踪读取失败（${it.javaClass.simpleName}），请重新导出" }
    }

    private fun appendLine(app: Context, data: String) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var slot = prefs.getInt(ACTIVE_SLOT, 0).mod(FILE_COUNT)
        var file = File(app.filesDir, "protocol_trace-$slot.log")
        val line = "[${clock.format(Date())}] $data\n"
        if (file.length() + line.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
            slot = (slot + 1) % FILE_COUNT
            file = File(app.filesDir, "protocol_trace-$slot.log")
            file.writeText("", Charsets.UTF_8)
            prefs.edit().putInt(ACTIVE_SLOT, slot).commit()
        }
        file.appendText(line, Charsets.UTF_8)
    }
}

/** 仅检查 TLV 头部的 ID 和长度，不读取敏感参数值。 */
object ProtocolTraceFormat {
    fun csm(direction: String, id: Int, name: String, bytes: ByteArray): String =
        "$direction id=0x${id.toString(16).padStart(4, '0')} name=$name bytes=${bytes.size} params=${shape(bytes)}"

    internal fun shape(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "empty"
        var offset = 0
        val fields = ArrayList<String>()
        while (offset + 4 <= bytes.size && fields.size < 24) {
            val size = u16(bytes, offset)
            val id = u16(bytes, offset + 2)
            if (size < 4 || offset + size > bytes.size) break
            fields += "$id:${size - 4}"
            offset += size
        }
        if (fields.isEmpty()) return "opaque"
        return fields.joinToString(",", prefix = "[", postfix = "]") +
            if (offset == bytes.size) "" else "+tail=${bytes.size - offset}"
    }

    fun rtsp(
        direction: String,
        method: String,
        path: String,
        cseq: Int,
        bytes: Int,
        status: String?,
        elapsedMs: Long?,
    ): String {
        val safeMethod = method.takeIf { it.matches(Regex("[A-Z]{2,12}")) } ?: "UNKNOWN"
        val safePath = path.substringBefore('?').takeIf { it.matches(Regex("/[A-Za-z0-9_./-]{0,100}")) } ?: "/[hidden]"
        val code = status?.let { Regex("(?:RTSP|HTTP)/[0-9.]+\\s+([0-9]{3})").find(it)?.groupValues?.get(1) }
        return "$direction method=$safeMethod path=$safePath cseq=$cseq bytes=$bytes" +
            (code?.let { " status=$it" } ?: "") +
            (elapsedMs?.let { " durationMs=$it" } ?: "")
    }

    private fun u16(bytes: ByteArray, index: Int): Int =
        ((bytes[index].toInt() and 255) shl 8) or (bytes[index + 1].toInt() and 255)
}
