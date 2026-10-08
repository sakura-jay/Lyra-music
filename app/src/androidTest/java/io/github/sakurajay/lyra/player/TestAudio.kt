package io.github.sakurajay.lyra.player

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 测试内合成 PCM WAV，由 MockWebServer 供流。
 *
 * 出声链路的验证不能挂在外部音频地址上：凭记忆写的 URL 既会失效，也会把网络抖动
 * 当成回归。不同频率用来区分两首歌——重叠播放时能确认确实是两段不同的音频。
 */
object TestAudio {

    /** 16-bit 单声道正弦波，44 字节标准 RIFF 头。 */
    fun wav(durationMs: Int, frequency: Double = 440.0, sampleRate: Int = 8000): ByteArray {
        val frames = sampleRate * durationMs / 1000
        val dataBytes = frames * 2
        val out = ByteArrayOutputStream(44 + dataBytes)

        fun le32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun le16(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())

        out.write("RIFF".toByteArray())
        le32(36 + dataBytes)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        le32(16)
        le16(1)                       // PCM
        le16(1)                       // mono
        le32(sampleRate)
        le32(sampleRate * 2)          // byte rate
        le16(2)                       // block align
        le16(16)                      // bits
        out.write("data".toByteArray())
        le32(dataBytes)

        val pcm = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val sample = (Math.sin(2 * Math.PI * frequency * i / sampleRate) * 12000).toInt()
            pcm.putShort(sample.toShort())
        }
        out.write(pcm.array())
        return out.toByteArray()
    }
}
