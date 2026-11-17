package com.stellar.pmwlite.fortify;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Player-applied reinforcement ("提前加固").
 *
 * A reinforced block gets a bonus added to its wind threshold in TornadoDestroyHandler, so the
 * same structure survives a stronger tornado. This is the mitigation half of the alert feature:
 * the forecast gives lead time, the kit converts lead time into fewer destroyed blocks.
 *
 * BACKWARD COMPATIBILITY: TornadoDestroyHandler only reads {@link #bonusAt}; when nothing has
 * been reinforced every lookup returns 0 and destruction is identical to v0.1.0.
 */
public final class FortificationManager {
    /** Bonus cap per block, in the same units as ServerConfig thresholds (≈ mph). */
    public static final int MAX_BONUS = 60;
    /** Cap on tracked positions per dimension (bounds memory on large servers). */
    public static final int MAX_ENTRIES = 8192;
    /** Bonus granted per kit application. */
    public static final int STEP = 15;

    private static final Map<ServerLevel, FortificationManager> INSTANCES = new WeakHashMap<>();

    private final Map<BlockPos, Integer> bonus = new HashMap<>();
    private int totalApplications = 0;

    private FortificationManager() {}

    public static FortificationManager of(ServerLevel level) {
        return INSTANCES.computeIfAbsent(level, l -> new FortificationManager());
    }

    public static void clear(ServerLevel level) {
        INSTANCES.remove(level);
    }

    /** Extra wind resistance at a position (0 when not reinforced). */
    public int bonusAt(BlockPos pos) {
        Integer v = bonus.get(pos);
        return v == null ? 0 : v;
    }

    /**
     * Reinforce one block.
     *
     * @return the bonus after applying, or -1 when the block is already at {@link #MAX_BONUS}
     *         or the dimension hit {@link #MAX_ENTRIES}
     */
    public int fortify(BlockPos pos) {
        BlockPos key = pos.immutable();
        Integer current = bonus.get(key);
        if (current == null && bonus.size() >= MAX_ENTRIES) return -1;
        int next = Math.min((current == null ? 0 : current) + STEP, MAX_BONUS);
        if (current != null && current >= MAX_BONUS) return -1;
        bonus.put(key, next);
        totalApplications++;
        return next;
    }

    /** Remove reinforcement (used when the block is broken). */
    public void release(BlockPos pos) {
        bonus.remove(pos);
    }

    public int reinforcedCount() {
        return bonus.size();
    }

    public int totalApplications() {
        return totalApplications;
    }

    public void clearAll() {
        bonus.clear();
        totalApplications = 0;
    }
}
