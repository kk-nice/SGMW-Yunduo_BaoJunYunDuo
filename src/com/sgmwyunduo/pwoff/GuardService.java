package com.sgmwyunduo.pwoff;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 云朵（E230/LingOS, MT8666, Android 9）行人警示自动关闭。
 *
 * 依据对 com.sgmw.lingos.setting（SGMWSetting）和 com.desaysv.sgmwrvc 的反编译结论，
 * 云朵整车属性走 Android Automotive 的 CarService（VHAL）：
 *   - 属性: VehiclePropertyIds.PEDESTRIAN_WARNING = 561008756 (0x21705074)
 *   - 读取: CarPropertyManager.getProperty(byte[].class, 561008756, 0)
 *           value[0] == 0 -> 关, == 1 -> 开
 *   - 写入: CarPropertyManager.setProperty(byte[].class, 561008756, 0, new byte[]{1}) 开
 *           CarPropertyManager.setProperty(byte[].class, 561008756, 0, new byte[]{2}) 关
 *
 * 由于 android.car 不在公开 SDK，这里全部用反射调用，避开编译期对 android.car.jar 的依赖，
 * 运行时由车机框架解析 android.car.Car / CarPropertyManager。
 */
public class GuardService extends Service {
    static final String TAG = "PwOff";

    private static final String CAR_CLASS = "android.car.Car";
    private static final String CPM_CLASS = "android.car.hardware.property.CarPropertyManager";
    private static final String CAR_PROPERTY_SERVICE = "android.car.PROPERTY_SERVICE";
    private static final String[] CAR_MANAGER_CANDIDATES = {
            "property", "android.car.property", "car_property",
            "android.car.ICarProperty", "CarPropertyService", "carProperty"
    };
    private static final int PROP_PEDESTRIAN_WARNING = 561008756; // VehiclePropertyIds.PEDESTRIAN_WARNING
    private static final byte CMD_OFF = 2; // 1=开, 2=关
    // VehicleProperty.CURRENT_GEAR = 557862945（SGMW 私有 ID），
    // PropertyHalServiceIds:128 登记为 CAR_INFO，与 PWS 同权限。
    // 值 3 = P 挡；!=3 视为已挂挡（DriveFragment / PropertyManager 均用 !=3 判行车）。
    private static final int PROP_CURRENT_GEAR = 557862945;
    private static final int GEAR_PARK = 3;

    private static final long POLL_ARMED_MS = 2_000;
    private static final long POLL_IDLE_MS = 10_000;
    private static final long RESEND_MS = 3_000;
    private static final long SETTLE_MS = 60_000;
    private static final long ARM_TIMEOUT_MS = 20 * 60_000;
    private static final long SLEEP_GAP_MS = 5_000;
    private static final long SCREEN_OFF_REARM_MS = 60_000;

    private static final String CHANNEL_ID = "guard";
    private static final String EXTRA_REASON = "reason";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Object mCar;
    private Object mCpm; // CarPropertyManager
    private boolean bound;

    private boolean armed;
    private long armedAt;
    private long offSince = -1;
    private long lastSendAt = -RESEND_MS;
    private int sends;

    private long lastTickUptime = -1;
    private long lastTickElapsed = -1;
    private long screenOffAt = -1;

    static void start(Context context, String reason) {
        Intent i = new Intent(context, GuardService.class).putExtra(EXTRA_REASON, reason);
        context.startForegroundService(i);
    }

