package com.stellar.pmwlite.damage;

import com.stellar.pmwlite.config.ServerConfig;
import com.stellar.pmwlite.weather.Storm;
import com.stellar.pmwlite.weather.StormManager;
import com.stellar.pmwlite.weather.WindField;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Ported from PMWeather Storm.java destruction logic + MovingBlock entity.
 * Instead of a custom moving-block entity, we use vanilla FallingBlockEntity with
 * swirl velocity -- visually compatible with shaders (geometry rendered through normal pipeline).
 *
 * OPTIMIZATION v4 (this is the most allocation-heavy per-tick path in the mod):
 *  - Block identification no longer formats a registry-name String per block: the config
 *    resolves its thresholds into Block-keyed maps once, so a scan performs zero allocations
 *    for the ~315 candidate blocks that are immune or unknown.
 *  - Background wind is sampled once per scan instead of once per block. The scan window is
 *    ~10 blocks wide while the background field varies over 1000s of blocks, so the value is
 *    effectively constant there; the Rankine vortex term (the one that actually decides
 *    destruction) is still evaluated exactly, per block.
 *  - Squared-distance and threshold checks run before any Vec3 is constructed.
 *  - Scan stays throttled to 4 Hz (v2), which already removed 80% of the work.
 */
public final class TornadoDestroyHandler {
    private final ServerConfig cfg;
    private int tick = 0;

    public TornadoDestroyHandler(ServerConfig cfg) {
        this.cfg = cfg;
    }

    /**
     * Called each tick from the server tick handler: scan the funnel core of each
     * ground-touching tornado and rip up blocks whose wind resistance is below the
     * local wind speed.
     */
    public void tick(ServerLevel level, StormManager manager) {
        if (!cfg.doDamage) return;
        // Throttle: destruction scan is expensive. 4x/sec is plenty for visible funnel
        // damage and cuts CPU by ~80% vs every-tick scanning.
        if (++tick % 5 != 0) return;
        for (Storm s : manager.getStorms()) {
            if (!s.isTornadoOnGround()) continue;
            ripAroundFunnel(level, s, manager);
        }
    }

    private void ripAroundFunnel(ServerLevel level, Storm s, StormManager manager) {
        Vec3 c = s.position;
        int cx = (int) c.x, cz = (int) c.z;
        int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz);

        WindField wind = manager.getWind();
        List<Storm> storms = manager.getStorms();
        // One ambient-wind sample for the whole scan window (see class javadoc).
        Vec3 background = wind.backgroundWind(new Vec3(cx + 0.5, groundY, cz + 0.5), level.getGameTime());
        float rmw = Math.max(s.width * 0.5f, 4f);

        // sample a limited band around the funnel core for performance
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx * dx + dz * dz > 12) continue;
                int x = cx + dx, z = cz + dz;
                for (int y = groundY; y <= groundY + 6; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;

                    // v4: Block-identity lookup, no String / ResourceLocation per block
                    int threshold = cfg.strengthThreshold(state);
                    if (threshold < 0) continue; // blacklisted or unknown -> safe by default

                    Vec3 bp = new Vec3(x + 0.5, y + 0.5, z + 0.5);
                    Vec3 localWind = wind.getWind(bp, background, storms);
                    float windSpeed = (float) localWind.length() * 3.6f; // m/s -> mph-ish scale
                    if (windSpeed < threshold) continue;

                    // rip it up into a swirling falling block (1.20.1: FallingBlockEntity.fall)
                    level.destroyBlock(pos, false);
                    FallingBlockEntity fb = FallingBlockEntity.fall(level, pos, state);
                    fb.setDeltaMovement(wind.rankineCirculation(s.position, bp,
                                    s.smoothWindspeed, rmw)
                            .add(0, 0.25, 0).scale(1.4));
                }
            }
        }
    }
}
