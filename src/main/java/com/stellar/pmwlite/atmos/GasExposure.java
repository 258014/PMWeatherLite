package com.stellar.pmwlite.atmos;

import java.util.Locale;

/**
 * 多气体联合暴露模型 / Combined gas exposure (纯函数，无世界依赖).
 *
 * 为什么不能只看 CO₂
 * ------------------
 * 单一气体阈值会漏掉两类真实场景：
 *  1. **协同效应**：SO₂ 吸附在 PM 表面深入肺部；NO₂ 与 CO 同时削弱携氧与呼吸道防御。
 *     各气体都在"单阈值以下"时，组合仍可能造成明显风险。
 *  2. **湿沉降**：降雨把 SO₂/NO₂/颗粒物洗出边界层，同样浓度在雨中危害和浑浊度都更低；
 *     但 CO 难溶于水，湿沉降对它几乎无效。
 *
 * 这里不追求医学精度，而是给出一个**连续、单调、可解释、有界**的游戏化指数：
 *  - 输入是 5 种浓度（CO₂ 体积分数、SO₂/NO₂/CO 体积分数、PM 浑浊度指数）与降雨强度；
 *  - 输出是 0..1 归一化风险指数、5 级风险、能见度折扣，以及"贡献最大"的气体；
 *  - 每段公式都是线性+min 饱和，没有 exp/矩阵，1 Hz 每玩家调用一次没有性能问题。
 *
 * 阈值全部是**游戏标定值**，不是职业暴露限值：背景 SO₂≈0.5 ppb、NO₂≈0.02 ppb、CO≈100 ppm
 * （世界内数值），到达"轻度"需要比背景高 2~3 个数量级，和 GasField.MAX 一致，保证玩家
 * 只有在真正建成排放工业区后才会看到后果。
 */
public final class GasExposure {
    /** 风险分级。ordinal 即等级，供 HUD 与效果表共用。 */
    public enum Grade {
        NONE("无"), MILD("轻度"), MODERATE("中度"), SEVERE("重度"), CRITICAL("危险");

        public final String label;
        Grade(String label) { this.label = label; }
    }

    /** 输出容器，可复用。 */
    public static final class Result {
        /** 0..1 归一化综合暴露指数（已含协同与湿沉降修正）。 */
        public float index;
        public Grade grade = Grade.NONE;
        /** 0..1，1=通透，0=几乎看不见；由 PM + 二次气溶胶 + CO₂ 窒息感共同决定。 */
        public float visibility = 1f;
        /** 各气体的归一化暴露份额（0..3），用于 HUD 显示"谁贡献最大"。 */
        public final float[] parts = new float[5];
        /** 贡献最大的气体下标，对应 {@link #GAS_KEYS}。 */
        public int dominant = -1;

        public String label() {
            return grade.label + "(" + Math.round(index * 100f) + "%)";
        }
    }

    public static final int SO2 = 0, NO2 = 1, CO = 2, PM = 3, CO2 = 4, COUNT = 5;
    public static final String[] GAS_KEYS = {"so2", "no2", "co", "pm", "co2"};
    public static final String[] GAS_NAMES = {"二氧化硫", "二氧化氮", "一氧化碳", "颗粒物", "二氧化碳"};

    /** 游戏标定阈值：达到该浓度时单一气体贡献≈1.0。 */
    private static final float SO2_REF = 5.0e-4f;   // 500 ppb
    private static final float NO2_REF = 2.0e-4f;   // 200 ppb
    private static final float CO_REF = 2.0e-3f;    // 2000 ppm（世界尺度）
    private static final float PM_REF = 0.6f;       // 浑浊度指数
    private static final float CO2_BASE = 4.0e-3f;  // 背景 400 ppm
    private static final float CO2_REF = 1.6e-2f;   // 2% 时贡献≈1.0

    /** 风险等级边界（归一化指数）。 */
    private static final float[] GRADE_EDGE = {0.12f, 0.35f, 0.62f, 0.85f};

