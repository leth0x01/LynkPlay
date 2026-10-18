# 领克 06 原厂媒体中心契约（XSFMediaCenter / Extraservice）

> 调查日期：2026-10-17。记录领克 06（BX11 / IHU514G）原厂车机与 CarPlay 接收端（LynkPlay）之间的
> 媒体交互契约。结论区分「已证实 / 推断 / 未知」，证据为反编译源码、LynkPlay 仓库代码和实车日志对照。
>
> 当前状态：LynkPlay 0.2.4 已接入播放、暂停、切歌、播放信息、进度、当前歌曲列表和封面；
> 2026-10-16 实车确认媒体控制可用。0.2.4 同时修正 `getMultiMediaList` 的空 Parcelable 编码，尚待实车回归。

## 1. 逆向对象与材料

| 项 | 值 |
| --- | --- |
| 媒体中心 | `ecarx.xsf.mediacenter`（XSFMediaCenter.apk），v3.4.3（1E882A0）/ versionCode 32015008，反编译 4964 类 |
| 封面服务 | `com.neusoft.extraservice`（Extraservice.apk），反编译 482 类 |
| 转换链 | vdexExtractor → Cdex2Dex → jadx（CompactDex 转标准 dex） |
| 反编译产物 | `.private/lynk06_cluster_20261012_154433/components/ecarx.xsf.mediacenter/decompiled/`、`.private/lynkplay-cluster-evidence-20261013/03-device-binaries/Extraservice-decompiled/` |

两应用均为 odex 化系统应用，APK 内无 `classes.dex`，代码取自 `oat/arm64/*.vdex`。

## 2. 三个 Binder 契约（已证实）

原厂媒体中心暴露一个服务接口，客户端（LynkPlay）实现两个回调接口。transaction 编号来自反编译的
`Stub` 常量，与 LynkPlay `Lynk06MediaCenterService` / `Lynk06MusicClient` / `Lynk06MusicPlayback`
的编码逐条一致。

### 2.1 IMediaCenterSvc（原厂服务，LynkPlay 调用）

descriptor：`ecarx.xsf.mediacenter.IMediaCenterSvc`

