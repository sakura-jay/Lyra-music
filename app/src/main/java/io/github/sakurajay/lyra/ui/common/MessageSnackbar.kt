package io.github.sakurajay.lyra.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/**
 * 点歌与各种操作的结果提示。
 *
 * 以前这些提示是列表里的一行（首页）或列表上方的一行（搜索/音乐库/歌单详情）：
 * 前者在点列表靠下的歌时生成在顶部、当场就滚出屏幕，还把整列挤下一个行高；
 * 后者虽然看得见，也一样每闪一次就把下面的列表顶动一下。
 *
 * 现在统一走 Snackbar——它渲染在迷你播放条正上方，不参与列表布局，也不会被滚走。
 *
 * [sticky] 为 true 的提示常驻到被下一条替换。取址过程（"正在向「X」取址（无损）…"）必须
 * 整段都看得见：用 Short 会在 4 秒后自己消失，用户又回到"只有一个转轮，不知道是在等慢源
 * 还是已经卡死"。结果类提示用 Short，说完就走，并由 [onShown] 清掉状态里的那条。
 */
@Composable
fun rememberMessageHost(message: String?, sticky: Boolean, onShown: () -> Unit): SnackbarHostState {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(message, sticky) {
        if (message.isNullOrBlank()) return@LaunchedEffect
        val result = host.showSnackbar(
            message = message,
            duration = if (sticky) SnackbarDuration.Indefinite else SnackbarDuration.Short,
        )
        // 常驻那条不该在消失时被清掉，否则状态会先于下一条消息变成空
        if (!sticky && result in setOf(SnackbarResult.Dismissed, SnackbarResult.ActionPerformed)) onShown()
    }
    return host
}
