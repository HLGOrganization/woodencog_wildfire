package net.chauvedev.woodencog.block.electricstove;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.foundation.block.IBE;
import net.chauvedev.woodencog.block.WoodencogBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

import javax.annotation.Nullable;

/**
 * 电磁炉的方块。
 *
 * <p>外形照搬 {@code moreburners:electric_burner}：一个 16×16 的底座加一圈顶盖，形状沿用鼓风燃烧器的
 * {@link BlazeBurnerBlock.HeatLevel} 概念，但这里的热档位是由方块实体根据当前温度算出来后写进方块状态的，
 * 不是玩家手动切的。
 *
 * <h2>方块状态</h2>
 * <ul>
 *   <li>{@link #HEAT_LEVEL}（{@code blaze}）—— 给 Create 用。鼓风机、大锅、传送带上的焊接都读它，
 *       所以电磁炉能被这些机器当成热源。温度变化时由方块实体刷新。</li>
 *   <li>{@link #UPGRADED} —— 装了升级件为 true。目前只用于切换模型（升级后外观不同）。</li>
 *   <li>{@link #POWERED} —— 红石信号。有信号才工作，和隧道加热器一致。</li>
 *   <li>{@link #FACING} —— 朝向，决定数值面板（温度显示框）在哪个面。</li>
 * </ul>
 *
 * <p>方块本身不做任何逻辑，全部交给 {@link ElectricStoveBlockEntity}；这里只负责
 * 状态定义、形状、以及把 ticker 接上去。
 */
public class ElectricStoveBlock extends BaseEntityBlock implements IWrenchable, IBE<ElectricStoveBlockEntity> {

    /** 热档位，交给 Create 读。名字沿用 Create 的 {@code blaze} 属性名以便复用现成的模型/逻辑。 */
    public static final EnumProperty<BlazeBurnerBlock.HeatLevel> HEAT_LEVEL =
        BlazeBurnerBlock.HEAT_LEVEL;

    /** 是否装了升级件。影响模型，也影响方块实体允许的最高温度。 */
    public static final BooleanProperty UPGRADED = BooleanProperty.create("upgraded");

    /** 红石供电。没电就不加热。 */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    /** 朝向。数值面板挂在正面。 */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /**
     * 接线面位掩码：第 0/1/2/3 位 = 北/南/西/东 是否接了电线。
     *
     * <h2>为什么放进方块状态（而不是 ModelData）</h2>
     *
     * <p>本鲸一开始想走 Forge 的 {@code ModelData}，但<b>那是给带 {@code BakedModel} 的
     * 方块实体模型用的</b>；本方块是 {@link RenderShape#MODEL} 的普通 JSON 模型，
     * 根本不会去问 {@code getModelData}。走了那条路等于什么都没发生。
     *
     * <p>放进方块状态的代价：接线变化时要刷一次 {@code setBlock}。
     * 但接线变化本来就是低频事件（玩家拉线/拆线），比每帧查能力便宜得多。
     *
     * <p>★ 用 {@code setBlock(..., 2)} 而不是 {@code setBlockAndUpdate}，
     * 原因见 {@link ElectricStoveBlockEntity#updateHeatLevel()} 的注释 ——
     * flag 3 会重建方块实体，把温度清零。
     */
    public static final IntegerProperty PORTS = IntegerProperty.create("ports", 0, 15);

    /**
     * 贴图档位，<b>0~8 共 9 档</b>。
     *
     * <h2>★ 为什么不能直接扩展 {@link #HEAT_LEVEL}</h2>
     *
     * <p>{@code HEAT_LEVEL} 是 Create 的 {@code BlazeBurnerBlock.HEAT_LEVEL}，
     * 类型是 {@code EnumProperty<HeatLevel>}，枚举只有 5 个值
     * （NONE / SMOULDERING / FADING / KINDLED / SEETHING）——
     * <b>想加也加不了，那是别人的枚举</b>。
     *
     * <p>而且那 5 档是给鼓风机、大锅、传送带焊接读的，语义不能乱改。
     *
     * <p>所以贴图单独开一个 {@code IntegerProperty}：
     * <ul>
     *   <li>{@code HEAT_LEVEL} —— 继续给 Create 用，5 档不变</li>
     *   <li>{@code TIER} —— 只管贴图，1 档一张，9 张图全能用上</li>
     * </ul>
     *
     * <p>分档依据见 {@link ElectricStoveBlockEntity#tierFor(double)}：
     * 照搬木炭灶的每 266.67 度一档，最后两档按电阻炉的上限调整（档 8 = 2300 度以上）。
     */
    public static final IntegerProperty TIER = IntegerProperty.create("tier", 0, 8);

