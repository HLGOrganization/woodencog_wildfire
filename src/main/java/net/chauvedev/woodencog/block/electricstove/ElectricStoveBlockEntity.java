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
 * 电磁炉的方块实体：存 Forge Energy、按玩家设定的温度加热、温度越高耗电越快。
 *
 * <h2>温度怎么设</h2>
 *
 * <p>用 Create 的 {@link ScrollValueBehaviour}，和创造马达调转速是同一套东西：玩家对着方块正面的
 * 数值框右键/滚轮就能调，调完会回调 {@link #onTargetChanged}。
 *
 * <h2>可调区间</h2>
 *
 * <pre>
 *   未升级： 500 ~ 2000 °C     （默认 1250）
 *   升级后：1000 ~ 2500 °C     （下限抬高，不是往下放）
 * </pre>
 *
 * <p>两档都是 150 格，所以进度条长度一致，装升级件不会让条忽然变长或变短。
 *
 * <h2>耗电曲线（二次方）</h2>
 *
 * <pre>
 *   cost = BASE_ENERGY_COST * (target / 1000)^2
 * </pre>
 *
 * <p>1000 度时正好是 {@link #BASE_ENERGY_COST}；2000 度是 4 倍，2500 度是 6.25 倍。
 * 系数从目标温度算，不看当前实际温度——玩家设了 2500 就一直按 2500 付费，
 * 这样"设高一点"不会因为还没烧热就便宜一截。
 *
 * <h2>热档位怎么映射</h2>
 *
 * <p>Create 的鼓风机、大锅、焊接只认 {@link BlazeBurnerBlock.HeatLevel} 四档。电磁炉当前温度
 * （{@link #currentTemperature}）决定写进方块的档位：
 * <pre>
 *   &lt; 80           NONE        不发热
 *   80 ~ 199       SMOULDERING
 *   200 ~ 339      FADING
 *   &gt;= 340        KINDLED
 *   且温度 &gt;= SEETHING_THRESHOLD(1000)  → SEETHING  超级加热
 * </pre>
 * 换个说法：只要目标温度设到 340 以上、电也够，电磁炉就是个"炽热"热源；设到 1000 以上就是超级加热。
 *
 * <h2>什么时候加热</h2>
 *
 * <p>三个条件同时满足才加热、才扣电：方块有红石信号（{@code POWERED}）、电够本 tick 的耗电、
 * 目标温度大于 0。缺一个就停止加热并自然降温（{@link #COOLING_RATE}）。
 *
 * <p>实际温度向目标温度靠拢的速率是 {@link #HEATING_RATE} 度/tick（20 度/秒），
 * 所以从 0 烧到 2500 约需 125 秒；降温同样按 {@link #COOLING_RATE}。
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
    public static final int BASE_MAX_TEMPERATURE = 2000;

    /** 装了升级件时的最高温度。 */
    public static final int UPGRADED_MAX_TEMPERATURE = 2500;

    /** 耗电基准：1000 度时每 tick 耗多少 FE。实际耗电 = 本值 × (目标温度/1000)^2。 */
    public static final int BASE_ENERGY_COST = 40;

    /** 电池容量（FE）。按 2500 度满载 625 FE/t 算，能撑 80 秒左右。 */
    public static final int ENERGY_CAPACITY = 50000;

    /** 最大输入功率（FE/t）。够 2500 度的 625 FE/t 加上余量。 */
    public static final int MAX_RECEIVE = 2000;

    /** 加热速率：每 tick 升温多少度。20 度/秒 → 0 到 2500 约 125 秒。 */
    public static final double HEATING_RATE = 20.0D;

    /** 冷却速率：每 tick 降温多少度。 */
    public static final double COOLING_RATE = 10.0D;

    /** 达到这个温度就算"超级加热"（SEETHING 档）。 */
    public static final double SEETHING_THRESHOLD = 1000.0D;

    /** 低于这个温度算不发热。 */
    public static final double NONE_THRESHOLD = 80.0D;

    // ────────────────────────────────────────────────────────────────

    private static final String KEY_ENERGY = "Energy";
    private static final String KEY_TARGET = "TargetTemperature";
    private static final String KEY_CURRENT = "CurrentTemperature";
    private static final String KEY_UPGRADED = "Upgraded";

    /** 电池。接收来自外部，不允许抽走（电器不是电池）。 */
    private final EnergyStorage energy = new EnergyStorage(ENERGY_CAPACITY, MAX_RECEIVE, 0, 0) {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int received = super.receiveEnergy(maxReceive, simulate);
            if (received > 0 && !simulate) {
                ElectricStoveBlockEntity.this.setChanged();
            }
            return received;
        }
    };

    private final LazyOptional<IEnergyStorage> energyHandler = LazyOptional.of(() -> energy);

    /** 温度调节行为，和创造马达的转速调节同一种东西。 */
    public StoveTemperatureBehaviour targetTemperature;

    /** 玩家设定的目标温度，1~2000 或 1~2500。 */
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
     *   <li>后果就是老大看到的：模型已经变成升级外观了，但温度面板还是 500~2000。</li>
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
        // ★ 这里 between 的是【刻度】不是温度，见下面 DEGREES_PER_TICK 的注释。
        //   ★★ 区间要按【当前升级状态】取，不能写死 baseMin/base —— 否则读档后
        //      已升级的炉子会从 500~2000 起步，得等下一次 setUpgraded 才纠正过来。
        targetTemperature.between(
            isUpgraded() ? upgradedMinTicks() : baseMinTicks(),
            isUpgraded() ? upgradedTicks() : baseTicks()
        );
        targetTemperature.value = clampTicks(temperatureToTicks(targetTemperatureValue));
        // ★ withFormatter 收的是 Integer，箱装箱后传进来；用 Integer 形参避免拆箱重载选错。
        targetTemperature.withFormatter(v -> formatTicks(v == null ? 0 : v.intValue()));
        targetTemperature.withCallback(this::onTargetChanged);
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
    //   所以这里把刻度压到 0~200（每格 10 °C），和创造马达同量级。
    //   升级后刻度放到 0~250，多出来的 50 格就是老大要的"右边多接一段"。

    /** 一格代表多少 °C。 */
    public static final int DEGREES_PER_TICK = 10;

    // ── 面板刻度的上下限 ──
    //
    // 面板的 between(min, max) 决定两件事：
    //   · max - min = 进度条有几格（条有多长）
    //   · 玩家能滚到的范围
    // 所以"未升级 500~2000"是 150 格、"升级后 1000~2500"也是 150 格 ——
    // 两种状态下条一样长，不会出现装个升级件条突然变短/变长。
    // ★ 这里刻意让升级后的下限【抬高】到 1000：老大要的是"升级件换的是高档位区间"，
    //   而不是"能往下调更低"。所以装上升级件后，500~999 这段就不再可选。

    /** 未升级时的刻度下限：50 格 × 10 = 500 °C。 */
    public static int baseMinTicks() {
        return BASE_MIN_TEMPERATURE / DEGREES_PER_TICK;
    }

    /** 未升级时的刻度上限：200 格 × 10 = 2000 °C。 */
    public static int baseTicks() {
        return BASE_MAX_TEMPERATURE / DEGREES_PER_TICK;
    }

    /** 升级后的刻度下限：100 格 × 10 = 1000 °C。 */
    public static int upgradedMinTicks() {
        return UPGRADED_MIN_TEMPERATURE / DEGREES_PER_TICK;
    }

    /** 升级后的刻度上限：250 格 × 10 = 2500 °C。 */
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
     *   未升级： 500 ~ 2000 °C   （50 ~ 200 刻度）
     *   升级后：1000 ~ 2500 °C   （100 ~ 250 刻度）
     * </pre>
     *
     * <p>不是"放宽上限"，是<b>换到高一段的区间</b>：下限从 500 抬到 1000，上限从 2000 抬到 2500。
     * 两种状态都是 150 格，所以进度条长度不变，只是右侧整段往上挪。
     *
     * <p>两种情况都要处理：
     * <ul>
     *   <li>当前温度低于新下限（比如原本在 700，装上升级件后下限变成 1000）→ 抬到 1000；</li>
     *   <li>当前温度高于新上限 → 压到 2500。</li>
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

    /** 服务端：算电、升温、刷新热档位、把热推给上方的容器。 */
    public void tickServer() {
        if (level == null || level.isClientSide) {
            return;
        }

        boolean powered = getBlockState().getValue(ElectricStoveBlock.POWERED);
        int cost = energyCostPerTick();
        boolean hasPower = powered && energy.getEnergyStored() >= cost;

        if (hasPower) {
            energy.extractEnergy(cost, false);
        }

        // 温度向目标靠拢
        double target = hasPower ? targetTemperatureValue : 0.0D;
        if (currentTemperature < target) {
            currentTemperature = Math.min(target, currentTemperature + HEATING_RATE);
        } else if (currentTemperature > target) {
            currentTemperature = Math.max(target, currentTemperature - COOLING_RATE);
        }

        updateHeatLevel();
        // ★ 把热推给上方的容器（坩埚、锅、烤架……）。见下面 pushHeatUpwards 的注释。
        pushHeatUpwards();
        setChanged();
        // 温度是被坩埚每帧读的，得让客户端也知道（数值框、Jade 提示都看它）。
        if (level.getGameTime() % 20L == 0L) {
            sendData();
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
     * 电磁炉断开后，坩埚会按它自己的速率慢慢凉下来，而不是被瞬间清零。
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
    public int energyCostPerTick() {
        if (targetTemperatureValue <= 0) {
            return 0;
        }
        double ratio = targetTemperatureValue / 1000.0D;
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
        if (want == lastHeatLevel) {
            return;
        }
        lastHeatLevel = want;
        BlockState state = getBlockState();
        if (state.hasProperty(ElectricStoveBlock.HEAT_LEVEL)
            && state.getValue(ElectricStoveBlock.HEAT_LEVEL) != want) {
            level.setBlock(worldPosition, state.setValue(ElectricStoveBlock.HEAT_LEVEL, want), 2);
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  能力
    // ────────────────────────────────────────────────────────────────

    /**
     * 电磁炉对外暴露的两个能力。
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
                // 电磁炉的温度由电力和目标温度决定，不接受外部写入。
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
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        // EnergyStorage 只认 deserializeNBT，没有 setEnergy，所以这里包一个只有一个键的 Tag 过去。
        CompoundTag energyTag = new CompoundTag();
        energyTag.putInt("Energy", tag.getInt(KEY_ENERGY));
        energy.deserializeNBT(energyTag);

        targetTemperatureValue = tag.contains(KEY_TARGET) ? tag.getInt(KEY_TARGET) : DEFAULT_TEMPERATURE;
        currentTemperature = tag.contains(KEY_CURRENT) ? tag.getDouble(KEY_CURRENT) : 0.0D;

        // ★★ 升级状态以【方块状态】为准，NBT 里那个只是兜底。
        //   如果方块状态有 UPGRADED 属性，就用它覆盖字段 —— 这样即使 NBT 是旧的
        //   （比如上一版留下的存档），读进来的也会是正确的值。
        upgraded = tag.getBoolean(KEY_UPGRADED);
        syncUpgradedFromState();

        lastHeatLevel = heatLevelFor(currentTemperature);
        if (targetTemperature != null) {
            // 升级后是 1000~2500，未升级是 500~2000，两种区间不同，按当前状态取。
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
