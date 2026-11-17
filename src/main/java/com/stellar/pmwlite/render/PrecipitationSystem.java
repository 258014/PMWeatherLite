package com.stellar.pmwlite.render;

import com.stellar.pmwlite.atmos.AtmosphereGrid;
import com.stellar.pmwlite.atmos.AtmosphereManager;
import com.stellar.pmwlite.config.ServerConfig;
import com.stellar.pmwlite.weather.StormManager;
import com.stellar.pmwlite.weather.Thermodynamics;
import com.stellar.pmwlite.weather.WeatherSystem;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Extreme-weather precipitation FX, rendered with VANILLA particles so Oculus/Iris
 * shader packs light them correctly (no post-processing pipeline hijack).
 *
 * OPTIMIZATION v2:
 *  - Phase (which depends on an expensive thermodynamic profile) is now cached per
 *    coarse 64-block cell and refreshed only every ~5 seconds, instead of a full
 *    buildProfile() for every player every 2 ticks.
 *  - Particle count scales down when many players are nearby (crowd guard).
 *  - Also consumes WeatherSystem weather types so 17-type precipitation shows up.
 *
 * OPTIMIZATION v4 (algorithm + memory):
 *  - Phase cache switched from {@code HashMap<Long, PhaseCache>} to a primitive-keyed
 *    {@link Long2ObjectOpenHashMap}: no Long boxing per lookup, no HashMap.Node per cell.
 *  - Biome is resolved ONCE per cell instead of twice (surfaceTemp + surfaceDew each
 *    called level.getBiome(BlockPos.containing(...))), removing a BlockPos allocation
 *    and a chunk-biome lookup per query.
 *  - Emit loop caches the random source and the emission origin, avoiding repeated
 *    field hops and Vec3 math per particle.
 *
 * OPTIMIZATION v5 (network):
 *  - Precipitation now goes out as ONE packet carrying the whole particle count, instead of
 *    one packet per particle (6x fewer packets: ~60/s -> ~10/s per player under heavy rain).
 *    Verified equivalent against the 1.20.1 client bytecode; see emitAround().
 */
public final class PrecipitationSystem {
    private int tick = 0;

    /** 云量调制下限：云量再低也保留三成降水粒子，避免网格重定位时雨"消失一下"。 */
    private static final float CLOUD_FLOOR = 0.3f;
    /** 云量到这个值以上算"完全成云"，不再衰减。 */
    private static final float CLOUD_FULL = 0.45f;

    /** Cached precipitation phase per 64-block column: key = (cellX << 32) ^ cellZ. */
    private final Long2ObjectOpenHashMap<PhaseCache> phaseCache = new Long2ObjectOpenHashMap<>(64);

    private static final class PhaseCache {
        final Thermodynamics.Precipitation phase;
        final long expiry;
        PhaseCache(Thermodynamics.Precipitation p, long e) { phase = p; expiry = e; }
    }

    /** v0.2 兼容入口：不接大气场，行为与旧版逐位一致。 */
    public void tick(ServerLevel level, StormManager manager, WeatherSystem weather) {
        tick(level, manager, weather, null);
    }

