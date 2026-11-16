package com.workbuddy.antidizzybridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.opengl.GLES20;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 通用版 AntiDizzy 注入：用手机自带传感器驱动 ColorOS 原生「晕动舒缓显示」。
 *
 * 适用：OPPO / OnePlus / realme 的 ColorOS 16 系列（固件里带
 * com.android.server.display.feature.antidizzy.* 但缺车型传感器或特性开关的机型）。
 *
 * 自动适配：
 *   - 若设备本身就有 car motion / car scene 传感器（33171091 / 33171092），不注入，保持原厂行为；
 *   - 否则自动挑选最佳运动数据源（线性加速度优先），并按可用性启用陀螺仪；
 *   - 反射用的类/字段/方法名支持多套备选，跨版本可用。
 *
 * 「仅车辆运动时显示」用 VehicleDetector 做车辆运动识别（振动能量 + 步态周期 + 转向角速度），
 * 近似原厂 car scene 的效果；「常驻显示」用高质量线性加速度驱动。
 */
public class Bridge implements IXposedHookLoadPackage {

    private static final String TAG = "AntiDizzyBridge";

    private static final String CLS_MANAGER = "com.android.server.display.feature.antidizzy.AntiDizzyManager";
    private static final String CLS_FEATURE = "com.android.server.display.feature.antidizzy.OplusFeatureAntiDizzy";
    private static final String CLS_VIEW = "com.android.server.display.feature.antidizzy.AntiDizzyView";

    private static final String[] F_INSTANCES = {"mInstances", "mAntiDizzyInstances", "mMap"};
    private static final String[] M_HANDLE = {"handleMotionSensorEvent", "handleSensorEvent", "onMotionSensorEvent"};
    private static final String[] F_SETTINGS = {"mCurrentAntiDizzySettings", "mAntiDizzySettings", "mIsAntiDizzyEnabled"};
    private static final String[] F_NEEDDRAW = {"mIsNeedDrawBall", "mNeedDrawBall"};
    private static final String[] F_VIEW = {"mAntiDizzyView", "mView"};
    private static final String[] M_ALPHA = {"updateSurfaceAlpha", "setSurfaceAlpha"};
    private static final String[] M_SKIPSHOT = {"setSkipScreenshot", "setSkipScreenShot"};
    private static final String[] M_CREATEVIEW = {"createAntiDizzyView", "createView"};

    private static final int CAR_MOTION_TYPE = 33171091;
    private static final int CAR_SCENE_TYPE = 33171092;

    // ---- 反射句柄 ----
    private static ClassLoader sLoader;
    private static volatile Object sManager;
    private static volatile Map<?, ?> sInstances;
    private static volatile Method sHandle;
    private static volatile Method sAlpha;
    private static volatile Method sSkipShot;
    private static volatile Method sCreateView;
    private static volatile Field fSettings, fNeedDraw, fView;
    private static volatile Constructor<?> sEvCtor;
    private static volatile Field fValues, fSensor, fTimestamp, fAccuracy;

    // ---- 运行时状态 ----
    private static volatile Context sCtx;
    private static volatile SensorManager sSm;
    private static volatile Sensor sAccel;
    private static volatile Sensor sGyro;
    private static volatile boolean sUseLinear;
    private static volatile boolean sReady;
    private static volatile Object sAlphaView;
    private static volatile boolean sAlphaApplied = false;

    private final VehicleDetector detector = new VehicleDetector();

