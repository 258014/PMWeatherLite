package com.stellar.pmwlite.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.stellar.pmwlite.PMWLite;
import net.minecraft.client.KeyMapping;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Client bootstrap on the MOD event bus: key bindings and resource-reload hooks.
 *
 * Kept separate from {@link ClientEvents} (which sits on the Forge bus) because the two event buses
 * are registered by different annotations -- mixing them in one class silently drops handlers.
 */
@Mod.EventBusSubscriber(modid = PMWLite.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientSetup {

    /** R by default; only fires in-game so it never eats chat input. */
    public static final KeyMapping RADAR_KEY = new KeyMapping(
            "key.pmwlite.radar",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            "key.categories.pmwlite");

    private ClientSetup() {}

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(RADAR_KEY);
    }

    /**
     * Shaders/programs are reloaded with the resource pack, so the post chain has to be rebuilt or
     * it keeps pointing at deleted GL objects. The listener itself does no work on the reload
     * threads -- it just marks the pipeline dirty.
     */
    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new PreparableReloadListener() {
            @Override
            public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager rm,
                                                  ProfilerFiller prepProfiler, ProfilerFiller reloadProfiler,
                                                  Executor background, Executor game) {
                CompletableFuture<Void> done = CompletableFuture.completedFuture(null);
                return done.thenCompose(barrier::wait).thenRun(PostEffectPipeline::invalidate);
            }
        });
    }
}
