package com.stellar.pmwlite.registry;

import com.stellar.pmwlite.PMWLite;
import com.stellar.pmwlite.block.WeatherStationBlock;
import com.stellar.pmwlite.item.ReinforcementKitItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Central registration for everything this mod adds to the game.
 *
 * DESIGN NOTE (backward compatibility):
 *   v0.1.0 shipped zero registry entries -- every visual was a vanilla particle or sound event
 *   and every system was command-driven. All of that keeps working untouched; this class only
 *   ADDS objects. The existing public surface (StormManager / WeatherSystem / Thermodynamics /
 *   SoundManager.Sfx / ServerConfig) is not modified.
 */
public final class ModRegistry {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, PMWLite.MOD_ID);
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, PMWLite.MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, PMWLite.MOD_ID);

    // ---------------- sound events (backed by vanilla .ogg files, see assets/pmwlite/sounds.json)
    public static final RegistryObject<SoundEvent> STORM_AMBIENT = sound("storm_ambient");
    public static final RegistryObject<SoundEvent> TORNADO_ROAR = sound("tornado_roar");
    public static final RegistryObject<SoundEvent> HAIL_TICK = sound("hail_tick");
    public static final RegistryObject<SoundEvent> ALERT_BLUE = sound("alert.blue");
    public static final RegistryObject<SoundEvent> ALERT_YELLOW = sound("alert.yellow");
    public static final RegistryObject<SoundEvent> ALERT_ORANGE = sound("alert.orange");
    public static final RegistryObject<SoundEvent> ALERT_RED = sound("alert.red");
    public static final RegistryObject<SoundEvent> RADAR_PING = sound("radar.ping");
    public static final RegistryObject<SoundEvent> STATION_SCAN = sound("station.scan");
    public static final RegistryObject<SoundEvent> FORTIFY_APPLY = sound("fortify.apply");

    private static RegistryObject<SoundEvent> sound(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(
                new ResourceLocation(PMWLite.MOD_ID, name)));
    }

    // ---------------- blocks / items
    public static final RegistryObject<Block> WEATHER_STATION =
            BLOCKS.register("weather_station", WeatherStationBlock::new);

    public static final RegistryObject<Item> WEATHER_STATION_ITEM = ITEMS.register("weather_station",
            () -> new BlockItem(WEATHER_STATION.get(), new Item.Properties()));

    public static final RegistryObject<Item> REINFORCEMENT_KIT = ITEMS.register("reinforcement_kit",
            () -> new ReinforcementKitItem(new Item.Properties().stacksTo(16)));

    // ---------------- creative tab
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, PMWLite.MOD_ID);

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.pmwlite"))
            .icon(() -> new ItemStack(WEATHER_STATION_ITEM.get()))
            .displayItems((params, output) -> {
                output.accept(WEATHER_STATION_ITEM.get());
                output.accept(REINFORCEMENT_KIT.get());
            })
            .build());

    private ModRegistry() {}

    /** Called once from the mod constructor. */
    public static void init(net.minecraftforge.eventbus.api.IEventBus bus) {
        SOUNDS.register(bus);
        BLOCKS.register(bus);
        ITEMS.register(bus);
        TABS.register(bus);
    }
}
