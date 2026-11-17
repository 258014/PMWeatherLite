package com.stellar.pmwlite.client;

import com.stellar.pmwlite.PMWLite;
import net.minecraftforge.fml.ModList;

/**
 * Shader-ecosystem detection + capability negotiation.
 *
 * WHY THIS EXISTS
 * ---------------
 * PMWeather's original look comes from a Veil-driven full-screen raymarching pass that takes over
 * the whole post chain and is turned off whenever a shader pack is active (`replace: true`).
 * A 1.20.1 Forge port cannot do that: Oculus/Iris replaces the same post chain, so two systems
 * fighting over it produces exactly the "visual difference" this layer is meant to remove.
 *
 * The rule implemented here:
 *   - a shader pack is active  -> we add NOTHING to the post chain (the pack owns the atmosphere);
 *     the look is carried by shader-safe layers only (fog events where the pack honours them,
 *     GUI-space compositing, vanilla weather state, particles).
 *   - no shader pack           -> we additionally run our own PostChain haze pass.
 *
 * Everything is detected lazily and defensively: if a probe throws, we degrade to "no custom
 * post effect" instead of crashing. Detection is throttled because Iris' pack state is read on
 * the client thread every frame otherwise.
 */
public final class ShaderCompatLayer {

    /** What the client is actually running right now. */
    public enum Mode {
        /** Pure vanilla pipeline; our post pass is allowed. */
        VANILLA,
        /** Iris/Oculus installed but no pack selected; vanilla pipeline, our pass is allowed. */
        IRIS_NO_PACK,
        /** A shader pack owns the post chain; we must stay out of it. */
        SHADER_PACK_ACTIVE,
        /** OptiFine with shaders; same rule as SHADER_PACK_ACTIVE. */
        OPTIFINE_SHADERS
    }

    private static final int PROBE_INTERVAL_TICKS = 20;

    private static boolean irisPresent = false;
    private static boolean optifinePresent = false;
    private static boolean shaderPackActive = false;
    private static String packName = "";
    private static Mode mode = Mode.VANILLA;
    private static int probeTimer = 0;
    private static boolean probed = false;
    private static boolean loggedOnce = false;

    private ShaderCompatLayer() {}

    /** Cheap, cached; safe to call every frame. */
    public static Mode mode() {
        return mode;
    }

    /** True when a shader pack owns the post chain, so we must not run our own pass. */
    public static boolean shaderPackActive() {
        return shaderPackActive;
    }

    public static String packName() {
        return packName;
    }

    /**
     * Custom post-processing is allowed only when nobody else owns the pipeline.
     * Also gated by the user toggle in {@link ClientVisualState}.
     */
    public static boolean postEffectsAllowed() {
        return !shaderPackActive && ClientVisualState.postEffectsEnabled();
    }

    /**
     * Fog events are honoured by vanilla and by OptiFine, but Iris' shader pipeline computes its
     * own fog and ignores Forge's viewport events -- pushing fog there would be a no-op at best
     * and a double-fog at worst, so we skip it whenever a pack is active.
     */
    public static boolean fogEventsAllowed() {
        return !shaderPackActive;
    }

    /** GUI-space compositing runs through the normal 2D pipeline, so it is always safe. */
    public static boolean overlayAllowed() {
        return true;
    }

    /** Called once per client tick; throttled internally. */
    public static void tick() {
        if (!probed) {
            irisPresent = isLoaded("oculus") || isLoaded("iris");
            optifinePresent = isLoaded("optifine") || classExists("net.optifine.Config");
            probed = true;
        }
        if (++probeTimer < PROBE_INTERVAL_TICKS) return;
        probeTimer = 0;

        boolean active = false;
        String name = "";
        if (irisPresent) {
            // Iris/Oculus keep the selected pack in a static holder; read it reflectively so the
            // mod keeps working (and compiling) when neither is installed.
            Object v = invokeStatic("net.irisshaders.iris.Iris", "getCurrentPackName");
            if (v instanceof String s) {
                name = s;
                active = !s.isEmpty() && !"OFF".equalsIgnoreCase(s) && !"(internal)".equals(s);
            } else {
                Object b = invokeStatic("net.irisshaders.iris.Iris", "irisConfig");
                Object p = b == null ? null : invokeNoArg(b, "getShaderPackName");
                if (p instanceof java.util.Optional<?> opt && opt.isPresent()) {
                    name = String.valueOf(opt.get());
                    active = true;
                }
            }
        }
        if (!active && optifinePresent) {
            Object v = invokeStatic("net.optifine.Config", "isShaders");
            active = Boolean.TRUE.equals(v);
            if (active) name = "OptiFine";
        }

        shaderPackActive = active;
        packName = name;
        mode = active
                ? (optifinePresent && !irisPresent ? Mode.OPTIFINE_SHADERS : Mode.SHADER_PACK_ACTIVE)
                : (irisPresent || optifinePresent ? Mode.IRIS_NO_PACK : Mode.VANILLA);

        if (!loggedOnce) {
            loggedOnce = true;
            PMWLite.LOGGER.info("[PMWLite] 渲染兼容层: mode={} iris={} optifine={} pack='{}' -> 自定义后处理 {}",
                    mode, irisPresent, optifinePresent, packName,
                    postEffectsAllowed() ? "启用" : "交由光影处理");
        }
    }

    /** Human-readable one-liner for /pmwlite fx status. */
    public static String describe() {
        String who = switch (mode) {
            case SHADER_PACK_ACTIVE -> "光影包生效中 (" + packName + ")";
            case OPTIFINE_SHADERS -> "OptiFine 光影生效中";
            case IRIS_NO_PACK -> "已安装光影加载器，但未启用光影包";
            default -> "原版渲染管线";
        };
        return who + " §8| §7自定义后处理: "
                + (postEffectsAllowed() ? "§a启用" : "§c关闭（由光影接管）");
    }

    // ---------------- reflection helpers (never throw) ----------------

    private static boolean isLoaded(String modId) {
        try {
            return ModList.get() != null && ModList.get().isLoaded(modId);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, ShaderCompatLayer.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object invokeStatic(String className, String method) {
        try {
            Class<?> c = Class.forName(className, false, ShaderCompatLayer.class.getClassLoader());
            return c.getMethod(method).invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }
}
