package com.stellar.pmwlite.weather;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Ported from PMWeather ThermodynamicEngine + Sounding (the atmospheric-science core).
 * Computes a vertical temperature/dewpoint/pressure profile, CAPE/CINH instability,
 * lapse rate and precipitation type. This makes storm formation thermodynamically driven
 * instead of random -- the "algorithm layer" enhancement.
 *
 * All rendering is excluded on purpose: shaders draw the clouds.
 *
 * ===== OPTIMIZATION v4 (algorithm + allocation) =====
 *  1. Profile is now a primitive-array struct ({@link Profile}) instead of
 *     {@code TreeMap<Integer, AirParcel>}. One sounding used to allocate 1 TreeMap +
 *     N Integer keys + N records + N tree nodes (~5.7 KB / 65 boxed layers, 65 autobox
 *     ops per call). Now it allocates 5 compact arrays and zero boxed values.
 *  2. {@code lrNoise} and {@code columnNoise} were sampled with IDENTICAL arguments
 *     (same x/z scale, same octaves/lacunarity/gain) -- one redundant FBM is removed.
 *  3. instability() no longer builds {@code entrySet().stream().toList()} just to read
 *     the layer spacing (that allocated a full ArrayList copy per call).
 *  4. Parcel-lift invariants (surface tK/pK ratio, dewpoint-depression moist bonus) are
 *     hoisted out of the layer loop: 2 divisions + 1 compare saved per layer.
 *     Numerically identical results (same float operation order preserved).
 */
public final class Thermodynamics {
    public static final float G = 9.81f;
    public static final float R_D = 287.05f;
    public static final float MOLAR_MASS = 0.02896968f;
    public static final float R_UNIVERSAL = 8.31432f;

    /** Poisson exponent for the dry-adiabatic lift; constant, so evaluated once per class. */
    private static final float POISSON_EXP = R_D / 1005f;

    public static float celsiusToKelvin(float c) { return c + 273.15f; }

    /** Diurnal surface warming peak in early afternoon (source: diurnalFactor * 5). */
    public static float diurnalBonus(long dayTime) {
        float daytime = dayTime / 24000f;
        double x = (daytime - 0.18) * Math.PI * 2;
        return (float) Math.sin(x + (Math.sin(x) / -2)) * 5f + 5f;
    }

    /** Hypsometric / barometric pressure at altitude (ported verbatim). */
    public static float pressureAtHeight(float altitude, float tempC, float refAltitude, float refPressure) {
        return refPressure * (float) Math.exp(-((G * MOLAR_MASS * (altitude - refAltitude))
                / (R_UNIVERSAL * celsiusToKelvin(tempC))));
    }

    /** Virtual temperature from temperature and dewpoint (Bolton approximation). */
    public static float virtualTemperature(float tempC, float dewC, float pressure) {
        float e = vaporPressure(dewC);
        float tK = celsiusToKelvin(tempC);
        return (tK / (1f - (e / pressure) * 0.378f)) - 273.15f;
    }

    /** Magnus formula: saturation vapour pressure (hPa) from dewpoint. */
    public static float vaporPressure(float dewC) {
        return 6.112f * (float) Math.exp((17.67f * dewC) / (dewC + 243.5f));
    }

    /** Legacy view of a single layer; kept for API compatibility, no longer used internally. */
    public record AirParcel(float temperature, float dewpoint, float pressure, float virtualTemperature) {}

    /**
     * Compact vertical sounding: one flat array per quantity, indexed by layer.
     * Layers are evenly spaced (alt = 0, step, 2*step, ...), so the old
     * {@code TreeMap.floorEntry()} lookups become a single integer division.
     *
     * Space: 4 * N + 4 * N bytes + object header  vs  ~88 bytes * N for the boxed TreeMap.
     */
    public static final class Profile {
        private final int step;
        private final int n;
        private final int[] heights;
        private final float[] temp;
        private final float[] dew;
        private final float[] press;
        private final float[] vtemp;

        private Profile(int step, int n) {
            this.step = step;
            this.n = n;
            this.heights = new int[n];
            this.temp = new float[n];
            this.dew = new float[n];
            this.press = new float[n];
            this.vtemp = new float[n];
        }

        public int size() { return n; }
        public boolean isEmpty() { return n == 0; }
        /** Uniform spacing between layers, in metres. */
        public int step() { return step; }
        public int heightAt(int i) { return heights[i]; }
        public float temperature(int i) { return temp[i]; }
        public float dewpoint(int i) { return dew[i]; }
        public float pressure(int i) { return press[i]; }
        public float virtualTemperature(int i) { return vtemp[i]; }

