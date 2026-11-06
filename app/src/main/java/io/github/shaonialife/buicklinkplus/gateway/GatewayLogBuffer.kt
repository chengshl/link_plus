package io.github.shaonialife.buicklinkplus.gateway

import java.util.ArrayDeque

/** No disk backlog. Vehicle association is captured when a log enters the queue. */
class GatewayLogBuffer(
    private val redactor: LogRedactor,
    private val capacity: Int = 2048,
    private val vehicleProvider: () -> String? = { null },
) {
    data class TaggedLine(val text: String, val vehicleId: String?)

    private val lines = ArrayDeque<TaggedLine>()
    @Volatile var dropped: Long = 0
        private set
    init { require(capacity > 0) }

    @Synchronized
    fun offer(raw: String) {
        val associatedVehicle = vehicleProvider()
        val safeLines = if (raw.length > 8192) listOf("[超长日志已省略]") else {
            val split = raw.lineSequence().take(33).toList()
            val bounded = if (split.size > 32) split.take(31) + "[多行日志超限，已省略余下内容]" else split
            bounded.map(redactor::line)
        }
        for (line in safeLines.filter { it.isNotBlank() }) {
            if (lines.size >= capacity) { lines.removeFirst(); dropped++ }
            lines.addLast(TaggedLine(line, associatedVehicle))
        }
    }

    /** One batch never mixes different vehicles, including unassigned logs. */
    @Synchronized
    fun takeTagged(): List<TaggedLine> = buildList {
        val first = lines.peekFirst()?.vehicleId
        while (size < 8 && lines.isNotEmpty() && lines.peekFirst().vehicleId == first) {
            add(lines.removeFirst())
        }
    }

    @Synchronized
    fun retryTagged(batch: List<TaggedLine>) {
        for (line in batch.asReversed()) {
            if (lines.size >= capacity) { dropped++; continue }
            lines.addFirst(line)
        }
    }

    /** Kept for legacy UI/unit tests that do not track vehicle identity. */
    fun take(): List<String> = takeTagged().map { it.text }

    fun retry(batch: List<String>) = retryTagged(batch.map { TaggedLine(it, null) })

    @Synchronized
    fun clear() { lines.clear() }
}