    // ---- 导航广播监听 ----
    // 场景服务识别「驾车」靠的是地图导航 App 发出的状态广播。
    // system_server 里注册 receiver 可以直接收到同样的广播——这是当前唯一
    // 能拿到的可靠驾车信号（sensorhub 模块缺失、场景库被 SELinux 拦）。
    // 先全量 dump extras 摸清格式，之后再解析 status。
    private static final String[] NAV_ACTIONS = {
            "com.autonavi.minimap#Navigation.NotifyDrivingNavigationStatus",      // 高德驾车
            "com.autonavi.minimap#Navigation.NotifyPublicTransportStatus",        // 高德公交/地铁
            "com.autonavi.minimap#Navigation.NotifyGaoDeTaxiStatus",              // 高德打车
            "com.autonavi.minimap#Navigation.NotifyCruiseTrafficLightStatus",     // 高德巡航
            "com.tencent.map#Navigation.NotifyDrivingNavigationStatus",           // 腾讯驾车
            "com.tencent.map#Navigation.NotifyCruiseTrafficLightStatus",          // 腾讯巡航
            "com.baidu.BaiduMap#Navigation.NotifyDrivingNavigationStatus",        // 百度
            "com.baidu.navi#Navigation.NotifyDrivingNavigationStatus",            // 百度导航
    };
    private volatile BroadcastReceiver navReceiver;
    private volatile int navRxCount = 0;
    /** 从导航广播解析出的「驾车中」状态（真实格式待实测校准） */
    private volatile boolean navDriving = false;
    private final float[] gravity = new float[3];
    private boolean gravityInit;
    private long lastSampleNs = 0L;
    private volatile float lastGyro = 0f;
    private final float[] lastGyroVec = new float[3];
    private final float[] gravVec = new float[3];
    private static volatile Sensor sGrav;

    private volatile float gain = 1.0f;
    private volatile int mode = 2;
    private volatile int testMode = 0;
    private volatile boolean enabled = true;
    private volatile float vehRms = 0.10f;
    private volatile float vehStop = 0.045f;
    private volatile float vehHold = 5f;

