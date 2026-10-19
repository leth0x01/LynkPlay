# 领克 06 车辆只读验证 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在 LynkPlay 自身进程、车型与权限条件下验证六个车辆信号的读取、订阅、失效与连接恢复，并提供 Windows 离线一键运行及报告回传。

**Architecture:** shared 中的纯 Kotlin 白名单、解码与状态模型；common 中独立的反射只读车辆后端、限时会话与受 DUMP 权限保护的诊断 Service；Windows ADB 脚本显式确认后安装既有 release 包、启动 60 秒验证并拉回该次 JSON。不给 CarPlay 控制器接入车辆数值，不新增设置或仪表 UI。所有平台密钥、认证素材及原车二进制保留在本地私有目录。

**Tech Stack:** Kotlin、Android Service / Handler、反射 android.car API、AtomicFile、JUnit / Robolectric、Windows PowerShell 5.1、现有 release 平台签名构建。

---

## 设计依据与边界

- 用户确认第二张仪表照片由 LynkPlay 投出，原车没有 Apple CarPlay 入口；已经具备全幅地图背景投影，不能据此推断原车叠层可独立接管。
- 2026-10-19 车辆快照与照片对上：43046 km、673 km、P、0 km/h。`0x287000b4` 是燃油类型，禁止作为剩余油量。
- 原厂 `android.car.Car` 有 `createCar(Context, ServiceConnection)`、`connect()`、`disconnect()`；CarPropertyManager 有 `getPropertyList()`、`getProperty(Class,int,int)`、`registerListener(CarPropertyEventListener,int,float)`、`unregisterListener(listener)`。
- 门禁复用 `VehicleIntegration.isDetectedLynk06()`，不使用构建目标作为运行时放行依据。先检查完整车型与 CAR_VENDOR_EXTENSION，再反射厂商框架。
- 只访问以下六个 propertyId，area 恒为 0；不读取 VIN、定位、账户或全量属性值；不包含 setProperty / ICarFunction 写入、系统设置修改、ADB 提权、iAP2 车辆上报或 CarPlay 重连调用。
- 诊断服务按需运行 60 秒，START_NOT_STICKY；不在应用启动、开机或 CarPlay 会话中自动启动。独立 Service 不打开 Activity、不改变中控前台。

## 字段与有效性

| key / 枚举 | propertyId | 字节数 | 输出 |
| --- | --- | --- | --- |
| odometer / ODOMETER | 0x287000a3 | 4 | unsigned little-endian ×0.1，km |
| range / RANGE | 0x287000a4 | 2 | unsigned little-endian，km |
| speed / SPEED | 0x287000a8 | 2 | unsigned little-endian ×0.01，km/h |
| gear / GEAR | 0x287000ec | 1 | 2=D、3=N、4=R、5=P，其余无效 |
| rpm_raw / RPM_RAW | 0x2870023f | 2 | 原始整数与十六进制；不宣告 r/min 或缩放 |
| fuel_raw / FUEL_RAW | 0x28700207 | 1 | 原始整数与十六进制；不宣告百分比或体积 |

- AVAILABLE 仅用于已经有转换依据的四个字段；候选字段即使状态可用也只输出 RAW_ONLY。
- 属性状态非 0、缺值、长度不符、全 FF、负时间戳等不能输出有效解码值。全 FF 是此验证器保守拒收规则，不声称已经核实 MCU 哨兵定义。
- 保留源 timestamp 与本地 elapsedRealtimeNanos；源 timestamp=0 只表示未知。有效性的新鲜度基于本地成功观察，不声称已经证明 MCU 缓存新鲜。
- 正常每秒主动读取一次；不能因为车辆静止、没有变化回调而误判信号失联。
- 超过 3 秒未成功观察标记 STALE；断开、停止、超时立即使当前解码值失效。历史保留当时观察值与当时状态，用于离线核对，不作为当前有效值。
- 两个正源时间戳倒序的观察不覆盖新值；明显未来时间戳（超过本地接收时间 1 秒）为错误。跨重连重置时间戳基线，旧连接的回调不能污染新代次。

## 共享模型 API（子代理之间的固定契约）

包：`com.shilapi.xcertplay.vehicle.probe`，位于 `shared/src/main/java/.../vehicle/probe/`。

