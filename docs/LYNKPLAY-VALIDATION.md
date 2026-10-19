# LynkPlay 验证记录

## 2026-10-19：CarPlay Ultra 协议可达性

复核 iPhone 的 AirPlay `/info` 请求，三份独立采集（2026-10-16 两次、2026-10-19 一次）只声明三项仪表内容：

```
altScreenURLs=[maps:/car/instrumentcluster/map,
               maps:/car/instrumentcluster/instructioncard,
               maps:/car/instrumentcluster]
```

LynkPlay 已实现全部三项。iPhone 未声明车速表、转速表、挡位或警告灯的任何渲染入口，也没有 Ultra 专属字段；这与“地图铺满仪表背景、原车元素继续由车机绘制”的实测结果一致。

`extendedFeatures` 中声明的 `vocoderInfo` 与 `enhancedRequestCarUI` 没有换来额外 UI 请求；iAP2 仅有三个电动车车辆端点，没有仪表布局通道。真 Ultra 需要 Apple 与车厂的双边授权，离线 MFi 实验身份不具备该渲染通道。

结论与可推进方向见[CarPlay Ultra 协议可达性](LYNK06-CARPLAY-ULTRA-REACHABILITY.md)。

## 2026-10-19：车辆只读探针首次实车运行

在 2023 款领克 06（BX11 / IHU514G / xecarx / API 28）驻车状态用 Windows 离线 Runner 运行一次，采集器为 PowerShell 7.6.6，设备 `172.20.10.6:5555` 经 ADB 连接。覆盖安装平台签名包成功，服务启动成功，60 秒后按截止写终态 `completed`（reason=`deadline`）。

**已核实**

| 项 | 结果 |
| --- | --- |
| 六项读取 | 全部成功，各 59 次主动读取（60 秒、1 Hz），0 次错误、0 条 ERROR 状态 |
| 里程 | `0x287000a3` = 430770 → 43077.0 km |
| 续航 | `0x287000a4` = 625 → 625 km |
| 车速 | `0x287000a8` = 0 → 0 km/h（驻车） |
| 挡位 | `0x287000ec` = 5 → P |
| 转速候选 | `0x2870023f` 在 60 秒内出现 33–363 多个原始值，仅记录原始数 |
| 油量候选 | `0x28700207` 原始 92，未按百分比解释 |
| 历史 | 256 条保留 + 98 条丢弃，合计 354 = 6 × 59，计数一致 |
| 报告写入与拉回 | 49 次轮询拉取，最终按本次 request_id 校验后取回终态 |

与 10 月 19 日早先采集的照片对比：里程由 43046 km 增至 43077 km，续航由 673 km 减至 625 km，挡位仍为 P，车速为 0，读数的量纲与变化方向一致。

**本轮暴露的两点事实**

- **属性订阅在此期间没有产生任何回调**：六项 `callbackCount` 均为 0，全部数据来自每秒主动读取。该固件的 `changeMode` 为按变化推送，驻车且数值不变时没有事件，符合预期；不能把“无回调”当成失联。
- **源时间戳不同属性来自不同时钟域**：油量候选在整轮中固定为 `178727990547` ns，其余属性在 899–945 秒区间递增。因此源时间戳只能用于同字段单调性判断，不能跨字段比较，也没有据此推断缓存新鲜度。

报告新增 `subscriptions` 段（每项 `propertyId` / `registered` / `callbacks`），用于区分“注册失败”与“注册成功但无回调”。

随后用含该字段的构建再运行一次（`bb8e6dbd`，60.01 秒），六项均 `registered=true`、`callbacks=0`，里程仍 430770、续航由 625 降至 623、车速 0、挡位 P。因此在该固件的驻车、数值不变的条件下，订阅能注册但不下发事件；本次全部读数来自每秒主动读取。

**动态验证（第三轮 `2f9b0d05`，`-SkipInstall`，60.00 秒）**

乘员原地挪车时采集，六项全部读到，0 错误：

| 阶段 | 挡位 | 车速 | 说明 |
| --- | --- | --- | --- |
| 0–4 s | R | 1.40 → 2.98 → 1.51 → 1.40 km/h | 倒车 |
| 5–13 s | D | 0 → 2.75 → 4.95 → 5.90 → 3.43 km/h | 前进 |
| 14–28 s | R | 0 → 2.75 → 3.48 → 2.30 → 0 km/h | 再次倒车 |
| 29–42 s | P | 0 km/h | 停车 |

- 挡位原始值与速度变化逐段对应：`4`=R、`2`=D、`5`=P，未出现未知值。
- 车速 `×0.01 km/h` 的换算与场景一致，非零值共 19 次。
- 转速候选在 R/D 挡处于 132–990、P 挡怠速为 0–231，随工况变化；仍未确定与仪表 r/min 的换算。
- 油量候选由 92 降到 91；里程 430770（0.1 km 精度，本轮移动不足 0.1 km）；续航 622。
- **订阅在数值持续变化时也没有下发任何事件**：六项 `registered=true`、`callbacks=0`。该固件下 CarPropertyManager 订阅不可依赖，1 Hz 主动读取是唯一有效来源。

**仍未验证**

- CarService 断连恢复，以及与会话 CarPlay 并行时的稳定性。
- 高速、长时间行驶下的转速与油量换算。
- 油量原始值是否为百分比（需加满油或记录已知油量对照）。

本轮仅验证只读读取链路，未写入车辆属性、未接入仪表 UI、未涉及 CarPlay Ultra 协议。
## 2026-10-19：车辆只读验证包（代码完成，待实车）

新增独立的只读车辆信号探针，用于在 LynkPlay 自身进程和权限条件下取证，不接入仪表 UI、车辆控制或 CarPlay 上报。

