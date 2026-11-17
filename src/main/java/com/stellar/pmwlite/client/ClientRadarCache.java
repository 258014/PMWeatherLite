package com.stellar.pmwlite.client;

import com.stellar.pmwlite.network.NetworkHandler;
import com.stellar.pmwlite.network.RadarRequestPacket;
import com.stellar.pmwlite.network.RadarSyncPacket;
import net.minecraft.Util;
import net.minecraftforge.network.PacketDistributor;

/**
 * Client-side holder for the latest radar snapshot.
 *
 * The server owns the truth; this is a dumb cache. A snapshot older than {@link #STALE_MS} is
 * flagged so the radar screen can show "数据过期" instead of pretending to be live.
 */
public final class ClientRadarCache {
    /** A snapshot older than this is considered stale (the screen refreshes at 1 Hz). */
    public static final long STALE_MS = 2500L;

    private static volatile RadarSyncPacket latest;
    private static volatile long receivedAt = 0L;

    private ClientRadarCache() {}

    /** Called from the packet handler on the client thread. */
    public static void accept(RadarSyncPacket msg) {
        latest = msg;
        receivedAt = Util.getMillis();
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

    public static void clear() {
        latest = null;
        receivedAt = 0L;
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
}