| code | 方法 | 说明 |
| --- | --- | --- |
| 1 | `registerMusic` | 以空包名注册音乐客户端 |
| 2 | `registerNews` | 注册新闻客户端 |
| 3 | `registerVideo` | 注册视频客户端 |
| 4 | `unregister` | 注销 |
| 5 | `requestPlay` | 请求媒体控制权（LynkPlay 用 `requestControl`） |
| 6 | `updateMusicPlaybackState` | 更新播放状态（LynkPlay 用 `updatePlayback`） |
| 7 | `updateMediaSourceTypeList` | 更新来源类型列表（LynkPlay 用 `updateSources`） |
| 8 | `updateCurrentSourceType` | 更新当前来源（LynkPlay 用 `selectMusicSource`） |
| 9 | `updatePlaylist` | 更新播放列表（LynkPlay 用 `updateCurrentTrack`） |
| 10 | `updateCurrentProgress` | 更新进度（LynkPlay 用 `updateProgress`） |
| 11 | `updateVideoPlaybackState` | 更新视频播放状态 |
| 12 | `updateNewsPlaybackState` | 更新新闻播放状态 |
| 13 | `updateCurrentRecommendInfo` | 更新推荐信息 |
| 14 | `updateCurrentLyric` | 更新当前歌词 |
| 15/16 | `declareSupportCollectTypes` / `cancelSupportCollectTypes` | 收藏能力声明/撤销 |
| 17/18 | `declareSupportDownloadTypes` / `cancelSupportDownloadTypes` | 下载能力声明/撤销 |
| 19 | `registerInMusic` | 以真实包名注册音乐客户端（LynkPlay 用 `register`） |
| 20 | `registerInNews` | 以真实包名注册新闻客户端 |
| 21 | `registerInVideo` | 以真实包名注册视频客户端 |
| 22 | `declareVrCtrlPriority` | 语音控制优先级声明 |
| 23/24 | `declareMusicCtrlCapability` / `cancelMusicCtrlCapabilityDeclaration` | 音乐控制能力声明/撤销 |
| 25/26 | `declareRadioCtrlCapability` / `cancelRadioCtrlCapabilityDeclaration` | 电台控制能力声明/撤销 |
| 27/28 | `declareNewsCtrlCapability` / `cancelNewsCtrlCapabilityDeclaration` | 新闻控制能力声明/撤销 |
| 29 | `updateMediaList` | 更新媒体列表 |
| 30 | `declareMediaCenterCapability` | 媒体中心能力声明（LynkPlay 用 `declareWheelControl`，声明 `[0,2]`＝标准媒体键 + 仪表 DIM 控制） |
| 31 | `getStateBinder` | 获取状态 binder |
| 32 | `getMediaControlClientApi` | 获取媒体控制客户端 API |
| 33 | `getMediaControllerApi` | 获取 MediaController API |
| 34/35 | `declareVrChannelCapability` / `cancelVrChannelCapability` | 语音通道能力声明/撤销 |
| 36 | `asyncSendVrChannelResult` | 异步回传语音通道结果 |
| 37 | `updateErrorMsg` | 更新错误信息 |
| 38 | `updateMediaContent` | 更新媒体内容 |
| 39 | `updateMultiMediaList` | 更新多媒体列表 |
| 40 | `updateMediaPlayList` | 更新媒体播放列表 |
| 41 | `updateCollectMsg` | 更新收藏状态 |
| 42 | `queryCurrentFocusClient` | 查询当前焦点客户端 |
| 43 | `updateMediaContentTypeList` | 更新媒体内容类型列表 |
| 44 | `updateCollectMsgByUUID` | 按 UUID 更新收藏状态 |

### 2.2 IMusicClient（客户端实现，原车回调）

descriptor：`ecarx.xsf.mediacenter.IMusicClient`

| code | 方法 | LynkPlay 处理 |
| --- | --- | --- |
| 1 | `onPlay` | 转 `CarPlayMediaButton.PLAY` |
| 2 | `onPause` | 转 `CarPlayMediaButton.PAUSE` |
| 3 | `onNext` | 转 `CarPlayMediaButton.NEXT` |
| 4 | `onPrevious` | 转 `CarPlayMediaButton.PREVIOUS` |
| 5 | `onForward` | 快进 |
| 6 | `onRewind` | 快退 |
| 7 | `onLoopModeChange` | 循环模式切换 |
| 8 | `onSourceSelected` | 来源选择（`source==6` 时 LynkPlay 转发 `PLAY`） |
| 9 | `onMediaSelected` | 选中媒体 |
| 10 | `getMusicPlaybackInfo` | 返回 `IMusicPlaybackInfo` binder（原车拉取播放信息） |
| 11 | `getMediaSourceTypeList` | 返回来源列表 `[6]` |
| 12 | `getCurrentSourceType` | 返回 `6` |
| 13 | `getCurrentProgress` | 返回当前进度 |
| 14 | `getPlaylist` | 返回播放列表（LynkPlay 返回空） |
| 15 | `onCollect` | 收藏 |
| 16 | `onDownload` | 下载 |
| 17 | `onSourceChanged` | 来源变更通知（非播放请求，LynkPlay 仅记录） |
| 18 | `onReplay` | 重播 |
| 19 | `onPlayRecommend` | 播放推荐 |
| 20 | `onCancelRecommend` | 取消推荐 |
| 21 | `onMediaSelectedPlay` | 选中播放（`source==6` 且 UUID 匹配时 LynkPlay 转发 `PLAY`） |
| 22 | `onMediaForward` | 媒体快进 |
| 23 | `onMediaRewind` | 媒体快退 |
| 24 | `onMediaQualityChange` | 音质切换 |
| 25 | `onMediaCenterFocusChanged` | 焦点变更（LynkPlay 记录包名变化） |
| 26 | `onExit` | 退出 |
| 27 | `selectListMediaPlay` | 从列表选择播放 |
| 28 | `getContentList` | 返回内容列表（LynkPlay 返回空） |
| 29 | `getMultiMediaList` | 返回多媒体列表；LynkPlay 返回 null Parcelable，以 presence 标记 0 编码 |
| 30 | `ctrlPlayMediaList` | 播放列表控制 |
| 31 | `ctrlPauseMediaList` | 暂停列表控制 |
| 32 | `ctrlCollect` | 收藏控制 |
| 33 | `operationType` | 操作类型 |
| 34 | `ctrlCollectByUUID` | 按 UUID 收藏控制 |