**实现**

- 六项固定白名单，area 恒为 0：里程 `0x287000a3`、续航 `0x287000a4`、车速 `0x287000a8`、挡位 `0x287000ec`、转速候选 `0x2870023f`、油量候选 `0x28700207`。`0x287000b4` 是燃油类型，不在白名单。
- 反射只读后端：先严格车型与 `android.car.permission.CAR_VENDOR_EXTENSION` 门禁，再枚举配置、读取并订阅。本车厂商属性没有 areaConfigs，OEM 返回空区域列表，按 area 0 放行。
- 60 秒限时会话，进程级单 I/O 槽与租约；3 秒操作看门狗后使当前值失效并写终态。旧调用未返回时拒绝新会话，不新建线程或排队。重连代次隔离，迟到回调不能复活新值。
- 报告写入 `Android/data/com.leth0x01.lynkplay/files/vehicle-probe/<uuid>.json`，AtomicFile、512 KiB 上限、最近 5 份；运行中或待清理的报告不会被拒绝报告淘汰。
- Service `Lynk06VehicleProbeService` 仅 exported + `android.permission.DUMP`，无 intent-filter，`START_NOT_STICKY`，不自动启动。
- Windows 离线 Runner 显式确认后覆盖安装、启动、拉回本次 UUID 的终态 JSON；START 已发出但未确认终态时，退出仍只对本次 UUID 尽力 STOP。

**本机验证**

- shared / common / home 单元测试、三个模块 lint 与 Debug 构建通过；脚本 29 项测试通过，其中 Windows 假 ADB 真实子进程流程 15 项。
- 平台签名车测包重新构建并通过签名、包名、系统 UID 与认证素材校验。

**待实车验证**

- 应用进程内 `android.car` 类可见性、权限授予与后台 Service 启动；
- 六项读数的单位、非零动态值与真实回调频率；
- CarService 断连恢复，以及与会话 CarPlay 并行时的稳定性。

本轮没有实车运行结果，不把上述待验项记为通过；也不涉及 CarPlay Ultra 协议接入。
## 2026-10-19：离线采集、仪表读数对照与应用归属更正

复核用户提交的 `lynk06_ultra_capture_20261019_132155_333.zip` 和两张物理仪表照片。
ZIP CRC 与采集清单中的 313 条本地 SHA-256 全部通过；5 个目标应用目录、关键框架和 59 项传输均已取得。
11 项警告来自部分 framework 目录不存在和 10 次进程 maps 权限拒绝，未发生 ADB 断连或传输超时。

**已核实**

- 实车 `CarService` 快照与日志中的里程 `43046.0 km`、续航 `673 km`、P 挡及零车速，与照片显示一致。
- `com.thunder.carplay` 的应用资源名称为「雷石 KTV」／`Thunder Stone KTV`，Manifest 和代码对应歌曲、MV、麦克风及 KTV 播放功能。此前仅依据包名将它认定为原厂 Apple CarPlay 接收端，是错误判断；该 APK 不能作为 Apple CarPlay 协议实现的证据。
- `SENSOR_TYPE_FUEL_LEVEL` 所映射的 `0x287000b4` 在车辆服务中名为 `INFO_ID_IPKFUEL_FUEL_TYPE`，当前值为 0；不能作为有效剩余油量。另有 `INFO_ID_REMAINING_OIL`（`0x28700207`）返回原始值 99，单位、缩放及有效性仍待对照验证。
- 原厂空调应用使用 AdaptAPI `Car.create()`、`IConnectable` 和 `ICarFunction`；温度读取与设置明确使用 zone 1 表示左侧、zone 4 表示右侧。当前快照两侧温度原值均为 13，按该固件框架实现换算为 23.5；本轮未执行写入或控温验证。
- 用户明确确认第二张照片的地图由 LynkPlay 投出，原车没有 Apple CarPlay 入口。LynkPlay 地图背景可铺满仪表可见背景，车速、转速、挡位、警告灯、媒体与驾驶辅助元素仍保留。这是实车投影结果，但没有证明这些原车叠层已能由 Android 应用独立控制，也不能据此宣称 CarPlay Ultra 已接通。

本轮为资料完整性、静态实现和既有快照分析，不代表 LynkPlay 自身已完成车辆信号读取／订阅验证。

## 2026-10-17：包名与运行时线索（应用归属结论已更正）

当时复核了 2026-10-16 采集资料包（`.private/lynk06-field.zip`）中的包名和文件系统线索。
其中将 `com.thunder.carplay` 识别为 Apple CarPlay 的判断，已由 2026-10-19 的 APK 分析证伪。

**已核实**

| 项 | 结果 | 证据 |
| --- | --- | --- |
| `com.neusoft.carplay` 存在性 | 不存在 | `ecarx.txt`：`dumpsys package` 的 dexopt/compiler 段均输出 `Unable to find package: com.neusoft.carplay`；`pm list packages` 仅见 `com.neusoft.extraservice/avmlogin/dvrlogin` |
| 已安装的 Thunder 应用 | `com.thunder.carplay`（后续 APK 分析确认为雷石 KTV） | `packages.txt`：`/system/app/ThunderCarplay/ThunderCarplay.apk=com.thunder.carplay`；`ps -A`：`u0_a54 … com.thunder.carplay` |
| Neusoft E02 运行时落点 | 领克 06 均不存在 | `/vendor/etc/CarPlay/`、`/etc/airplay.conf`、`/data/vendor/CarPlay/` 均 `No such file or directory`；`ps` 无 `vendor.alfusos.hardware.applecore@1.0-service`；`/dev/usb_iAP2` 与 `usbncm0` 均不存在 |
| `carplay.*` 键 | 采集数据无任何实例 | `getprop`、应用诊断报告、全量文件 grep 均无 `carplay.*` |
| MFi 芯片（i2c 地址 0x10） | 全车 12 条 I2C 总线无 0x10 驱动绑定设备 | `/sys/bus/i2c/devices`：`0-001a`/`2-0043`/`3-0008`/`4-002c`… 等，无 `*-0010` |

