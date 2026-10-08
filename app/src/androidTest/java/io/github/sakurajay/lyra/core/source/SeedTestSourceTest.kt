package io.github.sakurajay.lyra.core.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sakurajay.lyra.di.ServiceLocator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 把测试音源种回应用，供每次重装后的手动验证使用。
 *
 * 装一次包（尤其是跑完 connectedDebugAndroidTest）应用数据就没了，手工导入的音源
 * 跟着消失，每次手测都得重新导一遍。这条测试读 androidTest assets 里那份 **gitignore
 * 掉**的真实音源，走与界面完全相同的"导入即试跑"路径把它种回去——顺带保证种进去的
 * 能力清单是真握手拿到的，不是硬编码。
 *
 * 单独跑：`./gradlew :app:connectedDebugAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=io.github.sakurajay.lyra.core.source.SeedTestSourceTest`
 */
@RunWith(AndroidJUnit4::class)
class SeedTestSourceTest {

    @Test
    fun seedRealSourceThroughTheSameImportPathAsTheUi() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val script = runCatching {
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("real-sources/lyra-test-source.js").use { it.readBytes().decodeToString() }
        }.getOrNull()
        assumeTrue("未放入 app/src/androidTest/assets/real-sources/lyra-test-source.js，跳过", script != null)

        val repository = ServiceLocator.sourceRepository(app)
        when (val result = SourceHosting.validate(app, script!!)) {
            is ValidationResult.Accepted -> {
                val stored = repository.import(script, result.capabilities)
                assertTrue("种完必须能在列表里看到", repository.list().any { it.id == stored.id })
                println("音源已播种：${stored.name}｜能力平台=${result.capabilities.keys}")
            }

            is ValidationResult.Rejected -> fail("音源握手失败，未播种：${result.message}")
        }
    }
}
