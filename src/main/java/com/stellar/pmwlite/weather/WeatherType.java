package com.stellar.pmwlite.weather;

import net.minecraft.util.Mth;

/**
 * Real-world severe weather taxonomy encoded for the PMWeatherLite simulation.
 * Every field is grounded in meteorological standards (CMA / NWS / EF-scale /
 * Saffir-Simpson). See the companion design doc for full Chinese descriptions.
 *
 * Rendering note: this mod NEVER draws clouds via post-processing; all visuals are
 * vanilla particles/entities + driving vanilla weather state so Oculus/Iris shader
 * packs render the actual volumetric look. This keeps full shader compatibility.
 */
public enum WeatherType {
    // ===== Convective storms =====
    SUPERCELL("超级单体雷暴", Category.CONVECTIVE,
            "暖湿气流+强垂直风切变+高CAPE(>2000 J/kg)，大气层结极不稳定",
            "按上升气流与中气旋：普通单体→超级单体→中气旋→龙卷",
            0.5f, 4f, 20f, 150f, 300f,
            "高耸积雨云(砧状云顶)、频繁闪电、大冰雹、绿灰色天空",
            "强降水、冰雹、下击暴流；可孕育龙卷"),

    TORNADO("龙卷风", Category.CONVECTIVE,
            "超级单体中气旋的垂直涡旋拉伸，风切变使涡旋收缩加速下伸触地",
            "EF0(65-85mph) / EF1(86-110) / EF2(111-135) / EF3(136-165) / EF4(166-200) / EF5(≥201)",
            0.02f, 1f, 0.15f, 0.6f, 60f,
            "漏斗云柱触地、碎片飞舞、天空发绿/黄、轰鸣声",
            "EF0折枝→EF2拔树掀车→EF3掀屋顶翻火车→EF5夷平建筑抛飞地基"),

    SQUALL_LINE("飑线", Category.CONVECTIVE,
            "冷锋前触发的排列成线的雷暴带，强垂直风切变维持",
            "按阵风：39-54mph小枝断 / 55-72拔浅根树 / 73-112结构小损 / 113+重损",
            1f, 8f, 40f, 200f, 500f,
            "绵延百公里的乌云墙、一字排开的闪电、狂风裹暴雨",
            "大范围直线大风、频繁闪电、突发性强降水，可致断电"),

    DOWNBURST("下击暴流", Category.CONVECTIVE,
            "雷暴中降水蒸发冷却形成下沉气流，触地后向四周辐散",
            "微下击暴流(<4km) / 下击暴流(4-10km)；阵风可超100mph",
            0.05f, 0.5f, 0.1f, 0.4f, 12f,
            "雨幕下有尘暴圆顶、地面放射状直线风",
            "突发性狂风可吹翻车辆、折断树木，威胁航空起降"),

    HAIL("冰雹", Category.CONVECTIVE,
            "强上升气流将水滴反复抬升冻结，层层裹冰后降落",
            "按直径：豌豆6mm / 镍币18mm / 四分之一美元25mm(强对流阈值) / 高尔夫球45mm / 棒球70mm / 葡萄柚100mm",
            0.1f, 1f, 0.1f, 0.5f, 30f,
            "白色冰块倾泻、噼啪撞击声、地表白色堆积",
            "砸毁作物/车顶/玻璃，大冰雹可致人畜伤亡"),

    // ===== Tropical cyclones =====
    HURRICANE("热带气旋/飓风", Category.TROPICAL,
            "海温>26.5℃的热带洋面，科氏力使扰动旋转，暖心结构自我强化",
            "热带低压(<38mph)→热带风暴(39-73)→萨菲尔-辛普森1-5级(74->155+mph)",
            6f, 240f, 80f, 600f, 1500f,
            "螺旋雨带、风眼墙、卫星云系旋转、狂风暴雨风暴潮",
            "1级损灌木→3级毁屋顶/风暴潮9-12英尺→5级建筑普遍失效、内涝"),
    TYPHOON_EXTRATROPICAL("温带气旋", Category.TROPICAL,
            "中纬度冷暖气团交汇的锋面波动（江淮气旋即此类）",
            "按中心气压与风力；可触发强对流与龙卷",
            12f, 120f, 100f, 800f, 2000f,
            "大范围层状云系、锋面雨带、持续大风",
            "大范围降雨降温，衍生龙卷/暴雪"),

    // ===== Winter precipitation =====
    BLIZZARD("暴风雪/雪暴", Category.WINTER,
            "强冷空气南下配合暖湿气流，低温+大风+降雪；吹雪使能见度骤降",
            "按能见度与降雪：吹雪(能见度<10km)/雪暴(<1km)；暴雪24h降雪≥10mm",
            2f, 72f, 30f, 300f, 1000f,
            "白毛风、能见度极低、积雪快速堆积、风卷雪粒横扫",
            "道路掩埋、交通瘫痪、低温冻伤、牲畜冻害"),
    ICE_STORM("冰冻雨/冰暴", Category.WINTER,
            "高空雪片落入近地面暖层融为雨滴，再进入贴地冷层成过冷雨，落地即冻",
            "按积冰厚度：轻微(<6mm)/中等(6-13)/严重(13-25)/灾难(>25mm)",
            1f, 48f, 20f, 200f, 600f,
            "雨滴落地成冰壳、树木电线裹冰、晶莹但危险的景象",
            "压断树枝电线致大范围断电、路面极滑、行人摔伤"),
    SLEET("雨夹雪", Category.WINTER,
            "雨滴穿过冷层凝固成冰粒(雪丸)后落地，不粘合",
            "按冰粒直径与持续时间",
            0.5f, 12f, 10f, 100f, 300f,
            "沙沙作响的小冰粒弹跳、地表湿滑",
            "路面结冰打滑、能见度下降"),

