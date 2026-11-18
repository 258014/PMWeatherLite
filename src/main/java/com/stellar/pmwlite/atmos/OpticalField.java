package com.stellar.pmwlite.atmos;

import java.util.Locale;

/**
 * 环境光学场 / Environmental optics solver（纯函数，无世界依赖，可单测）。
 *
 * 输入一组大气状态（温度、湿度、露点、云、太阳/月亮高度角、逆温、地磁、多气体浓度），
 * 输出 22 种现实中可辨识的光学/天文现象的"强度 0..1"，以及一个主导现象。
 *
 * ── 科学可解释（scientific=true）─────────────────────────────────────
 *  原有 5 种：
 *   幻日 PARHELION：卷层云水平六角片晶对低角度阳光做 60° 顶角棱镜两次折射，近太阳红、远侧蓝紫。
 *   日晕 HALO：同样冰晶但取向随机 → 22° 整圈，云更厚、太阳角限制更宽松。
 *   雾虹 FOGBOW：近饱和雾滴背光散射；太阳低角度、无雨时可见。
 *   污染霞光 AFTERGLOW：低角度阳光穿过气溶胶，米氏散射滤掉短波留下红褐霞光。
 *  新增 16 种（顺序即网络 wire contract，见 Kind）：
 *   环天顶弧 CZA：太阳 ≤32°、水平板状冰晶"顶面入、侧面出"，蓝紫在上红在下。
 *   环地平弧 CHA：太阳 >58°、侧面入底面出，红在上紫在下，高纬难见。
 *   日柱 SUN_PILLAR：水平板晶反射低角度光源形成竖直光柱。
 *   虹彩云 IRIDESCENT：薄云中小水滴/冰针衍射，色彩柔和不如彩虹分明。
 *   宝光 GLORY：背向太阳，观察者在云雾上的影子周围彩色环，红色外缘（Mie 后向散射）。
 *   双彩虹 RAINBOW：主虹（内紫外红）+ 霓（内红外紫，暗一档，中间亚历山大暗带）。
 *   月华 MOON_CORONA：薄云衍射月光，内蓝绿外红棕；随月相照度与月食消长。
 *   曙暮辉 CREPUSCULAR：阳光被云/地形切割成光束，透视图上汇聚到太阳。
 *   反曙暮辉 ANTICREPUSCULAR：同一束光在地平线另一侧汇聚到反日点。
 *   维纳斯带 BELT_VENUS：日落后地平线上方粉红带（反向散射的长波）。
 *   地球阴影 EARTH_SHADOW：维纳斯带下方暗蓝楔形，是地球本影投射到大气上（月食同源）。
 *   气辉 AIRGLOW：高层大气化学发光，真实存在但极暗，这里按比例提亮仍标科学。
 *   法塔莫甘娜 FATA_MORGANA：强近地逆温形成大气波导，地平线出现垂直堆叠的畸变蜃景。
 *   蓝太阳 BLUE_SUN：浓气溶胶散射掉长波，太阳/月亮呈蓝灰。
 *   绿闪 GREEN_FLASH：日出日落最后一瞬，大气折射+近地蜃景把绿像留在地平线上。
 *   月食 LUNAR_ECLIPSE：满月近交点，地球本影遮月、边缘铜红（见 LunarField）。
 *
 * ── 艺术化夸张（scientific=false）────────────────────────────────────
 *  极光样光幕 AURORA：真实极光在 100–300 km 电离层由太阳风沉降激发，MC 地表没有该物理过程。
 *     用地磁纬度 + 夜间 + 地磁活动代理 + 通透天空艺术化演绎，HUD 明确标注"艺术化"。
 *
 * 所有触发条件都用钟形/界限函数写成连续强度，避免阈值处硬跳变。参数集中在常量区。
 */
