package net.chauvedev.woodencog.block.tunnelheater;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.foundation.block.IBE;
import net.chauvedev.woodencog.block.WoodencogBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The block of a tunnel heater: a tunnel that sits on top of a Create belt, the way a brass tunnel
 * does, and heats whatever the belt carries underneath it, to the welding temperature of the metal or
 * to the no-recipe ceiling of an item that has no such temperature.
 *
 * <p>It belongs on a belt the way a brass tunnel does, and the placement rule is Create's own,
 * {@code BeltTunnelBlock.isValidPositionForPlacement}: the block below has to be a Create belt, and
 * that belt has to be horizontal. A sloped or vertical belt is not something an item can sit still on
 * long enough to be heated. Unlike a brass tunnel, the belt does <em>not</em> have to be encased -
 * {@code BeltTunnelBlock.canSurvive} also demands {@code BeltBlock.CASING}, and this block deliberately
 * leaves that out, so a bare belt is enough. {@link #canSurvive} is therefore the placement rule and
 * nothing else, and the block drops itself when the belt below is broken or turned into a slope.
 *
 * <p>The shape is a brass tunnel's: a tunnel over the belt, two 2/16 side walls that reach 5/16 down into
 * the belt block and a roof above them, open at both ends along the belt ({@link #SHAPE_X} and
 * {@link #SHAPE_Z}, chosen by {@link #HORIZONTAL_AXIS}). Those two shapes are built from the same three
 * boxes the two models are built from, so what is drawn and what is collided with cannot drift apart.
 *
 * <p>The redstone switch is Create's steam whistle one: a {@code POWERED} property, the state kept in
 * step in {@link #neighborChanged} from {@code level.hasNeighborSignal}. <b>The signal turns the heater
 * on</b>: the property defaults to false in {@link #registerDefaultState}, so a freshly placed heater is
 * off, and a heater without a signal reports {@code isHeating() == false}, so neither the belt below nor
 * a fan's airflow gets anything out of it until something powers it.
 *
 * <p>{@code POWERED} stays exactly that: this block's own signal. A signal anywhere on a connected run
 * of heaters turns the whole run on - each of them reads the group's signals while it is working out its
 * own group, so they all reach the same answer without being told - and a heater that is running because
 * a neighbour is signalled shows {@link #ACTIVE}. The two are deliberately separate properties: if the
 * group's answer were written back into {@code POWERED}, the one heater carrying the signal could turn
 * it off and the rest of the run would hold the group on forever.
 *
 * <p><b>The mode is chosen on the roof.</b> A tunnel heater carries a Create value box on top of its
 * roof - the same slot a brass tunnel uses for what it does with the items it takes in - which switches
 * between heating an item as it passes and holding it on the belt until it is hot enough to be worked or
 * welded. The box belongs to the block entity and not to this class, as does the rule that one heater of
 * a group carries the rest with it; see {@code TunnelHeaterBlockEntity} and {@code TunnelHeaterMode}. It
 * is deliberately not a property of the block state: a mode is one value per group, and block states are
 * per block and are what a client is sent, neither of which is a good fit for a choice a player makes
 * once and every member of a run shares.
 */
public class TunnelHeaterBlock extends Block implements IBE<TunnelHeaterBlockEntity> {

    /** The same property instance Create's steam whistle and vanilla redstone components use. */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    /**
     * Whether the group this heater is in is <b>working</b>, which takes both of the conditions at once:
     * the group carries a redstone signal and the battery the group shares has charge. A signalled
     * heater with a flat battery is not working and shows as off.
     *
     * <p>It says nothing about this block's own signal - that is {@link #POWERED} - and nothing in the
     * behaviour reads it: the block entity works out the answer from the group and the battery and only
     * mirrors it here so that the model can show it. Written by
     * {@code TunnelHeaterBlockEntity#tick} with {@code Block.UPDATE_CLIENTS}, since the block state is
     * the only thing that gets sent to a client.
     */
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    /**
     * Whether this heater is a crossing: side walls on all four edges instead of only the two across
     * the belt, so belts running both ways can pass through it.
     *
     * <p>Kept as a block state property rather than read live from the level in the model, because a
     * model can only be chosen by a block state. {@link #updateCross} works it out from the belts
     * around this one and writes it back; it is called from {@link #neighborChanged},
     * {@link #getStateForPlacement} and the block entity's tick, so a belt laid or removed later still
     * turns the model over on its own.
     *
     * <p>★ 2026-10-25: this is the property the {@code heating_tunnel_cross} model is hung on. The
     * shape below has a matching {@link #SHAPE_CROSS}; the two were authored together so what is drawn
     * and what is collided with stay the same thing.
     */
    public static final BooleanProperty CROSS = BooleanProperty.create("cross");

    /**
     * Which way the belt below runs, and so which way the tunnel's two openings face. Create's belt
     * tunnel carries the same property for the same reason. The shape below is authored for
     * {@link Direction.Axis#X} and the blockstate file turns it a quarter turn for {@code z}.
     */
    public static final EnumProperty<Direction.Axis> HORIZONTAL_AXIS = BlockStateProperties.HORIZONTAL_AXIS;

    /**
     * The straight tunnel with its bore along X, built out of the same three boxes the model is built out
     * of, so the tunnel a player sees and the tunnel a player bumps into are one and the same shape by
     * construction rather than by eye.
     *
     * <ul>
     *   <li>two side walls, 2/16 thick, reaching 5/16 down into the belt block and up to 10/16;</li>
     *   <li>one roof, filling the rest of the block from 10/16 up to the top.</li>
     * </ul>
     *
     * <p>The numbers are Create's own, read off {@code BeltTunnelShapes}, whose straight tunnel is
     * {@code Shapes.join(block, Shapes.or(opening(SOUTH), opening(NORTH)), ONLY_FIRST)} over
     * {@code block = Block.box(0, -5, 0, 16, 16, 16)} and
     * {@code opening = Block.box(2, -5, 14, 14, 10, 16)}: the bore is 12/16 wide, it starts at the same
     * -5/16 and ends at the same 10/16, and the skirt hangs into the belt block by the same amount. The
     * one difference is that Create carves its openings out of a solid box with a boolean cut and keeps a
     * 2/16 rim across each end, where these three boxes end flush; the bore, the height and the footprint
     * are the same, and those are what the belt underneath can tell the difference by.
     */
    private static final VoxelShape SHAPE_X = Shapes.or(
            Block.box(0.0D, -5.0D, 0.0D, 16.0D, 10.0D, 2.0D),
            Block.box(0.0D, -5.0D, 14.0D, 16.0D, 10.0D, 16.0D),
            Block.box(0.0D, 10.0D, 0.0D, 16.0D, 16.0D, 16.0D));

    /** {@link #SHAPE_X} turned a quarter turn, for a belt that runs along Z: the roof is the same box. */
    private static final VoxelShape SHAPE_Z = Shapes.or(
            Block.box(0.0D, -5.0D, 0.0D, 2.0D, 10.0D, 16.0D),
            Block.box(14.0D, -5.0D, 0.0D, 16.0D, 10.0D, 16.0D),
            Block.box(0.0D, 10.0D, 0.0D, 16.0D, 16.0D, 16.0D));

    /**
     * The crossing: one wall on each of the four edges, and the same roof on top. A belt can run
     * through it either way, so neither axis is the bore's and the blockstate turns it for neither.
     *
     * <p>★ 2026-10-25: built from the four walls of the {@code heating_tunnel_cross} model
     * ({@code x:0~2}, {@code x:14~16}, {@code z:0~2}, {@code z:14~16}, each {@code y:-5~9}) plus the
     * same {@code y:10~16} roof the two straight shapes use, so the crossing a player bumps into and
     * the crossing they see are one shape. The walls stop at {@code y:10} for the same reason the
     * straight ones do - that is where the roof starts.
     */
    private static final VoxelShape SHAPE_CROSS = Shapes.or(
            Block.box(0.0D, -5.0D, 0.0D, 2.0D, 10.0D, 16.0D),
            Block.box(14.0D, -5.0D, 0.0D, 16.0D, 10.0D, 16.0D),
            Block.box(0.0D, -5.0D, 0.0D, 16.0D, 10.0D, 2.0D),
            Block.box(0.0D, -5.0D, 14.0D, 16.0D, 10.0D, 16.0D),
            Block.box(0.0D, 10.0D, 0.0D, 16.0D, 16.0D, 16.0D));

    public TunnelHeaterBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(POWERED, Boolean.FALSE)
                .setValue(ACTIVE, Boolean.FALSE)
                .setValue(CROSS, Boolean.FALSE)
                .setValue(HORIZONTAL_AXIS, Direction.Axis.X));
    }

    @Override
    public Class<TunnelHeaterBlockEntity> getBlockEntityClass() {
        return TunnelHeaterBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends TunnelHeaterBlockEntity> getBlockEntityType() {
        return WoodencogBlockEntityTypes.TUNNEL_HEATER.get();
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED, ACTIVE, CROSS, HORIZONTAL_AXIS);
        super.createBlockStateDefinition(builder);
    }

    /**
     * The shape follows the belt: the bore opens along the axis the belt runs on, which is what the
     * model is rotated to as well, so the box a player bumps into is the tunnel they can see. Only
     * {@code getShape} is overridden, again like Create's belt tunnel, and it is what collision,
     * selection and the visual shape are all taken from.
     *
     * <p>★ 2026-10-25: a crossing has walls on all four edges, so it gets its own shape and ignores the
     * axis - there is no bore to turn.
     */
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (state.getValue(CROSS)) {
            return SHAPE_CROSS;
        }
        return state.getValue(HORIZONTAL_AXIS) == Direction.Axis.X ? SHAPE_X : SHAPE_Z;
    }

    /** Which way the belt below runs, or {@code X} when there is nothing readable to ask. */
    public static Direction.Axis beltAxis(LevelReader level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return AllBlocks.BELT.has(below)
                ? below.getValue(BeltBlock.HORIZONTAL_FACING).getAxis()
                : Direction.Axis.X;
    }

    /**
     * Whether this heater should be a crossing: there is a belt beside it running <em>across</em> the
     * one underneath, so items need to pass through it the other way too.
     *
     * <p>★ 2026-10-25: the rule is "a belt on the left or right side whose axis is not this block's own
     * axis". The belt below this block sets {@link #HORIZONTAL_AXIS}; a belt next to it running the
     * other way means the two cross here. A belt next to it running the <em>same</em> way is just the
     * same belt continuing, which a straight tunnel already handles.
     *
     * <p>Every side belt is also required to be horizontal, on the same terms
     * {@link #isValidPositionForPlacement} requires of the one underneath: a sloped belt cannot carry
     * an item through this block in a straight line, so it does not make a crossing.
     */
    public static boolean isCrossingAt(LevelReader level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        if (!AllBlocks.BELT.has(below)) {
            return false;
        }
        Direction.Axis own = below.getValue(BeltBlock.HORIZONTAL_FACING).getAxis();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (side.getAxis() == own) {
                // straight ahead / behind along the bore is the same belt, never a crossing
                continue;
            }
            BlockState neighbour = level.getBlockState(pos.relative(side));
            if (!AllBlocks.BELT.has(neighbour)) {
                continue;
            }
            if (neighbour.getValue(BeltBlock.SLOPE) != BeltSlope.HORIZONTAL) {
                continue;
            }
            // the belt beside us runs across ours => the two cross under this block
            if (neighbour.getValue(BeltBlock.HORIZONTAL_FACING).getAxis() != own) {
                return true;
            }
        }
        return false;
    }

    /**
     * Create's own belt tunnel placement rule, without the casing requirement: a Create belt below,
     * lying flat.
     */
    public boolean isValidPositionForPlacement(BlockState state, LevelReader level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return AllBlocks.BELT.has(below) && below.getValue(BeltBlock.SLOPE) == BeltSlope.HORIZONTAL;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return this.isValidPositionForPlacement(state, level, pos);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // The signal turns the heater on, so the state is read from the world at once: placed against a
        // live wire it starts running, placed anywhere else it starts switched off. Same as a whistle.
        // The openings are turned to follow the belt the heater was put on, not the player.
        // ★ 2026-10-25: the crossing is worked out here too, so a heater dropped on a belt junction is
        // a crossing from its very first tick rather than after the first neighbour update.
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        return this.defaultBlockState()
                .setValue(POWERED, level.hasNeighborSignal(pos))
                .setValue(CROSS, isCrossingAt(level, pos))
                .setValue(HORIZONTAL_AXIS, beltAxis(level, pos));
    }

    /**
     * Keeps the redstone state and the direction of the bore current, drops the block when the belt that
     * carries it is gone, and tells the block entities around it that the shape of the battery-sharing
     * group may have changed.
     *
     * <p>Setting the state here cannot come back into this method: {@code Level.setBlock} with flag 3
     * notifies the six neighbours of this position, not this position itself, so the powered state is
     * written once per redstone change. This is Create's {@code WhistleBlock#neighborChanged} verbatim,
     * with the belt check of {@code BeltTunnelBlock#neighborChanged} folded in and the two properties
     * written in one call so that a change of both costs one update.
     */
    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
        if (level.isClientSide) {
            return;
        }

        // The belt below is what carries this block. destroyBlock drops the heater itself, which is the
        // self-drop a belt tunnel also relies on; a shape update returning air would remove it silently.
        if (fromPos.equals(pos.below()) && !state.canSurvive(level, pos)) {
            level.destroyBlock(pos, true);
            return;
        }

        BlockState updated = state;
        boolean changed = false;
        boolean powered = level.hasNeighborSignal(pos);
        if (state.getValue(POWERED) != powered) {
            updated = updated.setValue(POWERED, powered);
            changed = true;
        }
        // A belt that was turned, or one that was replaced under the heater, can leave the openings
        // facing across it. The block entity also re-reads this every tick, so an old heater that was
        // saved before this property existed turns itself right on its first tick.
        Direction.Axis axis = beltAxis(level, pos);
        if (state.getValue(HORIZONTAL_AXIS) != axis) {
            updated = updated.setValue(HORIZONTAL_AXIS, axis);
            changed = true;
        }
        // ★ 2026-10-25: a belt laid or removed beside this one changes whether this is a crossing.
        // Belt placement notifies its neighbours, so this fires for the belt that matters.
        boolean cross = isCrossingAt(level, pos);
        if (state.getValue(CROSS) != cross) {
            updated = updated.setValue(CROSS, cross);
            changed = true;
        }
        if (changed) {
            level.setBlock(pos, updated, Block.UPDATE_ALL);
        }

        // Whether two tunnel heaters share one battery depends on which of them are next to each other, so
        // a block placed or broken beside this one can change the group of this one as well: every
        // member of the group is told to look its group up again, not just the neighbour that changed.
        TunnelHeaterBlockEntity.invalidateGroupsAround(level, pos);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        IBE.onRemove(state, level, pos, newState);
    }
}
