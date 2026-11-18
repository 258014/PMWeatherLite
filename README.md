# PMWeatherLite

[English](README_EN.md) | **简体中文**

> Minecraft **Forge 1.20.1** 的极端天气模拟 mod —— 把「天气模拟」和「天气渲染」拆开：
> 模拟留在服务端，云和闪电交给光影包（Oculus / Iris）自己画。

[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-62B47A)](https://www.minecraft.net/)
[![Forge](https://img.shields.io/badge/Forge-47.2.0+-orange)](https://files.minecraftforge.net/)
[![Java](https://img.shields.io/badge/Java-17-blue)](https://adoptium.net/)
[![Version](https://img.shields.io/badge/version-0.3.0-lightgrey)]()

---

## 这是什么

一个**功能等价的独立重写**：保留 [PMWeather](https://github.com/ProtoManly) 的大气热力学与风暴生命周期模型，但把渲染层完全交给光影包。

与原版 mod 的最大差别是**没有一行后处理管线代码**：

| | PMWeather（原版） | PMWeatherLite（本项目） |
|---|---|---|
| 平台 | 1.21.1 NeoForge | **1.20.1 Forge** |
| 渲染 | 依赖 Veil 4.2.1，全屏 raymarching 体积云 | **不做渲染**，只驱动原版 `rain` / `thunder` 状态 |
| 光影兼容 | 与原版 `replace: true` 冲突 | **完全兼容**（云由光影包绘制） |
| 表现层 | 自定义 shader + 自定义实体 | 原版粒子 + 原版音效事件 + 可选后处理/叠加层 |

**为什么不直接移植**：PMWeather 只发布 1.21.1 NeoForge 版本，强依赖 Veil 库；1.20.1 + Forge 上既没有 Veil，也没有对应的渲染管线，而且它默认开启的 `replace: true` 会直接顶掉光影包的后处理。所以只能重写。

---

## 核心设计：模拟 / 渲染分离

```
        ┌─────────────────────────────────────────────┐
        │  模拟层（服务端，本 mod）                    │
        │  · 热力学探空  CAPE / CINH / 虚温 / 相态      │
        │  · 风场        FBM 背景风 + Rankine 涡旋      │
        │  · 风暴状态机  生长 / 触地 / 消亡             │
        │  · 破坏与火蔓延                               │
        └──────────────────┬──────────────────────────┘
                           │ 只写原版状态 + 原版粒子/音效事件
        ┌──────────────────▼──────────────────────────┐
        │  接口层                                      │
        │  setWeatherParameters(rain, thunder)         │
        └──────────────────┬──────────────────────────┘
                           │
        ┌──────────────────▼──────────────────────────┐
        │  表现层（光影包 / 原版）                      │
        │  Oculus + Complementary → 体积云、闪电照明    │
        └─────────────────────────────────────────────┘
```

这样做的直接好处：**任何**支持雨天/雷暴的光影包都能正确渲染这套天气，不需要为每个光影包写适配。

---

## 特性

### 18 种极端天气（按 7 个类别）

| 类别 | 类型 |
|---|---|
| 强对流 CONVECTIVE | 超级单体雷暴、龙卷风、飑线、下击暴流、冰雹 |
| 热带气旋 TROPICAL | 热带气旋/飓风、温带气旋 |
| 冬季 WINTER | 暴风雪、冰冻雨/冰暴、雨夹雪 |
| 沙尘 DUST | 沙尘暴、火星尘暴（行星级） |
| 温度 THERMAL | 热浪/热穹顶、寒潮/极寒 |
| 火 FIRE | 火龙卷、野火/山火 |
| 水文 HYDRO | 山洪/暴洪、特大暴雨 |

强度分级对齐现实标准：EF0–EF5（龙卷）、萨菲尔-辛普森（飓风）、CMA 预警色（热浪/寒潮）、QX/T 强对流等级。

### 热力学驱动的生成

不是纯随机刷天气。每次评估会采样一列「探空」：

1. 由生物群系温度/湿度 + 昼夜曲线得到地面气块；
2. 逐层抬升，算 **CAPE**（对流有效位能）、**CINH**（对流抑制）、3000 m 以下 CAPE；
3. 结合递减率、低层露点、SRH 得到 0–1.75 的生成风险；
4. 风险 < 0.35 时**不生成**（稳定大气不该有雷暴），风险越高风暴越强越宽。

### 4 种风暴类型

`SUPERCELL` / `SQUALL` / `CYCLONE` / `FIRE_WHIRL`，各自有不同的生长速率、降水系数、闪电频率与涡旋结构（含多涡旋子涡）。

### 龙卷破坏

触地龙卷按风速阈值卷起方块：每格算一次完整风场（背景 + Rankine 涡旋），超过阈值就变成带切向速度的下落方块实体。默认阈值表对齐原版：树叶 50–65、羊毛 60、玻璃 70、箱子 120，泥土/砂砾/基岩免疫。

### 音效与反馈

全部复用原版 `SoundEvent`（零 `.ogg` 资源）：龙卷触地警笛、极端天气预警、命令确认/拒绝音、静音开关。预警文案走 action bar，按 EF 等级和天气类型分色。

v0.2 新增 10 个**具名音效事件**（`alert.blue/yellow/orange/red`、`radar.ping`、`station.scan`、`fortify.apply` 等）。
它们的 id 由本 mod 持有、声音文件通过 `sounds.json` 的 `"type": "event"` 指向原版事件——
所以既能被子包/资源包单独替换，又不需要往 jar 里塞任何音频。

### 气象雷达（v0.2）

按 **R** 打开。服务端把 512 格内的风暴、天气事件、固定资产打成**一个**数据包（实测 132 字节）发过来，客户端只做渲染：

- 环形刻度用 `DynamicTexture` 程序化生成，不打包 PNG；
- 红点=风暴（按影响半径放大）、橙点=天气事件、青点=气象站；
- 右侧信息栏给预警等级、风险指数、ETA、置信度、可能天气类型、加固计数；
- 数据超过 2.5 s 未刷新会标「数据过期」，不会假装实时。

### 天气预报机与加固（v0.2）

| 物品 | 作用 |
|---|---|
| **天气预报机** | 放置后右键输出四行预报；256 格内把预报置信度 +10% 并标记「精报」；**免疫风暴破坏** |
| **加固套件** | 右键任意方块，抗风阈值 +15（上限 +60），每级消耗 1 点耐久（64 次） |

预报复用**与风暴生成完全相同的热力学公式**（`buildProfile` + `formationRisk`），所以是「提前看到将要发生的事」，
而不是事后播报。四级预警对齐 CMA 配色：风险 `0.35 / 0.65 / 0.95 / 1.25` → 蓝 / 黄 / 橙 / 红；
升级立即播报、≥黄色每 30 s 提醒一次、降级静默。

### 光影兼容层与后处理（v0.2）

这是本版本最需要小心的地方——**不能和光影抢后处理管线**。做法是分层 + 让路：

| 层 | 光影包生效时 | 无光影时 |
|---|---|---|
| 雾（`ViewportEvent`） | **关闭**（Iris 自己算雾） | 拉近远平面 + 向板岩灰/雪白偏色 |
| 后处理 `PostChain` | **关闭** | 雾霾 + 去饱和 + 暗角 + 闪电（两趟 pass） |
| 屏幕叠加层（2D 管线） | 只保留暗角（避免双份雨） | 暗角 + 雨幕 + 闪电白闪 + 强度徽标 |

三层各自独立、各自可关（`/pmwlite fx`、`/pmwlite hud`），任何一层失败都只回退到下一层，**不会黑屏、不会冲突**。

### 光影与后处理联动（本轮）

新增 `LightingState`：每客户端 tick 算一次太阳方向/强度/色温、环境色与阴影四参数
（强度 / 软化 / 量化分辨率 / 偏移），雾、后处理 pass、2D 合成层共用同一份快照。
后处理阶段固定为 **雾 → 空气光 → 阴影幕 → 风暴雾 → 闪电 → 暗角 → 拷回主缓冲 → 2D 合成**，
其中 A~E 五步合在已有那一个 pass 里，**总 pass 数仍是 2**。
光影包激活时 3D pass 完全让路，但 2D 光照幕继续跟着光照走；从不写光影包的 uniform。
`/pmwlite fx light` 可只读查看当前光照状态。详见 [09-光影与后处理联动](docs/09-光影与后处理联动.md)。

### 大气场：每格一份空气（v0.3）

v0.1/v0.2 的天气是「实体」——一个 `Storm` 对象带着 Rankine 涡旋在世界里飘，天气类型由噪声 + 概率决定。
v0.3 换成「场」：玩家周围 256×256×384 格被切成 **16×8×16 = 2048 个格子，每格独立持有一份空气**，
每 3 tick 解一次方程，破坏判定、降水、HUD、雷达全部从场里读数。

```
 全球环流 8×8 列 × 8 层（10 m → 16 km）   ← 地转风 / 热成风 / 风暴暖心低压注入
        ↓ 背景场 + 引导气流
 本地网格 16×16×8 格（每格 16×16×48 方块） ← 平流 / 浮力 / 科氏 / 静力学 / RBSOR 投影
        ↓ 近地面边界条件
 地形风    逐列（16 格）                  ← 遮蔽 / 狭管加速 / 卡门涡街
```

单步解的是真方程，不是查表：

| 步骤 | 做什么 |
|---|---|
| 静力学积分 | `p(y) = p_sfc·exp(−g·h/(R·Tv))` → 暖湿气柱高层高压，**气压梯度真的驱动风** |
| 浮力 | 虚温异常 + 水汽浮力 → 垂直速度 `w` |
| 半拉格朗日平流 | 温度/湿度/氧气/云量随风走，无条件稳定 |
| 动量 | `−∇p/ρ` + 底风松弛 + 地面摩擦 + 科氏偏转（纬度假设为 `z/3000×80°`） |
| RBSOR 投影 | 红黑超松弛解 `∇²φ = ∇·u`，**u/v/w 三个分量一起**修正，压掉散度 |

于是出现了只有场式模拟才有的现象：暖区是热低压、高层是高压、山谷背风侧风小、巷子口风大、
背风面有周期性的涡街摆动、风暴被 2000 m 的中层引导气流推着走（不是随机游走）。

**雷达从 1 张图变 7 层**（`1`..`7` 或 `←/→` 切换）：降水/云量、气压、气温、相对湿度、氧气、风场（带箭头）、
体感温度。实心地形画成透明，所以地下挖洞时雷达上会看到真实形状的空洞。HUD 常驻气压/气温/体感/云底/风/含氧量。

**体感温度**用两套官方经验公式而不是拍系数：风寒（NWS 2001，T ≤ 10 ℃）+ 热指数（Rothfusz，T ≥ 27 ℃），
云底用 `LCL = 125×(T−Td)`。**氧气**低于 14.5% 给挖掘疲劳、低于 8.5% 每 2 秒 1 点溺水伤害（可关）。

**5 个开关全关 = 逐位回到 v0.2**：`doAtmosphere` / `terrainWind` / `oxygenEffects` /
`cloudDrivenPrecipitation` / `globalSteering`。所有既有公开方法签名、记录字段、命令语义未变。

自检入口 `/pmwlite atmoscheck [步数]`（控制台可跑）：断言无 NaN、场值在物理区间、气压随高度严格递减、
平均散度 < 0.5 s⁻¹、八层模型风有界、地转风非零、六个经验公式参考点、风暴 40 tick 后仍存活。

---

## 环境要求

| 项 | 版本 |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.2.0 或更高 |
| Java | 17 |
| 可选光影 | Oculus 1.8.0 + Embeddium 0.3.31（任意光影包，推荐 Complementary / Cristal） |

## 安装

1. 安装 Forge 1.20.1-47.2.0；
2. 把 `PMWeatherLite-0.3.0.jar` 放进 `.minecraft/mods/`；
3. 启动即可，**无需任何配置**。有光影就开光影，没有就用原版天气渲染。

## 命令

服务端命令，需要 OP 权限（等级 2）：

| 命令 | 作用 |
|---|---|
| `/pmwlite list` | 按类别列出全部天气类型 |
| `/pmwlite weather <TYPE>` | 在当前位置触发指定天气（Tab 可补全） |
| `/pmwlite summon` | 在当前位置召唤超级单体 |
| `/pmwlite status` | 当前活动事件、风暴数量、剩余时间、静音状态 |
| `/pmwlite mute` | 切换个人天气音效静音 |
| `/pmwlite clear` | 清除全部天气与风暴 |
| `/pmwlite radar` | 推送一次雷达快照（等同按 R） |
| `/pmwlite forecast` | 打印当前位置预报 |
| `/pmwlite assets` | 固定资产 / 加固 / 气象站覆盖统计 |
| `/pmwlite reinforce` | 加固准星 6 格内的方块 |
| `/pmwlite atmos` | 打印脚下的大气读数（气压/气温/露点/湿度/氧气/云量/云底/风/体感） |
| `/pmwlite global` | 打印当前位置的八层大气剖面（气压/温度/云量/风速） |
| `/pmwlite atmoscheck [步数]` | 大气场自检：无 NaN、静力学单调、散度、地转风、经验公式参考点 |
| `/pmwlite hint` | 操作提示 |

客户端命令，**不需要 OP**，只在本地执行：

| 命令 | 作用 |
|---|---|
| `/pmwlite fx [on\|off\|status]` | 开关后处理管线（不影响雾与叠加层） |
| `/pmwlite hud [on\|off]` | 开关屏幕叠加层（雨幕/暗角/闪电） |

按键：**R** 打开气象雷达（可在「按键设置 → PMWeather Lite」改键）。

---

## 构建

```bash
git clone <this-repo>
cd PMWeatherLite
./gradlew build          # Windows: gradlew.bat build
# 产物：build/libs/PMWeatherLite-0.3.0.jar
```

首次构建会下载 ForgeGradle 与 Minecraft 依赖（约 1–2 分钟）。离线构建可加 `--offline`。

开发环境运行：

```bash
./gradlew runClient
```

---

## 性能

v4 做过一轮算法与分配优化，实测（单线程微基准，20,000 次循环）：

| 路径 | 优化前 | 优化后 |
|---|---|---|
| 热力学探空（17 层 + CAPE + 相态） | 4.2 µs · 1840 B | **2.5 µs · 504 B**（1.65×，−72.6%） |
| 龙卷破坏扫描（315 格风场） | 104.9 ms / 1000 次 | **12.0 ms**（8.8×） |
| 破坏扫描方块识别垃圾 | 18.9 KB / 次 | **0 B** |
| 每风暴每 tick 噪声采样 | 8 次 simplex | **4.2 次** |
| 桥接层 玩家×事件 遍历 | 3 趟 | **1 趟** |
| 强降水粒子网络包 | ~60 包/秒/玩家 | **~10 包/秒/玩家** |
| 大气场单步（v0.3，2048 格 + 8 次 RBSOR） | — | **690 µs / 步**，每 3 tick 一步 → 均摊 0.24 ms/tick（tick 预算的 0.5%） |

数值一致性：300 组随机探空柱对拍，相态与 CAPE **零偏差**。

构建是**可复现**的：两次 `./gradlew clean build` 产出的 jar 字节完全一致（`preserveFileTimestamps=false` + 固定 manifest 时间戳，支持 `SOURCE_DATE_EPOCH` 覆盖），任何人都能验证发布物确实由该源码构建。

细节见 [`docs/06-算法与性能优化-v4.md`](docs/06-算法与性能优化-v4.md)。

---

## 目录结构

```
src/main/java/com/stellar/pmwlite/
├── PMWLite.java                 主类：事件注册、命令、每 tick 调度
├── weather/
│   ├── Thermodynamics.java      探空剖面、CAPE/CINH、相态、生成风险
│   ├── NoiseField.java          Simplex + FBM
│   ├── WindField.java           背景风 + Rankine 涡旋
│   ├── Storm.java               风暴状态机（4 种类型）
│   ├── StormManager.java        风暴生命周期与生成门控
│   ├── Vorticy.java             子涡旋
│   ├── WeatherType.java         18 种天气的静态参数表
│   └── WeatherSystem.java       天气事件：生成、强度包络、影响范围
├── render/
│   ├── WeatherStateBridge.java  原版 rain/thunder 驱动 + 预警（光影兼容关键）
│   ├── PrecipitationSystem.java 降水粒子与相态缓存
│   └── TornadoFX.java           漏斗烟尘粒子
├── damage/
│   ├── TornadoDestroyHandler.java  方块卷起（含固定资产免疫 + 加固加成）
│   └── WildfireSystem.java         风驱火蔓延
├── sound/SoundManager.java      音效、静音、冷却
├── config/ServerConfig.java     生成概率、方块阈值表
│
├── atmos/                       v0.3 大气场
│   ├── AtmosphereGrid.java      本地网格：平流/浮力/科氏/静力学/RBSOR 投影
│   ├── GlobalModel.java         八层全球环流：地转风/热成风/风暴注入
│   ├── TerrainWind.java         地形风：遮蔽/狭管/卡门涡街
│   ├── AtmosphereManager.java   调度、节流、低氧后果、统一查询
│   ├── ApparentTemperature.java 风寒/热指数/露点/云底/比湿
│   └── AtmosphereSelfTest.java  物理不变量自检（/pmwlite atmoscheck）
│
├── registry/ModRegistry.java    音效 / 方块 / 物品 / 创造标签注册
├── asset/                       IFixedAsset + FixedAssetManager（按维度的固定资产登记表）
├── fortify/FortificationManager.java   加固加成表
├── forecast/                    ForecastEngine（预报）+ AlertManager（预警播报）
├── block/WeatherStationBlock.java      天气预报机
├── item/ReinforcementKitItem.java      加固套件
├── radar/RadarService.java      服务端雷达快照
├── network/                     SimpleChannel + 雷达收/发两个包
└── client/                      仅客户端：雷达 GUI、按键、光影兼容层、后处理、叠加层
```

---

## 文档

| 文件 | 内容 |
|---|---|
| [01-移植工程方案](docs/01-移植工程方案.md) | 可行性分析、路线选择、光影兼容论证 |
| [02-极端天气系统设计](docs/02-极端天气系统设计.md) | 18 种天气的形成条件、分级、影响范围 |
| [03-安装与使用](docs/03-安装与使用.md) | 安装、命令、配置说明 |
| [04-优化评估报告-v2](docs/04-优化评估报告-v2.md) | 第一轮性能/稳定性评估 |
| [05-音效与反馈-v3](docs/05-音效与反馈-v3.md) | 音效系统与交互反馈设计 |
| [06-算法与性能优化-v4](docs/06-算法与性能优化-v4.md) | 本轮算法与分配优化、基准数据、验证方式 |
| [07-v0.2 模块与验收](docs/07-v0.2-模块与验收.md) | v0.2：模块清单、实现范围、验收标准、实测记录 |
| [08-大气场-v3](docs/08-大气场-v3.md) | 三层场式模拟的方程、接线点、开关、自检与限制 |
| [09-光影与后处理联动](docs/09-光影与后处理联动.md) | **本轮**：阶段顺序、光照/阴影参数、联动实现与回归注意事项 |

---

## 已知限制

1. **破坏扫描固定 ±3 格盒**：不随风速自适应，弱龙卷也在扫同样大的区域。
2. **两套天气系统并存**：`StormManager`（物理风暴）与 `WeatherSystem`（18 类天气）各自算降水，桥接层取 max。
3. **RNG 混用** `Math.random()` 与 `level.random`：不影响性能，但同种子无法复现序列。
4. 相态缓存以 64 格柱 / 5 秒为单位，跨柱边界最多滞后 5 秒。
5. **加固不持久化**：`FortificationManager` 是内存表，服务器重启后清零（与天气状态一致）。
6. **天气预报机复用原版方块贴图**作为占位，尚未自绘材质。
7. **光影包内的雾不受本 mod 控制**：Iris 自算雾，本 mod 主动让路；差异只能靠叠加层与粒子弥补。

---

## 致谢与声明

- 算法思路与参数体系参考 **ProtoManly's Weather (PMWeather)** by ProtoManly。原项目为 **ARR** 授权。
- 本项目为**独立重写**：不含原项目的任何源码、shader、贴图或音频资源；所有代码基于 Forge 1.20.1 API 重新实现，音效全部复用原版 `SoundEvent`。
- 大气物理公式（Magnus 饱和水汽压、Bolton 虚温、CAPE 积分、Rankine 涡旋）属公开领域。
- 本项目同样以 **ARR** 发布；如需商用或二次分发，请自行评估上游授权。

---

## License

ARR (All Rights Reserved). 完整条款见 [LICENSE](LICENSE)。
