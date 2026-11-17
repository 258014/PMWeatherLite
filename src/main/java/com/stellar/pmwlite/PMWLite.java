package com.stellar.pmwlite;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import com.stellar.pmwlite.config.ServerConfig;
import com.stellar.pmwlite.damage.TornadoDestroyHandler;
import com.stellar.pmwlite.damage.WildfireSystem;
import com.stellar.pmwlite.render.WeatherStateBridge;
import com.stellar.pmwlite.render.TornadoFX;
import com.stellar.pmwlite.render.PrecipitationSystem;
import com.stellar.pmwlite.weather.StormManager;
import com.stellar.pmwlite.weather.WeatherSystem;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.util.HashMap;
import java.util.Map;

/**
 * PMWeather Lite - independent Forge 1.20.1 reimplementation of PMWeather's storm
 * simulation, designed to coexist WITH shader packs (rendering delegated to Oculus/Iris).
 *
 * OPTIMIZATION v2:
 *  - Per-level state is cleaned on level unload (fixes HashMap leak across dimension unloads).
 *  - All 17 weather types from WeatherSystem are now wired into rendering/bridge/precipitation.
 */
@Mod(PMWLite.MOD_ID)
public class PMWLite {
    public static final String MOD_ID = "pmwlite";
    public static final Logger LOGGER = LogUtils.getLogger();

    private final ServerConfig config = new ServerConfig();
    private final Map<ServerLevel, StormManager> managers = new HashMap<>();
    private final Map<ServerLevel, WeatherSystem> weatherSystems = new HashMap<>();
    private final TornadoDestroyHandler destroyHandler = new TornadoDestroyHandler(config);
    private final WeatherStateBridge weatherBridge = new WeatherStateBridge(config);
    private final TornadoFX tornadoFX = new TornadoFX();
    private final PrecipitationSystem precipitation = new PrecipitationSystem();
    private final WildfireSystem wildfire = new WildfireSystem();
    private boolean initialized = false;

