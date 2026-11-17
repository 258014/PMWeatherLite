package com.stellar.pmwlite.client;

import com.stellar.pmwlite.PMWLite;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * All client-side render/input hooks, on the Forge event bus.
 *
 * Order of a frame:
 *   ViewportEvent (fog)  ->  RenderLevelStageEvent.AFTER_LEVEL (post chain)  ->  RenderGuiEvent.Post (overlay)
 * which is exactly the order a post-processing stack composites in, and the reason each layer can
 * fall back independently.
 */
@Mod.EventBusSubscriber(modid = PMWLite.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        ShaderCompatLayer.tick();
        ClientVisualState.tick();

        Minecraft mc = Minecraft.getInstance();
        while (ClientSetup.RADAR_KEY.consumeClick()) {
            if (mc.screen == null && mc.level != null && mc.player != null) {
                mc.setScreen(new RadarScreen());
            }
        }
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        AtmosphereLayer.applyFog(event);
    }

    @SubscribeEvent
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        AtmosphereLayer.applyFogColor(event);
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        PostEffectPipeline.render(event.getPartialTick());
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        StormOverlayLayer.render(event.getGuiGraphics(), event.getPartialTick());
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientRadarCache.clear();
    }
}
