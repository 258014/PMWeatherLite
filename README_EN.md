# PMWeatherLite

> An extreme-weather simulation mod for **Minecraft Forge 1.20.1** — it splits *simulation*
> from *rendering*: the physics stays on the server, the clouds and lightning are drawn by
> your shader pack (Oculus / Iris).

[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-62B47A)](https://www.minecraft.net/)
[![Forge](https://img.shields.io/badge/Forge-47.2.0+-orange)](https://files.minecraftforge.net/)
[![Java](https://img.shields.io/badge/Java-17-blue)](https://adoptium.net/)
[![Version](https://img.shields.io/badge/version-0.1.0-lightgrey)]()

**English** | [简体中文](README.md)

---

## What this is

A **functionally equivalent, independent reimplementation**: it keeps the atmospheric
thermodynamics and storm life-cycle model of [PMWeather](https://github.com/ProtoManly),
but hands the entire rendering layer over to the shader pack.

The key difference from the original: **not a single line of post-processing code**.

| | PMWeather (upstream) | PMWeatherLite (this project) |
|---|---|---|
| Platform | 1.21.1 NeoForge | **1.20.1 Forge** |
| Rendering | Veil 4.2.1, full-screen raymarched volumetric clouds | **No rendering**; only drives vanilla `rain` / `thunder` state |
| Shader compat | Conflicts with `replace: true` in most packs | **Fully compatible** (clouds come from the pack) |
| Visual layer | Custom shaders + custom entities | Vanilla particles + vanilla sound events (zero custom assets) |

**Why not a straight port**: PMWeather only ships for 1.21.1 NeoForge and hard-depends on
the Veil library. On 1.20.1 + Forge there is no Veil and no matching render pipeline — and
its default `replace: true` would override the shader pack's post-processing outright. So
a rewrite is the only option.

---

## Core design: simulation / rendering split

```
        ┌─────────────────────────────────────────────┐
        │  SIMULATION (server side, this mod)          │
        │  · Soundings     CAPE / CINH / virtual temp   │
        │  · Wind field    FBM background + Rankine     │
        │  · Storm FSM     growth / touchdown / decay   │
        │  · Destruction and fire spread                │
        └──────────────────┬──────────────────────────┘
                           │ writes vanilla state + particle/sound events only
        ┌──────────────────▼──────────────────────────┐
        │  INTERFACE LAYER                             │
        │  setWeatherParameters(rain, thunder)         │
        └──────────────────┬──────────────────────────┘
                           │
        ┌──────────────────▼──────────────────────────┐
        │  PRESENTATION (shader pack / vanilla)        │
        │  Oculus + Complementary → clouds, lightning  │
        └─────────────────────────────────────────────┘
```

The practical benefit: **any** shader pack that supports rain/thunder renders this weather
correctly. No per-pack adapter needed.

---

## Features

### 18 extreme weather types (7 categories)

| Category | Types |
|---|---|
| Convective | Supercell, Tornado, Squall line, Downburst, Hail |
| Tropical | Hurricane / Tropical cyclone, Extratropical cyclone |
| Winter | Blizzard, Ice storm, Sleet |
| Dust | Dust storm, Martian dust storm (planetary-scale) |
| Thermal | Heat wave / heat dome, Cold wave |
| Fire | Fire whirl, Wildfire |
| Hydro | Flash flood, Rainstorm |

Intensity grading follows real-world scales: EF0–EF5 (tornadoes), Saffir–Simpson
(hurricanes), CMA warning colours (heat/cold waves), QX/T convective levels.

### Thermodynamically driven spawning

Weather is not rolled at random. Each evaluation samples a vertical sounding:

1. Surface parcel from biome temperature/humidity plus the diurnal curve;
2. Lift it layer by layer to compute **CAPE**, **CINH** and low-level (≤3 km) CAPE;
3. Combine with lapse rate, low-level dewpoint and SRH into a 0–1.75 risk score;
4. Below **0.35 no storm forms** (a stable atmosphere should not produce thunderstorms);
   higher risk yields stronger, wider storms.

### 4 storm types

`SUPERCELL` / `SQUALL` / `CYCLONE` / `FIRE_WHIRL`, each with its own growth rate,
precipitation factor, lightning frequency and vortex structure (including multi-vortex
sub-vortices).

### Tornado destruction

A ground-touching tornado rips up blocks above a wind-speed threshold: every candidate
block gets a full wind evaluation (background + Rankine vortex), and blocks over the
threshold become falling-block entities with tangential velocity. Default thresholds
follow the original: leaves 50–65, wool 60, glass 70, chests 120; dirt/gravel/bedrock are
immune.

### Sounds and feedback

Everything reuses vanilla `SoundEvent`s (zero `.ogg` assets): touchdown siren, severe
weather alert, command confirm/deny, per-player mute. Alerts go to the action bar, coloured
by EF scale and weather type.

---

## Requirements

| Item | Version |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.2.0 or newer |
| Java | 17 |
| Optional shaders | Oculus 1.8.0 + Embeddium 0.3.31 (any pack; Complementary / Cristal recommended) |

## Installation

1. Install Forge 1.20.1-47.2.0;
2. Drop `PMWeatherLite-0.1.0.jar` into `.minecraft/mods/`;
3. Launch. **No configuration needed.** Works with or without shaders.

## Commands

OP level 2 required:

| Command | Effect |
|---|---|
| `/pmwlite list` | List every weather type, grouped by category |
| `/pmwlite weather <TYPE>` | Trigger a weather type at your position (tab-completable) |
| `/pmwlite summon` | Summon a supercell at your position |
| `/pmwlite status` | Active events, storm count, remaining time, mute state |
| `/pmwlite mute` | Toggle your personal weather-sound mute |
| `/pmwlite clear` | Clear all weather and storms |

---

## Building

```bash
git clone <this-repo>
cd PMWeatherLite
./gradlew build          # Windows: gradlew.bat build
# output: build/libs/PMWeatherLite-0.1.0.jar
```

The first build downloads ForgeGradle and Minecraft dependencies (1–2 minutes).
Add `--offline` for offline builds.

Dev runs:

```bash
./gradlew runClient
```

---

## Performance

The v4 pass focused on algorithms and allocation. Measured single-threaded, 20,000
iterations:

| Path | Before | After |
|---|---|---|
| Thermodynamic sounding (17 layers + CAPE + phase) | 4.2 µs · 1840 B | **2.5 µs · 504 B** (1.65×, −72.6%) |
| Tornado destruction scan (315-block wind field) | 104.9 ms / 1000 scans | **12.0 ms** (8.8×) |
| Block-identification garbage during scan | 18.9 KB / scan | **0 B** |
| Noise samples per storm per tick | 8 simplex | **4.2** |
| Bridge player×event passes per tick | 3 | **1** |

Numerical equivalence: 300 randomised sounding columns compared against the v3
implementation — **zero deviation** in precipitation phase and CAPE.

Details: [`docs/06-算法与性能优化-v4.md`](docs/06-算法与性能优化-v4.md) (Chinese).

---

## Project layout

```
src/main/java/com/stellar/pmwlite/
├── PMWLite.java                 Main class: event wiring, commands, per-tick schedule
├── weather/
│   ├── Thermodynamics.java      Sounding, CAPE/CINH, phase, formation risk
│   ├── NoiseField.java          Simplex + FBM
│   ├── WindField.java           Background wind + Rankine vortex
│   ├── Storm.java               Storm FSM (4 types)
│   ├── StormManager.java        Storm life cycle and spawn gating
│   ├── Vorticy.java             Sub-vortex
│   ├── WeatherType.java         Static parameter table for 18 weather types
│   └── WeatherSystem.java       Weather events: spawning, envelope, radius
├── render/
│   ├── WeatherStateBridge.java  Vanilla rain/thunder driver (the shader-compat key)
│   ├── PrecipitationSystem.java Precipitation particles and phase cache
│   └── TornadoFX.java           Funnel debris particles
├── damage/
│   ├── TornadoDestroyHandler.java  Block tearing
│   └── WildfireSystem.java         Wind-driven fire spread
├── sound/SoundManager.java      Sounds, mute, cooldowns
└── config/ServerConfig.java     Spawn probabilities, block threshold table
```

---

## Documentation

All design documents are currently in Chinese.

| File | Contents |
|---|---|
| [01-移植工程方案](docs/01-移植工程方案.md) | Feasibility study, route selection, shader-compat argument |
| [02-极端天气系统设计](docs/02-极端天气系统设计.md) | Formation conditions, grading, radius for all 18 types |
| [03-安装与使用](docs/03-安装与使用.md) | Installation, commands, config |
| [04-优化评估报告-v2](docs/04-优化评估报告-v2.md) | First performance/stability review |
| [05-音效与反馈-v3](docs/05-音效与反馈-v3.md) | Sound system and interaction feedback |
| [06-算法与性能优化-v4](docs/06-算法与性能优化-v4.md) | Algorithm/allocation work, benchmarks, verification |

---

## Known limitations

1. **Precipitation particles are still one packet per particle** — about 60 packets/s/player
   under heavy rain. Batching is pending (1.20.1 client packet semantics unconfirmed).
2. **The destruction scan uses a fixed ±3 block box** — it does not scale with wind speed.
3. **Two coexisting weather systems**: `StormManager` (physical storms) and `WeatherSystem`
   (18 types) each compute precipitation; the bridge takes the max.
4. **Mixed RNG** — `Math.random()` and `level.random` are both used, so a seed does not
   reproduce the same sequence.
5. The phase cache is keyed per 64-block column with a 5-second TTL, so crossing a column
   boundary can lag by up to 5 seconds.

---

## Credits and notices

- Algorithm concepts and parameter ranges are inspired by **ProtoManly's Weather
  (PMWeather)** by ProtoManly, which is distributed under an **All Rights Reserved** license.
- This project is an **independent reimplementation**: it contains none of the upstream
  source code, shaders, textures or audio. Every sound reuses a vanilla `SoundEvent`.
- The atmospheric physics used (Magnus saturation vapour pressure, Bolton virtual
  temperature, CAPE integration, Rankine vortex) is public-domain meteorology.
- Minecraft is a trademark of Mojang Studios. This project is not affiliated with or
  endorsed by Mojang Studios or the PMWeather author.

---

## License

ARR (All Rights Reserved). See [LICENSE](LICENSE) for the full terms.
