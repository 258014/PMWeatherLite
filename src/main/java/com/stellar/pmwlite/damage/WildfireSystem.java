package com.stellar.pmwlite.damage;

import com.stellar.pmwlite.weather.Storm;
import com.stellar.pmwlite.weather.StormManager;
import com.stellar.pmwlite.weather.WindField;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Wind-driven wildfire spread, ported from PMWeather WindEngine fire-intensity logic:
 * fire jumps downwind proportional to wind speed; fire whirls can ignite new columns.
 * Uses vanilla fire blocks + particles so shader packs render/light them natively.
 */
public final class WildfireSystem {
    private int tick = 0;

    public void tick(ServerLevel level, StormManager manager) {
        if (++tick % 20 != 0) return; // once per second
        for (Storm s : manager.getStorms()) {
            if (s.stormType != Storm.StormType.FIRE_WHIRL) continue;
            spreadFromWhirl(level, s, manager);
        }
        // Wind also accelerates existing fires downwind
        if (level.getGameTime() % 40 == 0) {
            pushFiresDownwind(level, manager);
        }
    }

    private void spreadFromWhirl(ServerLevel level, Storm s, StormManager manager) {
        Vec3 c = s.position;
        int cx = (int) c.x, cz = (int) c.z;
        int groundY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, cx, cz);
        int radius = Math.max((int) (s.width * 0.8f), 3);

        for (int i = 0; i < 3; i++) {
            int dx = level.random.nextInt(-radius, radius + 1);
            int dz = level.random.nextInt(-radius, radius + 1);
            BlockPos p = new BlockPos(cx + dx, groundY, cz + dz);
            BlockState target = level.getBlockState(p.above());
            BlockState support = level.getBlockState(p);
            if (target.isAir() && support.isSolid()
                    && net.minecraft.world.level.block.Blocks.FIRE.defaultBlockState().canSurvive(level, p.above())) {
                level.setBlockAndUpdate(p.above(), net.minecraft.world.level.block.Blocks.FIRE.defaultBlockState());
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.FLAME,
                        p.getX() + 0.5, p.getY() + 1.2, p.getZ() + 0.5, 3, 0, 0.2, 0, 0.02);
            }
        }
    }

    /**
     * Wind-driven spread for burning blocks near players.
     *
     * OPTIMIZATION v4: this used to build a fresh ArrayList of every burning block (per
     * 2 s, scanning 81 columns per player) before iterating it. It now walks the columns
     * and ignites inline -- no list allocation, no second pass -- and is capped so a large
     * fire cannot turn into an unbounded per-tick cost.
     */
    private void pushFiresDownwind(ServerLevel level, StormManager manager) {
        int budget = 128;
        for (var player : level.players()) {
            BlockPos center = player.blockPosition();
            for (int dx = -16; dx <= 16; dx += 4) {
                for (int dz = -16; dz <= 16; dz += 4) {
                    BlockPos p = center.offset(dx, 0, dz);
                    if (!(level.getBlockState(p).getBlock() instanceof BaseFireBlock)) continue;
                    pushFireDownwind(level, p, manager);
                    if (--budget <= 0) return;
                }
            }
        }
    }

    private void pushFireDownwind(ServerLevel level, BlockPos firePos, StormManager manager) {
        Vec3 wind = manager.getWind().getWind(Vec3.atCenterOf(firePos), level.getGameTime(), manager.getStorms());
        float speed = (float) wind.length();
        if (speed < 8f) return;
        Vec3 dir = wind.normalize();
        // Ignite a block 1-3 cells downwind if flammable
        int steps = 1 + level.random.nextInt(3);
        BlockPos target = firePos.offset((int) Math.round(dir.x * steps), 0, (int) Math.round(dir.z * steps));
        BlockState ground = level.getBlockState(target);
        BlockState above = level.getBlockState(target.above());
        boolean flammable = ground.is(BlockTags.LOGS) || ground.is(BlockTags.LEAVES)
                || ground.is(BlockTags.PLANKS);
        if (above.isAir() && ground.isSolid() && flammable) {
            if (level.random.nextFloat() < MthClamp(speed / 40f)) {
                level.setBlockAndUpdate(target.above(), net.minecraft.world.level.block.Blocks.FIRE.defaultBlockState());
            }
        }
    }

    private float MthClamp(float v) { return Math.max(0, Math.min(1, v)); }
}
