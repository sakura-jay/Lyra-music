# 只做 Android 原生（Kotlin + Compose + Media3），Windows 后置

需求原本是"跨平台音乐播放器"，但用户明确 Android 为主、Windows 可先不做，且把目标定为质量最优。为拿到动画帧率、系统级模糊、锁屏与蓝牙媒体键集成的上限，选择原生 Kotlin + Jetpack Compose + Media3，放弃 Electron/Tauri/Flutter/RN 一套代码多端的思路。

## 备选方案

- **Tauri v2（Web 前端 + Rust）**：音源本身就是 JS、生态组件最多，但 Android 端是系统 WebView 渲染，歌词高频动画与毛玻璃在中低端机易掉帧，与"丝滑"的硬要求冲突。
- **React Native**：与官方洛雪移动端的 UI 层同构，但动画需绕 Reanimated/Skia，质感上限低于原生。
- **Flutter**：跨端最省事，但音源引擎、后台播放、锁屏控制全依赖插件，插件出问题时无法在 Dart 层深入修复。

## 后果

Windows 端要等 Compose Multiplatform 或另起方案；UI 代码无法跨端复用。作为补偿，数据层、音源宿主与播放领域模型按平台无关方式划分，为后续桌面端留出复用面。
