import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// 发布签名只在本机放了 keystore.properties 时启用；密钥与口令都不入库
// （.gitignore 已排除 keystore.properties / *.jks / *.keystore）。
// 没有它时 release 照样构建，只是产出未签名包——CI 不会因为缺密钥而红，
// 也不会误用某个人的调试密钥当发布密钥。
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "io.github.sakurajay.lyra"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.sakurajay.lyra"
        // ADR-0005：minSdk 31 换取 RenderEffect 模糊与 Material 3 动态取色的单一路径
        minSdk = 31
        targetSdk = 36
        // 与"已收口的里程碑"对齐：M8 = v0.9.0，其审查修订批次作为补丁版 0.9.1 发布。
        // 版本权威仍是 tag，这里只给"设置→关于"和一个可辨识的安装包用。
        versionCode = 6
        versionName = "0.9.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // "有新版本可用"这类提示对本项目是噪音：compileSdk 被 AGP 8.13.2 卡在 36，
        // compose-bom 2026.09 / core-ktx 1.19 / okhttp 5.x 全部要求 37，升不动。
        // 报着只会让人以为"该升但没升"，真实约束记在 CHANGELOG 的工具链一节。
        disable += "GradleDependency"
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.quickjs.wrapper.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.mockwebserver)
}
