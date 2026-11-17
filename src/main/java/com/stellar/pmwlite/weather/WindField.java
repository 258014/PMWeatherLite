package com.stellar.pmwlite.weather;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Ported from PMWeather WindEngine.
 * Background wind from FBM noise + Rankine-vortex storm circulation + tornado swirl.
 * Pure math; Storm references are this mod's own Storm class.
 */
public final class WindField {
    private static final float TIME_SCALE = 20000f;
    private static final float SCALE = 12000f;

    private final NoiseField noise;
    private final float backgroundWindMultiplier;
    private final float seasonWindMod;

    public WindField(NoiseField noise, float backgroundWindMultiplier, float seasonWindMod) {
        this.noise = noise;
        this.backgroundWindMultiplier = backgroundWindMultiplier;
        this.seasonWindMod = seasonWindMod;
    }

    /** Background (ambient) wind at a position and world time, before storm influence. */
    public Vec3 backgroundWind(Vec3 position, long gameTime) {
        double ang = noise.fbm(
                position.x / (SCALE * 3f), position.z / (SCALE * 3f), gameTime / (TIME_SCALE * 6f),
                5, 2f, 0.1f, 1f) * Math.PI;
        // OPTIMIZATION v4: (cos, sin) is already a unit vector -- the previous
        // .normalize() allocated a second Vec3 and computed a sqrt for nothing.
        Vec3 dir = new Vec3(Math.cos(ang), 0, Math.sin(ang));
        double speed = Math.max(noise.sample(-position.z / SCALE, -position.x / SCALE, -gameTime / TIME_SCALE) + 1f, 0) * 12f;
        speed *= backgroundWindMultiplier;
        speed *= seasonWindMod;
        return dir.scale(speed);
    }

    /**
     * Rankine vortex circulation contribution from a storm at a given point.
     * Inside core (r<RMW): solid-body rotation (v proportional to r).
     * Outside core: v proportional to 1/r (potential vortex).
     */
    public static Vec3 rankineCirculation(Vec3 center, Vec3 point, float windspeed, float radiusOfMaxWind) {
        double dx = point.x - center.x;
        double dz = point.z - center.z;
        double r = Math.sqrt(dx * dx + dz * dz);
        if (r < 1e-4) return Vec3.ZERO;
        // tangential unit vector (counterclockwise around Y)
        double tx = -dz / r;
        double tz = dx / r;
        double v;
        if (r < radiusOfMaxWind) {
            v = windspeed * (r / radiusOfMaxWind);
        } else {
            v = windspeed * (radiusOfMaxWind / r);
        }
        return new Vec3(tx * v, 0, tz * v);
    }

    /** Full wind field: background + sum of storm circulations. */
    public Vec3 getWind(Vec3 position, long gameTime, List<Storm> storms) {
        return getWind(position, backgroundWind(position, gameTime), storms);
    }

    /**
     * Same as {@link #getWind(Vec3, long, List)} but with a caller-supplied background wind.
     * Used by the destruction scan, which samples ~300 blocks inside a single funnel window:
     * the background field is smooth at that scale, so one sample per scan is enough and
     * saves a 5-octave FBM per block. The vortex circulation is still computed per block.
     */
    public Vec3 getWind(Vec3 position, Vec3 background, List<Storm> storms) {
        Vec3 wind = background;
        for (Storm s : storms) {
            if (s.dead) continue;
            Vec3 center = s.position;
            double dx = position.x - center.x;
            double dz = position.z - center.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > s.influenceRadius) continue;
            float rmw = Math.max(s.width * 0.5f, 4f);
            Vec3 circ = rankineCirculation(center, position, s.smoothWindspeed, rmw);
            // vertical suction near the funnel core (tornado updraft)
            double coreFalloff = Mth.clamp(1f - (float) (dist / rmw), 0f, 1f);
            double updraft = coreFalloff * s.smoothWindspeed * 0.35f;
            // v4: add(dx,dy,dz) instead of constructing an intermediate Vec3
            wind = wind.add(circ).add(0, updraft, 0);
        }
        return wind;
    }

    /** Vorticity approximation: opposite winds on opposite samples => high swirl. */
    public double getSwirl(Vec3 position, long gameTime, List<Storm> storms, float sampleSize) {
        Vec3 z1 = getWind(position.add(0, 0, sampleSize), gameTime, storms).normalize();
        Vec3 z2 = getWind(position.add(0, 0, -sampleSize), gameTime, storms).normalize();
        Vec3 x1 = getWind(position.add(-sampleSize, 0, 0), gameTime, storms).normalize();
        Vec3 x2 = getWind(position.add(sampleSize, 0, 0), gameTime, storms).normalize();
        double compZ = (-z1.dot(z2) + 1) / 2;
        double compX = (-x1.dot(x2) + 1) / 2;
        return compZ * compX;
    }
}
