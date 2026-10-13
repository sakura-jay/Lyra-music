# 缓存上限运行时可调：自定义 evictor，不重建 SimpleCache

M8 要求播放缓存上限由用户调整（预设档位 + 自定义滑块），且调低到当前占用以下时立即淘汰。
但 Media3 的 `LeastRecentlyUsedCacheEvictor` 把上限写在构造参数里（`final`），`SimpleCache`
又约束"一个目录同一时刻只允许一个实例、淘汰器创建后不可替换"。我们决定自己实现一个
`CacheEvictor`，套用同样的 LRU 策略，但上限从一个 volatile 值读取；改上限时立即按新上限
淘汰一次超出部分。**不销毁重建 SimpleCache。**

## 备选方案

- **改上限时重建 SimpleCache**：`release()` 旧实例、用新 evictor 建新实例。实现最短，但
  `release()` 会打断正在播放的读取（播放链还持有媒体文件句柄），在播期间重建要么抛异常，
  要么让播放中断——这与"缓存为 crossfade 平滑交接服务"（ADR-0007）的目标直接冲突。
- **上限只在应用重启后生效**：改动为零，但不满足"调低即腾出空间"的交互预期。
- **引入第三方缓存库**：为一个上限可调引入新依赖，成本远超收益。

## 后果

- 需要自己实现淘汰逻辑（LRU + 命中回调 + 容量统计）。要实现的 Media3 1.11.1 `CacheEvictor`
  契约是：`requiresCacheSpanTouches` / `onCacheInitialized` / `onStartFile`，加上继承自
  `Cache.Listener` 的 `onSpanAdded` / `onSpanRemoved` / `onSpanTouched`（**没有 `onCacheReleased`**）。
- `SimpleCache` 在构造时收一个 `CacheEvictor`，之后不可替换——这是"必须自研 evictor
  而非换一个现成的"的直接原因。
- Media3 的 `Cache` / `CacheSpan` 是 Android 侧类型，项目又没开 `unitTests.returnDefaultValues`，
  所以淘汰策略要抽成一个**不依赖 Media3 的纯类**做 JVM 单测，`CacheEvictor` 只做薄适配；
  真机/模拟器侧再验证"改上限时在播不断流"。
- **修订 ADR-0007 的"最多 128MB"表述**：上限改为用户可调，默认仍为 128MB。
- 上限值经 `AppSettings`（SharedPreferences）持久化，是用户设置的一部分。