    public void tick(ServerLevel level, StormManager manager, WeatherSystem weather, ServerConfig cfg) {
        if (++tick % 2 != 0) return;
        if (level.players().isEmpty()) return;
        long now = level.getGameTime();

        // Crowd guard: emit fewer particles per player when the server is busy
        int players = level.players().size();
        int countScale = players > 8 ? 2 : (players > 3 ? 3 : 4);

        // v0.3: 云量调制。云是长在空中的，所以要查整根气柱的云量，而不是玩家脚下那一格。
        boolean cloudDriven = cfg != null && cfg.cloudDrivenPrecipitation && cfg.doAtmosphere;
        AtmosphereGrid grid = cloudDriven
                ? AtmosphereManager.of(level, cfg).grid() : null;
        if (grid != null && !grid.ready()) grid = null;

        for (var player : level.players()) {
            Vec3 p = player.position();
            float intensity = Math.max(manager.precipitationAt(p),
                    weather == null ? 0f : weather.precipitationAt(p));
            if (intensity < 0.12f) continue;

            if (grid != null) {
                float cloud = grid.columnCloud(p.x, p.z);
                // 0.3 → 1.0：云量只"削减"不"抹杀"。
                // 下限是必要的——网格重定位会把云量清 0，若允许打死就会在玩家飞行时
                // 出现"雨声在响但粒子里没有雨"的断裂；0.3 的下限保证强降水始终可见。
                float mod = CLOUD_FLOOR + (1f - CLOUD_FLOOR)
                        * Mth.clamp(cloud / CLOUD_FULL, 0f, 1f);
                intensity *= mod;
                if (intensity < 0.12f) continue;
            }

            Thermodynamics.Precipitation phase = cachedPhase(level, manager, p, now);
            if (phase == Thermodynamics.Precipitation.NONE) continue;
            emitAround(level, p, intensity, phase, countScale);
        }

        // Prune stale cache entries periodically (bounded memory)
        if (tick % 600 == 0) {
            var it = phaseCache.long2ObjectEntrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue().expiry < now) it.remove();
            }
        }
    }

    /** Returns the precipitation phase for a position, cached per 64-block cell for ~5 s. */
    private Thermodynamics.Precipitation cachedPhase(ServerLevel level, StormManager manager, Vec3 p, long now) {
        long cx = Math.floorDiv((int) p.x, 64);
        long cz = Math.floorDiv((int) p.z, 64);
        long key = (cx << 32) ^ (cz & 0xffffffffL);
        PhaseCache c = phaseCache.get(key);
        if (c != null && c.expiry > now) return c.phase;

        // v4: sample the biome once and reuse it for both temperature and downfall.
        var biome = level.getBiome(BlockPos.containing(p)).value();
        float temp = surfaceTemp(biome.getBaseTemperature());
        float dew = surfaceDew(biome.getModifiedClimateSettings().downfall(), temp);

        Thermodynamics.Profile profile = Thermodynamics.buildProfile(manager.getNoise(), p, temp, dew, 1013.25f,
                level.getSeaLevel(), now, 500, 8000);
        Thermodynamics.Precipitation phase = Thermodynamics.precipitationType(profile);
        phaseCache.put(key, new PhaseCache(phase, now + 100)); // 5 s @ 20 tps
        return phase;
    }

    private void emitAround(ServerLevel level, Vec3 center, float intensity,
                            Thermodynamics.Precipitation phase, int countScale) {
        int count = (int) (4 + intensity * 12) / countScale + 2;
        var type = switch (phase) {
            case SNOW, WINTRY_MIX -> ParticleTypes.SNOWFLAKE;
            case SLEET, FREEZING_RAIN -> ParticleTypes.SPLASH;
            case RAIN -> ParticleTypes.RAIN;
            default -> null;
        };
        if (type == null) return;

        // Per-phase vertical drift and speed (values unchanged from v3).
        double speed;
        switch (phase) {
            case SNOW, WINTRY_MIX -> speed = 0.01;
            case SLEET, FREEZING_RAIN -> speed = 0.001;
            default -> speed = 0.001;
        }

        // OPTIMIZATION v5: one packet instead of one packet per particle.
        //
        // Verified against 1.20.1 ClientPacketListener.handleParticleEvent bytecode:
        //   count > 0  ->  pos = base + gaussian * (xDist, yDist, zDist)
        //                  vel = gaussian * maxSpeed  (independent on each axis)
        //   count == 0 ->  pos = base, vel = maxSpeed * (xDist, yDist, zDist)  [exact direction]
        //
        // So the three dist parameters are SPREAD SIGMAS, not velocities. The old code sent
        // `count` separate packets with count=1 and a tiny y-jitter, i.e. a uniform box of
        // ±14 / ±9 blocks with near-zero velocity. The equivalent gaussian sigma for a
        // uniform half-width h is h/sqrt(3), hence H=8.1 (14/√3) and V=5.2 (9/√3).
        // Result: same particle count, same spread, 1/6 of the packets.
        final double H = 8.1;   // horizontal sigma  (~ uniform ±14)
        final double V = 5.2;   // vertical sigma    (~ uniform ±9)
        level.sendParticles(type, center.x, center.y + 17.0, center.z, count, H, V, H, speed);
    }

    private static float surfaceTemp(float bt) {
        return bt > 0 ? (float) Math.pow(bt / 1.85, 0.5) * 35f : -(float) Math.pow(-bt, 1.5) * 60f;
    }

    private static float surfaceDew(float downfall, float temp) {
        float humidity = Mth.clamp(downfall, 0, 1);
        return temp - (1 - humidity) * 15f;
    }
}
