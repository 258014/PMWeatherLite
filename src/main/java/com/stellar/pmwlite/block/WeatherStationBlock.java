package com.stellar.pmwlite.block;

import com.stellar.pmwlite.asset.FixedAssetManager;
import com.stellar.pmwlite.asset.IFixedAsset;
import com.stellar.pmwlite.forecast.ForecastEngine;
import com.stellar.pmwlite.registry.ModRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 天气预报机 / Weather Station — the first "fixed asset".
 *
 * Behaviour:
 *  - placeable like any block, survives tornados (windThreshold() &lt; 0),
 *  - registers itself in {@link FixedAssetManager}, so the existing object-management code
 *    (destruction scan, radar, forecast coverage) sees it without any special casing,
 *  - right-click prints a forecast; the station also raises the forecast confidence inside
 *    {@link FixedAssetManager#STATION_COVERAGE} blocks.
 */
public class WeatherStationBlock extends Block implements IFixedAsset {

    public WeatherStationBlock() {
        super(Properties.of()
                .mapColor(MapColor.METAL)
                .strength(3.5f, 6.0f)
                .sound(SoundType.METAL)
                .requiresCorrectToolForDrops());
    }

    @Override
    public String assetId() {
        return "pmwlite:weather_station";
    }

    /** Immune: a weather station must keep reporting during the storm it is warning about. */
    @Override
    public int windThreshold() {
        return -1;
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (level.isClientSide || oldState.getBlock() == this) return;
        if (level instanceof ServerLevel sl) {
            FixedAssetManager.of(sl).register(pos, assetId(), sl.getGameTime());
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!level.isClientSide && newState.getBlock() != this && level instanceof ServerLevel sl) {
            FixedAssetManager.of(sl).unregister(pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel sl) || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.PASS;
        }

        var managers = com.stellar.pmwlite.PMWLite.instance();
        var stormManager = managers == null ? null : managers.stormManager(sl);
        var f = ForecastEngine.forecast(sl, pos, stormManager);

        sp.sendSystemMessage(Component.literal("§6[气象站] §7位置 §f"
                + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()));
        sp.sendSystemMessage(Component.literal(f.colorCode() + f.headline()
                + " §7(风险 " + String.format("%.2f", f.risk()) + " · 置信 " + f.confidence() + "%)"));
        if (f.etaSeconds() >= 0) {
            sp.sendSystemMessage(Component.literal("§7预计 §f" + f.etaSeconds()
                    + " 秒 §7后影响本地"));
        }
        if (!f.expectedTypes().isEmpty()) {
            sp.sendSystemMessage(Component.literal("§7可能类型: §f"
                    + String.join("、", f.expectedTypes())));
        }
        sp.sendSystemMessage(Component.literal("§8提示: 用加固套件右键方块可提升抗风等级"));

        sl.playSound(null, pos, ModRegistry.STATION_SCAN.get(), SoundSource.BLOCKS, 0.8f, 1.2f);
        return InteractionResult.CONSUME;
    }

    /** Station block has no block-state properties, so no codec is required in 1.20.1. */
    @Override
    public String toString() {
        return "WeatherStationBlock";
    }

    /** Convenience for command output / debugging. */
    public static String describe(ServerLevel level, BlockPos pos) {
        var f = ForecastEngine.forecast(level, pos, null);
        return ChatFormatting.GRAY + "risk=" + f.risk() + " alert=" + f.alertLevel();
    }
}