    /**
     * 形状：底座 + 主体。
     *
     * <p>★ 2026-11-17 老大反馈「碰撞体积要往上多 3 像素」：
     * 主体上沿从 12/16 抬到 <b>15/16</b>，和模型里的顶盖（{@code to [14.5, 14.4, 14.5]}）对齐。
     */
    private static final VoxelShape SHAPE = Shapes.or(
        Block.box(2.0D, 0.0D, 2.0D, 14.0D, 3.0D, 14.0D),    // 底座
        Block.box(1.0D, 3.0D, 1.0D, 15.0D, 15.0D, 15.0D)    // 主体（上沿 12 → 15）
    );

    public ElectricStoveBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState()
            .setValue(HEAT_LEVEL, BlazeBurnerBlock.HeatLevel.NONE)
            .setValue(UPGRADED, Boolean.FALSE)
            .setValue(POWERED, Boolean.FALSE)
            .setValue(FACING, Direction.NORTH)
            .setValue(PORTS, 0)
            .setValue(TIER, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HEAT_LEVEL, UPGRADED, POWERED, FACING, PORTS, TIER);
    }

    @Override
    @SuppressWarnings("deprecation")
    public RenderShape getRenderShape(BlockState state) {
        // 模型是普通 JSON 模型，不需要专门的 BER 渲染器。
        return RenderShape.MODEL;
    }

    // ────────────────────────────────────────────────────────────────
    //  亮度：档位越高越亮，最亮 14（＝火把）
    // ────────────────────────────────────────────────────────────────

    /**
     * 每一档（{@link #TIER} 0~8）对应的光照等级。
     *
     * <p>★ 2026-11-20 老大要求：「热量等级越高，亮度越高。最亮有火把那么亮。」
     * 火把是 14，所以顶档就用 14（营火是 15，比火把亮一格，这里不用）。
     *
     * <p>档位和温度是绑定的（见 {@code ElectricStoveBlockEntity#tierFor}），
     * 所以亮度会跟着温度连续地往上走，不会跳。数值不是线性的：
     * 人眼对暗部更敏感，低档多给一点、高档压一点，看起来才"越烧越亮"而不是"一闪就到底"。
     *
     * <pre>
     *   档 0（熄灭）      0    完全不亮
     *   档 1（~267°C）    3    刚有点红
     *   档 2（~533°C）    5
     *   档 3（~800°C）    7
     *   档 4（~1067°C）   8
     *   档 5（~1333°C）  10
     *   档 6（~1600°C）  11
     *   档 7（~1867°C）  12
     *   档 8（2300°C+）  14    ← 超级加热，和火把一样亮
     * </pre>
     */
    private static final int[] LIGHT_BY_TIER = {0, 3, 5, 7, 8, 10, 11, 12, 14};

    /**
     * 方块光照函数：读方块状态里的 {@link #TIER}。
     *
     * <p>注册时通过 {@code properties(p -> p.lightLevel(ElectricStoveBlock::lightForState))} 挂上去。
     * <b>必须</b>用方块状态算而不是读方块实体 —— 光照是在客户端按区块烘焙的，
     * 那时候方块实体不一定存在；只认方块状态才能被 {@code level.setBlock(..., 2)} 刷到。
     */
    public static int lightForState(BlockState state) {
        if (!state.hasProperty(TIER)) {
            return 0;
        }
        int tier = state.getValue(TIER);
        if (tier < 0 || tier >= LIGHT_BY_TIER.length) {
            return 0;
        }
        return LIGHT_BY_TIER[tier];
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        int tier = state.hasProperty(TIER) ? state.getValue(TIER) : 0;
        if (tier <= 0) {
            return;
        }

        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.9D;
        double z = pos.getZ() + 0.5D;

        int soundChance = Math.max(4, 24 - tier * 3);
        if (random.nextInt(soundChance) == 0) {
            level.playLocalSound(x, y, z, SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS,
                0.3F + 0.08F * tier, 0.6F + random.nextFloat() * 0.3F, false);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    /**
     * 放下方块后：从相邻的电阻炉校准温度和红石状态。
     *
     * <p>老大 2026-11-17 要求：「刚放下的电阻炉应该自动被旁边的电阻炉校准温度，
     * 红石信号也要跟着传播」。
     *
     * <p>★ 用 {@code setPlacedBy} 而不是 {@code onPlace}：
     * {@code setPlacedBy} 时方块实体已经建好、能拿到 {@link ElectricStoveBlockEntity}；
     * {@code onPlace} 依赖 {@code level.setBlock} 的返回值，时机更早，BE 可能还是 null。
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof ElectricStoveBlockEntity stove) {
            stove.calibrateFromNeighbours();
        }

        // ★★ 2026-11-17 修「放下第三格炉子，前面的炉子才识别到上一个的链接状态」：
        //
        //   病因：新炉子放下时自己的 PORTS 还是默认 0（放下的那个瞬间
        //   StovePorts.mask 还没算过）。而下一次放第三方块时，
        //   「刷新邻居」那段才把它补上 —— 于是看起来"晚一格"。
        //
        //   修法：放下的<b>同一时刻</b>，把「自己 + 四个邻居」全部重算一遍。
        //   早先只刷了邻居、漏了自己。
        //
        // ★ 红石也要在这个同一时刻算好，否则后面单独 setBlock 一次会把 PORTS 覆盖掉。
        //   所以合并成一次写入。
        int ports = StovePorts.mask(level, pos);
        boolean signal = level.hasNeighborSignal(pos);
        if (!signal) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                ElectricStoveBlockEntity other = StoveGroup.beAt(level, pos.relative(dir));
                if (other != null && other.getBlockState().getValue(POWERED)) {
                    signal = true;
                    break;
                }
            }
        }
        BlockState current = level.getBlockState(pos);
        if (current.getBlock() instanceof ElectricStoveBlock) {
            BlockState next = current
                .setValue(PORTS, ports)
                .setValue(POWERED, signal);
            if (next != current) {
                level.setBlock(pos, next, 2);
            }
        }

        // 邻居的接口板也要跟着变（新炉子算「接上了」的那一面）
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            refreshPortsAt(level, pos.relative(dir));
        }
    }

    private static void refreshPortsAt(Level level, BlockPos p) {
        BlockState state = level.getBlockState(p);
        if (!(state.getBlock() instanceof ElectricStoveBlock) || !state.hasProperty(PORTS)) {
            return;
        }
        int ports = StovePorts.mask(level, p);
        if (ports != state.getValue(PORTS)) {
            level.setBlock(p, state.setValue(PORTS, ports), 2);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
                                BlockPos fromPos, boolean isMoving) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving);
        if (level.isClientSide) {
            return;
        }

        // ── 红石：自己和相邻炉子的信号合成一个「组信号」 ──
        // 老大 2026-11-17 要求红石也要在炉子之间传播：
        // 任意一台通电，整组都算通电（因为它们是共用一份电的）。
        boolean signal = level.hasNeighborSignal(pos);
        if (!signal) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                ElectricStoveBlockEntity other = StoveGroup.beAt(level, pos.relative(dir));
                if (other != null && other.getBlockState().getValue(POWERED)) {
                    signal = true;
                    break;
                }
            }
        }

        BlockState next = state;
        if (signal != state.getValue(POWERED)) {
            next = next.setValue(POWERED, signal);
        }
        // 邻居变了 ⇒ 可能有人拉线过来或拆线，重算接口板。
        int ports = StovePorts.mask(level, pos);
        if (ports != state.getValue(PORTS)) {
            next = next.setValue(PORTS, ports);
        }
        if (next != state) {
            // flag 2 = 只通知客户端渲染，不重建方块实体（否则温度会被清零）。
            level.setBlock(pos, next, 2);
        }

        // ── 把红石变化传出去，让相邻炉子也刷新 ──
        //
        // ★ 这里不能直接调 level.neighborChanged(...)：
        //   相邻炉子的 neighborChanged 里又会往回通知我，两个炉子会无限互相调用。
        //   改用「组内全员重算一次」—— 每个炉子只改自己的方块状态，靠 setBlock(flag 2)
        //   让客户端刷新；flag 2 不会反过来触发 neighborChanged（只有 flag 1/3 才更新邻居）。
        if (next != state) {
            List<ElectricStoveBlockEntity> group = StoveGroup.collect(level, pos);
            boolean groupSignal = signal;
            for (ElectricStoveBlockEntity be : group) {
                if (be == null || be.getBlockPos().equals(pos)) {
                    continue;
                }
                BlockState bs = be.getBlockState();
                if (bs.getValue(POWERED) != groupSignal) {
                    level.setBlock(be.getBlockPos(), bs.setValue(POWERED, groupSignal), 2);
                }
            }
        }
    }

    @Override
    public Class<ElectricStoveBlockEntity> getBlockEntityClass() {
        return ElectricStoveBlockEntity.class;
    }

    /**
     * 扳手右键：切换「独立 / 加入相邻组」。
     *
     * <p>2026-11-17 老大定：相邻的电阻炉默认共享电量与温度；
     * 扳手右键把它拆出去（它自己独立，<b>其余的还是共享</b>）；再右键一次加回去。
     *
     * <p>★ 只处理<b>服务端</b>：客户端也会被调用一次做预测，重复执行会切两次，等于没切。
     */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            // 返回 SUCCESS 让客户端播拧扳手的动画和声音，但不动数据。
            return InteractionResult.SUCCESS;
        }
        BlockEntity be = level.getBlockEntity(context.getClickedPos());
        if (!(be instanceof ElectricStoveBlockEntity stove)) {
            return InteractionResult.PASS;
        }
        boolean nowStandalone = stove.toggleStandalone();
        Player player = context.getPlayer();
        if (player != null) {
            player.displayClientMessage(
                Component.translatable(nowStandalone
                    ? "woodencog.electric_stove.mode.standalone"
                    : "woodencog.electric_stove.mode.shared"),
                true
            );
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockEntityType<? extends ElectricStoveBlockEntity> getBlockEntityType() {
        return WoodencogBlockEntityTypes.ELECTRIC_STOVE.get();
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ElectricStoveBlockEntity(WoodencogBlockEntityTypes.ELECTRIC_STOVE.get(), pos, state);
    }

    /*
     * ★★ 千万不要在这里覆盖 getTicker()。（2026-11-05 连踩两次）
     *
     * Create 的 IBE 接口已经提供了默认实现：
     *
     *     default <S extends BlockEntity> BlockEntityTicker<S> getTicker(...) {
     *         if (SmartBlockEntity.class.isAssignableFrom(getBlockEntityClass()))
     *             return new SmartBlockEntityTicker();   // ← 正是 SmartBlockEntity 要的那个
     *         return null;
     *     }
     *
     * 自己写一个 lambda 顶掉它，SmartBlockEntity 的 tick() 就再也不被调用：
     * 电量不扣、温度恒 0、TFC 坩埚读到的永远是 0 °C。
     * 本鲸第一版写了 `(lvl,pos,st,be) -> stove.tickServer()`，第二版改成调 stove.tick()，
     * 两次都没救 —— 因为问题不是"调了哪个方法"，而是"整个 ticker 被换掉了"。
     * 正确做法是让 IBE 的默认实现生效，每 tick 的逻辑写在方块实体的 tick() 里。
     */
}
