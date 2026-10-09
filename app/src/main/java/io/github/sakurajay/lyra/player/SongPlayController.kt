package io.github.sakurajay.lyra.player

import io.github.sakurajay.lyra.domain.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 列表页"点一首歌"的共同流程：取消上一次在途取址、把每一步说给界面、回写结果。
 *
 * 抽出来是因为四个列表页各抄了一份，而抄的那份会漏：以前用
 * `if (loadingSongId != null) return` 挡住并发点击，结果一次卡住的取址会让整页
 * 点什么都没反应，而且不吭声。现在改成"新点击取消旧取址"。
 */
class SongPlayController(private val launcher: SongQueueLauncher) {

    private var job: Job? = null

    /**
     * @param onStage 取址过程中的每一步（试哪个源、降到哪档、换源到第几家）
     * @param onSettled 只有真正跑完才会回调；被新点击取消时不会覆盖新状态
     */
    fun play(
        scope: CoroutineScope,
        song: Song,
        queue: List<Song>,
        onStage: (String) -> Unit,
        onSettled: (LaunchOutcome) -> Unit,
    ) {
        job?.cancel()
        job = scope.launch {
            onSettled(launcher.launch(song, queue, onStage))
        }
    }
}
