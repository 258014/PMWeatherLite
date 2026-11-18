package com.stellar.pmwlite.client;

import com.stellar.pmwlite.atmos.GasExposure;
import com.stellar.pmwlite.atmos.OpticalField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

/**
 * One place that answers "how stormy does the screen need to look right now?".
 *
 * It is derived purely from vanilla-synced weather state ({@code rainLevel} / {@code thunderLevel}),
 * which every shader pack and every vanilla client already receives. That keeps the visual layers
 * in sync with the simulation without adding a single packet, and it means the look is identical
 * in single-player, on a server, and behind a shader pack.
 *
 * Cost: a handful of float reads plus a 4 Hz biome probe -- no allocation, safe to call per frame.
 */
public final class ClientVisualState {
    private static boolean postEffectsEnabled = true;
    private static boolean overlayEnabled = true;

    private static float intensity = 0f;
    private static float thunder = 0f;
    private static boolean cold = false;
    /** 本地 CO₂ 体积分数（由 1 Hz 探针包写入，供雾色/能见度染色）。 */
    private static volatile float co2 = 0.0004f;
    /** 其他机器废气（SO2/NO2/CO/PM），网络线程写、渲染线程读。 */
    private static volatile float so2 = 5e-7f, no2 = 2e-8f, co = 1e-4f, pm = 0f;
    /** 当前主导光学现象（ordinal 对应 OpticalField.Kind）。 */
    private static volatile int opticsKind = 0;
    private static volatile boolean opticsArtistic = false;
    private static volatile int opticsDuration = 0;
    /**
     * v0.3.4：所有现象强度放在一个数组里，网络线程写入时**整体替换引用**，
     * 渲染线程只读一次局部引用，避免 22 个 float 被读到"半旧半新"的撕裂状态。
     */
    private static volatile float[] opticsStrength = new float[OpticalField.KIND_COUNT];
    /** 月亮状态（与服务端同源，ordinal 相 / 照度 / 高度 / 食 / 可用月光）。 */
    private static volatile int moonPhase = 0;
    private static volatile float moonIllum = 0f, moonAlt = 0f, moonEclipse = 0f, moonlight = 0f;
    /** 是否收到过光学包； false 时表现层退回用本地 LunarField 推导，避免首帧黑月。 */
    private static volatile boolean moonSynced = false;
    /** 多气体联合暴露（1 Hz 探针包），HUD 与雾色共用。 */
    private static volatile float exposure = 0f, visibility = 1f;
    private static volatile int exposureGrade = 0;
    private static float flash = 0f;
    private static int flashTimer = 0;
    private static int probeTimer = 0;

    private ClientVisualState() {}

    /** Called once per client tick from {@code ClientSetup}. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            intensity = 0f;
            thunder = 0f;
            flash = 0f;
            return;
        }

        // rainLevel / thunderLevel are already smoothed by the client (0..1).
        float rain = level.getRainLevel(1f);
        float th = level.getThunderLevel(1f);
        thunder = th;
        intensity = Mth.clamp(Math.max(rain, th * 1.25f), 0f, 1f);

        // Lightning flash: vanilla only sends thunder level, not individual bolts, so we derive a
        // flash cadence from the thunder level. Deterministic and bounded (never more than one
        // flash every 12 ticks), which is what keeps it from becoming a strobe.
        if (th > 0.55f) {
            if (flashTimer > 0) {
                flashTimer--;
                flash = flashTimer / 6f;
            } else if (mc.level.random.nextInt(90) < (int) (th * 6f)) {
                flashTimer = 6;
            }
        } else {
            flashTimer = 0;
            flash = 0f;
        }

        if (++probeTimer % 4 == 0) {
            BlockPos p = mc.player != null ? mc.player.blockPosition() : BlockPos.ZERO;
            var biome = level.getBiome(p);
            cold = biome.value().getBaseTemperature() < 0.3f;
        }
    }

    /** 0..1 storm intensity, for overlays and post uniforms. */
    public static float intensity() {
        return intensity;
    }

    public static float thunder() {
        return thunder;
    }

    /** True when the local biome is cold enough that precipitation should read as snow/hail. */
    public static boolean cold() {
        return cold;
    }

    public static float co2() {
        return co2;
    }

    public static void setCo2(float value) {
        co2 = value;
    }

    /** 0..1 污染浑浊度：CO₂、PM 与服务端联合暴露指数取较大者，避免只按单一气体染色。 */
    public static float pollution() {
        float byCo2 = Mth.clamp((co2 - 0.01f) / 0.04f, 0f, 1f);
        float byPm = Mth.clamp(pm, 0f, 1f) * 0.8f;
        float byExposure = Mth.clamp(exposure, 0f, 1f) * 0.9f;
        return Math.max(byCo2, Math.max(byPm, byExposure));
    }