### 2.3 IMusicPlaybackInfo（客户端实现，原车拉取播放信息）

descriptor：`ecarx.xsf.mediacenter.IMusicPlaybackInfo`

| code | 方法 | LynkPlay 返回值 |
| --- | --- | --- |
| 1 | `getLaunchIntent` | 启动 PendingIntent（可空） |
| 2 | `getTitle` | 曲名 |
| 3 | `getArtist` | 艺术家 |
| 4 | `getAlbum` | 专辑 |
| 5 | `getRadioFrequency` | 空 |
| 6 | `getRadioStationName` | 空 |
| 7 | `getDuration` | 时长（毫秒） |
| 8 | `getPlayingItemPositionInQueue` | 队列位置 |
| 9 | `getSourceType` | `6` |
| 10 | `getMediaPath` | 媒体路径 |
| 11 | `getPlaybackStatus` | 播放中 `1` / 暂停 `0` |
| 12 | `getLyricContent` | 空 |
| 13 | `getLyric` | 歌词 Uri |
| 14 | `getCurrentLyricSentence` | 当前歌词句 |
| 15 | `getPreviousArtwork` | 上一曲封面 Uri |
| 16 | `getArtwork` | **当前封面 Uri**（见 §5 null 封面根源） |
| 17 | `getNextArtwork` | 下一曲封面 Uri |
| 18 | `getLoopMode` | 循环模式 |
| 19 | `getRadioMode` | 电台模式 |
| 20 | `isSupportCollect` | 是否支持收藏 |
| 21 | `isCollected` | 是否已收藏 |
| 22 | `isSupportDownload` | 是否支持下载 |
| 23 | `isDownloaded` | 是否已下载 |
| 24 | `getUuid` | 曲目 UUID（LynkPlay 用 `title|artist`） |
| 25 | `getAppName` | `LynkPlay` |
| 26 | `getAppIcon` | 应用图标 |
| 27 | `getPackageName` | 包名 |
| 28 | `isSupportLoopModeSwitch` | 是否支持循环切换 |
| 29 | `isSupportVrCtrlPlayStatus` | 是否支持语音控制 |
| 30 | `getPlayingMediaListId` | 播放列表 ID |
| 31 | `getVip` | VIP 标识 |
| 32 | `getPlayingMediaListType` | 播放列表类型 |
| 33 | `getPlayerIntent` | 播放器 PendingIntent |

## 3. 媒体控制链路（原车 → CarPlay，已证实）

```
方控物理键
  → android.car.input.service
  → ECARX AdaptAPI Input
  → XSFInputService KeyPolicy
  → 标准媒体 KeyEvent（85 / 87 / 88）
  → XSFMediaCenter.MediaKeyReceiver
  → EventBus → MediaCtrlByKeyCallback
  → IMusicClient.onPlay/onPause/onNext/onPrevious（binder）
  → LynkPlay（Lynk06MusicClient.onTransact）
  → CarPlay HID
```

