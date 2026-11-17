package com.stellar.pmwlite.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.stellar.pmwlite.network.RadarSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * 气象雷达 / Weather Radar GUI.
 *
 * Renders the server-built snapshot ({@link RadarSyncPacket}) as a top-down scope:
 *   - sweep + range rings (a procedurally generated dynamic texture, so the rings are smooth
 *     without shipping a PNG),
 *   - storms (red, sized by influence radius), weather events (amber), fixed assets (cyan),
 *   - a side panel with alert tier, headline, risk, ETA, confidence and counters.
 *
 * Everything is drawn through {@code GuiGraphics}, i.e. the normal 2D pipeline, which is the one
 * path that is guaranteed to look the same with and without a shader pack.
 */
public class RadarScreen extends Screen {
    /** Radar diameter in GUI pixels. */
    private static final int SCOPE = 196;
    private static final int PANEL_W = 404;
    private static final int PANEL_H = 236;

    private static final ResourceLocation GRID_ID = new ResourceLocation("pmwlite", "dynamic/radar_grid");
    private static boolean gridReady = false;

    private long lastRequest = 0L;

    public RadarScreen() {
        super(Component.literal("气象雷达"));
    }

    @Override
    protected void init() {
        ClientRadarCache.request();
        lastRequest = System.currentTimeMillis();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);

        // Live refresh at ~1 Hz while the screen is open.
        long now = System.currentTimeMillis();
        if (now - lastRequest > 1000L) {
            lastRequest = now;
            ClientRadarCache.request();
        }

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        // frame
        g.fill(left - 1, top - 1, left + PANEL_W + 1, top + PANEL_H + 1, 0xFF1B2027);
        g.fill(left, top, left + PANEL_W, top + PANEL_H, 0xE6101418);
        g.fill(left, top, left + PANEL_W, top + 14, 0xFF232A33);
        g.drawString(this.font, "§b气象雷达 §7· §fRADAR §8| §7" + ClientVisualState.intensityPercent(),
                left + 6, top + 3, 0xFFFFFF, false);

        int scopeX = left + 14;
        int scopeY = top + 26;

        drawScope(g, scopeX, scopeY);

        RadarSyncPacket p = ClientRadarCache.snapshot();
        int infoX = scopeX + SCOPE + 16;
        if (p == null) {
            g.drawString(this.font, "§7等待服务器数据…", infoX, scopeY + 8, 0xFFFFFF, false);
            g.drawString(this.font, "§8若长时间无响应，请确认服务端已安装本模组", infoX, scopeY + 22, 0x888888, false);
            drawFooter(g, left, top);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }

