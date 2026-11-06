# 开机自启为什么会失效：现场取证与根因

> 记录时间：2026-11-05 / 11-06，取自实车 adb（`172.24.172.59:5557`，`product=full_spm8666p2_64_car`）。
> 本文只记录事实与可复现的判据，不做推测性归因。

## 一、现象

APK 装上、手动点一次之后，PWS 能正常关闭。但**下次熄火再点火，应用不会自己起来**，必须再点一次图标。`BOOT_COMPLETED`、`ACTION_BOOT_HU`、JobScheduler 三条通道全部失效。

## 二、结论

**不是权限问题，不是挡位问题，不是 JobScheduler 参数写错。**

真正的链路是：

```
熄火 → system_server 对本包执行 forceStopPackage
     → 进程被杀，且包被标记为 stopped
     → stopped 跨越点火保留（不是每次开机重置）
     → BOOT_COMPLETED / ACTION_BOOT_HU / JobScheduler 全部停止投递
     → 必须人工启动一次才会清除 stopped
```

## 三、取证

### 3.1 事件日志时间线（`logcat -b events`）

```
11-05 18:53:21.247  am_proc_start: [0,4199,10072,com.sgmwyunduo.pwoff,broadcast,com.sgmwyunduo.pwoff/.BootReceiver]
11-05 18:53:21.333  am_proc_bound:  [0,4199,com.sgmwyunduo.pwoff]
11-05 18:53:21.481  notification_enqueue: 前台通知入队（GuardService 正常起来）
11-05 19:04:10.401  am_create_activity: .../.MainActivity          ← 用户手点一次
11-05 19:15:37.096  Force stopping com.sgmwyunduo.pwoff appid=10072 user=0: from pid 785
11-05 19:15:37.097  am_kill: [0,4199,com.sgmwyunduo.pwoff,200,stop com.sgmwyunduo.pwoff]
11-05 19:15:37.097  am_schedule_service_restart: [0,com.sgmwyunduo.pwoff/.GuardService,1000]
11-05 19:15:37.312  notification_cancel_all
11-06 07:49:40.575  am_proc_start: [0,22365,10072,com.sgmwyunduo.pwoff,activity,.../.MainActivity]
```

关键点：

- **18:53:21 那次开机自启其实是成功的**——广播把 `BootReceiver` 拉起来了，进程 pid 4199，前台通知也入队了。所以广播链路本身没问题。
- **19:15:37 被强杀**，`am_kill` 的 reason 是 `200`，`stop` 字样在参数里，这是 `forceStopPackage` 杀进程的典型形态。
- **19:15:37 到次日 07:49:40 之间，进程完全不存在**（`ps` 里查不到）。这 12.5 小时里没有任何自启尝试。
- 07:49:40 的 reason 是 `activity`，也就是用户点图标。

crash buffer 为空，排除崩溃。

### 3.2 谁杀的（`logcat -b all`）

```
11-05 19:15:37.096  ActivityManager: Force stopping com.sgmwyunduo.pwoff appid=10072 user=0: from pid 785
11-05 19:15:37.101  Force stopping service ServiceRecord{54b199 u0 .../.GuardService}
```

`from pid 785` 就是 `system_server`。同一秒内它批量 force-stop 了 30+ 个包，包括：

```
com.autonavi.amapauto          com.android.mtp
com.autoai.wuling.factorymode  com.sgmw.lingos.hvac          ← 连系统应用都没放过
com.desaysv.e230.sgmwweather   com.yy.sgmwsmartreminder
com.carota.sgmw                com.desaysv.sgmwfactorycheck
```

这是车机熄火时的**全局清理流程**，不是针对本包。

### 3.3 JobScheduler 也是同一个原因

```
JOB #u0a72/8801  .../.BootJobService
  PERSISTED
  Minimum latency: +3s0ms
  Unsatisfied constraints: TIMING_DELAY [0x80000000]
  Ready: false (job=false user=true !pending=true !active=true ...)
```

Job history 里有两条开机时刻的 START，随后立刻 `STOP-P ... onStartJob returned false`，之后 12 小时再无一次执行。**artifact 本身没写错，是投递被 stopped 挡住了。**

### 3.4 为什么乐酷桌面（`com.lecoauto`）能自启

用户的反证成立，但结论不是"乐酷有特权"，而是**它的身份不同**：

```
cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
  → isDefault=true, com.lecoauto/.activity.MainActivity

am_kill: [0,1916,com.lecoauto,0,stop com.lecoauto]     ← reason=0
```

