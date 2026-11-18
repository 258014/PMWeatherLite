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
 * Order of a frame (v0.4 明确化):
 *   ClientTick (LightingState + ClientVisualState)
 *     -> ViewportEvent.RenderFog / ComputeFogColor (地形 shader 内的雾)
 *     -> RenderLevelStageEvent.AFTER_LEVEL (post chain: haze main→temp, blit temp→main)
 *     -> RenderGuiEvent.Post (2D 光照幕 / 雨幕 / 闪电 / HUD)
 *
 * 这就是后处理栈真正的合成顺序。三层都从同一份 LightingState / ClientVisualState 读数，
 * 且各自独立回退：光影包接管后链时只跳过 pass，雾和 2D 合成仍然跟着光照走。
 */
@Mod.EventBusSubscriber(modid = PMWLite.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        ShaderCompatLayer.tick();
        ClientVisualState.tick();
        // 先算光照事实，再让同一帧的雾/pass/2D 合成共用它；不要在渲染回调里算。
        LightingState.tick(0f);

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
        // 光学现象独立于风暴强度：晴天也可能有幻日/极光，所以单独调用，
        // 且在 StormOverlayLayer 之后画，让光幕叠在风暴暗角之上。
        OpticsOverlayLayer.render(event.getGuiGraphics(), event.getPartialTick());
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientRadarCache.clear();
    }
}
