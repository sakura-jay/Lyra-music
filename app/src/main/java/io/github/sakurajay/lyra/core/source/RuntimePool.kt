package io.github.sakurajay.lyra.core.source

import io.github.sakurajay.lyra.core.source.repo.StoredSource
import io.github.sakurajay.lyra.core.source.runtime.SourceRuntime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 音源运行时缓存：一条音源一个 JS 引擎实例，复用而不是每首播一次就重建。
 *
 * 重建的代价是实打实的：新建 QuickJS 上下文 + 注入 preload + 跑脚本 + 等 inited
 * 握手，用户感知就是"点下一首要等一下"。这也是官方移动端给每条音源开一条
 * JavaScriptThread 的原因。
 */
class RuntimePool(private val loader: suspend (StoredSource) -> SourceRuntime) {

    private val mutex = Mutex()
    private val runtimes = mutableMapOf<String, SourceRuntime>()

    suspend fun get(stored: StoredSource): SourceRuntime = mutex.withLock {
        runtimes[stored.id] ?: loader(stored).also { runtimes[stored.id] = it }
    }

    /** 音源被改或删掉时必须显式淘汰，否则会一直用着旧脚本 */
    suspend fun evict(sourceId: String) {
        mutex.withLock { runtimes.remove(sourceId) }?.close()
    }

    suspend fun closeAll() {
        val snapshot = mutex.withLock {
            val copy = runtimes.values.toList()
            runtimes.clear()
            copy
        }
        snapshot.forEach { runCatching { it.close() } }
    }

    val cachedIds: Set<String> get() = runtimes.keys.toSet()
}
