package io.github.sakurajay.lyra.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 底部导航与迷你播放条叠加在内容之上后，Tab 页滚动容器需要让出的尾部空间。
 *
 * 这个值不是普通布局 padding：页面视口要一直延伸到浮层后面，才能让滚动内容
 * 真正经过玻璃层；只给列表的 contentPadding，首项位置与可点区域都不变。
 */
val LocalBottomBarContentPadding = staticCompositionLocalOf<Dp> { 0.dp }
