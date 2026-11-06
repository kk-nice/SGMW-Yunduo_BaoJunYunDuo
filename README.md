# 云朵（E230）行人警示自动关闭（PedestrianWarningOff · Yunduo）

**宝骏云朵（E230 / spm8666p2_64_car / MT8666 / Android 9 / API 28 / LingOS）**车机的一个小工具：通过 CarService 厂商属性关闭**低速行人警示（PWS）**，免去每次上车手动关的麻烦。

> [!WARNING]
> **本项目仅供测试和临时应急使用，不是维修方案。**
> 行人警示是保护行人安全的功能。使用前请务必阅读文末的[免责声明](#免责声明)。

---

## 仓库说明

本仓库是**宝骏云朵（E230）**的独立实现，fork 自上游的[星光版项目](https://github.com/Echan1208/SGMW-Starlight_WuLingXingGuang)。

| 分支 | 内容 |
|---|---|
| `yunduo`（默认分支） | 宝骏云朵 E230 实现，本仓库主体 |
| `main` | 上游星光代码，仅作对照保留，**不对应云朵** |

两个车型虽然同属 SGMW LingOS 车机，但 PWS 的关闭入口不同：

- **星光**：设置应用里有一个直接可点的开关，所以上游版走的是常规路径；
- **云朵**：开关藏在二级菜单（`车辆 → 驾驶`），而且**不挂挡时点了也不生效**。

因此云朵版不碰 UI，直接写 CarService 厂商属性。

---

## 已实车验证的部分

| 项目 | 状态 |
|---|---|
| 写厂商属性 `0x21705074` 关闭 PWS | ✅ 实车生效 |
| `android.car.permission.CAR_INFO` 安装即授予 | ✅ `/data/app` 普通安装可用 |
| 挡位门控（P 挡先 defer，非 P 挡才写） | ✅ 逻辑正确，日志可证 |
| 每次点火零人工自动启动 | ❌ **未达成**，原因与规避方案见 [`docs/boot-autostart.md`](docs/boot-autostart.md) |

---

## 原理

### 为什么不用点设置里的按钮

云朵的 PWS 开关在设置的**二级菜单**里（`车辆 → 驾驶`），而且**不挂挡时点了也无效**。所以本应用不碰 UI，直接写属性。

### 直接写 CarService 厂商属性

```
PEDESTRIAN_WARNING = 561008756 = 0x21705074
写入：byte[] {2} = 关，{1} = 开
读取：byte[0]，1 = 开，0 = 关
area = 0
```

权限在 `PropertyHalServiceIds.java` 里登记为 `(CAR_INFO, CAR_INFO)`，而 `android.car.permission.CAR_INFO` 在本车 framework 中的 protectionLevel 是 `0x00000000`（normal），**`<uses-permission>` 声明即授予**，不需要平台签名，也不需要进 `priv-app`。

服务端唯一门禁是 `CarPropertyService` 的配置表检查 + `assertPermission`，通过后即 `mHal.setProperty`。

### 挡位门控

```
CURRENT_GEAR = 557862945 = 0x21405021     // CAR_INFO，可读
3 = P 挡
```

读到 P 挡时**不写**，只挂起等待（`defer until gear engaged`）；挂入非 P 挡后才写属性。这是云朵特有的约束——不挂挡写入无效。

### 重复补发

单次写入可能被 MCU 侧状态机忽略，所以读回 ON 后按固定节奏补发：

```
POLL_ARMED_MS   = 2s      // 挂起期间轮询间隔
RESEND_MS       = 3s      // 读到 ON 就每 3 秒补发一次 OFF
SETTLE_MS       = 60s     // 读回 OFF 后观察 60 秒确认稳定才 disarm
ARM_TIMEOUT_MS  = 20min   // 20 分钟关不上就放弃
```

同时注册了一个**持久化 JobScheduler 任务**（job id `8801`），作为开机广播的备份触发通道。

---

## 构建

Windows，无需 Gradle，直接用 SDK 工具链：

```powershell
# 需要：JDK 21、Android SDK build-tools 35.0.0、platforms;android-28
.\build.ps1
```

链路是 `javac → d8 → aapt2 → zipalign → apksigner`。

构建脚本会在首次运行时用 `keytool` 自动生成 `release.keystore`（别名 `pwoff`）。**签名文件不随仓库提交**（见 `.gitignore`），所以你自己构建出来的 APK 签名与本仓库发布版不一致，这是预期行为。

注意：`build-tools 34.0.0` 的 D8 在 JDK 21 下会崩，必须用 `35.0.0`。

## 安装

```powershell
.\install.ps1                      # 默认装同目录的 PedestrianWarningOff-Yunduo.apk
.\install.ps1 -Apk D:\path\x.apk   # 或指定路径
```

**安装后必须启动一次**（脚本里的 `am start` 就是在做这件事）。原因：`adb install` 之后应用处于 `stopped` 状态，`stopped` 的应用收不到 `BOOT_COMPLETED`。这一步省不得。

---

## 已知限制：开机自启

当前版本在被车机熄火清理后会失去自启能力，需要人工点一次图标。**这不是权限问题，也不是 JobScheduler 参数问题**，详细的取证过程、日志证据和规避方案见：

👉 [`docs/boot-autostart.md`](docs/boot-autostart.md)

一句话结论：熄火时 `system_server` 对本应用执行 `forceStopPackage`，包被钉在 `stopped`，`stopped` 跨点火保留，此后 `BOOT_COMPLETED` / `ACTION_BOOT_HU` / JobScheduler 全部不投递。

### 两种规避

1. **不改代码**：在乐酷桌面（默认 HOME，不被 force-stop 钉 stopped）的"已启用 app"里把本应用加进去，由它开机 `START_APP` 拉起。
2. **改系统**：装一个 init 看守进程，每 30s 检查一次 `pidof`，发现进程不在就 `am start` 显式启动 `.BootActivity`。
   APK 里已经内置了这个无界面入口（`BootActivity`，`Theme.NoDisplay`），配套脚本与 rc 在 [`watchdog/`](watchdog/)。
   **注意**：这一路需要往 `/vendor` 写文件，受分区校验约束，落地通道见 [`watchdog/README.md`](watchdog/README.md)。

---

## 免责声明

- 本项目与上汽通用五菱、宝骏、德赛西威无任何关联，属个人实验项目。
- 行人警示音是国家标准要求的低速提示功能，关闭后**可能影响行人（尤其视障人士）对车辆的感知**。请在封闭场地或明确安全的前提下使用，公共道路行驶请恢复该功能。
- 修改车辆属性存在不确定性风险，包括但不限于影响其他依赖该属性的功能。因使用本工具造成的一切后果由使用者自行承担。
- 本项目仅用于学习、研究与个人车辆的临时调试，不得用于商业用途或批量改装。
