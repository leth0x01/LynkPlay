# 按连接模式自动开启车机热点 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 打开/返回 LynkPlay、切换到车机热点及开启自动热点开关后，按模式自动准备车机热点。

**Architecture:** 保留现有自动热点开关、权限引导和 `CarHotspotTethering` 的平台/ADB 启动路径。在 common 层增加统一的异步准备器，以独立任务标识处理取消、去重与过期结果；Activity 的生命周期与配置操作调用同一入口。连接控制器继续负责真正连接前的热点就绪检查，不主动重连现有会话。

**Tech Stack:** Kotlin、Android、SharedPreferences、Robolectric、JUnit、Gradle。

---

## 现状与设计

- `DiPlayActivity.startCarHotspotOnLaunch()` 仅在首次启动、蓝牙启动及特定权限返回路径调用。
- `applyWirelessLink()` 保存模式后仅取消旧热点任务，没有启动新任务。
- 两种自动热点开关的保存回调均未启动热点。
- 启动判断仅使用上次 transport，未考虑 `DefaultConnectionMode`；共享取消布尔值也不能隔离快速切换时的旧任务。
- Host 菜单保存通过 `persistMenuSettings()` 落盘，实际重连仍经过 `CarPlayController.startWirelessHotspot()`；保留该连接前兜底。

### 行为规则

| 场景 | 行为 |
| --- | --- |
| 打开/返回应用 | 使用默认连接方式（跟随上次、无线、USB）及保存的无线子模式判断 |
| 保存车机热点模式 | 作为明确的无线模式选择，立即检查准备条件，不受上次 USB 记录影响 |
| 保存其他无线模式 | 取消尚未完成的预准备任务 |
| 开启自动热点 | 立即重新判断；授权返回后再次判断 |
| USB 后显式选择热点，再前往授权页面 | 保留本次无线选择及 Activity 恢复状态，返回后继续准备；开始连接或修改默认模式后清除此临时选择 |
| 关闭自动热点、离开 Activity、开始连接、执行热点修复 | 取消预准备；开始连接后由控制器接管 |
| 已有 CarPlay 会话 | 不预准备、不停止会话，模式变更显示现有待重连提示 |
| 已开启热点 | 底层状态检查返回 READY，不重复发起平台启动 |

沿用 opt-in 和已保存热点凭据校验，不更改用户原有开关选择。预准备只启动车机已保存热点，不写配置、不自动关闭热点、不循环重试。取消不能撤销系统已经接受的启动请求，但可阻止后续步骤及旧结果覆盖新状态。

## Task 1: 统一条件与异步准备器

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarHotspotSetup.kt`
- Create: `common/src/main/java/com/shilapi/xcertplay/CarHotspotPreparation.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/CarHotspotSetupTest.kt`
- Create: `common/src/test/java/com/shilapi/xcertplay/CarHotspotPreparationTest.kt`

1. 增加失败测试：默认 USB 不启动、默认无线覆盖上次 USB、显式无线切换覆盖上次 USB。
2. 将共同条件提取为 `shouldPrepare(context, wireless, hasSession)`；启动判断使用 `DiPlayPreferences.autoConnectWireless()`。
3. 准备器接收资格检查、启动、结果派发函数；`request()` 合并进行中请求，`cancel()` 使旧任务失效；每次执行传入独立取消判断。
4. 用 latch 和可控派发验证去重、取消、快速切换、过期结果及失败后重新请求。
5. Run: `./gradlew :common:testDebugUnitTest --tests '*CarHotspotSetupTest' --tests '*CarHotspotPreparationTest'`。

## Task 2: 接入生命周期与模式操作

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/HomeHotspotAccessTest.kt`

1. 补充场景测试：已有权限时开启自动开关、Wi-Fi/USB 切热点、热点关闭后返回应用、切回非热点模式。
2. 所有预准备入口复用准备器；`onResume()` 每次检查；`onStop()`、连接接管和热点修复取消任务。
3. 自动热点开关保存后立即检查；`applyWirelessLink()` 使用明确无线选择判断，并调用 `markReconnectNeeded()`，不主动断开现有会话。
4. 默认连接模式保存后重新判断。
5. Run: `./gradlew :common:testDebugUnitTest --tests '*HomeHotspotAccessTest' --tests '*CarHotspotSwitchTest' --tests '*DefaultConnectionModeTest'`。

## Task 3: 更新说明与验证

**Files:**
- Modify: `common/src/main/res/values*/strings.xml` 中既有两项自动热点说明。

1. 各语言说明补充返回应用、切换车机热点模式及打开开关时执行的行为，明确仅无线车机热点模式生效。
2. Run: `git diff --check`。
3. Run CI:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
```

4. 实车验收：开机进入热点模式后自动开启；USB/Wi-Fi 切热点自动开启；切回其他模式不额外开热点；缺权限能引导；已运行会话不因保存模式而断开。仅记录实际执行的验证结果。

## 执行结果

- 已实现统一准备器、生命周期检查、模式/开关变更触发、默认连接方式判断和授权往返的显式选择恢复。
- 已更新全部 9 个资源目录中的既有自动热点说明。
- 新增测试先复现返回应用、USB/Wi-Fi 切换、开关立即生效及 USB 后授权返回问题，再验证修复。
- 完整 CI 命令通过，三个 Lint 与三个 Debug 构建通过。
- 补充 Android 9 / API 28 覆盖及已有会话保护后，最终热点/默认模式定向测试 100 项通过，0 失败；其中 Home 场景在 API 28、29 各执行 11 项。
- `git diff --check` 通过。

实车固件上的热点开启效果仍待验收。
