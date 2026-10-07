package io.github.sakurajay.lyra.core.source.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M1 第一条切片的验收测试。
 *
 * 它不测功能，测的是 ADR-0002 唯一真正 risky 的假设：Java 侧回调 resolve 的 Promise，
 * 其 `.then` 续体必须在下一次跨调用边界时被排空。这条不成立，音源契约就写不出来，
 * ADR 要重开——所以在这里拦住，而不是等到接真实音源时才发现。
 */
@RunWith(AndroidJUnit4::class)
class SourceScriptEngineTest {

    @Test
    fun evaluateExposesGlobalToHost() {
        val engine = SourceScriptEngine.create("eval")
        try {
            engine.evaluate("globalThis.__greeting = 'lyra'")
            assertEquals("lyra", engine.globalString("__greeting"))
        } finally {
            engine.close()
        }
    }

    @Test
    fun javaFunctionBoundToGlobalIsCallableFromScript() {
        val engine = SourceScriptEngine.create("bridge")
        try {
            engine.bindGlobal("__add") { args ->
                ((args[0] as Number).toInt() + (args[1] as Number).toInt()).toString()
            }
            engine.evaluate("globalThis.__sum = __add(2, 3)")
            assertEquals("5", engine.globalString("__sum"))
        } finally {
            engine.close()
        }
    }

    @Test
    fun promiseResolvedByHostCallbackDeliversItsContinuation() {
        val engine = SourceScriptEngine.create("promise")
        try {
            engine.evaluate(
                """
                const __resolvers = new Map();
                globalThis.startTask = (id) =>
                    new Promise((resolve) => __resolvers.set(id, resolve))
                        .then((value) => { globalThis.__result = value; });
                globalThis.settleTask = (id, value) => {
                    const resolve = __resolvers.get(id);
                    __resolvers.delete(id);
                    resolve(value);
                };
                """.trimIndent(),
            )

            engine.callGlobal("startTask", "req-1")
            // 尚未 resolve：不断言 null（JS null 到 Java 的映射不由我猜），只断言还没拿到 pong
            assertNotEquals("pong", engine.globalString("__result"))

            // 模拟网络回调来自另一条线程：真实音源的 request 就是这个形状
            val done = CountDownLatch(1)
            Thread {
                engine.callGlobal("settleTask", "req-1", "pong")
                done.countDown()
            }.start()
            assertNotNull(done.await(5, TimeUnit.SECONDS))

            assertEquals("pong", engine.globalString("__result"))
        } finally {
            engine.close()
        }
    }

    @Test
    fun esFeaturesUsedByRealSourcesWork() {
        val engine = SourceScriptEngine.create("es")
        try {
            engine.evaluate(
                """
                (async () => {
                    const opt = { a: { b: 7 } }.missing?.deep?.value ?? 42;
                    const arr = [...[1, 2, 3], 4];
                    const won = await Promise.any([
                        Promise.reject(new Error('first fails')),
                        Promise.resolve('fast'),
                    ]);
                    const map = new Map([[1, 'a']]);
                    const spread = { ...{ x: 1 }, y: 2 };
                    globalThis.__es = String(opt) + '|' + arr.join('') + '|' + won + '|' +
                        map.get(1) + '|' + String(spread.x + spread.y);
                })();
                """.trimIndent(),
            )
            // 整个 async IIFE 的微任务应在 evaluate 返回后排空
            assertEquals("42|1234|fast|a|3", engine.globalString("__es"))
        } finally {
            engine.close()
        }
    }
}
