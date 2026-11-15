package com.workbuddy.antidizzybridge;

/**
 * 车辆运动识别（替代 OPPO 私有 car motion / car scene 传感器）。
 *
 * <p>设计目标：只有「真的在车上行驶」才判定 true。手持手机时的任何动作
 * （手抖、点屏、拿起放下、走路）都不应触发。
 *
 * <p>判据（进入需全部满足，并持续 enterHoldSec）：
 * <ul>
 *   <li>rms        振动能量        车辆路面激励 0.1~1.5 m/s²</li>
 *   <li>lowRatio   1~6Hz 能量占比   车辆低频为主（≥0.55）；手抖 8~12Hz（0.2~0.4）</li>
 *   <li>vertRatio  重力方向能量占比 车振以竖直为主（≥0.45）；手部晃动各向分散</li>
 *   <li>rotRms     陀螺角速度 RMS   手机固定时很小（&lt;0.12）；手持必然大</li>
 *   <li>step       步态周期性      走路 ≥0.5；车辆无周期 ≈0</li>
 * </ul>
 *
 * <p>另有「手持抑制」：检出明显的角速度突变（拿起/翻转）后，一段时间内禁止进入车辆态。
 */
public class VehicleDetector {

    private static final int WIN = 300;            // 3s 窗口（100Hz）
    private static final float EVAL_DT = 0.5f;     // 评估周期

    private final float[] magBuf = new float[WIN];   // |a|（含 AC 分量）
    private final float[] lowBuf = new float[WIN];   // 6Hz 低通输出
    private final float[] vertBuf = new float[WIN];  // 重力方向投影
    private final float[] rotBuf = new float[WIN];   // 陀螺模长
    private int idx = 0;
    private int filled = 0;

    private float dtEst = 0.01f;
    private float lp1 = 0f, lp2 = 0f;

    private final float[] grav = new float[3];
    private boolean gravOk = false;

    // ---------------- 可调阈值 ----------------
    public float enterRms = 0.12f;          // 进入：振动能量下限
    public float exitRms = 0.05f;           // 退出：能量上限
    public float enterLowRatio = 0.55f;     // 进入：低频能量占比下限
    public float exitLowRatio = 0.35f;      // 退出：低频占比下限
    public float enterVertRatio = 0.45f;    // 进入：竖直方向占比下限
    public float enterRot = 0.12f;          // 进入：陀螺 RMS 上限（姿态必须稳定）
    public float exitRot = 0.30f;           // 退出：陀螺 RMS 上限
    public float stepReject = 0.45f;        // 步态周期性强于此值判为走路
    public float enterHoldSec = 8f;         // 连续满足多久才进入
    public float exitHoldSec = 5f;          // 连续不满足多久才退出
    public float suppressSec = 8f;          // 手持抑制时长

    private float enterAccum = 0f, exitAccum = 0f, evalTimer = 0f, suppressLeft = 0f;
    private boolean inVehicle = false;

    // ---------------- 诊断输出 ----------------
    public volatile float lastRms, lastLow, lastVert, lastRot, lastStep;
    public volatile boolean lastSuppressed;

    public void setSampleRate(float hz) {
        if (hz > 5f && hz < 500f) dtEst = 1f / hz;
    }

    public boolean isInVehicle() {
        return inVehicle;
    }

    /**
     * 每次加速度采样调用一次。
     *
     * @param ax,ay,az 去重力后的线加速度（m/s²）
     * @param gx,gy,gz 陀螺角速度（rad/s），无陀螺时全 0
     * @param vx,vy,vz 重力向量（m/s²），无重力传感器时全 0
     */
    public void onSample(float ax, float ay, float az,
                         float gx, float gy, float gz,
                         float vx, float vy, float vz,
                         float dtSec) {
        if (dtSec > 0.002f && dtSec < 0.1f) dtEst = dtSec;

        grav[0] = vx;
        grav[1] = vy;
        grav[2] = vz;
        gravOk = (vx * vx + vy * vy + vz * vz) > 1.0f;

        float mag = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        float rot = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);

        // 6Hz 二阶低通（两级一阶级联），把车振低频段与手抖高频段分开
        float a = 1f - (float) Math.exp(-2.0 * Math.PI * 6.0 * dtEst);
        lp1 += a * (mag - lp1);
        lp2 += a * (lp1 - lp2);

        float vert = 0f;
        if (gravOk) {
            float n = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
            vert = (ax * vx + ay * vy + az * vz) / n;
        }

        magBuf[idx] = mag;
        lowBuf[idx] = lp2;
        vertBuf[idx] = gravOk ? vert : mag;   // 无重力传感器时退化为模长
        rotBuf[idx] = rot;
        idx = (idx + 1) % WIN;
        if (filled < WIN) filled++;

