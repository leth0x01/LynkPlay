# BX11 导航协议与访问边界离线复核

初始复核日期：2026-10-13。当前状态更新：2026-10-17。

## 当前结论

离线复核覆盖 XSFNaviService、ECARX AdaptAPI 和 DIM 模式管理器。2026-10-16 实车已确认 LynkPlay 在物理仪表显示 CarPlay 路线，因此本文重点保留协议角色和边界，不再把“能否显示”作为纯离线推测。

当前实现直接使用车机运行时提供的 `DimInteraction` / `INaviInteraction` / `IDimMenuInteraction`：发送导航状态、Turn-by-Turn、投影声明、模式请求和完整路线卡片。EAS 同步协议 3902 不承担仪表焦点接管，不需要继续通过重复调用 3902 解决地图显示。

## 复核范围

- XSFNaviService 的 OpenNaviAdapt、EASNaviAdapt 和同步协议分发。
- `NaviDimPresentationManager` 对 4100、4101、4102 的处理。
- 原车 `NaviDashboardManager` 向 AdaptAPI 发布状态和完整导航信息的行为。
- 10 月 13 日焦点探针结果与 10 月 16 日 LynkPlay 实车诊断。

部分 Compact DEX 方法只能恢复到指令级或不完整 Java；未成功还原的控制流不作为确定结论。

## 3902：同步返回输入，不接管仪表焦点

调用链：

```text
OpenNaviAdapt.invokeNaviAPI(TransferModel)
  → mAPIManager.b(NaviBaseModel)
  → ecarx.naviservice.g.d.b → super.b
  → ecarx.naviservice.g.l.b
  → protocolID == 3902 → return reqModel
```

EAS 的 `callMaster("invokeNaviAPI", ...) → getSyncResult()` 也进入同一同步管理器。该分支返回输入模型，没有读取 `focusMapVendor` / `focusMapPackage`，也没有更新 DIM 地图归属。

第三轮探针调用曾到达服务，并触发 `onNaviFocusChanged`，但随后出现 cancelNavi / HiCar 焦点丢失，+1/+3/+6 秒的 `getNaviMode()` 都为 1，物理仪表仍未显示。该结果说明重复申请焦点不是有效优先方向。

## 4100、4101、4102 的不同角色

| 值 | 常量 | 同步管理器处理 |
| --- | --- | --- |
| 4100 | `NAVI_DIM_DISPLAY_MODE` | 没有专门切换分支，进入地图代理 |
| 4101 | `RSP_DIM_DISPLAY_MODE` | 接收地图侧显示模式并更新记录与扩展信息 |
| 4102 | `NAVI_DIM_SWITCH_MODE` | 处理仪表模式切换请求 |

4100 的默认分支不能替代 4102。构造 `DimDisplayMode` 后调用普通同步入口，不等于已经请求仪表切换。

### 4102：切换请求

- 读取 `mapVendor`。
- 10、-2、-1 等非具体 vendor 会替换为当前地图记录的 vendor。
- 具体 vendor 必须与当前地图归属一致，否则返回 10054 `RESULT_DIM_NOT_PRESENTATION`。
- 条件满足后转换到 AdaptAPI 菜单模式，最终调用 `DimMenuInteraction.switchNaviMode`。

当前 LynkPlay 直接调用已核对的 AdaptAPI `switchNaviMode(FULL)`；车机仍可拒绝，结果只作为诊断，不冒充物理显示。

### 4101：地图侧模式更新

- 具体 vendor 不匹配时返回非法参数或不更新当前状态。
- FULL / AR 只在地图归属与 widgetMapVendor 一致时应用。
- OFF / SIMPLIFY 同样检查当前 DIM 地图归属。
- 生效后通过 `EXT_KEY_DIM_DISPLAY_MODE` 向 AdaptAPI 发布模式。

### 模式映射

| SDK 模式 | AdaptAPI 菜单模式 | 扩展动作 |
| --- | --- | --- |
| OFF=0 | OFF=1 | 33 |
| SIMPLIFY=1 | SIMPLIFY=2 | 34 |
| FULL=2 | FULL=3 | 35 |
| AR=3 | 4 | 36 |

## NaviService Binder 入口

`NaviService.onBind` 按 action/category 提供不同 Binder：

- `ecarx.intent.action.NAVI_SERVICE_STARTED` + `ecarx.intent.category.NAVI` → OpenNaviAdapt。
- 同 action + `ecarx.intent.category.NAVI_INNER` → InnerSDKAdapt。
- `ecarx.naviservice.intent.action.EAS_SUPPORT_SERVICE` 或 `com.ecarx.eas.core.intent.action.SUPPORT_SERVICE` → EASNaviAdapt。

直接 NaviService 入口受固件权限限制。接口存在、类可加载或某次同步返回成功，都不能单独证明调用方可以使用全部导航模块。

当前 LynkPlay 的实车成功路径是车机运行时 AdaptAPI 链路；本文不把它等同于已独立确认 EAS 的全部模块访问能力。

## 原厂导航数据输出

`NaviDashboardManager.a(NavigationInfoToDim)` 调用 `updateNavigationInfo` 发布完整对象。mapStatus 分支中：

- 16 / 33 → `notifyNavigationStatus(1)`
- 17 / 35 → `notifyNavigationStatus(0)`
- 9001 / 9002 / 9003 → 4 / 5 / 6

这为 LynkPlay 使用 1 表示导航进行、0 表示结束提供了本车代码依据。该函数没有显示其他平台常见的 START=2 → SUCCESS=1 序列，因此不把其他平台顺序套用到 BX11。

当前 LynkPlay 已发布：

- 转向 ID。
- 下一提示距离和剩余距离，单位为米。
- 剩余时间，单位为秒。
- 道路名称。
- 状态为 0 的结束卡片。

Apple 驾驶侧不作为车辆航向；未解码的车道、高速出口、摄像头和服务区信息保持为空。

## 与实车结果对照

2026-10-16 应用报告记录：

- `adapterConnected=true`
- `menuAttached=true`
- `naviAttached=true`
- `navigationSent=true`
- `lastNotifySuccess=true`
- `switchFullModeAccepted=true`
- `fullNavigationInfoSent=29`
- `mapVisible=true`
- `streamActive=true`

用户同时确认物理仪表显示 CarPlay 路线。`getNaviMode()` 当时仍为 1，进一步证明该 getter 不应作为唯一可见条件。

## 后续复核

1. 在 0.2.4 上记录导航开始、模式请求、完整卡片、原车 MCU open、结束清理和原车导航恢复的同一时间线。
2. 被动采集原车导航“简洁 → 三指投影 → 菜单切回 → 结束”的模式事件，不通过未知事务号写状态。
3. 对照道路名称、转向、距离和 ETA 的物理显示，区分协议发布成功与内容显示正确。
4. 记录服务重连和异常退出后的持久清理，不以一次成功导航代替恢复验证。

相关显示诊断见[领克仪表地图显示依据与诊断](LYNK06-MAP-VISIBILITY-DIAGNOSTICS-20261015.md)。