        drawBlips(g, scopeX, scopeY, p);
        drawSweep(g, scopeX, scopeY);
        drawInfo(g, infoX, scopeY, p);
        drawFooter(g, left, top);
        super.render(g, mouseX, mouseY, partialTick);
    }

    // ---------------- scope ----------------

    private void drawScope(GuiGraphics g, int x, int y) {
        ensureGrid();
        g.fill(x - 2, y - 2, x + SCOPE + 2, y + SCOPE + 2, 0xFF2C3742);
        g.fill(x - 1, y - 1, x + SCOPE + 1, y + SCOPE + 1, 0xFF070A0D);
        if (gridReady) {
            g.blit(GRID_ID, x, y, 0, 0, SCOPE, SCOPE, SCOPE, SCOPE);
        }
    }

    /** Blips: kind 0 = storm, 1 = weather event, 2 = fixed asset. */
    private void drawBlips(GuiGraphics g, int sx, int sy, RadarSyncPacket p) {
        int cx = sx + SCOPE / 2;
        int cy = sy + SCOPE / 2;
        float half = SCOPE / 2f;

        List<RadarSyncPacket.Blip> blips = p.blips();
        for (RadarSyncPacket.Blip b : blips) {
            float nx = b.dx() / com.stellar.pmwlite.radar.RadarService.RANGE;
            float nz = b.dz() / com.stellar.pmwlite.radar.RadarService.RANGE;
            int px = cx + (int) (nx * half);
            int py = cy + (int) (nz * half);
            if (px < sx || px > sx + SCOPE || py < sy || py > sy + SCOPE) continue;

            switch (b.kind()) {
                case 0 -> {
                    int col = b.intensity() > 0.66f ? 0xFFFF4A3A
                            : b.intensity() > 0.33f ? 0xFFFF7A3A : 0xFFB06020;
                    int halo = 3 + (int) (b.radius() / 60f);
                    g.fill(px - halo, py - halo, px + halo, py + halo, (col & 0x00FFFFFF) | 0x33000000);
                    g.fill(px - 2, py - 2, px + 2, py + 2, col);
                    g.fill(px - 1, py - 1, px + 1, py + 1, 0xFFFFFFFF);
                }
                case 1 -> {
                    int col = b.intensity() > 0.6f ? 0xFFFFC24A : 0xFFD89A2A;
                    int halo = 4;
                    g.fill(px - halo, py - halo, px + halo, py + halo, (col & 0x00FFFFFF) | 0x22000000);
                    g.fill(px - 2, py - 2, px + 2, py + 2, col);
                }
                default -> {
                    g.fill(px - 2, py - 2, px + 2, py + 2, 0xFF1A3A44);
                    g.fill(px - 1, py - 1, px + 1, py + 1, 0xFF4AD8FF);
                }
            }
        }
    }

    /** Rotating sweep line, drawn as short segments so it reads as a radar without a texture. */
    private void drawSweep(GuiGraphics g, int sx, int sy) {
        int cx = sx + SCOPE / 2;
        int cy = sy + SCOPE / 2;
        float phase = (System.currentTimeMillis() % 4000L) / 4000f;
        double ang = phase * Math.PI * 2.0;
        int segs = 26;
        for (int i = 0; i < segs; i++) {
            double r = (SCOPE / 2.0) * (i / (double) segs);
            int px = cx + (int) (Math.cos(ang) * r);
            int py = cy + (int) (Math.sin(ang) * r);
            g.fill(px, py, px + 2, py + 2, 0x5520FF9A);
        }
    }

    // ---------------- info panel ----------------

    private void drawInfo(GuiGraphics g, int x, int y, RadarSyncPacket p) {
        int w = PANEL_W - (x - (this.width - PANEL_W) / 2) - 12;

        String tier = switch (p.alertLevel()) {
            case 4 -> "§c红色预警";
            case 3 -> "§6橙色预警";
            case 2 -> "§e黄色预警";
            case 1 -> "§9蓝色预警";
            default -> "§a无预警";
        };
        g.drawString(this.font, tier, x, y, 0xFFFFFF, false);

        String head = p.headline() == null ? "" : p.headline();
        g.drawString(this.font, "§f" + trim(head, 22), x, y + 12, 0xFFFFFF, false);

        int row = y + 30;
        row = kv(g, x, row, "风险指数", String.format("§f%.2f", p.risk()));
        row = kv(g, x, row, "预计影响", p.etaSeconds() < 0 ? "§8—"
                : "§f" + p.etaSeconds() + " 秒后");
        row = kv(g, x, row, "预报置信", "§f" + p.confidence() + "%"
                + (p.precise() ? " §b(气象站精报)" : " §8(粗略)"));
        row += 4;

        g.drawString(this.font, "§7可能天气", x, row, 0xAAAAAA, false);
        row += 11;
        List<String> types = p.types();
        if (types == null || types.isEmpty()) {
            g.drawString(this.font, "§8—", x, row, 0x777777, false);
            row += 11;
        } else {
            for (int i = 0; i < Math.min(types.size(), 4); i++) {
                g.drawString(this.font, "§e· §f" + trim(types.get(i), 18), x, row, 0xFFFFFF, false);
                row += 11;
            }
        }
        row += 5;

        g.drawString(this.font, "§7风暴 §f" + p.stormCount()
                + "   §7天气事件 §f" + p.eventCount()
                + "   §7固定资产 §f" + p.assetCount(), x, row, 0xFFFFFF, false);
        row += 11;
        g.drawString(this.font, "§7已加固方块 §f" + p.reinforcedCount()
                + (p.reinforcedCount() == 0 ? " §8(用加固套件右键方块)" : ""), x, row, 0xFFFFFF, false);
        row += 14;

        g.drawString(this.font, "§8范围 " + com.stellar.pmwlite.radar.RadarService.RANGE
                + " 格 · 红=风暴 橙=天气 青=气象站", x, row, 0x777777, false);
        if (ClientRadarCache.stale()) {
            g.drawString(this.font, "§c数据过期", x, row + 11, 0xFF5555, false);
        }
        if (w < 10) {
            // never happens with the fixed layout, kept as a guard so a future resize cannot divide by 0
            return;
        }
    }

    private int kv(GuiGraphics g, int x, int y, String k, String v) {
        g.drawString(this.font, "§7" + k, x, y, 0xAAAAAA, false);
        g.drawString(this.font, v, x + 66, y, 0xFFFFFF, false);
        return y + 11;
    }

    private void drawFooter(GuiGraphics g, int left, int top) {
        g.drawString(this.font, "§8R / ESC 关闭 · 每 1 秒自动刷新",
                left + 6, top + PANEL_H - 12, 0x777777, false);
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ---------------- procedural ring texture ----------------

    /**
     * Builds a 128x128 RGBA scope background once: range rings, crosshair, degree ticks and a
     * radial falloff. Done with a {@link DynamicTexture} so the rings are actually round and no
     * binary asset has to be shipped.
     */
    private static synchronized void ensureGrid() {
        if (gridReady) return;
        Minecraft mc = Minecraft.getInstance();
        DynamicTexture tex = null;
        try {
            int size = 128;
            NativeImage img = new NativeImage(size, size, false);
            float c = size / 2f;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    float dx = (x + 0.5f) - c;
                    float dy = (y + 0.5f) - c;
                    float r = (float) Math.sqrt(dx * dx + dy * dy) / c; // 0..1 (1.41 at corners)
                    int argb = 0;
                    if (r <= 1.0f) {
                        // inner glow: darker toward the rim
                        int base = (int) (Mth.clamp(0.10f - r * 0.05f, 0f, 1f) * 255);
                        argb = 0xFF000000 | (base << 16) | ((base + 6) << 8) | (base + 10);
                        // rings at 25/50/75/100 %
                        for (float ring : new float[]{0.25f, 0.5f, 0.75f, 1.0f}) {
                            if (Math.abs(r - ring) < 0.006f) {
                                argb = ring >= 1f ? 0xFF3C5A6E : 0x66A8D8F0;
                            }
                        }
                        // crosshair
                        if (Math.abs(dx) < 0.7f || Math.abs(dy) < 0.7f) {
                            argb = 0x44A8D8F0;
                        }
                        // degree ticks every 30 degrees
                        double ang = Math.atan2(dy, dx);
                        double deg = Math.toDegrees(ang);
                        if (Math.abs(deg % 30.0) < 0.9 && r > 0.9f && r <= 1.0f) {
                            argb = 0xCC7FE8C0;
                        }
                    }
                    img.setPixelRGBA(x, y, argb);
                }
            }
            tex = new DynamicTexture(img);
            mc.getTextureManager().register(GRID_ID, tex);
            tex.upload();
            gridReady = true;
        } catch (Throwable t) {
            // A failed texture must never break the screen; the scope simply renders untextured.
            gridReady = false;
            if (tex != null) {
                try {
                    tex.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
