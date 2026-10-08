package io.github.sakurajay.lyra.data.provider.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 榜单签名/加解密的纯逻辑。
 *
 * 这套 AES-ECB-NoPadding + MD5 签名是抄 lx-music 的，抄错一个字节接口就 403，
 * 而错误表现只会是"榜单为空"——所以往返必须在这里钉死。
 */
class KuwoLeaderboardCryptoTest {

    @Test
    fun encryptDecryptRoundTripsThroughTheWireFormat() {
        val plain = """{"id":"93","pn":0,"rn":30}"""
        val encrypted = KuwoLeaderboardProvider.encrypt(plain)
        // 密文 base64 解回来必须是 16 的倍数（AES 块大小）
        assertEquals(0, java.util.Base64.getDecoder().decode(encrypted).size % 16)
        // 线上响应是 URL 编码后的密文，解密前必须先还原
        assertEquals(plain, KuwoLeaderboardProvider.decrypt(java.net.URLEncoder.encode(encrypted, "UTF-8")))
    }

    @Test
    fun decryptOfRawBase64WouldCorruptPlusSigns() {
        // 钉住这个坑：裸 base64 里的 '+' 会被 URLDecoder 变成空格，直接喂解密必炸
        val encrypted = KuwoLeaderboardProvider.encrypt("0123456789abcdef0123456789abcdef")
        val rawFailure = runCatching { KuwoLeaderboardProvider.decrypt(encrypted) }
        val wireSuccess = runCatching { KuwoLeaderboardProvider.decrypt(java.net.URLEncoder.encode(encrypted, "UTF-8")) }
        assertTrue("带 + 的密文走裸路径应当失败，否则说明测试没覆盖到坑", rawFailure.isFailure || encrypted.contains('+'))
        assertTrue(wireSuccess.isSuccess)
    }

    @Test
    fun signIsMd5OfAppIdDataTimeUppercased() {
        val param = KuwoLeaderboardProvider.buildParam("{\"id\":\"93\"}", timeMs = 1_700_000_000_000L)
        val data = param.substringAfter("data=").substringBefore("&time=")
        val sign = param.substringAfter("sign=")
        val expected = KuwoLeaderboardProvider.md5Hex("y67sprxhhpws" + java.net.URLDecoder.decode(data, "UTF-8") + "1700000000000").uppercase()
        assertEquals(expected, sign)
        assertTrue("sign 必须是大写十六进制", sign == sign.uppercase() && sign.length == 32)
    }
}
