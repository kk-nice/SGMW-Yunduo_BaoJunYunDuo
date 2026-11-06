# 开机自启：乐酷桌面托管（实车已验证）

## 结论

熄火会被系统 `force-stop` 打上 `stopped`，应用自己救不了自己。**唯一的免 root、免改 OTA 解法是让乐酷桌面在每次点火时把它拉起来**，配置只需一次。

---

## 一、为什么应用自己起不来

`logcat -b events` 原文（车机本地时间）：

```
11-05 18:53:21.247 am_proc_start [0,4199,10072,com.sgmwyunduo.pwoff,broadcast,.../.BootReceiver]
11-05 19:04:10.401 am_create_activity .../.MainActivity
11-05 19:15:37.096 ActivityManager: Force stopping com.sgmwyunduo.pwoff appid=10072 user=0: from pid 785
11-05 19:15:37.097 am_kill [0,4199,...,200,stop com.sgmwyunduo.pwoff]
11-05 19:15:37.312 notification_cancel_all
11-06 07:49:40.575 am_proc_start [0,22365,...,activity,.../.MainActivity]
```

- `18:53:21` 那一刻，**开机广播其实成功投递过**，进程 id 4199 —— 说明 `BOOT_COMPLETED` 与 `BootReceiver` 本身没写错。
- `19:15:37` 熄火，`from pid 785`（`system_server`）对本包执行 `Force stopping`，同秒批量清了 30+ 个包（`com.sgmw.lingos.hvac`、`com.carota.sgmw` 等系统应用都在列），**不是针对本包**。
- 之后 12.5 小时到 `07:49:40` 才出现下一个 `am_proc_start`，reason 是 `activity` —— 就是用户手点那一次。

`Force stopping` 会把包钉进 `stopped`，`stopped` **跨点火保留**。处于 `stopped` 的包：

- 收不到 `BOOT_COMPLETED` / `ACTION_BOOT_HU`；
- JobScheduler 的持久任务不会被唤醒（`dumpsys jobscheduler` 里 `Ready: false`，history 只有开机那两条 `START` + `STOP-P onStartJob returned false`）；
- 任何 `bind` / `startService` 都不投递。

**只有 `startActivity` 显式启动一个 exported 组件，AMS 才会顺手清掉 `stopped`。** 这是唯一的口子。

## 二、为什么乐酷桌面能钻这个口子

1. **它没被打上 `stopped`**：清理日志里它的记录是 `am_kill [0,1916,com.lecoauto,0,...]`，reason=**0**（普通 kill），且不在 `Force stopping` 名单内。
2. **它是默认 HOME**：`resolve-activity --brief -a MAIN -c HOME` → `isDefault=true, com.lecoauto/.activity.MainActivity`。HOME 开机必起。
3. **它有自启列表 + 直接 startActivity**：反编译 `AutoService`，`W(ArrayList)` 遍历"桌面已启用 app"，对每个 `isEnable()` 的条目构造
   `new AutoTaskInfo(TaskType.START_APP, 包名, true)`（指令级位置 `:1733-1741`，另一处 `:1447`）。
   你车上的高德地图、音乐就是这条链路拉起来的。
4. **本应用能被它列出来**：`dumpsys package com.sgmwyunduo.pwoff` 里有
   `3940b77 com.sgmwyunduo.pwoff/.MainActivity filter 23dc9da`，`Category: android.intent.category.LAUNCHER`。

## 三、配置（一次性）

在乐酷桌面的自启 / 已启用应用设置里，把「行人警示自动关闭」（`com.sgmwyunduo.pwoff`）加入启用列表并保存。

> 具体菜单名以车机实际为准（本车已实测有效）。

配置完成后，本应用已经处于"被启动过"的状态，`stopped=false`，自身那套 `BOOT_COMPLETED` + 持久 Job 在这一轮点火里也会正常工作 —— 两条链路互为兜底。

## 四、验收清单

```bash
# 1. 点火后，包不再是 stopped
adb shell "dumpsys package com.sgmwyunduo.pwoff | grep -E 'stopped=|notLaunched='"

# 2. 进程确实起来了，且 reason 是 activity / broadcast 而不是手工
adb shell "logcat -b events | grep am_proc_start | grep pwoff"
adb shell "ps -A -o pid,etime,args | grep sgmwyunduo"

# 3. 业务日志里出现关闭动作
adb shell "logcat -d -s PwOff | tail -20"
```

预期：`stopped=false`、`am_proc_start` 的 reason 为 `activity`（乐酷拉起）或 `broadcast`（自身 `BootReceiver`），`PwOff` 日志出现 `pedestrian warning -> 0` 或 `set 2 ok`。

## 五、什么情况下会重新退回"要手点一次"

- 用户在系统设置里对本应用执行「强行停止」；
- 乐酷桌面被用户手动强制停止，或它的自启开关被关掉；
- OTA 改了乐酷的自启逻辑（升级后需重新确认）。

## 六、其他路线为什么不行（结论留档）

| 路线 | 为什么不成立 |
|---|---|
| `android:persistent="true"` | 该属性只对 `/system`、`/system/priv-app` 生效，`/data/app` 声明无效 |
| JobScheduler / `BOOT_COMPLETED` / `ACTION_BOOT_HU` | 包被 `stopped` 后全部不投递，与参数无关 |
| `multimanagerservice` 白名单 | 反编译确认它只有分屏能力，**不存在白名单**，也不主动拉任何 app |
| 往 `/vendor` 装 init 看守 | `/vendor` 只读且受 dm-verity 保护，`adb root` 的 overlay 重启即丢，改 `vendor.img` 要过 `update_entity` 分区签名 —— 需 root 或改 OTA |
| 平台签名伪造 | 私钥在五菱柳州侧，OTA 里没有 |
