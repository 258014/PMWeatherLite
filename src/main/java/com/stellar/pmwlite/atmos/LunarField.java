package com.stellar.pmwlite.atmos;

/**
 * 月亮状态纯函数 / Lunar state (无世界依赖，服务端与客户端共用同一套推导).
 *
 * Minecraft 已经同步了世界时间和 8 相月相，但"月相 → 月面明暗 → 月光强度 → 月食"这条链
 * 过去没有被表现层使用。这里把它收敛成一组纯函数，服务器光学求解器和客户端
 * LightingState / 叠加层各调用一次，得到完全相同的读数，不会出现 HUD 说满月、
 * 画面却画新月的穿帮。
 *
 * 约定与真实天文一致的部分
 * ------------------------
 * - 月相周期取 8 个 MC 日（与 {@code Level.getMoonPhase()} 的 0..7 周期一致），0=满月、4=新月；
 * - 月面被照亮比例 = (1+cos(2π·phase/8))/2，满月 1、上/下弦 ≈0.5、新月 0；
 * - 月亮与太阳分居天空两侧：moonAltitude = −sunAltitude；
 * - 月食只可能发生在**满月**且月球靠近黄白交点；夜间、月亮在地平线上才看得见；
 * - 月食时地球本影遮月，边缘因地球大气折射呈铜红色（表现层画暗盘+铜红边）。
 *
 * 游戏化处理（明确标注）
 * --------------------
 * 真实交点年 18.6 年、食季每半年一次；MC 没有黄道/白道，也不能让玩家等现实半年。
 * 这里用"每 6 个月相周期（48 MC 日）出现一个食季、满月前后 ±0.35 日可见"作隐喻，
 * 周期短但**仍然稀有、仍然严格绑定满月**，逻辑自洽。
 */
public final class LunarField {
    /** 一个完整月相周期的 tick 数（8 天）。 */
    public static final long CYCLE_TICKS = 24000L * 8L;
    /** 一个食季间隔多少个周期。 */
    public static final long ECLIPSE_EVERY_CYCLES = 6L;
    /** 食季窗口（天）——满月前后各这么多天可能食。 */
    public static final float ECLIPSE_WINDOW_DAYS = 0.35f;

    private LunarField() {}

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 连续月相 0..8（0=满月，4=新月）。 */
    public static float phase(long dayTime) {
        long t = ((dayTime % CYCLE_TICKS) + CYCLE_TICKS) % CYCLE_TICKS;
        return (float) t / 24000f;
    }

    /** 与 {@code Level.getMoonPhase()} 对齐的离散相 0..7。 */
    public static int phaseIndex(long dayTime) {
        return (int) phase(dayTime) % 8;
    }

    /** 月面照亮比例 0..1。 */
    public static float illumination(long dayTime) {
        double a = phase(dayTime) / 8.0 * Math.PI * 2.0;
        return (float) (0.5 * (1.0 + Math.cos(a)));
    }

    /** true=盈月（新月→满月，亮边在右）；false=亏月（亮边在左）。 */
    public static boolean waxing(long dayTime) {
        float p = phase(dayTime);
        return p >= 4f;
    }

    /** 月亮高度角（度），与太阳高度角相反。 */
    public static float altitudeDeg(long dayTime) {
        return -OpticalField.sunAltitude(dayTime);
    }

    /**
     * 月食强度 0..1。只有满月 + 食季 + 月亮在地平线附近或以上才非零。
     * 1 表示全食（本影完全盖住月面）。
     */
    public static float eclipse(long dayTime) {
        float illum = illumination(dayTime);
        if (illum < 0.96f) return 0f;              // 只有满月附近
        if (altitudeDeg(dayTime) < -6f) return 0f; // 月亮还没升起来 / 已落下

        long cycle = Math.floorDiv(dayTime, CYCLE_TICKS);
        if (Math.floorMod(cycle, ECLIPSE_EVERY_CYCLES) != 0L) return 0f;

        long inCycle = ((dayTime % CYCLE_TICKS) + CYCLE_TICKS) % CYCLE_TICKS;
        // 食季中心在周期开头（phase=0，满月）
        float daysFromNode = Math.min(inCycle / 24000f,
                8f - inCycle / 24000f);
        float node = clamp(1f - daysFromNode / ECLIPSE_WINDOW_DAYS, 0f, 1f);
        float full = clamp((illum - 0.96f) / 0.04f, 0f, 1f);
        return clamp(node * full, 0f, 1f);
    }

    /**
     * 可用月光强度 0..1：照亮比例 × 地平线可见性 × 云量透过率 × (1−月食遮蔽)。
     * 用于夜间环境光与"月华/月食"现象的公共输入，避免在两处各算一遍。
     */
    public static float moonlight(long dayTime, float cloud, float rain) {
        float alt = altitudeDeg(dayTime);
        float up = clamp((alt + 6f) / 16f, 0f, 1f);
        float clear = clamp(1f - clamp(cloud, 0f, 1f) * 0.85f
                - clamp(rain, 0f, 1f) * 0.35f, 0f, 1f);
        float e = eclipse(dayTime);
        return clamp(illumination(dayTime) * up * clear * (1f - 0.92f * e), 0f, 1f);
    }

    /** HUD 用：相名 + 照亮百分比 + 月食标记。 */
    public static String describe(long dayTime) {
        String[] names = {"满月", "亏凸月", "下弦月", "亏眉月", "新月", "盈眉月", "上弦月", "盈凸月"};
        String phaseName = names[phaseIndex(dayTime)];
        float e = eclipse(dayTime);
        String tag = e > 0.05f ? " §c[月食 " + Math.round(e * 100f) + "%]" : "";
        return String.format("§7月相 §f%s §7照度 §f%.0f%%%s",
                phaseName, illumination(dayTime) * 100f, tag);
    }
}
