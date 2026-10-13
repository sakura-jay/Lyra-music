package io.github.sakurajay.lyra.player

import java.util.TreeSet

/**
 * 可调上限的 LRU 记账。
 *
 * 语义逐条对照 Media3 的 `LeastRecentlyUsedCacheEvictor`（1.11.1）：按 `lastTouchTimestamp`
 * 升序排（最久未用的在前），时间戳相同再按 [tieBreak] 定序；新增即入集合并累加字节；移除即
 * 出集合、减字节；触碰 = 先移除旧 span 再加新 span。
 *
 * 抽成不依赖 Media3 的纯类是为了可测：Media3 的 `Cache` / `CacheSpan` 是 Android 侧类型，
 * 项目又没开 `unitTests.returnDefaultValues`，放到 JVM 单测里跑不起来（见 ADR-0008）。
 * [AdjustableLruCacheEvictor] 只是它的薄适配器。
 */
internal class LruEvictionBudget<T : Any>(
    private val lengthOf: (T) -> Long,
    private val timestampOf: (T) -> Long,
    private val tieBreak: Comparator<T>,
    limitBytes: Long,
) {

    private val spans = TreeSet<T> { a, b ->
        val diff = timestampOf(a) - timestampOf(b)
        if (diff != 0L) diff.compareTo(0L) else tieBreak.compare(a, b)
    }

    @Volatile
    var limitBytes: Long = limitBytes
        private set

    /** 当前已登记的总字节数。 */
    @Volatile
    var size: Long = 0L
        private set

    @Synchronized
    fun onAdded(span: T) {
        spans.add(span)
        size += lengthOf(span)
    }

    @Synchronized
    fun onRemoved(span: T) {
        if (spans.remove(span)) size -= lengthOf(span)
    }

    @Synchronized
    fun onTouched(oldSpan: T, newSpan: T) {
        if (spans.remove(oldSpan)) size -= lengthOf(oldSpan)
        spans.add(newSpan)
        size += lengthOf(newSpan)
    }

    @Synchronized
    fun setLimit(bytes: Long) {
        limitBytes = bytes
    }

    /**
     * 若"当前占用 + [requiredSpace]"已超上限，给出最久未用的那个等待淘汰；否则返回 null。
     * 淘汰动作由调用方执行（只有它能调 `cache.removeSpan`），删除会回调 [onRemoved] 更新计数。
     */
    @Synchronized
    fun oldestIfOverflowing(requiredSpace: Long): T? =
        if (size + requiredSpace > limitBytes) spans.firstOrNull() else null
}
