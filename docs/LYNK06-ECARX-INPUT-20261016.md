# 领克 06 ECARX AdaptAPI 输入调查

调查日期：2026-10-16；契约复核更新：2026-10-17。

**结论：LynkPlay 不直接注册 AdaptAPI 输入拦截，相关试验代码已移除。** 目标车为 2023 款领克 06 燃油版，BX11 / IHU514G / xecarx / Android 9。

10 月 17 日对 XSFInputService 的离线复核补齐了完整链路：原车输入服务本身通过 AdaptAPI 接收车辆按键，再把媒体键注入为标准 Android KeyEvent，交给 XSFMediaCenter。LynkPlay 应接原车媒体中心回调，而不是与系统输入服务竞争同一批按键。

## 试验背景

曾实现 `EcarxAdaptApiInputBackend`，反射调用：

```text
com.ecarx.xui.adaptapi.input.Input.requestKeysInterception
```

依据来自另一套 SX11-A3 / IHU715P 原厂 CarPlay。实车测试确认该方案不适合作为 BX11 上 LynkPlay 的输入后端，生产代码已恢复到引入前状态。

## 离线复核对象

- `/system/framework/AdapterAPI.jar`
- `/system/framework/arm64/boot-AdapterAPI.vdex`
- `/system/framework/arm64/boot-AdapterAPIImpl.vdex`
- `/system/priv-app/XSFInputService/XSFInputService.apk`

CompactDex 转换后使用 JADX 核对 `InputImpl`、`KeyMapAdapterApi` 和 XSFInputService 的键码资源与动作类。未完整还原的方法不作为控制流结论。

## `InputImpl` 的实际语义

```java
ArrayMap<IKeyCallback, int[]> mCallbacks = new ArrayMap<>();

public int[] requestKeysInterception(int[] keys, IKeyCallback callback) {
    mCallbacks.put(callback, keys);
    int[] finalKeys = filterKeys(keys); // 本车实现原样返回
    mIm.requestKeysInterception(finalKeys, mKeyEventObserver);
    return finalKeys;
}
```

回调前，系统会先通过车机键码映射表将车辆原始码转换为 ECARX 应用码，再与每个回调注册的数组比较。

需要保留的实现结论：

1. 注册参数是 ECARX 应用码，不是车辆驱动原始码。
2. 本车 `filterKeys` 原样返回请求数组，因此返回数组不表示这些按键最终都会交给当前应用。
3. 回调对象会作为 `ArrayMap` 的键；反射 Proxy 必须保证 `equals` 自反。试验版曾因 `equals` 恒为 false，使 `mCallbacks.get(proxy)` 返回 null，并在 `isValidKey` 中触发空指针。

## 原始码与应用码

| 原始码 | 应用码 | 含义 |
| --- | --- | --- |
| 298 | 200231 | 方向盘语音助手 |
| 299 | 200005 | 方向盘电话 |
| 300 / 301 / 302 | 200164 / 200024 / 200025 | 静音 / 音量加 / 音量减 |
| 303 | 210004 | 来源切换 |
| 304 / 305 | 200088 / 200087 | 上一曲 / 下一曲 |

XSFInputService 在 BX11 上选择 `e02_key_action` 资源表，并注册语音、电话、媒体、音量等应用码。原车系统服务注册这些码，不代表第三方并行注册会收到同样事件。

## 实车观察

LynkPlay 试验后端所在进程的 `InputImpl` observer 日志只看到：

| 原始码 | 应用码 | 观察次数 |
| --- | --- | ---: |
| 298 | 200231 语音键 | 8 |
| 302 | 200025 音量减 | 56 |

同一轮操作中的媒体键没有进入该试验回调，但原车媒体中心明确向 LynkPlay 发出：

- 下一曲：`回调指令=4`
- 上一曲：`回调指令=5`
- 暂停：`回调指令=2`
- 播放：`回调指令=1`

所有已确认操作均要求 `control=true`、`handled=true`。

## 与原车输入契约的交叉结论

XSFInputService 的链路为：

```text
车辆按键
  → android.car.input.service
  → ECARX AdaptAPI Input
  → XSFInputService KeyPolicy
  → 媒体键注入标准 KeyEvent
  → XSFMediaCenter
  → LynkPlay IMusicClient
  → CarPlay
```

语音键则由 XSFInputService 发送有序广播 `ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT`。LynkPlay 已通过 `Lynk06VoiceKeyReceiver` 使用该路径；再注册 AdaptAPI 语音码会形成重复入口。

电话键由 XSFInputService 发送原车电话广播，XCBTPhone3 根据 HFP 通话状态执行接听、拒接或挂断，LynkPlay 不接管。

## 最终决策

- **媒体键**：原车输入服务 → XSFMediaCenter → `Lynk06MediaCenter` → CarPlay。
- **语音键**：ECARX 有序广播 → `Lynk06VoiceKeyReceiver` → Siri。
- **电话键**：原车 XCBTPhone3 / Android HFP Client 处理。
- **音量键**：继续由车机系统处理。

自建 AdaptAPI 输入后端不会重新加入，除非未来固件采集证明存在当前两条路径无法覆盖、且不会产生重复动作的新按键。

完整按键分发见[原厂按键输入契约](LYNK06-INPUT-CONTRACT-20261017.md)，实车结果见[验证记录](LYNKPLAY-VALIDATION.md)。
