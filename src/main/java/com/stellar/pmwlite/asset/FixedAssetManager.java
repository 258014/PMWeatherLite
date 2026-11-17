package com.stellar.pmwlite.asset;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Per-dimension registry of placed fixed assets.
 *
 * Why a registry instead of a BlockEntity: the existing systems (StormManager, WeatherSystem,
 * TornadoDestroyHandler, the radar) already work on plain positions, and v0.1.0 shipped no
 * block entities at all. A position map keeps those call sites untouched, avoids NBT migration,
 * and is trivially bounded.
 *
 * BACKWARD COMPATIBILITY: nothing here is required by the old code paths. If the map is empty,
 * every existing behaviour is bit-identical to v0.1.0.
 */
public final class FixedAssetManager {
    /** Hard cap so a grief-y world cannot grow the registry without bound. */
    public static final int MAX_ASSETS = 512;
    /** A station provides precise forecasts inside this radius. */
    public static final int STATION_COVERAGE = 256;

    private static final Map<ServerLevel, FixedAssetManager> INSTANCES = new WeakHashMap<>();

    private final ServerLevel level;
    private final Map<BlockPos, AssetRecord> assets = new HashMap<>();

    /** Immutable snapshot entry; BlockPos is already immutable, no copy needed. */
    public record AssetRecord(BlockPos pos, String kind, long placedAt) {}

    private FixedAssetManager(ServerLevel level) {
        this.level = level;
    }

    public static FixedAssetManager of(ServerLevel level) {
        return INSTANCES.computeIfAbsent(level, FixedAssetManager::new);
    }

    /** Drop all state for a dimension (called on LevelEvent.Unload). */
    public static void clear(ServerLevel level) {
        INSTANCES.remove(level);
    }

    public boolean register(BlockPos pos, String kind, long gameTime) {
        if (assets.size() >= MAX_ASSETS && !assets.containsKey(pos)) return false;
        assets.put(pos.immutable(), new AssetRecord(pos.immutable(), kind, gameTime));
        return true;
    }

    public void unregister(BlockPos pos) {
        assets.remove(pos);
    }

    public boolean isAsset(BlockPos pos) {
        return assets.containsKey(pos);
    }

    public int size() {
        return assets.size();
    }

    public Collection<AssetRecord> all() {
        return assets.values();
    }

    /** True when at least one station covers the given position. */
    public boolean coveredByStation(BlockPos pos) {
        int r2 = STATION_COVERAGE * STATION_COVERAGE;
        for (AssetRecord a : assets.values()) {
            if (a.pos().distSqr(pos) <= r2) return true;
        }
        return false;
    }

    /** Stations within a radius, used by the radar display. */
    public List<AssetRecord> stationsNear(BlockPos center, int radius) {
        int r2 = radius * radius;
        List<AssetRecord> out = new ArrayList<>();
        for (AssetRecord a : assets.values()) {
            if (a.pos().distSqr(center) <= r2) out.add(a);
        }
        return out;
    }

    /** Bound the work done by any single scan; safe to call every tick. */
    public void pruneInvalid() {
        if (assets.isEmpty()) return;
        assets.entrySet().removeIf(e -> {
            var state = level.getBlockState(e.getKey());
            return !(state.getBlock() instanceof IFixedAsset) || state.isAir();
        });
    }
}