    private final ServiceConnection carConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "CarService connected");
            getCarPropertyManager();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "CarService disconnected");
            mCar = null;
            mCpm = null;
        }
    };

    private void getCarPropertyManager() {
        try {
            Class<?> carCls = mCar.getClass();
            Method getCarManager = carCls.getMethod("getCarManager", String.class);

            // 探测 1: 打印车机 Car 类里所有 public static String 常量（服务名/PROPERTY_SERVICE 真值）
            probeCarConstants(carCls);

            // 探测 2: 逐个候选服务名试 getCarManager
            String[] candidates = new String[CAR_MANAGER_CANDIDATES.length + 1];
            String propServiceValue = readStringConstant(carCls, "PROPERTY_SERVICE");
            candidates[0] = propServiceValue != null ? propServiceValue : CAR_PROPERTY_SERVICE;
            System.arraycopy(CAR_MANAGER_CANDIDATES, 0, candidates, 1, CAR_MANAGER_CANDIDATES.length);

            for (String name : candidates) {
                if (name == null) {
                    continue;
                }
                try {
                    Object mgr = getCarManager.invoke(mCar, name);
                    Log.i(TAG, "getCarManager(\"" + name + "\") -> " + (mgr == null ? "null" : mgr.getClass().getName()));
                    if (mgr != null && mCpm == null) {
                        mCpm = mgr;
                        Log.i(TAG, "CarPropertyManager acquired via \"" + name + "\": " + mgr.getClass().getName());
                    }
                } catch (Throwable t) {
                    Throwable cause = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
                    Log.w(TAG, "getCarManager(\"" + name + "\") threw " + cause.getClass().getName() + ": " + cause.getMessage());
                }
            }

            if (mCpm == null) {
                Log.w(TAG, "all candidate service names returned null");
                return;
            }
            if (armed) {
                handler.removeCallbacks(tick);
                handler.post(tick);
            }
        } catch (Throwable t) {
            Log.w(TAG, "getCarManager failed", t);
        }
    }

    private void probeCarConstants(Class<?> carCls) {
        try {
            for (java.lang.reflect.Field f : carCls.getFields()) {
                if (f.getType() == String.class && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    try {
                        Log.i(TAG, "Car." + f.getName() + " = \"" + f.get(null) + "\"");
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "probeCarConstants failed", t);
        }
    }

    private String readStringConstant(Class<?> carCls, String fieldName) {
        try {
            java.lang.reflect.Field f = carCls.getField(fieldName);
            return (String) f.get(null);
        } catch (Throwable t) {
            Log.w(TAG, "read constant " + fieldName + " failed: " + t);
            return null;
        }
    }

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long now = SystemClock.elapsedRealtime();
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                screenOffAt = now;
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                if (screenOffAt >= 0 && now - screenOffAt >= SCREEN_OFF_REARM_MS) {
                    arm("screen on after " + (now - screenOffAt) / 1000 + "s off");
                }
                screenOffAt = -1;
            }
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(this);
            long up = SystemClock.uptimeMillis();
            long el = SystemClock.elapsedRealtime();
            if (lastTickElapsed >= 0) {
                long slept = (el - lastTickElapsed) - (up - lastTickUptime);
                if (slept > SLEEP_GAP_MS) {
                    arm("resumed after " + slept / 1000 + "s sleep");
                }
            }
            lastTickUptime = up;
            lastTickElapsed = el;

            if (armed) {
                check();
            }
            handler.postDelayed(this, armed ? POLL_ARMED_MS : POLL_IDLE_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "后台守护", NotificationManager.IMPORTANCE_MIN);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);

        // Android 9 (API 28): ACTION_SCREEN_ON/OFF are protected system broadcasts,
        // only the system can send them, so plain registration is safe here.
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, f);

        bindCar();
        handler.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
                .setContentTitle("行人警示自动关闭")
                .setContentText("开机/唤醒后自动关闭行人警示")
                .setOngoing(true)
                .build();
        startForeground(1, n);

        String reason = intent != null ? intent.getStringExtra(EXTRA_REASON) : null;
        arm(reason != null ? reason : "restart");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(screenReceiver);
        unbindCar();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 注册持久化 Job 的唯一入口：只由 MainActivity / BootReceiver 调一次，服务启动路径不调，避免自触发循环 */
    static void schedulePersistedJob(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js.getPendingJob(8801) != null) {
                Log.i(TAG, "persisted job already pending, skip");
                return;
            }
            JobInfo job = new JobInfo.Builder(8801, new ComponentName(ctx, BootJobService.class))
                    .setPersisted(true)
                    .setMinimumLatency(3000)
                    .build();
            int result = js.schedule(job);
            Log.i(TAG, "persisted job scheduled, result=" + result);
        } catch (Throwable t) {
            Log.w(TAG, "scheduleJob failed", t);
        }
    }

    private void bindCar() {
        if (bound) {
            return;
        }
        try {
            Class<?> carCls = Class.forName(CAR_CLASS);
            Method createCar = carCls.getMethod("createCar", Context.class, ServiceConnection.class, Handler.class);
            mCar = createCar.invoke(null, this, carConnection, handler);
            if (mCar == null) {
                Log.w(TAG, "Car.createCar returned null");
                return;
            }
            Method connect = mCar.getClass().getMethod("connect");
            connect.invoke(mCar);
            bound = true;
            Log.i(TAG, "Car.connect invoked");
        } catch (Throwable t) {
            Log.w(TAG, "bindCar failed", t);
        }
    }

    private void unbindCar() {
        if (mCar != null) {
            try {
                Method disconnect = mCar.getClass().getMethod("disconnect");
                disconnect.invoke(mCar);
            } catch (Throwable ignored) {
            }
            mCar = null;
            mCpm = null;
            bound = false;
        }
    }

    private void arm(String reason) {
        Log.i(TAG, "armed: " + reason);
        armed = true;
        armedAt = SystemClock.elapsedRealtime();
        offSince = -1;
        lastSendAt = -RESEND_MS;
        sends = 0;
        handler.post(tick);
    }

    private void disarm(String reason) {
        Log.i(TAG, "disarmed: " + reason);
        armed = false;
    }

    private void check() {
        if (mCpm == null) {
            bindCar();
            if (SystemClock.elapsedRealtime() - armedAt >= ARM_TIMEOUT_MS) {
                disarm("car service unavailable for " + ARM_TIMEOUT_MS / 60_000 + " min");
            }
            return;
        }
        int status = getPedestrianWarning();
        long now = SystemClock.elapsedRealtime();
        if (status == 1) {
            offSince = -1;
            int gear = getCurrentGear();
            if (gear == GEAR_PARK) {
                // P 挡下写入会被 MCU 忽略/复位，等挂挡再发；期间保持 armed，不 disarm。
                if (now - lastSendAt >= RESEND_MS) {
                    Log.i(TAG, "pedestrian warning is ON, gear=P(" + gear + ") -> defer until gear engaged");
                    lastSendAt = now;
                }
            } else if (now - lastSendAt >= RESEND_MS) {
                sends++;
                Log.i(TAG, "pedestrian warning is ON, gear=" + gear + ", sending OFF (attempt " + sends + ")");
                setPedestrianWarning(CMD_OFF);
                lastSendAt = now;
            }
        } else if (status == 0 && sends > 0) {
            if (offSince < 0) {
                offSince = now;
                Log.i(TAG, "pedestrian warning is OFF");
            } else if (now - offSince >= SETTLE_MS) {
                disarm("stayed off for " + SETTLE_MS / 1000 + "s");
            }
        } else if (now - armedAt >= ARM_TIMEOUT_MS) {
            disarm("never saw it on within " + ARM_TIMEOUT_MS / 60_000 + " min (last status " + status + ")");
        }
    }

    /** @return 1=开, 0=关, -1=未知/失败 */
    private int getPedestrianWarning() {
        try {
            Method getProp = mCpm.getClass().getMethod("getProperty", Class.class, int.class, int.class);
            Object propValue = getProp.invoke(mCpm, byte[].class, PROP_PEDESTRIAN_WARNING, 0);
            if (propValue == null) {
                return -1;
            }
            Method getValue = propValue.getClass().getMethod("getValue");
            Object value = getValue.invoke(propValue);
            if (value instanceof byte[]) {
                byte b = ((byte[]) value)[0];
                Log.i(TAG, "getPedestrianWarning -> " + b);
                return b == 1 ? 1 : 0;
            }
            return -1;
        } catch (Throwable t) {
            Log.w(TAG, "getPedestrianWarning failed", t);
            return -1;
        }
    }

    /** @return 挡位；3=P 挡。读不到返回 -1（按非 P 挡处理，即照常尝试关闭） */
    private int getCurrentGear() {
        try {
            Method getProp = mCpm.getClass().getMethod("getProperty", Class.class, int.class, int.class);
            Object propValue = getProp.invoke(mCpm, Integer.class, PROP_CURRENT_GEAR, 0);
            if (propValue == null) {
                return -1;
            }
            Method getValue = propValue.getClass().getMethod("getValue");
            Object value = getValue.invoke(propValue);
            if (value instanceof Integer) {
                return ((Integer) value).intValue();
            }
            return -1;
        } catch (Throwable t) {
            Log.w(TAG, "getCurrentGear failed", t);
            return -1;
        }
    }

    private void setPedestrianWarning(int value) {
        try {
            Method setProp = mCpm.getClass().getMethod("setProperty", Class.class, int.class, int.class, Object.class);
            setProp.invoke(mCpm, byte[].class, PROP_PEDESTRIAN_WARNING, 0, new byte[]{(byte) value});
            Log.i(TAG, "setPedestrianWarning(" + value + ") sent");
        } catch (Throwable t) {
            Log.w(TAG, "setPedestrianWarning failed", t);
        }
    }
}
