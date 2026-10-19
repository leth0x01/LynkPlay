# BX11 / IHU514G 系统仪表版本

本文记录 2023 款领克 06 燃油版的系统环境、仪表地图实现和实车结果。内容已根据 2026-10-12 至 2026-10-16 的现有采集更新。

早期测试使用独立包名 `com.leth0x01.lynkplay.clustersystem`。当前车测包与正式包已统一为 `com.leth0x01.lynkplay` 的 `release` 构建，不再使用独立的 `clusterSystem` 或 `carTest` 构建类型。2026-10-16 采集时安装的是 LynkPlay 0.2.3（versionCode 54）；当前源码和已发布版本为 0.2.4（versionCode 55），但 0.2.4 尚无新增实车采集结果。

## 已采集的车机环境

| 项目 | 采集结果 |
| --- | --- |
| 车型 | 2023 款领克 06 燃油版 |
| 平台 | BX11 / IHU514G / xecarx |
| Android | Android 9 / API 28 |
| 系统版本 | `PQ2A.190405.003`，incremental `762` |
| 版本名称 | `SW0BX110520H8EHH.00762` |
| ABI | `arm64-v8a, armeabi-v7a, armeabi` |
| 物理中控屏 | 1920 × 720 / 160 dpi |
| 应用可用区域 | 1800 × 720 / 160 dpi / 60 Hz |
| 仪表显示 | `DashboardMapDisplay`，displayId 1，1920 × 720 / 213 dpi / 30 Hz，支持 Presentation |
| AdaptAPI | 1.1.18.9 |
| 原车导航服务 | `ecarx.naviservice` |
| 原车地图 | `com.autonavi.amapauto` |

2026-10-16 的 `dumpsys package` 显示 `com.leth0x01.lynkplay` 使用 `android.uid.system` 共享用户，实际 userId 为 1000。车型自动识别命中 `Lynk06PetrolAdapter`，条件为 product/device=`IHU514G`、model=`BX11` 或 `IHU514G`、hardware=`xecarx`、API 28 的完整组合。

## 仪表显示事实

- `DashboardMapDisplay` 在原车未导航、简洁提示、地图模式和退出导航等阶段均持续存在，因此“显示器存在”不能表示物理仪表正在显示地图。
- 2026-10-16 应用诊断记录到 `presentationDisplay=1:DashboardMapDisplay`、`presentationShowing=true`、`mapVisible=true`、`streamActive=true` 和有效输出 Surface。
- 同次诊断累计收到 38 个路线引导帧，其中 36 个具备完整导航数据；`adapterConnected=true`、`menuAttached=true`、`naviAttached=true`，没有记录链路错误。
- 用户已确认物理仪表显示 CarPlay 路线，并在方向盘切换下一曲后继续正常显示；应用状态只能证明输出链路就绪，物理显示结论来自该次人工观察。
- 实车地图显示期间 `getNaviMode()` 仍为 1，`getShowPresentationOption()` 为 0。历史采集中地图显示和消失时都出现过相同 getter 值，因此二者只保留为诊断信息，不作为地图可见条件。
- 原车三指左划日志记录 `startScreenProjection start=true`，随后出现 `INFO_ID_MMI_NAVSYNCDISPLAY open`。原车投影服务还会检查恢复出厂状态、导航是否启动、IHU 状态和 LCD 状态；请求发出不等于物理屏幕已经完成显示。

## 当前实现

### 显示选择与视频输出

- 仅在完整车型组合匹配后选择仪表显示。
- 显示必须有效、带 Presentation 标记、名称精确为 `DashboardMapDisplay`、尺寸精确为 1920 × 720，并排除默认显示器。
- 独立仪表流默认请求 1920 × 720、30 fps 和 100% 比例。BX11 上同时控制窗口可见性与 alpha，避免 Android 9 的 SurfaceView 图层在导航结束后继续遮挡原车界面。
- 导航结束先隐藏应用窗口，再异步撤销厂商接口状态；显示模式变化保留解码 Surface，不要求重新连接 CarPlay。

### 导航状态与路线卡片

- 导航状态来自 iPhone 的真实 iAP2 RouteGuidanceUpdate，不以显示器存在或菜单 getter 代替。
- 开始和结束导航分别调用 `notifyNavigationStatus(1)` 与 `notifyNavigationStatus(0)`，并配套发送 Turn-by-Turn 开始、停止和投影声明。
- 路线卡片使用真实道路名称、下一提示距离、剩余距离和剩余时间；距离单位为米，ETA 使用剩余秒数。
- 只有 maneuver、距离和剩余时间均有效时才生成完整卡片。未解码的车道、高速出口、摄像头和服务区信息保持为空。
- 变化卡片最短间隔为 500 ms，内容未变化时每 10 秒续发。结束导航时发送状态为 0 的空卡片并撤销本次导航声明。
- Apple maneuver type 按已核对的 BX11 映射转换为仪表 turn ID；Apple 的驾驶侧不作为车辆航向，`getDrivingDirection` 保持 0。

