# BX11 系统 UID 有线 CarPlay VPN 修复

初始修复日期：2026-10-14。当前状态更新：2026-10-17。

## 问题

早期独立系统包实际运行 UID 为 1000，USB 权限和 iAP2 通道正常，但 Android 9 的 `VpnService.establish()` 返回 null。系统日志显示准备状态仍归属于 `[Legacy VPN]`，后续 AppOps 校验无法把它识别为当前应用。

Android 9 的 `Vpn.isCurrentPreparedPackage()` 在该路径只比较 UID；Legacy VPN 的 owner UID 与当前应用同为 1000，系统会误判为已经准备完成，而没有更新实际归属包。

## 修复

`SystemUidVpnPreparation` 只在以下条件全部满足时进入兼容路径：

- Android 9。
- 当前进程实际 UID 为 1000。
- 具备 `CONTROL_VPN`。
- 首次 `establish()` 返回 null。
- 没有现有 VPN 配置。
- 没有 always-on VPN。

兼容路径先通过系统接口切换到中间归属，再在 `finally` 中恢复当前应用包名，从而绕过“相同 UID 即已准备”的短路。中间归属不建立隧道；第二次 `establish()` 仍执行 Android 的正常权限和应用范围检查。任何切换或恢复失败都进入原有清理流程，不报告成功。

## 实车结果

### 2026-10-14

系统日志记录：

```text
[Legacy VPN] → com.android.shell → com.leth0x01.lynkplay.clustersystem
Established ... on tun0
```

早期独立包随后成功建立有线 CarPlay，会话视频约 30 fps，诊断中 `recoveries=0`、`late=0`。一次 USB 读取失败后重新连接成功。

### 2026-10-16

当前统一包名 `com.leth0x01.lynkplay` 的 LynkPlay 0.2.3（54）在实车上仍为 UID 1000。有线 CarPlay 连接成功，用户确认画面与声音正常，物理仪表显示 CarPlay 路线。

该结果确认统一包继续使用修复后的 VPN 路径。当前发布版为 0.2.4（55），尚无新增 VPN 实车采集。

## 自动化验证

回归覆盖：

- 同 UID 导致准备短路后的恢复。
- 中间切换异常时恢复当前包。
- 切换或恢复被拒绝时不得报告成功。
- 已有 VPN 或 always-on VPN 时不进入兼容路径。
- 仪表结束通知后的异步清理完成。

当前车测与正式版本统一使用 `release` 构建，不再维护独立 `clusterSystem` 构建类型。

## 后续复核

1. 0.2.4 冷启动首次有线连接。
2. 应用内断开后的 USB 重连。
3. 多次物理 USB 插拔。
4. 覆盖升级后连接。
5. 熄火后上电及已有 VPN/always-on VPN 的保护路径。
