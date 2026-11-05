package net.chauvedev.woodencog.block.electricstove;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.foundation.block.IBE;
import net.chauvedev.woodencog.block.WoodencogBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
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
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

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

    /** 形状：跟电动燃烧器一样，底部 2/16 起、到 14/16，外加顶盖。 */
    private static final VoxelShape SHAPE = Shapes.or(
        Block.box(2.0D, 0.0D, 2.0D, 14.0D, 3.0D, 14.0D),   // 底座
        Block.box(1.0D, 3.0D, 1.0D, 15.0D, 12.0D, 15.0D)    // 主体
    );

    public ElectricStoveBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState()
            .setValue(HEAT_LEVEL, BlazeBurnerBlock.HeatLevel.NONE)
            .setValue(UPGRADED, Boolean.FALSE)
            .setValue(POWERED, Boolean.FALSE)
            .setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HEAT_LEVEL, UPGRADED, POWERED, FACING);
    }

    @Override
    @SuppressWarnings("deprecation")
    public RenderShape getRenderShape(BlockState state) {
        // 模型是普通 JSON 模型，不需要专门的 BER 渲染器。
        return RenderShape.MODEL;
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

    @Override
    @SuppressWarnings("deprecation")
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
                                BlockPos fromPos, boolean isMoving) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving);
        if (level.isClientSide) {
            return;
        }
        boolean powered = level.hasNeighborSignal(pos);
        if (powered != state.getValue(POWERED)) {
            level.setBlockAndUpdate(pos, state.setValue(POWERED, powered));
        }
    }

    @Override
    public Class<ElectricStoveBlockEntity> getBlockEntityClass() {
        return ElectricStoveBlockEntity.class;
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
