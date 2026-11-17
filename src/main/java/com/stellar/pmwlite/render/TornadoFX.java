package com.stellar.pmwlite.render;

import com.stellar.pmwlite.weather.Storm;
import com.stellar.pmwlite.weather.StormManager;
import com.stellar.pmwlite.weather.WindField;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Rendering enhancement WITHIN the shader-compatible constraint:
 * spawns debris/dust/spindrift as vanilla particles along the tornado funnel.
 * Particles are geometry drawn through the normal pipeline, so shader packs light and
 * shadow them correctly -- no post-processing pipeline is hijacked.
 */
public final class TornadoFX {
    private int tickCounter = 0;

    /** Called each server tick; emits funnel particles for ground-touching tornadoes. */
    public void tick(ServerLevel level, StormManager manager) {
        if (++tickCounter % 2 != 0) return; // throttle to every 2 ticks
        if (level.players().isEmpty()) return; // boundary: no observers, no FX work
        for (Storm s : manager.getStorms()) {
            if (!s.isTornadoOnGround()) continue;
            // Boundary perf guard: skip funnels nobody can see (128-block view band)
            boolean observed = false;
            for (var player : level.players()) {
                if (player.distanceToSqr(s.position) < 128 * 128) { observed = true; break; }
            }
            if (!observed) continue;
            emitFunnel(level, s, manager);
        }
    }

    private void emitFunnel(ServerLevel level, Storm s, StormManager manager) {
        Vec3 c = s.position;
        int groundY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                (int) c.x, (int) c.z);
        float height = Math.max(s.width * 2.2f, 20f);
        int particles = 6;

        for (int i = 0; i < particles; i++) {
            float t = level.random.nextFloat();
            float y = groundY + t * height;
            double radius = s.width * 0.5f * (0.25f + t * 0.9f); // funnel widens with height
            double ang = level.random.nextDouble() * Math.PI * 2;
            Vec3 p = new Vec3(c.x + Math.cos(ang) * radius, y, c.z + Math.sin(ang) * radius);

            // tangential swirl from the ported Rankine vortex
            Vec3 tangent = WindField.rankineCirculation(c, p, s.smoothWindspeed, Math.max(s.width * 0.5f, 4f));

            // dust at the base, rain/spindrift higher, embers for fire whirls
            var type = s.stormType == Storm.StormType.FIRE_WHIRL
                    ? ParticleTypes.CAMPFIRE_COSY_SMOKE
                    : (t < 0.35f ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE);
            level.sendParticles(type, p.x, p.y, p.z, 1,
                    tangent.x * 0.05, 0.02 + level.random.nextDouble() * 0.08, tangent.z * 0.05, 0.02);
        }

        // ground-level debris cloud
        if (level.random.nextBoolean()) {
            double a = level.random.nextDouble() * Math.PI * 2;
            double rr = level.random.nextDouble() * s.width;
            level.sendParticles(ParticleTypes.CLOUD,
                    c.x + Math.cos(a) * rr, groundY + 0.5, c.z + Math.sin(a) * rr,
                    2, 0.3, 0.15, 0.3, 0.03);
        }
    }
}