public final class OpticalField {
    /** 现象枚举；ordinal 即网络包里的 kind，顺序是 wire contract，不要在中间插入。 */
    public enum Kind {
        NONE("无", true, 0x00000000),
        PARHELION("幻日 · 三日凌空", true, 0xFFFFD9A0),
        HALO("22° 日晕", true, 0xFFD8E4F0),
        FOGBOW("雾虹", true, 0xFFC8E8E0),
        AFTERGLOW("污染霞光", true, 0xFFE08A5A),
        AURORA("极光样光幕", false, 0xFF66E8A8),   // false = 艺术化
        // ── v0.3.4 追加：只能加在末尾，ordinal 是网络协议的一部分 ──
        CIRCUMZENITHAL("环天顶弧", true, 0xFFA8D8FF),
        CIRCUMHORIZONTAL("环地平弧", true, 0xFFB8E8FF),
        SUN_PILLAR("日柱", true, 0xFFFFD8A0),
        IRIDESCENT("虹彩云", true, 0xFFC8B0F0),
        GLORY("宝光", true, 0xFFD8C0E8),
        RAINBOW("双彩虹 · 主虹与霓", true, 0xFF70C8F0),
        MOON_CORONA("月华", true, 0xFFD8E4F8),
        CREPUSCULAR("曙暮辉", true, 0xFFFFC878),
        ANTICREPUSCULAR("反曙暮辉", true, 0xFFFFB0A0),
        BELT_VENUS("维纳斯带", true, 0xFFF0A0A8),
        EARTH_SHADOW("地球阴影", true, 0xFF4860A0),
        AIRGLOW("气辉", true, 0xFF78D8B0),
        FATA_MORGANA("法塔莫甘娜蜃景", true, 0xFFB8D8E8),
        BLUE_SUN("蓝太阳", true, 0xFF7090F0),
        GREEN_FLASH("绿闪", true, 0xFF60F0A0),
        LUNAR_ECLIPSE("月食", true, 0xFFC06048);

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
        public float kp;                // 地磁活动代理 0..9（由极端天气强度映射）
        public float so2, no2, co, pm, co2;  // 多气体浓度
        public boolean underground;
        // ── v0.3.4 新增输入（缺省 0 时对旧调用完全兼容）──
        /** 近地逆温强度 ℃（0..12），法塔莫甘娜的必要条件。 */
        public float inversionC;
        /** 月亮高度角（度），由 LunarField.altitudeDeg() 派生。 */
        public float moonAltitudeDeg;
        /** 月面照亮比例 0..1。 */
        public float moonIllum;
        /** 月食遮蔽强度 0..1。 */
        public float moonEclipse;
        /** 降水强度 0..1，彩虹/宝光需要水滴。 */
        public float rain;
    }

    /** 输出：各现象强度 + 主导现象 + 持续状态标记。 */
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
    private static final float PARHELION_SUN_PEAK = 18f, PARHELION_SUN_W = 24f;
    private static final float PARHELION_CLOUD_PEAK = 0.45f, PARHELION_CLOUD_W = 0.26f;
    private static final float ICE_DEW_MAX = -2f, ICE_DEW_SPAN = 10f;
    private static final float CLEAR_PM_MAX = 0.45f;

    private static final float AURORA_LAT_MIN = 40f, AURORA_LAT_SPAN = 35f;
    private static final float AURORA_KP_MIN = 4f, AURORA_KP_SPAN = 4f;
    private static final float AURORA_CLOUD_MAX = 0.55f;

    private OpticalField() {}

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 线性斜坡：x≤lo→0，x≥hi→1。 */
    private static float ramp(float x, float lo, float hi) {
        return clamp((x - lo) / (hi - lo), 0f, 1f);
    }

    /** 钟形函数：x 在 peak 附近达到 1，按 width 高斯衰减。 */
    private static float bell(float x, float peak, float width) {
        float d = (x - peak) / width;
        return (float) Math.exp(-0.5f * d * d);
    }

