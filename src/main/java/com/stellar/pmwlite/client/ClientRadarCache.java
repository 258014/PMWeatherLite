package com.stellar.pmwlite.client;

import com.stellar.pmwlite.network.AtmosLayerPacket;
import com.stellar.pmwlite.network.AtmosOpticsPacket;
import com.stellar.pmwlite.network.AtmosProbePacket;
import com.stellar.pmwlite.network.NetworkHandler;
import com.stellar.pmwlite.network.RadarRequestPacket;
import com.stellar.pmwlite.network.RadarSyncPacket;
import net.minecraft.Util;
import net.minecraftforge.network.PacketDistributor;

/**
 * 客户端侧的大气/雷达数据缓存。
 *
 * 服务端是唯一事实来源；这里只保存最近一次快照，并负责"什么时候该再要一份"。
 * 所有字段都是 volatile：网络线程写、渲染线程读。
 */
public final class ClientRadarCache {
    /** A snapshot older than this is considered stale (the screen refreshes at 1 Hz). */
    public static final long STALE_MS = 2500L;

    private static volatile RadarSyncPacket latest;
    private static volatile long receivedAt = 0L;

    private static volatile AtmosLayerPacket layer;
    private static volatile long layerAt = 0L;
    private static volatile int selectedLayer = AtmosLayerPacket.LAYER_PRECIP;

    private static volatile AtmosProbePacket probe;
    private static volatile long probeAt = 0L;

    private ClientRadarCache() {}

    /** Called from the packet handler on the client thread. */
    public static void accept(RadarSyncPacket msg) {
        latest = msg;
        receivedAt = Util.getMillis();
    }

    public static void acceptLayer(AtmosLayerPacket msg) {
        layer = msg;
        layerAt = Util.getMillis();
    }

    public static void acceptProbe(AtmosProbePacket msg) {
        probe = msg;
        probeAt = Util.getMillis();
        if (msg.valid()) {
            ClientVisualState.setCo2(msg.co2());
            ClientVisualState.setGases(msg.so2(), msg.no2(), msg.co(), msg.pm());
            ClientVisualState.setExposure(msg.exposure(), msg.exposureGrade(), msg.visibility());
        }
    }

    /** 光学状态包：写到客户端视觉状态，渲染层与 HUD 共用同一份。 */
    public static void acceptOptics(AtmosOpticsPacket msg) {
        ClientVisualState.setOptics(msg.kind(), msg.artistic(), msg.durationSec(),
                msg.strengths(), msg.moonPhase(), msg.moonIllum(), msg.moonAlt(),
                msg.moonEclipse(), msg.moonlight());
    }

    public static RadarSyncPacket snapshot() {
        return latest;
    }

    public static boolean has() {
        return latest != null;
    }

    public static boolean stale() {
        return latest == null || Util.getMillis() - receivedAt > STALE_MS;
    }

    public static AtmosLayerPacket layer() {
        return layer;
    }

    public static boolean layerStale() {
        return layer == null || Util.getMillis() - layerAt > STALE_MS;
    }

    public static AtmosProbePacket probe() {
        return probe;
    }

    public static boolean probeFresh() {
        return probe != null && Util.getMillis() - probeAt < 3000L;
    }

    public static int selectedLayer() {
        return selectedLayer;
    }

    public static void setSelectedLayer(int kind) {
        int k = Math.floorMod(kind, AtmosLayerPacket.LAYER_COUNT);
        selectedLayer = k;
        requestLayer(k);
    }

    public static void clear() {
        latest = null;
        receivedAt = 0L;
        layer = null;
        layerAt = 0L;
        probe = null;
        probeAt = 0L;
        ClientVisualState.resetOptics();
    }

    /**
     * Ask the server for a fresh snapshot.
     *
     * Guarded: if the channel is not negotiated (e.g. the server has a different protocol, or we
     * are in the main menu) this silently does nothing instead of throwing.
     */
    public static void request() {
        try {
            NetworkHandler.CHANNEL.send(PacketDistributor.SERVER.noArg(), new RadarRequestPacket());
        } catch (Throwable ignored) {
            // server without the mod / channel not ready -> radar stays on its last snapshot
        }
    }

    public static void requestLayer(int kind) {
        try {
            NetworkHandler.CHANNEL.send(PacketDistributor.SERVER.noArg(),
                    new com.stellar.pmwlite.network.AtmosLayerRequestPacket(kind));
        } catch (Throwable ignored) {
            // same as above
        }
    }
}