        /** Index of the layer at or below {@code heightM}; clamped to the profile ends. */
        public int indexAtOrBelow(int heightM) {
            if (n == 0) return -1;
            int i = heightM / step;
            if (i < 0) return 0;
            return i < n ? i : n - 1;
        }
    }

    /**
     * Build a vertical atmospheric profile over a surface parcel.
     * Ported structure from ThermodynamicEngine.samplePoint(): lapse rate with humidity,
     * low-level moisture bump, capping inversion at 12-16 km, FBM temperature noise.
     */
    public static Profile buildProfile(
            NoiseField noise, Vec3 pos, float sfcTemp, float sfcDew, float sfcPressure,
            int groundY, long time, int res, int height) {

        int step = Math.max(res, 1);           // guard: res<=0 used to loop forever
        int n = height / step + 1;
        Profile profile = new Profile(step, n);

        float humidity = Mth.clamp((sfcTemp - sfcDew) / 20f, 0f, 1f);
        humidity = 1f - humidity; // rough RH proxy

        float uhumidity = Mth.clamp(humidity, 0f, 1f);

        // OPTIMIZATION v2: hoist all height-invariant noise out of the altitude loop.
        // OPTIMIZATION v4: lrNoise and columnNoise used the exact same sample point --
        // one FBM call (2 simplex octaves) per sounding is now saved.
        double columnNoise = noise.fbm(pos.x / 15000.0, 0, pos.z / 15000.0, 2, 2f, 0.5f, 1f);
        double lrNoise = columnNoise > 0 ? Math.pow(columnNoise, 1.25) * 2 : columnNoise;
        float lapseRate = 5.5f + (float) lrNoise;
        lapseRate *= 0.4f + (1f - uhumidity);

        float dewRatio = Mth.lerp((float) Mth.clamp((lrNoise + 1) / 2, 0, 1),
                Mth.lerp(uhumidity, 0.4f, 0.1f), Mth.lerp(uhumidity, 0.65f, 0.3f));

        float bumpH = groundY + Mth.clamp((float) columnNoise + 0.5f, 0.5f, 1.5f) * 1250;
        float bumpStrength = Mth.clamp((float) columnNoise + 0.5f, 0f, 1.5f) * 5.5f * Mth.clamp(1f - uhumidity, 0, 1) - 4f * uhumidity;
        float inversionHeight = Mth.lerp(Mth.clamp((float) columnNoise, 0f, 1f), 12000, 16000);
        double invScale = 1.0 / 1500.0;

        final double nx = pos.x / 15000.0;
        final double nz = pos.z / 15000.0;

        int i = 0;
        for (int alt = 0; alt <= height && i < n; alt += step, i++) {
            float altKm = alt / 1000f;
            float t = sfcTemp - lapseRate * altKm;
            float dp = sfcDew - lapseRate * altKm * dewRatio;

            // low-level moisture bump (boundary layer) -- uses precomputed values
            if (alt > bumpH) {
                float k = Mth.clamp((alt - bumpH) / 150f, 0f, 1f);
                t += Mth.lerp(k, 0, bumpStrength);
                dp -= Mth.lerp(k, 0, bumpStrength);
            }

            // capping inversion -- uses precomputed values
            if (alt > inversionHeight) {
                float dif = alt - inversionHeight;
                float k = Mth.clamp((float) (dif * invScale), 0f, 1f);
                float inv = lapseRate * (dif / 1000f);
                t += Mth.lerp(k, 0, inv);
                dp += Mth.lerp(k, 0, inv * dewRatio);
            }

            // 3D temperature noise (the only genuinely altitude-dependent noise)
            double tNoise = noise.fbm(nx, alt / 2000.0, nz, 4, 2f, 0.5f, 1f) * 1.5;
            t += (float) tNoise;
            dp -= (float) tNoise * 1.5f;
            dp = Math.min(dp, t);

            float p = pressureAtHeight(alt, t, 0, sfcPressure);
            profile.heights[i] = alt;
            profile.temp[i] = t;
            profile.dew[i] = dp;
            profile.press[i] = p;
            profile.vtemp[i] = virtualTemperature(t, dp, p);
        }
        return profile;
    }

