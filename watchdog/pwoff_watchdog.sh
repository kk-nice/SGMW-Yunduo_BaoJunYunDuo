#!/system/bin/sh
#
# 行人警示看守（PwOff watchdog）
#
# 常驻进程，由 init 在 sys.boot_completed=1 之后拉起（见 pwoff_watchdog.rc）。
# 应用被车机熄火流程 force-stop 之后，30 秒内重新拉起它。
#
# 关键点：必须用 am start 显式启动 .BootActivity。
#   - 只 startService 不生效：服务无法启动一个处于 stopped 状态的包；
#   - 显式 startActivity 会让 AMS 顺带清掉该包的 stopped 标志，
#     之后 BOOT_COMPLETED / ACTION_BOOT_HU / JobScheduler 才恢复投递。
#
# 域的要求：本脚本必须以 u:r:shell:s0 运行，
#   init 域虽然能执行 app_process（(allow init zygote_exec ...)），但没有 entrypoint，
#   am 会起不来；shell 域才有
#   (allow shell zygote_exec (file (ioctl read getattr lock map execute execute_no_trans open)))。
#

PKG=com.sgmwyunduo.pwoff
ACT="${PKG}/.BootActivity"
INTERVAL=30
RETRY=10

log -t PwOffWatch "watchdog up: pkg=${PKG}, interval=${INTERVAL}s"

# 双保险：rc 已经在 boot_completed 之后触发，这里再确认一次
i=0
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    i=$((i + 1))
    if [ "$i" -ge 30 ]; then
        log -t PwOffWatch "boot not completed after 60s, continue anyway"
        break
    fi
    sleep 2
done

# toybox 的 pidof 在 Android 9 上有；缺了就用 ps 兜底
HAVE_PIDOF=0
if command -v pidof >/dev/null 2>&1; then
    HAVE_PIDOF=1
fi

alive() {
    if [ "$HAVE_PIDOF" = "1" ]; then
        pidof "$PKG" >/dev/null 2>&1
    else
        ps -A 2>/dev/null | grep -q "$PKG"
    fi
}

while true; do
    if alive; then
        :
    else
        log -t PwOffWatch "pid gone, am start ${ACT}"
        am start --user 0 -n "$ACT" >/dev/null 2>&1
        rc=$?
        if [ "$rc" -ne 0 ]; then
            log -t PwOffWatch "am start failed rc=${rc}, retry in ${RETRY}s"
            sleep "$RETRY"
        fi
    fi
    sleep "$INTERVAL"
done
