package net.chauvedev.woodencog.block.electricstove;

import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 电阻炉（原电磁炉）的方块实体：存 Forge Energy、按玩家设定的温度加热、温度越高耗电越快。
 *
 * <h2>温度怎么设</h2>
 *
 * <p>用 Create 的 {@link ScrollValueBehaviour}，和创造马达调转速是同一套东西：玩家对着方块正面的
 * 数值框右键/滚轮就能调，调完会回调 {@link #onTargetChanged}。
 *
 * <h2>可调区间</h2>
 *
 * <pre>
 *   未升级： 500 ~ 2500 °C     （默认 1250）
 *   升级后：1000 ~ 3000 °C     （下限抬高，不是往下放）
 * </pre>
 *
 * <p>两档都是 200 格，所以进度条长度一致，装升级件不会让条忽然变长或变短。
 *
 * <h2>耗电曲线（二次方）</h2>
 *
 * <pre>
 *   cost = BASE_ENERGY_COST * (target / 1000)^2
 * </pre>
 *
 * <p>1000 度时正好是 {@link #BASE_ENERGY_COST}；2000 度是 4 倍，2500 度是 6.25 倍，3000 度是 9 倍。
 * 系数从目标温度算，不看当前实际温度——玩家设了 3000 就一直按 3000 付费，
 * 这样"设高一点"不会因为还没烧热就便宜一截。
 *
 * <h2>热档位怎么映射</h2>
 *
 * <p>Create 的鼓风机、大锅、焊接只认 {@link BlazeBurnerBlock.HeatLevel} 四档。电阻炉当前温度
 * （{@link #currentTemperature}）决定写进方块的档位：
 * <pre>
 *   &lt; 80           NONE        不发热
 *   80 ~ 199       SMOULDERING
 *   200 ~ 339      FADING
 *   &gt;= 340        KINDLED
 *   且温度 &gt;= SEETHING_THRESHOLD(1000)  → SEETHING  超级加热
 * </pre>
 * 换个说法：只要目标温度设到 340 以上、电也够，电阻炉就是个"炽热"热源；设到 1000 以上就是超级加热。
 *
 * <h2>什么时候加热</h2>
 *
 * <p>三个条件同时满足才加热、才扣电：方块有红石信号（{@code POWERED}）、电够本 tick 的耗电、
 * 目标温度大于 0。缺一个就停止加热并自然降温（{@link #COOLING_RATE}）。
 *
 * <p>实际温度向目标温度靠拢的速率是 {@link #HEATING_RATE} 度/tick（20 度/秒），
 * 所以从 0 烧到 3000 约需 150 秒；降温同样按 {@link #COOLING_RATE}。
 */
public class ElectricStoveBlockEntity extends SmartBlockEntity {

    // ────────────────────────────────────────────────────────────────
    //  可调参数（数值都在这里，改这里就够了）
    // ────────────────────────────────────────────────────────────────

    /**
     * 未升级时的温度下限。低于这个温度没意义（那些活儿篝火就能干），
     * 所以面板不让往下调，省得玩家得从 0 一路滚上来。
     */
    public static final int BASE_MIN_TEMPERATURE = 500;

    /** 装升级件之后的温度下限 —— 往上抬，不是往下放。 */
    public static final int UPGRADED_MIN_TEMPERATURE = 1000;

    /** 刚放下时的目标温度。 */
    public static final int DEFAULT_TEMPERATURE = 1250;

    /** 没装升级件时的最高温度。 */
    public static final int BASE_MAX_TEMPERATURE = 2500;

    /** 装了升级件时的最高温度。 */
    public static final int UPGRADED_MAX_TEMPERATURE = 3000;

    /** 耗电基准：1000 度时每 tick 耗多少 FE。实际耗电 = 本值 × (目标温度/1000)^2。 */
    /**
     * 诊断日志开关。
     *
     * <p>2026-11-18 加：老大反馈「升到目标温度后又降下来」，只看代码判断不出
     * 是 anyPowered 掉了、电不够、还是目标温度算错。打开后每 2 秒打一行，
     * 把参与 {@code hasPower} 判定的每个值都写进日志。
     *
     * <p>定位完把这里改成 false 就行，不影响功能。
     */
    public static final boolean DEBUG_STOVE = true;

    public static final int BASE_ENERGY_COST = 40;

    /** 电池容量（FE）。按 2500 度满载 250 FE/t 算，能撑 200 秒左右；3000 度满载 360 FE/t 则约 139 秒。 */
    public static final int ENERGY_CAPACITY = 50000;

    /** 最大输入功率（FE/t）。够 3000 度的 360 FE/t 加上余量。 */
    public static final int MAX_RECEIVE = 2000;

    /**
     * 加热速率：每 tick 升温多少度。
     *
     * <p>★ 2026-11-17 老大定：<b>每秒 +100 °C</b> ⇒ 每 tick +5 °C（20 tick = 1 秒）。
     * 原来写的是 20 度/tick（400 度/秒），太快了。
     */
    public static final double HEATING_RATE = 5.0D;

    /**
     * 冷却速率：每 tick 降温多少度。
     *
     * <p>★ 2026-11-17 老大定：<b>每秒 -50 °C</b> ⇒ 每 tick -2.5 °C。
     * 原来写的是 10 度/tick（200 度/秒），太快了。
     */
    public static final double COOLING_RATE = 2.5D;

    /**
     * 达到这个温度才算「超级加热」（SEETHING 档，也是最后一档贴图）。
     *
     * <p>★ 2026-11-17 老大改成 <b>2300 度</b>（原来是 1000）。
     *
     * <p>2300 这个数字和本模组其它地方保持一致，是有出处的：
     * <ul>
     *   <li>{@code MixinCharcoalForgeBlockEntity.WOODENCOG_SUPERHEATED_TEMPERATURE = 2300F}
     *       —— 木炭炉的超级加热阈值</li>
     *   <li>{@code WoodenCogCommonConfigs} 的 {@code blaze_burner_seething} 默认 2300</li>
     * </ul>
     * 现在电阻炉也统一用这个值，三处一致。
     */
    public static final double SEETHING_THRESHOLD = 2300.0D;

    /** 低于这个温度算不发热。 */
    public static final double NONE_THRESHOLD = 80.0D;

    // ────────────────────────────────────────────────────────────────

    private static final String KEY_ENERGY = "Energy";
    private static final String KEY_TARGET = "TargetTemperature";
    private static final String KEY_CURRENT = "CurrentTemperature";
    private static final String KEY_UPGRADED = "Upgraded";
    private static final String KEY_STANDALONE = "Standalone";

    /**
     * 是否被扳手拆成了「独立炉子」。
     *
     * <p>独立炉子不参与组：不收组池的电、不和别人共享温度，自成一组。
     * 扳手右键一次拆开、再右键一次加回去。
     *
     * <p>★ 和 {@code upgraded} 不同，这个状态<b>只存 NBT</b>，不放进方块状态。
     * 理由是它不影响外观（模型不变），放进方块状态只会多出 2 倍变体。
     */
    private boolean standalone = false;

    public boolean isStandalone() {
        return standalone;
    }

    /**
     * 扳手切换独立状态。
     *
     * @return 切换后的新状态
     */
    public boolean toggleStandalone() {
        this.standalone = !this.standalone;
        setChanged();
        sendData();
        return this.standalone;
    }

    /** 电池。接收来自外部，不允许抽走（电器不是电池）。 */
    private final StoveEnergy energy = new StoveEnergy();

    /**
     * 电阻炉自己的电池。
     *
     * <p>为什么是具名类而不是匿名类：{@link #consumeInternal} 是内部用的方法，
     * 匿名类的方法在 {@code EnergyStorage} 类型的变量上看不见（编译报「找不到符号」）。
     */
    private final class StoveEnergy extends EnergyStorage {
        StoveEnergy() {
            super(ENERGY_CAPACITY, MAX_RECEIVE, 0, 0);
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int received = super.receiveEnergy(maxReceive, simulate);
            if (received > 0 && !simulate) {
                ElectricStoveBlockEntity.this.setChanged();
            }
            return received;
        }

        /**
         * 内部扣电。★ 不能走 Forge 的 extractEnergy。
         *
         * <p>构造时 maxExtract = 0（电器不对外放电），而 Forge 的实现是
         * {@code if (!canExtract()) return 0;}，canExtract() 就是 maxExtract > 0。
         * ⇒ maxExtract == 0 时 extractEnergy 永远返回 0、一格电都不扣。
         *
         * <p>原来 tickAsLeader 里写的是 energy.extractEnergy(cost, false)，
         * 以为扣了、实际一点没扣，电量永远满格。
         */
        void consumeInternal(int amount) {
            if (amount <= 0) {
                return;
            }
            int cur = getEnergyStored();
            if (cur <= 0) {
                return;
            }
            int take = Math.min(cur, amount);
            deserializeNBT(IntTag.valueOf(cur - take));
            ElectricStoveBlockEntity.this.setChanged();
        }
    }

    private final LazyOptional<IEnergyStorage> energyHandler = LazyOptional.of(() -> new IEnergyStorage() {
        /**
         * 组内的电统一进【组长】的池子。
         *
         * <p>★ 多根电线可以同时喂 —— 每个接线面都会走这里一次，各自把电充进同一个池子，
         * 就是简单相加，不需要去重（每根线的电流本来就该都算数）。
         */
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            ElectricStoveBlockEntity target = energyOwner();
            if (target != ElectricStoveBlockEntity.this) {
                return target.energy.receiveEnergy(maxReceive, simulate);
            }
            return ElectricStoveBlockEntity.this.energy.receiveEnergy(maxReceive, simulate);
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            // 电阻炉是纯消耗端，不对外放电。
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return energyOwner().energy.getEnergyStored();
        }

        @Override
        public int getMaxEnergyStored() {
            return ENERGY_CAPACITY;
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    });

    /**
     * 这个炉子的电该记在谁身上 —— 组里就记组长，独立就记自己。
     *
     * <p>每次查询都重算组，不缓存：组会随放/拆方块和扳手切换变化，缓存容易漏失效。
     */
    private ElectricStoveBlockEntity energyOwner() {
        if (standalone || level == null || level.isClientSide) {
            return this;
        }
        List<ElectricStoveBlockEntity> group = StoveGroup.collect(level, worldPosition);
        return StoveGroup.leaderOf(group);
    }

    /** 温度调节行为，和创造马达的转速调节同一种东西。 */
    public StoveTemperatureBehaviour targetTemperature;

    /** 玩家设定的目标温度，1~2500 或 1~3000。 */
    private int targetTemperatureValue = DEFAULT_TEMPERATURE;

    /** 当前实际温度，向目标靠拢。 */
    private double currentTemperature = 0.0D;

    /** 是否装了升级件。 */
    /**
     * 是否已升级。
     *
     * <p>★★ <b>唯一真源是方块状态 {@code UPGRADED}，不是这个 NBT 字段。</b>
     *
     * <p>2026-11-05 踩过的坑：一开始把升级状态同时存在两处 —— 方块状态里的 {@code UPGRADED}
     * 属性，和方块实体里这个 {@code upgraded} 字段（写进 NBT）。看起来冗余无害，实则致命：
     * <ul>
     *   <li>{@link #setUpgraded} 用 {@code level.setBlock(pos, state, 2)} 改方块状态。
     *       flag 2 只是"通知客户端"，<b>不会重建方块实体</b>，所以 BE 里那个字段是旧的；</li>
     *   <li>而存档里存的是 BE 那个旧字段，重启后 {@code read()} 把它读回来，
     *       于是出现「方块状态写着 upgraded=true，BE 却认为自己是 false」的分裂；</li>
     *   <li>后果就是老大看到的：模型已经变成升级外观了，但温度面板还是未升级区间。</li>
     * </ul>
     *
     * <p>修法是把 NBT 字段降级成<b>镜像</b>：{@link #isUpgraded()} 永远读方块状态，
     * 字段只在 {@code read()} 里被动同步（给那些还没重建完成的瞬间兜个底）。
     * 这样两者不可能再不一致。
     */
    private boolean upgraded = false;

    /** 从方块状态读，不信任内存字段。 */
    private void syncUpgradedFromState() {
        BlockState state = getBlockState();
        if (state != null && state.hasProperty(ElectricStoveBlock.UPGRADED)) {
            this.upgraded = state.getValue(ElectricStoveBlock.UPGRADED);
        }
    }

    /** 上一 tick 写进方块的热档位，只在变化时才 setBlock，避免每 tick 刷方块更新。 */
    private BlazeBurnerBlock.HeatLevel lastHeatLevel = BlazeBurnerBlock.HeatLevel.NONE;

    /** 上一 tick 写进方块的贴图档位（0~8）。和上面同理，用于避免无谓的 setBlock。 */
    private int lastTier = 0;

    public ElectricStoveBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ────────────────────────────────────────────────────────────────
    //  行为注册
    // ────────────────────────────────────────────────────────────────

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // 和创造马达的转速调节是同一个组件：右键/滚轮改数值，回调里收到新值。
        // 数值框挂在方块正面的中心，并且负责升级后的灰色（见 ElectricStoveValueBox）。
        ValueBoxTransform transform = new ElectricStoveValueBox();

        // ★ 用 StoveTemperatureBehaviour 而不是原生 ScrollValueBehaviour：
        //   原生那个的右键面板把行名写死成 "Value"、并且只打印刻度（少一个 0）。
        //   详见该类自己的注释。
        targetTemperature = new StoveTemperatureBehaviour(
            Component.translatable("woodencog.electric_stove.temperature"),
            this,
            transform
        );
        // ★ 建 behaviours 之前先从方块状态同步一次升级标记：
        //   方块实体可能是从方块状态（而不是 NBT）建起来的，这时候字段还是默认的 false。
        syncUpgradedFromState();

        // ★★ 2026-11-17 修「第一次打开方向时温度不匹配」：
        //   旧存档里可能没有 KEY_TARGET（更早的版本），这时 targetTemperatureValue 用的是
        //   DEFAULT_TEMPERATURE(1250)。但如果存档里读出来的值越界（比如老的 2000 上限配了 2500），
        //   夹完就和面板显示对不上。
        //   这里在建面板之前先把字段本身夹进当前合法区间，保证「字段 ↔ 面板刻度」从一开始就一致。
        this.targetTemperatureValue = ticksToTemperature(
            clampTicks(temperatureToTicks(this.targetTemperatureValue)));
        // ★ 这里 between 的是【刻度】不是温度，见下面 DEGREES_PER_TICK 的注释。
        //   ★★ 区间要按【当前升级状态】取，不能写死 baseMin/base —— 否则读档后
        //      已升级的炉子会从未升级区间起步，得等下一次 setUpgraded 才纠正过来。
        targetTemperature.between(
            isUpgraded() ? upgradedMinTicks() : baseMinTicks(),
            isUpgraded() ? upgradedTicks() : baseTicks()
        );
        // ★★ 2026-11-17 修「初始显示 500 度」：
        //
        //   原来这里是直接赋值 `targetTemperature.value = clampTicks(...)`。
        //   那样会绕过 ScrollValueBehaviour.setValue()，而 setValue 里才有的
        //   「值变了就调 callback」这一步就没跑 —— 面板拿到的还是构造时的默认值。
        //
        //   反编译确认（Create 6.0.8，ScrollValueBehaviour.setValue 字节码）：
        //       18: if_icmpne 22    ← 值相同就 return，不调 callback
        //       22: putfield value
        //       27..35: callback.accept(新值)
        //
        //   ⇒ 现在改成走 setValue()，统一从这一条路进 onTargetChanged。
        //
        // ★★ 顺序也很重要：withFormatter / withCallback 必须在 setValue 之前装好，
        //   否则这次 setValue 触发的回调落不到我们自己身上（callback 那时还是默认的空 lambda）。
        targetTemperature.withFormatter(v -> formatTicks(v == null ? 0 : v.intValue()));
        targetTemperature.withCallback(this::onTargetChanged);
        targetTemperature.setValue(clampTicks(temperatureToTicks(targetTemperatureValue)));
        behaviours.add(targetTemperature);
    }

    /** 把刻度夹到当前（未升级/已升级）的合法区间里。 */
    private int clampTicks(int ticks) {
        int lo = isUpgraded() ? upgradedMinTicks() : baseMinTicks();
        int hi = isUpgraded() ? upgradedTicks() : baseTicks();
        return Math.max(lo, Math.min(hi, ticks));
    }

    /** 玩家改了目标温度（收到的是刻度，换算成 °C）。 */
    private void onTargetChanged(Integer newTicks) {
        // 再夹一次：万一从别处（指令、旧存档）传来越界的刻度，这里兜住。
        this.targetTemperatureValue = ticksToTemperature(clampTicks(newTicks == null ? 0 : newTicks));
        setChanged();

        // ★★ 2026-11-17 老大反馈「调整一个的温度，其他电阻炉没跟着变」：
        //   组内目标温度也要同步。这里把新目标广播给同组的其它炉子。
        //   组员收到后只改自己的目标字段和面板刻度，<b>不再回调</b>（否则会来回刷）。
        if (!standalone && level != null && !level.isClientSide) {
            propagateTargetToGroup(this.targetTemperatureValue);
        }
    }

    /** 把一个目标温度写进同组其它炉子（不触发它们的回调）。 */
    private void propagateTargetToGroup(int targetCelsius) {
        for (ElectricStoveBlockEntity be : StoveGroup.collect(level, worldPosition)) {
            if (be == this) {
                continue;
            }
            be.applySharedTarget(targetCelsius);
        }
    }

    /**
     * 组员接收组长广播的目标温度。
     *
     * <p>★ 只改字段和面板刻度，<b>不走 setValue 的 callback</b> ——
     * 否则组员会再把值广播回去，形成循环。
     */
    void applySharedTarget(int targetCelsius) {
        if (this.targetTemperatureValue == targetCelsius) {
            return;
        }
        this.targetTemperatureValue = targetCelsius;
        if (targetTemperature != null) {
            targetTemperature.value = clampTicks(temperatureToTicks(targetCelsius));
        }
        setChanged();
        sendData();
    }

    // ────────────────────────────────────────────────────────────────
    //  面板刻度 ↔ 温度
    // ────────────────────────────────────────────────────────────────
    //
    // ★ 为什么不用 1~2000 直接当 between 的范围（2026-11-05 踩过的坑）：
    //   Create 的 ScrollValueBehaviour.createBoard 是
    //       new ValueSettingsBoard(label, max, 10, ...)
    //   第二个参数 max 直接决定面板的【格子总数】，也就是那条进度条有多长。
    //   创造马达 between(-256, 256) ⇒ 面板 256 格，正好铺满方块上方；
    //   如果直接 between(1, 2000) ⇒ 2000 格 ⇒ 条比整个屏幕还长（老大实测就是这样）。
    //   所以这里把刻度压到 0~250（每格 10 °C），和创造马达同量级。
    //   升级后刻度放到 0~300，多出来的 50 格就是"右边多接一段"。

    /** 一格代表多少 °C。 */
    public static final int DEGREES_PER_TICK = 10;

    // ── 面板刻度的上下限 ──
    //
    // 面板的 between(min, max) 决定两件事：
    //   · max - min = 进度条有几格（条有多长）
    //   · 玩家能滚到的范围
    // 所以"未升级 500~2500"是 200 格、"升级后 1000~3000"也是 200 格 ——
    // 两种状态下条一样长，不会出现装个升级件条突然变短/变长。
    // ★ 这里刻意让升级后的下限【抬高】到 1000：老大要的是"升级件换的是高档位区间"，
    //   而不是"能往下调更低"。所以装上升级件后，500~999 这段就不再可选。

    /** 未升级时的刻度下限：50 格 × 10 = 500 °C。 */
    public static int baseMinTicks() {
        return BASE_MIN_TEMPERATURE / DEGREES_PER_TICK;
    }

    /** 未升级时的刻度上限：250 格 × 10 = 2500 °C。 */
    public static int baseTicks() {
        return BASE_MAX_TEMPERATURE / DEGREES_PER_TICK;
    }

    /** 升级后的刻度下限：100 格 × 10 = 1000 °C。 */
    public static int upgradedMinTicks() {
        return UPGRADED_MIN_TEMPERATURE / DEGREES_PER_TICK;
    }

    /** 升级后的刻度上限：300 格 × 10 = 3000 °C。 */
    public static int upgradedTicks() {
        return UPGRADED_MAX_TEMPERATURE / DEGREES_PER_TICK;
    }

    public static int temperatureToTicks(int temperature) {
        return Math.max(0, temperature) / DEGREES_PER_TICK;
    }

    public static int ticksToTemperature(int ticks) {
        return Math.max(0, ticks) * DEGREES_PER_TICK;
    }

    /** 面板上显示的文字：`1200 °C`。 */
    private String formatTicks(int ticks) {
        return ticksToTemperature(ticks) + " °C";
    }

    // ────────────────────────────────────────────────────────────────
    //  温度上限（受升级影响）
    // ────────────────────────────────────────────────────────────────

    public int getMaxTemperature() {
        return isUpgraded() ? UPGRADED_MAX_TEMPERATURE : BASE_MAX_TEMPERATURE;
    }

    /**
     * 是否已升级 —— 以方块状态为准。
     *
     * <p>所有需要判断升级状态的地方（上限、面板范围、外观）都走这里，
     * 不要直接读 {@code upgraded} 字段，那个可能落后于方块状态。
     */
    public boolean isUpgraded() {
        BlockState state = getBlockState();
        if (state != null && state.hasProperty(ElectricStoveBlock.UPGRADED)) {
            this.upgraded = state.getValue(ElectricStoveBlock.UPGRADED);
        }
        return this.upgraded;
    }

    /**
     * 装上升级件。已装过就返回 false（不重复吃物品）。
     *
     * <h2>升级件把可调区间整体上移</h2>
     *
     * <pre>
     *   未升级： 500 ~ 2500 °C   （50 ~ 250 刻度，200 格）
     *   升级后：1000 ~ 3000 °C   （100 ~ 300 刻度，200 格）
     * </pre>
     *
     * <p>不是"放宽上限"，是<b>换到高一段的区间</b>：下限从 500 抬到 1000，上限从 2500 抬到 3000。
     * 两种状态都是 200 格，所以进度条长度一致。
     *
     * <p>两种情况都要处理：
     * <ul>
     *   <li>当前温度低于新下限（比如原本在 700，装上升级件后下限变成 1000）→ 抬到 1000；</li>
     *   <li>当前温度高于新上限 → 压到 3000。</li>
     * </ul>
     */
    public boolean setUpgraded(boolean value) {
        // ★ 用 isUpgraded()（读方块状态），不要用字段 —— 字段可能是旧的。
        if (isUpgraded() == value) {
            return false;
        }
        this.upgraded = value;
        int loTicks = value ? upgradedMinTicks() : baseMinTicks();
        int hiTicks = value ? upgradedTicks() : baseTicks();
        if (targetTemperature != null) {
            targetTemperature.between(loTicks, hiTicks);
            // 夹进新区间（下钳和上钳都要做，否则原本 700 度的炉子装上升级件会停在区间外）。
            targetTemperature.value = clampTicks(targetTemperature.value);
            targetTemperatureValue = ticksToTemperature(targetTemperature.value);
        }
        // 外观跟着升级状态变。
        // ★ 同样必须用 flag 2，不能用 setBlockAndUpdate —— 否则装了升级件之后
        //   方块实体当场被重建，刚设好的刻度上限和温度全丢。
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            if (state.hasProperty(ElectricStoveBlock.UPGRADED)
                && state.getValue(ElectricStoveBlock.UPGRADED) != value) {
                level.setBlock(worldPosition, state.setValue(ElectricStoveBlock.UPGRADED, value), 2);
            }
        }
        setChanged();
        sendData();
        return true;
    }

    // ────────────────────────────────────────────────────────────────
    //  Tick
    // ────────────────────────────────────────────────────────────────

    /**
     * 每 tick 的入口。{@link ElectricStoveBlock#getTicker} 直接调它。
     *
     * <p>★ 这里必须调 {@code super.tick()}：{@code SmartBlockEntity} 靠它驱动
     * behaviours（ScrollValueBehaviour 的同步、tick）、{@code setChanged} 的批量提交、
     * 以及客户端/服务端的 read-write 往返。少了这一行，温度数值框不会刷新，
     * 电量也不会同步给客户端。
     */
    @Override
    public void tick() {
        super.tick();
        if (level == null) {
            return;
        }
        if (level.isClientSide) {
            // 客户端只等同步，不自己算。
            return;
        }
        this.tickServer();
    }

    /**
     * 服务端：算电、升温、刷新热档位、把热推给上方的容器。
     *
     * <h2>组的处理方式（2026-11-17 老大定）</h2>
     *
     * <p><b>只有组长算温度和扣电</b>，组员只把组长的结果抄过来。
     * 这样 N 台炉子每 tick 只做一次 DFS、只算一次温度，不会平方级放大。
     *
     * <p>★ 走的是「组长优先」而不是「每台各算各的再取平均」：
     * 老大要的是<b>组内 currentTemperature 永远相等</b>，由一个人算、其他人抄，
     * 才能保证严格相等（各自算会有浮点漂移，一分钟后就对不上了）。
     */
    public void tickServer() {
        if (level == null || level.isClientSide) {
            return;
        }

        List<ElectricStoveBlockEntity> group = StoveGroup.collect(level, worldPosition);
        ElectricStoveBlockEntity leader = StoveGroup.leaderOf(group);

        if (leader == null || leader != this) {
            // 组员的温度由组长在 tickAsLeader 里直接写过来，这里不做事。
            // ★ 为什么不让组员自己去读组长：同一 tick 内谁先跑不确定，
            //   组员先跑就会抄到上一 tick 的值，组内温度会永远差一 tick、并且每台之间也可能不同步。
            return;
        }

        tickAsLeader(group);
    }

    /**
     * 刚放下时，从相邻组校准自己的状态。
     *
     * <h2>老大 2026-11-17 要求</h2>
     *
     * <p>「刚放下的电阻炉应该自动被旁边的电阻炉校准温度，红石信号也要跟着传播」
     *
     * <p>具体做三件事：
     * <ol>
     *   <li><b>目标温度</b>：抄相邻组的（否则新放的是默认 1250，和旁边不一样）；</li>
     *   <li><b>实际温度</b>：也抄过来 —— 新放下的炉子立刻和组同温，
     *       不用等好几秒慢慢升/降上去；</li>
     *   <li><b>红石供电</b>：如果相邻炉子有红石信号，自己也接上
     *       （炉子之间不像导线那样传红石，但既然算一组，供电状态也该一致）。</li>
     * </ol>
     *
     * <p>★ 只在<b>刚放下</b>时调用一次，不是每 tick —— 否则玩家手动改温度会被立刻纠正回去。
     */
    public void calibrateFromNeighbours() {
        if (level == null || level.isClientSide) {
            return;
        }

        // 收集相邻的水平炉子（此时自己还没加入任何组，用方向直接找）
        ElectricStoveBlockEntity source = null;
        for (Direction dir : new Direction[]{
                Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            ElectricStoveBlockEntity other = StoveGroup.beAt(level, worldPosition.relative(dir));
            if (other != null && !other.isStandalone()) {
                source = other;
                break;
            }
        }
        if (source == null) {
            return; // 旁边没有炉子，用默认值就好
        }

        // ① 目标温度
        this.targetTemperatureValue = source.targetTemperatureValue;
        if (targetTemperature != null) {
            targetTemperature.value = clampTicks(temperatureToTicks(this.targetTemperatureValue));
        }

        // ② 实际温度
        this.currentTemperature = source.currentTemperature;
        updateHeatLevel();

        // ③ 红石供电
        boolean powered = source.getBlockState().getValue(ElectricStoveBlock.POWERED);
        if (powered != getBlockState().getValue(ElectricStoveBlock.POWERED)) {
            level.setBlock(worldPosition,
                getBlockState().setValue(ElectricStoveBlock.POWERED, powered), 2);
        }

        setChanged();
        sendData();
    }

    /**
     * 组长把温度直接写进组员，并让组员刷新自己的热档位。
     *
     * <p>★ 这是「组内 currentTemperature 永远相等」的<b>唯一</b>实现点。
     * 不用 getter/setter 传递是因为组员需要同步更新方块状态（热档位）和上方容器的热量。
     *
     * <p>★★ 2026-11-17 补：组员也要 {@code sendData()}。
     * 原来只有组长会周期性发同步包，组员<b>永远不发给客户端</b>，
     * 于是老大看到的是「组员没有实际温度」——客户端那边它们的 currentTemperature 一直是 0。
     */
    void applySharedTemperature(double temperature) {
        // ★★★ 2026-11-18 修：不能因为「温度没变」就跳过供热 ★★★
        //
        //  老大原话（2026-11-18 19:51）：
        //  「电磁炉组内只会有一个电磁炉的上方是正常加热，其余的电磁炉只在
        //    切换温度的时候加热一下，只有一个电磁炉的情况下升温降温都是正常的」
        //
        //  原因就在原来的第一行 `if (this.currentTemperature == temperature) return;`：
        //    · 组长每 tick 都调 pushHeatUpwards()（在 tickAsLeader 里，无条件）
        //    · 组员只有「温度变了」才走到 pushHeatUpwards()
        //  ⇒ 温度稳定后组员的 currentTemperature == 组长的，**直接 return 了**，
        //    于是组员上方的坩埚再也没人给它补热。
        //
        //  而 TFC 的坩埚每 tick 会把自己的 targetTemperature 往 0 衰减
        //  （反编译 CrucibleBlockEntity.serverTick 确认），必须持续补热才稳得住，
        //  所以组员上方的坩埚就凉了。
        //
        //  「切换温度那一下会加热」也正好对上：温度一变，判断不等，走了一次供热。
        //
        //  ⇒ 正确做法：**把「状态更新」和「供热」分开**
        //    · 状态更新（改温度、刷档位、标脏、发同步包）—— 只在变化时做
        //    · 供热（pushHeatUpwards）—— **每 tick 都做**，这样才和组长一致
        //
        //  ★ 不能把 pushHeatUpwards() 直接挪到第一行：那样变温那一 tick 推的是旧温度。
        var changed = this.currentTemperature != temperature;
        if (changed) {
            this.currentTemperature = temperature;
            updateHeatLevel();
        }

        // 每 tick 都推热（内部 currentTemperature <= 0 时会自己返回）
        pushHeatUpwards();

        if (changed) {
            setChanged();
            // 和组长一样，每秒同步一次给客户端（数值框、Jade、模型档位都读它）。
            if (level != null && level.getGameTime() % 20L == 0L) {
                sendData();
            }
        }
    }

    /**
     * 组长的 tick：把整组当成一台炉子来算。
     *
     * <p>规则：
     * <ul>
     *   <li>组内<b>任意一台</b>有红石信号且电够，整组就加热（老大要的是"共享"）；</li>
     *   <li>目标温度取组内<b>最高</b>的那个 —— 有人想烧高温，整组就一起往上走；</li>
     *   <li>耗电按组内<b>炉子数量</b>线性叠加：N 台一起加热就是 N 份耗电；</li>
     *   <li>电从组池（就是组长自己的 {@code energy}）里扣，多根电线充进来的都在这里。</li>
     * </ul>
     */
    private void tickAsLeader(List<ElectricStoveBlockEntity> group) {
        boolean anyPowered = false;
        int maxTarget = 0;
        for (ElectricStoveBlockEntity be : group) {
            if (be.getBlockState().getValue(ElectricStoveBlock.POWERED)) {
                anyPowered = true;
            }
            maxTarget = Math.max(maxTarget, be.targetTemperatureValue);
        }

        int cost = energyCostForTemperature(maxTarget) * Math.max(1, group.size());
        boolean hasPower = anyPowered && energy.getEnergyStored() >= cost;

        // ★ 2026-11-18 诊断日志：老大反馈「升到目标温度后又降下来」。
        //   每 40 tick（2 秒）打一行，把参与判定的所有值都记下来，
        //   用来区分到底是 anyPowered 掉了、电不够、还是目标温度算错了。
        //   定位完成后把 DEBUG_STOVE 改成 false 即可关掉。
        if (DEBUG_STOVE && level != null && level.getGameTime() % 40L == 0L) {
            org.slf4j.LoggerFactory.getLogger("woodencog/stove").info(
                "[炉子诊断] t={} pos={} 组={} anyPowered={} stored={} cost={} hasPower={} "
                    + "maxTarget={} cur={} tier={}",
                level.getGameTime(), worldPosition, group.size(), anyPowered,
                energy.getEnergyStored(), cost, hasPower,
                maxTarget, String.format("%.1f", currentTemperature), lastTier
            );
        }
        if (hasPower) {
            // ★★★ 2026-11-18 修：这里原来是 energy.extractEnergy(cost, false) ★★★
            //
            //  EnergyStorage 构造时 maxExtract = 0（电器不对外放电），
            //  Forge 的 extractEnergy 开头就是 `if (!canExtract()) return 0;`，
            //  而 canExtract() 就是 maxExtract > 0 ⇒ 永远返回 0，一格电都不扣。
            //
            //  后果：电量永远满格 → 「energy.getEnergyStored() >= cost」永远成立
            //        → 只要有红石信号就一直烧，该省的省不下来。
            //        （反方向也会出问题：如果哪天容量调小，判断和实际扣费就更对不上。）
            //
            //  改用内部扣电方法 consumeInternal，绕过 maxExtract 的限制。
            energy.consumeInternal(cost);
        }

        double target = hasPower ? maxTarget : 0.0D;
        if (currentTemperature < target) {
            currentTemperature = Math.min(target, currentTemperature + HEATING_RATE);
        } else if (currentTemperature > target) {
            currentTemperature = Math.max(target, currentTemperature - COOLING_RATE);
        }

        updateHeatLevel();
        pushHeatUpwards();

        // ★ 烫伤：站在炉子正上方的实体会挨烫（老大 2026-11-17 要求）。
        //   只在组长里做一次 —— 组内所有炉子温度相同，每台各查一遍会重复扣血。
        burnEntitiesAbove();

        // ★ 把温度直接写进组员 —— 组内 currentTemperature 必须严格相等（老大 2026-11-17 要求）。
        //   不能指望组员自己 tick 时来抄：同一 tick 内谁先跑不确定。
        for (ElectricStoveBlockEntity be : group) {
            if (be != this) {
                be.applySharedTemperature(this.currentTemperature);
                be.burnEntitiesAbove();
            }
        }

        setChanged();
        if (level.getGameTime() % 20L == 0L) {
            sendData();
        }
    }

    /**
     * 烫伤站在本炉子<b>正上方</b>的实体。
     *
     * <h2>规则（老大 2026-11-17）</h2>
     *
     * <ul>
     *   <li>踩在炉子上（正上方那一格）就会挨烫</li>
     *   <li>伤害随温度上升，满 12 血/秒</li>
     *   <li>高温时还会把玩家点着</li>
     * </ul>
     *
     * <h2>★ 为什么每 10 tick 才结算一次</h2>
     *
     * <p>见 {@link StoveBurn}：Minecraft 没有「每秒持续伤害」的机制，
     * 每 tick 给一次会让血条以肉眼可见的速度被抽走、还每 tick 刷击退。
     * 改成每 0.5 秒给「半秒的量」，累计正好是目标值，手感也清楚。
     *
     * <h2>★ 为什么用正上方那一格的中心点</h2>
     *
     * <p>不能只用 {@code blockPos.above()} 查实体 —— 玩家站在方块边缘时，
     * {@link net.minecraft.world.entity.Entity#blockPosition()} 可能还在邻格。
     * 这里查「脚底落在这个方块上表面之上的实体」，包含骑跨边缘的情况。
     */
    private void burnEntitiesAbove() {
        if (level == null || level.isClientSide) {
            return;
        }
        if (level.getGameTime() % StoveBurn.DAMAGE_INTERVAL_TICKS != 0L) {
            return;
        }
        float damage = StoveBurn.damagePerInterval(currentTemperature);
        if (damage <= 0.0F) {
            return;
        }

        // 上表面 15/16 处 → 上方一格。用 AABB 覆盖整个方块上方那一格。
        double top = worldPosition.getY() + 15.0D / 16.0D;
        net.minecraft.world.phys.AABB area = new net.minecraft.world.phys.AABB(
            worldPosition.getX(), top, worldPosition.getZ(),
            worldPosition.getX() + 1.0D, top + 0.5D, worldPosition.getZ() + 1.0D
        );

        for (net.minecraft.world.entity.LivingEntity entity : level.getEntitiesOfClass(
                net.minecraft.world.entity.LivingEntity.class, area)) {
            entity.hurt(level.damageSources().hotFloor(), damage);
            if (StoveBurn.shouldIgnite(currentTemperature)) {
                entity.setSecondsOnFire(StoveBurn.IGNITE_SECONDS);
            }
        }
    }

    /** 旧的客户端 tick 入口，保留为空实现以免外部还在调用。 */
    public void tickClient() {
        // 客户端不自己算温度。
    }

    /**
     * 把当前温度推给<b>上方</b>的容器（TFC 坩埚 / 锅 / 烤架……）。
     *
     * <h2>这是从 TFC 篝火抄来的做法</h2>
     *
     * <p>本鲸一开始以为：只要给自己挂上 {@code IHeatBlock} 能力，坩埚就会来读温度。
     * <b>完全不是。</b> 反编译 TFC 的
     * {@code AbstractFirepitBlockEntity.serverTick} 可以看到，篝火是这么做的（字节码 248~263）：
     *
     * <pre>
     *   temperature = HeatCapability.adjustDeviceTemp(...);
     *   HeatCapability.provideHeatTo(level, pos.above(), temperature);   // ← 主动往上推
     * </pre>
     *
     * <p>而 {@code HeatCapability.provideHeatTo} 的内部实现是：
     *
     * <pre>
     *   var be = level.getBlockEntity(pos);
     *   if (be != null)
     *       be.getCapability(BLOCK_CAPABILITY)
     *         .ifPresent(heat -&gt; heat.setTemperatureIfWarmer(temperature));
     * </pre>
     *
     * <p>也就是说：<b>热源负责推，容器只负责收</b>。坩埚自己那侧根本没有"去找下方热源"的逻辑
     * （它的 {@code BLOCK_CAPABILITY} 甚至返回 {@code SidedHandler.Noop}）。
     * 所以只挂能力而不推送，坩埚永远收不到热 —— 这正是之前"上方坩埚不加热"的真正原因。
     *
     * <p>直接调用 {@code provideHeatTo} 而不是自己写 {@code setTemperature}，好处是
     * 里面用的是 {@code setTemperatureIfWarmer}（只升不降），和篝火的行为完全一致：
     * 电阻炉断开后，坩埚会按它自己的速率慢慢凉下来，而不是被瞬间清零。
     */
    private void pushHeatUpwards() {
        if (currentTemperature <= 0.0D) {
            return;
        }
        net.dries007.tfc.common.capabilities.heat.HeatCapability.provideHeatTo(
            level,
            worldPosition.above(),
            (float) currentTemperature
        );
    }

    /**
     * 本 tick 的耗电，二次方曲线。
     *
     * <pre>cost = BASE_ENERGY_COST * (target / 1000)^2</pre>
     */
    /**
     * 本炉子（若在组内则为<b>整组</b>）本 tick 的耗电。
     *
     * <p>★ 组内的电是共享的，所以对外报告耗电时也必须是组的总耗电，
     * 否则 Jade/盖板会显示得比实际扣的少。
     */
    public int energyCostPerTick() {
        int per = energyCostForTemperature(targetTemperatureValue);
        if (standalone || level == null || level.isClientSide) {
            return per;
        }
        List<ElectricStoveBlockEntity> group = StoveGroup.collect(level, worldPosition);
        return per * Math.max(1, group.size());
    }

    /** 按给定目标温度算单台耗电，二次方曲线。 */
    public static int energyCostForTemperature(int targetTemperature) {
        if (targetTemperature <= 0) {
            return 0;
        }
        double ratio = targetTemperature / 1000.0D;
        return (int) Math.round(BASE_ENERGY_COST * ratio * ratio);
    }

    /** 当前温度对应的 Create 热档位。 */
    public BlazeBurnerBlock.HeatLevel heatLevelFor(double temperature) {
        if (temperature >= SEETHING_THRESHOLD) {
            return BlazeBurnerBlock.HeatLevel.SEETHING;
        }
        if (temperature >= 340.0D) {
            return BlazeBurnerBlock.HeatLevel.KINDLED;
        }
        if (temperature >= 200.0D) {
            return BlazeBurnerBlock.HeatLevel.FADING;
        }
        if (temperature >= NONE_THRESHOLD) {
            return BlazeBurnerBlock.HeatLevel.SMOULDERING;
        }
        return BlazeBurnerBlock.HeatLevel.NONE;
    }

    /**
     * 当前温度对应的<b>贴图档位</b>（0~8，共 9 档）。
     *
     * <h2>2026-11-17 老大定：按木炭灶的级别</h2>
     *
     * <p>木炭灶（TFC {@code CharcoalForgeBlockEntity.serverTick}）的算法是：
     * <pre>
     *   level = clamp((int)(temperature / 1600 * 6) + 1, 1, 7)
     * </pre>
     * 反编译确认过，{@code maxVisibleTemperature()} = 1600。
     * 换算成温度边界就是 <b>每 266.67 度一档</b>：
     *
     * <pre>
     *   档 0：熄灭
     *   档 1：    0 ~  266.67     hand_1
     *   档 2： 266.67 ~  533.33    hand_2
     *   档 3： 533.33 ~  800.00    hand_3
     *   档 4： 800.00 ~ 1066.67    hand_4
     *   档 5：1066.67 ~ 1333.33    hand_5
     *   档 6：1333.33 ~ 1600.00    hand_6
     *   档 7：1600.00 ~ 2300.00    hand_7
     *   档 8：2300 以上            hand_8   ← 超级加热
     * </pre>
     *
     * <p>★ 和木炭灶唯一的区别在最后两档：木炭灶上限 1600（档 7 就是顶），
     * 而电阻炉能到 3000，所以把 <b>2300（超级加热阈值）</b>单独划成第 9 档，
     * 正好对应老大说的「hand_8 是 2300 度」。
     */
    public static int tierFor(double temperature) {
        if (temperature >= SEETHING_THRESHOLD) {
            return 8;
        }
        if (temperature <= 0.0D) {
            return 0;
        }
        // 和木炭灶同一套公式，只是把 clamp 上限从 7 放到 7（档 8 单独判过）
        int level = (int) (temperature / CHARCOAL_FORGE_MAX_VISIBLE * 6.0D) + 1;
        return Math.max(1, Math.min(7, level));
    }

    /** 木炭灶的「可见温度上限」，档位公式里的那个 1600。 */
    private static final double CHARCOAL_FORGE_MAX_VISIBLE = 1600.0D;

    /** 把当前温度换算成热档位写进方块状态，只在变化时写。 */
    /**
     * 温度变了就刷新方块状态里的热档位，让模型跟着换。
     *
     * <p>★★ 这里<b>必须</b>用 {@code level.setBlock(pos, state, 2)}（2 = UPDATE_CLIENTS），
     * <b>绝对不能</b>用 {@code setBlockAndUpdate(...)}（= flag 3 = UPDATE_ALL）。
     *
     * <p>2026-11-05 踩过的坑：用 {@code setBlockAndUpdate} 时，方块状态一变就会触发
     * {@code Block.onRemove} → 原方块实体被移除并<b>新建一个</b>。
     * 于是每 tick 都在「升一点温 → 换档位 → BE 被重建 → currentTemperature 归零」之间循环：
     * 表现为温度永远显示 0、电量永远不扣、TFC 坩埚永远读到 0 °C，
     * 但方块状态确实变成了 {@code blaze=seething}（档位换成功了，温度却被清零）。
     *
     * <p>flag 2 只把新状态发给客户端做渲染，不会重建方块实体，温度就保住了。
     */
    private void updateHeatLevel() {
        BlazeBurnerBlock.HeatLevel want = heatLevelFor(currentTemperature);
        int wantTier = tierFor(currentTemperature);

        // ★ 2026-11-17：现在要同时维护两个属性
        //   HEAT_LEVEL（Create 的 5 档，给鼓风机/大锅/焊接读）
        //   TIER（0~8 的 9 档，只用来选贴图）
        //   所以不能再用「want == lastHeatLevel 就返回」的短路 —— 档位没变但 tier 变了也得刷。
        if (want == lastHeatLevel && wantTier == lastTier) {
            return;
        }
        lastHeatLevel = want;
        lastTier = wantTier;

        BlockState state = getBlockState();
        BlockState next = state;
        if (state.hasProperty(ElectricStoveBlock.HEAT_LEVEL)
            && state.getValue(ElectricStoveBlock.HEAT_LEVEL) != want) {
            next = next.setValue(ElectricStoveBlock.HEAT_LEVEL, want);
        }
        if (state.hasProperty(ElectricStoveBlock.TIER)
            && state.getValue(ElectricStoveBlock.TIER) != wantTier) {
            next = next.setValue(ElectricStoveBlock.TIER, wantTier);
        }
        if (next != state) {
            level.setBlock(worldPosition, next, 2);
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  能力
    // ────────────────────────────────────────────────────────────────

    /**
     * 电阻炉对外暴露的两个能力。
     *
     * <ul>
     *   <li>{@link ForgeCapabilities#ENERGY} —— 任何 Forge Energy 源（IE 电线等）都能给它充电。</li>
     *   <li>{@link HeatCapability#BLOCK_CAPABILITY} —— <b>让放在它上方的 TFC 坩埚/烤炉能拿到温度</b>。
     *       TFC 的坩埚（{@code CrucibleBlockEntity}）每 tick 会向下读方块的 {@code IHeatBlock}
     *       并把自己的 {@code targetTemperature} 对齐过去。没有这个能力，坩埚就永远烧不热，
     *       这就是老大实测"上方坩埚加热不了"的原因。</li>
     * </ul>
     *
     * <p>★ {@code IHeatBlock} 只有 getTemperature / setTemperature 两个方法，
     * 所以这里用一个内联闭包实现，不需要单独的类。
     * 返回的是<b>当前温度</b>（不是目标温度）—— 坩埚该拿到"现在多热"，
     * 而不是玩家设定的目标，否则电一断坩埚还会显示全温。
     */
    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable Direction side) {
        if (capability == ForgeCapabilities.ENERGY) {
            return energyHandler.cast();
        }
        if (capability == net.dries007.tfc.common.capabilities.heat.HeatCapability.BLOCK_CAPABILITY) {
            return heatHandler.cast();
        }
        return super.getCapability(capability, side);
    }

    /** TFC 热源能力。只读：外部可以读温度，但不该改我们的温度。 */
    private final LazyOptional<net.dries007.tfc.common.capabilities.heat.IHeatBlock> heatHandler =
        LazyOptional.of(() -> new net.dries007.tfc.common.capabilities.heat.IHeatBlock() {
            @Override
            public float getTemperature() {
                return (float) currentTemperature;
            }

            @Override
            public void setTemperature(float value) {
                // 电阻炉的温度由电力和目标温度决定，不接受外部写入。
                // 留空实现是为了满足接口；TFC 的坩埚本身也只读不写。
            }
        });

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        energyHandler.invalidate();
        heatHandler.invalidate();
    }

    // ────────────────────────────────────────────────────────────────
    //  存档
    // ────────────────────────────────────────────────────────────────

    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        tag.putInt(KEY_ENERGY, energy.getEnergyStored());
        tag.putInt(KEY_TARGET, targetTemperatureValue);
        tag.putDouble(KEY_CURRENT, currentTemperature);
        tag.putBoolean(KEY_UPGRADED, upgraded);
        tag.putBoolean(KEY_STANDALONE, standalone);
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);

        // ★★★ 2026-11-18 修：这里原来传的是 CompoundTag，必然抛异常 ★★★
        //
        //  原来的写法：
        //      CompoundTag energyTag = new CompoundTag();
        //      energyTag.putInt("Energy", tag.getInt(KEY_ENERGY));
        //      energy.deserializeNBT(energyTag);
        //
        //  但 Forge 的 `EnergyStorage.deserializeNBT` 只认 **IntTag**，反编译确认：
        //      public void deserializeNBT(Tag nbt) {
        //          IntTag intTag;
        //          if (nbt instanceof IntTag) { intTag = (IntTag) nbt; }
        //          else throw new IllegalArgumentException(
        //              "Can not deserialize to an instance that isn't the default implementation");
        //          this.energy = intTag.getAsInt();
        //      }
        //  所以包成 CompoundTag 过去 100% 抛异常，日志里能看到：
        //      Failed to load data for block entity woodencog:electric_stove
        //      java.lang.IllegalArgumentException: Can not deserialize to an instance ...
        //
        //  ★ 后果就是老大报的那串现象：
        //    read() 在 L1024 抛异常后，**后面的字段全都没读**（目标温度和实际温度
        //    都在它下面几行），于是：
        //      · 方块状态（贴图）是存档里的 → 看着对
        //      · currentTemperature 却是字段默认值 0 → 坩埚收不到热
        //      · 一改温度就会走 setTargetTemperature 那条路重新赋值 → 又能热了
        //    这解释了「贴图对、坩埚不热、但调一下温度就好」。
        energy.deserializeNBT(IntTag.valueOf(tag.getInt(KEY_ENERGY)));

        targetTemperatureValue = tag.contains(KEY_TARGET) ? tag.getInt(KEY_TARGET) : DEFAULT_TEMPERATURE;
        currentTemperature = tag.contains(KEY_CURRENT) ? tag.getDouble(KEY_CURRENT) : 0.0D;

        // ★★ 升级状态以【方块状态】为准，NBT 里那个只是兜底。
        //   如果方块状态有 UPGRADED 属性，就用它覆盖字段 —— 这样即使 NBT 是旧的
        //   （比如上一版留下的存档），读进来的也会是正确的值。
        upgraded = tag.getBoolean(KEY_UPGRADED);
        syncUpgradedFromState();

        // 老存档没有这个键 ⇒ 默认 false（加入相邻组），这是老大要的默认行为。
        standalone = tag.getBoolean(KEY_STANDALONE);

        lastHeatLevel = heatLevelFor(currentTemperature);
        // 贴图档位也要跟着恢复，否则读档后第一次 updateHeatLevel 会因为「lastTier 还是 0」
        // 多刷一次方块状态（无害，但没必要）。
        lastTier = tierFor(currentTemperature);
        if (targetTemperature != null) {
            // 升级后是 1000~3000，未升级是 500~2500，两种区间不同，按当前状态取。
            int loTicks = isUpgraded() ? upgradedMinTicks() : baseMinTicks();
            int hiTicks = isUpgraded() ? upgradedTicks() : baseTicks();
            targetTemperature.between(loTicks, hiTicks);
            targetTemperature.value = clampTicks(temperatureToTicks(targetTemperatureValue));
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  只读访问（给盖板提示、测试用）
    // ────────────────────────────────────────────────────────────────

    public int getTargetTemperature() {
        return targetTemperatureValue;
    }

    public double getCurrentTemperature() {
        return currentTemperature;
    }

    public int getEnergyStored() {
        return energy.getEnergyStored();
    }

    public int getEnergyCapacity() {
        return energy.getMaxEnergyStored();
    }
}