    private float lastAx, lastAy, lastAz;
    private long sLastMotionAt = 0L;
    private float sAppliedAlpha = -1f;
    private long sLastErr = 0L;
    private static final long ALPHA_TAIL_MS = 5000L;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!"android".equals(lp.packageName)) return;
        sLoader = lp.classLoader;
        log("injected into system_server");
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boot();
                } catch (Throwable tr) {
                    log("boot failed: " + Log.getStackTraceString(tr));
                }
            }
        }, "antidizzy-bridge");
        t.setDaemon(true);
        t.start();
    }

    // =============================================================== 启动

    private void boot() throws Throwable {
        Object manager = waitManager();
        if (manager == null) {
            log("找不到 AntiDizzyManager —— 该固件可能没有 AntiDizzy 实现，退出");
            return;
        }
        sManager = manager;
        resolveReflection();

        sCtx = systemContext();
        if (sCtx == null) {
            log("no system context, abort");
            return;
        }
        sSm = (SensorManager) sCtx.getSystemService(Context.SENSOR_SERVICE);
        if (sSm == null) {
            log("no SensorManager, abort");
            return;
        }
        if (hasNativeCarSensor()) {
            log("检测到原厂 car motion/car scene 传感器 → 不注入，保持原厂行为");
            return;
        }

        pickMotionSource();
        if (sAccel == null) {
            log("没有可用的加速度计，退出");
            return;
        }
        installShaderHook();
        sReady = true;
        startRefresher();
        startReporter();
        registerSensors();
        registerNavReceiver();

        log("ready: source=" + (sUseLinear ? "LINEAR_ACCELERATION" : "ACCELEROMETER")
                + " gyro=" + (sGyro != null));
    }

    private Object waitManager() {
        for (int i = 0; i < 600; i++) {
            try {
                Class<?> c = cls(CLS_MANAGER);
                Object m = c.getMethod("getInstance").invoke(null);
                if (m != null) return m;
            } catch (Throwable t) {
                if (i % 40 == 0) log("waiting AntiDizzyManager: " + t);
            }
            try { Thread.sleep(500); } catch (Throwable ignored) {}
        }
        return null;
    }

    private boolean hasNativeCarSensor() {
        try {
            List<Sensor> list = sSm.getSensorList(Sensor.TYPE_ALL);
            if (list == null) return false;
            for (Sensor s : list) {
                int t = s.getType();
                if (t == CAR_MOTION_TYPE || t == CAR_SCENE_TYPE) return true;
                String n = s.getName();
                if (n != null) {
                    String ln = n.toLowerCase();
                    if (ln.contains("car motion") || ln.contains("car scene")) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void pickMotionSource() {
        try {
            Sensor lin = sSm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            if (lin != null) {
                sAccel = lin;
                sUseLinear = true;
            } else {
                sAccel = sSm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                sUseLinear = false;
            }
            sGyro = sSm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            sGrav = sSm.getDefaultSensor(Sensor.TYPE_GRAVITY);
        } catch (Throwable ignored) {
        }
    }

    private void registerSensors() {
        SensorEventListener l = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                try {
                    onMotion(e);
                } catch (Throwable ignored) {
                }
            }
            @Override
            public void onAccuracyChanged(Sensor s, int a) {
            }
        };
        SensorEventListener gl = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                try {
                    float[] v = e.values;
                    lastGyroVec[0] = v[0];
                    lastGyroVec[1] = v[1];
                    lastGyroVec[2] = v[2];
                    lastGyro = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
                } catch (Throwable ignored) {
                }
            }
            @Override
            public void onAccuracyChanged(Sensor s, int a) {
            }
        };
        SensorEventListener gv = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                try {
                    float[] v = e.values;
                    gravVec[0] = v[0];
                    gravVec[1] = v[1];
                    gravVec[2] = v[2];
                } catch (Throwable ignored) {
                }
            }
            @Override
            public void onAccuracyChanged(Sensor s, int a) {
            }
        };
        int rate = sUseLinear ? 10000 : 20000;
        if (sAccel != null) sSm.registerListener(l, sAccel, rate);
        if (sGyro != null) sSm.registerListener(gl, sGyro, 20000);
        if (sGrav != null) sSm.registerListener(gv, sGrav, 20000);
    }

    // ============================================================ 反射解析

    private void resolveReflection() {
        String inst = null;
        try {
            Class<?> mgr = cls(CLS_MANAGER);
            inst = firstField(mgr, F_INSTANCES);
            if (inst != null) {
                Field f = mgr.getDeclaredField(inst);
                f.setAccessible(true);
                sInstances = (Map<?, ?>) f.get(sManager);
            }
        } catch (Throwable t) {
            log("instances resolve failed: " + t);
        }

        try {
            Class<?> feat = cls(CLS_FEATURE);
            sHandle = findMethod(feat, M_HANDLE, SensorEvent.class);
            fSettings = findField(feat, F_SETTINGS);
            fNeedDraw = findField(feat, F_NEEDDRAW);
            fView = findField(feat, F_VIEW);
            log("reflect: handle=" + (sHandle != null)
                    + " settings=" + (fSettings != null)
                    + " needDraw=" + (fNeedDraw != null)
                    + " view=" + (fView != null)
                    + " instField=" + inst);
        } catch (Throwable t) {
            log("feature reflect failed: " + t);
        }

        Class<?> featCls = null;
        try {
            featCls = cls(CLS_FEATURE);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> view = cls(CLS_VIEW);
            sAlpha = findMethod(view, M_ALPHA, float.class);
            sSkipShot = findMethod(view, M_SKIPSHOT, boolean.class);
            if (featCls != null) sCreateView = findMethod(featCls, M_CREATEVIEW);
        } catch (Throwable t) {
            log("view reflect failed: " + t);
        }

        try {
            Class<?> se = cls("android.hardware.SensorEvent");
            Constructor<?> ctor;
            try {
                ctor = se.getDeclaredConstructor(int.class);
            } catch (Throwable t) {
                ctor = se.getDeclaredConstructor(int.class, int.class);
            }
            ctor.setAccessible(true);
            sEvCtor = ctor;
            fValues = accessible(se, "values");
            fSensor = accessible(se, "sensor");
            fTimestamp = accessible(se, "timestamp");
            fAccuracy = accessible(se, "accuracy");
        } catch (Throwable t) {
            log("SensorEvent reflect failed: " + t);
        }
    }

    private static Field accessible(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field findField(Class<?> c, String[] names) {
        for (String n : names) {
            Field f = accessible(c, n);
            if (f != null) return f;
        }
        return null;
    }

    private static String firstField(Class<?> c, String[] names) {
        for (String n : names) {
            try {
                c.getDeclaredField(n);
                return n;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> c, String[] names, Class<?>... params) {
        for (String n : names) {
            try {
                Method m = c.getDeclaredMethod(n, params);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Class<?> cls(String name) throws ClassNotFoundException {
        ClassLoader l = sLoader;
        return l != null ? Class.forName(name, false, l) : Class.forName(name);
    }

    private Context systemContext() {
        try {
            Class<?> at = cls("android.app.ActivityThread");
            Object cur = at.getMethod("currentActivityThread").invoke(null);
            if (cur != null) return (Context) at.getMethod("getSystemContext").invoke(cur);
        } catch (Throwable ignored) {
        }
        try {
            Object app = cls("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ========================================================== 后台维护

    private void startRefresher() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        refreshInstances();
                        refreshSettings();
                        Thread.sleep(2000);
                    } catch (Throwable ignored) {
                        try { Thread.sleep(2000); } catch (Throwable ignored2) {}
                    }
                }
            }
        }, "antidizzy-bridge-refresh");
        t.setDaemon(true);
        t.start();
    }

    private void startReporter() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(5000);
                        log("state: " + detector.state()
                                + " mode=" + mode + " test=" + testMode
                                + " inst=" + (sInstances == null ? -1 : sInstances.size())
                                + " a=(" + r(lastAx) + "," + r(lastAy) + "," + r(lastAz) + ")"
                                + " gyro=" + r(lastGyro)
                                + " nav=" + navDriving + "/rx" + navRxCount
                                + " alpha=" + sAppliedAlpha);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "antidizzy-bridge-report");
        t.setDaemon(true);
        t.start();
    }

    private static String r(float v) {
        return String.valueOf(Math.round(v * 100) / 100f);
    }

    private void refreshInstances() {
        if (sManager == null) return;
        try {
            Class<?> mgr = cls(CLS_MANAGER);
            String n = firstField(mgr, F_INSTANCES);
            if (n == null) return;
            Field f = mgr.getDeclaredField(n);
            f.setAccessible(true);
            sInstances = (Map<?, ?>) f.get(sManager);
        } catch (Throwable ignored) {
        }
    }

    private void refreshSettings() {
        Context c = sCtx;
        if (c == null) return;
        try {
            enabled = Settings.Secure.getInt(c.getContentResolver(),
                    "oplus_customize_antidizzy_enable", 0) == 1;
            mode = Settings.Secure.getInt(c.getContentResolver(),
                    "oplus_customize_antidizzy_mode", 2);
            testMode = Settings.Secure.getInt(c.getContentResolver(),
                    "motion_cues_bridge_test", 0);
            gain = readFloat(c, "motion_cues_gain", 1.0f);
            vehRms = readFloat(c, "motion_cues_veh_rms", 0.10f);
            vehStop = readFloat(c, "motion_cues_veh_stop", 0.045f);
            vehHold = readFloat(c, "motion_cues_veh_hold", 5f);
            detector.enterRms = vehRms;
            detector.exitRms = vehStop;
            detector.enterHoldSec = vehHold;
            detector.enterLowRatio = readFloat(c, "motion_cues_veh_low", 0.55f);
            detector.enterRot = readFloat(c, "motion_cues_veh_rot", 0.12f);
            detector.stepReject = readFloat(c, "motion_cues_veh_step", 0.45f);
        } catch (Throwable ignored) {
        }
    }

    /** 注册导航广播监听：广播本身是公开的，system_server 也能收。 */
    private void registerNavReceiver() {
        try {
            IntentFilter f = new IntentFilter();
            for (String a : NAV_ACTIONS) f.addAction(a);
            // 千万不要 addDataType：带 type 的 filter 只匹配有 MIME 的 intent，
            // 导航广播没有 type，加了就永远收不到（本次排查花了一轮部署才定位）
            navReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        navRxCount++;
                        StringBuilder sb = new StringBuilder(intent.getAction());
                        String pkg = intent.getPackage();
                        if (pkg != null) sb.append(" pkg=").append(pkg);
                        android.os.Bundle ex = intent.getExtras();
                        if (ex != null) {
                            sb.append(" extras={");
                            for (String k : ex.keySet()) {
                                sb.append(k).append('=').append(ex.get(k)).append(", ");
                            }
                            sb.append('}');
                        }
                        log("nav rx: " + sb);
                        boolean d = guessDriving(intent);
                        if (d != navDriving) {
                            navDriving = d;
                            log("nav driving -> " + d);
                        }
                    } catch (Throwable ignored) {}
                }
            };
            sCtx.registerReceiver(navReceiver, f);
            log("nav receiver registered (" + NAV_ACTIONS.length + " actions)");
        } catch (Throwable t) {
            log("nav receiver failed: " + t);
        }
    }

    /**
     * 启发式解析导航广播里的「驾车中」状态。
     * 真实格式以日志 dump 为准，这里先覆盖常见的 key 约定。
     */
    private boolean guessDriving(Intent intent) {
        android.os.Bundle ex = intent.getExtras();
        if (ex == null) return navDriving;
        for (String k : new String[]{"status", "navigation_status", "NavigationStatus"}) {
            Object v = ex.get(k);
            if (v instanceof Integer) return (Integer) v == 1;
        }
        for (String k : new String[]{"isNavigation", "isNavigating", "isDriving"}) {
            Object v = ex.get(k);
            if (v instanceof Boolean) return (Boolean) v;
        }
        return navDriving;
    }

    private static float readFloat(Context c, String key, float def) {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), key);
            if (s == null || s.trim().isEmpty()) return def;
            float v = Float.parseFloat(s.trim());
            return v > 0 ? v : def;
        } catch (Throwable t) {
            return def;
        }
    }

    // ============================================================ 数据处理

    private void onMotion(SensorEvent e) {
        if (!sReady || e == null || e.values == null) return;
        float[] v = e.values;

        long now = System.nanoTime();
        float dt = lastSampleNs == 0 ? 0.02f : Math.min(0.2f, (now - lastSampleNs) / 1e9f);
        lastSampleNs = now;

        float ax, ay, az, gx, gy, gz, vx, vy, vz;
        if (testMode > 0) {
            float[] s = synth(testMode);
            ax = s[0]; ay = s[1]; az = s[2];
            gx = s[3]; gy = s[4]; gz = s[5];
            vx = 0f; vy = 9.8f; vz = 0f;      // 模拟模式固定 y 轴为重力方向
        } else if (sUseLinear) {
            ax = v[0]; ay = v[1]; az = v[2];
            gx = lastGyroVec[0]; gy = lastGyroVec[1]; gz = lastGyroVec[2];
            vx = gravVec[0]; vy = gravVec[1]; vz = gravVec[2];
        } else {
            if (!gravityInit) {
                gravity[0] = v[0]; gravity[1] = v[1]; gravity[2] = v[2];
                gravityInit = true;
                return;
            }
            for (int i = 0; i < 3; i++) gravity[i] = gravity[i] * 0.94f + v[i] * 0.06f;
            ax = v[0] - gravity[0];
            ay = v[1] - gravity[1];
            az = v[2] - gravity[2];
            gx = lastGyroVec[0]; gy = lastGyroVec[1]; gz = lastGyroVec[2];
            vx = gravity[0]; vy = gravity[1]; vz = gravity[2];
        }

        lastAx = ax; lastAy = ay; lastAz = az;
        // 检测器吃原始数据（不做死区/增益），否则会抹掉低幅车振
        detector.setSampleRate(1f / Math.max(0.005f, dt));
        detector.onSample(ax, ay, az, gx, gy, gz, vx, vy, vz, dt);

        if (Math.abs(ax) < 0.02f) ax = 0f;
        if (Math.abs(ay) < 0.02f) ay = 0f;
        if (Math.abs(az) < 0.02f) az = 0f;

        ax *= gain; ay *= gain; az *= gain;

        dispatch(ax, ay, az);
    }

    private float[] synth(int m) {
        double t = System.currentTimeMillis() / 1000.0;
        if (m == 2) {
            // 车辆：宽带低频路面激励（频率避开 1.2~2.8Hz 步频段，符合真实路谱）+ 缓慢车身姿态变化
            float road = 0.45f * (float) Math.sin(2 * Math.PI * 0.70 * t)
                    + 0.35f * (float) Math.sin(2 * Math.PI * 1.05 * t + 1.1)
                    + 0.40f * (float) Math.sin(2 * Math.PI * 3.30 * t + 2.3)
                    + 0.30f * (float) Math.sin(2 * Math.PI * 4.60 * t + 0.5)
                    + 0.25f * (float) Math.sin(2 * Math.PI * 5.80 * t + 3.1)
                    + 0.22f * (float) (Math.random() - 0.5);
            float lon = (float) (0.9 * Math.sin(2 * Math.PI * 0.07 * t));
            float lat = (float) (0.5 * Math.sin(2 * Math.PI * 0.043 * t));
            float rr = (float) (0.03 + 0.03 * Math.abs(Math.sin(2 * Math.PI * 0.02 * t)));
            return new float[]{lat + road * 0.35f, lon + road, road * 0.30f,
                    rr, rr * 0.6f, rr * 0.5f};
        }
        if (m == 3) {
            // 走路：1.75Hz 周期冲击
            float step = (float) (1.3 * Math.sin(2 * Math.PI * 1.75 * t));
            float noise = (float) ((Math.random() - 0.5) * 0.25);
            return new float[]{noise, step + noise, noise, 0.30f, 0.25f, 0.20f};
        }
        if (m == 4) {
            // 手持微动：8~12Hz 小幅高频 + 陀螺中等活跃（不足以触发抑制，但姿态不稳）
            float hf = 0.22f * (float) Math.sin(2 * Math.PI * 9.5 * t)
                    + 0.16f * (float) Math.sin(2 * Math.PI * 11.7 * t + 0.8)
                    + 0.10f * (float) (Math.random() - 0.5);
            float g = (float) (0.26 + 0.12 * Math.sin(2 * Math.PI * 0.35 * t));
            return new float[]{hf * 0.8f, hf, hf * 0.6f, g, g * 0.7f, g * 0.5f};
        }
        // 正弦振荡：仅用于确认原生渲染链路是否打通
        float a = (float) (3.0 * Math.sin(2 * Math.PI * 0.4 * t));
        float b = (float) (3.0 * Math.cos(2 * Math.PI * 0.4 * t));
        return new float[]{a, b, 0f, 0f, 0f, 0f};
    }

    private void dispatch(float ax, float ay, float az) {
        Map<?, ?> map = sInstances;
        if (map == null || map.isEmpty() || sHandle == null || sEvCtor == null) return;

        for (Object ctrl : new ArrayList<Object>(map.values())) {
            if (ctrl == null) continue;
            boolean on = true;
            try {
                if (fSettings != null) on = fSettings.getBoolean(ctrl);
            } catch (Throwable ignored) {
            }
            if (!on) {
                applyAlpha(ctrl, 0f);
                continue;
            }
            try {
                sHandle.invoke(ctrl, buildEvent(ax, ay, az));
                boolean nd = false;
                try {
                    if (fNeedDraw != null) nd = fNeedDraw.getBoolean(ctrl);
                } catch (Throwable ignored) {
                }
                if (nd) sLastMotionAt = System.currentTimeMillis();

                // 「仅车辆运动」模式下原厂不会预先创建浮层，检测到车辆时主动拉起
                if (mode == 1 && enabled && detector.isInVehicle()
                        && fView != null && fView.get(ctrl) == null
                        && sCreateView != null
                        && System.currentTimeMillis() - sLastCreate > 3000) {
                    sLastCreate = System.currentTimeMillis();
                    try {
                        sCreateView.invoke(ctrl);
                        log("requested createAntiDizzyView (vehicle detected)");
                    } catch (Throwable t) {
                        log("createAntiDizzyView failed: " + t);
                    }
                }
                float target;
                if (!enabled) {
                    target = 0f;
                } else if (mode == 2) {
                    target = 1f;
                } else if (mode == 1) {
                    boolean veh = detector.isInVehicle()
                            || System.currentTimeMillis() - sLastMotionAt < ALPHA_TAIL_MS;
                    target = veh ? 1f : 0f;
                } else {
                    target = 1f;
                }
                applyAlpha(ctrl, target);
            } catch (Throwable t) {
                if (System.currentTimeMillis() - sLastErr > 10000) {
                    sLastErr = System.currentTimeMillis();
                    log("dispatch failed: " + t);
                }
            }
        }
    }

    private long sLastCreate = 0L;

    // ============================================================ 圆点抗锯齿
    //
    // 原厂 AntiDizzyView 用 GLES 画「单位圆三角形扇」：
    //   vertex : gl_Position = proj_matrix * position;   （position 是单位圆坐标）
    //   fragment: gl_FragColor = color;                  （纯色填充，边缘是硬切）
    // fragment 里既没有边缘距离也没有导数，所以球边缘完全依赖几何边界 → 明显锯齿。
    //
    // 做法：hook GLES20.glShaderSource，把两个 shader 换成等价但带边缘平滑的版本。
    //   - vertex 额外传出单位圆坐标 vLocal
    //   - fragment 用 length(vLocal) 求归一化半径，边缘用 fwidth 做 1 像素宽的抗锯齿
    //     fwidth 自带分辨率自适应：球在任何尺寸、任何屏幕密度下边缘都是 1px 过渡，
    //     所以不需要针对机型/分辨率配参数。
    //
    // 安全性：替换前先用同样的 GL context 试编译一次，编译不过就保留原 shader，
    // 避免把「画不出来」当成副作用。

    private static final String VS_AA =
            "attribute vec4 position;\n"
            + "uniform mat4 proj_matrix;\n"
            + "varying vec2 vLocal;\n"
            + "void main() {\n"
            + "  vLocal = position.xy;\n"
            + "  gl_Position = proj_matrix * position;\n"
            + "}\n";

    private static final String FS_AA_HQ =
            "#extension GL_OES_standard_derivatives : enable\n"
            + "precision mediump float;\n"
            + "uniform vec4 color;\n"
            + "varying vec2 vLocal;\n"
            + "void main() {\n"
            + "  float d = length(vLocal);\n"
            + "  float w = max(fwidth(d), 0.002);\n"
            + "  float a = 1.0 - smoothstep(1.0 - w, 1.0, d);\n"
            + "  if (a <= 0.004) discard;\n"
            + "  gl_FragColor = vec4(color.rgb, color.a * a);\n"
            + "}\n";

    private static final String FS_AA_FIX =
            "precision mediump float;\n"
            + "uniform vec4 color;\n"
            + "varying vec2 vLocal;\n"
            + "void main() {\n"
            + "  float d = length(vLocal);\n"
            + "  float a = 1.0 - smoothstep(0.96, 1.0, d);\n"
            + "  if (a <= 0.004) discard;\n"
            + "  gl_FragColor = vec4(color.rgb, color.a * a);\n"
            + "}\n";

    private static volatile boolean sVertexAAReady = false;
    /** tryCompile 内部会调用 glShaderSource，必须防止被自己的 hook 再次拦截（否则无限递归） */
    private static final ThreadLocal<Boolean> sInTryCompile = new ThreadLocal<Boolean>();
    private static volatile boolean sExtChecked = false;
    private static volatile boolean sDerivOk = false;

    private static boolean hasDerivatives() {
        if (sExtChecked) return sDerivOk;
        try {
            String ext = GLES20.glGetString(GLES20.GL_EXTENSIONS);
            if (ext == null) return false;         // context 还没就绪，下次再问
            sDerivOk = ext.contains("GL_OES_standard_derivatives");
            sExtChecked = true;
            return sDerivOk;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 用当前 context 试编译，确认可以替换再换。 */
    private static boolean tryCompile(int type, String src) {
        if (Boolean.TRUE.equals(sInTryCompile.get())) return false;
        sInTryCompile.set(Boolean.TRUE);
        int sh = 0;
        try {
            sh = GLES20.glCreateShader(type);
            if (sh == 0) return false;
            GLES20.glShaderSource(sh, src);
            GLES20.glCompileShader(sh);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0);
            return ok[0] != 0;
        } catch (Throwable t) {
            return false;
        } finally {
            sInTryCompile.remove();
            if (sh != 0) {
                try {
                    GLES20.glDeleteShader(sh);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void installShaderHook() {
        try {
            Method m = GLES20.class.getDeclaredMethod("glShaderSource", int.class, String.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (Boolean.TRUE.equals(sInTryCompile.get())) return;
                        Object a1 = param.args[1];
                        if (!(a1 instanceof String)) return;
                        String src = (String) a1;
                        if (src.isEmpty()) return;

                        if (src.contains("proj_matrix * position")) {
                            if (tryCompile(GLES20.GL_VERTEX_SHADER, VS_AA)) {
                                param.args[1] = VS_AA;
                                sVertexAAReady = true;
                                log("shader: vertex patched (vLocal out)");
                            } else {
                                log("shader: vertex AA compile failed, keep original");
                            }
                        } else if (src.contains("gl_FragColor = color;")) {
                            if (!sVertexAAReady) return;    // 没有 vLocal 不能单独换
                            boolean hq = hasDerivatives();
                            String dst = hq ? FS_AA_HQ : FS_AA_FIX;
                            if (tryCompile(GLES20.GL_FRAGMENT_SHADER, dst)) {
                                param.args[1] = dst;
                                log("shader: fragment patched (" + (hq ? "fwidth" : "fixed") + " AA)");
                            } else {
                                log("shader: fragment AA compile failed, keep original");
                            }
                        }
                    } catch (Throwable t) {
                        log("shader patch error: " + t);
                    }
                }
            });
            log("shader hook installed");
        } catch (Throwable t) {
            log("shader hook failed: " + t);
        }
    }

    private void applyAlpha(Object ctrl, float target) {
        try {
            Object view = fView == null ? null : fView.get(ctrl);
            if (view == null || sAlpha == null) return;

            // 视图可能被原厂重建（切模式/旋转/销毁重建），每次换对象都要重新设置
            if (view != sAlphaView) {
                sAlphaView = view;
                sAlphaApplied = false;
                if (sSkipShot != null) {
                    try {
                        sSkipShot.invoke(view, false);
                        log("skipScreenshot disabled");
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (!sAlphaApplied || Math.abs(target - sAppliedAlpha) > 0.01f) {
                sAlpha.invoke(view, target);
                sAppliedAlpha = target;
                sAlphaApplied = true;
            }
        } catch (Throwable t) {
            if (System.currentTimeMillis() - sLastErr > 10000) {
                sLastErr = System.currentTimeMillis();
                log("applyAlpha failed: " + t);
            }
        }
    }

    private SensorEvent buildEvent(float ax, float ay, float az) throws Exception {
        SensorEvent ev = (SensorEvent) sEvCtor.newInstance(16);
        float[] v = new float[16];
        v[1] = ax; v[2] = ay;
        v[4] = ax; v[5] = ay;
        v[8] = ax; v[9] = ay; v[10] = az;
        if (fValues != null) fValues.set(ev, v);
        if (fSensor != null) fSensor.set(ev, sAccel);
        if (fTimestamp != null) fTimestamp.setLong(ev, System.nanoTime());
        if (fAccuracy != null) fAccuracy.setInt(ev, 3);
        return ev;
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
        }
    }
}
