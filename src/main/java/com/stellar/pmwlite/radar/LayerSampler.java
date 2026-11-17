package com.stellar.pmwlite.radar;

import com.stellar.pmwlite.PMWLite;
import com.stellar.pmwlite.atmos.AtmosphereGrid;
import com.stellar.pmwlite.atmos.AtmosphereManager;
import com.stellar.pmwlite.config.ServerConfig;
import com.stellar.pmwlite.network.AtmosLayerPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

/**
 * 把本地大气网格采样成一张 16×16 的图层，供雷达 GUI 显示。
 *
 * 采样平面取玩家视线高度那一层（不是地面），因为玩家关心"我这一高度上周围是什么"。
 * 空值（实心地形/越界）写入 -1，客户端画成透明——这样地下挖洞时雷达上会出现真实形状的空洞。
 */
public final class LayerSampler {

    private LayerSampler() {}

    public static AtmosLayerPacket sample(ServerLevel level, ServerPlayer player, int kind) {
        int k = Mth.clamp(kind, 0, AtmosLayerPacket.LAYER_COUNT - 1);
        PMWLite mod = PMWLite.instance();
        if (mod == null) return AtmosLayerPacket.empty(k);
        ServerConfig cfg = mod.config();
        if (!cfg.doAtmosphere) return AtmosLayerPacket.empty(k);

        AtmosphereManager am = AtmosphereManager.of(level, cfg);
        AtmosphereGrid grid = am.grid();
        int g = AtmosphereGrid.SX;
        if (!grid.ready()) return AtmosLayerPacket.empty(k);

        double wy = player.getY() + 1.4;
        AtmosphereGrid.Probe probe = new AtmosphereGrid.Probe();
        float[] values = new float[g * g];
        byte[] angles = k == AtmosLayerPacket.LAYER_WIND ? new byte[g * g] : null;

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int z = 0; z < g; z++) {
            for (int x = 0; x < g; x++) {
                double wx = grid.originX() + x * AtmosphereGrid.CELL + AtmosphereGrid.CELL * 0.5;
                double wz = grid.originZ() + z * AtmosphereGrid.CELL + AtmosphereGrid.CELL * 0.5;
                int i = z * g + x;
                if (!grid.probe(wx, wy, wz, probe)) {
                    values[i] = Float.NaN;
                    continue;
                }
                float v = switch (k) {
                    // 云长在空中，玩家视线高度往往没有云，所以这一层查整根气柱
                    case AtmosLayerPacket.LAYER_PRECIP -> grid.columnCloud(wx, wz) * 100f;
                    case AtmosLayerPacket.LAYER_PRESSURE -> probe.pressureHpa;
                    case AtmosLayerPacket.LAYER_TEMP -> probe.tempC;
                    case AtmosLayerPacket.LAYER_HUMIDITY -> probe.humidity * 100f;
                    case AtmosLayerPacket.LAYER_OXYGEN -> probe.oxygen * 100f;
                    case AtmosLayerPacket.LAYER_WIND -> probe.windSpeed;
                    default -> probe.apparentC;
                };
                values[i] = v;
                if (!Float.isNaN(v)) {
                    if (v < min) min = v;
                    if (v > max) max = v;
                }
                if (angles != null) {
                    float ang = (float) Math.atan2(probe.windZ, probe.windX);
                    angles[i] = (byte) Math.round((ang + Math.PI) / (Math.PI * 2) * 255f - 128f);
                }
            }
        }
        if (min > max) {
            min = 0f;
            max = 1f;
        }
        if (max - min < 1e-4f) {
            max = min + 1f;
        }

        byte[] cells = new byte[g * g];
        for (int i = 0; i < cells.length; i++) {
            float v = values[i];
            cells[i] = Float.isNaN(v) ? -1
                    : (byte) Mth.clamp(Math.round((v - min) / (max - min) * 255f), 0, 255);
        }

        String unit = switch (k) {
            case AtmosLayerPacket.LAYER_PRECIP -> "%";
            case AtmosLayerPacket.LAYER_PRESSURE -> "hPa";
            case AtmosLayerPacket.LAYER_TEMP -> "°C";
            case AtmosLayerPacket.LAYER_HUMIDITY -> "%";
            case AtmosLayerPacket.LAYER_OXYGEN -> "%";
            case AtmosLayerPacket.LAYER_WIND -> "m/s";
            default -> "°C";
        };

        return new AtmosLayerPacket(k, g, grid.originX(), grid.originZ(), AtmosphereGrid.CELL,
                min, max, AtmosLayerPacket.NAMES[k], unit, cells, angles, min, max);
    }
}