**结论**

- `com.thunder.carplay` 是雷石 KTV，不是 Apple CarPlay 接收端，也不能凭 `com.thunder.*` 推断为 ThunderSoft / 中科创达的 CarPlay 实现。
- 资料包中逆向得到的 `carplay.*` 键映射（pack `REVERSE_ENGINEERING_MAC.md` §2.3b）属于
  IHU715P / Neusoft 实现；本车的包名和文件系统线索不能证明可直接套用该映射。
- MFi 芯片仍待探测：`/sys/bus/i2c/devices` 只列有驱动绑定的设备，E02 的裸 I2C `0x10` 芯片
  （ioctl 直访，无驱动）不会出现在 sysfs；需 `i2cdetect` 或 ioctl 探测才能定论（本轮采集只读，未探测）。

## 2026-10-16：领克 06 实车采集（方控 / 仪表地图 / 连接稳定性）

在 2023 款领克 06 燃油版（BX11 / IHU514G / xecarx / Android 9 / SDK 28）完成一轮只读采集与按键取证：
三次系统快照、应用诊断报告、分段 logcat 与按键时间线（资料包 `lynk06-field`，采集全程未修改车机设置、
属性、USB role 或系统服务，未操作 I2C 寄存器，未读取认证素材）。

**已核实**

| 项 | 结果 | 证据 |
| --- | --- | --- |
| 车型自动识别 | 命中 `Lynk06PetrolAdapter` | `product/device=IHU514G`、`model=BX11`、`hardware=xecarx`、`sdk=28` |
| 固件 | `PQ2A.190405.003 dev-keys`；`ro.build.version.versionname=SW0BX110520H8EHH.00762`；ABI `arm64-v8a` | `getprop` |
| 屏幕 | 物理 1920×720 / 160dpi；应用可用 1800×720 | `wm size`、应用报告 `Screen:` |
| 安装与权限 | `com.leth0x01.lynkplay` v0.2.3 / versionCode 54，`sharedUser=android.uid.system` | `dumpsys package` |
| 有线 CarPlay | 连接成功，画面与声音正常 | 用户确认与会话日志 |
| 方控媒体键 | 下一曲 / 上一曲 / 播放暂停由**原车媒体中心回调**送达并转发 CarPlay | `领克媒体中心：回调指令=4/5/2/1 control=true handled=true` |
| 语音键短按 | 唤起 Siri，经 ECARX 有序广播 | `Lynk06VoiceKey: 动作=0；处理=true` |
| 接听 / 挂断 | 人工确认成功（未记录键码来源） | 采集时的人工确认 |
| 仪表媒体信息 | 原车助手把播放记为 `appName:LynkPlay`，`artwork` 为本应用发布的 `http://127.0.0.1:<port>/covers/<uuid>/<uuid>.jpg` | 原车 `MusicPlaybackInfoHelper` 日志 |
| 仪表地图 | 物理仪表显示 CarPlay 路线 | 用户确认；应用侧 `mapVisible=true`、`routeGuidanceFrames=38/valid=36` |
| USB 插拔恢复 | 2 次均约 13 秒恢复画面与声音 | `stability-usb-01/02-*` |
| OneOS 输入服务 | 本车不存在（预期） | `Geely input service: visible=false` |

**本次修复（待复验；下述为当时方案）**

- 软件「断开 → 用 USB 连接」重连：实车 2/2 停在 `WaitingForReenumeration`，该阶段只有请求失败才有
  fallback、没有超时。新增 `ReenumerationWatchdog`（180 s，越过 `WiredSessionMemory` 记录的
  30–150 s USB 复位窗口），超时后重跑配置决策使用 iPhone 当前提供的配置。
- 同步 DiPlay 0.2.21 时改用上游 `watchReenumeration`，移除上述 180 s watchdog：10 s 后重新发现
  同一 USB 节点并检查权限和配置；设备已离开总线时继续等待接入事件，已有接入或更新重试会使旧检查失效。
  该逻辑针对同一实车卡死现象；180 s 方案与新方案均不能据此宣称已通过领克实车复验。

**仍未核实**

- 无线 CarPlay 端到端（本轮只测有线）；语音键长按（用户跳过）。
- 完整稳定性循环：退出重连 2/10、USB 插拔 2/20、熄火→上电 0/5；蓝牙 / Wi-Fi / 原车语音恢复未逐项确认。
- 仪表媒体中心的专辑封面：原车 `ThreeAlbumService` 拿到 null 封面 URL 时执行 `new URL("null")`
  抛 `MalformedURLException` 并回退默认封面；客户端「封面未就绪即发空」为既有设计，未改动。
  用户报告「不开仪表地图有封面、一开就没有」，但本轮日志里该异常在 `appMapReady` 两种状态下都出现，
  且地图开启时段（22:05–22:09）原车 `MusicPlaybackInfoHelper` 与 `ThreeAlbumService` 都没有输出，
  需同一首歌、只切地图开关的对照采集才能区分是原车换了渲染组件、播放源变化，还是失败率上升。
- MFi 认证芯片（仅确认 `/dev/i2c-*` 节点存在）；车机上实际运行 APK 的哈希与签名。

**已定位的实车缺陷**

- 在车机设置里启用 LynkPlay 无障碍服务会使设置应用崩溃（OEM `AccessibilityServiceWarning` 弹窗空指针，
  与本应用声明无关）；应用内已提供经 ADB 启用的途径，不要使用设置界面。
