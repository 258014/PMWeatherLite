package com.stellar.pmwlite.config;

import java.util.HashMap;
import java.util.Map;

/**
 * Ported from PMWeather ServerConfig.java (the gameplay-relevant subset).
 * Holds spawn probabilities, stage progression, and per-block wind-damage thresholds.
 * Values mirror the source defaults.
 */
public final class ServerConfig {
    // spawning (source defaults)
    public int spawnRange = 384;
    public int chanceInOneSquall = 10;
    public int chanceInOneCyclone = 64;
    public int chanceInOneStage1 = 2;
    public int chanceInOneStage2 = 3;
    public int chanceInOneStage3 = 5;
    public double stormSpawnChancePerMinute = 1.0 / 5.0;
    public boolean environmentSystem = true;
    public int maxStorms = 3;

    // storm dynamics
    public double squallStrengthMultiplier = 1.25;
    public float backgroundWindMultiplier = 1.0f;
    public float stageChance = 1f / 3f;
    public int windPerStage = 22;
    public float widthPerStage = 4f;

    // modules
    public boolean doDamage = true;
    public boolean doTornadoes = true;
    public boolean doCyclones = true;
    public boolean doSqualls = true;
    public boolean doSeasons = true;

    /** Global switch for all mod sounds (per-player mute still works when this is on). */
    public static boolean soundEnabled = true;
    /** Emit action-bar warnings when severe weather approaches. */
    public boolean weatherWarnings = true;

    // ---------------- v0.3 大气场（参照"每格一份空气"的场式模拟）----------------
    /** 本地大气场（气压/风/湿度/温度/氧气）总开关；关闭后行为与 v0.2 完全一致。 */
    public boolean doAtmosphere = true;
    /** 地形风（遮蔽 / 狭管加速 / 卡门涡街）是否参与破坏判定。 */
    public boolean terrainWind = true;
    /** 低氧后果（挖掘疲劳 + 缓慢伤害）；只想要数值、不想要惩罚时关掉。 */
    public boolean oxygenEffects = true;
    /** 降水强度是否随本地云量调制（云薄时粒子更少）。 */
    public boolean cloudDrivenPrecipitation = true;
    /** 风暴是否被八层全球环流的中层引导气流推动（关掉则退回 v0.2 的纯噪声漂移）。 */
    public boolean globalSteering = true;

    // ---------------- CO₂ 示踪场 / 污染（机器耗氧→排 CO₂→平流→中毒）----------------
    /** CO₂ 示踪场总开关；关闭后不检测排放源、不平流 CO₂，行为与之前一致。 */
    public boolean co2System = true;
    /** 是否把"燃烧中的机器/火"当作耗氧排碳源（需要建造时才关心）。 */
    public boolean machineEmissions = true;
    /** CO₂ 健康后果：高浓度给缺氧/反胃等效果（复用原版 effect，供 PoT 等 mod 兼容）。 */
    public boolean co2HealthEffects = true;

    /**
     * Per-block wind speed threshold at which the block is ripped up.
     * Ported from source blockStrengths defaults (leaves 50-65, wool 60, etc.).
     */
    public final Map<String, Integer> blockStrengths = new HashMap<>();
    /** Blocks never damaged (source blacklist). */
    public final java.util.Set<String> blacklistedBlocks = new java.util.HashSet<>();

    public ServerConfig() {
        // strengthening materials
        blockStrengths.put("minecraft:reinforced_glass", 140);
        blockStrengths.put("minecraft:glass", 70);
        blockStrengths.put("minecraft:glass_pane", 65);
        // leaves
        blockStrengths.put("minecraft:acacia_leaves", 55);
        blockStrengths.put("minecraft:azalea_leaves", 55);
        blockStrengths.put("minecraft:birch_leaves", 50);
        blockStrengths.put("minecraft:dark_oak_leaves", 55);
        blockStrengths.put("minecraft:cherry_leaves", 55);
        blockStrengths.put("minecraft:flowering_azalea_leaves", 55);
        blockStrengths.put("minecraft:mangrove_leaves", 65);
        blockStrengths.put("minecraft:oak_leaves", 55);
        blockStrengths.put("minecraft:jungle_leaves", 55);
        // light / fragile
        blockStrengths.put("minecraft:chain", 75);
        blockStrengths.put("minecraft:lantern", 75);
        blockStrengths.put("minecraft:soul_lantern", 75);
        blockStrengths.put("minecraft:cobweb", 65);
        blockStrengths.put("minecraft:chest", 120);
        // wool family
        for (String c : new String[]{"white","orange","magenta","light_blue","yellow","lime","pink",
                "gray","light_gray","cyan","purple","blue","brown","green","red","black"}) {
            blockStrengths.put("minecraft:" + c + "_wool", 60);
        }
        // blacklist (base terrain stays intact)
        java.util.Collections.addAll(blacklistedBlocks,
                "minecraft:gravel", "minecraft:farmland", "minecraft:dirt_path",
                "minecraft:snow_block", "minecraft:bedrock", "minecraft:dirt");
    }

    public boolean isBlacklisted(String blockId) { return blacklistedBlocks.contains(blockId); }
    public Integer getStrength(String blockId) { return blockStrengths.get(blockId); }

    // ------------------------------------------------------------------
    // OPTIMIZATION v4: identity-keyed lookup tables.
    //
    // The destruction scan used to build a registry-name String for every non-air block
    // (~315 per scan, 4 scans/s per tornado => ~1.3k Strings/s, each with a StringBuilder
    // and a ResourceLocation) purely to do two HashMap<String,...> lookups. The tables are
    // now resolved once into Block-keyed maps, so a scan does two identity-hash lookups and
    // allocates nothing.
    // ------------------------------------------------------------------
    private Map<net.minecraft.world.level.block.Block, Integer> strengthByBlock;
    private java.util.Set<net.minecraft.world.level.block.Block> blacklistByBlock;

    private void buildBlockCache() {
        if (strengthByBlock != null) return;
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        Map<net.minecraft.world.level.block.Block, Integer> strengths = new HashMap<>(blockStrengths.size() * 2);
        for (Map.Entry<String, Integer> e : blockStrengths.entrySet()) {
            var rl = net.minecraft.resources.ResourceLocation.tryParse(e.getKey());
            if (rl == null || !registry.containsKey(rl)) continue;
            strengths.put(registry.get(rl), e.getValue());
        }
        java.util.Set<net.minecraft.world.level.block.Block> blacklist = new java.util.HashSet<>(blacklistedBlocks.size() * 2);
        for (String id : blacklistedBlocks) {
            var rl = net.minecraft.resources.ResourceLocation.tryParse(id);
            if (rl == null || !registry.containsKey(rl)) continue;
            blacklist.add(registry.get(rl));
        }
        this.blacklistByBlock = blacklist;
        this.strengthByBlock = strengths;
    }

    /**
     * Wind-speed threshold for a block, in the same units the old String lookup used.
     *
     * @return the threshold, or -1 when the block is blacklisted / unknown (immune)
     */
    public int strengthThreshold(net.minecraft.world.level.block.state.BlockState state) {
        buildBlockCache();
        var block = state.getBlock();
        if (blacklistByBlock.contains(block)) return -1;
        Integer v = strengthByBlock.get(block);
        return v == null ? -1 : v;
    }
}
