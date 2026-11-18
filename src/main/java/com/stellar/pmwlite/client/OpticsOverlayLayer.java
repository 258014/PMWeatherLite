package com.stellar.pmwlite.client;

import com.stellar.pmwlite.atmos.GasExposure;
import com.stellar.pmwlite.atmos.OpticalField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * 环境光学与天文现象的屏幕合成层 / Screen-space optical + lunar layer.
 *
 * 全程走 {@code GuiGraphics}（2D 管线），因此在原版、Embeddium、OptiFine 和任意
 * Oculus/Iris 光影包下表现一致——不碰光影包的后链，不写它的 uniform，不会"双天空"。
 * 这正是本 mod 一贯的兼容策略（见 docs/09）。
 *
 * 画面是**示意性的**：用有限条带/弧/光束程序化画出方位与配色，而不是光线追踪。
 * 每一项都有科学的产生条件（OpticalField 负责判定），但屏幕投影是游戏化近似：
 *  - 色序遵循现实：主虹/月华内紫外红，环天顶弧蓝紫在上红在下，
 *    环地平弧红在上紫在下，幻日/日晕内红外紫，宝光红色外缘；
 *  - 极光配色遵循氧 557.7nm 绿 / 630nm 红 / 氮 427.8nm 蓝紫；
 *  - 月亮按 LunarField 的相与照度画明暗，月食时叠铜红本影。
 *
 * 成本上限固定：每类现象 ≤64 段、圆盘 ≤1、HUD ≤4 行，无纹理分配。
 */
public final class OpticsOverlayLayer {
    private static final int AURORA_BANDS = 10;
    private static final int ARC_SEGMENTS = 48;
    private static final int BEAM_COUNT = 12;

    private OpticsOverlayLayer() {}