- 原厂 `com.neusoft.carplay` 未出现在 `pm path`；不能据此断言不存在。

ECARX 输入路径的调查过程、映射表与不采用理由见
[ECARX AdaptAPI 输入调查](LYNK06-ECARX-INPUT-20261016.md)。

## 2026-10-13：领克 06 方控、麦克风与蓝牙适配

新增本车语音键有序广播接入：CarPlay 活跃且未通话时短按 Siri，长按请求打开 LynkPlay；成功处理后终止后续广播。补齐本车前台播放／暂停按键；蓝牙音频模式下媒体控制交给 AVRCP，并保持当前会话配置到重新连接。Siri 录音器启动失败后释放并尝试后备源，领克追加 MIC 末级回退；录音诊断记录实际来源。蓝牙诊断改为本车 A2DP Sink / HFP Client。

最终完整 CI 通过：shared 1420 项（跳过 1 项）、common 1294 项、home 6 项，0 失败／错误；mobile、home、maphost 的 Lint 与 Debug 构建通过。`git diff --check` 通过。

一次复核在已有 `SettingsBackupServerTest.stopDoesNotFailWhileConnectionsAreEnding` 卡住，线程栈停在 JDK 25.0.3 的 `ServerSocket.close()` 原生关闭路径；终止该测试 worker 后重跑完整命令通过。方控、拾音质量、原车蓝牙控制与长按对 HiCar 的优先级仍需实车复核，接口依据、参考源码和步骤见[专项适配记录](LYNK06-WHEEL-MICROPHONE-BLUETOOTH.md)。

## 2026-10-13：最新实车反馈

用户确认已在 2023 款领克 06 燃油版、Android 9 真实车机上验证，无线、有线 CarPlay 均能连接。

当前异常：仪表地图无法使用，声音出现异常，待排查。连接成功不代表声音、仪表地图或长时间稳定性已通过验收。本次反馈未指定安装包版本、无线连接方式或声音异常的具体表现，暂不归因于某个版本、连接模式或音频通道。

本次同步文档，并将版本配置更新为 0.1.8 / versionCode 46。AGENTS.md 要求的完整 Android 检查命令执行成功（`BUILD SUCCESSFUL`），单元测试任务复用已有结果；文档相对链接检查与 `git diff --check` 通过。NDK 任务输出 `fcntl(): Bad file descriptor`，未导致构建失败。

以下各日期条目保留当时的测试与采集结果；其中“待实车验证”描述的是当时状态。当前连接与异常状态以上述最新反馈为准。

## 2026-10-13：合并 DiPlay 0.2.19 与菜单布局迁移

将远端 `main` 的 `9aa9c549` 合并到 `lynkplay`，保留 LynkPlay 包名、版本配置、领克仪表适配及诊断信息。手机诊断报告使用与本地导出相同的报告生成逻辑。上游 README 更新到 `docs/DIPLAY-README.md` 与 `docs/DIPLAY-README.zh-CN.md`。

CarPlay 菜单迁移上游玻璃面板布局，顶部固定标题与关闭按钮，底部固定保存按钮，退出应用位于滚动列表末尾。尺寸继续使用领克窗口规则：宽屏菜单 1120dp、控件至少 72dp，窄窗按可用宽度收缩，并保留系统栏和刘海安全边距。DiLink 首次设置的导航音频默认值仅应用于启用 BYD 集成的车型。

完整执行 AGENTS.md 要求的测试、lint 和 Debug 构建：shared 1417 项（跳过 1 项）、common 1272 项、home 6 项，0 失败／错误；三个模块 lint 与 Debug 构建通过。公开源码检查、8 项打包脚本测试与 8 种语言的静态页面生成通过。

一次完整检查在上游 `SettingsBackupServerTest.stopDoesNotFailWhileConnectionsAreEnding` 中卡住，线程栈停在 JDK 25 的 `ServerSocket.close()` 原生调用；终止该测试 worker 后重跑完整命令通过。

已检查 1.5 倍字体下的窄窗、完整窗口、阿拉伯语及领克 1920×720 布局截图，标题与固定操作区可见：

- [窄窗](screenshots/lynkplay-swipe-compact-large-font.png)
- [完整窗口](screenshots/lynkplay-swipe-full-large-font.png)
- [阿拉伯语](screenshots/lynkplay-swipe-arabic-large-font.png)
- [浅色](screenshots/lynkplay-swipe-light-large-font.png)
- [领克菜单顶部](screenshots/lynkplay-swipe-lynk06-top-large-font.png)
- [领克菜单底部](screenshots/lynkplay-swipe-lynk06-bottom-large-font.png)

## 2026-10-12：仪表 watcher 注册失败恢复与 root attempt 异常后备

仪表链接新增明确的 `watcherRegistered` 状态，仅在注册调用成功返回后置为 true。注册前或注册中途失败时，尝试解除回调与断开接口，清空 DimInteraction 和连接类引用，让下一次重试重新创建对象、注册 watcher，再连接服务。旧注册代次的迟到回调不再参与连接状态处理。

新增 2 项仪表回归，分别模拟首次注册直接抛异常，以及保存 watcher 后再抛异常。两项均验证第二次创建和注册成功、仅调用一次 connect、接口恢复 available；部分注册用例还验证清理调用与旧回调失效。新增 1 项 VPN 回归验证 root attempt 的 RuntimeException 被记为 UNAVAILABLE 后，继续系统授权验证、ADB 后备及最终验证。