```kotlin
enum class VehicleProbeSignal(
    val key: String, val propertyId: Int, val byteCount: Int, val unit: String?
) { ODOMETER, RANGE, SPEED, GEAR, RPM_RAW, FUEL_RAW }
enum class VehicleProbeSource { POLL, CALLBACK }
enum class VehicleProbeValueState {
    WAITING, AVAILABLE, RAW_ONLY, UNSUPPORTED, UNAVAILABLE, ERROR,
    STALE, DISCONNECTED, STOPPED
}
data class VehicleProbeObservation(
    val propertyId: Int,
    val areaId: Int,
    val status: Int,
    val sourceTimestampNanos: Long,
    val receivedElapsedNanos: Long,
    val rawBytes: ByteArray?,
    val source: VehicleProbeSource,
    val errorCode: String? = null
)
data class VehicleProbeReading(
    val signal: VehicleProbeSignal,
    val state: VehicleProbeValueState,
    val rawHex: String?, val rawUnsigned: Long?,
    val numericValue: Double?, val textValue: String?, val unit: String?,
    val sourceStatus: Int?, val sourceTimestampNanos: Long?, val observedElapsedNanos: Long?,
    val ageMillis: Long?, val readCount: Long, val callbackCount: Long,
    val errorCode: String?
)
data class VehicleProbeEvent(
    val reading: VehicleProbeReading,
    val source: VehicleProbeSource,
    val receivedElapsedNanos: Long
)
data class VehicleProbeSnapshot(
    val connected: Boolean, val stopped: Boolean,
    val readings: List<VehicleProbeReading>,
    val history: List<VehicleProbeEvent>, val droppedEvents: Long
)
class VehicleProbeStore(historyLimit: Int = 256, staleAfterNanos: Long = 3_000_000_000L) {
    fun markConnected()
    fun markDisconnected(errorCode: String = "disconnected")
    fun markUnsupported(signal: VehicleProbeSignal, errorCode: String = "unsupported")
    fun markError(signal: VehicleProbeSignal, errorCode: String)
    fun accept(observation: VehicleProbeObservation): Boolean
    fun stop(reason: String = "stopped")
    fun snapshot(nowElapsedNanos: Long): VehicleProbeSnapshot
}
```

状态模型内部同步，输入字节立即解码为不可变值，不把调用方可变 ByteArray 保存在历史中。snapshot 不增加计数。markConnected 在重连时清理旧值及源时间戳基线，但不丢弃累计历史和计数。停止后拒绝后续观察。

## Android 只读后端契约

包：`com.shilapi.xcertplay`；文件 `Lynk06VehicleProbeBackend.kt`。

```kotlin
internal interface Lynk06VehicleProbeBackend {
    fun connect(onConnected: () -> Unit, onDisconnected: () -> Unit)
    fun supportedPropertyIds(): Set<Int>
    fun read(propertyId: Int): VehicleProbeObservation
    fun subscribe(propertyId: Int, callback: (VehicleProbeObservation) -> Unit): Boolean
    fun close()
}
internal class ReflectiveLynk06VehicleProbeBackend(context: Context) : Lynk06VehicleProbeBackend
```

- 后端自身也执行完整车型、权限与白名单检查，不能依靠上层保护。只枚举配置 ID / access / areas；仅接受白名单、可读配置。该 OEM 在没有 areaConfigs 时生成空 getAreaIds()，本次六个厂商属性即属于此类；空列表允许固定 area 0，显式区域列表必须包含 0，只有非零区域的配置拒绝。不能以“列表不含 0”为由拒绝空区域列表。
- 调用固定 API，不接受外部 methodName / propertyId / 路径参数。`read()` 结果 source=POLL，订阅 source=CALLBACK，本地接收时钟使用 elapsedRealtimeNanos。
- 源对象必须带正确 propertyId / areaId；status、timestamp、value 均按实际类型验证，不把 null / 错误 / 类型不符变成零。
- 使用 CarPropertyEventListener 的动态代理；实现 Object 的 equals/hashCode/toString，忽略已关闭代理。onChangeEvent/onErrorEvent 均须正确处理，只转发白名单事件。
- 订阅请求 rate=1f，返回 true 只记录本地注册请求，报告必须另列实际 callbackCount，不宣称真实 1 Hz。保存部分成功注册的 listener，close 时逐个注销并 disconnect，清理项失败互不阻塞后续项。
- Binder 调用全部由会话的独立 I/O 槽执行，Service 主线程、看门狗和报告写盘线程不得执行车辆 Binder 调用。