乐酷是**默认 HOME**。熄火清理对它是普通 kill（`reason=0`），**不置 stopped**；对本应用是 `forceStopPackage`，**置 stopped**。这一个字段的差别决定了下次开机广播是否投递。

乐酷的 `AutoService` 还有一个 `START_APP` 机制：

```java
// AutoService.W(ArrayList) —— 遍历桌面"已启用 app"列表
new AutoTaskInfo(TaskType.START_APP, appInfo.getPackageName(), true)
//   见 deco 反编译 :1733-1741，另一处构造在 :1447
```

即：乐酷被系统拉起后，会遍历"已启用 app"列表逐个 `startActivity`。高德、音乐就是这样被带起来的。

**本应用有 `LAUNCHER` 入口**：

```
dumpsys package com.sgmwyunduo.pwoff
  3940b77 com.sgmwyunduo.pwoff/.MainActivity  filter 23dc9da
    Category: "android.intent.category.LAUNCHER"
```

所以只要把它加进乐酷的"已启用 app"，乐酷就能替我们启动它，而 `startActivity` 会**清除 stopped 状态**。

### 3.5 为什么系统应用能自启，第三方不行

`com.desaysv.carstate`（`CarStateService`）的 manifest：

```xml
<!-- carstate_src/resources/AndroidManifest.xml -->
:46    android:persistent="true"
:51    <action android:name="android.intent.action.ACTION_BOOT_HU"/>
:52    <action android:name="android.intent.action.BOOT_COMPLETED"/>
```

`android:persistent="true"` 让 `ActivityManagerService` 在系统就绪时主动拉起并常驻，且熄火清理走 `evenPersistent=false` 路径，**不置 stopped**。所以它那两条广播永远收得到。

`persistent` 只对 `/system`、`/system/priv-app` 下的系统应用生效。第三方 `/data/app` 写这个属性无效。

顺带排除了另一个怀疑对象：`com.example.multimanagerservice` 反编译后确认**没有任何白名单/保活/force-stop 逻辑**，它的 `IMultiManager` 12 个方法全是分屏操作（`enterSplitScreenMode` / `startActivityToScreen` / `swapScreen` 等），`protectMoveTask` / `protectAutoSplit` / `protectDismissSplit` 是分屏状态保护。19:15 那次批量 force-stop 与它无关。

## 四、规避方案（不需要改代码、不需要 root）

**在乐酷桌面里把"行人警示自动关闭"加入已启用应用列表**（方式与添加高德、音乐相同）。

之后每次点火的完整链路：

```
熄火 → force-stop（本包 stopped）
点火 → 系统拉起乐酷（HOME 豁免 force-stop）
     → 乐酷遍历已启用 app，对本包执行 START_APP
     → startActivity(.MainActivity)
     → MainActivity.onCreate 里 startService(GuardService)
     → 进程起来，stopped 被清除
     → GuardService 连 CarService，读 PWS，P 挡 defer / 挂挡写入
```

需要改代码 / root / 动 `/system` 的路径都试过并已排除，不再重复尝试：

- AIDL 代持（云朵没有对应的 `com.sgmw.vehiclesettings`）
- 伪造平台签名（实际是五菱柳州私钥）
- `adb root` + `remount`（重启失效，写镜像需过 OTA 分区签名校验）
- `priv-app` 白名单（PWS 属性登记为 normal，用不上，且需要写 `/system`）
- `android:persistent`（仅系统应用有效）
- `multimanagerservice` 白名单（不存在）

## 五、验证命令

加完乐酷配置后，熄火、点火一次，然后：

```bash
# 1. 进程是否由乐酷触发
adb shell "logcat -b events | grep am_proc_start | grep pwoff"

# 2. stopped 是否已被清除
adb shell "dumpsys package com.sgmwyunduo.pwoff | grep -E 'stopped=|notLaunched='"

# 3. 业务日志：是否读到 ON、是否 defer、是否写入
adb shell "logcat -d -s PwOff"

# 4. 任务是否还在排队（正常应为已执行或已不存在）
adb shell "dumpsys jobscheduler | grep -A 12 sgmwyunduo"
```

判据：`am_proc_start` 的 reason 应为 `com.lecoauto` 触发的启动，时间点紧跟点火之后几秒；`stopped=false`；`PwOff` 日志出现 `armed:` 与后续 `getPedestrianWarning -> 0`。
