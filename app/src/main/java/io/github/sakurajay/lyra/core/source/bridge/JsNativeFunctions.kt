package io.github.sakurajay.lyra.core.source.bridge

import android.util.Base64
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.zip.Deflater
import java.util.zip.Inflater
import org.json.JSONArray

/**
 * 音源脚本可用的同步原生能力。
 *
 * 语义对齐官方移动端 preload：key/iv 按"原始字符串的 UTF-8 字节"处理，不是 hex；
 * 这样同一份音源在 Lyra 与洛雪上算出同样的结果，差异只会让取址失败。
 */
class JsNativeFunctions {

    fun strToBase64(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    /** 官方协议要求返回 JSON 数组字符串，供 JS 侧 new Uint8Array(JSON.parse(...)) */
    fun base64ToByteArrayJson(value: String): String =
        JSONArray().apply {
            Base64.decode(value, Base64.NO_WRAP).forEach { put(it.toInt()) }
        }.toString()

    /**
     * 字节数组（JSON）直接转 base64。
     *
     * 官方没有这条通道，它把字节先按 UTF-8 解码成字符串再 base64，任意二进制（AES
     * 密文等）会被破坏；Lyra 用这条无损路径替代。
     */
    fun byteArrayJsonToBase64(json: String): String {
        val array = JSONArray(json)
        val bytes = ByteArray(array.length()) { i -> array.getInt(i).toByte() }
        return bytes.toBase64()
    }

    fun md5Hex(value: String): String = digestHex("MD5", value)

    fun shaHex(value: String, algorithm: String): String =
        digestHex(
            when (algorithm.lowercase().replace("-", "")) {
                "sha1" -> "SHA-1"
                "sha256" -> "SHA-256"
                "sha512" -> "SHA-512"
                else -> throw IllegalArgumentException("Unsupported digest: $algorithm")
            },
            value,
        )

    fun randomBytes(size: Int): String =
        ByteArray(size.coerceIn(0, 1024)).also { SecureRandom().nextBytes(it) }.toBase64()

    /**
     * @param mode "cbc" 走 AES/CBC/PKCS5Padding（等价官方 PKCS7），"ecb" 走 AES/ECB
     */
    fun aesCrypt(dataBase64: String, keyBase64: String, ivBase64: String, mode: String, encrypt: Boolean): String {
        val keyBytes = keyBase64.fromBase64()
        val cipher = Cipher.getInstance(if (mode == "cbc") "AES/CBC/PKCS5Padding" else "AES/ECB/PKCS5Padding")
        val spec = SecretKeySpec(keyBytes, "AES")
        if (mode == "cbc") {
            val iv = ivBase64.fromBase64()
            require(iv.size == 16) { "AES CBC iv must be 16 bytes, got ${iv.size}" }
            cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, spec, IvParameterSpec(iv))
        } else {
            cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, spec)
        }
        return cipher.doFinal(dataBase64.fromBase64()).toBase64()
    }

    /** @param mode des-ecb / des-cbc / des3-ecb / des3-cbc；官方移动版没有 DES，这是 Lyra 的补集 */
    fun desCrypt(dataBase64: String, keyBase64: String, mode: String, encrypt: Boolean): String {
        val keyBytes = keyBase64.fromBase64()
        val triple = mode.startsWith("des3")
        val algorithm = if (triple) "DESede" else "DES"
        val cipherName = if (mode.endsWith("cbc")) "$algorithm/CBC/PKCS5Padding" else "$algorithm/ECB/PKCS5Padding"
        val cipher = Cipher.getInstance(cipherName)
        val spec = SecretKeySpec(keyBytes, algorithm)
        val block = if (triple) 8 else 8
        if (mode.endsWith("cbc")) {
            val iv = ByteArray(block)
            cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, spec, IvParameterSpec(iv))
        } else {
            cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, spec)
        }
        return cipher.doFinal(dataBase64.fromBase64()).toBase64()
    }

    fun rsaEncrypt(dataBase64: String, publicKeyBase64: String, padding: String): String {
        val key = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(publicKeyBase64.fromBase64()))
        val cipher = Cipher.getInstance(padding)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(dataBase64.fromBase64()).toBase64()
    }

    fun rsaDecrypt(dataBase64: String, privateKeyBase64: String, padding: String): String {
        val key = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(privateKeyBase64.fromBase64()))
        val cipher = Cipher.getInstance(padding)
        cipher.init(Cipher.DECRYPT_MODE, key)
        return cipher.doFinal(dataBase64.fromBase64()).toBase64()
    }

    /** 官方移动版注释掉了 zlib，这里补上：入参与返回都是 base64 */
    fun zlib(dataBase64: String, op: String): String {
        val input = dataBase64.fromBase64()
        return when (op) {
            "deflate" -> {
                val deflater = Deflater()
                deflater.setInput(input)
                deflater.finish()
                val out = java.io.ByteArrayOutputStream(input.size)
                val buffer = ByteArray(4096)
                while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
                deflater.end()
                out.toByteArray().toBase64()
            }

            "inflate" -> {
                val inflater = Inflater()
                inflater.setInput(input)
                val out = java.io.ByteArrayOutputStream(input.size * 4)
                val buffer = ByteArray(4096)
                while (!inflater.finished()) {
                    val read = try {
                        inflater.inflate(buffer)
                    } catch (e: java.util.zip.DataFormatException) {
                        throw IllegalArgumentException("bad deflate data", e)
                    }
                    if (read == 0) break
                    out.write(buffer, 0, read)
                }
                inflater.end()
                out.toByteArray().toBase64()
            }

            else -> throw IllegalArgumentException("Unsupported zlib op: $op")
        }
    }

    private fun digestHex(algorithm: String, value: String): String =
        MessageDigest.getInstance(algorithm).digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
}
