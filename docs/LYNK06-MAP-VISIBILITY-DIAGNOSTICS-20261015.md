# 领克仪表地图显示依据与诊断

初始实现日期：2026-10-15。当前状态更新：2026-10-17。

## 判断链路

### iPhone 导航请求

`FceNavigationState` 从 iAP2 RouteGuidanceUpdate 读取状态字段 `0x01` 和来源字段 `0x13`。状态 1、3、4、5、6 表示导航进行；明确为空的来源会结束导航；尚未收到状态字段时，非空来源作为导航依据。`CarPlayNavigationStatus` 将变化传给 `Lynk06ClusterLink.setNavigation()`。

### 应用地图窗口

`Lynk06ClusterState.showMap` 使用 `available && navigating`，不使用 `getNaviMode()`。导航结束先在主线程隐藏窗口，再由厂商接口 worker 清理导航状态、Turn-by-Turn、路线卡片和投影声明。

`ClusterMapPresentation.setMapVisible()` 在 BX11 上同时设置 decorView 的 VISIBLE/INVISIBLE 与窗口 alpha，避免 Android 9 SurfaceView 独立图层继续覆盖原车界面。Presentation 生命周期、窗口、Surface 和视频流分别记录。

`appMapReady=true` 只表示应用窗口与输出条件齐备，不表示物理仪表已显示。

### 原车显示器与 getter

`DashboardMapDisplay` 在未导航、简洁提示、地图模式和退出导航时都持续存在，不能用“显示器存在”判断地图。

历史实车中地图显示和消失时 `getNaviMode()` 都出现过 1；本车 `getShowPresentationOption()` 返回 0。当前实现只记录这两个值，不以它们控制地图窗口。

### 原车投影门控

本车 `ECarXScreenProjectionService` 会在以下状态拒绝投影：

- 恢复出厂流程进行中。
- 原车认为导航尚未启动。
- IHU 状态为 25。
- LCD 状态为 2、3、4、5。

允许后，start=true 会请求 MCU 属性 `0x2870006c` 写入 1，并记录 `INFO_ID_MMI_NAVSYNCDISPLAY open`；start=false 写入 2 并记录 close。

诊断分别保存门控判断、start/stop 请求和 MCU open/close。start 请求不等于 MCU open，MCU open 也不等于物理仪表像素回执。

## 自动诊断

每次独立状态变化生成 `mapRevision`。以下事件都附带该版本及应用输出摘要：

- 导航请求、路线卡片和有效帧统计。
- AdaptAPI 创建、连接、菜单/导航接口、回调和重连。
- 投影声明、模式请求、Turn-by-Turn 和结束清理。
- Presentation、窗口可见性、alpha、displayId、Surface 和视频流。
- 封面登记/读取、原车歌曲列表、播放信息与媒体按键。
- 原车 Power 与 ScreenProjection 日志。

原车日志从本次订阅开始读取，不把上次会话最后一行当作当前状态。重复状态更新时间但不重复刷记录；新链路不会被旧窗口迟到的 close 覆盖。

## 2026-10-16 实车结果

应用报告记录：

```text
navigationActive=true
routeGuidanceFrames=38
validNavigationFrames=36
adapterConnected=true
menuAttached=true
naviAttached=true
presentationShowing=true
mapVisible=true
streamActive=true
selectedCluster=1:DashboardMapDisplay
```

原车门控摘要为 `factoryReset=false`、`naviNotStarted=false`、`ihu=17`、`lcd=0`，并观察到 MCU open。用户确认物理仪表显示 CarPlay 路线，并在切换下一曲后地图继续正常。

应用快照和 MCU 日志是链路证据；物理显示结论来自用户观察，二者不互相替代。

## 当前待验证

- 0.2.4 导航开始、结束和原车恢复的完整闭环。
- 简洁/地图菜单操作对应的回调原值。
- 长时间导航、反复启停、倒车和前后台切换。
- 异常退出、服务重连和下次启动时的持久清理。
- 无线 CarPlay 下的仪表显示稳定性。

实车系统与实现总览见[系统仪表版本](LYNK06-CLUSTER-SYSTEM-BUILD-20261013.md)。