    // ===== Dust / visibility =====
    DUST_STORM("沙尘暴", Category.DUST,
            "强风卷起干旱裸露地表沙尘；冷锋大风+沙源+不稳定层结",
            "浮尘(能见度<10km)/扬沙(1-10km)/沙尘暴(<1km)/强沙尘暴(<500m)/特强(<50m黑风暴)",
            0.5f, 24f, 30f, 300f, 800f,
            "黄褐色天幕、太阳昏黄、空气中弥漫沙粒、能见度骤降",
            "掩埋农田道路、损害呼吸道、磨损机械、特强时可致火车停运"),
    SANDSTORM_MARS("火星尘暴(行星级)", Category.DUST,
            "太阳辐射加热使尘埃升入大气，正反馈使全球尘暴持续数周",
            "局地/区域/全球级；风速可达30m/s但火星大气稀薄",
            48f, 2400f, 100f, 2000f, 5000f,
            "整个天空橙红色、太阳变暗、全球性昏黄",
            "遮挡阳光影响太阳能设备、能见度极低（设定为稀有天象事件）"),

    // ===== Thermal extremes =====
    HEAT_WAVE("热浪/热穹顶", Category.THERMAL,
            " blocking高压脊长期滞留，下沉气流压缩增温+地表反馈形成热穹顶",
            "黄色(连续3天≥35℃)/橙色(24h≥37℃)/红色(24h≥40℃)",
            24f, 720f, 35f, 500f, 2000f,
            "烈日蒸腾、地表热浪扭曲、空气发烫、午后最烈",
            "中暑脱水、农作物灼伤、电力负载激增致火灾"),
    COLD_WAVE("寒潮/极寒", Category.THERMAL,
            "强冷高压南下，冷空气堆积使气温骤降",
            "寒潮(24h降温≥8℃且最低≤4℃)；严寒/酷寒按绝对低温",
            12f, 168f, 20f, 400f, 1500f,
            "呼气成雾、冰晶、霜冻、万物萧瑟",
            "冻伤、水管冻裂、农作物冻害、能耗激增"),

    // ===== Fire weather =====
    FIRE_WHIRL("火龙卷", Category.FIRE,
            "地表猛烈燃烧产生的强烈上升气流与周围风切变耦合旋转",
            "小火龙卷(<10m) / 火龙卷(10-50m高) / 火风暴(可跨越数百米)",
            0.05f, 1f, 0.02f, 0.2f, 15f,
            "火焰旋转成柱、浓烟遮天、飞火四溅、橙红火光",
            "点燃周围可燃物、高温灼伤、浓烟窒息"),
    WILDFIRE("野火/山火", Category.FIRE,
            "长期干旱+高温+大风，起火后风助火势快速蔓延",
            "按过火面积与火线强度；风驱型蔓延速度可达数km/h",
            1f, 720f, 30f, 500f, 3000f,
            "滚滚浓烟、橘红火墙、飞火、天空灰暗",
            "烧毁植被建筑、烟霾危害健康、破坏生态"),

    // ===== Hydrological =====
    FLASH_FLOOD("山洪/暴洪", Category.HYDRO,
            "短时强降水(≥80mm/h)或堰塞溃决，在陡峭地形快速汇集",
            "山洪灾害预警按雨量/水位；水流速度可达数m/s",
            0.1f, 12f, 20f, 150f, 500f,
            "浑浊激流暴涨、夹杂树木石块、轰鸣",
            "冲毁道路桥梁建筑、卷走人车，是致死率最高的洪灾类型"),
    RAINSTORM("特大暴雨", Category.HYDRO,
            "持续的暖湿输送+地形抬升或台风倒槽，产生长时间强降水",
            "暴雨(24h≥50mm)/大暴雨(≥100)/特大暴雨(≥250mm)",
            1f, 72f, 40f, 400f, 1200f,
            "倾盆大雨、天地连线、积水迅速上涨",
            "城市内涝、山体滑坡、泥石流、农田淹没");

    public enum Category { CONVECTIVE, TROPICAL, WINTER, DUST, THERMAL, FIRE, HYDRO }

    public final String name;
    public final Category category;
    public final String formation;      // 形成条件
    public final String scale;          // 强度分级
    public final float minDurationH;    // 最短持续(小时)
    public final float maxDurationH;    // 最长持续(小时)
    public final float coreRadiusBlocks; // 核心影响半径(方块)
    public final float outerRadiusBlocks; // 外围影响半径(方块)
    public final float spawnRarity;     // 生成稀有度(权重,越大越罕见)
    public final String visuals;        // 视觉与环境表现
    public final String impacts;        // 对人物/交通/建筑/场景的影响

    WeatherType(String name, Category category, String formation, String scale,
                float minDurationH, float maxDurationH, float coreRadiusBlocks,
                float outerRadiusBlocks, float spawnRarity, String visuals, String impacts) {
        this.name = name;
        this.category = category;
        this.formation = formation;
        this.scale = scale;
        this.minDurationH = minDurationH;
        this.maxDurationH = maxDurationH;
        this.coreRadiusBlocks = coreRadiusBlocks;
        this.outerRadiusBlocks = outerRadiusBlocks;
        this.spawnRarity = spawnRarity;
        this.visuals = visuals;
        this.impacts = impacts;
    }

    /** Random duration within the realistic range, in ticks (20 tps). */
    public int durationTicks() {
        float h = Mth.lerp(java.util.concurrent.ThreadLocalRandom.current().nextFloat(),
                minDurationH, maxDurationH);
        return Math.max(200, (int) (h * 36000));
    }
}
