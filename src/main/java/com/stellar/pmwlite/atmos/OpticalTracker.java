package com.stellar.pmwlite.atmos;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 光学现象状态机 / Optical phenomenon state machine（服务端）。
 *
 * 每个维度一个实例。职责：
 *  1. 从 {@link AtmosphereGrid.Probe} 取数，补上太阳角/地磁纬度/Kp 代理，喂给 {@link OpticalField}；
 *  2. 对理想强度做**时间平滑**（淡入快、淡出慢），避免云飘过阈值时现象硬闪；
 *  3. 累计持续秒数、判定 onset/hold/decay 三阶段，供 HUD 描述"正在形成/稳定/消退"；
 *  4. 提供当前快照给网络包与雷达。
 *
 * 地磁纬度与 Kp 是**艺术化代理**：Minecraft 没有地磁场和太阳风。
 *  - 地磁纬度：用 |z| / 24 映射到 0..90°，鼓励玩家去"高纬"看极光（纯粹地理隐喻）。
 *  - Kp 代理：用当前雷暴等级 + 近场强风暴数量映射 0..9（把"空间天气剧烈"隐喻为"天空情绪剧烈"）。
 *  这两条都在文档里明确标为艺术化，不冒充物理。
 */
public final class OpticalTracker {
    /** 淡入时间常数（秒）——现象出现得干脆。 */
    private static final float RISE_TAU = 1.5f;
    /** 淡出时间常数（秒）——现象退得慢，避免闪烁。 */
    private static final float FALL_TAU = 4.0f;
    /** 主导现象的最低可见强度。 */
    private static final float VISIBLE = 0.18f;

    private final OpticalField.Input input = new OpticalField.Input();
    private final OpticalField.Result target = new OpticalField.Result();
    private final OpticalField.Result current = new OpticalField.Result();
    /** 每种现象的平滑强度。 */
    private final float[] smooth = new float[OpticalField.KIND_COUNT];
    /** 每种现象连续可见的秒数。 */
    private final int[] duration = new int[OpticalField.KIND_COUNT];
    private OpticalField.Kind dominant = OpticalField.Kind.NONE;
    private boolean artistic = false;
    private String phase = "静默";
    private boolean enabled = true;