        // 手持抑制：明显的腕部动作 / 剧烈冲击后，一段时间内不判车辆
        if (rot > 0.60f || mag > 4.0f) suppressLeft = suppressSec;
        if (suppressLeft > 0f) suppressLeft = Math.max(0f, suppressLeft - dtEst);

        evalTimer += dtEst;
        if (evalTimer < EVAL_DT) return;
        evalTimer = 0f;
        if (filled < WIN) return;             // 等 3s 窗口填满

        evaluate();
    }

    private void evaluate() {
        int n = filled;

        float sMag = 0f, sLow = 0f;
        for (int i = 0; i < n; i++) {
            sMag += magBuf[i];
            sLow += lowBuf[i];
        }
        float mMag = sMag / n;
        float mLow = sLow / n;

        float eMag = 0f, eLow = 0f, eVert = 0f, eRot = 0f;
        for (int i = 0; i < n; i++) {
            float d = magBuf[i] - mMag;
            eMag += d * d;
            float l = lowBuf[i] - mLow;
            eLow += l * l;
            float v = vertBuf[i];
            eVert += v * v;
            float r = rotBuf[i];
            eRot += r * r;
        }

        float rms = (float) Math.sqrt(eMag / n);
        float rmsLow = (float) Math.sqrt(eLow / n);
        float rmsVert = (float) Math.sqrt(eVert / n);
        float rot = (float) Math.sqrt(eRot / n);

        float lowRatio = rms > 1e-4f ? Math.min(1f, rmsLow / rms) : 0f;
        float vertRatio = rms > 1e-4f ? Math.min(1f, rmsVert / rms) : 0f;
        float step = stepPeriodicity(n);

        lastRms = rms;
        lastLow = lowRatio;
        lastVert = vertRatio;
        lastRot = rot;
        lastStep = step;
        lastSuppressed = suppressLeft > 0f;

        boolean walking = step > stepReject;
        boolean suppressed = suppressLeft > 0f;

        if (!inVehicle) {
            boolean ok = rms > enterRms
                    && lowRatio > enterLowRatio
                    && (!gravOk || vertRatio > enterVertRatio)
                    && rot < enterRot
                    && !walking
                    && !suppressed;
            if (ok) {
                enterAccum += EVAL_DT;
                if (enterAccum >= enterHoldSec) {
                    inVehicle = true;
                    enterAccum = 0f;
                }
            } else {
                enterAccum = 0f;
            }
        } else {
            boolean exit = rms < exitRms
                    || lowRatio < exitLowRatio
                    || rot > exitRot
                    || walking;
            if (exit) {
                exitAccum += EVAL_DT;
                if (exitAccum >= exitHoldSec) {
                    inVehicle = false;
                    exitAccum = 0f;
                }
            } else {
                exitAccum = 0f;
            }
        }
    }

    /**
     * 重力方向分量自相关，取步频区间（1.2~2.8Hz）内的归一化峰值。
     * 走路有稳定的 1.5~2Hz 周期冲击，车辆宽带振动没有。
     */
    private float stepPeriodicity(int n) {
        float mean = 0f;
        for (int i = 0; i < n; i++) mean += vertBuf[i];
        mean /= n;

        float energy = 0f;
        for (int i = 0; i < n; i++) {
            float v = vertBuf[i] - mean;
            energy += v * v;
        }
        if (energy < 1e-4f) return 0f;

        float sr = 1f / dtEst;
        int lagMin = Math.max(2, (int) (sr / 2.8f));
        int lagMax = Math.min(n / 2, (int) (sr / 1.2f));
        if (lagMax <= lagMin) return 0f;

        float best = 0f;
        for (int lag = lagMin; lag <= lagMax; lag++) {
            float acc = 0f, e1 = 0f, e2 = 0f;
            for (int i = 0; i + lag < n; i++) {
                float a = vertBuf[i] - mean;
                float b = vertBuf[i + lag] - mean;
                acc += a * b;
                e1 += a * a;
                e2 += b * b;
            }
            if (e1 <= 1e-6f || e2 <= 1e-6f) continue;
            float norm = acc / (float) Math.sqrt(e1 * e2);
            if (norm > best) best = norm;
        }
        return best;
    }

    public String state() {
        return "veh=" + inVehicle
                + " rms=" + round(lastRms)
                + " low=" + round(lastLow)
                + " vert=" + round(lastVert)
                + " rot=" + round(lastRot)
                + " step=" + round(lastStep)
                + " sup=" + lastSuppressed;
    }

    private static String round(float v) {
        return String.valueOf(Math.round(v * 100) / 100f);
    }
}
