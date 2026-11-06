# init 看守方案（备选留档 · 需系统写权限）

> **本车已验证不需要它。** 免 root、免改 OTA 的解法是乐酷桌面托管，见 [`../docs/leco-autostart.md`](../docs/leco-autostart.md)。
> 本节只在"能写 `/vendor`"的前提下成立：`/vendor` 只读且受 dm-verity 保护，`adb root` 的 overlay 重启即丢，
> 改 `vendor.img` 要过 `update_entity` 的分区签名校验。**不 root、不 OTA 时这条路不成立。**

## 为什么要它

车机熄火时 `system_server` 对本包执行 `Force stopping`，进程被杀且包被标记 `stopped`。
`stopped` 跨点火保留，此后 `BOOT_COMPLETED` / `ACTION_BOOT_HU` / JobScheduler 全部不投递，
应用只能靠人工点图标恢复。详见 [`../docs/boot-autostart.md`](../docs/boot-autostart.md)。

应用自身的任何机制都救不了这一条（它连进程都起不来），所以必须有一个**不属于应用进程**的看守。

## 组成

| 文件 | 放置位置 | 作用 |
|---|---|---|
| `pwoff_watchdog.sh` | `/vendor/bin/pwoff_watchdog.sh`（0755 root:root） | 每 30s 检查一次 `pidof com.sgmwyunduo.pwoff`，不在就 `am start` 显式拉起 `.BootActivity` |
| `pwoff_watchdog.rc` | `/vendor/etc/init/pwoff_watchdog.rc` | 声明 init service，`on property:sys.boot_completed=1` 启动 |
| APK 内的 `.BootActivity` | 随 APK 安装 | 无界面入口：`startForegroundService(GuardService)` 后立即 `finish()` |

关键点是 `am start` **显式启动组件**：AMS 会顺带清掉该包的 `stopped` 标志。
只 `startService` 不行 —— 服务无法启动一个处于 `stopped` 状态的包。

## SELinux 依据（取自本车 plat_sepolicy.cil，逐字核对）

```
(allow init shell_exec (file (read getattr map execute open)))
(allow init shell (process (transition)))              (另有 siginh rlimitinh)
(allow shell zygote_exec (file (ioctl read getattr lock map execute execute_no_trans open)))
```

- `su` 域共 18 条 allow，`(allow su zygote_exec ...)` = **0** → su 方案不成立。
- `(allow init zygote_exec ...)` 只有 1 条，且**不含 `entrypoint`**，同时镜像里没有
  `(type_transition init shell_exec → shell)`，所以 **init 域里执行 `am` 起不来**。
- `shell` 域（`adb shell` 所在域）同时具备 `shell_exec` 与 `zygote_exec(execute_no_trans)`，
  `am` 在里面可用。

因此 rc 里必须显式 `seclabel u:r:shell:s0`。**不要照抄镜像里 `vrdata_config` / `boototamonitor`
的 `u:r:init:s0`** —— 那两个脚本只做文件读写，不碰 app_process。

## 镜像里的先例（说明这条路供应商自己走过）

```rc
# /vendor/etc/init/ucu_ota_monitor.rc
service boototamonitor /vendor/bin/ota_monitor.sh
    user root
    group root
    disabled
    oneshot
    seclabel u:r:init:s0

on boot
    start boototamonitor
```

```rc
# /vendor/etc/init/update_display.rc
service vrdata_config /vendor/bin/vrdata_config.sh
    user root
    group root
    oneshot
    seclabel u:r:init:s0

on boot
    start vrdata_config
```

## 落地通道（这一步必须你来定）

脚本和 rc 必须进入 `/vendor`，而 `/vendor` 是只读分区且受校验保护。三条通道：

1. **已有持久 root**（工程模式 / 已解锁 + 已关 verity）：`adb root` → `adb remount` → push 两个文件 → `chmod 755`。
   注意 `adb remount` 的 overlay 在重启后失效，**必须确认写入是落到分区还是 overlay**。
2. **改 vendor.img 再刷**：解包 `payload.bin` 的 vendor 分区，放入文件，重新打包并让 OTA 校验通过。
   需要厂商签名能力，否则 `update_entity` 会拒。
3. **厂商 OTA 通道**：把这套东西交给能签 OTA 的一方打包进下次升级。

不落地的验证方式：先只把 `/vendor/bin/pwoff_watchdog.sh` 手工跑起来
（`adb shell su` 或 adb root 下 `sh /data/local/tmp/pwoff_watchdog.sh`），
确认 `am start` 能清 stopped、GuardService 能起来，再决定是否值得写进分区。

## 验证

```bash
# 看守是否在跑
adb shell "ps -A | grep pwoff_watchdog"
adb shell "logcat -d -s PwOffWatch"

# 人为制造 stopped，观察 30s 内是否被拉起
adb shell "am force-stop com.sgmwyunduo.pwoff"
adb shell "dumpsys package com.sgmwyunduo.pwoff | grep stopped="
# 等 30~60s 再看
adb shell "dumpsys package com.sgmwyunduo.pwoff | grep stopped="
adb shell "ps -A -o pid,etime,args | grep sgmwyunduo"
adb shell "logcat -b events | grep am_proc_start | grep pwoff"
```

预期：`stopped=true` → 30~60s 后 `stopped=false`，进程 pid 出现，`am_proc_start` 的 reason 为 `activity`。

## 回滚

```bash
adb shell "rm /vendor/etc/init/pwoff_watchdog.rc /vendor/bin/pwoff_watchdog.sh"
adb shell "stop pwoff_watchdog"     # 已运行的实例立即停
```

## 风险

- 看守是常驻进程，每 30s 一次 `pidof`，功耗可忽略但会阻止深度休眠时的进程清空。
- 若 `am start` 失败（AMS 未就绪、用户切换），脚本会 10s 后重试并写日志，不会死循环刷屏。
- 修改 `/vendor` 属系统级改动，与 OTA 升级可能冲突，升级后需重新放置。