### 原车服务协调与恢复

- 仅在完整车型匹配、实际 UID 为 1000、系统版本元数据开启、AdaptAPI 已连接且收到真实完整导航卡片后，才临时暂停 `ecarx.naviservice`。
- 暂停前记录原 enabled setting；导航结束、链路关闭或服务断开后恢复原值并发送恢复广播。
- 如果原服务此前已被用户或其他应用禁用，LynkPlay 不取得该状态的所有权，也不会擅自恢复。
- 导航状态、路线卡片、Turn-by-Turn 和投影声明的待清理项会持久记录；下次建立仪表链路时先完成遗留清理，再声明新的导航会话。
- Android 不保证进程被强制终止时立即执行退出回调。测试中应先正常结束导航并确认原车导航恢复，再关闭或更新应用。

### Android 9 系统 UID VPN

早期独立系统包在 Android 9 上曾出现 USB 与 iAP2 正常、但 `VpnService.establish()` 返回空的问题。修复后会在无现有 VPN 配置、无 always-on VPN 且首次建立失败的限定条件下重新准备本应用的 VPN 归属，再执行正常建立流程。

2026-10-14 实车日志确认 VPN 隧道建立成功；CarPlay 高德地图已显示，视频约 30 fps，诊断中的 recoveries 和 late 均为 0。一次 USB 读取失败后会话重新连接成功。

## 实车结果

### 2026-10-14

- 修复后的早期独立系统包成功建立有线 CarPlay 会话。
- 中控画面和仪表 CarPlay 地图均已显示。
- 视频日志持续约 30 fps，未记录视频恢复或迟帧。

### 2026-10-15

- 同步当前歌曲单项列表后，原车仪表广播改为使用当前 CarPlay 封面。
- 用户在该次固定歌曲对照中确认仪表显示正确封面；该结果不代表长时间或所有音乐应用均稳定。

### 2026-10-16

- 统一包名的 LynkPlay 0.2.3（versionCode 54）在实车上识别为 UID 1000。
- 有线 CarPlay 连接成功，用户确认画面与声音正常。
- 用户确认物理仪表显示 CarPlay 路线；应用记录 `routeGuidanceFrames=38`、`validNavigationFrames=36`、`mapVisible=true`。
- 方向盘下一曲、上一曲和播放/暂停通过原车媒体中心回调到达 LynkPlay，操作后仪表地图和声音保持正常。
- 两次物理 USB 拔插均恢复画面与声音，用户估计约 13 秒。
- 从应用内断开后直接再次选择 USB 连接，两次都停在等待重新枚举。已发布的 v0.2.4 APK 加入等待超时后的回退；当前分支同步上游后改为 10 秒同节点重检，两种方案均尚待实车复验。
- 原车仪表媒体中心在本轮三次媒体操作后都未显示音乐封面。应用侧已有封面发布与 HTTP 读取成功记录；结合 10 月 15 日的单次成功，只能确认链路可用但尚不稳定，不能直接归因于仪表地图开关或封面生成失败。

## 后续实车复核

1. 确认原车导航在测试前可正常显示，并避免两个 CarPlay 应用同时控制仪表。
2. 使用当前统一包打开“仪表盘上的 CarPlay 地图（实验性）”，启动有明确转向提示的真实路线。
3. 核对中控画面、物理仪表地图、道路名称、距离、转向和声音，并导出应用诊断报告。
4. 结束导航，确认应用地图窗口隐藏、清理项完成，原车导航可再次正常使用。
5. 分别复测应用内断开后的 USB 重连、物理 USB 拔插和熄火后上电；逐次记录恢复时间及画面、声音和原车功能状态。
6. 对同一首歌曲只切换仪表地图开关，分别记录原车仪表媒体中心封面是否显示，避免把播放源变化与地图状态混为一项。

当前仍需补充：0.2.4 的软件断开重连、无线 CarPlay、完整稳定性循环、导航结束后的原车恢复、长时间仪表地图运行，以及原车仪表媒体中心封面。

## 相关记录

- [LynkPlay 验证记录](LYNKPLAY-VALIDATION.md)
- [领克仪表地图显示依据与诊断](LYNK06-MAP-VISIBILITY-DIAGNOSTICS-20261015.md)
- [BX11 导航协议与访问边界离线复核](LYNK06-CLUSTER-OFFLINE-20261013.md)
- [BX11 系统 UID VPN 修复](LYNK06-SYSTEM-VPN-FIX-20261014.md)
- [领克 06 车测说明](LYNKPLAY-CAR-TEST.md)
