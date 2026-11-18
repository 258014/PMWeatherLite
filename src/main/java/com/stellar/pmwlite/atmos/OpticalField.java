package com.stellar.pmwlite.atmos;

import java.util.Locale;

/**
 * 环境光学场 / Environmental optics solver（纯函数，无世界依赖，可单测）。
 *
 * 输入一组大气状态（温度、湿度、露点、云、太阳高度角、地磁纬度、地磁活动、多气体浓度），
 * 输出 5 种现实中可辨识的光学现象的"强度 0..1"，以及一个主导现象。
 *
 * ── 科学可解释（scientific=true）─────────────────────────────────────
 *  幻日 PARHELION：卷层云里水平取向的六角片晶，对低角度阳光做 60° 顶角棱镜两次折射，
 *     偏向角最小 21.84°（"22°幻日"），近太阳侧红、远侧蓝紫（色散），太阳角 >60° 消失。
 *     因此需要：低太阳角 + 薄卷层云 + 冰晶（露点显著低于 0℃）+ 通透（气溶胶少）。
 *  日晕 HALO：同样冰晶但取向随机 → 22° 收不成点而摊成整圈；云更厚、太阳角限制更宽松。
 *  雾虹 FOGBOW：近饱和雾滴（|T−Td|<3℃、低云底）背光散射，太阳低角度、无雨时可见。
 *  污染霞光 AFTERGLOW：日出日落低角度阳光穿过气溶胶，米氏散射把短波滤掉、留下红褐霞光。
 *     这是真实的气溶胶光学效应，但把"机器废气浓度"直接映射到霞光强度是游戏化比例。
 *
 * ── 艺术化夸张（scientific=false）────────────────────────────────────
 *  极光样光幕 AURORA：真实极光发生在 100–300 km 电离层，由太阳风带电粒子沿地磁场沉降，
 *     激发氧原子（557.7nm 绿 / 630nm 红）与氮分子（427.8nm 蓝紫）。Minecraft 是地表游戏，
 *     没有电离层、没有太阳风沉降，**不存在产生真实极光的物理过程**。
 *     这里用"地磁纬度 + 夜间 + 地磁活动代理量 + 通透天空"做艺术化演绎，
 *     并在 HUD 上明确标注"艺术化"，不冒充科学现象。
 *
 * 所有触发条件都用钟形/界限函数写成连续强度，避免阈值处的硬跳变。参数集中在常量区。
 */
public final class OpticalField {
    /** 现象枚举；ordinal 即网络包里的 kind，顺序是 wire contract，不要在中间插入。 */
    public enum Kind {
        NONE("无", true, 0x00000000),
        PARHELION("幻日 · 三日凌空", true, 0xFFFFD9A0),
        HALO("22° 日晕", true, 0xFFD8E4F0),
        FOGBOW("雾虹", true, 0xFFC8E8E0),
        AFTERGLOW("污染霞光", true, 0xFFE08A5A),
        AURORA("极光样光幕", false, 0xFF66E8A8);   // false = 艺术化

        public final String label;
        /** true=科学可解释；false=艺术化夸张。 */
        public final boolean scientific;
        public final int argb;

        Kind(String label, boolean scientific, int argb) {
            this.label = label;
            this.scientific = scientific;
            this.argb = argb;
        }
    }

    public static final int KIND_COUNT = Kind.values().length;

    /** 输入：一次求解所需的全部大气状态。 */
    public static final class Input {
        public float tempC;
        public float humidity;          // 0..1
        public float dewPointC;
        public float cloud;             // 0..1 整列云量
        public float cloudBaseM;        // 云底高度（米）
        public float sunAltitudeDeg;    // 太阳高度角，负数=在地平线下
        public float geomagneticLat;    // 地磁纬度绝对值 0..90
        public float kp;                // 地磁活动代理 0..9（由极端天气强度映射，见 OpticalTracker 调用方）
        public float so2, no2, co, pm, co2;  // 多气体浓度
        public boolean underground;
    }

    /** 输出：5 种现象强度 + 主导现象 + 持续状态标记。 */
    public static final class Result {
        public final float[] strength = new float[KIND_COUNT];
        public Kind dominant = Kind.NONE;
        public boolean artistic = false;
        public int durationSec = 0;
        public String phase = "静默";
        /** 主导现象的归一化强度（0..1）。 */
        public float intensity() {
            return dominant == Kind.NONE ? 0f : strength[dominant.ordinal()];
        }
    }

    // ── 触发参数（现实依据见类注释）───────────────────────────────
    private static final float PARHELION_SUN_PEAK = 18f, PARHELION_SUN_W = 24f;  // 低太阳角，>60° 衰减
    private static final float PARHELION_CLOUD_PEAK = 0.45f, PARHELION_CLOUD_W = 0.26f; // 薄卷层云
    private static final float ICE_DEW_MAX = -2f, ICE_DEW_SPAN = 10f;   // 露点 < −2℃ 才可能是冰晶云
    private static final float CLEAR_PM_MAX = 0.45f;                    // 气溶胶过多则无法透光成幻日

    private static final float AURORA_LAT_MIN = 40f, AURORA_LAT_SPAN = 35f;  // 高磁纬（艺术映射）
    private static final float AURORA_KP_MIN = 4f, AURORA_KP_SPAN = 4f;      // 地磁扰动阈值
    private static final float AURORA_CLOUD_MAX = 0.55f;                     // 需通透夜空

    private OpticalField() {}

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 钟形函数：x 在 peak 附近达到 1，按 width 高斯衰减。 */
    private static float bell(float x, float peak, float width) {
        float d = (x - peak) / width;
        return (float) Math.exp(-0.5f * d * d);
    }

