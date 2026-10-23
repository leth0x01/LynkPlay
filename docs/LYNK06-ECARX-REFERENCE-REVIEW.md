# DiPlay-ECARX 参考实现与 E02 契约对照

复核日期：2026-10-20。目标车：2023 款领克 06 燃油版，BX11 / IHU514G / xecarx / Android 9。

参考仓库：[jiopia/DiPlay-ECARX](https://github.com/jiopia/DiPlay-ECARX)，固定版本 [`7f78343642fe695bb56906c3d8761c1c68bae7ce`](https://github.com/jiopia/DiPlay-ECARX/tree/7f78343642fe695bb56906c3d8761c1c68bae7ce)。其 README 的主目标是 2020 款领克 05，DCY11 / 高通 820A；不能按“同为亿咖通”直接替代 E02 的实车契约。

## 采用结论

| 参考实现 | E02 对照结果 | 决策 |
| --- | --- | --- |
| `EcarxMicPolicy` 麦克风仲裁 | E02 SDK 确有申请、释放和回调，但初始化与能力判断不同 | 优先调查应用内服务连接及仲裁效果，不直接复制实现 |
| `EcarxSwKeyIntercept` 直接注册 AdaptAPI 按键 | BX11 已确认由 XSFInputService → XSFMediaCenter 分发媒体键 | 保留现有媒体中心与语音广播路径 |
| `EcarxDimApi` 不调用 `notifyNavigationStatus` | BX11 已核实存在该方法，原车使用 1 / 0 声明开始与结束 | 保留 E02 导航状态发布 |
| DIM ETA 使用到达时刻的 epoch 秒 | BX11 当前契约使用剩余秒数 | 保留 `Lynk06NavigationInfo.remainingSeconds` |
| `CarBluetoothProfileGuard` 禁用 A2DP / Headset 连接策略 | E02 是 A2DP Sink / HFP Client，原车接听与挂断走 HFP Client | 不移植该让位策略 |
| `hicar_enable`、`AudioControlMode`、`ASR_AEC` 参数 | 参考代码针对 820A；本轮 E02 HiCar VDEX 字符串扫描只命中 `hicar_enable` | 不能据此套用整组参数或判断开关值 |
| 根据 `test-keys` 推断默认平台签名可用 | 构建标签不能替代证书比对 | 继续使用本项目实际证书核验流程 |

## 麦克风：已还原的 E02 SDK 契约

### 证据来源

使用 2026-10-19 实车采集的 `boot-ecarx.gkui.openapi.impl.vdex`。经 vdexExtractor 提取 CompactDex，再通过 dexlib2 的 `CDexBackedDexFile` 重写为标准 DEX，用 JADX 复核下列类。不是仅改 DEX 文件头，也不把其他车型的反编译结果当成本车实现。

| 文件 | SHA-256 |
| --- | --- |
| 实车 `boot-ecarx.gkui.openapi.impl.vdex` | `447797bd6391d4168a64e342a4ec1c69747332ee100022e2c6bed8a7c3590aea` |
| 转换后的 `openapi.dex` | `1ffe910f25da59969b9c2681575fff80eb90bf29d9321ca8563941b937b58d04` |

本地分析产物保存在 `.private/ecarx-reference-review/`，原始固件及反编译源码不进入公开仓库。

### 类与方法

```text
com.ecarx.sdk.policy.PolicyAPI
  get(Context)
  createPolicyAPI(Context)
  init(Context, com.ecarx.sdk.openapi.ECarXApiClient.Callback)
  getAudioPolicy()
  release()

com.ecarx.sdk.policy.IAudioPolicy
  getAudioStreamTypeForFunction(String): int
  requestMic(int, IMicResourceCallback): boolean
  releaseMic(IMicResourceCallback): boolean

com.ecarx.sdk.policy.IMicResourceCallback
  onAbort(int)
  onResume()

REQUEST_TYPE_SYSTEM = 1
REQUEST_TYPE_CALL = 2
REQUEST_TYPE_RECORD = 3
```

E02 的 `IAudioPolicy` 与 `RemoteAudioPolicyImpl` 没有 `isNeedrequestMic()`。

### 必须先连接服务

`PolicyAPI.get(context)` 只取得或构造 SDK 对象；`PolicyAPIImpl` 构造函数只保存 application context。

实际连接路径为：

```text
PolicyAPI.init(context, callback)
  → ECarXApiClientImpl.init(..., SERVICE_POLICY="policy", ...)
  → com.ecarx.sdk.openapi / com.ecarx.sdk.openapi.ServicePoolService
  → 服务连接回调
  → PolicyAPIImpl.initPolicy()
  → IServiceManager.getService(context, "policy")
  → IPolicy
  → getAudioPolicy()
```

`getAudioPolicy()` 即使没有连接服务，也会创建非空的 `RemoteAudioPolicyImpl`。此时其 `mRemoteAudioPolicy` 为 null，`requestMic()` 直接返回 false。

此外，音频 wrapper 被缓存；应在服务连接成功后取得，并在重新连接后重新取得。不能将“非空对象”“反射未抛异常”记为服务已连接或麦克风已获准。

### 回调与释放

SDK 用 `HashMap<IMicResourceCallback, RemoteCallback>` 保留申请成功的回调，释放时用同一个回调查找远端 wrapper。因此反射 Proxy 的 `equals` 必须自反，`hashCode` 必须稳定，申请与释放不能换回调实例。

`requestMic()` 返回 true 后，SDK 保存该回调；`releaseMic()` 返回 true 后才移除。返回 false 必须作为未完成的操作记录，不能因为调用未抛异常就报告释放成功。应用异常退出后的服务端资源回收行为仍需单独验证。

## 为什么不能直接复制参考实现

固定版本的 `EcarxMicPolicy.kt` 与 `AndroidMediaSink.kt` 存在以下接入差异：

1. `isNeedRequestMic()` 找不到 `isNeedrequestMic()` 时返回 false，调用方随即跳过申请；在上述 E02 契约上会走这个分支。
2. 参考实现取得 `PolicyAPI` 后直接取 `getAudioPolicy()`，没有完成 E02 所需的 `init` 连接过程。
3. 仲裁与 820A 音频参数仅从 `enterCommunicationMode()` 接入，入口条件是 `audioType == "telephony"`；Siri 的 `speechrecognition` 路径不经过这里。
4. `Held.release()` 没有检查释放函数的 boolean 返回值，false 也会生成成功结果。
5. `onResume` 不改变录音状态，`onAbort` 只记录后继续录音；这不能证明资源撤销后仍能取得有效声音。

这些结论来自源码与 E02 SDK 静态复核，不代表本轮执行过麦克风申请或实车录音。

## 下一步接入条件

优先做麦克风专项验证，而不是改动已验证的方控、仪表或原车电话链路：

1. 在 LynkPlay 自身进程验证 `PolicyAPI` 可见性、`init` 回调和 `policy` 服务连接；单独记录超时、断连与权限错误。
2. 服务连接后取得音频接口，验证同一 callback 的申请与释放；分开记录同步返回值、`onResume`、`onAbort` 和实际采集数据。
3. 对照当前标准录音路径测试 Siri；`CALL=2`、`RECORD=3` 的存在并不能决定 Siri 应使用哪一个，需结合原车调用方及驻车实验确认。
4. 同时核对录音源、`peakDbfs`、采集字节、UDP 发送和 Siri 实际识别。申请成功不等于麦克风质量通过。
5. 验证 Siri 结束、拒绝、通话插入、反复唤醒、连接断开后原车语音与电话仍可用，再决定是否接入生产录音路径。

本轮 `adb devices -l` 没有已连接设备，未执行上述服务连接及录音验证。

## 其他不可直接迁移的行为

### 蓝牙让位

参考 `CarBluetoothProfileGuard` 使用 `BluetoothProfile.A2DP` / `HEADSET`，Binder 后备也是 `IBluetoothA2dp` / `IBluetoothHeadset`。它还会将设备连接策略持久设为禁止，恢复时统一改为允许并主动连接；没有逐设备保存原始策略，目标也包括名称含 iPhone 的其他已配对设备。

E02 已确认使用 A2DP Sink（11）/ HFP Client（16）；现有原车电话键和接听/挂断依赖 HFP Client。不能简单替换 Profile ID 后启用整套策略。若未来实车出现蓝牙与 CarPlay 音频争用，应先定位具体 Profile、设备和会话，再评估只对当前手机、可还原原策略的处理。

### 仪表与按键

仪表继续使用本车已验证的 `DashboardMapDisplay`、导航状态、投影门控和剩余秒数；方控继续使用 XSFMediaCenter 与语音有序广播。参考项目“不存在”的接口只描述其目标固件，不能覆盖 BX11 的采集结果。

相关契约：

- [方控、麦克风与蓝牙适配](LYNK06-WHEEL-MICROPHONE-BLUETOOTH.md)
- [原厂按键输入契约](LYNK06-INPUT-CONTRACT-20261017.md)
- [原车仪表联动调研](LYNK06-CLUSTER-INTEGRATION-RESEARCH.md)
- [仪表系统签名构建](LYNK06-CLUSTER-SYSTEM-BUILD-20261013.md)

## 本轮检查

- 文档本地链接与 `git diff --check` 通过。
- 按 `.github/workflows/android.yml` 运行 shared / common / home 单元测试、mobile / home / maphost lint 与 Debug 构建。首轮达到工具 200 秒时限而中止；延长时限后重跑，`BUILD SUCCESSFUL in 3m 45s`，退出码 0。
- 本轮变更为契约和参考对照文档，未改动生产音频行为。
