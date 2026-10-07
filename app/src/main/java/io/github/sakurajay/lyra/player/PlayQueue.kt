package io.github.sakurajay.lyra.player

import io.github.sakurajay.lyra.domain.model.Song

enum class PlayMode {
    /** 顺序播放：到队尾即停止 */
    SEQUENTIAL,

    /** 列表循环 */
    LOOP_ALL,

    /** 单曲循环 */
    LOOP_ONE,

    /** 随机播放：整轮放完重新洗牌 */
    SHUFFLE,
}

/**
 * 播放队列的纯逻辑实现，不依赖 Android 与播放器。
 *
 * 刻意做成纯函数式状态机：播放模式最容易出错的地方正是"边界"——最后一曲之后去哪、
 * 删掉正在播的那首要不要跳下一首、拖拽后索引怎么跟着走。这些都应该能在没有真机的
 * 情况下被单元测试钉住。
 */
class PlayQueue(
    songs: List<Song> = emptyList(),
    mode: PlayMode = PlayMode.LOOP_ALL,
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
) {

    private val _entries = songs.map { QueueEntry(it) }.toMutableList()
    val entries: List<QueueEntry> get() = _entries.toList()

    var currentIndex: Int = if (_entries.isEmpty()) -1 else 0
        private set

    /** 随机模式的播放顺序；内容变化后重新生成，保证同一轮内顺序稳定 */
    private var shuffleOrder: List<Int> = emptyList()
    private var shuffleCursor: Int = 0

    /** 切到随机模式时立刻建好播放顺序，否则第一轮会退化成顺序播放 */
    var mode: PlayMode = mode
        set(value) {
            field = value
            if (value == PlayMode.SHUFFLE) rebuildShuffle()
        }

    init { if (mode == PlayMode.SHUFFLE) rebuildShuffle() }

    val size: Int get() = _entries.size

    fun current(): Song? = _entries.getOrNull(currentIndex)?.song

    fun isEmpty(): Boolean = _entries.isEmpty()

    fun replaceAll(songs: List<Song>, startIndex: Int = 0) {
        _entries.clear()
        _entries.addAll(songs.map { QueueEntry(it) })
        currentIndex = if (_entries.isEmpty()) -1 else startIndex.coerceIn(0, _entries.lastIndex)
        rebuildShuffleIfNeeded()
    }

    fun jumpTo(index: Int): Song? {
        if (index !in _entries.indices) return null
        currentIndex = index
        if (mode == PlayMode.SHUFFLE) shuffleCursor = shuffleOrder.indexOf(index).coerceAtLeast(0)
        return _entries[index].song
    }

    /** 插入到"下一首"位置：在当前曲之后，不影响当前索引 */
    fun insertNext(song: Song) {
        if (_entries.isEmpty()) {
            _entries.add(QueueEntry(song))
            currentIndex = 0
        } else {
            _entries.add((currentIndex + 1).coerceAtMost(_entries.lastIndex + 1), QueueEntry(song))
        }
        rebuildShuffleIfNeeded()
    }

    fun append(song: Song) {
        _entries.add(QueueEntry(song))
        if (currentIndex == -1) currentIndex = 0
        rebuildShuffleIfNeeded()
    }

    /** 删除一项；删的是当前曲时，当前索引自然落到原来的下一首，末尾则回退 */
    fun removeAt(index: Int): Song? {
        if (index !in _entries.indices) return null
        val removed = _entries.removeAt(index).song
        when {
            _entries.isEmpty() -> currentIndex = -1
            index < currentIndex -> currentIndex--
            index == currentIndex -> currentIndex = index.coerceAtMost(_entries.lastIndex)
            else -> Unit
        }
        rebuildShuffleIfNeeded()
        return removed
    }

    fun move(from: Int, to: Int) {
        if (from !in _entries.indices || to !in _entries.indices || from == to) return
        val playing = current()
        _entries.add(to, _entries.removeAt(from))
        // 拖拽后要让"正在播的那一首"跟着走，否则当前索引会指向别的歌
        currentIndex = _entries.indexOfFirst { it.song === playing }.takeIf { it >= 0 } ?: currentIndex
        rebuildShuffleIfNeeded()
    }

    fun clear() {
        _entries.clear()
        currentIndex = -1
        shuffleOrder = emptyList()
        shuffleCursor = 0
    }

    fun next(): Song? = when (mode) {
        PlayMode.LOOP_ONE -> current()
        PlayMode.SEQUENTIAL -> if (currentIndex < _entries.lastIndex) { currentIndex++; current() } else null
        PlayMode.LOOP_ALL -> {
            if (_entries.isEmpty()) return null
            currentIndex = (currentIndex + 1) % _entries.size
            current()
        }

        PlayMode.SHUFFLE -> advanceShuffle()
    }

    fun previous(): Song? = when (mode) {
        PlayMode.LOOP_ONE -> current()
        PlayMode.SEQUENTIAL -> if (currentIndex > 0) { currentIndex--; current() } else null
        PlayMode.LOOP_ALL -> {
            if (_entries.isEmpty()) return null
            currentIndex = if (currentIndex == 0) _entries.lastIndex else currentIndex - 1
            current()
        }

        PlayMode.SHUFFLE -> {
            if (_entries.isEmpty() || shuffleOrder.isEmpty()) return null
            shuffleCursor = (shuffleCursor - 1).coerceAtLeast(0)
            currentIndex = shuffleOrder[shuffleCursor]
            current()
        }
    }

    private fun advanceShuffle(): Song? {
        if (_entries.isEmpty()) return null
        if (shuffleOrder.isEmpty() || shuffleCursor >= shuffleOrder.lastIndex) {
            rebuildShuffle(force = true)
            shuffleCursor = 0
        } else {
            shuffleCursor++
        }
        currentIndex = shuffleOrder[shuffleCursor]
        return current()
    }

    private fun rebuildShuffleIfNeeded() {
        if (mode == PlayMode.SHUFFLE) rebuildShuffle()
    }

    private fun rebuildShuffle(force: Boolean = false) {
        if (mode != PlayMode.SHUFFLE && !force) return
        shuffleOrder = _entries.indices.toList().shuffled(random)
        shuffleCursor = shuffleOrder.indexOf(currentIndex).coerceAtLeast(0)
    }

    class QueueEntry(val song: Song) {
        val uid: Long = NEXT_ID.getAndIncrement()

        companion object {
            private val NEXT_ID = java.util.concurrent.atomic.AtomicLong(1)
        }
    }
}
