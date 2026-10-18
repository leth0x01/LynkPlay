# 领克 06 原厂按键输入契约（XSFInputService）

调查日期：2026-10-17。目标车为 BX11 / IHU514G / xecarx / Android 9。本文记录原厂按键输入服务的分发链路，并与 2026-10-16 实车日志交叉核对。

## 组件概览

| 项 | 值 |
| --- | --- |
| 包名 | `ecarx.xsf.inputservice` |
| 版本 | `SY-NEU3.4-20221229-BX11-Volume0Up1` |
| 核心 Service | `ecarx.xsf.inputservice.InputService` |
| 按键策略 | `ecarx.xsf.inputservice.key.KeyPolicyImpl` |

## 输入链路

```text
车辆 HAL 按键
  → android.car.input.service.DefaultInputService.onKeyEvent()
  → InputManager.reportKeyToAdaptApi()
  → ECARX AdaptAPI Input
  → XSFInputService.requestKeysInterception()
  → IKeyCallback → KeyPolicyImpl.onKeyEvent()
  → 对应动作类 onShortClick() / onLongClick()
  → 标准 KeyEvent 或原车广播
```

`InputService.onCreate()` 按车型前缀选择 `e02`、`kx11` 或 `ex11` 键码表；BX11 使用 `e02_key_action`。

## 媒体键：标准 KeyEvent → XSFMediaCenter

`KeyPolicyImpl` 的媒体动作注入标准 Android 媒体事件：

| 物理动作 | 注入键码 | 后续行为 |
| --- | --- | --- |
| 下一曲 | 87 `KEYCODE_MEDIA_NEXT` | XSFMediaCenter → `IMusicClient.onNext` |
| 上一曲 | 88 `KEYCODE_MEDIA_PREVIOUS` | XSFMediaCenter → `IMusicClient.onPrevious` |
| 播放/暂停 | 85 `KEYCODE_MEDIA_PLAY_PAUSE` | XSFMediaCenter 按当前状态选择 onPlay/onPause |

完整链路：

```text
方控媒体键
  → XSFInputService 注入标准媒体 KeyEvent
  → XSFMediaCenter.MediaKeyReceiver
  → MediaCtrlByKeyCallback
  → LynkPlay IMusicClient
  → CarPlay 媒体按钮
```

2026-10-16 实车分别记录下一曲、上一曲和暂停各一次原车媒体中心回调，均为 `control=true`、`handled=true`，用户确认动作成功。

通话状态下，播放/暂停动作不再注入媒体键，而是发送 `ECARX_KEY_BLEPLAYPAUSE_EVENT`，由 XCBTPhone3 在来电状态执行铃声静音。

## 语音键：有序广播

短按语音动作发送：

```text
ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT
```

并携带事件类型 200231。LynkPlay 的 `Lynk06VoiceKeyReceiver` 仅在 CarPlay 活跃且未通话时请求 Siri；处理成功后终止有序广播，否则继续交给原车。

2026-10-16 实车记录 `Lynk06VoiceKey: 动作=0；处理=true`，用户确认 Siri 出现。长按在该轮被跳过，是否会先被 HiCar 或原车接收者处理仍待实车确认。

## 电话键：原车广播 → XCBTPhone3

方向盘电话键应用码为 200005。原车动作发送：

```text
ecarx.intent.action.ECARX_KEY_RCALL_EVENT
```

XCBTPhone3 根据通话状态处理：

| 状态 | 短按 | 长按 |
| --- | --- | --- |
| 空闲 | 打开电话应用 | 拨最近号码 |
| 来电 | 接听 | 拒接 |
| 拨出或通话中 | 挂断 | 挂断 |

2026-10-16 用户人工确认接听与挂断成功。采集日志同时记录 Android `HfpClientConnection` 从 INCOMING → ACTIVE → 空闲，说明电话会话走车机 HFP Client；LynkPlay 不接管电话键。

## 关键广播

| 字符串 | 用途 |
| --- | --- |
| `ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT` | 方向盘语音助手 |
| `ecarx.intent.action.ECARX_KEY_RCALL_EVENT` | 方向盘电话键 |
| `ecarx.intent.action.ECARX_KEY_BLEPLAYPAUSE_EVENT` | 通话状态下播放/暂停键的电话行为 |
| `ecarx.intent.action.START_CARPLAY_SERVICE` | 原厂 CarPlay 服务启动入口 |
| `ecarx.intent.action.ECARX_KEY_EVENT_RCARPLAY_EVENT` | 原厂 R CarPlay 键事件 |

## 关键应用码

| 应用码 | 含义 |
| ---: | --- |
| 200005 | 方向盘电话键 |
| 200231 | 方向盘语音键 |
| 200085 | 播放/暂停 |
| 200087 | 下一曲 |
| 200088 | 上一曲 |
| 200024 / 200025 / 200164 | 音量加 / 音量减 / 静音 |
| 200003 / 200004 | Home / 返回 |
| 110001 / 110002 / 110003 | 导航 / 电台 / 媒体 |

## LynkPlay 的采用边界

- 不直接注册 AdaptAPI 媒体键，避免与 XSFInputService 竞争或形成重复动作。
- 只从 XSFMediaCenter 接收媒体控制回调。
- 只监听已确认的语音有序广播，不监听电话键。
- 通话期间将前台媒体按键交还原车。
- 音量键始终由系统处理。

## 当前验证状态

| 项目 | 状态 |
| --- | --- |
| 媒体键离线链路 | 已核实 |
| 下一曲 / 上一曲 / 暂停实车结果 | 已确认 |
| 语音短按来源与 Siri 结果 | 已确认 |
| 电话键离线链路 | 已核实 |
| 接听 / 挂断结果 | 已人工确认，HFP 状态与之对应 |
| 语音长按与 HiCar 优先级 | 待复验 |
| App 未运行时语音键能否启动 LynkPlay | 待复验 |

媒体中心后半段协议见[原厂媒体中心契约](LYNK06-MEDIACENTER-CONTRACT-20261017.md)。
