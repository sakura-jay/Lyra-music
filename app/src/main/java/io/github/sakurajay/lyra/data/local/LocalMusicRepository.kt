package io.github.sakurajay.lyra.data.local

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.domain.model.QualityAvailability
import io.github.sakurajay.lyra.domain.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 设备上的音频文件。
 *
 * 只读 MediaStore，不自己走文件系统：分区存储之后应用枚举 `/sdcard` 既需要
 * MANAGE_EXTERNAL_STORAGE（上架会被问为什么），又会把别人藏起来的目录翻出来。
 * MediaStore 给的就是"用户能听到的那些歌"，这正是我们要的集合。
 *
 * 映射成 [Song] 而不是另立一种类型：列表行、收藏、歌单、备份、播放链全都已经认 Song，
 * 再开一条本地曲目的平行通路等于把这些能力在本地那一半全部重做一遍。
 *
 * 收 [ContentResolver] 而不是 Context：这个仓库由进程级单例持有，存 Context 会被 lint
 * 判成静态泄漏，而它本来就只需要一个解析器。
 */
class LocalMusicRepository(private val resolver: ContentResolver) {

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val scanned = MutableStateFlow(false)
    /** 区分"扫过但一首没有"和"还没扫"，界面据此决定显示空态还是授权引导 */
    val hasScanned: StateFlow<Boolean> = scanned.asStateFlow()

    private val mutex = Mutex()

    /**
     * 重扫。调用方必须先确认 [hasPermission]——没授权时 MediaStore 只回一个空游标，
     * 分不出"用户不让看"和"手机上确实没歌"，这两种空态在界面上是两回事。
     */
    suspend fun refresh(): List<Song> = mutex.withLock {
        val read = withContext(Dispatchers.IO) { query() }
        _songs.value = read
        scanned.value = true
        read
    }

    // Cursor 由下面的 rows.use 关；lint 认不出 Kotlin 的 use，只能明确告诉它
    @SuppressLint("Recycle")
    private fun query(): List<Song> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.BITRATE,
            MediaStore.Audio.Media.MIME_TYPE,
        )
        val rows = resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            // 新塞进去的排前面：用户拷完歌打开应用，第一眼看的就是这几首
            "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        ) ?: return emptyList()
        return rows.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val bitrateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.BITRATE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val durationSec = (cursor.getLong(durationCol) / 1000L).toInt()
                    if (durationSec <= 0) continue
                    add(
                        Song(
                            id = id.toString(),
                            source = Song.LOCAL_SOURCE,
                            name = cursor.getString(titleCol).orEmpty(),
                            artists = listOfNotNull(cursor.getString(artistCol)?.takeUnless(::isUnknown)),
                            albumName = cursor.getString(albumCol).orEmpty(),
                            albumId = cursor.getLong(albumIdCol).toString(),
                            durationSec = durationSec,
                            qualities = listOf(declaredQuality(
                                mime = cursor.getString(mimeCol).orEmpty(),
                                bitrateKbps = cursor.getInt(bitrateCol),
                            )),
                            artworkUrl = albumArtworkUri(cursor.getLong(albumIdCol)),
                            localUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon()
                                .appendPath(id.toString()).build().toString(),
                        ),
                    )
                }
            }
        }
    }

    companion object {
        /** 33 以下没有 READ_MEDIA_AUDIO，只能申请旧的存储读权限 */
        val requiredPermission: String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE

        fun hasPermission(context: Context): Boolean =
            context.checkSelfPermission(requiredPermission) == PackageManager.PERMISSION_GRANTED

        /** MediaStore 对没有标签的文件填 "<unknown>"，那不是艺术家 */
        private fun isUnknown(value: String): Boolean =
            value.isBlank() || value.equals("<unknown>", ignoreCase = true)

        /** MediaStore 对没有专辑图的曲集填 0，这时不能给一个指向 0 号的假地址 */
        internal fun albumArtworkUri(albumId: Long): String =
            if (albumId <= 0) ""
            else "content://media/external/audio/albums/$albumId/cover"

        /**
         * 本地文件没有"档位"这回事，但列表行与播放页都要显示一个。
         *
         * 按容器与码率给个诚实的近似：flac/wav 这类无损容器算无损，其余看码率。
         * 它只影响显示，取址根本不经过音源，所以错了也不会播成另一档。
         */
        internal fun declaredQuality(mime: String, bitrateKbps: Int): QualityAvailability {
            // ogg/vorbis 是有损的，不能因为"少见"就当成无损
            val lossless = listOf("flac", "wav", "ape", "alac").any { mime.contains(it, ignoreCase = true) } ||
                bitrateKbps >= 1000
            return QualityAvailability(if (lossless) Quality.LOSSLESS else if (bitrateKbps >= 224) Quality.HIGH else Quality.STANDARD)
        }
    }
}