    /** 湿沉降最大削减比例：SO₂/NO₂/PM 可被雨水带走，CO 基本无效。 */
    private static final float WET_MAX = 0.55f;

    private GasExposure() {}

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * 求解一次联合暴露。
     *
     * @param rain 0..1 降水强度；越大，水溶性气体与颗粒物被洗出越多
     */
    public static Result evaluate(float so2, float no2, float co, float pm, float co2,
                                  float rain, Result out) {
        Result r = out != null ? out : new Result();
        float wet = clamp(rain, 0f, 1f) * WET_MAX;

        // 单气体暴露份额：线性到参考值，最多 3 倍（避免某个极端源淹没其余项）。
        float s = clamp(so2 / SO2_REF, 0f, 3f) * (1f - wet);
        float n = clamp(no2 / NO2_REF, 0f, 3f) * (1f - wet * 0.8f);
        float c = clamp((co - GasField.BACKGROUND[GasField.CO]) / CO_REF, 0f, 3f); // CO 不溶于水
        float p = clamp(pm / PM_REF, 0f, 2.5f) * (1f - wet);
        float x = clamp((co2 - CO2_BASE) / CO2_REF, 0f, 3f);

        r.parts[SO2] = s;
        r.parts[NO2] = n;
        r.parts[CO] = c;
        r.parts[PM] = p;
        r.parts[CO2] = x;

        // 协同项：SO₂+PM（气溶胶载体把硫化物带入肺深部）、NO₂+CO（氧化损伤+缺氧）、
        // SO₂+NO₂（共同刺激呼吸道）。用 min 饱和，避免二次项在大浓度下爆炸。
        float syn = 0.45f * Math.min(s, p)
                  + 0.35f * Math.min(n, c)
                  + 0.25f * Math.min(s, n);

        float base = 0.34f * s + 0.30f * n + 0.42f * c + 0.52f * p + 0.36f * x;
        // 0.85 是"所有气体都满负荷"时的归一化上限，除以它后指数落在 0..1。
        r.index = clamp((base + syn) / 0.85f, 0f, 1f);

        if (r.index < GRADE_EDGE[0]) r.grade = Grade.NONE;
        else if (r.index < GRADE_EDGE[1]) r.grade = Grade.MILD;
        else if (r.index < GRADE_EDGE[2]) r.grade = Grade.MODERATE;
        else if (r.index < GRADE_EDGE[3]) r.grade = Grade.SEVERE;
        else r.grade = Grade.CRITICAL;

        // 能见度：PM 是主因；SO₂ 促进二次气溶胶；CO₂ 高浓度带来的灼闷感只做轻微折扣。
        r.visibility = clamp(1f - 0.55f * clamp(p, 0f, 1f) - 0.12f * clamp(s, 0f, 1f)
                - 0.10f * clamp(x, 0f, 1f), 0.06f, 1f);

        r.dominant = -1;
        float best = 0f;
        for (int i = 0; i < COUNT; i++) {
            if (r.parts[i] > best) { best = r.parts[i]; r.dominant = i; }
        }
        if (best < 0.05f) r.dominant = -1;
        return r;
    }

    /** 从大气探针直接求解，供服务端效果与网络包共用同一份判定。 */
    public static Result fromProbe(AtmosphereGrid.Probe p, float rain, Result out) {
        if (p == null) return evaluate(0f, 0f, 0f, 0f, 0f, 0f, out);
        return evaluate(p.gases[GasField.SO2], p.gases[GasField.NO2], p.gases[GasField.CO],
                p.gases[GasField.PM], p.co2, rain, out);
    }

    /** HUD 单行描述，含主导气体与能见度。 */
    public static String describe(Result r) {
        if (r == null || r.grade == Grade.NONE) return "§7污染风险 §f无";
        String who = r.dominant >= 0 ? GAS_NAMES[r.dominant] : "复合污染";
        return String.format(Locale.ROOT, "§7污染风险 §f%s §7主导 §f%s §7能见度 §f%.0f%%",
                r.grade.label, who, r.visibility * 100f);
    }
}