    public static void render(GuiGraphics g, float partialTick) {
        if (!ClientVisualState.overlayEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.options.hideGui) return;

        int w = g.guiWidth(), h = g.guiHeight();
        long t = mc.level.getGameTime();

        // 屏幕锚点：太阳/反日点/月亮都从同一份 LightingState 推导，
        // 保证光学层与后处理/雾层用的是同一个方向事实。
        int sunX = anchorX(LightingState.screenSunX, w);
        int sunY = anchorY(LightingState.screenSunY, h);
        int antiX = w - sunX;
        int antiY = h - sunY;
        int moonX = anchorX(LightingState.screenMoonX, w);
        int moonY = anchorY(LightingState.screenMoonY, h);

        // ── 原有 5 种 ──────────────────────────────────────────────
        float aurora = strength(OpticalField.Kind.AURORA);
        if (aurora > 0.12f) aurora(g, w, h, aurora, t, partialTick);
        float parhelion = strength(OpticalField.Kind.PARHELION);
        if (parhelion > 0.12f) parhelion(g, w, h, parhelion, sunX, sunY);
        float halo = strength(OpticalField.Kind.HALO);
        if (halo > 0.12f) ring(g, sunX, sunY, (int) (w * 0.16f), (int) (h * 0.17f),
                0, 360, halo, 0xE88A5A, 0x8A7AE8, 1f);
        float fogbow = strength(OpticalField.Kind.FOGBOW);
        if (fogbow > 0.12f) fogbow(g, w, h, fogbow, antiX, antiY);
        float afterglow = strength(OpticalField.Kind.AFTERGLOW);
        if (afterglow > 0.12f) afterglow(g, w, h, afterglow);

        // ── v0.3.4 冰晶弧族 ────────────────────────────────────────
        // 环天顶弧：高空大弧，蓝紫在上（外）、红在下（内）
        float cza = strength(OpticalField.Kind.CIRCUMZENITHAL);
        if (cza > 0.12f) ring(g, sunX, (int) (h * 0.85f), (int) (h * 0.78f), (int) (h * 0.62f),
                198, 342, cza, 0x6A8AE8, 0xE86A4A, 0.85f);
        // 环地平弧：低空大弧，红在上（外）、紫在下（内）
        float cha = strength(OpticalField.Kind.CIRCUMHORIZONTAL);
        if (cha > 0.12f) ring(g, sunX, (int) (h * 1.45f), (int) (h * 1.05f), (int) (h * 0.72f),
                205, 335, cha, 0xE86A4A, 0x8A5AE8, 0.85f);
        float pillar = strength(OpticalField.Kind.SUN_PILLAR);
        if (pillar > 0.12f) sunPillar(g, sunX, sunY, h, pillar);

        // ── 水滴与散射族 ────────────────────────────────────────────
        float iridescent = strength(OpticalField.Kind.IRIDESCENT);
        if (iridescent > 0.12f) iridescent(g, sunX, sunY, w, iridescent, t);
        float glory = strength(OpticalField.Kind.GLORY);
        if (glory > 0.12f) glory(g, antiX, antiY, h, glory);
        float rainbow = strength(OpticalField.Kind.RAINBOW);
        if (rainbow > 0.12f) doubleRainbow(g, antiX, antiY, h, rainbow);

        // ── 曙暮与地影族 ────────────────────────────────────────────
        float crepuscular = strength(OpticalField.Kind.CREPUSCULAR);
        if (crepuscular > 0.12f) rays(g, sunX, sunY, w, h, crepuscular, 0xFFD890, false);
        float anticrepuscular = strength(OpticalField.Kind.ANTICREPUSCULAR);
        if (anticrepuscular > 0.12f) rays(g, antiX, antiY, w, h, anticrepuscular, 0xFFB0A0, true);
        float belt = strength(OpticalField.Kind.BELT_VENUS);
        if (belt > 0.12f) horizonBand(g, w, h, (int) (h * 0.62f), (int) (h * 0.10f),
                belt, 0xE88594, 46);
        float earthShadow = strength(OpticalField.Kind.EARTH_SHADOW);
        if (earthShadow > 0.12f) horizonBand(g, w, h, (int) (h * 0.70f), (int) (h * 0.14f),
                earthShadow, 0x2E3E78, 60);
        float airglow = strength(OpticalField.Kind.AIRGLOW);
        if (airglow > 0.12f) horizonBand(g, w, h, (int) (h * 0.42f), (int) (h * 0.07f),
                airglow, 0x4AC8A0, 24);

        // ── 地平线畸变与极限色 ──────────────────────────────────────
        float fata = strength(OpticalField.Kind.FATA_MORGANA);
        if (fata > 0.12f) fataMorgana(g, w, h, fata, t);
        float blue = strength(OpticalField.Kind.BLUE_SUN);
        if (blue > 0.12f) glowBlob(g, sunX, sunY, (int) (12 + 10 * blue),
                rgb(blue, 0x4C6CE0), 4);
        float green = strength(OpticalField.Kind.GREEN_FLASH);
        if (green > 0.12f) greenFlash(g, sunX, h, green);

        // ── 月亮：相、月面明暗、月食、月华 ───────────────────────────
        if (ClientVisualState.moonSynced() && ClientVisualState.moonAlt() > -6f
                && LightingState.screenMoonFacing > 0f) {
            drawMoon(g, moonX, moonY, h, t);
        }
        float corona = strength(OpticalField.Kind.MOON_CORONA);
        if (corona > 0.12f && LightingState.screenMoonFacing > 0f) {
            ring(g, moonX, moonY, (int) (10 + 22 * corona), (int) (9 + 19 * corona),
                    0, 360, corona, 0x88B0D8, 0xD8A080, 0.75f);
        }

        opticsHud(g, w);
    }

    private static float strength(OpticalField.Kind kind) {
        return ClientVisualState.strength(kind);
    }

    /** 方向余弦 → 屏幕像素；FOV 用 0.55·屏幕尺寸近似，够画示意层。 */
    private static int anchorX(float dot, int w) {
        return (int) Mth.clamp(w * 0.5f + dot * w * 0.55f, -w * 0.2f, w * 1.2f);
    }

    private static int anchorY(float dot, int h) {
        return (int) Mth.clamp(h * 0.5f - dot * h * 0.55f, -h * 0.2f, h * 1.2f);
    }

    private static int rgb(float s, int rgb) {
        int a = (int) (Mth.clamp(s, 0f, 1f) * 150f);
        return (a << 24) | rgb;
    }

    // ──────────────────────────────────────────────────────────────
    // 通用绘制原语（全固定段数，无分配）
    // ──────────────────────────────────────────────────────────────

