package com.stellar.pmwlite;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import com.stellar.pmwlite.asset.FixedAssetManager;
import com.stellar.pmwlite.config.ServerConfig;
import com.stellar.pmwlite.damage.TornadoDestroyHandler;
import com.stellar.pmwlite.damage.WildfireSystem;
import com.stellar.pmwlite.forecast.AlertManager;
import com.stellar.pmwlite.forecast.ForecastEngine;
import com.stellar.pmwlite.fortify.FortificationManager;
import com.stellar.pmwlite.network.NetworkHandler;
import com.stellar.pmwlite.radar.RadarService;
import com.stellar.pmwlite.registry.ModRegistry;
import com.stellar.pmwlite.render.WeatherStateBridge;
import com.stellar.pmwlite.render.TornadoFX;
import com.stellar.pmwlite.render.PrecipitationSystem;
import com.stellar.pmwlite.weather.StormManager;
import com.stellar.pmwlite.weather.WeatherSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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
 * v0.2 additions (all additive; nothing in the v0.1 public surface changed):
 *  - registry (sounds / weather station block / reinforcement kit),
 *  - radar network channel + {@link RadarService},
 *  - {@link AlertManager} forecast alerts,
 *  - {@link FixedAssetManager} / {@link FortificationManager} per-level state with cleanup.
 */
@Mod(PMWLite.MOD_ID)
public class PMWLite {
    public static final String MOD_ID = "pmwlite";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static PMWLite instance;

    private final ServerConfig config = new ServerConfig();
    private final Map<ServerLevel, StormManager> managers = new HashMap<>();
    private final Map<ServerLevel, WeatherSystem> weatherSystems = new HashMap<>();
    private final TornadoDestroyHandler destroyHandler = new TornadoDestroyHandler(config);
    private final WeatherStateBridge weatherBridge = new WeatherStateBridge(config);
    private final TornadoFX tornadoFX = new TornadoFX();
    private final PrecipitationSystem precipitation = new PrecipitationSystem();
    private final WildfireSystem wildfire = new WildfireSystem();
    private final AlertManager alertManager = new AlertManager();
    private boolean initialized = false;

    public PMWLite() {
        instance = this;
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        IEventBus forgeBus = MinecraftForge.EVENT_BUS;

        ModRegistry.init(modBus);
        NetworkHandler.init();

        forgeBus.register(this);
        LOGGER.info("PMWeather Lite initialized (storm physics; rendering delegated to shader packs)");
    }

    /** Mod singleton, or null before construction (e.g. from a data-pack load hook). */
    public static PMWLite instance() {
        return instance;
    }

    /** Per-dimension storm simulation, created on demand; never null for a live level. */
    public StormManager stormManager(ServerLevel level) {
        return managers.computeIfAbsent(level, l -> new StormManager(l, config));
    }

    /** Per-dimension weather-event simulation, created on demand. */
    public WeatherSystem weatherSystem(ServerLevel level) {
        return weatherSystems.computeIfAbsent(level, l -> new WeatherSystem(l, config));
    }

    public AlertManager alerts() {
        return alertManager;
    }

    public ServerConfig config() {
        return config;
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
        FixedAssetManager.clear(sl);
        ForecastEngine.clear(sl);
        FortificationManager.clear(sl);
        com.stellar.pmwlite.atmos.AtmosphereManager.clear(sl);
    }

