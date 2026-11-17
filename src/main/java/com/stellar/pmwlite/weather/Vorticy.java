package com.stellar.pmwlite.weather;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Ported near-verbatim from PMWeather Vorticy.java (82 lines, platform-independent).
 * A sub-vortex orbiting a storm, used to model multi-vortex tornadoes.
 */
public class Vorticy {
    public float windspeedMult = 0f;
    public final float maxWindspeedMult;
    public final float widthPerc;
    public final float distancePerc;
    public float angle;
    public int lifetime;
    public int tickCount;
    public boolean dead = false;

    private final Storm storm;

    public Vorticy(Storm storm, float maxWindspeedMult, float widthPerc, float distancePerc, int lifetime) {
        this.storm = storm;
        this.maxWindspeedMult = maxWindspeedMult;
        this.distancePerc = distancePerc;
        this.widthPerc = widthPerc;
        this.lifetime = lifetime;
        this.angle = (float) (Math.random() * Mth.TWO_PI);
    }

    public void tick() {
        if (dead) return;
        tickCount++;
        float lifeDelta = (float) tickCount / lifetime;
        float wind = storm.windspeed * (1 - distancePerc);
        float angleAdd = (float) Math.toRadians(wind / 300f);
        // ramp up in first half of life, ramp down in second half
        if (lifeDelta > 0.5f) {
            windspeedMult = Mth.lerp((lifeDelta - 0.5f) * 2f, maxWindspeedMult, 0f);
        } else {
            windspeedMult = Mth.lerp(lifeDelta * 2f, 0f, maxWindspeedMult);
        }
        if (tickCount > lifetime) dead = true;
        if (storm.isCyclone()) angleAdd *= 0.1f;
        angle += angleAdd;
        if (angle > Mth.TWO_PI) angle = 0f;
    }

    public float getWidth() {
        return widthPerc * storm.width;
    }

    public float getDistance() {
        return distancePerc * storm.width;
    }

    public Vec3 getPosition() {
        float realDist = getDistance();
        return storm.position.add(new Vec3(Math.sin(angle) * realDist, 0, Math.cos(angle) * realDist));
    }
}