修改前 26 项定向测试中，这 3 项新增回归均失败。修复后完整执行 AGENTS.md 命令：shared 1374 项（跳过 1）、common 1213 项、home 4 项，0 失败／错误；仪表链接 16 项与 VPN 特权授权 10 项均通过，三个 Lint 与三个 Debug 构建通过。public-tree 检查通过。

## 2026-10-12：VPN 真实授权 fallback、ABI 隔离与仪表连接退避

VPN 特权授权改为 root → 系统验证 → 未授权时 ADB → 再次系统验证。命令退出码为 0 但 `VpnService.prepare()` 仍要求授权时，也执行 ADB 后备；结果单独记录真实授权状态。前台仍复核系统授权与 USB 模式后才继续连接。新增 4 项测试覆盖命令成功但未授权后 ADB 恢复、ADB READY 但仍未授权、系统已授权时跳过 ADB，以及授权检查异常。

ABI Probe 在采集前标记已尝试，整体异常只写诊断日志；内部按类与方法保留局部失败记录。新增测试模拟 `SecurityException`，确认仪表仍能连接和重连，Probe 仅调用一次。

仪表连接请求拥有 4 秒等待窗口，等待期间不因自身断线回调重新安排连接；超时或接口连接失败后按 1、2、4、8 秒退避，最大 8 秒，成功连接后复位。单次模式读取与导航通知失败继续按 1 秒重试。新增测试验证首次启动和重连均允许 2.5 秒的连接回调完成，并确认持续缺失回调时请求间隔增长。

修改前的 20 项定向测试中，新增的 5 项问题回归失败；修复后仪表链接 14 项、VPN 特权授权 9 项与 VPN 授权流程 16 项在完整测试中均通过。完整执行 AGENTS.md 命令：shared 1374 项（跳过 1）、common 1210 项、home 4 项，0 失败／错误；三个 Lint 与三个 Debug 构建通过。public-tree 检查通过。

本轮结果来自本地检查。USB 冷启动／插拔／覆盖升级、无线长时间会话、仪表模式与导航组合，以及运行时 ABI snapshot 仍待实车验证。

## 2026-10-12：仪表自动恢复、VPN fallback 与 Windows CLI 编码修复

仪表单次读取或导航通知异常只发布 unavailable 状态，并在 1 秒后重试；连续 3 次失败重连厂商接口。Service 断线与连接回调缺失会继续尝试连接，重新取得接口并发布本会话导航状态。关闭后取消恢复任务，仍尝试撤销已发送的导航通知。

`Lynk06ClusterLinkTest` 共 10 项通过，新增 7 项覆盖首次读取失败后自动恢复、连续失败重连及导航重新发布、开始／结束通知重试、Service 断线恢复、连接回调缺失后恢复、关闭取消重试，以及只读 ABI 采集。`VpnPreConsentTest` 共 5 项通过，fallback 测试覆盖 root 不可用、appops 命令失败和命令成功三种分支。

完整执行 AGENTS.md 的 Android 检查命令，shared 1374 项（跳过 1）、common 1202 项、home 4 项，0 失败／错误；三个 Lint 与三个 Debug 构建通过。

