# 可调上限 evictor + LyraCache.setLimit

**Blocked by**: 无

## 目标
缓存上限成为运行时可读的可变值：改小 → 立即按最久未听淘汰到新上限；改大 → 不动。播放不断流。

## 涉及层
- [ ] 数据层：`AppSettings` 增 `cacheLimitBytes`（`StateFlow<Long>`，默认 128MB）+ `setCacheLimit`（SharedPreferences 持久化）。
- [ ] 逻辑层：新增自定义 `CacheEvictor`（对齐 Media3 1.11.1 契约：`requiresCacheSpanTouches` / `onCacheInitialized` / `onStartFile` + 继承自 `Cache.Listener` 的 `onSpanAdded` / `onSpanRemoved` / `onSpanTouched`；**无 `onCacheReleased`**），上限从可变值读；淘汰策略抽成**不依赖 Media3 的纯类**（JVM 可测），evictor 只做薄适配。`LyraCache` 用它替换 `LeastRecentlyUsedCacheEvictor(MAX_BYTES)`（`LyraCache.kt:33,42`），新增 `setLimit(context, bytes)` → 返回释放字节数。见 ADR-0008。
- [ ] UI 层：无（本工单只做逻辑；控件见 005）。
- [ ] 测试：**JVM 单测**——纯类上的上限读值、LRU 淘汰顺序、调低即淘汰到上限、调高不动；**设备测试**——改上限时正在播放不断流（对照 `LyraCacheTest`）。注：Media3 的 `Cache`/`CacheSpan` 是 Android 类型，项目未开 `unitTests.returnDefaultValues`，故纯逻辑必须落在不依赖 Media3 的类上才测得到。

## 验收标准
- 上限变更即时生效，无需重启。
- 调低到当前占用以下 → 淘汰最久未听的直到不超标，返回释放字节数。
- 调高不删除任何条目。
- 在播期间改上限，播放不中断、无异常。
- `SimpleCache` 不因改上限被销毁重建。
