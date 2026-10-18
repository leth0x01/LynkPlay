# 领克 06 媒体方控交接

初始交接日期：2026-10-14。当前状态更新：2026-10-17。

本文保留早期问题的演进过程，并以当前 LynkPlay 0.2.4 实现和 2026-10-16 实车采集为准。早期独立系统包已退出当前发布流程，车测与正式版本统一使用 `com.leth0x01.lynkplay` 的 `release` 构建。

## 当前结论

- 下一曲、上一曲、播放和暂停均已通过原车 XSFMediaCenter 回调控制 CarPlay。
- 2026-10-16 实车记录下一曲 `4`、上一曲 `5`、暂停 `2`，均为 `control=true`、`handled=true`；暂停后 `playing=false`。
- 原车播放信息已经记录 `appName:LynkPlay`，标题、歌手、专辑、进度和封面 URL 已进入媒体中心链路。
- 2026-10-15 单曲对照确认当前封面显示成功；2026-10-16 综合采集又出现三次无封面，说明尚未达到长期稳定。
- 0.2.4 修正了原车查询多媒体列表时的空返回编码，尚待实车回归。

早期“播放能开始但不能暂停”和“仪表信息来自原车音乐”的结论只适用于 10 月 14 日旧现场，不再代表当前版本。

## 原车链路

```text
方控物理键
  → XSFInputService
  → 标准媒体 KeyEvent
  → XSFMediaCenter.MediaKeyReceiver
  → IMusicClient 回调
  → LynkPlay
  → CarPlay 媒体按钮
```

媒体信息反向链路：

```text
iPhone Now Playing
  → LynkPlay 当前歌曲列表 / 播放信息 / 进度 / 封面
  → XSFMediaCenter
  → DIM / 原车封面服务
  → 仪表音乐区域
```

## 当前实现入口

| 文件 | 作用 |
| --- | --- |
| `common/src/main/java/com/shilapi/xcertplay/Lynk06MediaCenter.kt` | 服务绑定、注册、控制权、发布、进度和重连 |
| `common/src/main/java/com/shilapi/xcertplay/Lynk06MediaCenterProtocol.kt` | Binder transaction、Parcelable 字段和原车回调 |
| `common/src/main/java/com/shilapi/xcertplay/Lynk06MusicArtwork.kt` | 封面保存、回环 HTTP 与清理 |
| `common/src/main/java/com/shilapi/xcertplay/CarPlayMediaKeys.kt` | CarPlay 会话、Now Playing 和 MediaSession |
| `common/src/main/java/com/shilapi/xcertplay/Lynk06MediaKeyPolicy.kt` | 前台标准媒体键策略 |
| `common/src/main/java/com/shilapi/xcertplay/Lynk06MediaSystemLog.kt` | 原车媒体/DIM 日志采集与脱敏 |
| `common/src/test/java/com/shilapi/xcertplay/Lynk06MediaCenterTest.kt` | 协议、控制权、列表和回调回归 |

## 协议摘要

服务 descriptor 为 `ecarx.xsf.mediacenter.IMediaCenterSvc`。LynkPlay 使用：

| transaction | 用途 |
| ---: | --- |
| 19 | 以实际包名注册音乐客户端 |
| 4 | 注销 |
| 5 | 请求媒体控制权 |
| 6 | 更新播放信息 |
| 7 / 8 | 更新来源列表 / 当前来源 |
| 9 | 更新当前歌曲列表 |
| 10 | 更新进度 |
| 30 | 声明标准媒体键与 DIM 能力 |

`IMusicClient` 的 1/2/3/4 分别是播放、暂停、下一曲、上一曲。它们与 CarPlay 媒体按钮数值不是同一套编号，不能直接互换。

来源统一为 6，对应原车 ONLINE 音乐入口。注册、来源列表、当前来源、播放信息和歌曲列表都使用相同来源值。

## 控制权与生命周期

- 注册后声明能力、选择来源并请求控制权。
- 原车控制权回调优先于请求的即时返回，旧请求不能覆盖新回调。
- 只有会话有效、非通话、且当前包持有控制权时才转发媒体操作。
- 来源切换或服务断开会清除控制权并停止进度任务。
- iPhone 再次开始播放时可重新请求控制权。
- 关闭时先同步封锁迟到回调，再在 worker 上注销、释放服务和清理封面。

## 当前歌曲与封面

本车多封面路径从 transaction 9 的播放列表缓存取当前及相邻封面。LynkPlay 只掌握当前歌曲，因此发布单项列表：

- 列表项 UUID 与播放信息一致。
- 歌曲文字、时长或封面变化时更新。
- 进度和播放/暂停变化不重复列表。
- 重新取得控制权或重连后重新发布。
- 不编造相邻歌曲和未知队列。

封面通过本机回环 HTTP 提供。原车曾成功下载、保存并显示当前图片；后续综合采集仍有缺失，因此下一位开发者应优先做同歌曲、同来源、只切地图状态的对照，而不是再次替换整个封面传输方案。

## 进度同步

- iAP2 位置使用单调时间作为基准。
- 播放时外推，暂停时冻结；seek 和相同位置的新上报都会重新校准。
- 持有控制权且播放时每秒只发送进度，不重发歌曲和封面。
- 来源变化、失权、断开和关闭会取消后续任务。

## 诊断重点

出现媒体问题时导出诊断报告，重点关联：

- “领克媒体中心：音乐客户端注册成功”。
- 控制权、来源变化和回调指令。
- 当前歌曲列表 `trackRevision` 与封面短编号。
- 播放信息 `updated`、`playing`、`artwork`。
- 封面 HTTP 请求与 `getArtwork` 空值统计。
- 当时的 `mapRevision`、`appMapReady` 和 MCU 投影摘要。

绑定存在、MediaSession active 或 HTTP 200 都不能单独证明控制权、手机动作或物理封面显示。

## 当前待办

1. 在 0.2.4 上从连接前采集注册和首次发布。
2. 回归 `getMultiMediaList` 修正后的原车查询行为。
3. 连续测试播放、暂停、上一曲、下一曲和快速切歌。
4. 只切换仪表地图开关，比较同一首歌的封面。
5. 测试前后台、来源切换、通话后恢复和断开重连。
6. 单独观察进度条与 seek，不以进度日志替代物理显示。

详细字段见[原厂媒体中心契约](LYNK06-MEDIACENTER-CONTRACT-20261017.md)，诊断方法见[原车仪表媒体诊断记录](LYNK06-MEDIA-DIAGNOSTICS-20261015.md)。