## 会话、超时与 Service

文件：`Lynk06VehicleProbeSession.kt`、`Lynk06VehicleProbeReport.kt`、`Lynk06VehicleProbeService.kt`。

- 每次请求以 canonical UUID `request_id` 关联；不接受外部时长、字段列表或文件路径。Service 组件名 `com.shilapi.xcertplay.Lynk06VehicleProbeService`。
- action 固定为 `com.leth0x01.lynkplay.action.VEHICLE_PROBE_START` 和 `com.leth0x01.lynkplay.action.VEHICLE_PROBE_STOP`。
- Manifest：exported=true、permission=android.permission.DUMP；只允许持有 DUMP 的调用方（预期为 ADB shell/系统工具），并非严格 UID 白名单。不要在 onStartCommand 用 Binder.getCallingUid 推断原始调用者。不增加 intent-filter、launcher、boot receiver、设置页或后台自动采集入口，onBind 返回 null。显式声明 CAR_VENDOR_EXTENSION 并在运行时检查授权。
- 采集的共享库清单没有注册 android.car；当前 boot-android.car VDEX 的 API 已离线核对。不添加未经证实的 uses-library，也不以任意 DexClassLoader 或 Binder transaction 猜测进行回退；反射不可见时输出明确失败阶段。
- 开始后立即写初始报告，然后连接。六个字段每秒轮询；正常断开使值失效，清理旧后端后按 1/2/4/8 秒有界退避重连，在新代次重新确认配置并订阅，整个请求总时限仍为 60 秒。
- 每个 I/O 操作 3 秒看门狗。OEM Binder 不能被可靠强制取消：超时后会话失败、当前值失效、停止调度，独立线程仍能写终态报告。
- 同进程固定一个 I/O 槽；一次仅一个 Binder 操作在途，不无限排队。旧调用 / 清理未返回时拒绝新会话（busy），不能通过超时不断创建新线程。旧调用返回后仅做该代次清理，不发布结果。
- 同 request_id 重复 START 幂等、不延长截止时间；不同请求在忙时各自得到 rejected 报告，不覆盖正在运行的报告或停止旧会话。STOP 只作用于匹配的 request_id。
- onDestroy/超时/完成/STOP 立即逻辑停止并让迟到回调失效；清理在 I/O 槽内尽力执行。不得阻塞主线程等待清理。

## JSON 与 Windows 固定契约

输出：`getExternalFilesDir(null)/vehicle-probe/<request_id>.json`，Android 9 上脚本拉取路径为 `/sdcard/Android/data/com.leth0x01.lynkplay/files/vehicle-probe/<request_id>.json`。

根字段至少包括：

```json
{
  "schemaVersion": 1,
  "requestId": "canonical-uuid",
  "readOnly": true,
  "status": "running|completed|stopped|failed|rejected",
  "reason": "machine-readable-code",
  "startedWallTimeMillis": 0,
  "elapsedMillis": 0,
  "freshnessBasis": "local_observation",
  "connected": false,
  "stopped": false,
  "readings": [],
  "history": [],
  "droppedEvents": 0,
  "cleanupPending": false
}
```

- readings 包含固定六项：key、propertyId（十六进制字符串）、state、rawHex、rawUnsigned、numericValue、textValue、unit、status/时间信息、readCount、callbackCount、errorCode；无效解码值使用 JSON null，不省略成零。
- 候选字段不能带解码 numericValue / textValue / unit。history 至多 256 条，明确当时的 source 与 state。
- 最多每秒写一次中间报告，终态单独保证写入；AtomicFile 原子替换。保留最近 5 个报告，只删除此目录内 canonical UUID 命名的旧 JSON，不跟随外部输入路径。报告有硬性大小上界（建议 512 KiB），超出时丢弃最旧历史并增加 droppedEvents，不能写半截 JSON。
- 写盘线程与 I/O 看门狗分离；写盘失败记录受限错误，不把完成日志当成已成功写盘。接口错误仅保留短错误类型/码，不倾倒异常中可能含隐私的全量消息。
- Android 9 的 externalFilesDir 可能被持有外部存储权限的其他应用读取，不能称为严格私密存储；确认提示和使用文档须告知。不要为导出扩展现有 FileProvider 路径。
- Windows Runner 支持已有 ADB；显式确认后才安装同包 release APK，提示覆盖安装会中断当前 CarPlay。验证捆绑 APK SHA-256，拒绝不匹配的文件；不卸载、不降级、不提权、不修改车机设置。
- 设备完整匹配后才安装 / 启动；多设备需选择或 -Serial。每条设备命令显式 -s。请求 UUID 本地生成，拉取文件必须 requestId 匹配且 status 为终态；旧报告不能冒充本次成功。
- 默认等待上限 100 秒，单条 ADB 有超时；任何错误保留本机日志和已拉取报告。只在退出时发送匹配 UUID 的 STOP；不停止其他请求。不依赖网络、Python、Java 或 REA。

