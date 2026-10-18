# 领克 06 原车仪表联动调研

初始调研日期：2026-10-12。当前状态更新：2026-10-17。

## 当前结论

2023 款领克 06 燃油版使用 BX11 / IHU514G / xecarx / Android 9。仪表地图链路由三个部分组成：

1. Android `DisplayManager` 暴露的 `DashboardMapDisplay`，用于承载 CarPlay 独立地图视频流。
2. ECARX AdaptAPI 的 `DimInteraction`、`IDimMenuInteraction` 和 `INaviInteraction`，用于声明导航、投影来源、Turn-by-Turn 和路线卡片。
3. 原车 Power / ScreenProjection 服务，负责车辆状态门控和 MCU 投影开关。

2026-10-16 用户已确认物理仪表显示 CarPlay 路线；该结果不再只是接口推测。应用诊断同时记录 `adapterConnected=true`、`mapVisible=true`、`streamActive=true`、38 个路线帧和 36 个有效帧。

## 车机与显示

| 项目 | 采集结果 |
| --- | --- |
| 设备 | product/device=`IHU514G`，model=`BX11`，hardware=`xecarx` |
| 系统 | Android 9 / API 28，`SW0BX110520H8EHH.00762` |
| 主屏 | 1920 × 720 / 160 dpi / 60 Hz |
| 仪表显示 | `DashboardMapDisplay`，displayId 1，1920 × 720 / 213 dpi / 30 Hz |
| AdaptAPI | 1.1.18.9 |

六个原车仪表阶段中，`DashboardMapDisplay` 始终存在，因此显示器存在不能作为地图模式或物理可见性的判据。

## 已核实接口

| 目标 | 接口 |
| --- | --- |
| 连接生命周期 | `DimInteraction.create`、`IConnectable` watcher、connect/disconnect |
| 导航状态 | `notifyNavigationStatus`、`notifyTurnByTurnStarted`、`notifyTurnByTurnStopped` |
| 路线信息 | `updateNavigationInfo` |
| 仪表模式 | `getNaviMode`、`switchNaviMode`、`onChangeNaviMode` |
| 投影来源 | `setPresentationToDimSwitch`、`getShowPresentationOption` |
| 扩展模式 | `updateExtensionInfo(EXT_KEY_DIM_DISPLAY_MODE)` |

原车导航进行和结束分别使用 `notifyNavigationStatus(1)` 与 `notifyNavigationStatus(0)`。原车导航还注册导航交互回调与顶层交互回调，并发布完整 `NavigationInfoToDim`。

## 原车操作与日志

原车开始导航后先出现简洁箭头提示；三指向左划可请求地图投影，仪表菜单可切回简洁模式，退出导航结束显示。

历史日志中，三指左划记录：

- `ECarXScreenProjectionService.startScreenProjection start=true`
- 随后 `INFO_ID_MMI_NAVSYNCDISPLAY open`

原车 Power 服务会检查恢复出厂状态、导航是否启动、IHU 状态和 LCD 状态。收到 start 请求不等于 MCU 已执行 open，MCU open 也不等于物理像素回执。

实车地图显示和消失时 `getNaviMode()` 都曾为 1；`getShowPresentationOption()` 在本车实现中固定返回 0。因此当前实现不再用这两个 getter 决定应用地图窗口是否显示，只将它们作为诊断信息。

## 当前 LynkPlay 实现

- 只有完整匹配 IHU514G / BX11 / xecarx / API 28 时才初始化领克仪表链路。
- 显示必须有效、带 Presentation 标记、名称和尺寸精确匹配，并排除主屏。
- CarPlay 真实导航状态与路线提示分别管理；导航请求结束时先隐藏窗口，不等待厂商 Binder 清理。
- 厂商调用运行在独立线程，连接失败按 1、2、4、8 秒退避；连续事务失败后重建连接。
- 服务重连后重新取得菜单和导航接口、重新注册回调，并重新发布当前导航状态。
- 导航开始时声明投影来源、发送状态 1、Turn-by-Turn 开始、请求地图模式，并发布完整路线卡片。
- 路线卡片包含转向、下一提示距离、剩余距离、剩余秒数和道路名称；缺少真实数据时不编造卡片。
- 完整卡片发送后尝试解除原车投影门控。
- 导航结束发送状态为 0 的结束卡片、状态 0、Turn-by-Turn 停止并撤销投影声明。
- 各清理项在调用前持久记录；崩溃或重连后先补偿清理，再接管新会话。
- 应用窗口在 BX11 上同时控制 visibility 与 alpha，避免 Android 9 SurfaceView 图层残留。

## 诊断与证据分层

应用报告分别记录：

- iPhone 导航请求与有效路线帧。
- AdaptAPI 创建、连接、菜单/导航接口、回调和重连。
- 应用 Presentation、窗口可见性、alpha、Surface 与视频流。
- 原车投影门控、start/stop 请求与 MCU open/close。
- 原车媒体封面和按键事件所处的地图状态版本。

`appMapReady=true` 只表示应用输出条件齐备；物理仪表可见性仍需人工观察。2026-10-16 的“物理仪表显示 CarPlay 路线”来自用户确认，并有应用快照作为链路旁证。

## 采集脚本

`scripts/collect_lynk06_cluster.ps1` 可分阶段采集原车导航、三指投影、仪表菜单、方向盘、媒体和语音日志。脚本支持从指定阶段继续，并在 ADB 离线时停止推进。

早期 `lynk06_cluster_20261012_154433` 在第 1–14 阶段有完整前后标记，第 15 阶段起 ADB 离线；后续结论同时使用 10 月 13 日仪表资料和 10 月 16 日 `lynk06-field` 只读采集，不把缺失阶段补写为已执行。

## 当前待验证

- 0.2.4 导航开始、结束与原车恢复的完整闭环。
- 简洁/地图菜单操作与当前实现回调的逐项对应。
- 长时间导航、反复启停、倒车和前后台切换。
- 熄火后上电及异常退出后的持久清理恢复。
- 无线 CarPlay 下的仪表地图稳定性。

协议分发细节见[BX11 导航协议与访问边界离线复核](LYNK06-CLUSTER-OFFLINE-20261013.md)，显示判据见[领克仪表地图显示依据与诊断](LYNK06-MAP-VISIBILITY-DIAGNOSTICS-20261015.md)。
