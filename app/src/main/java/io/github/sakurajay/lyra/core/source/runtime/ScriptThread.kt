package io.github.sakurajay.lyra.core.source.runtime

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * 音源脚本的执行线程。
 *
 * QuickJS 的 context 不是线程安全的，只能在创建它的线程上调用，因此一个音源一条线程，
 * context 与线程同生命周期。
 */
class ScriptThread(name: String) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lyra-source-$name").apply { isDaemon = true }
    }

    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    /** 在脚本线程上同步执行并取回结果，调用方线程会阻塞。 */
    fun <T> call(block: () -> T): T = executor.submit(Callable(block)).get()

    fun post(block: () -> Unit) {
        executor.execute(block)
    }

    fun quit() {
        executor.shutdown()
        // 给挂起的回调跑完的时间；直接强杀会让 context 泄漏
        if (!executor.awaitTermination(2, TimeUnit.SECONDS)) executor.shutdownNow()
    }
}
