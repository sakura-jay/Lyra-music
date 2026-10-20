package io.github.sakurajay.lyra.ui.common

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.ui.motion.LocalMotionScale
import io.github.sakurajay.lyra.ui.motion.LyraMotion
import io.github.sakurajay.lyra.ui.motion.MotionSpec

/**
 * 迷你播放条那两行"曲名 + 艺术家"，换曲时交替（014）。
 *
 * **判据是曲目身份**（[songIdentity]），不是整个 `Song`、也不是播放状态：同一首歌补上封面、
 * 拿到时长、或者播放 / 暂停翻转，两行文字都不许重播一次交替。012 那条"几何跟手、身份跳变"
 * 是同一原则的另一面——只有身份变了才配得上一次交替。
 *
 * 快速连点切歌时只往**最新那一首**交替：这是 `AnimatedContent` 自带的行为（新目标到来时取消
 * 上一次未完成的交替），不要"顺手"把它改成排队播放——那会追不上手指，读起来是一段滚动字幕。
 */
@Composable
fun LyraSongLines(
    song: Song?,
    modifier: Modifier = Modifier,
    @StringRes idleLabel: Int = R.string.mini_player_idle,
    @StringRes idleHint: Int = R.string.mini_player_idle_hint,
) {
    val scale = LocalMotionScale.current
    val text = songLineText(
        song = song,
        idleTitle = stringResource(idleLabel),
        idleArtist = stringResource(idleHint),
        unknownArtist = stringResource(R.string.mini_player_unknown_artist),
    )
    if (!MotionSpec.shouldAnimate(scale)) {
        // 关掉动画时**根本不进 AnimatedContent**。这不只是"省一层动画"：实测
        // `EnterTransition.None togetherWith ExitTransition.None` 仍然会把旧、新两份内容
        // **同时画出一帧**（约 16ms），也就是迷你条上会闪一下"两行字叠在一起"。
        // 量出来的三个差值：静止A↔第0帧 1863px、第0帧↔第1帧 1800px、第1帧↔稳定态 0px——
        // 第 0 帧既不是 A 也不是 B，只能是两份同画。`SongLinesUiTest` 里有钉住这一条的测试。
        // ADR-0009 把"0"定成直接到终态，只有走这里才真办得到。
        SongLines(text, modifier)
    } else {
        AnimatedContent(
            // 这个对象是**交替的触发键**：AnimatedContent 按 equals 决定要不要动一次，
            // 所以"什么算换曲"全在 [songLineText] 的字段表里，那条纯函数有 JVM 单测直接断言它。
            targetState = text,
            // `.using(null)`：ContentTransform 默认带一层 SizeTransform（用 spring 动画容器尺寸），
            // 而**动画任何参与布局的属性**是 ADR-0009 护栏二明令禁止的。`using` 是
            // AnimatedContentTransitionScope 的成员扩展，只能在这个 lambda 里调，
            // 所以它留在调用处而不是收进 LyraMotion.songLineSwap。
            transitionSpec = { LyraMotion.songLineSwap(scale).using(null) },
            modifier = modifier,
            contentAlignment = Alignment.CenterStart,
            label = "songLines",
        ) { line -> SongLines(line, Modifier) }
    }
}

/** 两行字本身。开不开动画都画同一个东西，所以"到终态"与"交替结束"长得一模一样 */
@Composable
private fun SongLines(line: SongLineText, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = line.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = line.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 交替的那一份状态：身份 + 两行要显示的字。
 *
 * `AnimatedContent` 用 `equals` 判断要不要交替，所以这个类的字段表**就是**"什么算换曲"的定义：
 * - 有 [identity]：换一首同名同艺术家的歌照样交替——旁边的封面同时变了，
 *   不动一下反而读成"这一行还属于上一首"。
 * - 不含 `Song` 本体：封面、时长、清晰度这些字段更新时两行字一模一样，重播一次交替是无信息噪声。
 * - 含 [title] / [artist]：万一某条路径把同一 `id` 的曲名改了（本地文件改标签后重新扫描），
 *   文字必须换过去，不能因为"身份没变"就停在旧字上。
 */
internal data class SongLineText(
    val identity: Pair<String, String>?,
    val title: String,
    val artist: String,
)

/**
 * 由"正在播的这首歌 + 三份现成的文案"算出交替键。
 *
 * 单独成一个纯函数（而不是就地拼进 `SongLineText(...)`）有两个用处：一，三份文案都要走
 * `stringResource`，那是组合期调用，拼在一起就没法在 JVM 上测；二，这个函数**就是**"什么算换曲"
 * 的可执行定义，`SongLineTextTest` 直接断言它的等式，而不靠读注释。
 */
internal fun songLineText(
    song: Song?,
    idleTitle: String,
    idleArtist: String,
    unknownArtist: String,
): SongLineText = SongLineText(
    identity = songIdentity(song),
    title = song?.name ?: idleTitle,
    artist = song?.let { artistLine(it.artists, unknownArtist) } ?: idleArtist,
)

/**
 * 曲目身份 = 音源 + 曲 id。
 *
 * 带上 [Song.source] 是因为 `id` 只在**单个音源内**才是主键：本地曲目的 `id` 由文件路径派生，
 * 平台曲目由各家自己的接口给出，两边没有任何"全局不撞"的保证。这一对与库里的曲目键同形
 * （`FavoritesRepository.keyOf` 就是 `"$source:$id"`），所以判"换了一首歌"沿用同一个口径，
 * 不在 UI 层另发明一套身份。
 */
internal fun songIdentity(song: Song?): Pair<String, String>? = song?.let { it.source to it.id }

/**
 * 艺术家那一行：空白项丢掉，全空时给"未知艺术家"而不是"导入音源后即可播放"。
 *
 * 没有艺术家不等于没在播——本地文件常常就没有标签，那句提示以前正在播本地歌时也那么写。
 */
internal fun artistLine(artists: List<String>, unknownArtist: String): String =
    artists.filter { it.isNotBlank() }.joinToString(" / ").ifBlank { unknownArtist }
