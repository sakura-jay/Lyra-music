package io.github.sakurajay.lyra.core.source.runtime

import io.github.sakurajay.lyra.core.source.bridge.JsNativeFunctions
import io.github.sakurajay.lyra.core.source.bridge.RequestExecutor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.security.SecureRandom

class SourceInitException(message: String) : Exception(message)

data class SourceCapability(
    val name: String,
    val type: String,
    val actions: List<String>,
    val qualitys: List<String>,
)

/**
 * 一条音源脚本的运行时：加载 preload、握手 init、分发 action。
 *
 * 协议与洛雪移动端逐字对齐（见 lx-preload.js 头部注释），这样音源不用改就能跑。
 */
class SourceRuntime private constructor(
    private val engine: SourceScriptEngine,
    private val key: String,
    private val natives: JsNativeFunctions,
    private val requests: RequestExecutor,
    private val timers: ScheduledExecutorService,
) : AutoCloseable {

    /** 音源自己在 `send(inited)` 里上报的能力；不是我们写死的枚举 */
    var capabilities: Map<String, SourceCapability> = emptyMap()
        private set

    var scriptInfo: ScriptInfo = ScriptInfo()
        private set

    var updateAlertHandler: ((log: String, updateUrl: String?) -> Unit)? = null

    private val initDeferred = CompletableDeferred<Unit>()
    // 回包来自 OkHttp 线程、移除来自协程线程，必须并发安全
    private val pendingInvokes = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private var timerSeq = 0

    companion object {
        private const val INIT_TIMEOUT_SECONDS = 30L
        private const val INVOKE_TIMEOUT_SECONDS = 30L

        /**
         * 加载一条音源。
         *
         * 顺序是硬约束：preload 在 `lx_setup` 被调用的那一刻才去读
         * `globalThis.__lx_native_call__` 等原生函数，所以必须在 eval preload 之后、
         * 调 lx_setup 之前把 Java 函数绑上去。
         */
        suspend fun load(
            preloadScript: String,
            sourceScript: String,
            scriptId: String,
            requests: RequestExecutor,
        ): SourceRuntime {
            val engine = SourceScriptEngine.create(scriptId)
            val runtime = SourceRuntime(
                engine = engine,
                key = randomKey(),
                natives = JsNativeFunctions(),
                requests = requests,
                timers = Executors.newSingleThreadScheduledExecutor { r ->
                    Thread(r, "lyra-source-timer-$scriptId").apply { isDaemon = true }
                },
            )
            try {
                runtime.bindBridge()
                engine.evaluate(preloadScript, "lx-preload.js")
                runtime.scriptInfo = ScriptInfo.parse(sourceScript)
                engine.callGlobal(
                    "lx_setup", runtime.key, scriptId,
                    runtime.scriptInfo.name, runtime.scriptInfo.description,
                    runtime.scriptInfo.version, runtime.scriptInfo.author,
                    runtime.scriptInfo.homepage, sourceScript,
                )
                engine.evaluate(sourceScript, "$scriptId.js")

                withTimeout(INIT_TIMEOUT_SECONDS * 1000) { runtime.initDeferred.await() }
                return runtime
            } catch (e: Throwable) {
                runtime.close()
                if (e is SourceInitException) throw e
                throw SourceInitException("音源初始化失败: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        private fun randomKey(): String =
            SecureRandom().let { java.math.BigInteger(128, it).toString(16) }
    }

    /**
     * 宿主 → 脚本 的单向通道。
     *
     * 必须在**别的**线程上调用：callGlobal 内部会把任务投到脚本线程并阻塞等待结果。
     * 若先 withContext(engine.dispatcher) 进了脚本线程再调它，就是往同一条单线程执行器
     * 二次投递并等它完成——自死锁。切到 IO 上等，同时保证 withTimeout 真的可取消。
     */
    private suspend fun callScript(action: String, payload: JSONObject?) {
        if (closed) throw SourceInitException("音源已卸载")
        withContext(Dispatchers.IO) {
            if (payload == null) engine.callGlobal("__lx_native__", key, action)
            else engine.callGlobal("__lx_native__", key, action, payload.toString())
        }
    }

    suspend fun invoke(source: String, action: String, info: JSONObject): JSONObject {
        if (!initDeferred.isCompleted) throw SourceInitException("音源尚未完成 init 握手")
        val requestKey = "invoke-${System.nanoTime()}"
        val deferred = CompletableDeferred<JSONObject>()
        pendingInvokes[requestKey] = deferred

        val data = JSONObject().apply {
            put("source", source)
            put("action", action)
            put("info", info)
        }
        callScript(
            "request",
            JSONObject().apply {
                put("requestKey", requestKey)
                put("data", data)
            },
        )

        return try {
            withTimeout(INVOKE_TIMEOUT_SECONDS * 1000) { deferred.await() }
        } finally {
            pendingInvokes.remove(requestKey)
        }
    }

    override fun close() {
        closed = true
        requests.cancelAll()
        timers.shutdownNow()
        pendingInvokes.values.forEach { it.completeExceptionally(SourceInitException("音源已卸载")) }
        pendingInvokes.clear()
        runCatching { engine.close() }
    }

    /**
     * 卸载后必须丢弃迟到回调。
     *
     * 音源的 in-flight 请求可能在 close 之后才返回；此时脚本线程已终止，往它投递
     * 会抛 RejectedExecutionException——未捕获的异常会炸掉整个进程，instrumentation
     * 把它记到当前用例头上，后续用例直接不执行。这是真机上踩到的。
     */
    @Volatile
    private var closed = false

    // ---------- JS → 宿主 ----------

    private fun bindBridge() {
        engine.bindGlobal("__lx_native_call__") { args ->
            handleNativeCall(args.getOrNull(0) as? String, args.getOrNull(1) as? String, args.getOrNull(2) as? String)
        }
        engine.bindGlobal("__lx_native_call__set_timeout") { args -> scheduleTimer(args) }
        engine.bindGlobal("__lx_native_call__clear_timeout") { args ->
            (args.getOrNull(0) as? String)?.let { id -> timerTasks.remove(id)?.cancel(false) }
            null
        }
        engine.bindGlobal("__lx_native_call__utils_str2b64") { args ->
            natives.strToBase64(args.string(0))
        }
        engine.bindGlobal("__lx_native_call__utils_b642buf") { args ->
            natives.base64ToByteArrayJson(args.string(0))
        }
        engine.bindGlobal("__lx_native_call__utils_buf2b64") { args ->
            natives.byteArrayJsonToBase64(args.string(0))
        }
        engine.bindGlobal("__lx_native_call__utils_str2md5") { args -> natives.md5Hex(args.string(0)) }
        engine.bindGlobal("__lx_native_call__utils_sha") { args -> natives.shaHex(args.string(0), args.string(1)) }
        engine.bindGlobal("__lx_native_call__utils_random_bytes") { args ->
            natives.randomBytes((args.getOrNull(0) as? Number)?.toInt() ?: 0)
        }
        engine.bindGlobal("__lx_native_call__utils_aes_encrypt") { args ->
            natives.aesCrypt(args.string(0), args.string(1), args.optString(2), args.string(3), true)
        }
        engine.bindGlobal("__lx_native_call__utils_aes_decrypt") { args ->
            natives.aesCrypt(args.string(0), args.string(1), args.optString(2), args.string(3), false)
        }
        engine.bindGlobal("__lx_native_call__utils_des_crypt") { args ->
            natives.desCrypt(args.string(0), args.string(1), args.string(2), args.getOrNull(3) as? Boolean ?: true)
        }
        engine.bindGlobal("__lx_native_call__utils_rsa_encrypt") { args ->
            natives.rsaEncrypt(args.string(0), args.string(1), args.optString(2, "RSA/ECB/NoPadding"))
        }
        engine.bindGlobal("__lx_native_call__utils_zlib") { args -> natives.zlib(args.string(0), args.string(1)) }
    }

    private val timerTasks = mutableMapOf<String, java.util.concurrent.ScheduledFuture<*>>()

    private fun scheduleTimer(args: Array<Any?>): Any? {
        val id = args.getOrNull(0) as? String ?: return null
        val delay = ((args.getOrNull(1) as? Number)?.toLong() ?: 0L).coerceIn(0L, 5 * 60 * 1000L)
        val task = timers.schedule({
            timerTasks.remove(id)
            // 回调跑在定时器线程上，由 engine 内部转投脚本线程
            engine.callGlobal("__lx_native__", key, "__set_timeout__", JSONObject().put("id", id).toString())
        }, delay, TimeUnit.MILLISECONDS)
        timerTasks[id] = task
        return null
    }

    private fun handleNativeCall(senderKey: String?, action: String?, dataJson: String?): Any? {
        if (senderKey != key) return "Invalid key"
        if (action == null) return null
        val data = runCatching { JSONObject(dataJson ?: "{}") }.getOrElse { JSONObject() }
        when (action) {
            "init" -> onInited(data)
            "showUpdateAlert" -> updateAlertHandler?.invoke(
                data.optString("log"),
                data.optString("updateUrl").ifEmpty { null },
            )

            "request" -> onRequest(data)
            "cancelRequest" -> requests.cancel(data.optString("requestKey"))
            "response" -> onInvokeResponse(data)
        }
        return null
    }

    private fun onInited(data: JSONObject) {
        if (!data.optBoolean("status", false)) {
            initDeferred.completeExceptionally(SourceInitException(data.optString("message", "音源上报初始化失败")))
            return
        }
        val sources = data.optJSONObject("sources") ?: JSONObject()
        capabilities = buildMap {
            for (sourceName in sources.keys()) {
                val entry = sources.optJSONObject(sourceName) ?: continue
                put(
                    sourceName,
                    SourceCapability(
                        name = entry.optString("name", sourceName),
                        type = entry.optString("type", "music"),
                        actions = entry.optJSONArray("actions").toStringList(),
                        qualitys = entry.optJSONArray("qualitys").toStringList(),
                    ),
                )
            }
        }
        initDeferred.complete(Unit)
    }

    private fun onRequest(data: JSONObject) {
        val requestKey = data.optString("requestKey")
        val url = data.optString("url")
        val options = data.optJSONObject("options") ?: JSONObject()
        val parsed = RequestExecutor.Options(
            method = options.optString("method", "GET"),
            headers = options.optJSONObject("headers").toStringMap(),
            body = options.optString("body").takeIf { it.isNotEmpty() },
            // encoding 是字符集不是 MIME，不能当 contentType 用，否则 toMediaType() 直接抛
            contentType = options.optString("contentType").takeIf { it.isNotEmpty() },
            form = options.optJSONObject("form").toStringMap(),
        )
        val binary = options.optBoolean("binary", false)

        requests.execute(requestKey, url, parsed) { result ->
            if (closed) return@execute
            val payload = when (result) {
                is RequestExecutor.Result.Failure ->
                    JSONObject().put("requestKey", requestKey).put("error", result.message)

                is RequestExecutor.Result.Success -> JSONObject()
                    .put("requestKey", requestKey)
                    .put("error", JSONObject.NULL)
                    .put(
                        "response",
                        JSONObject()
                            .put("statusCode", result.statusCode)
                            .put("headers", JSONObject(result.headers as Map<*, *>))
                            .apply {
                                if (binary) {
                                    put("body", org.json.JSONArray(result.body.toList().map { it.toInt() and 0xff }))
                                } else {
                                    put("body", String(result.body, Charsets.UTF_8))
                                }
                            },
                    )
            }
            // 回投脚本线程：这条边界必须由 __lx_native__ 走，Promise 才会被排空
            engine.callGlobal("__lx_native__", key, "response", payload.toString())
        }
    }

    private fun onInvokeResponse(data: JSONObject) {
        val requestKey = data.optString("requestKey")
        val deferred = pendingInvokes[requestKey] ?: return
        if (data.optBoolean("status", false)) {
            deferred.complete(data.optJSONObject("result") ?: JSONObject())
        } else {
            deferred.completeExceptionally(SourceInitException(data.optString("errorMessage", "音源返回失败")))
        }
    }
}

/** 脚本头部注释块里的元信息，洛雪靠它显示音源名称 */
data class ScriptInfo(
    val name: String = "",
    val description: String = "",
    val version: String = "",
    val author: String = "",
    val homepage: String = "",
) {
    companion object {
        private val TAG = Regex("""@\s*(name|description|version|author|homepage)\s+(.*)""")

        fun parse(script: String): ScriptInfo {
            var name = ""; var description = ""; var version = ""; var author = ""; var homepage = ""
            script.lineSequence().take(40).forEach { line ->
                TAG.find(line)?.destructured?.let { (tag, value) ->
                    val text = value.trim()
                    when (tag) {
                        "name" -> if (name.isEmpty()) name = text
                        "description" -> if (description.isEmpty()) description = text
                        "version" -> if (version.isEmpty()) version = text
                        "author" -> if (author.isEmpty()) author = text
                        "homepage" -> if (homepage.isEmpty()) homepage = text
                    }
                }
            }
            return ScriptInfo(name, description, version, author, homepage)
        }
    }
}

private fun Array<Any?>.string(index: Int): String =
    (this.getOrNull(index) as? String) ?: throw IllegalArgumentException("expected string at $index")

private fun Array<Any?>.optString(index: Int, default: String = ""): String =
    (this.getOrNull(index) as? String) ?: default

private fun org.json.JSONArray?.toStringList(): List<String> =
    this?.let { arr -> List(arr.length()) { i -> arr.optString(i) } } ?: emptyList()

private fun JSONObject?.toStringMap(): Map<String, String> =
    this?.let { obj -> buildMap { obj.keys().forEach { key -> put(key, obj.optString(key)) } } } ?: emptyMap()
