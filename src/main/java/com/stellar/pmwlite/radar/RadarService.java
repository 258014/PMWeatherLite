package com.stellar.pmwlite.radar;

import com.stellar.pmwlite.PMWLite;
import com.stellar.pmwlite.asset.FixedAssetManager;
import com.stellar.pmwlite.forecast.ForecastEngine;
import com.stellar.pmwlite.fortify.FortificationManager;
import com.stellar.pmwlite.network.NetworkHandler;
import com.stellar.pmwlite.network.RadarSyncPacket;
import com.stellar.pmwlite.weather.Storm;
import com.stellar.pmwlite.weather.StormManager;
import com.stellar.pmwlite.weather.WeatherSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the radar snapshot on the server and ships it to one player.
 *
 * Reads only public APIs of StormManager / WeatherSystem / FixedAssetManager, so the radar is a
 * pure consumer of existing state -- no new coupling into the simulation.
 */
public final class RadarService {
    /** Radar range in blocks; the GUI scales blips into this radius. */
    public static final int RANGE = 512;

    private RadarService() {}

    public static void sendTo(ServerPlayer player) {
        PMWLite mod = PMWLite.instance();
        if (mod == null) return;
        ServerLevel level = player.serverLevel();
        StormManager sm = mod.stormManager(level);
        WeatherSystem ws = mod.weatherSystem(level);

        Vec3 eye = player.position();
        List<RadarSyncPacket.Blip> blips = new ArrayList<>();

        int stormCount = 0;
        if (sm != null) {
            for (Storm s : sm.getStorms()) {
                float dx = (float) (s.position.x - eye.x);
                float dz = (float) (s.position.z - eye.z);
                if (Math.abs(dx) > RANGE || Math.abs(dz) > RANGE) continue;
                blips.add(new RadarSyncPacket.Blip((byte) 0, dx, dz,
                        s.influenceRadius, s.rainIntensity(), s.stormType.ordinal()));
                stormCount++;
            }
        }

        int eventCount = 0;
        if (ws != null) {
            for (WeatherSystem.WeatherEvent e : ws.getActive()) {
                float dx = (float) (e.center.x - eye.x);
                float dz = (float) (e.center.z - eye.z);
                if (Math.abs(dx) > RANGE || Math.abs(dz) > RANGE) continue;
                blips.add(new RadarSyncPacket.Blip((byte) 1, dx, dz,
                        e.type.outerRadiusBlocks, e.intensity, e.type.ordinal()));
                eventCount++;
            }
        }

        FixedAssetManager assets = FixedAssetManager.of(level);
        int assetCount = 0;
        for (FixedAssetManager.AssetRecord a : assets.all()) {
            float dx = a.pos().getX() + 0.5f - (float) eye.x;
            float dz = a.pos().getZ() + 0.5f - (float) eye.z;
            if (Math.abs(dx) > RANGE || Math.abs(dz) > RANGE) continue;
            blips.add(new RadarSyncPacket.Blip((byte) 2, dx, dz, 4f, 1f, 0));
            assetCount++;
        }

        var f = ForecastEngine.forecast(level, player.blockPosition(), sm);
        int reinforced = FortificationManager.of(level).reinforcedCount();

        RadarSyncPacket packet = new RadarSyncPacket(
                f.alertLevel(), f.risk(), f.etaSeconds(), f.confidence(), f.precise(),
                f.headline(), f.expectedTypes(), blips,
                stormCount, eventCount, assetCount, reinforced);

        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /**
     * 单层大气数据（雷达图层切换时按需请求）。
     * 图层编号在 {@code AtmosLayerPacket} 里定义，超出范围会被夹紧。
     */
    public static void sendLayer(ServerPlayer player, int kind) {
        ServerLevel level = player.serverLevel();
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                com.stellar.pmwlite.radar.LayerSampler.sample(level, player, kind));
    }

    /**
     * 1 Hz 大气探针推送。HUD 与雷达信息栏共用这一份数据，保证显示与判定同源。
     */
    public static void sendProbe(ServerPlayer player) {
        PMWLite mod = PMWLite.instance();
        if (mod == null) return;
        ServerLevel level = player.serverLevel();
        var manager = com.stellar.pmwlite.atmos.AtmosphereManager.of(level, mod.config());
        var probe = new com.stellar.pmwlite.atmos.AtmosphereGrid.Probe();
        boolean ok = manager.probe(player.position().add(0, player.getEyeHeight(), 0), probe);
        // 联合暴露与服务端效果判定用同一个纯函数、同一份探针、同一个降雨读数，
        // 保证 HUD 显示的等级就是玩家实际承受的等级。
        float exposure = 0f, visibility = 1f;
        int grade = 0;
        if (ok) {
            float rain = mod.config().wetDeposition ? level.getRainLevel(1f) : 0f;
            var ex = com.stellar.pmwlite.atmos.GasExposure.fromProbe(probe, rain, null);
            exposure = ex.index;
            grade = ex.grade.ordinal();
            visibility = ex.visibility;
        }
        com.stellar.pmwlite.network.AtmosProbePacket packet = ok
                ? new com.stellar.pmwlite.network.AtmosProbePacket(true, probe.underground,
                probe.pressureHpa, probe.tempC, probe.humidity, probe.oxygen,
                probe.windSpeed, probe.apparentC, probe.dewPointC, probe.cloudBaseM,
                probe.cloud, 0f, probe.co2,
                probe.gases[com.stellar.pmwlite.atmos.GasField.SO2],
                probe.gases[com.stellar.pmwlite.atmos.GasField.NO2],
                probe.gases[com.stellar.pmwlite.atmos.GasField.CO],
                probe.gases[com.stellar.pmwlite.atmos.GasField.PM],
                exposure, grade, visibility)
                : new com.stellar.pmwlite.network.AtmosProbePacket(false, false,
                0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
                0f, 0, 1f);
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /** 1 Hz 光学现象状态推送（与探针同频，供 HUD 文案与屏幕叠加层共用）。 */
    public static void sendOptics(ServerPlayer player) {
        PMWLite mod = PMWLite.instance();
        if (mod == null || !mod.config().opticsSystem) return;
        ServerLevel level = player.serverLevel();
        var manager = com.stellar.pmwlite.atmos.AtmosphereManager.of(level, mod.config());
        var snap = manager.optics().snapshot();
        var probe = new com.stellar.pmwlite.atmos.AtmosphereGrid.Probe();
        manager.probe(player.position().add(0, player.getEyeHeight(), 0), probe);
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                com.stellar.pmwlite.network.AtmosOpticsPacket.from(
                        snap, level.getDayTime(), probe.cloud, level.getRainLevel(1f),
                        mod.config().moonSystem));
    }
}