10 月 17 日对 XSFInputService 的复核补齐了媒体键进入 XSFMediaCenter 之前的路径；LynkPlay 不直接注册 AdaptAPI 媒体键。

### 3.1 键码 → 原厂内部动作（`MediaKeyReceiver`）

| 键码 | 事件 |
| --- | --- |
| 85 `KEYCODE_MEDIA_PLAY_PAUSE` | `EventMediaPlayOrPauseKey`（按播放状态再分播放/暂停） |
| 86 `KEYCODE_MEDIA_STOP` | `EventMediaButtonAction(3)` |
| 87 `KEYCODE_MEDIA_NEXT` | `EventMediaButtonAction(6)` |
| 88 `KEYCODE_MEDIA_PREVIOUS` | `EventMediaButtonAction(5)` |
| 89 `KEYCODE_MEDIA_REWIND` | `EventMediaButtonAction(4)` |
| 90 `KEYCODE_MEDIA_FAST_FORWARD` | `EventMediaButtonAction(7)` |
| 126 | `EventMediaButtonAction(1)` |
| 127 | `EventMediaButtonAction(2)` |

### 3.2 原厂内部动作 → 控制方法（`MediaCtrlByKeyCallback`）

| action | 控制方法 | IMusicClient code |
| --- | --- | --- |
| 1 | `playCtrlPlay` | 1 `onPlay` |
| 2 | `playCtrlPause` | 2 `onPause` |
| 3 | `playCtrlStop` | — |
| 4 | `playCtrlNoQuickRewind` | — |
| 5 | `playCtrlPrevious` | 4 `onPrevious` |
| 6 | `playCtrlNext` | 3 `onNext` |
| 7 | `playCtrlNoQuickFastForward` | — |

### 3.3 实车「回调指令」语义

LynkPlay 把 `IMusicClient` 回调转成 `CarPlayMediaButton` 常量后记录「回调指令=index」：

| `CarPlayMediaButton` | 值 | 实车日志 |
| --- | --- | --- |
| `PLAY` | 1 | — |
| `PAUSE` | 2 | M3 暂停 → `回调指令=2` |
| `PLAY_PAUSE` | 3 | — |
| `NEXT` | 4 | M1 下一曲 → `回调指令=4` |
| `PREVIOUS` | 5 | M2 上一曲 → `回调指令=5` |

## 4. 播放信息 / 封面链路（CarPlay → 原车仪表）

封面有两条独立路径，均已定位到 XSFMediaCenter 侧：

**DIM 路径（已证实）**

```
iPhone 封面 → LynkPlay HTTP 发布
  → 原车拉 IMusicClient.getMusicPlaybackInfo() 得 IMusicPlaybackInfo
  → 原车调 getArtwork()（code 16）取封面 Uri
  → XSFMediaCenter.MusicPlaybackInfoHolder.setMusicInfo() 缓存
  → XSFMediaCenter.DimInteractionHelper.updatePlaybackInfo()
       → updateDimPlaybackInfo：封面已缓存则直接用 file://，否则 loadCoverFromNet
       → loadCoverFromNet：sourceType∈{6,35,8,0,9} 或 http 时
           DimImageProcess.doSaveMusicCover 下载封面到 DimImageLoader.SD_PATH
  → iMediaInteraction.updatePlaybackInfo(dimPlaybackInfo) 发 DIM 给仪表
```

**广播路径（发送者未定位，见 §6）**

```
？ → com.neusoft.audio.AlbumShowBroadcast（携带 albumPath/isShow/type）
  → Extraservice.AlbumService / ThreeAlbumService
  → distributeImg：type∈{6,8,9} 时 DownloadAlbumAST 下载 URL，type∈{0,1,7} 时读本地图片
  → AlbumPresentation 投仪表
```

## 5. 「null」封面异常根源（已证实）

`MusicPlaybackInfoHolder.setMusicInfo()`（TAG `MusicPlaybackInfoHelper`）中：