    /** 柔性光斑：叠加半透明矩形近似径向衰减（不做纹理，成本固定）。 */
    private static void glowBlob(GuiGraphics g, int cx, int cy, int radius, int argb, int layers) {
        for (int l = layers; l >= 1; l--) {
            int r = radius * l / layers;
            int a = ((argb >>> 24) * (layers - l + 1)) / (layers * layers);
            int col = (a << 24) | (argb & 0x00FFFFFF);
            g.fill(cx - r, cy - r, cx + r, cy + r, col);
        }
    }

    /**
     * 椭圆弧：外圈 outer、内圈 inner，用两段实现色序。
     * start/end 为角度（度），0=右、90=下（屏幕坐标）。
     */
    private static void ring(GuiGraphics g, int cx, int cy, int rx, int ry,
                             float start, float end, float s, int outer, int inner, float alpha) {
        int a = (int) (Mth.clamp(s, 0f, 1f) * 90f * alpha);
        if (a <= 1) return;
        int seg = ARC_SEGMENTS;
        int thick = Math.max(2, (int) (2 + 3 * s));
        for (int pass = 0; pass < 2; pass++) {
            int rrX = Math.max(2, rx - pass * thick);
            int rrY = Math.max(2, ry - pass * thick);
            int col = (a << 24) | (pass == 0 ? outer : inner);
            int step = Math.max(1, seg / 24);
            for (int i = 0; i <= seg; i += step) {
                double ang = Math.toRadians(start + (end - start) * i / (double) seg);
                int x = cx + (int) (Math.cos(ang) * rrX);
                int y = cy + (int) (Math.sin(ang) * rrY);
                g.fill(x - thick / 2, y - thick / 2, x + thick / 2 + 1, y + thick / 2 + 1, col);
            }
        }
    }

