package com.stellar.pmwlite.client;

import com.stellar.pmwlite.PMWLite;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-only commands, registered on the client dispatcher so they never reach the server --
 * which means they also work on a server that does not have the mod installed.
 *
 * They exist because the post pipeline and the overlay are *client* preferences: two players on
 * the same server must be able to make different choices, and a server-side config cannot express
 * that. {@code /pmwlite} itself stays a server command (permission level 2) exactly as before.
 */
@Mod.EventBusSubscriber(modid = PMWLite.MOD_ID, value = Dist.CLIENT)
public final class ClientCommands {
    private static boolean registered = false;
    private static boolean complained = false;

    private ClientCommands() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (registered) return;
        try {
            event.getDispatcher().register(Commands.literal("pmwlite")
                    .then(Commands.literal("fx")
                            .executes(ctx -> togglePost(ctx.getSource()))
                            .then(Commands.literal("on").executes(ctx -> setPost(ctx.getSource(), true)))
                            .then(Commands.literal("off").executes(ctx -> setPost(ctx.getSource(), false)))
                            .then(Commands.literal("status").executes(ctx -> status(ctx.getSource()))))
                    .then(Commands.literal("hud")
                            .executes(ctx -> toggleHud(ctx.getSource()))
                            .then(Commands.literal("on").executes(ctx -> setHud(ctx.getSource(), true)))
                            .then(Commands.literal("off").executes(ctx -> setHud(ctx.getSource(), false)))));
            registered = true;
        } catch (Throwable t) {
            // A conflicting command tree must never break login; degrade to "not available".
            if (!complained) {
                complained = true;
                PMWLite.LOGGER.debug("[PMWLite] 客户端命令注册失败: {}", t.toString());
            }
        }
    }

    private static int togglePost(CommandSourceStack src) {
        boolean on = ClientVisualState.togglePostEffects();
        reply(src, (on ? "§a已开启" : "§c已关闭") + " §7后处理管线 §8(" + ShaderCompatLayer.describe() + "§8)");
        return on ? 1 : 0;
    }

    private static int setPost(CommandSourceStack src, boolean on) {
        ClientVisualState.setPostEffectsEnabled(on);
        reply(src, (on ? "§a已开启" : "§c已关闭") + " §7后处理管线");
        return on ? 1 : 0;
    }

    private static int toggleHud(CommandSourceStack src) {
        boolean on = ClientVisualState.toggleOverlay();
        reply(src, (on ? "§a已开启" : "§c已关闭") + " §7屏幕叠加层（雨幕/暗角/闪电）");
        return on ? 1 : 0;
    }

    private static int setHud(CommandSourceStack src, boolean on) {
        ClientVisualState.setOverlayEnabled(on);
        reply(src, (on ? "§a已开启" : "§c已关闭") + " §7屏幕叠加层");
        return on ? 1 : 0;
    }

    private static int status(CommandSourceStack src) {
        reply(src, "§6[PMWLite] 渲染兼容层: §f" + ShaderCompatLayer.describe()
                + "\n§7后处理: " + PostEffectPipeline.status()
                + "\n§7叠加层: " + (ClientVisualState.overlayEnabled() ? "§a开" : "§c关")
                + "\n§7当前强度: §f" + Math.round(ClientVisualState.intensity() * 100f) + "%");
        return 1;
    }

    private static void reply(CommandSourceStack src, String text) {
        src.sendSuccess(() -> Component.literal(text), false);
    }
}
