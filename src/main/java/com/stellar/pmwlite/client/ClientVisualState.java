package com.stellar.pmwlite.client;

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
    private static volatile float parhelion = 0f, halo = 0f, fogbow = 0f, afterglow = 0f, aurora = 0f;
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

    /** 0..1 污染浑浊度：CO₂ 超过 1% 起逐渐明显，5% 封顶；PM 也计入（取较大者）。 */
    public static float pollution() {
        float byCo2 = Mth.clamp((co2 - 0.01f) / 0.04f, 0f, 1f);
        return Math.max(byCo2, Mth.clamp(pm, 0f, 1f) * 0.8f);
    }

    // ---- 多气体读数 ----
    public static void setGases(float s, float n, float c, float p) {
        so2 = s; no2 = n; co = c; pm = p;
    }
    public static float so2() { return so2; }
    public static float no2() { return no2; }
    public static float co() { return co; }
    public static float pm() { return pm; }

    // ---- 光学现象状态 ----
    public static void setOptics(int kind, boolean artistic, int duration,
                                 float ph, float ha, float fb, float ag, float au) {
        opticsKind = kind;
        opticsArtistic = artistic;
        opticsDuration = duration;
        parhelion = ph; halo = ha; fogbow = fb; afterglow = ag; aurora = au;
    }
    public static int opticsKind() { return opticsKind; }
    public static boolean opticsArtistic() { return opticsArtistic; }
    public static int opticsDuration() { return opticsDuration; }
    public static float parhelion() { return parhelion; }
    public static float halo() { return halo; }
    public static float fogbow() { return fogbow; }
    public static float afterglow() { return afterglow; }
    public static float aurora() { return aurora; }

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
