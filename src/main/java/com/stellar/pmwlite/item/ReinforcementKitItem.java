package com.stellar.pmwlite.item;

import com.stellar.pmwlite.fortify.FortificationManager;
import com.stellar.pmwlite.registry.ModRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 加固套件 / Reinforcement Kit — turns forecast lead time into actual damage reduction.
 *
 * Right-click any block to raise its wind threshold by {@link FortificationManager#STEP}
 * (max {@link FortificationManager#MAX_BONUS}). One durability per application.
 *
 * The block keeps its original state and drops; the bonus lives in {@link FortificationManager},
 * so this works on any block from any mod without a block-state change or NBT migration.
 */
public class ReinforcementKitItem extends Item {

    public ReinforcementKitItem(Properties properties) {
        super(properties.durability(64));
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel sl)) return InteractionResult.PASS;

        BlockState state = sl.getBlockState(pos);
        if (state.isAir()) return InteractionResult.PASS;

        FortificationManager fg = FortificationManager.of(sl);
        int before = fg.bonusAt(pos);
        int after = fg.fortify(pos);

        if (after < 0) {
            if (ctx.getPlayer() instanceof ServerPlayer sp) {
                sp.sendSystemMessage(Component.literal(before >= FortificationManager.MAX_BONUS
                        ? "§e[加固] 该方块已达上限 §7(+" + before + ")"
                        : "§c[加固] 本维度加固数量已达上限 §7(" + FortificationManager.MAX_ENTRIES + ")"));
            }
            return InteractionResult.CONSUME;
        }

        if (ctx.getPlayer() instanceof ServerPlayer sp) {
            sp.sendSystemMessage(Component.literal("§a[加固] §7抗风阈值 §f"
                    + before + " §7→ §f" + after
                    + " §8(上限 +" + FortificationManager.MAX_BONUS + ")"));
        }
        sl.playSound(null, pos, ModRegistry.FORTIFY_APPLY.get(), SoundSource.BLOCKS, 1.0f, 0.9f);

        if (ctx.getPlayer() != null) {
            ctx.getItemInHand().hurtAndBreak(1, ctx.getPlayer(),
                    p -> p.broadcastBreakEvent(ctx.getHand() == null ? InteractionHand.MAIN_HAND : ctx.getHand()));
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("右键方块：提升抗风等级").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("每级 +" + FortificationManager.STEP
                + "，上限 +" + FortificationManager.MAX_BONUS).withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("配合气象站预警可在风暴前完成加固").withStyle(ChatFormatting.DARK_GRAY));
    }
}
