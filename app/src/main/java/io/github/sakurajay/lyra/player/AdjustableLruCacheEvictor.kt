package io.github.sakurajay.lyra.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan

/**
 * 上限可调的 LRU 淘汰器。
 *
 * 与 Media3 的 `LeastRecentlyUsedCacheEvictor` 行为一致，唯一区别是上限可变：改小即可立即
 * 淘汰到新上限，而不必销毁重建 `SimpleCache`（重建会打断在播的读取，见 ADR-0008）。
 * 策略本体在 [LruEvictionBudget]，这里只做 Media3 契约的适配。
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class AdjustableLruCacheEvictor(initialLimitBytes: Long) : CacheEvictor {

    private val budget = LruEvictionBudget(
        lengthOf = { span: CacheSpan -> span.length },
        timestampOf = { span: CacheSpan -> span.lastTouchTimestamp },
        tieBreak = Comparator { a, b -> a.compareTo(b) },
        limitBytes = initialLimitBytes,
    )

    val limitBytes: Long get() = budget.limitBytes

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        // C.LENGTH_UNSET 表示长度未知，此时没法预腾空间——与 Media3 一致
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        budget.onAdded(span)
        evict(cache, 0L)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) = budget.onRemoved(span)

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) =
        budget.onTouched(oldSpan, newSpan)

    /**
     * 改上限；改小到当前占用以下会立即淘汰。
     *
     * 返回本次真正腾出的字节数：调用方要据此决定界面说什么，而它没有别的办法知道
     * 淘汰了多少——再扫一遍 `cache.keys` 是拿全量重算换一次记账。
     */
    fun setLimit(cache: Cache, bytes: Long): Long {
        budget.setLimit(bytes)
        return evict(cache, 0L)
    }

    private fun evict(cache: Cache, requiredSpace: Long): Long {
        var evicted = 0L
        while (true) {
            val span = budget.oldestIfOverflowing(requiredSpace) ?: break
            val before = budget.size
            cache.removeSpan(span)
            // removeSpan 会回调 onSpanRemoved 更新计数；若这一下没删动（如 span 正在写入），
            // 再循环就会卡死——Media3 的原版没有这道闸，这里补上
            if (budget.size >= before) break
            evicted += before - budget.size
        }
        return evicted
    }
}
