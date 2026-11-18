package com.stellar.pmwlite.client;

import com.stellar.pmwlite.atmos.LunarField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 光照状态快照 / Lighting state — 一个 tick 算一次，所有表现层共用同一份读数。
 *
 * 为什么需要它
 * ------------
 * 以前雾色写死板岩灰、后处理写死冷色、叠加层写死蓝白闪光：三处各自拍脑袋，
 * 于是"白天雷暴"和"黄昏雷暴"看起来一样，光影包和我们的后处理也谈不上联动。
 *
 * 现在把**光照事实**收敛到这里：太阳方向/强度/颜色来自世界时间与天气，
 * 环境色直接取原版天空色（所以生物群系、维度、时段都自动跟随），
 * 阴影四参数由太阳高度角与降水推导。雾、后处理、2D 叠加层只读这份快照，
 * 不会出现"雾说傍晚、后处理说正午"的分裂。
 *
 * 性能约定
 * --------
 * - tick() 只在客户端 tick（20 Hz）调用一次；render 路径只读静态 float，零分配；
 * - 与主世界模拟、网络包、服务端配置**完全无关**，不要在这里读任何服务端状态；
 * - 光影包激活时我们仍算这份快照：后处理让路给光影包，但 2D 合成层继续用它，
 *   这样"光照变化在后处理过程中正确生效"在两种渲染配置下都成立。
 *
 * 说明：{@link #shadowResolution} 是**本 mod 屏幕空间阴影幕的量化网格数**，
 * 不是 Iris/OptiFine 的 shadowMapResolution。我们从不写光影包的 uniform——
 * 那是它的所有权，抢了就会黑屏；我们只保证自己的合成层跟它的光照同步。
 */
public final class LightingState {

    // 太阳/环境
    /** 世界空间指向太阳的单位向量（x=东, y=天顶）。 */
    public static float sunX = 1f, sunY = 0f, sunZ = 0f;
    /** 相机空间太阳向量：xy 是屏幕方向，z>0 表示太阳在镜头前方。 */
    public static float screenSunX = 0f, screenSunY = 0f, screenSunFacing = 1f;
    /** 考虑云雨遮蔽后的有效日光强度 0..1。 */
    public static float sunlight = 1f;
    /** 环境光强度 0..1（从原版天空色推出）。 */
    public static float ambient = 1f;
    /** 日光颜色（黄昏偏暖，正午近白）。 */
    public static float sunR = 1f, sunG = 0.98f, sunB = 0.92f;
    /** 环境色（原版天空色）。 */
    public static float ambR = 0.6f, ambG = 0.7f, ambB = 0.9f;

    // 月亮 / v0.3.4（只读快照，由 LunarField + 服务端光学包共同驱动）
    /** 世界空间指向月亮的单位向量（与太阳相反）。 */
    public static float moonX = -1f, moonY = 0f, moonZ = 0f;
    /** 相机空间月亮向量；z>0 表示月亮在镜头前方。 */
    public static float screenMoonX = 0f, screenMoonY = 0f, screenMoonFacing = -1f;
    /** 月相 0..7（0=满月，4=新月），照度 0..1，月食 0..1，可用月光 0..1。 */
    public static int moonPhase = 0;
    public static float moonIllum = 0f, moonEclipse = 0f, moonlight = 0f;

    // 阴影（屏幕空间幕参数）
    /** 阴影幕强度 0..0.85：晴天正午最强，雷暴天被云摊薄。 */
    public static float shadowStrength = 0.4f;
    /** 边缘软化 0.06..0.45：太阳越低越软、雨雪天越软。 */
    public static float shadowSoftness = 0.12f;
    /** 暗部量化网格数（越大越细），固定值避免逐帧重建结构。 */
    public static float shadowResolution = 96f;
    /** 阴影幕相对屏幕中心的偏移，随太阳的屏幕方向反向移动。 */
    public static float shadowOffsetX = 0f, shadowOffsetY = 0f;

    // 时段派生量
    /** 白昼程度 0..1（用原版 skyDarken，自动适配维度/日食）。 */
    public static float daylight = 1f;
    /** 晨昏暖色权重 0..1。 */
    public static float dusk = 0f;

    private static final Vec3 UP = new Vec3(0.0, 1.0, 0.0);

    private LightingState() {}

    /** 由 {@code ClientEvents.onClientTick} 调用，不要在渲染线程里调用。 */
    public static void tick(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            reset();
            return;
        }

        float rain = level.getRainLevel(1f);
        float thunder = level.getThunderLevel(1f);

        // getSunAngle: 0=日出, π/2=正午, π=日落, 3π/2=午夜（单位已是弧度）
        float angle = level.getSunAngle(partialTick);
        float sy = (float) Math.sin(angle);
        float sx = (float) Math.cos(angle);
        sunX = sx;
        sunY = sy;
        sunZ = 0f;
        // 月亮与太阳严格对置；相/照度优先用服务端同源读数，未同步时用本地纯函数兜底。
        moonX = -sx;
        moonY = -sy;
        moonZ = 0f;
        long dayTime = level.getDayTime();
        if (ClientVisualState.moonSynced()) {
            moonPhase = ClientVisualState.moonPhase();
            moonIllum = ClientVisualState.moonIllum();
            moonEclipse = ClientVisualState.moonEclipse();
            moonlight = ClientVisualState.moonlight();
        } else {
            moonPhase = LunarField.phaseIndex(dayTime);
            moonIllum = LunarField.illumination(dayTime);
            moonEclipse = LunarField.eclipse(dayTime);
            moonlight = LunarField.moonlight(dayTime, rain, level.getRainLevel(1f));
        }

        daylight = Mth.clamp(1f - level.getSkyDarken(partialTick), 0f, 1f);
        dusk = Mth.clamp(1f - Math.abs(sy) / 0.40f, 0f, 1f) * daylight;

        // 云层遮蔽：雨越大，直射光越弱；雷暴再削一点，但保留足够亮度让地形可读
        float occlusion = Mth.clamp(1f - rain * 0.55f - thunder * 0.20f, 0.18f, 1f);
        sunlight = daylight * occlusion;

        // 正午近白，晨昏偏暖；夜间日光强度≈0，颜色不影响结果
        float warm = dusk * 0.85f;
        sunR = 1f;
        sunG = Mth.lerp(warm, 0.98f, 0.64f);
        sunB = Mth.lerp(warm, 0.92f, 0.36f);

        Vec3 sky = level.getSkyColor(mc.player == null ? Vec3.ZERO : mc.player.position(), partialTick);
        ambR = Mth.clamp((float) sky.x, 0f, 1f);
        ambG = Mth.clamp((float) sky.y, 0f, 1f);
        ambB = Mth.clamp((float) sky.z, 0f, 1f);
        float skyLuma = (ambR + ambG + ambB) / 3f;
        // 月光照明：只在夜间补一点冷色环境光；白昼 contribution 被 (1−daylight) 压到 0，
        // 因此不会改变现有白天的亮度标定，也不写光影包 uniform。
        ambient = Mth.clamp((0.22f + skyLuma * 0.78f) * (0.55f + daylight * 0.45f)
                + moonlight * 0.18f * (1f - daylight * 0.85f), 0f, 1f);

        // 阴影：晴天锐利，低太阳角/降水软化并摊薄
        shadowStrength = Mth.clamp(0.12f + 0.55f * sunlight, 0f, 0.85f);
        shadowSoftness = Mth.clamp(0.10f + dusk * 0.22f + rain * 0.12f + thunder * 0.05f, 0.06f, 0.45f);
        shadowResolution = 96f;

        // 相机空间投影：screen.xy 决定散射/阴影幕方向，screen.z 决定太阳是否入镜
        Entity cam = mc.getCameraEntity();
        Vec3 forward = cam == null ? new Vec3(0.0, 0.0, -1.0) : cam.getViewVector(partialTick);
        Vec3 right = forward.cross(UP);
        if (right.lengthSqr() < 1.0e-6) right = new Vec3(1.0, 0.0, 0.0);
        right = right.normalize();
        Vec3 up = right.cross(forward).normalize();
        Vec3 sun = new Vec3(sunX, sunY, sunZ);

        screenSunX = Mth.clamp((float) sun.dot(right), -1f, 1f);
        screenSunY = Mth.clamp((float) sun.dot(up), -1f, 1f);
        screenSunFacing = Mth.clamp((float) sun.dot(forward), -1f, 1f);
        // 月亮的相机空间投影（手写点积，避免每 tick 再 new 一个 Vec3）
        screenMoonX = Mth.clamp(moonX * (float) right.x + moonY * (float) right.y
                + moonZ * (float) right.z, -1f, 1f);
        screenMoonY = Mth.clamp(moonX * (float) up.x + moonY * (float) up.y
                + moonZ * (float) up.z, -1f, 1f);
        screenMoonFacing = Mth.clamp(moonX * (float) forward.x + moonY * (float) forward.y
                + moonZ * (float) forward.z, -1f, 1f);
        shadowOffsetX = -screenSunX * 0.006f;
        shadowOffsetY = -screenSunY * 0.006f;
    }

    /** 无世界时给一组"中性白天"读数，避免显示层读到上一维度的值。 */
    public static void reset() {
        sunX = 1f; sunY = 0f; sunZ = 0f;
        screenSunX = 0f; screenSunY = 0f; screenSunFacing = 1f;
        moonX = -1f; moonY = 0f; moonZ = 0f;
        screenMoonX = 0f; screenMoonY = 0f; screenMoonFacing = -1f;
        moonPhase = 0; moonIllum = 0f; moonEclipse = 0f; moonlight = 0f;
        sunlight = 0f;
        ambient = 1f;
        sunR = 1f; sunG = 0.98f; sunB = 0.92f;
        ambR = 0.6f; ambG = 0.7f; ambB = 0.9f;
        shadowStrength = 0f;
        shadowSoftness = 0.12f;
        shadowResolution = 96f;
        shadowOffsetX = 0f; shadowOffsetY = 0f;
        daylight = 0f;
        dusk = 0f;
    }

    /** /pmwlite fx light 的可读输出。 */
    public static String describe() {
        return String.format(
                "日光 §f%.0f%% §7(%0.2f,%0.2f,%0.2f)  §7环境 §f%.0f%%  §7晨昏 §f%.0f%%%n"
                        + "§7阴影 强度 §f%.2f §7软化 §f%.2f §7网格 §f%.0f §7偏移 §f(%+.3f,%+.3f)%n"
                        + "§7太阳 世界 §f(%.2f,%.2f) §7屏幕 §f(%.2f,%.2f,%+.2f)%n"
                        + "§7月亮 相 §f%d §7照度 §f%.0f%% §7月光 §f%.0f%% §7食 §f%.0f%%",
                sunlight * 100f, sunR, sunG, sunB, ambient * 100f, dusk * 100f,
                shadowStrength, shadowSoftness, shadowResolution, shadowOffsetX, shadowOffsetY,
                sunX, sunY, screenSunX, screenSunY, screenSunFacing,
                moonPhase, moonIllum * 100f, moonlight * 100f, moonEclipse * 100f);
    }
}
