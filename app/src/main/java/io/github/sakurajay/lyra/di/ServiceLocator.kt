package io.github.sakurajay.lyra.di

import android.content.Context
import io.github.sakurajay.lyra.core.source.repo.SourceRepository
import java.io.File

/**
 * 手写依赖注入。
 *
 * 项目还没有引入 Hilt：M1 只有一个跨页共享依赖（音源存储）。按 Google 的架构指南，
 * 依赖少时用 ServiceLocator 比引入注解处理器更划算；等 M4 的 Room 与各仓库对象出现，
 * 再评估是否换成 Hilt，届时这里是唯一的替换点。
 */
object ServiceLocator {

    @Volatile
    private var repository: SourceRepository? = null

    fun sourceRepository(context: Context): SourceRepository {
        return repository ?: synchronized(this) {
            repository ?: SourceRepository(File(context.filesDir, "source-store")).also { repository = it }
        }
    }
}