```java
this.mArtwork = String.valueOf(this.musicPlaybackInfo.getArtwork());
```

当 `getArtwork()` 返回 `null` 时，`String.valueOf(null)` 得到字符串 `"null"`。该字符串随封面广播传给
`ThreeAlbumService.DownloadAlbumAST`：

```java
InputStream inputStreamOpenStream = new URL((String) objArr[0]).openStream();
```

`new URL("null")` 抛 `MalformedURLException`。这与既有实车结论一致：LynkPlay 侧「封面未就绪即发空」
（宁可空着也不挂上一首封面）是既有设计；原厂 `ThreeAlbumService` 未对 null 做空判。两侧代码均已拿到。

同一隐患在 DIM 路径 `DimInteractionHelper.loadCoverFromNet()` 再次出现：
`String.valueOf(dimPlaybackInfo.getArtwork())`，artwork 为 null 时同样得到 `"null"` 并尝试下载。

LynkPlay 当前会在封面尚未准备好时返回空，避免继续挂着上一首图片；准备完成后发布当前歌曲列表和播放信息。2026-10-15 单曲对照中原车成功读取并显示当前封面，但 2026-10-16 综合采集的三次媒体操作仍未看到封面，因此该异常不能视为已经稳定解决。

## 6. 未定位的一环（未知）

`com.neusoft.audio.AlbumShowBroadcast`（`Extraservice.IPKUtil.Action.audioAlbumShowAction`）是封面从
中控媒体中心传到仪表 `AlbumService`/`ThreeAlbumService` 的中转广播，携带 `albumPath` / `prePath` /
`nextPath` / `isShow` / `type`。现有采集尚未定位最终发送者。

2026-10-15 的对照日志确认：调用 `updatePlaylist` 后，广播中的 `albumPath` 已替换为当前 CarPlay
回环封面，用户确认显示正确。2026-10-16 的后续采集又出现三次无封面；其中应用发布和 HTTP 读取成功，
但地图开启时段原车 `MusicPlaybackInfoHelper` 与 `ThreeAlbumService` 缺少对应输出。

因此当前待确认的是原车后续广播与渲染在不同场景下为何不稳定，而不是再次证明 LynkPlay 能否生成图片。
后续应在同一首歌、同一来源下只切换地图状态，并从连接前开始采集注册、列表、播放信息、广播和物理显示。

## 7. 关键常量

| 常量 | 值 | 含义 |
| --- | --- | --- |
| `Lynk06MediaCenterService.SOURCE_MUSIC` | 6 | `PlaybackState.SOURCE_TYPE_ONLINE`；DIM 把来源 6 编码为仪表「音乐」 |
| `IMusicClient` descriptor | `ecarx.xsf.mediacenter.IMusicClient` | 客户端回调接口 |
| `IMusicPlaybackInfo` descriptor | `ecarx.xsf.mediacenter.IMusicPlaybackInfo` | 播放信息接口 |
| `IMediaCenterSvc` descriptor | `ecarx.xsf.mediacenter.IMediaCenterSvc` | 原厂媒体中心服务 |
| `declareWheelControl` 参数 | `int[]{0, 2}` | 0＝标准媒体键，2＝仪表 DIM 控制 |

## 8. 0.2.4 协议修正与验证边界

`IMusicClient.getMultiMediaList` 返回的是可空 Parcelable `IMediaLists`，不是 binder。空值必须先写
presence 标记 0。旧实现误用 `writeStrongBinder(null)`，原车会把 flat-binder 魔数按 presence 整数读取，
随后尝试从错误位置创建 Parcelable。0.2.4 已改为 `reply.writeInt(0)`，并增加 Binder 回归测试锁定格式。

当前已实车确认播放、暂停、上一曲和下一曲；当前歌曲封面只在单次对照中确认成功，后续综合采集仍有缺失；
进度数据已进入原车媒体链路，但物理进度条尚未单独验收。
