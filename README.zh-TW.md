# LynkPlay

基於 [DiPlay](https://github.com/shihabal3amri/DiPlay) 的 CarPlay 接收器，針對 **2023 款領克 06 燃油版、Android 9 車機**維護。[简体中文](README.zh-CN.md) · [English](README.md)。

目前 LynkPlay 版本為 **0.2.6**（versionCode 57）。本分支原始碼現已同步 DiPlay 0.2.21，新增內容見[上游版本說明](docs/RELEASE-NOTES-0.2.21.md)。

已有實車回饋確認無線、有線 CarPlay 均能在目標車機上連線。舊版斷開車機藍牙後，方向盤按鍵無法控制音樂。本版已接入原車媒體中心。2026-10-16 在 2023 款領克 06 上的實車採集已確認媒體按鍵、語音鍵喚起 Siri、儀表顯示 CarPlay 地圖與 USB 插拔恢復，詳見[驗證記錄](docs/LYNKPLAY-VALIDATION.md)。

## 安裝與建置

- 主應用程式模組：`mobile`。
- 套件名稱：`com.leth0x01.lynkplay`，使用 BX11 平台簽章並宣告 `android.uid.system`，維持既有授權體驗及覆蓋升級相容性。
- 2026-10-23 實車確認：在 BX11 / IHU514G、762 韌體上，不使用系統 UID 也能顯示儀表地圖。系統 UID 影響權限處理，並非儀表地圖的必要條件；本次驗證仍保留平台簽章。授權差異及證據見[驗證記錄](docs/LYNKPLAY-VALIDATION.md)。
- Debug 套件名稱：`com.leth0x01.lynkplay.hudtest`。
- 可與 DiPlay 共存，各自儲存資料與權限；設定可透過備份／還原移轉。
- 診斷報告儲存至 `Downloads/LynkPlay`，車機限制可能使其改存應用程式專用目錄。

依[建置指南](docs/BUILD.md)準備 JDK 25 與 Android SDK，再執行：

```powershell
.\gradlew.bat :mobile:assembleDebug
```

APK 位於 `mobile/build/outputs/apk/debug/mobile-debug.apk`。一般原始碼建置預設不包含執行時認證素材；車測包的平台簽章、認證輸入及建置流程請見[車測說明](docs/LYNKPLAY-CAR-TEST.md)。

車測包使用平台簽章建置：

```sh
uv run scripts/build_lynkplay_car_test.py
```

腳本執行 `:mobile:assembleRelease`，核驗平台憑證指紋、`com.leth0x01.lynkplay` 套件名稱、`android.uid.system` 宣告及認證素材，輸出 `mobile/build/outputs/apk/release/mobile-release.apk`。

後續實車驗證重點為聲音異常、連線穩定性，以及 Siri、方向盤按鍵、全螢幕和開機啟動；儀表地圖已在平台簽章的車測包上實車顯示。測試時只執行一款接收器應用程式。

## 更新與上游同步

LynkPlay 檢查[本 fork 的 Releases](https://github.com/leth0x01/LynkPlay/releases)，也可進入 **設定 → 關於 → 檢查更新**，下載並驗證 APK 後安裝。正式更新包沿用長期簽章與認證素材：

```sh
uv run --locked scripts/build_lynkplay_release.py
```

完整流程請見[正式出包與更新發布](docs/BUILD.md#build-a-release-apk)。`main` 保留 DiPlay 上游，`lynkplay` 維護品牌與領克 06 調整；目前已合併 DiPlay 0.2.21（`6d3b8a8c`）。上游同步指令請見[簡體中文首頁](README.zh-CN.md#更新与上游同步)。

本次更新改善應用程式內安裝，不再依賴 ADB，並在安裝前明確提示應用程式結束及 CarPlay 中斷。舊版若提示「剖析失敗」，請用車機檔案管理員開啟下載的 APK 覆蓋安裝，無需解除安裝。覆蓋升級已在模擬器驗證，Android 9 目標車機仍待複驗。詳見 [LynkPlay 0.2.6 版本說明](docs/LYNKPLAY-RELEASE-NOTES-0.2.6.md)與[驗證紀錄](docs/LYNKPLAY-VALIDATION.md)。

## 來源與授權

LynkPlay 衍生自 DiPlay，接收器源自 xcertplay，介面採用 DiAuto 設計。保留原專案版權、授權與貢獻聲明，詳見 [LICENSE](LICENSE)、[第三方聲明](docs/THIRD_PARTY_NOTICES.md)及[上游繁體中文 README](docs/DIPLAY-README.zh-TW.md)。上游文件中的車型支援與發布連結屬於 DiPlay。

CarPlay 與其圖示屬於 Apple Inc.；此衍生專案不表示獲得 Apple 或領克官方認證。