    /** 由游戏时间算太阳高度角（度）。dayTime 0=日出、6000=正午、12000=日落、18000=午夜。 */
    public static float sunAltitude(long dayTime) {
        double t = ((dayTime % 24000L) + 24000L) % 24000L / 24000.0;
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
        float sun = in.sunAltitudeDeg;
        float absSun = Math.abs(sun);
        float rain = clamp(in.rain, 0f, 1f);

        // 冰晶可用性：露点显著低于 0℃ 才可能是卷层云冰晶（而非水滴）
        float ice = ramp(ICE_DEW_MAX - in.dewPointC, 0f, ICE_DEW_SPAN)
                  * ramp(14f - in.tempC, 0f, 16f);
        float clearSky = clamp(1f - pm / CLEAR_PM_MAX, 0f, 1f);
        float hazeFree = clamp(1f - pm / 0.30f, 0f, 1f);
        // 近饱和雾滴（雾虹/宝光共用）
        float saturation = ramp(1f - Math.abs(in.tempC - in.dewPointC) / 3f, 0f, 1f);

        // ── 原有 5 种 ──────────────────────────────────────────────
        float parhelion = bell(sun, PARHELION_SUN_PEAK, PARHELION_SUN_W)
                * bell(cloud, PARHELION_CLOUD_PEAK, PARHELION_CLOUD_W)
                * ice * clearSky;
        if (sun > 60f) parhelion *= ramp(75f - sun, 0f, 15f);

        float halo = ramp(80f - sun, 0f, 45f)
                * ramp(sun + 8f, 0f, 18f)
                * bell(cloud, 0.72f, 0.30f)
                * ramp(-3f - in.dewPointC, 0f, 12f)
                * clamp(1f - pm / 0.55f, 0f, 1f);

        float fogbow = ramp(in.humidity - 0.88f, 0f, 0.12f)
                * saturation
                * ramp(260f - in.cloudBaseM, 0f, 220f)
                * ramp(40f - sun, 0f, 40f)
                * ramp(sun + 2f, 0f, 10f);

        float aerosol = ramp(pm - 0.18f, 0f, 0.55f)
                * clamp((in.so2 + in.no2) / 3.0e-4f, 0f, 1f);
        float afterglow = aerosol * bell(absSun, 3f, 16f);

        float lat = ramp(in.geomagneticLat - AURORA_LAT_MIN, 0f, AURORA_LAT_SPAN);
        float kp = ramp(in.kp - AURORA_KP_MIN, 0f, AURORA_KP_SPAN);
        float night = ramp(-6f - sun, 0f, 12f);
        float auroraClear = clamp(1f - cloud / AURORA_CLOUD_MAX, 0f, 1f)
                * clamp(1f - pm / 0.35f, 0f, 1f);
        float aurora = lat * kp * night * auroraClear;

        // ── 环天顶弧：太阳 ≤32°、薄卷层云、冰晶、通透 ────────────────
        float cza = ramp(32f - sun, 0f, 10f) * ramp(sun + 2f, 0f, 6f)
                * bell(cloud, 0.42f, 0.20f) * ice * clearSky;

        // ── 环地平弧：太阳 >58°、同样的板状冰晶但光路相反 ────────────
        float cha = ramp(sun - 55f, 0f, 8f) * ramp(88f - sun, 0f, 10f)
                * bell(cloud, 0.42f, 0.22f) * ice * clearSky;

        // ── 日柱：低角度光源被水平板晶反射，允许太阳略低于地平线 ──────
        float pillar = bell(sun, 1f, 13f) * ice
                * clamp(1f - cloud / 0.70f, 0f, 1f) * clearSky;

        // ── 虹彩云：薄云衍射，冰晶不是必需，露点不能太干 ──────────────
        float iridescent = bell(cloud, 0.45f, 0.22f)
                * bell(sun, 35f, 30f)
                * ramp(in.dewPointC + 20f, 0f, 30f)
                * clearSky;

        // ── 宝光：背向太阳 + 云雾在低处 + 近饱和 ────────────────────
        float glory = ramp(25f - sun, 0f, 20f) * ramp(sun + 2f, 0f, 8f)
                * ramp(in.humidity - 0.85f, 0f, 0.15f) * saturation
                * ramp(400f - in.cloudBaseM, 0f, 350f)
                * clamp(1f - cloud / 0.85f, 0f, 1f);

        // ── 双彩虹：低太阳角 + 水滴（雨或雾）──────────────────────
        float water = Math.max(rain, saturation * 0.75f);
        float rainbow = ramp(42f - sun, 0f, 30f) * ramp(sun + 2f, 0f, 10f)
                * ramp(water, 0f, 0.7f) * bell(cloud, 0.65f, 0.30f);

        // ── 月华：月亮升起 + 薄云衍射 + 月面够亮，月食时消失 ────────
        float moonUp = ramp(in.moonAltitudeDeg - 3f, 0f, 12f);
        float moonBright = clamp(in.moonIllum, 0f, 1f) * (1f - 0.9f * clamp(in.moonEclipse, 0f, 1f));
        float corona = moonUp * bell(cloud, 0.42f, 0.22f) * moonBright * clearSky;

        // ── 曙暮辉 / 反曙暮辉：同一束光的两侧投影，分界在太阳过地平线 ─
        float crepuscular = bell(sun, 2f, 10f) * bell(cloud, 0.50f, 0.30f) * clearSky;
        float anticrepuscular = bell(sun, -2f, 9f) * bell(cloud, 0.45f, 0.30f)
                * clearSky * ramp(4f - sun, 0f, 8f);

        // ── 维纳斯带 / 地球阴影：民用暮光，高度角窗口不同 ─────────────
        float beltVenus = bell(sun, -6f, 5f) * hazeFree
                * clamp(1f - cloud / 0.80f, 0f, 1f);
        float earthShadow = bell(sun, -3.5f, 3f) * hazeFree
                * clamp(1f - cloud / 0.70f, 0f, 1f);

        // ── 气辉：真实但极暗，×0.45 表达"比极光暗一档" ───────────────
        float airglow = ramp(-12f - sun, 0f, 12f)
                * clamp(1f - cloud / 0.50f, 0f, 1f)
                * clamp(1f - pm / 0.30f, 0f, 1f) * 0.45f;

        // ── 法塔莫甘娜：强逆温 + 低太阳角 + 通透 ────────────────────
        float fata = ramp(in.inversionC - 4f, 0f, 8f)
                * ramp(20f - absSun, 0f, 18f)
                * clamp(1f - cloud / 0.60f, 0f, 1f)
                * clamp(1f - pm / 0.40f, 0f, 1f);

        // ── 蓝太阳：浓气溶胶滤掉长波（PM 或硫/氮氧化物）────────────────
        float smoke = Math.max(ramp(pm - 0.35f, 0f, 0.40f),
                clamp((in.so2 + in.no2) / 6.0e-4f, 0f, 1f) * 0.6f);
        float blueSun = smoke * bell(absSun, 12f, 25f)
                * clamp(1f - cloud / 0.60f, 0f, 1f);

        // ── 绿闪：地平线 ±2.5°、空气极通透 ─────────────────────────
        float greenFlash = ramp(2.5f - absSun, 0f, 2f)
                * clamp(1f - pm / 0.15f, 0f, 1f)
                * clamp(1f - cloud / 0.50f, 0f, 1f)
                * clamp(1f - Math.abs(in.tempC - in.dewPointC) / 20f, 0f, 1f);

        // ── 月食：满月近交点 + 月亮在地平线上 + 天空通透 ──────────────
        float lunarEclipse = clamp(in.moonEclipse, 0f, 1f)
                * ramp(in.moonAltitudeDeg + 4f, 0f, 10f)
                * clamp(1f - cloud / 0.60f, 0f, 1f);

        r.strength[Kind.PARHELION.ordinal()] = clamp(parhelion, 0f, 1f);
        r.strength[Kind.HALO.ordinal()] = clamp(halo, 0f, 1f);
        r.strength[Kind.FOGBOW.ordinal()] = clamp(fogbow, 0f, 1f);
        r.strength[Kind.AFTERGLOW.ordinal()] = clamp(afterglow, 0f, 1f);
        r.strength[Kind.AURORA.ordinal()] = clamp(aurora, 0f, 1f);
        r.strength[Kind.CIRCUMZENITHAL.ordinal()] = clamp(cza, 0f, 1f);
        r.strength[Kind.CIRCUMHORIZONTAL.ordinal()] = clamp(cha, 0f, 1f);
        r.strength[Kind.SUN_PILLAR.ordinal()] = clamp(pillar, 0f, 1f);
        r.strength[Kind.IRIDESCENT.ordinal()] = clamp(iridescent, 0f, 1f);
        r.strength[Kind.GLORY.ordinal()] = clamp(glory, 0f, 1f);
        r.strength[Kind.RAINBOW.ordinal()] = clamp(rainbow, 0f, 1f);
        r.strength[Kind.MOON_CORONA.ordinal()] = clamp(corona, 0f, 1f);
        r.strength[Kind.CREPUSCULAR.ordinal()] = clamp(crepuscular, 0f, 1f);
        r.strength[Kind.ANTICREPUSCULAR.ordinal()] = clamp(anticrepuscular, 0f, 1f);
        r.strength[Kind.BELT_VENUS.ordinal()] = clamp(beltVenus, 0f, 1f);
        r.strength[Kind.EARTH_SHADOW.ordinal()] = clamp(earthShadow, 0f, 1f);
        r.strength[Kind.AIRGLOW.ordinal()] = clamp(airglow, 0f, 1f);
        r.strength[Kind.FATA_MORGANA.ordinal()] = clamp(fata, 0f, 1f);
        r.strength[Kind.BLUE_SUN.ordinal()] = clamp(blueSun, 0f, 1f);
        r.strength[Kind.GREEN_FLASH.ordinal()] = clamp(greenFlash, 0f, 1f);
        r.strength[Kind.LUNAR_ECLIPSE.ordinal()] = clamp(lunarEclipse, 0f, 1f);

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
