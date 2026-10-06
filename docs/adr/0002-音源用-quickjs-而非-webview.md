# 音源脚本用 QuickJS + 专用线程执行，不用 WebView

音源是第三方 JS 脚本，需要一个常驻的 JS 宿主。曾考虑把系统 WebView（V8）当引擎以省去嵌入成本，但 WebView 的 renderer 进程会被系统在内存压力下回收，`onRenderProcessGone` 之后该 WebView 必须销毁重建——挂在里面的音源常驻状态（未 resolve 的 Promise、init 结果）会全部丢失，而音源恰恰是长驻宿主。因此选择 QuickJS，配合一条专用线程（`ScriptThread`）执行脚本。

这一选择有实测依据：官方洛雪移动端（lyswhut/lx-music-mobile）的 React Native 只负责 UI，音源跑在原生 `userApi/QuickJS.java` + `JavaScriptThread` + `assets/script/user-api-preload.js` 上。

## 后果

- 必须自己实现 `lx.*` 宿主契约（`request` 异步桥接、`crypto`、`buffer`），并移植 preload 作为规范；这是"一份固定契约的移植"，不是 Node 兼容层。
- 每次 native→JS 回调之后必须 drain QuickJS 的 pending job queue，否则脚本里的 Promise 永不 resolve——这是该方案最常见的翻车点。
- 一期把 `zlib`、DES、SHA 等官方移动版未实现的能力补齐，使 Lyra 成为官方契约的超集。
