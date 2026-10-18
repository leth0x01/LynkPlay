# 领克 06 方控、麦克风与蓝牙适配

当前状态更新：2026-10-17。目标车为 2023 款领克 06 燃油版，IHU514G / BX11 / xecarx / Android 9。固件专属行为只在 product、device、model、hardware 和 API 28 完整匹配时启用。

## 实车结论

2026-10-16 已确认：

- 下一曲、上一曲和播放/暂停可控制 CarPlay。
- 短按方向盘语音键唤起 Siri。
- 接听和挂断成功，电话状态由 Android HFP Client 记录。
- 通话后下一曲仍可用。
- 原车媒体中心回调是媒体键进入 LynkPlay 的实际路径。

仍待确认：语音键长按、App 未运行时的启动、Siri 实际录音质量、无线模式、通话后所有音频状态恢复及长时间稳定性。

## 按键分工

### 媒体键

原车链路：

```text
方控物理键
  → XSFInputService
  → 标准媒体 KeyEvent
  → XSFMediaCenter
  → LynkPlay IMusicClient
  → CarPlay
```

LynkPlay 分别转发 PLAY、PAUSE、NEXT、PREVIOUS，不把明确播放或暂停重新变成切换指令。只有会话有效、非通话且持有原车媒体控制权时才发送。

自建 AdaptAPI 输入后端已经移除。实车中该后端没有收到媒体键，而原车媒体中心已提供完整回调；并行监听语音码还会形成双触发入口。

### 语音键

XSFInputService 发送有序广播：

```text
ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT
```

- CarPlay 活跃且未通话时，短按请求 Siri；成功后终止后续广播。
- 未连接、连接建立中、通话中或请求失败时继续原车处理。
- 长按尝试打开 LynkPlay 设置主界面；该行为尚未完成实车验收。

2026-10-16 日志记录 `Lynk06VoiceKey: 动作=0；处理=true`，用户确认 Siri 出现。

### 电话键

方向盘电话键由原车 XCBTPhone3 处理：来电时接听/拒接，拨出或通话中挂断。车机运行 Android HFP Client，LynkPlay 不监听电话键，也不接管通话控制。

### 音量键

音量加、音量减和静音继续由车机系统处理，LynkPlay 不拦截。

## 媒体中心与蓝牙音频

- CarPlay 音频模式：使用 XSFMediaCenter 回调和 CarPlay 媒体按钮。
- 原车蓝牙音频模式：媒体控制交给 AVRCP，辅助 MediaSession 不申请媒体键焦点。
- 音频模式在连接配置生成时固定，修改设置后到下一次连接才切换路径。
- 会话建立时记录 A2DP Sink、HFP Client 和 AVRCP Controller 状态，并在 30 秒后再次检查。

2026-10-16 的采集显示车机存在 A2DP Sink、HFP Client 和 AVRCP Controller；有线 CarPlay 画面与声音正常。该结果不等于蓝牙音频模式已完成全部回归。

## Siri 麦克风

录音源按以下顺序尝试：

1. `VOICE_RECOGNITION`
2. `VOICE_COMMUNICATION`
3. 本车标准 `MIC` 末级回退

每次切换前释放失败的 AudioRecord，不修改全车 AudioManager 模式。诊断记录创建、初始化、启动失败阶段和最终使用的录音源，并包含 `peakDbfs`、采集字节与 UDP 发送统计。

早期专项采集只证明 `VOICE_COMMUNICATION` 录音曾启动，不能证明音量、清晰度、回声或 Siri 识别质量。

## 自动化覆盖

现有测试覆盖：

- 语音键短按成功/失败、未连接、连接中、通话中和错误车型放行。
- 长按打开 LynkPlay 的调用路径。
- 前台播放/暂停只发送一次，重复与释放被消费。
- 原车媒体中心四种操作、控制权、来源变化、断开和迟到回调。
- 蓝牙音频不重复控制、不申请媒体键焦点，设置变化等待重连。
- 麦克风创建/初始化/启动失败后的释放和三级回退。
- A2DP Sink / HFP Client Profile 选择和异常诊断。

## 后续实车复核

1. 短按语音键只触发一次 Siri，并确认原车语音不同时出现。
2. 经用户同意测试长按，记录 HiCar、ADB 和 LynkPlay 的先后行为。
3. 分别在 CarPlay 音频和原车蓝牙音频模式下测试前后台切歌、暂停和恢复。
4. 测试 Siri 实际识别、电话接听/挂断及通话后音乐恢复。
5. 导出诊断并核对录音源、`peakDbfs`、采集字节、UDP 发送和蓝牙 Profile 状态。

输入分发细节见[原厂按键输入契约](LYNK06-INPUT-CONTRACT-20261017.md)，媒体协议见[原厂媒体中心契约](LYNK06-MEDIACENTER-CONTRACT-20261017.md)。
