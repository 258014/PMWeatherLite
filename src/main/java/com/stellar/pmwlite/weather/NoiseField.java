package com.stellar.pmwlite.weather;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import net.minecraft.world.phys.Vec3;

/**
 * Ported from PMWeather ThermodynamicEngine / WindEngine FBM core.
 * Fractal Brownian Motion over a 3D Simplex noise field. Platform-independent math.
 */
public final class NoiseField {
    private final SimplexNoise noise;

    public NoiseField(long seed) {
        this.noise = new SimplexNoise(new net.minecraft.world.level.levelgen.LegacyRandomSource(seed));
    }

    /** Raw simplex sample in [-1,1]. */
    public double sample(double x, double y, double z) {
        return noise.getValue(x, y, z);
    }

    /** Fractal Brownian Motion over a Vec3 sample point (delegates, kept for API parity). */
    public double fbm(Vec3 pos, int octaves, float lacunarity, float gain, float amplitude) {
        return fbm(pos.x, pos.y, pos.z, octaves, lacunarity, gain, amplitude);
    }

    /**
     * Fractal Brownian Motion: sum octaves with lacunarity/gain decay.
     *
     * OPTIMIZATION v4: this overload used to do {@code new Vec3(x, y, z)} only to read the
     * components back -- one throw-away allocation per FBM call (7+ per storm per tick,
     * 65 per sounding). It now runs the octave loop directly on the primitives.
     * The arithmetic and octave order are unchanged.
     */
    public double fbm(double x, double y, double z, int octaves, float lacunarity, float gain, float amplitude) {
        double y0 = 0;
        float amp = amplitude;
        int oct = Math.max(octaves, 1);
        for (int i = 0; i < oct; i++) {
            y0 += amp * noise.getValue(x, y, z);
            x *= lacunarity; y *= lacunarity; z *= lacunarity;
            amp *= gain;
        }
        return y0;
    }

    /** Temperature field scale constants, from PMWeather ThermodynamicEngine. */
    public static final float XZ_SCALE = 15000f;
    public static final float Y_SCALE = 2000f;
    public static final float TIME_SCALE = 20000f;

    /** Diurnal temperature factor: sin(x + sin(x)/-2) with x=(daytime-0.18)*2pi. */
    public static float diurnalFactor(long dayTime) {
        float daytime = dayTime / 24000f;
        double x = (daytime - 0.18) * Math.PI * 2;
        return (float) Math.sin(x + (Math.sin(x) / -2));
    }

    /** Lerp/clamp helpers mirrored from Mth for algorithmic parity. */
    public static float clamp(float v, float min, float max) { return Mth.clamp(v, min, max); }
    public static float lerp(float a, float b, float t) { return Mth.lerp(t, a, b); }
}
