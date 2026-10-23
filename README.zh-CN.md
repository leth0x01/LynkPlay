# LynkPlay

基于 [DiPlay](https://github.com/shihabal3amri/DiPlay) 的 CarPlay 接收器，面向 **2023 款领克 06 燃油版、Android 9 车机**维护。

当前 LynkPlay 版本为 **0.2.6**（versionCode 57）。本分支源码现已同步 DiPlay 0.2.21，新增内容见[上游版本说明](docs/RELEASE-NOTES-0.2.21.md)。

已有实车反馈确认无线、有线 CarPlay 均能在目标车机上连接。旧版断开车机蓝牙后，方控无法控制音乐。本版已接入原车媒体中心。2026-10-16 在 2023 款领克 06 上的实车采集已确认媒体方控、语音键唤起 Siri、仪表显示 CarPlay 地图与 USB 插拔恢复，详见[验证记录](docs/LYNKPLAY-VALIDATION.md)。

本次更新改善应用内安装，不再依赖 ADB，并在安装前明确提示应用退出及 CarPlay 中断。旧版若提示“解析失败”，请用车机文件管理器打开下载的 APK 覆盖安装，无需卸载。覆盖升级已在模拟器验证，Android 9 目标车机仍待复验。详见 [LynkPlay 0.2.6 版本说明](docs/LYNKPLAY-RELEASE-NOTES-0.2.6.md)、[车测说明](docs/LYNKPLAY-CAR-TEST.md) 和 [验证记录](docs/LYNKPLAY-VALIDATION.md)。

## 安装与构建

- 主应用模块：`mobile`。
- 安装标识：`com.leth0x01.lynkplay`，使用 BX11 平台签名并声明 `android.uid.system`，维持既有授权体验和覆盖升级兼容性。
- 2026-10-23 实车确认：在 BX11 / IHU514G、762 固件上，不使用系统 UID 也能显示仪表地图。系统 UID 影响权限处理，不是仪表地图的必需条件；本次验证仍保留平台签名。授权差异与证据见[验证记录](docs/LYNKPLAY-VALIDATION.md)。
- Debug 安装标识：`com.leth0x01.lynkplay.hudtest`。
- 可与 DiPlay 共存，私有数据与权限独立；原版设置不会自动迁移，可使用现有备份／恢复功能。
- 保留上游内部 namespace、类型及资源键，方便后续合并更新。
- 诊断报告保存到 `Downloads/LynkPlay`；车机限制可能触发应用专用目录回退。

按 [构建说明](docs/BUILD.md) 准备 JDK 25 和 Android SDK，再执行：

```powershell
.\gradlew.bat :mobile:assembleDebug
```

APK 位于 `mobile/build/outputs/apk/debug/mobile-debug.apk`。普通源码构建默认没有运行时配件身份；仅编译成功不足以连接 iPhone。本地 standalone 构建流程见构建说明，不应提交认证资源或签名密钥。

车测包使用平台签名构建：

```sh
uv run scripts/build_lynkplay_car_test.py
```

脚本执行 `:mobile:assembleRelease`，核验平台证书指纹、`com.leth0x01.lynkplay` 包名、`android.uid.system` 声明及认证素材，输出 `mobile/build/outputs/apk/release/mobile-release.apk`。

后续实车验证重点为声音异常、连接稳定性，以及 Siri、方向盘按键、全屏和开机启动；仪表地图已在平台签名的车测包上实车显示。Android 9 的 Wi-Fi Direct 限制见 [说明](docs/ANDROID9_WIFI_DIRECT.md)。测试时只运行一款接收器应用，避免同时占用连接和音频。

## 更新与上游同步

LynkPlay 检查 [本 fork 的 Releases](https://github.com/leth0x01/LynkPlay/releases)。默认在后台检查更新，也可进入 **设置 → 关于 → 检查更新**，下载并校验 APK 后点击安装。

正式更新包使用平台签名和认证素材，在仓库根目录执行：

```sh
uv run --locked scripts/build_lynkplay_release.py
```

脚本执行完整 Android CI 检查和正式构建，核验 APK 后生成 `mobile/build/outputs/publish/<版本>/LynkPlay-<版本>.apk` 与 `SHA256SUMS.txt`。每次更新都递增 `gradle.properties` 中的版本名称和版本代码，将这两个附件一起上传到对应版本的 GitHub Release。完整流程见 [正式出包与更新发布](docs/BUILD.md#build-a-release-apk)。

- `main`：保留 DiPlay 上游。
- `lynkplay`：品牌修改和领克 06 适配。
- 初始基线：`24ae1fa5`（DiPlay 0.2.18）。
- 当前已合并基线：`6d3b8a8c`（DiPlay 0.2.21）。

提交或暂存当前修改后，同步上游：

```powershell
git fetch upstream
git switch main
git merge --ff-only upstream/main
git push origin main
git switch lynkplay
git merge main
```

解决冲突并执行完整检查后，再推送 `lynkplay`：

```powershell
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest :mobile:lintDebug :home:lintDebug :maphost:lintDebug :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
git push origin lynkplay
```

CI 同时检查 `main` 和 `lynkplay` 的 push，以及 pull request。

## 来源与许可证

LynkPlay 是 DiPlay 的派生项目，接收器源自 xcertplay，界面采用 DiAuto 设计。保留原项目版权、许可与贡献说明，详见 [LICENSE](LICENSE)、[第三方许可声明](docs/THIRD_PARTY_NOTICES.md)、[上游中文 README](docs/DIPLAY-README.zh-CN.md) 和 [上游英文 README](docs/DIPLAY-README.md)。上游 README 中的车型支持、名称及发布链接属于原项目。

CarPlay 名称和图标归 Apple Inc. 所有。此派生项目不表示获得 Apple 或领克官方认证。
