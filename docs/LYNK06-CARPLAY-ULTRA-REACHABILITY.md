# CarPlay Ultra 协议可达性调研（2026-10-19）

## 结论

**在本车现有身份与实现下，无法获得真正的 CarPlay Ultra。** 证据来自 iPhone 自己的能力声明，而不是推测。

iPhone 在 AirPlay `/info` 请求中只列出三项仪表内容：

```
altScreenURLs=[maps:/car/instrumentcluster/map,
               maps:/car/instrumentcluster/instructioncard,
               maps:/car/instrumentcluster]
```

三份独立采集（2026-10-16 两次、2026-10-19 一次）内容完全一致。LynkPlay 已全部实现这三种内容，并已确认地图能在物理仪表上全幅显示。

**iPhone 没有声明任何额外能力**：没有车速表、转速表、挡位或警告灯的渲染入口，也没有 Ultra 专属字段。这解释了实测现象——地图铺满背景，但车速、转速、挡位、警告灯仍由原车绘制。

## Ultra 与普通 CarPlay 的差别在这里

| | 普通 CarPlay（本车现状） | CarPlay Ultra |
| --- | --- | --- |
| 仪表内容 | iPhone 提供地图 / 转向卡 / 仪表视图三个 URL | iPhone 接管整块仪表，含车速表、转速表等 |
| 车辆数据 | 车机自绘叠层，iPhone 不参与 | iPhone 读取车辆信号并渲染 |
| 前置条件 | iAP2 + AirPlay + MFi 身份 | 同上，另加 Apple 与车厂的双边 Ultra 授权 |

LynkPlay 用的是离线 MFi 实验身份，不在 Apple 的授权车机列表内。即使修改 feature 位或伪造声明，iPhone 侧也没有对应的渲染通道——它不会因为车机声称支持就下发 Ultra UI。

## 已验证的协议面

- `extendedFeatures` 已声明 `vocoderInfo` 与 `enhancedRequestCarUI`（`AirPlayInfoPlist.kt`），iPhone 未因此返回额外 UI 请求。
- 主屏 display `features=10`（旋钮 + 高保真触摸），副屏 `features=0`，与仪表无触控的实际情况一致。
- iAP2 侧只有 `StartVehicleStatusUpdates` / `VehicleStatusUpdate` / `StopVehicleStatusUpdates` 三个车辆端点，用于电动车电量与续航，没有仪表布局或车辆 UI 描述通道。
- Apple 授权实现（IHU715P / Neusoft）的 `libcarplay.so` 中存在 `Enhanced Screen!`、`enhancedRequestCarUI`、`instrumentcluster/*` 等字符串，但没有出现 Ultra 标记；`enhancedRequestCarUI` 在 Ghidra 中无直接字符串交叉引用，未定位到独立处理函数。

## 实车对照：三种仪表内容

2026-10-19 用同一台车切换仪表内容后拍照对比：

| 内容 | iPhone URL | 实车结果 |
| --- | --- | --- |
| 地图 · 默认 | `maps:/car/instrumentcluster/map` | 地图铺满仪表背景 |
| **地图和官方转向卡** | `maps:/car/instrumentcluster` | 地图铺满，**左上角出现 iPhone 渲染的蓝色转向卡**，另有官方转向条 |
| 转向提示卡 | `maps:/car/instrumentcluster/instructioncard` | 未单独复核 |

关键观察：切到 `maps:/car/instrumentcluster` 后，左上角出现的是 iPhone 自己绘制的转向卡（含"进入无名道路"、距离与进度条），不是 LynkPlay 的自定义卡片。这说明 iPhone 在这个 URL 下确实多渲染了一层导航 UI。

但即使在这个最完整的模式下，车速、转速、挡位、警告灯、油量、里程仍由原车绘制——iPhone 没有接管它们。

这再次确认了边界：iPhone 提供的最强仪表内容 = 地图 + 转向卡，不含整车仪表。Ultra 所需的整表接管不在其中。

## 仍可推进的方向

真 Ultra 不可达，但车辆数据层已经实测可用（见[验证记录](LYNKPLAY-VALIDATION.md)），可以在普通 CarPlay 上实现接近 Ultra 的仪表体验：

1. **默认采用官方转向卡**：`maps:/car/instrumentcluster` 已验证能带来原生转向卡，无需自绘，也比自定义卡片更省资源。需评估转向卡与左侧"领航辅助"卡片的重叠。
2. **车辆信号叠加的必要性降低**：原车已绘制车速、转速、挡位、油量，自绘会重复。只有当需要把数据交给 iPhone 或其他 Launcher 卡片时才需要。
3. **驾驶状态提示**：用挡位、车速判断低速/倒车，控制地图与卡片的显示策略。

这三项都基于已验证的轮询通道，不依赖 Apple 授权。

## 建议

先做一次低成本验证：把仪表内容切到「地图和官方转向卡」，看 iPhone 的原生仪表视图是否已包含更多元素。若已包含，则无需自绘；若仍是地图加卡片，再评估自绘叠加的必要性。

不建议继续逆向 feature 位或伪造声明来追求 Ultra：没有授权与渲染通道，改动只会带来连接风险。