## 分工与文件所有权

- 主代理：设计、源框架 API 核对、复核与修复、合并、完整 CI、release 签名/构建及交付包。原有未提交修改不覆盖，不提交 Git。
- 子代理 A：`shared/.../vehicle/probe/*.kt` 与对应模型测试，先实现固定契约。
- 子代理 B：`common/.../Lynk06VehicleProbeBackend.kt` 与反射/只读安全测试；不编辑 Service / Session / Report。
- 子代理 C：Session / Report / Service、common Manifest、对应生命周期/超时/序列化测试；不编辑模型或反射后端。
- 子代理 D：`scripts/run_lynk06_vehicle_probe.ps1`、`.cmd`、对应 Python / PowerShell 假 ADB 测试、`docs/LYNK06-VEHICLE-PROBE.md`。
- 独立复核子代理：只读检查设计与实现，列阻断风险；主代理对每条意见确认、修改设计或代码并重测。

## 独立设计复核的处理

独立只读子代理已检查原有 Manifest、车型门禁和仪表代码。其提出的 API 可见性、显式车辆权限、进程级 I/O 槽位、迟到结果失效、缓存新鲜度、报告关联、防重入、DUMP 真实边界、externalFilesDir 隐私和后台启动风险，均已纳入本设计。原厂车辆 API 的精确签名已从本次采集的 boot-android.car VDEX 核对；实车类加载与后台启动结果仍由验证包取证，不能在交付前虚报通过。

## 实施与验收

### Task 1：模型与纯状态测试

先写测试，再实现字段解码、候选字段、错误与过期、停止、重连、晚到时间戳、可变输入和有界历史。命令：`:shared:testDebugUnitTest --tests '*VehicleProbe*'`。

### Task 2：反射只读后端

用注入 class lookup / 假 CarPropertyManager 验证 API 签名、读配置筛选、部分注册与注销、代理 Object 方法、错误类型、回调过滤。未知车型 / 权限不足必须在 class lookup 前拒绝；检查实现不包含车辆写入调用。

### Task 3：会话和 Service

用假后端、可控时钟与调度器验证 60 秒截止、重复 START、STOP 隔离、重连代次、迟到回调、阻塞 I/O、busy 拒绝、主线程无 Binder 调用、独立终态写入、原子文件与历史上界、清理异常。验证 Manifest DUMP 权限与 START_NOT_STICKY，不修改现有启动 / CarPlay 链路。

### Task 4：离线 Runner

假 ADB 验证中文 / 空格路径、多个设备、未知车型、错误 APK 哈希、安装失败、断连、命令超时、请求不匹配、远端失败 / busy 和报告拉取。默认不删除本机失败结果，不执行远端危险命令。PowerShell 文件保留 UTF-8 BOM，兼容 Windows PowerShell 5.1 API。

### Task 5：复核、集成和交付

主代理与独立子代理复核；任何 HIGH / CRITICAL 问题修复后重测。运行项目要求的完整 Android 测试、lint、Debug 构建，Python / PowerShell 脚本测试、public-tree 和 diff 检查，再用既有车测构建脚本生成并验证平台签名 release APK。仅将经哈希和包名/签名校验的 APK 放入私有 Windows 包。

本地 fake/Robolectric 验证不代替实车验证。实车待验：自身进程权限与 classpath、读数单位及非零动态值、真实回调频率、CarService 断连恢复、同时运行 CarPlay 的稳定性。不将待验项写成通过，不承诺接通 Ultra。