    public PMWLite() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        IEventBus forgeBus = MinecraftForge.EVENT_BUS;
        forgeBus.register(this);
        LOGGER.info("PMWeather Lite initialized (storm physics; rendering delegated to shader packs)");
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        initialized = true;
        LOGGER.info("PMWeather Lite ready. /pmwlite list shows all {} weather types.",
                com.stellar.pmwlite.weather.WeatherType.values().length);
    }

    /** Fix memory leak: drop per-level state when a dimension unloads. */
    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        managers.remove(sl);
        weatherSystems.remove(sl);
    }

    /** Clear per-player sound state on logout (bounded memory). */
    @SubscribeEvent
    public void onPlayerLogout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            com.stellar.pmwlite.sound.SoundManager.forget(sp.getUUID());
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !initialized) return;
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (ServerLevel level : server.getAllLevels()) {
            StormManager manager = managers.computeIfAbsent(level, l -> new StormManager(l, config));
            WeatherSystem weather = weatherSystems.computeIfAbsent(level, l -> new WeatherSystem(l, config));
            manager.tick();
            weather.tick();
            destroyHandler.tick(level, manager);
            weatherBridge.tick(level, manager, weather);
            tornadoFX.tick(level, manager);
            precipitation.tick(level, manager, weather);
            wildfire.tick(level, manager);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        dispatcher.register(net.minecraft.commands.Commands.literal("pmwlite")
                .requires(src -> src.hasPermission(2))
                .then(net.minecraft.commands.Commands.literal("summon").executes(ctx -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    StormManager m = managers.computeIfAbsent(level, l -> new StormManager(l, config));
                    Vec3 p = ctx.getSource().getPosition();
                    StormManager.summonAt(m, p);
                    // audio + text feedback
                    var src = ctx.getSource().getPlayer();
                    if (src != null) com.stellar.pmwlite.sound.SoundManager.playUi(src,
                            com.stellar.pmwlite.sound.SoundManager.Sfx.SUMMON, 0.8f, 1.0f);
                    ctx.getSource().sendSuccess(() -> Component.literal("§a[PMWLite] 风暴已生成 §7("
                            + (int) p.x + ", " + (int) p.z + ")"), true);
                    return 1;
                }))
                .then(net.minecraft.commands.Commands.literal("weather")
                        .then(net.minecraft.commands.Commands.argument("type",
                                com.mojang.brigadier.arguments.StringArgumentType.string())
                                .suggests((ctx, builder) -> {
                                    for (com.stellar.pmwlite.weather.WeatherType t
                                            : com.stellar.pmwlite.weather.WeatherType.values()) {
                                        builder.suggest(t.name());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    ServerLevel level = ctx.getSource().getLevel();
                                    WeatherSystem ws = weatherSystems.computeIfAbsent(level, l -> new WeatherSystem(l, config));
                                    String raw = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "type");
                                    var src = ctx.getSource().getPlayer();
                                    try {
                                        com.stellar.pmwlite.weather.WeatherType type =
                                                com.stellar.pmwlite.weather.WeatherType.valueOf(raw.toUpperCase());
                                        ws.trigger(type, ctx.getSource().getPosition());
                                        if (src != null) com.stellar.pmwlite.sound.SoundManager.playUi(src,
                                                com.stellar.pmwlite.sound.SoundManager.Sfx.UI_CONFIRM, 0.6f, 1.4f);
                                        ctx.getSource().sendSuccess(() -> Component.literal("§a[PMWLite] 已触发 §e"
                                                + type.name + " §7(" + type.category + ")"), true);
                                    } catch (IllegalArgumentException ex) {
                                        if (src != null) com.stellar.pmwlite.sound.SoundManager.playUi(src,
                                                com.stellar.pmwlite.sound.SoundManager.Sfx.UI_DENY, 0.6f, 0.8f);
                                        ctx.getSource().sendFailure(Component.literal(
                                                "§c[PMWLite] 未知天气类型: " + raw + " §7用 /pmwlite list 查看全部"));
                                    }
                                    return 1;
                                })))
                .then(net.minecraft.commands.Commands.literal("list").executes(ctx -> {
                    // Group by category so the list stays readable. The count is read from the
                    // enum so the header can never drift out of sync with the actual types.
                    int total = com.stellar.pmwlite.weather.WeatherType.values().length;
                    StringBuilder sb = new StringBuilder("§6[PMWLite] " + total + " 种极端天气§r\n");
                    for (com.stellar.pmwlite.weather.WeatherType.Category cat
                            : com.stellar.pmwlite.weather.WeatherType.Category.values()) {
                        sb.append("§e").append(cat).append("§7: ");
                        for (com.stellar.pmwlite.weather.WeatherType t
                                : com.stellar.pmwlite.weather.WeatherType.values()) {
                            if (t.category == cat) sb.append(t.name()).append(" ");
                        }
                        sb.append("\n");
                    }
                    String text = sb.toString();
                    ctx.getSource().sendSuccess(() -> Component.literal(text), false);
                    return 1;
                }))
                .then(net.minecraft.commands.Commands.literal("status").executes(ctx -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    WeatherSystem ws = weatherSystems.get(level);
                    StormManager sm = managers.get(level);
                    int wx = ws == null ? 0 : ws.getActive().size();
                    int st = sm == null ? 0 : sm.getStorms().size();
                    boolean muted = ctx.getSource().getPlayer() != null
                            && com.stellar.pmwlite.sound.SoundManager.isMuted(ctx.getSource().getPlayer());
                    StringBuilder sb = new StringBuilder("§6[PMWLite] 状态§r\n");
                    sb.append("§7活动天气事件: §f").append(wx).append("\n");
                    sb.append("§7活跃风暴: §f").append(st).append("\n");
                    sb.append("§7音效: ").append(muted ? "§c已静音" : "§a开启").append("\n");
                    if (ws != null && !ws.getActive().isEmpty()) {
                        sb.append("§7事件列表:\n");
                        for (var e : ws.getActive()) {
                            sb.append("  §e").append(e.type.name)
                                    .append(" §7强度 ").append(String.format("%.0f%%", e.intensity * 100))
                                    .append(" §7剩余 ").append(e.remainingSeconds(level.getGameTime())).append("s\n");
                        }
                    }
                    String text = sb.toString();
                    ctx.getSource().sendSuccess(() -> Component.literal(text), false);
                    return 1;
                }))
                .then(net.minecraft.commands.Commands.literal("mute").executes(ctx -> {
                    var src = ctx.getSource().getPlayer();
                    if (src == null) {
                        ctx.getSource().sendFailure(Component.literal("§c[PMWLite] 该命令只能由玩家执行。"));
                        return 0;
                    }
                    boolean nowMuted = com.stellar.pmwlite.sound.SoundManager.toggleMute(src);
                    if (nowMuted) {
                        com.stellar.pmwlite.sound.SoundManager.playUi(src,
                                com.stellar.pmwlite.sound.SoundManager.Sfx.UI_DENY, 0.5f, 0.8f);
                    } else {
                        com.stellar.pmwlite.sound.SoundManager.playUi(src,
                                com.stellar.pmwlite.sound.SoundManager.Sfx.UI_CONFIRM, 0.5f, 1.4f);
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal(nowMuted
                            ? "§c[PMWLite] 已静音天气音效 §7(/pmwlite mute 恢复)"
                            : "§a[PMWLite] 已开启天气音效"), false);
                    return 1;
                }))
                .then(net.minecraft.commands.Commands.literal("clear").executes(ctx -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    StormManager m = managers.computeIfAbsent(level, l -> new StormManager(l, config));
                    WeatherSystem ws = weatherSystems.computeIfAbsent(level, l -> new WeatherSystem(l, config));
                    m.getStorms().clear();
                    ws.clearAll();
                    var src = ctx.getSource().getPlayer();
                    if (src != null) com.stellar.pmwlite.sound.SoundManager.playUi(src,
                            com.stellar.pmwlite.sound.SoundManager.Sfx.CLEAR, 0.7f, 1.0f);
                    ctx.getSource().sendSuccess(() -> Component.literal("§a[PMWLite] 已清除全部天气与风暴。"), true);
                    return 1;
                })));
    }
}