    // ---- 多气体读数 ----
    public static void setGases(float s, float n, float c, float p) {
        so2 = s; no2 = n; co = c; pm = p;
    }
    public static float so2() { return so2; }
    public static float no2() { return no2; }
    public static float co() { return co; }
    public static float pm() { return pm; }

    // ---- 多气体联合暴露 ----
    public static void setExposure(float index, int grade, float vis) {
        exposure = index; exposureGrade = grade; visibility = vis;
    }
    public static float exposure() { return exposure; }
    public static int exposureGrade() { return exposureGrade; }
    public static GasExposure.Grade exposureGradeEnum() {
        GasExposure.Grade[] g = GasExposure.Grade.values();
        return exposureGrade >= 0 && exposureGrade < g.length ? g[exposureGrade] : GasExposure.Grade.NONE;
    }
    public static float visibility() { return visibility; }

    // ---- 光学现象状态 ----
    /** 网络线程调用：整体替换数组引用，渲染线程读到的永远是一份完整快照。 */
    public static void setOptics(int kind, boolean artistic, int duration, float[] strengths,
                                 int phase, float illum, float alt, float eclipse, float moonlightValue) {
        float[] copy = new float[OpticalField.KIND_COUNT];
        if (strengths != null) {
            System.arraycopy(strengths, 0, copy, 0, Math.min(copy.length, strengths.length));
        }
        opticsKind = kind;
        opticsArtistic = artistic;
        opticsDuration = duration;
        opticsStrength = copy;
        moonPhase = phase;
        moonIllum = illum;
        moonAlt = alt;
        moonEclipse = eclipse;
        moonlight = moonlightValue;
        moonSynced = true;
    }

    /** 断线时回到"未同步"状态，下一局的月亮读数不被上一局污染。 */
    public static void resetOptics() {
        opticsKind = 0;
        opticsArtistic = false;
        opticsDuration = 0;
        opticsStrength = new float[OpticalField.KIND_COUNT];
        moonPhase = 0;
        moonIllum = 0f; moonAlt = 0f; moonEclipse = 0f; moonlight = 0f;
        moonSynced = false;
        exposure = 0f; visibility = 1f; exposureGrade = 0;
    }

    public static boolean moonSynced() { return moonSynced; }
    /** 渲染线程调用：先取局部引用，再读下标，保证一致。 */
    public static float strength(OpticalField.Kind kind) {
        float[] s = opticsStrength;
        int i = kind.ordinal();
        return i >= 0 && i < s.length ? s[i] : 0f;
    }
    public static float strength(int kindIndex) {
        float[] s = opticsStrength;
        return kindIndex >= 0 && kindIndex < s.length ? s[kindIndex] : 0f;
    }
    public static int opticsKind() { return opticsKind; }
    public static boolean opticsArtistic() { return opticsArtistic; }
    public static int opticsDuration() { return opticsDuration; }
    // 旧 getter 保留（源码兼容），内部改为读数组
    public static float parhelion() { return strength(OpticalField.Kind.PARHELION); }
    public static float halo() { return strength(OpticalField.Kind.HALO); }
    public static float fogbow() { return strength(OpticalField.Kind.FOGBOW); }
    public static float afterglow() { return strength(OpticalField.Kind.AFTERGLOW); }
    public static float aurora() { return strength(OpticalField.Kind.AURORA); }

    // ---- 月亮 ----
    public static int moonPhase() { return moonPhase; }
    public static float moonIllum() { return moonIllum; }
    public static float moonAlt() { return moonAlt; }
    public static float moonEclipse() { return moonEclipse; }
    public static float moonlight() { return moonlight; }

    /** 0..1 flash brightness of the current lightning blink. */
    public static float flash() {
        return flash;
    }

    public static boolean postEffectsEnabled() {
        return postEffectsEnabled;
    }

    public static void setPostEffectsEnabled(boolean v) {
        postEffectsEnabled = v;
    }

    public static boolean overlayEnabled() {
        return overlayEnabled;
    }

    public static void setOverlayEnabled(boolean v) {
        overlayEnabled = v;
    }

    public static boolean togglePostEffects() {
        postEffectsEnabled = !postEffectsEnabled;
        return postEffectsEnabled;
    }

    public static boolean toggleOverlay() {
        overlayEnabled = !overlayEnabled;
        return overlayEnabled;
    }

    /** Short label for HUD/radar titles, e.g. "风暴强度 62%". */
    public static String intensityPercent() {
        return "风暴强度 " + Math.round(intensity * 100f) + "%";
    }

    /** Fog far-plane scale (1.0 = untouched, 0.08 = thick). Only used by the fog layer. */
    public static float fogScale() {
        if (intensity < 0.25f) return 1f;
        float t = (intensity - 0.25f) / 0.75f;
        return Mth.clamp(1f - t * 0.82f, 0.14f, 1f);
    }
}