    /** 由游戏时间算太阳高度角（度）。dayTime 0=日出、6000=正午、12000=日落、18000=午夜。 */
    public static float sunAltitude(long dayTime) {
        double t = ((dayTime % 24000L) + 24000L) % 24000L / 24000.0;
        // 正午(t=0.25) → cos(0)=1 → 90°；日出(t=0)/日落(t=0.5) → 0°；午夜 → −90°
        double sinAlt = Math.cos((t - 0.25) * 2.0 * Math.PI);
        return (float) Math.toDegrees(Math.asin(clamp((float) sinAlt, -1f, 1f)));
    }

    /**
     * 求解一次。所有现象强度都算出来（便于叠加），dominant 取最强且 >0.18 者。
     */
    public static Result solve(Input in, Result out) {
        Result r = out != null ? out : new Result();
        java.util.Arrays.fill(r.strength, 0f);
        r.dominant = Kind.NONE;
        r.artistic = false;

        if (in.underground) return r;   // 地下看不到任何天空光学现象

        float pm = clamp(in.pm, 0f, 1f);
        float cloud = clamp(in.cloud, 0f, 1f);

        // 冰晶可用性：露点显著低于 0℃ 才可能是卷层云冰晶（而非水滴）
        float ice = clamp((ICE_DEW_MAX - in.dewPointC) / ICE_DEW_SPAN, 0f, 1f)
                  * clamp((14f - in.tempC) / 16f, 0f, 1f);
        float clearSky = clamp(1f - pm / CLEAR_PM_MAX, 0f, 1f);

        // 幻日：低太阳角 + 薄卷层云 + 冰晶 + 通透
        float parhelion = bell(in.sunAltitudeDeg, PARHELION_SUN_PEAK, PARHELION_SUN_W)
                * bell(cloud, PARHELION_CLOUD_PEAK, PARHELION_CLOUD_W)
                * ice * clearSky;
        // 太阳高于 60° 几乎消失（现实：光线无法水平穿过竖直冰晶侧面）
        if (in.sunAltitudeDeg > 60f) parhelion *= clamp((75f - in.sunAltitudeDeg) / 15f, 0f, 1f);

        // 日晕：云更厚、太阳角更宽松、同样要冰晶
        float halo = clamp((80f - in.sunAltitudeDeg) / 45f, 0f, 1f)
                * clamp((in.sunAltitudeDeg + 8f) / 18f, 0f, 1f)
                * bell(cloud, 0.72f, 0.30f)
                * clamp((-3f - in.dewPointC) / 12f, 0f, 1f)
                * clamp(1f - pm / 0.55f, 0f, 1f);

        // 雾虹：近饱和 + 低云底 + 低太阳角（雾滴散射，与冰晶无关）
        float saturation = clamp(1f - Math.abs(in.tempC - in.dewPointC) / 3f, 0f, 1f);
        float fogbow = clamp((in.humidity - 0.88f) / 0.12f, 0f, 1f)
                * saturation
                * clamp((260f - in.cloudBaseM) / 220f, 0f, 1f)
                * clamp((40f - in.sunAltitudeDeg) / 40f, 0f, 1f)
                * clamp((in.sunAltitudeDeg + 2f) / 10f, 0f, 1f);

        // 污染霞光：低角度阳光 + 气溶胶（PM/SO₂/NO₂）米氏散射；纯科学效应，比例游戏化
        float aerosol = clamp((pm - 0.18f) / 0.55f, 0f, 1f)
                * clamp((in.so2 + in.no2) / 3.0e-4f, 0f, 1f);
        float afterglow = aerosol
                * bell(Math.abs(in.sunAltitudeDeg), 3f, 16f);   // 日出日落最强

        // 极光样光幕（艺术化）：夜 + 高磁纬 + 地磁扰动代理 + 通透天空
        float lat = clamp((in.geomagneticLat - AURORA_LAT_MIN) / AURORA_LAT_SPAN, 0f, 1f);
        float kp = clamp((in.kp - AURORA_KP_MIN) / AURORA_KP_SPAN, 0f, 1f);
        float night = clamp((-6f - in.sunAltitudeDeg) / 12f, 0f, 1f);
        float auroraClear = clamp(1f - cloud / AURORA_CLOUD_MAX, 0f, 1f)
                * clamp(1f - pm / 0.35f, 0f, 1f);
        float aurora = lat * kp * night * auroraClear;

        r.strength[Kind.PARHELION.ordinal()] = clamp(parhelion, 0f, 1f);
        r.strength[Kind.HALO.ordinal()] = clamp(halo, 0f, 1f);
        r.strength[Kind.FOGBOW.ordinal()] = clamp(fogbow, 0f, 1f);
        r.strength[Kind.AFTERGLOW.ordinal()] = clamp(afterglow, 0f, 1f);
        r.strength[Kind.AURORA.ordinal()] = clamp(aurora, 0f, 1f);

        float best = 0f;
        for (Kind k : Kind.values()) {
            if (k == Kind.NONE) continue;
            float s = r.strength[k.ordinal()];
            if (s > best) { best = s; r.dominant = k; }
        }
        if (best < 0.18f) {
            r.dominant = Kind.NONE;
            r.artistic = false;
        } else {
            r.artistic = !r.dominant.scientific;
        }
        return r;
    }

    /** 给 HUD/文档用：现象的单行说明，带科学/艺术标记。 */
    public static String describe(Kind kind, boolean artistic, int durationSec, String phase) {
        if (kind == Kind.NONE) return "光学现象：无";
        String tag = artistic ? "§d[艺术化]" : "§b[科学]";
        return String.format(Locale.ROOT, "光学现象：%s %s§7 · 持续 %ds · %s",
                kind.label, tag, durationSec, phase);
    }
}