    /**
     * CAPE (Convective Available Potential Energy) and CINH (Convective Inhibition),
     * ported from Sounding.getCAPE(): integrate buoyancy of a lifted surface parcel.
     * High CAPE + low CINH = strong storm / tornado potential.
     *
     * OPTIMIZATION v4: array walk instead of TreeMap entrySet; the previous implementation
     * copied the whole entry set into a List only to read the layer spacing.
     */
    public static Instability instability(Profile profile) {
        int n = profile.size();
        if (n < 2) return new Instability(0, 0, 0);

        int delta = profile.step();
        float sfcT = profile.temperature(0);
        float sfcP = profile.pressure(0);

        // Hoisted out of the loop: these depend only on the surface parcel.
        float liftCoeff = celsiusToKelvin(sfcT) / celsiusToKelvin(sfcP);
        float dewDepression = sfcT - profile.dewpoint(0);
        boolean hasMoistBonus = dewDepression < 6f;
        float moistBonus = hasMoistBonus ? (6f - dewDepression) * 0.12f : 0f;

        float cape = 0, cinh = 0, cape3 = 0;

        for (int i = 0; i < n; i++) {
            int h = profile.heightAt(i);
            float envP = profile.pressure(i);
            // lifted parcel: dry adiabat below LCL, moist above (simplified Bolton)
            float parcelT = liftCoeff * (float) Math.pow(envP / sfcP, POISSON_EXP) - 273.15f;
            if (hasMoistBonus) parcelT += moistBonus;

            float envK = celsiusToKelvin(profile.virtualTemperature(i));
            float pK = celsiusToKelvin(parcelT);
            float buoyancy = ((pK - envK) / envK) * G; // m/s^2
            float layer = buoyancy * delta;
            cape += Math.max(layer, 0f);
            cinh += Math.min(layer, 0f);
            if (h <= 3000 && envP < 850 && layer > 0) cape3 += layer;
        }
        return new Instability(cape, cinh * 0.75f, cape3);
    }

    /** Environmental lapse rate between two heights (°C per km). */
    public static float lapseRate(Profile profile, int lower, int upper) {
        if (profile.isEmpty()) return 0f;
        float a = profile.temperature(profile.indexAtOrBelow(lower));
        float b = profile.temperature(profile.indexAtOrBelow(upper));
        return (a - b) / ((upper - lower) / 1000f);
    }

    public enum Precipitation { RAIN, SNOW, SLEET, FREEZING_RAIN, WINTRY_MIX, NONE }

    /** Precipitation phase by sampling the vertical profile (ported from getPrecipitationType). */
    public static Precipitation precipitationType(Profile profile) {
        int n = profile.size();
        if (n == 0) return Precipitation.NONE;
        float ground = profile.temperature(0);
        Precipitation precip = Precipitation.SNOW;
        for (int i = 0; i < n; i++) {
            float t = profile.temperature(i);
            if (t < 3f && t > -1f) precip = Precipitation.WINTRY_MIX;
            else if (t <= 0f) precip = (precip == Precipitation.RAIN || precip == Precipitation.WINTRY_MIX)
                    ? Precipitation.SLEET : precip;
            else precip = (precip == Precipitation.SNOW || precip == Precipitation.SLEET
                    || precip == Precipitation.WINTRY_MIX) ? Precipitation.RAIN : precip;
        }
        if ((precip == Precipitation.RAIN || precip == Precipitation.WINTRY_MIX) && ground <= 0f)
            return Precipitation.FREEZING_RAIN;
        return precip;
    }

    /**
     * Storm-formation risk combining CAPE/CINH, lapse rate, low-level dewpoint and SRH.
     * Ported from Sounding.getRisk() + RiskEngine.getRisk().
     */
    public static float formationRisk(Profile profile, NoiseField noise,
                                      Vec3 pos, long dayTime, float riskCurve) {
        Instability inst = instability(profile);
        float risk = 1f;
        risk *= Mth.clamp(inst.cape() / 2000f, 0, 2f);
        risk *= Mth.clamp((inst.cape3() - 25f) / 50f, 0, 1.25f);
        float lr = lapseRate(profile, 0, 3000);
        risk *= Mth.clamp((lr - 5f) / 1.5f, 0.75f, 1.25f);
        risk *= 1f - Mth.clamp(inst.cinh() / -500f, 0, 1);
        float dew = profile.isEmpty() ? 0 : profile.dewpoint(0);
        risk *= Mth.clamp((dew - 7f) / 11f, 0.15f, 1.25f);
        // Storm Relative Helicity proxy from noise field
        float srh = (float) ((noise.sample(pos.x / 5000f, dayTime / 15000f, pos.z / 5000f) + 1f) / 2f);
        srh = (float) Math.pow(srh, 1.5f) * 400f;
        risk *= Mth.clamp(srh / 325f, 0, 1.25f);
        risk = Mth.clamp(risk, 0, 1.75f);
        return (float) Math.pow(Mth.clamp(risk / 1.75f, 0, 1), riskCurve + 0.1f) * 1.75f;
    }

    public record Instability(float cape, float cinh, float cape3) {}
}