读取 [Windows 失败日志](https://github.com/leth0x01/LynkPlay/actions/runs/38225756196/job/114732733150)，确认三个中文 CLI 的 `--help` 因 CP1252 输出而抛出 `UnicodeEncodeError`。本地以 `PYTHONIOENCODING=cp1252` 复现后，三个入口统一配置 UTF-8 标准输出与错误输出。新增编码回归通过三个脚本子用例；脚本编译、public-tree 扫描、三个 CLI 帮助与静态站点生成检查均通过。workflow 已拆分为独立步骤，并加入编码回归；新代码的 Windows／macOS runner 结果待提交后复核。

运行时 ABI Probe 已接入仪表会话日志，采集方式见 [仪表研究记录](LYNK06-CLUSTER-INTEGRATION-RESEARCH.md#运行时-abi-probe-与文本-snapshot)。实车签名 snapshot、USB 插拔／冷启动／覆盖升级、无线稳定性与 FULL／SIMPLIFY／OFF 导航组合仍待实车验证。

## 2026-10-12：领克 06 仪表模式联动（0.1.6）

从本车 AdapterAPI.jar 和 XSFNaviService APK 核对连接方式、仪表模式接口与导航通知值。新增 CarPlayNavigationStatus，独立跟踪 iAP2 导航状态；Lynk06ClusterLink 在独立线程连接厂商服务，读取／监听模式，导航进行发送 1、结束发送 0。仅在接口可用、导航进行且 FULL（3）模式下显示仪表窗口；简洁或失败时隐藏窗口，保留解码 Surface。关闭时撤销本次导航通知并释放回调。

新增 7 项测试覆盖导航结束／断开、无转向时导航继续、无效帧、监听所有者更换、模式跟随、接口失败、结束通知和资源释放。完整执行 AGENTS.md 命令，单元测试、三个 Lint 和三个 Debug 构建通过；shared 1356 项（跳过 1）、common 1179 项，均无失败。home 检查使用已有结果。

专项采集脚本通过 PowerShell 7.6.6 解析检查。离线替身分别模拟阶段开始前和操作后的 ADB 断线，两组均确认停止推进、生成中断记录、保留已采集目录与 ZIP。新增按阶段补采参数和原车投屏相关组件采集；Windows 与车机执行待复核。

0.1.6-lynk06-test / versionCode 44 的 carTest APK 已构建。Android 9 签名、长期签名和打包认证资产校验通过，APK SHA256 为 `e80891e54c33a256d6172175e6043346ce42030d8d13edd4453fcfe9ee6ebf29`。接口权限、实车模式回调和原车飞屏控制尚待车测核对；采集数据和测试图片保留本地。

## 2026-10-12：领克 06 仪表地图接入（0.1.5）

接入采集到的 DashboardMapDisplay。选择条件为已匹配的 IHU514G / xecarx / API 28 车机、有效副屏、Presentation 标记、精确名称及 1920 × 720 尺寸。沿用独立仪表视频流和现有开关，默认 100% 原生尺寸、30 fps，通用边距和居中车标；已保存的比例、内容和位置继续生效。

设置沿用高级分类的“仪表盘上的 CarPlay 地图（实验性）”，开启与关闭仍经过运行中重新连接确认。新增领克说明已添加到全部 7 个语言资源目录。

首轮定向回归通过，覆盖领克显示选择、错误设备与显示参数、拒绝比亚迪回退、默认关闭、地图协商参数、用户设置保留、独立 Surface 111 路由及关闭释放。原有仪表选择、地图比例能力、设置确认和翻译测试通过。

最终完整 CI 检查通过：shared 1353 项（1 项跳过）、common 1175 项、home 4 项，0 失败；三个 Lint 与三个 Debug 构建均通过。新增 9 项领克仪表测试全部通过，包括主屏排除及显示失效后的虚拟流处理。

已生成 `0.1.5-lynk06-test` / versionCode 43；包名、长期签名、Android 9 签名与认证素材校验通过。APK SHA256：`1800f92053b857d3758732c7af8abc5b8f0e3f33b4778e1340f0c8f7914d106b`。

实车待验：地图实际显示位置与裁切、原车仪表地图模式切换、关闭后的原车地图恢复。现有采集只证明显示设备存在，未验证新版本投屏。

## 2026-10-12：领克 06 实车密度宽屏适配（0.1.4）

按采集的 1920 × 720、160 dpi 主屏参数新增宽屏尺寸策略。可用窗口达到 1400 × 600 dp 时，菜单最大宽度 1120 dp、弹窗最大宽度 960 dp、按钮和输入框最低高度 72 dp；菜单正文最低 20 sp，弹窗正文 20 sp、标题 26 sp。窄窗和低高度窗口使用紧凑布局。

车型自动匹配使用 IHU514G product / device / model、xecarx hardware 和 API 28 的完整组合，统一使用领克 06 燃油版配置。新增测试覆盖完整匹配、部分标识误匹配、其他平台以及 Android 运行时匹配和诊断输出。

界面测试覆盖实车主屏尺寸、普通应用安全窗口 1800 × 670、深浅主题、窄窗、高密度窗口、RTL 和 1.5 字体缩放。热点弹窗另以 400 px 可用高度模拟软键盘占用，验证底部操作按钮可见；这是窗口高度模拟，实际车机键盘行为仍需实测。

大字体截图由 API 29 Robolectric native graphics 生成，存放在本地 `common/build/reports/carplay-menu-ui/` 和 `common/build/reports/app-dialog-ui/`。

USB 继续使用已实现的 root 优先流程，5 项 VpnPreConsent 回归测试通过。实车目前已有 ACTIVATE_VPN allow 和 USB 视频阶段证据；本轮未取得新版本完整前台 USB 会话。

最终完整 CI 命令通过：shared 1353 项（1 项跳过）、common 1166 项、home 4 项，0 失败；mobile、home、maphost 的 Lint 和 Debug 构建均通过。

已生成 `0.1.4-lynk06-test` / versionCode 42；正式包名、长期签名、Android 9 签名兼容性和认证素材检查通过。APK SHA256：`2dad1aad8018cc0e9fec65363b258b2418e00155fdeae9dd023048ebf2e23be2`。

# LynkPlay 品牌基线验证（2026-10-11）

## 2026-10-12：三指下滑菜单主体适配

菜单主体改为固定标题栏、可滚动分组卡片和固定操作栏，配色与主设置页一致。输入框使用主题圆角背景，窄窗口中标签与输入框纵向排列；操作按钮按可用宽度调整排列。横排选项空间不足时转为纵排，窗口变宽后可恢复横排。系统栏和屏幕缺口通过窗口 insets 保留安全边距。

已有 34 项菜单功能测试通过，覆盖手势、草稿、取消、保存重连及主题切换。新增 UI 测试在深浅色、360 dp 紧凑窗口、1920 × 720 横屏、宽屏和 RTL 场景中检查分组卡片、固定区域可见性、输入框样式以及取消时的草稿回退。

完整 CI 命令通过：shared 1350 项（1 项跳过）、common 1164 项、home 4 项，0 失败；三个 Lint 和三个 Debug 构建通过。

截图由 API 29 Robolectric native graphics 绘制，字体缩放为 1.5，存放在本地 `common/build/reports/carplay-menu-ui/`。1920 × 720 场景使用模拟密度 320 dpi，实车密度仍以诊断报告为准。

已生成 `0.1.3-lynk06-test` / versionCode 41 车测包，原长期签名、Android 9 签名、正式包名和认证素材检查通过。APK SHA256：`c6d6366575a71f16e58def1c65bf9a596c4ce1f792e1d1f37d761d02734739c1`。菜单的实车显示、触摸和键盘行为待覆盖安装后确认。

## 2026-10-12：应用弹窗样式统一

热点配置、文字及数值输入、选择列表、确认提示和时间选择弹窗使用统一的应用样式入口。外框、输入框和按钮沿用软件的深浅色配色与圆角规则。按钮按可用宽度及文字长度横排或纵排，长内容弹窗保留操作区的最低高度。

用户提供的车机信息为 12.3 英寸、8:3、1920 × 720 横屏、亿咖通 E02、6 GB 内存、64 GB 存储，Android 9。

完整 CI 命令通过：shared 1350 项（1 项跳过）、common 1163 项、home 4 项，0 失败；三个 Lint 和三个 Debug 构建通过。新增 4 项 UI 测试验证热点校验、密码显隐、保存与取消、列表选中及回调、时间选择和低高度横屏操作按钮可见性。分辨率、夜间模式及氛围灯长内容弹窗的既有测试通过。补充 1920 × 720 横屏场景后，定向 UI 测试再次通过。

大字体截图由 API 29 Robolectric native graphics 绘制，字体缩放为 1.5，存放在本地 `common/build/reports/app-dialog-ui/`。1920 × 720 场景按模拟密度 320 dpi 配置；截图为弹窗本身，不能代表实车实际密度或软键盘行为。

已生成 `0.1.2-lynk06-test` / versionCode 40 车测 APK，包含上一版的 root VPN 授权修复。Android 9 签名、正式包名、认证素材及原长期签名检查通过。APK SHA256：`8504d564f9297b81a84bceb3b54619bbb6c572ac874a538e230748c159555178`。本版实车显示和输入行为待覆盖安装后确认。

## 2026-10-12：USB VPN root 授权修复

用户实车反馈：2023 款领克 06 燃油版能够无线连接，但 CarPlay 画面和操作卡顿；USB 模式提示无法打开 VPN 授权界面。车机已有 root，电脑 ADB 已连接过。

VPN 授权界面无法使用时，新增 App 内 root 优先的授权路径。通过 `su -c id -u` 确认 root 后，仅为当前应用包名设置 `ACTIVATE_VPN`；未取得 root 才使用原有 ADB 后备。root 命令失败不会触发 ADB，最终通过 `VpnService.prepare()` 验证系统授权。后台完成时确认仍为 USB 模式，避免无线切换期间触发 USB 启动。

本地完整 CI 命令通过：shared 1350 项（1 项跳过）、common 1159 项、home 4 项，0 失败；mobile、home、maphost 的 Lint 和 Debug 构建通过。新增 5 项测试覆盖当前包名授权、root 不可用、root 命令失败、ADB 后备顺序及包名校验。

已生成 `0.1.1-lynk06-test` / versionCode 39 的车测 APK。构建脚本核验 Android 9 签名、正式包名、认证素材配对及原长期签名通过。APK SHA256：`64b7b28db8d9f3729d5192842a75a0763e7fa5834fe298f9d1537b2d84fe31ba`。root 授权修复的实车结果待新版验证。

## 修改范围

主应用品牌改为 LynkPlay，release 安装标识为 `com.leth0x01.lynkplay`，debug 为 `com.leth0x01.lynkplay.hudtest`。保留 namespace、存储键、兼容 action 和车型逻辑。更新来源为 `leth0x01/LynkPlay` 的 Releases。

## 本地环境与检查

Windows、JDK 25.0.3、Android SDK Platform 37、Build Tools 36.0.0、NDK 28.2.13676358。测试 PATH 包含 Git Bash 的 sh。

执行 AGENTS.md 要求的全部任务，使用 `--continue` 让测试失败后其余任务继续完成：

```powershell
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest :mobile:lintDebug :home:lintDebug :maphost:lintDebug :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug --continue --console=plain
```

| 检查 | 结果 |
| --- | --- |
| shared 单元测试 | 1348 项，3 项失败 |
| common 单元测试 | 1148 项，7 项失败 |
| home 单元测试 | 4 项，全部通过 |
| mobile / home / maphost Lint | 全部通过 |
| mobile / home / maphost Debug 构建 | 全部通过 |
| 新增 fork 更新来源与空 Releases 测试 | 通过 |
| VPN 授权错误提示测试（新品牌） | 16 项全部通过 |
| 诊断导出 MediaStore 测试（新目录） | 5 项全部通过 |
| 35 个修改资源文件内容核对 | 仅替换可见品牌文字，保留资源键、格式参数及许可归属文案 |
| git diff --check | 通过 |

完整检查命令返回失败，不能宣称全量测试通过。剩余 10 项失败均在独立工作区中的未修改 DiPlay 基线 `24ae1fa5` 用同一 JDK/SDK 单独复现：

- `HotspotJoinNormalizedTransactionTest`：5 项中 3 项失败，事务返回 `STORAGE_FAILED`。
- `UpdateInstallCompatibilityTest`：10 项中 4 项失败，FileProvider 无法匹配 Windows 缓存文件路径。
- `UsbPermissionSetupTest`：15 项中 3 项失败，Git Bash 接收的 shell 参数引号导致语法错误。

以上测试和对应实现未因品牌修改而改变，未为绕过本地失败而修改 Android 运行行为或屏蔽测试。Linux CI 的结果仍需单独核实。

## APK 验证

`aapt2 dump badging` 已确认 APK 的 application label 为 `LynkPlay`，包括简体中文、繁体中文和其他 locale；package 为 `com.leth0x01.lynkplay.hudtest`。

合并 manifest 中 diagnostic-reports、update-apks 的 provider authorities 均使用新 package，application label 指向 `app_name`。debug manifest 的原 `DiPlay HUD Test` 硬编码 label 已移除。

输出：`mobile/build/outputs/apk/debug/mobile-debug.apk`。该 APK 是普通源码构建，没有提供运行时配件身份。本次没有上传或发布 APK。

## 实车验证边界

目标为 2023 款领克 06 燃油版、Android 9。本次没有实车连接、音频、方向盘、Siri 或启动行为的验证证据，不宣称车型适配完成。下一阶段需获取车机诊断报告及实际故障，再核对车机系统属性、厂商服务和按键接口。

## 官方领克图标替换复检

同日将默认 BYD 车机按钮图标替换为领克官方 LYNK & CO 字标，默认按钮名称同步为 `LYNK & CO`。来源和处理方式见 [品牌素材说明](LYNKPLAY-BRANDING.md)。

再次执行上面的完整检查：三个 Debug 构建和三个 Lint 任务全部通过，测试仍为 shared 1348 项中 3 项失败、common 1148 项中 7 项失败、home 4 项通过。失败测试与前述基线问题一致，没有新增失败。

已检查生成的 192 × 192 图标，并从最终 APK 提取 `res/raw/ic_car_home.png`：SHA-256 为 `0ddacb2f9e9b0f0bda8382053521c6e44867400265205a74a54c43e90c5914f3`，与源资源一致。既有 AirPlayIconSizing 测试全部通过，图标仍符合方形 PNG 和最大 192 px 的传输要求。未改变设置布局或行对齐。

## 领克 06 第一优先级：车型与厂商能力隔离

目标配置通过 `mobile/src/main/res/values/vehicle_config.xml` 固定为 `lynk_06_2023_petrol`，关闭 BYD 集成。shared 的通用资源保留上游能力，避免大规模删除 BYD 代码。目标标识是构建配置，不是实车检测结果。

Geely 输入契约和 Siri 录音源分别判断，仅接受已有验证记录的 `geely_fx11` / `CS11_A4` / API 30 组合；Android 9、品牌字段或单个型号字段不启用特殊策略。输入服务还检查可见性、启用状态、导出状态、权限及 Binder 描述符，失败释放绑定并记录标准媒体键回退。标准 MediaSession 与原有录音源后备继续使用。

BYD HUD、车辆数据、专属 ADB 仪表、胎压和氛围灯的入口受到运行门禁保护，已保存的偏好和残留 BYD 包无法开启它们。通用 GPS、电话音频路由、公共第二屏和 Wi-Fi 扫描恢复保留。直接调用 BYD 车辆检查或探测接口会在建立 ADB 连接前拒绝请求。

引导不再要求选择 DiLink 代际，显示领克目标、实际 Android 版本与待验证状态。新增四个字符串覆盖全部七套语言资源。诊断记录构建目标、设备标识、固件、ABI、各项策略及输入服务状态。

完整 CI 命令执行结果：shared 1350 项中 3 项失败，common 1154 项中 7 项失败，home 4 项通过；三个 Lint 和三个 Debug 构建通过。共 2508 项，新增加的 8 项能力隔离、服务回退和 UI 测试通过，剩余 10 项失败与前述已复现的 Windows 基线问题一致，不能宣称全量 CI 通过。

最终补充直接探测门禁后再次执行完整 CI，仍为同一组 10 项基线失败。随后定向复检新 UI、车辆门禁与输入绑定测试全部通过。

大字体截图由 `LynkSetupUiTest` 调用真实引导组件并以 Robolectric native graphics 绘制，覆盖紧凑 360 dp / mdpi、宽屏 1280 dp / xhdpi 和阿拉伯语 600 dp / mdpi，字体缩放 1.5。生成截图保存在本地 `common/build/reports/lynk-setup-ui/`。UI 渲染环境使用 API 29，车型运行门禁另在 API 28 测试。这些都是模拟验证，不是实车照片。

最终 APK 资源检查确认 `config_vehicle_profile=lynk_06_2023_petrol`、`config_byd_integration=false`。普通 Debug APK 仍是未提供配件认证输入的源码构建；未制作独立车测包，未进行实车连接、声音、Siri 或方向盘验收。

## 2026-10-12：稳定独立车测包

新增 `carTest` 构建，包名为 `com.leth0x01.lynkplay`，版本 `0.1.0-lynk06-test` / versionCode 38，非 debug。未来 release 使用相同的本地长期 APK 签名，保证在递增版本后能够覆盖升级。

签名证书 SHA256：`ea6a1ad297d40af0ab441543b08c3d5a26f237a0ba1096448d07ab5a0227fc74`。密钥和密码只保存在忽略文件中，尚未建立异地备份。初始化脚本重复运行保留原签名。

认证输入来自[原版官方 v0.2.18 APK](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.18)，APK SHA256 与官方 `SHA256SUMS.txt` 一致：`e241457b36b21fe75b06b5682b4dcfc4fbc25cf75b1855c7a4d055129635ce8e`。仅导入固定两项认证资产到 `.private/`，来源记录不会打包。本地验证证书为 P-256 且与私钥配对，不代表 Apple 认证。

已执行负向检查：错误源 APK SHA256、缺失认证、不配对的密钥和证书、错误签名证书指纹均被拒绝。PowerShell 三个脚本语法检查通过，公开源码及新增文件按凭据扫描规则检查通过。源码 debug APK 未包含认证资产。

车测 APK 通过 `apksigner verify --min-sdk-version 28`（v2 签名），证书与固定指纹相同；`aapt2` 确认包名、版本和应用名称 LynkPlay。打包的两个认证文件与选定输入 SHA256 完全一致。重新构建仍使用同一签名。构建流程见 [车测说明](LYNKPLAY-CAR-TEST.md)。

没有进行安装、发布、上传或实车验收。

本次完整执行 AGENTS.md 命令：shared 1350 项中 3 项失败、common 1154 项中 7 项失败、home 4 项通过，共 2508 项。失败名称与上述已确认的 Windows 基线 10 项完全一致。三个 Debug 构建与三个 Debug Lint 通过；全量 CI 仍返回失败，不宣称全量通过。

`:mobile:lintCarTest` 通过；随后重新构建并通过最终包名、签名和认证资产核验。产物 `mobile/build/outputs/apk/carTest/mobile-carTest.apk` 的 SHA256 为 `74ec294ed38bf188f22a9fad7db1b4de17d718271095c9a2a81d655ed1d41a4e`，旁边生成 `SHA256SUMS.txt`。认证输入和签名私钥未进入公开源码。
