package io.github.sakurajay.lyra.core.source.repo

import io.github.sakurajay.lyra.core.source.runtime.ScriptInfo
import io.github.sakurajay.lyra.core.source.runtime.SourceCapability
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

class InvalidSourceException(message: String) : Exception(message)

data class StoredSource(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val fileName: String,
    val enabled: Boolean,
    val importedAt: Long,
    /** 来源标识 → 该来源声明支持的 action，由音源自己在 inited 里上报后回填 */
    val capabilities: Map<String, List<String>> = emptyMap(),
)

/**
 * 已导入音源的存储。
 *
 * 一期先用文件 + JSON 索引：脚本正文各存一个文件，索引存元信息与启用状态。
 * Room 属于 M4（收藏与歌单）的范围，不为一条切片提前引入数据库。
 *
 * 脚本正文可能含 token，因此只落在应用私有目录，且不入库（.gitignore 已排除 assets/sources）。
 */
class SourceRepository(rootDir: File) {

    private val sourcesDir = File(rootDir, "sources").apply { mkdirs() }
    private val indexFile = File(rootDir, "sources.json")

    fun list(): List<StoredSource> =
        readIndex().toSequence().filterIsInstance<JSONObject>().map { it.toStoredSource() }.toList()

    fun scriptText(id: String): String {
        val source = list().firstOrNull { it.id == id } ?: throw InvalidSourceException("音源不存在: $id")
        val file = File(sourcesDir, source.fileName)
        if (!file.isFile) throw InvalidSourceException("音源文件丢失: ${source.fileName}")
        return file.readText()
    }

    /** 落库；同一份脚本内容重复导入时返回既有记录，不产生副本。 */
    fun import(scriptText: String, capabilities: Map<String, SourceCapability> = emptyMap()): StoredSource {
        if (scriptText.isBlank()) throw InvalidSourceException("脚本内容为空")

        val id = idOf(scriptText)
        val index = JSONArray(readIndexText())
        val existing = index.toSequence().filterIsInstance<JSONObject>().firstOrNull { it.optString("id") == id }
        if (existing != null) {
            existing.put("capabilities", capabilitiesToJson(capabilities))
            writeIndex(index)
            return existing.toStoredSource()
        }

        val info = ScriptInfo.parse(scriptText)
        if (info.name.isBlank()) throw InvalidSourceException("脚本缺少 @name 声明，无法识别")

        val fileName = "$id.js"
        File(sourcesDir, fileName).writeText(scriptText)

        index.put(
            JSONObject()
                .put("id", id)
                .put("name", info.name)
                .put("version", info.version)
                .put("author", info.author)
                .put("fileName", fileName)
                .put("enabled", true)
                .put("importedAt", System.currentTimeMillis())
                .put("capabilities", capabilitiesToJson(capabilities)),
        )
        writeIndex(index)

        return StoredSource(id, info.name, info.version, info.author, fileName, true, System.currentTimeMillis(), capabilitiesToActions(capabilities))
    }

    fun setEnabled(id: String, enabled: Boolean) = mutate(id) { it.put("enabled", enabled) }

    fun updateCapabilities(id: String, capabilities: Map<String, SourceCapability>) =
        mutate(id) { it.put("capabilities", capabilitiesToJson(capabilities)) }

    /** 删除记录与脚本文件；文件删除失败不阻塞索引更新，避免留下幽灵条目。 */
    fun delete(id: String) {
        val index = JSONArray(readIndexText())
        val kept = JSONArray()
        index.toSequence().forEach { entry ->
            if (entry is JSONObject && entry.optString("id") != id) kept.put(entry)
            else if (entry is JSONObject) File(sourcesDir, entry.optString("fileName")).delete()
        }
        writeIndex(kept)
    }

    fun enabledSources(): List<StoredSource> = list().filter { it.enabled }

    private fun mutate(id: String, block: (JSONObject) -> Unit) {
        val index = JSONArray(readIndexText())
        var hit = false
        index.toSequence().forEach { entry ->
            if (entry is JSONObject && entry.optString("id") == id) {
                block(entry)
                hit = true
            }
        }
        if (!hit) throw InvalidSourceException("音源不存在: $id")
        writeIndex(index)
    }

    private fun readIndexText(): String =
        if (indexFile.isFile) indexFile.readText() else "[]"

    private fun readIndex(): JSONArray = runCatching { JSONArray(readIndexText()) }.getOrElse { JSONArray() }

    private fun writeIndex(index: JSONArray) {
        indexFile.writeText(index.toString())
    }

    /** 内容寻址：同内容同 id，重复导入不产生副本 */
    private fun idOf(scriptText: String): String =
        MessageDigest.getInstance("SHA-256").digest(scriptText.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)

    private fun JSONObject.toStoredSource() = StoredSource(
        id = optString("id"),
        name = optString("name"),
        version = optString("version"),
        author = optString("author"),
        fileName = optString("fileName"),
        enabled = optBoolean("enabled", true),
        importedAt = optLong("importedAt"),
        capabilities = jsonToCapabilitiesMap(optJSONObject("capabilities")),
    )
}

private fun JSONArray.toSequence(): Sequence<Any?> = sequence {
    for (i in 0 until length()) yield(get(i))
}

private fun capabilitiesToJson(capabilities: Map<String, SourceCapability>): JSONObject =
    JSONObject().apply {
        // 必须显式建 JSONArray：把 Kotlin List 直接交给 put 会被序列化成空数组
        capabilities.forEach { (source, cap) -> put(source, JSONArray(cap.actions)) }
    }

private fun capabilitiesToActions(capabilities: Map<String, SourceCapability>): Map<String, List<String>> =
    capabilities.mapValues { (_, cap) -> cap.actions }

private fun jsonToCapabilitiesMap(obj: JSONObject?): Map<String, List<String>> {
    obj ?: return emptyMap()
    return buildMap {
        obj.keys().forEach { key ->
            val array = obj.optJSONArray(key)
            put(key, if (array == null) emptyList() else List(array.length()) { i -> array.optString(i) })
        }
    }
}
