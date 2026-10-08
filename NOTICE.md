# Wooden Cog: Wildfire — NOTICE / 归属说明

本仓库是 **[Wooden Cog](https://modrinth.com/mod/woodencog)**（mod id: `woodencog`）的非官方衍生构建，
为 **TFC_Wild_Fire** 整合包重新打包。

## 上游与协议

| 项目 | 信息 |
|---|---|
| 上游（现行维护） | https://github.com/Manwe03/woodencog （分支 `1.20.1-6.0.3`） |
| 上游（原始仓库） | https://github.com/Sparks-and-Rotation/woodencog （已归档） |
| Modrinth | https://modrinth.com/mod/woodencog |
| CurseForge | https://www.curseforge.com/minecraft/mc-mods/woodencog |
| 协议 | **MIT License** |
| 版权 | `Copyright (c) 2022 SlimeKnights` / `Copyright (c) 2023 ChauveDev` |
| 重建基线 | 版本 `1.2.17`，commit `c1f33478f7ce010e26c1546ff30519886eb1381b` |
| 目标平台 | Minecraft 1.20.1 / Forge 47.x |

MIT 授权允许使用、复制、修改、发布、分发、再许可与销售，条件是**在所有副本或实质性部分中保留版权声明和许可声明**。
上游协议全文见仓库根目录 [`LICENCE`](LICENCE)，并已随 jar 一起打包（`META-INF/LICENCE`）。

## 关于 "All rights reserved" 元数据的说明

上游在 Modrinth / CurseForge 发布的 jar，其 `META-INF/mods.toml` 里写着 Forge 模板的默认值
`license="All rights reserved"`。该值**与上游仓库的 `LICENCE` 文件（MIT）及 Modrinth 项目页声明的 MIT 相矛盾**，
属于上游作者未清理的模板残留。仓库中的 `LICENCE` 文件才是授权意思表示，因此本衍生构建在 `mods.toml` 中
把该字段改为 `license="MIT"`，与真实协议保持一致。

## 本衍生版所做的修改

### A. 品牌与协议（不改变行为）

1. `build.gradle`
   - `archivesBaseName`：`woodencog` → `woodencog_wildfire`（只影响产物 jar 文件名）
   - 为 `processResources` 设置 `duplicatesStrategy = EXCLUDE`：datagen 会产出一份只含占位名的
     `assets/woodencog/lang/en_us.json`，与手写的真名版本冲突；该设置让**手写资源优先**，
     与上游发布版 jar 的实际内容一致
2. `src/main/resources/META-INF/mods.toml`
   - `displayName`：`Wooden Cog` → `Wooden Cog: Wildfire`
   - `license`：`All rights reserved` → `MIT`（见上文说明）
   - `credits`：补充上游归属与非官方声明
   - `description`：追加衍生构建说明
3. 新增 `src/main/resources/META-INF/LICENCE`（上游 MIT 全文）与 `META-INF/NOTICE`，
   使必要声明随 jar 分发

### B. 代码改动

4. `compat/createmoreburners/CMBIntegrationImpl.java` —— **原生整合**
   Create More Burners 的 `moreburners:electric_burner` 原先由 Wildfires 模组的
   `WoodenCogMoreBurnerTemperatureMixin` 从外部注入：按 Create 档位阈值映射温度，并用
   TFC 的设备升温函数逐帧逼近。本构建把它实现为 woodencog 自己的代码（直接读
   `WoodenCogCommonConfigs`，不再需要反射），Wildfires 侧对应 mixin 已注释停用。
   其余 More Burners 方块保持原有的线性公式不变。

5. `item/WoodencogItems.java` —— **移除无用注册**
   上游在此为 TFC 的每一个金属物品注册了一个 "unfinished" 孪生物品
   （遍历 `TFCItems.METAL_ITEMS`，约 477 个，形如 `<tfc 路径>/unfinished`）。
   这些物品没有任何配方、标签、战利品表或创造标签引用，也没有语言条目，因此在游戏里会
   显示成原始键名（例如 `item.woodencog.metal.knife_blade.black_bronze.unfinished`），
   其中 240 个连模型都没有。本构建移除该注册，使其不再污染 JEI 物品列表。
   该项由上游 commit `a18dd48`（"New Test Block, Item"）引入，属测试残留。
   如需恢复，从上游 1.2.17 取回该循环即可。

### C. 第八档超级加热（新功能）

6. **木炭炉第八档（2300 ℃ 超级加热）** —— 由 Wildfires 迁入并原生化

   TFC 的共享方块状态属性 `heat_level` 上限为 7，因此木炭炉最热只能呈现"白热"外观，
   而坐在炉子上的设备（坩埚，以及经由 `mixin/heat/MixinBasinBlockEntity` 读取的 Create
   机器）无法区分"白热"和"真正超热"。本构建新增第 8 档：

   - `mixin/heat/MixinTFCBlockStateProperties.java`：把 TFC `heat_level` 属性的上限从
     7 提到 8。TFC 在同一个静态初始化块里声明了 26 个 `IntegerProperty`，因此该
     `@Redirect` 按**运行时属性名**判别，而不是按调用点判别。
   - `mixin/heat/MixinCharcoalForgeBlockEntity.java`：设备温度达到 2300 ℃ 时把点着的
     炉子提升到第 8 档。它同时把第 8 档以第 7 档的形式呈现给 TFC 自己的 ticker，使原版
     `Mth.clamp(..., 1, 7)` 既不与它争夺该值、也不会每 tick 多写一次方块更新；并且它是
     第 8 档唯一的写入方。因此超热状态是稳定的，不会闪烁。
   - `utils/CogUtil.java`：由档位反推温度的映射从 8 档改为 9 档（除数 6 → 7），
     于是"超热档"从第 7 档移到第 8 档。第 7 档依然是白热，只是不再被当作超热 —— 这是
     有意为之的改动。
   - 新增资源：`assets/tfc/blockstates/charcoal_forge.json`（TFC 原有 8 个 variant 加
     `heat_level=8`）、`assets/woodencog/models/block/charcoal_forge/heat_8.json`，
     以及配套的顶面贴图与其 `.mcmeta`。

   该功能原先由 Wildfires 模组从外部注入。现在 Wildfires 的
   `TFCBlockStatePropertiesMixin` 已注销、其内两处木炭炉热量注入已删除：
   `heat_level` 属性**只能有一个归属者** —— 两个模组同时扩展它会向同一个
   `IntegerProperty.create` 调用点装两条 redirect，两个写入方同时写同一个方块状态会让
   模型闪烁。Wildfires 自己的土窑（earthen oven）仍保留 `heat_level=8` 的 variant，
   现在依赖本模组提供扩展后的属性。

### D. 鼓风机加热上限与过热保护（由 Wildfires 迁入）

7. **鼓风机加热上限可配置 + 过热销毁守卫** —— 由 Wildfires 迁入并原生化

   Create 的鼓风机把物品加热到某个上限，本模组原先把这两个上限硬编码在
   `mixin/MixinFanProcessing.java` 里（背后是岩浆时 1700 ℃，背后是火/篝火时 200 ℃）。
   现在改为读取 `woodencog-common.toml` 的 `[woodencog.fan]`：

   - `blasting_temperature` 默认 **1500.0**（上游 1700.0）。1500 低于锻铁、生铁与各类钢的
     熔点（1535 / 1540），因此鼓风机不再"熔掉"它们；铜、青铜、黄铜、镍、黑钢仍照旧熔化。
   - `smoking_temperature` 默认 **600.0**（上游 200.0）。
   - `overheat_multiplier`（1.5）与 `no_recipe_ceiling`（1600.0）约束新的守卫
     `compat/create/FanOverheatGuard.java`，由 `mixin/MixinFanOverheat.java` 以
     priority 900 注入 Create 的 `FanProcessing`，先于本模组自己的 priority 1000 加热注入：

     - 带 `welding_temperature` 的物品，超过 `焊接温度 × overheat_multiplier` 即被销毁。
       该乘数不得低于 **1.25**：每个 TFC 金属的熔点都恰好是其焊接温度的 1.25 倍
       （最低为 silver 的 1.2497），低于此值会在金属来得及熔化之前就把它销毁。
     - 既无焊接温度、又无加热配方的物品本来会无限升温，因此超过 `no_recipe_ceiling` 即被销毁。
       由于岩浆鼓风机现在自己就停在 1500（< 1600），该条款只会对"进鼓风机之前就已经超过
       1600"的物品生效；设为 0.0 可完全关闭。
     - 物品未越线时两条注入都不取消，所以正常的加热、烹饪与熔化路径不受影响。

   顺带修正：掉落物注入原先在"TFC 加热配方当前无事可做"时也会取消 Create 自己的鼓风机处理。
   由于 `CallbackInfoReturnable.setReturnValue` 本身就会取消方法，结果是"同时带 TFC 热量数据与
   Create 洗涤配方"的物品落在地上永远洗不了，而同一件物品放在传送带/置物台上却正常
   （那条路径只在 TFC 加热配方真的改变物品时才取消）。现在掉落物注入只对岩浆与火鼓风机取消
   （本模组有意接管的情形）；水鼓风机只降温，随后把物品交回 Create，洗涤恢复正常。

   该守卫与它的两个数值原先由 Wildfires 从外部注入（`FanOverheatMixin`、`FanOverheatGuard`、
   `wildfires-fan-heat.toml`，默认 `overheatMultiplier` 1.3、`noRecipeCeiling` 1600.0，
   整合包把前者调到了 1.5）。因为所有被鼓风机加热的物品都要经过本模组，鼓风机现在只有
   一个归属者。Wildfires 侧 mixin 与配置注册均已停用，`wildfires-fan-heat.toml` 不再生成，
   已存在的也变成惰性文件。除上述两个上限、以及数值的读取位置之外，守卫行为没有变化。

   `blaze_burner_seething` 的默认值由 2000.0 改为 **2300.0**，与 Create 自身的超热温度、
   以及第 6 项中木炭炉提升到第 8 档的阈值一致。整合包配置本来就是 2300.0，所以实际游戏
   行为不变。

## modId 与兼容性

**modId 保持 `woodencog` 不变。** 未重命名任何 Java 包名、注册名、mixin 配置、资源或数据命名空间。
因此本构建是上游 woodencog 1.2.x 的直接替换品，**与已使用 woodencog 的存档兼容**。

> 注意：因为 modId 未变，本 jar **不能**与上游 woodencog 同时放入 `mods/` 目录，否则 Forge 会报重复 mod 冲突。

第 6 项扩展的是已有的方块状态属性，而不是新增方块。此前炉子只可能存 0–7，因此**现有存档
不会持有新属性无法表示的值，无需迁移**。反过来不成立：`heat_level=8` 必须被客户端识别，
所以**联机时每个客户端都要装本模组**；纯原版 TFC 客户端无法表示该档位，会加载方块状态
定义失败（日志记录 `Exception loading blockstate definition`，该方块丢失模型）。

## 非官方声明

本构建为个人/整合包自用衍生版本，**未获得上游作者的任何赞助、认可或背书**。

## 随附的第三方内容

| 来源 | 协议 | 说明 |
|---|---|---|
| Create | MIT | `assets/create/` 下 1 处覆盖贴图 |
| TerraFirmaCraft | EUPL-1.2 | **未再分发任何 TFC 代码与贴图**；jar 内 `data/tfc/` 仅为 woodencog 自行编写的 tags 与 `item_heats` 数据覆盖 |
| TerraFirmaCraft（兼容性覆盖） | EUPL-1.2 | `assets/tfc/blockstates/charcoal_forge.json`：模组无法向其它模组的 blockstate 追加 variant，只能整文件替换，故以 9 项表替换 TFC 的 8 项表。其中原有 8 项是未改动的模型引用、贴图也未改动，第 9 项指向本模组自己的模型 |
| Wildfires（作者自有模组） | — | 第 8 档顶面贴图 `assets/woodencog/textures/block/devices/charcoal_forge/8.png` 及其 `.mcmeta` 复制自 Wildfires 的 `earthen_oven_heat_8.png`。该图是 Wildfires 的原创素材（16×32、2 帧；TFC 第 7 档为 16×208、13 帧），由同一作者作为自有作品复用 |
| Wildfires（作者自有模组） | — | 鼓风机过热守卫的 Java 代码 `compat/create/FanOverheatGuard.java` 与 `mixin/MixinFanOverheat.java` 迁自 Wildfires 的 `first.wildfires.compat.create.FanOverheatGuard` 与 `first.wildfires.mixin.create.FanOverheatMixin`，仅改动读取两个上限的来源，mixin 保持同样的 priority；由同一作者作为自有作品复用 |
