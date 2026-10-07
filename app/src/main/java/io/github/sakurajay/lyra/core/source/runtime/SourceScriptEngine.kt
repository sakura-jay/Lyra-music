package io.github.sakurajay.lyra.core.source.runtime

import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.CoroutineDispatcher

/**
 * 一个音源脚本对应一个引擎实例。
 *
 * 选择 QuickJS 而不是 WebView 的理由见 ADR-0002：WebView 的 renderer 进程会被系统在
 * 内存压力下回收，而音源是需要长期驻留回调状态的宿主。
 */
class SourceScriptEngine private constructor(
    private val thread: ScriptThread,
    private val context: QuickJSContext,
) : AutoCloseable {

    val dispatcher: CoroutineDispatcher = thread.dispatcher

    companion object {
        init {
            // native 库只需在进程内加载一次
            QuickJSLoader.init()
        }

        fun create(scriptName: String): SourceScriptEngine {
            val thread = ScriptThread(scriptName)
            return thread.call { SourceScriptEngine(thread, QuickJSContext.create()) }
        }
    }

    fun evaluate(script: String, fileName: String = "source.js") {
        thread.call { context.evaluate(script, fileName) }
    }

    /** 把 Java 函数注入 globalThis，供脚本回调宿主能力。 */
    fun bindGlobal(name: String, handler: (Array<Any?>) -> Any?) {
        thread.call {
            context.globalObject.setProperty(name, JSCallFunction { args -> handler(args) })
        }
    }

    /**
     * 调用脚本里的全局函数。
     *
     * 返回后脚本内排队的 Promise 回调已由 native 层的 job loop 排空，
     * 因此调用完立刻读全局变量能拿到异步链路的结果。
     */
    fun callGlobal(name: String, vararg args: Any?): Any? =
        thread.call { context.globalObject.getJSFunction(name)?.call(*args) }

    fun globalString(name: String): String? =
        thread.call { context.globalObject.getStringProperty(name) }

    override fun close() {
        thread.call { context.destroy() }
        thread.quit()
    }
}