    /** Clear per-player sound state on logout (bounded memory). */
    @SubscribeEvent
    public void onPlayerLogout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            com.stellar.pmwlite.sound.SoundManager.forget(sp.getUUID());
            alertManager.forget(sp.getUUID());
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !initialized) return;
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (ServerLevel level : server.getAllLevels()) {
            StormManager manager = stormManager(level);
            WeatherSystem weather = weatherSystem(level);
            manager.tick();
            weather.tick();
            destroyHandler.tick(level, manager);
            weatherBridge.tick(level, manager, weather);
            tornadoFX.tick(level, manager);
            precipitation.tick(level, manager, weather, config);
            wildfire.tick(level, manager);
            alertManager.tick(level, manager);
            com.stellar.pmwlite.atmos.AtmosphereManager.of(level, config).tick(manager, weather);
        }

        // 大气探针 1 Hz 推送（HUD 与雷达信息栏共用；doAtmosphere 关闭时不发，避免无意义流量）
        if (config.doAtmosphere && server.getTickCount() % 20 == 0) {
            for (ServerLevel level : server.getAllLevels()) {
                for (ServerPlayer player : level.players()) {
                    RadarService.sendProbe(player);
                    RadarService.sendOptics(player);
                }
            }
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        dispatcher.register(net.minecraft.commands.Commands.literal("pmwlite")
                .requires(src -> src.hasPermission(2))
                .then(net.minecraft.commands.Commands.literal("summon").executes(ctx -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    StormManager m = stormManager(level);
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
                                    WeatherSystem ws = weatherSystem(level);
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
                    StormManager m = stormManager(level);
                    WeatherSystem ws = weatherSystem(level);
                    m.getStorms().clear();
                    ws.clearAll();
                    var src = ctx.getSource().getPlayer();
                    if (src != null) com.stellar.pmwlite.sound.SoundManager.playUi(src,
                            com.stellar.pmwlite.sound.SoundManager.Sfx.CLEAR, 0.7f, 1.0f);
                    ctx.getSource().sendSuccess(() -> Component.literal("§a[PMWLite] 已清除全部天气与风暴。"), true);
                    return 1;
                }))
                // ---------------- v0.2: forecast / radar / fortification / assets ----------------
                .then(net.minecraft.commands.Commands.literal("forecast").executes(ctx -> {
                    var src = ctx.getSource().getPlayer();
                    BlockPos at = src != null ? src.blockPosition() : BlockPos.containing(ctx.getSource().getPosition());
                    ServerLevel level = ctx.getSource().getLevel();
                    var f = ForecastEngine.forecast(level, at, managers.get(level));
                    StringBuilder sb = new StringBuilder("§6[PMWLite] 预报 §7@ ")
                            .append(at.getX()).append(", ").append(at.getZ()).append("\n");
                    sb.append(f.colorCode()).append(f.headline()).append("\n");
                    sb.append("§7风险 §f").append(String.format("%.2f", f.risk()))
                            .append(" §8| §7置信 §f").append(f.confidence()).append("%")
                            .append(f.precise() ? " §b(气象站精报)" : " §8(粗略)").append("\n");
                    if (f.etaSeconds() >= 0) {
                        sb.append("§7预计 §f").append(f.etaSeconds()).append("s §7后影响本地\n");
                    }
                    if (!f.expectedTypes().isEmpty()) {
                        sb.append("§7可能: §f").append(String.join("、", f.expectedTypes())).append("\n");
                    }
                    String text = sb.toString();
                    ctx.getSource().sendSuccess(() -> Component.literal(text), false);
                    return 1;
                }))
                .then(net.minecraft.commands.Commands.literal("radar").executes(ctx -> {
                    var src = ctx.getSource().getPlayer();
                    if (src == null) {
                        ctx.getSource().sendFailure(Component.literal("§c[PMWLite] 该命令只能由玩家执行。"));
                        return 0;
                    }
                    RadarService.sendTo(src);
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "§a[PMWLite] 雷达数据已发送 §7(按 §fR §7随时打开)"), false);
                    return 1;
                }))
                .then(net.minecraft.commands.Commands.literal("reinforce").executes(ctx -> {
                    var src = ctx.getSource().getPlayer();
                    if (src == null) {
                        ctx.getSource().sendFailure(Component.literal("§c[PMWLite] 该命令只能由玩家执行。"));
                        return 0;
                    }
                    // 6-block raycast: same target the player is aiming at, no client input needed.
                    HitResult hit = src.pick(6.0D, 1.0F, false);
                    if (!(hit instanceof BlockHitResult bhr) || bhr.getType() != HitResult.Type.BLOCK) {
                        ctx.getSource().sendFailure(Component.literal(
                                "§c[PMWLite] 请先瞄准一个方块（6 格内）"));
                        return 0;
                    }
                    BlockPos pos = bhr.getBlockPos();
                    FortificationManager fg = FortificationManager.of(src.serverLevel());
                    int before = fg.bonusAt(pos);
                    int after = fg.fortify(pos);
                    if (after < 0) {
                        ctx.getSource().sendFailure(Component.literal(before >= FortificationManager.MAX_BONUS
                                ? "§e[PMWLite] 该方块已达加固上限 §7(+" + before + ")"
                                : "§c[PMWLite] 本维度加固数量已达上限"));
                        return 0;
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal("§a[PMWLite] 已加固 §f"
                            + pos.toShortString() + " §7抗风阈值 " + before + " → §f" + after), false);
                    return 1;
                }))
                .then(net.minecraft.commands.Commands.literal("assets").executes(ctx -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    FixedAssetManager am = FixedAssetManager.of(level);
                    BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
                    StringBuilder sb = new StringBuilder("§6[PMWLite] 固定资产§r\n");
                    sb.append("§7已登记: §f").append(am.size()).append(" §8/ ")
                            .append(FixedAssetManager.MAX_ASSETS).append("\n");
                    sb.append("§7本站覆盖: ")
                            .append(am.coveredByStation(at) ? "§a精报已启用" : "§8无气象站覆盖").append("\n");
                    sb.append("§7已加固方块: §f").append(FortificationManager.of(level).reinforcedCount()).append("\n");
                    var near = am.stationsNear(at, FixedAssetManager.STATION_COVERAGE);
                    if (!near.isEmpty()) {
                        sb.append("§7附近气象站:\n");
                        int shown = 0;
                        for (var a : near) {
                            if (shown++ >= 5) break;
                            sb.append("  §b").append(a.kind()).append(" §7@ ")
                                    .append(a.pos().toShortString()).append("\n");
                        }
                    }
                    String text = sb.toString();
                    ctx.getSource().sendSuccess(() -> Component.literal(text), false);
                    return 1;
                }))
                .then(net.minecraft.commands.Commands.literal("atmos").executes(ctx -> {
                    var src = ctx.getSource().getPlayer();
                    if (src == null) {
                        ctx.getSource().sendFailure(Component.literal("§c[PMWLite] 该命令只能由玩家执行。"));
                        return 0;
                    }
                    ServerLevel level = src.serverLevel();
                    var am = com.stellar.pmwlite.atmos.AtmosphereManager.of(level, config);
                    var probe = new com.stellar.pmwlite.atmos.AtmosphereGrid.Probe();
                    Vec3 eye = src.position().add(0, src.getEyeHeight(), 0);
                    if (!am.probe(eye, probe)) {
                        ctx.getSource().sendFailure(Component.literal(
                                "§c[PMWLite] 本地大气网格尚未就绪（或 doAtmosphere 已关闭）"));
                        return 0;
                    }
                    StringBuilder sb = new StringBuilder("§6[PMWLite] 本地大气 @ ")
                            .append((int) eye.x).append(", ").append((int) eye.y).append(", ")
                            .append((int) eye.z).append("§r\n");
                    sb.append(String.format("§7气压 §f%.2f hPa   §7气温 §f%.2f ℃   §7露点 §f%.2f ℃%n",
                            probe.pressureHpa, probe.tempC, probe.dewPointC));
                    sb.append(String.format("§7相对湿度 §f%.0f%%   §7比湿 §f%.2f g/kg%n",
                            probe.humidity * 100f,
                            com.stellar.pmwlite.atmos.ApparentTemperature.saturationMixingRatio(
                                    probe.tempC, probe.pressureHpa) * probe.humidity));
                    sb.append(String.format("§7氧气 §f%.2f%%   §7云量 §f%.0f%%   §7云底 §f%.0f m%n",
                            probe.oxygen * 100f, probe.cloud * 100f, probe.cloudBaseM));
                    sb.append(String.format("§7风 §f%.2f m/s §7(u %.2f / v %.2f / w %.2f)   体感 §f%.2f ℃%n",
                            probe.windSpeed, probe.windX, probe.windZ, probe.windY, probe.apparentC));
                    sb.append("§7位置: ").append(probe.underground ? "§c地下/室内" : "§a露天");
                    String text = sb.toString();
                    ctx.getSource().sendSuccess(() -> Component.literal(text), false);
                    return 1;
                }))
                .then(net.minecraft.commands.Commands.literal("global").executes(ctx -> {
                    var src = ctx.getSource().getPlayer();
                    BlockPos at = src != null ? src.blockPosition()
                            : BlockPos.containing(ctx.getSource().getPosition());
                    ServerLevel level = ctx.getSource().getLevel();
                    var gm = com.stellar.pmwlite.atmos.AtmosphereManager.of(level, config).global();
                    if (!gm.ready()) {
                        ctx.getSource().sendFailure(Component.literal("§c[PMWLite] 全球大气模型尚未就绪。"));
                        return 0;
                    }
                    StringBuilder sb = new StringBuilder("§6[PMWLite] 八层大气剖面 @ ")
                            .append(at.getX()).append(", ").append(at.getZ()).append("§r\n");
                    sb.append("§8层            高度      气压      温度    云量     风§r\n");
                    for (int l = 0; l < com.stellar.pmwlite.atmos.GlobalModel.LAYERS; l++) {
                        int i = gm.indexAt(at.getX(), at.getZ(), l);
                        if (i < 0) break;
                        float wu = gm.windUAt(at.getX(), at.getZ(), l);
                        float wv = gm.windVAt(at.getX(), at.getZ(), l);
                        sb.append(String.format("§7%-12s §f%6.0f m  §f%7.1f hPa  §f%6.1f ℃  §f%4.0f%%  §f%5.1f m/s%n",
                                com.stellar.pmwlite.atmos.GlobalModel.LAYER_NAMES[l],
                                com.stellar.pmwlite.atmos.GlobalModel.HEIGHTS[l],
                                gm.pressureAt(at.getX(), at.getZ(), l),
                                gm.temperatureAt(at.getX(), at.getZ(), l),
                                gm.cloudAt(at.getX(), at.getZ(), l) * 100f,
                                Math.sqrt(wu * wu + wv * wv)));
                    }
                    String text = sb.toString();
                    ctx.getSource().sendSuccess(() -> Component.literal(text), false);
                    return 1;
                }))
                .then(net.minecraft.commands.Commands.literal("atmoscheck")
                        .executes(ctx -> runAtmosCheck(ctx.getSource(), 24))
                        .then(net.minecraft.commands.Commands.argument("steps",
                                com.mojang.brigadier.arguments.IntegerArgumentType.integer(4, 240))
                                .executes(ctx -> runAtmosCheck(ctx.getSource(),
                                        com.mojang.brigadier.arguments.IntegerArgumentType
                                                .getInteger(ctx, "steps")))))
                .then(net.minecraft.commands.Commands.literal("hint").executes(ctx -> {
                    var src = ctx.getSource().getPlayer();
                    if (src == null) return 0;
                    src.sendSystemMessage(Component.literal(ChatFormatting.GOLD + "[PMWLite] 快捷操作"));
                    src.sendSystemMessage(Component.literal("§7按 §fR §7打开气象雷达"));
                    src.sendSystemMessage(Component.literal("§7/item 获取气象站与加固套件（创造模式物品栏）"));
                    src.sendSystemMessage(Component.literal("§7/pmwlite forecast §8预报 · §7/pmwlite reinforce §8瞄准加固"));
                    return 1;
                })));
    }

    /**
     * 大气场自检：控制台也能跑（网格按命令位置临时建立），所以专用服务器冒烟测试可以覆盖到。
     * 返回 1 表示全部通过，0 表示有失败项。
     */
    private int runAtmosCheck(net.minecraft.commands.CommandSourceStack src, int steps) {
        ServerLevel level = src.getLevel();
        Vec3 at = src.getPosition();
        if (src.getPlayer() == null) {
            // 控制台/命令方块没有"视高"，用该列地表上方一格，避免在 y= 出生点（可能在地下）跑测试
            int sy = level.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    (int) Math.floor(at.x), (int) Math.floor(at.z));
            at = new Vec3(at.x, Math.max(at.y, sy + 1), at.z);
        }
        var report = com.stellar.pmwlite.atmos.AtmosphereSelfTest.run(level, at, steps);
        String head = report.failures == 0
                ? "§a[PMWLite] 大气场自检通过 §7(" + report.checks + " 项全绿)"
                : "§c[PMWLite] 大气场自检失败 §7" + report.failures + " / " + report.checks + " 项";
        String where = "§8位置 " + (int) at.x + ", " + (int) at.y + ", " + (int) at.z;
        src.sendSuccess(() -> Component.literal(head + "\n" + where), false);
        src.sendSuccess(() -> Component.literal(report.text()), false);

        // 同时写服务端日志：自检的价值一半在"能 grep"，只打在聊天框里等于没做。
        // 日志里去掉 § 颜色码，方便直接进 CI / 冒烟脚本比对。
        String plain = report.text().replaceAll("§.", "");
        if (report.failures == 0) {
            LOGGER.info("[PMWLite] atmoscheck PASS ({} checks) at {},{},{}\n{}",
                    report.checks, (int) at.x, (int) at.y, (int) at.z, plain);
            com.stellar.pmwlite.sound.SoundManager.playUi(src.getPlayer(),
                    com.stellar.pmwlite.sound.SoundManager.Sfx.UI_CONFIRM, 0.6f, 1.2f);
        } else {
            LOGGER.warn("[PMWLite] atmoscheck FAIL {}/{} at {},{},{}\n{}",
                    report.failures, report.checks, (int) at.x, (int) at.y, (int) at.z, plain);
        }
        return report.failures == 0 ? 1 : 0;
    }
}