    /** 地平线色带：从 y 开始的若干横条，越往下越淡。 */
    private static void horizonBand(GuiGraphics g, int w, int h, int y, int bandH,
                                    float s, int rgb, int alphaMax) {
        int bands = 6;
        int bh = Math.max(2, bandH / bands);
        for (int k = 0; k < bands; k++) {
            int a = (int) (s * (1f - k / (float) bands) * alphaMax);
            if (a <= 1) continue;
            g.fill(0, y + k * bh, w, y + (k + 1) * bh, (a << 24) | rgb);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 各现象
    // ──────────────────────────────────────────────────────────────

    /** 极光帘幕：竖条随时间横向漂移，绿→红/紫按强度分层。 */
    private static void aurora(GuiGraphics g, int w, int h, float s, long t, float pt) {
        int top = 0;
        int curtainH = (int) (h * (0.18f + 0.22f * s));
        int bandW = Math.max(4, w / AURORA_BANDS / 2);
        float phase = (t + pt) * 0.03f;
        for (int k = 0; k < AURORA_BANDS; k++) {
            float p = k / (float) (AURORA_BANDS - 1);
            float wave = 0.55f + 0.45f * (float) Math.sin(phase + k * 0.9f)
                    * (float) Math.cos(phase * 0.6f + k * 0.37f);
            int len = (int) (curtainH * (0.35f + 0.65f * Math.abs(wave)));
            int x = (int) (p * w + Math.sin(phase * 0.5f + k) * bandW * 1.5f);
            if (len < 2) continue;
            int a = (int) (Mth.clamp(s, 0f, 1f) * (40f + 55f * Math.abs(wave)));
            g.fill(x, top, x + bandW, top + len, (a << 24) | 0x3AE088);
            if (s > 0.55f) {
                int ar = (int) ((s - 0.55f) * 90f);
                g.fill(x, top, x + bandW, top + (int) (len * 0.28f),
                        (ar << 24) | 0xC8425C);
            }
            if (s > 0.7f) {
                int ab = (int) ((s - 0.7f) * 110f);
                g.fill(x, top + len - Math.max(2, len / 6), x + bandW, top + len,
                        (ab << 24) | 0x6A7CE8);
            }
        }
    }

    /** 幻日：真太阳两侧约 22° 处两枚光斑，内侧红、外侧蓝紫。 */
    private static void parhelion(GuiGraphics g, int w, int h, float s, int sunX, int sunY) {
        int off = (int) (w * 0.16f);
        int r = Math.max(6, (int) (10 + 14 * s));
        for (int side = -1; side <= 1; side += 2) {
            int bx = sunX + side * off;
            glowBlob(g, bx - side * (r / 2), sunY, r, rgb(s, 0xE86A4A), 4);
            glowBlob(g, bx + side * (r / 2), sunY, r, rgb(s, 0x6A8AE8), 4);
        }
    }

    /** 雾虹：背日点附近一道柔弧，色序内紫外红。 */
    private static void fogbow(GuiGraphics g, int w, int h, float s, int antiX, int antiY) {
        int r = (int) (h * 0.55f);
        int a = (int) (s * 70f);
        int seg = 40;
        double start = Math.PI * 1.15, end = Math.PI * 1.85;
        for (int i = 0; i < seg; i++) {
            double ang = start + (end - start) * i / (double) seg;
            int x = antiX + (int) (Math.cos(ang) * r);
            int y = antiY + (int) (Math.sin(ang) * r);
            g.fill(x - 2, y - 2, x + 2, y + 2, (a << 24) | 0xE8D0A0);
        }
    }

    /** 污染霞光：屏幕底部暖红褐渐变。 */
    private static void afterglow(GuiGraphics g, int w, int h, float s) {
        horizonBand(g, w, h, h - Math.max(2, h / 12) * 6, Math.max(2, h / 12), s, 0xD0703A, 42);
    }

    /** 日柱：穿过太阳的竖直光柱，顶部更宽更淡。 */
    private static void sunPillar(GuiGraphics g, int cx, int cy, int h, float s) {
        int top = Math.max(0, cy - (int) (h * 0.42f));
        int bottom = Math.min(h, cy + (int) (h * 0.14f));
        int halfW = Math.max(3, (int) (4 + 6 * s));
        int a = (int) (s * 70f);
        g.fill(cx - halfW / 2, top, cx + halfW / 2, bottom, (a << 24) | 0xFFD8A0);
        g.fill(cx - 1, top, cx + 1, bottom, ((a * 2) << 24) | 0xFFF0D0);
    }

    /** 虹彩云：太阳周围几片粉彩薄云，颜色随相位缓慢漂移。 */
    private static void iridescent(GuiGraphics g, int cx, int cy, int w, float s, long t) {
        int[] colors = {0xC8A0E8, 0x88C8E8, 0xE8B0C8, 0xB0E0C0};
        for (int k = 0; k < 4; k++) {
            float drift = (float) Math.sin(t * 0.008f + k * 1.7f);
            int x = cx + (int) (drift * w * 0.10f) + (k - 2) * (int) (w * 0.045f);
            int y = cy + (int) (Math.cos(t * 0.006f + k) * 18f) + k * 8;
            glowBlob(g, x, y, (int) (14 + 12 * s), rgb(s * 0.55f, colors[k]), 3);
        }
    }

    /** 宝光：反日点周围三圈彩色环，红色外缘。 */
    private static void glory(GuiGraphics g, int cx, int cy, int h, float s) {
        int r = (int) (h * 0.16f);
        int[] cols = {0xE86A4A, 0xE8C86A, 0x6A8AE8};
        for (int k = 0; k < 3; k++) {
            ring(g, cx, cy, r + k * 10, r + k * 9, 0, 360, s * (1f - k * 0.2f),
                    cols[k], cols[(k + 1) % 3], 0.65f);
        }
    }

    /** 双彩虹：主虹内紫外红；外侧霓内红外紫、更暗；中间留亚历山大暗带。 */
    private static void doubleRainbow(GuiGraphics g, int cx, int cy, int h, float s) {
        int r = (int) (h * 0.62f);
        ring(g, cx, cy, r, (int) (r * 0.82f), 190, 350, s, 0xE86A4A, 0x6A8AE8, 1f);
        int r2 = (int) (r * 1.16f);
        ring(g, cx, cy, r2, (int) (r2 * 0.82f), 190, 350, s * 0.5f,
                0x8A6AE8, 0xE86A4A, 0.7f);
    }

    /** 曙暮辉 / 反曙暮辉：从光源（或反日点）发散/汇聚的定长光束。 */
    private static void rays(GuiGraphics g, int cx, int cy, int w, int h,
                             float s, int rgb, boolean converge) {
        int len = (int) (h * (0.42f + 0.25f * s));
        int a = (int) (s * 26f);
        for (int k = 0; k < BEAM_COUNT; k++) {
            float p = k / (float) (BEAM_COUNT - 1) - 0.5f;
            int x0 = cx + (int) (p * w * 0.65f);
            int x1 = cx + (int) (p * w * (converge ? 0.08f : 0.95f));
            int y0 = cy;
            int y1 = Math.min(h, Math.max(0, cy + (converge ? len : -len)));
            drawBeam(g, x0, y0, x1, y1, a, rgb);
        }
    }

    /** 用 8 段矩形近似一条渐隐光束（无浮点纹理、无分配）。 */
    private static void drawBeam(GuiGraphics g, int x0, int y0, int x1, int y1, int a, int rgb) {
        int seg = 8;
        for (int i = 0; i < seg; i++) {
            float f0 = i / (float) seg, f1 = (i + 1) / (float) seg;
            int xa = x0 + (int) ((x1 - x0) * f0);
            int ya = y0 + (int) ((y1 - y0) * f0);
            int xb = x0 + (int) ((x1 - x0) * f1);
            int yb = y0 + (int) ((y1 - y0) * f1);
            float fade = 1f - f0;
            g.fill(Math.min(xa, xb), Math.min(ya, yb),
                    Math.max(xa, xb) + 1, Math.max(ya, yb) + 1,
                    ((int) (a * fade) << 24) | rgb);
        }
    }

    /** 法塔莫甘娜：地平线附近的波导蜃景，多层上下错位、随时间缓慢起伏。 */
    private static void fataMorgana(GuiGraphics g, int w, int h, float s, long t) {
        int bands = 5;
        int y0 = (int) (h * 0.70f);
        for (int k = 0; k < bands; k++) {
            int y = y0 + k * Math.max(2, (int) (h * 0.025f));
            int a = (int) (s * (30f - k * 4f));
            int half = (int) (w * (0.16f + k * 0.03f));
            int shift = (int) (Math.sin(t * 0.02f + k * 0.9f) * w * 0.02f);
            int cx = w / 2 + shift;
            g.fill(cx - half, y, cx + half, y + 2, (a << 24) | 0xB8D8E8);
            if (k % 2 == 0) {
                g.fill(cx - half / 2, y - 4, cx + half / 2, y - 2,
                        ((a / 2) << 24) | 0xE8E0C0);
            }
        }
    }

    /** 绿闪：日出/日落最后一瞬地平线上的绿色小弧。 */
    private static void greenFlash(GuiGraphics g, int cx, int h, float s) {
        int y = h - 6;
        int half = (int) (6 + 10 * s);
        int a = (int) (s * 130f);
        g.fill(cx - half, y, cx + half, y + 3, (a << 24) | 0x40E878);
        g.fill(cx - half / 2, y - 2, cx + half / 2, y, ((a / 2) << 24) | 0xA0F0B0);
    }

    /**
     * 月亮：亮盘 + 相位明暗 + 月食本影。
     *
     * 逐行画月牙，几何与照度严格一致：每一行 y 的外缘半宽为 √(r²−y²)，
     * 明暗界线（ terminator ellipse ）把这一行分成 1−2·illum 与 2·illum 两段；
     * illum=0 完全不亮、0.5 恰好半月、1 全亮。盈月亮边在右、亏月在左。
     * 月食时把亮面颜色按食分混向铜红，并在外缘留一圈折射铜光。
     */
    private static void drawMoon(GuiGraphics g, int cx, int cy, int h, long t) {
        float illum = Mth.clamp(ClientVisualState.moonIllum(), 0f, 1f);
        float eclipse = Mth.clamp(ClientVisualState.moonEclipse(), 0f, 1f);
        if (illum <= 0.005f && eclipse <= 0.005f) return;
        int r = Math.max(7, (int) (h * 0.035f));
        int dark = 0x55202830;
        int lit = mix(0xE8F0F8, 0xB04828, eclipse * 0.85f);
        boolean waxing = ClientVisualState.moonPhase() >= 4; // 4=新月，5..7 为盈月
        float leftFrac = 1f - 2f * illum;

        // 暗盘：新月时仍留一点"地照"，避免月亮完全消失
        g.fill(cx - r, cy - r, cx + r, cy + r, dark);
        if (illum > 0.005f) {
            int rr = r * r;
            for (int dy = -r; dy <= r; dy++) {
                double outer = Math.sqrt(Math.max(0, rr - dy * dy));
                int ox = (int) outer;
                if (ox <= 0) continue;
                int boundary = (int) (outer * leftFrac);
                int xa, xb;
                if (waxing) { xa = cx + boundary; xb = cx + ox; }
                else { xa = cx - ox; xb = cx - boundary; }
                if (xb > xa) g.fill(xa, cy + dy, xb, cy + dy + 1, lit);
            }
        }
        if (eclipse > 0.02f) {
            int a = (int) (eclipse * 110f);
            // 铜红折射外缘（月全食时的"血月"边）
            g.fill(cx - r - 1, cy - r / 3, cx - r, cy + r / 3, (a << 24) | 0xC05030);
            g.fill(cx + r, cy - r / 3, cx + r + 1, cy + r / 3, (a << 24) | 0xC05030);
        }
    }

    /** 线性混色（仅 RGB，alpha 保持 src 的 0xE8 级别）。 */
    private static int mix(int a, int b, float t) {
        int k = (int) (Mth.clamp(t, 0f, 1f) * 255f);
        int inv = 255 - k;
        int r = (((a >>> 16) & 0xFF) * inv + ((b >>> 16) & 0xFF) * k) / 255;
        int g = (((a >>> 8) & 0xFF) * inv + ((b >>> 8) & 0xFF) * k) / 255;
        int bl = ((a & 0xFF) * inv + (b & 0xFF) * k) / 255;
        return 0xE8000000 | (r << 16) | (g << 8) | bl;
    }

    /** HUD：现象名 + 科学/艺术标记 + 持续时间 + 多气体/月亮/风险读数。 */
    private static void opticsHud(GuiGraphics g, int w) {
        Minecraft mc = Minecraft.getInstance();
        int kind = ClientVisualState.opticsKind();
        int lines = 0;
        String l0 = null, l1 = null, l2 = null;

        if (kind > 0 && kind < OpticalField.KIND_COUNT) {
            OpticalField.Kind k = OpticalField.Kind.values()[kind];
            String tag = ClientVisualState.opticsArtistic() ? "§d艺术化" : "§b科学";
            l0 = String.format("§7光学：%s%s§7 · %ds", k.label, tag, ClientVisualState.opticsDuration());
            lines++;
        }
        float ppm = 1e6f;
        l1 = String.format("§7SO₂ §f%.0f §7NO₂ §f%.0f §7CO §f%.0f §8ppm §7PM §f%.0f%%",
                ClientVisualState.so2() * ppm, ClientVisualState.no2() * ppm,
                ClientVisualState.co() * ppm, ClientVisualState.pm() * 100f);
        lines++;
        if (ClientVisualState.exposureGrade() > 0) {
            GasExposure.Grade gr = ClientVisualState.exposureGradeEnum();
            l2 = String.format("§7联合暴露 §f%s §7能见度 §f%.0f%%",
                    gr.label, ClientVisualState.visibility() * 100f);
            lines++;
        }
        String l3 = null;
        if (ClientVisualState.moonSynced() && ClientVisualState.moonAlt() > -6f) {
            l3 = String.format("§7月相 §f%d §7照度 §f%.0f%%%s",
                    ClientVisualState.moonPhase(), ClientVisualState.moonIllum() * 100f,
                    ClientVisualState.moonEclipse() > 0.02f
                            ? " §c[月食 " + Math.round(ClientVisualState.moonEclipse() * 100f) + "%]" : "");
            lines++;
        }
        if (lines == 0) return;

        int tw = Math.max(mc.font.width(l1),
                Math.max(l0 == null ? 0 : mc.font.width(l0),
                        Math.max(l2 == null ? 0 : mc.font.width(l2), l3 == null ? 0 : mc.font.width(l3)))) + 8;
        int x = w - tw - 6;
        int y = 46;
        g.fill(x - 2, y - 3, w - 4, y + lines * 10 + 2, 0x55101418);
        int row = 0;
        if (l0 != null) { g.drawString(mc.font, l0, x, y + row * 10, 0xFFFFFF, false); row++; }
        g.drawString(mc.font, l1, x, y + row * 10, 0xFFFFFF, false); row++;
        if (l2 != null) { g.drawString(mc.font, l2, x, y + row * 10, 0xFFFFFF, false); row++; }
        if (l3 != null) g.drawString(mc.font, l3, x, y + row * 10, 0xFFFFFF, false);
    }
}
