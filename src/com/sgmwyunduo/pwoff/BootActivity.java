package com.sgmwyunduo.pwoff;

import android.app.Activity;
import android.os.Bundle;

/**
 * 无界面入口。给 init 看守脚本用：
 *
 *   am start --user 0 -n com.sgmwyunduo.pwoff/.BootActivity
 *
 * 为什么不用 MainActivity：
 *   - MainActivity 在 onCreate 里会弹 Toast，对无感自启是多余的动作；
 *   - 显式启动一个 exported 组件，AMS 会顺带把这个包的 stopped 标志清掉，
 *     这才是真正需要的那一步（stopped 状态下 BOOT_COMPLETED / JobScheduler 全部不投递）。
 *
 * 本 Activity 使用 @android:style/Theme.NoDisplay，且 onCreate 里立即 finish()，
 * 不会在桌面/任务栈里留下任何可见痕迹。
 */
public class BootActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GuardService.schedulePersistedJob(this);
        GuardService.start(this, "watchdog");
        finish();
    }
}