    /** 每 tick 调用；内部按 1 Hz 推进状态，避免每 tick 都跑求解。 */
    public void tick(ServerLevel level, AtmosphereGrid grid, Vec3 pos,
                     AtmosphereGrid.Probe probe, StormLike storms, long gameTime) {
        if (!enabled) {
            clear();
            return;
        }
        // 只有整秒才重算目标（1 Hz），其余 tick 不动，平滑在整秒处做
        if (gameTime % 20L != 0L) return;

        input.tempC = probe.tempC;
        input.humidity = probe.humidity;
        input.dewPointC = probe.dewPointC;
        input.cloud = probe.cloud;
        input.cloudBaseM = probe.cloudBaseM;
        input.so2 = probe.gases[GasField.SO2];
        input.no2 = probe.gases[GasField.NO2];
        input.co = probe.gases[GasField.CO];
        input.pm = probe.gases[GasField.PM];
        input.co2 = probe.co2;
        input.underground = probe.underground;
        long dayTime = level.getDayTime();
        input.sunAltitudeDeg = OpticalField.sunAltitude(dayTime);

        // v0.3.4：降水、逆温代理、月亮状态。
        // 逆温用"空气比露点干很多 + 天空通透"作代理：晴朗静稳夜最容易形成辐射逆温，
        // 这是法塔莫甘娜的必要条件；不到 4℃ 时求解器直接给 0。
        input.rain = level.getRainLevel(1f);
        input.inversionC = Mth.clamp((probe.tempC - probe.dewPointC) * 0.55f
                * clamp01(1f - probe.cloud), 0f, 12f);
        input.moonAltitudeDeg = LunarField.altitudeDeg(dayTime);
        input.moonIllum = LunarField.illumination(dayTime);
        input.moonEclipse = LunarField.eclipse(dayTime);

        // 艺术化代理量
        input.geomagneticLat = Mth.clamp((float) Math.abs(pos.z) / 24f, 0f, 90f);
        float thunder = level.getThunderLevel(1f);
        input.kp = Mth.clamp(thunder * 6f + storms.severeCount() * 1.5f, 0f, 9f);

        OpticalField.solve(input, target);

        // 指数平滑：上升快、下降慢；1 Hz 一步
        for (int i = 1; i < OpticalField.KIND_COUNT; i++) {
            float goal = target.strength[i];
            float cur = smooth[i];
            float tau = goal >= cur ? RISE_TAU : FALL_TAU;
            float alpha = 1f - (float) Math.exp(-1f / tau);
            smooth[i] = cur + (goal - cur) * alpha;
            if (smooth[i] < 0.01f) smooth[i] = 0f;
            duration[i] = smooth[i] >= VISIBLE ? duration[i] + 1 : 0;
        }

        // 主导 = 平滑后最强；若上一主导仍在可见阈值上且差别不大，保留以免来回跳
        float best = 0f;
        OpticalField.Kind bestKind = OpticalField.Kind.NONE;
        OpticalField.Kind prev = dominant;
        float prevS = prev == OpticalField.Kind.NONE ? 0f : smooth[prev.ordinal()];
        for (int i = 1; i < OpticalField.KIND_COUNT; i++) {
            float s = smooth[i];
            if (s > best) { best = s; bestKind = OpticalField.Kind.values()[i]; }
        }
        if (prev != OpticalField.Kind.NONE && prevS >= VISIBLE && prevS >= best * 0.75f) {
            bestKind = prev;
            best = prevS;
        }
        dominant = best >= VISIBLE ? bestKind : OpticalField.Kind.NONE;
        artistic = dominant != OpticalField.Kind.NONE && !dominant.scientific;

        // 三阶段：按"目标 vs 当前"的变化方向判定
        if (dominant == OpticalField.Kind.NONE) {
            phase = "静默";
        } else {
            float goal = target.strength[dominant.ordinal()];
            float cur = smooth[dominant.ordinal()];
            if (goal > cur + 0.04f) phase = "形成中";
            else if (goal < cur - 0.04f) phase = "消退中";
            else phase = "稳定";
        }

        // 写回 current 供发包（各分项用平滑值，主导/艺术/持续用状态机值）
        System.arraycopy(smooth, 0, current.strength, 0, OpticalField.KIND_COUNT);
        current.dominant = dominant;
        current.artistic = artistic;
        current.durationSec = dominant == OpticalField.Kind.NONE ? 0 : duration[dominant.ordinal()];
        current.phase = phase;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** 风暴系统的只读视图（解耦，避免 OpticalTracker 直接依赖 StormManager）。 */
    public interface StormLike {
        int severeCount();
    }

    public void setEnabled(boolean value) {
        enabled = value;
        if (!enabled) clear();
    }

    public void clear() {
        java.util.Arrays.fill(smooth, 0f);
        java.util.Arrays.fill(duration, 0);
        dominant = OpticalField.Kind.NONE;
        artistic = false;
        phase = "静默";
        current.dominant = OpticalField.Kind.NONE;
        current.artistic = false;
        current.durationSec = 0;
        current.phase = "静默";
        java.util.Arrays.fill(current.strength, 0f);
    }

    /** 发包用的只读快照（字段是公开数组，调用方不要改）。 */
    public OpticalField.Result snapshot() {
        return current;
    }

    public OpticalField.Kind dominant() {
        return dominant;
    }

    public boolean artistic() {
        return artistic;
    }

    public String phase() {
        return phase;
    }

    public int durationSec() {
        return dominant == OpticalField.Kind.NONE ? 0 : duration[dominant.ordinal()];
    }

    /** 强度数组（发包用）。 */
    public float strength(int kindIndex) {
        return kindIndex >= 0 && kindIndex < OpticalField.KIND_COUNT ? smooth[kindIndex] : 0f;
    }
}
